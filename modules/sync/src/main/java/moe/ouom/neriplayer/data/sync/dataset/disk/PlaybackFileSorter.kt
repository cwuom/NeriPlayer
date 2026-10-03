package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.PriorityQueue

internal class PlaybackFileSorter<T>(
    private val directory: File, private val prefix: String,
    private val codec: PlaybackRecordCodec<T>, private val dayFirst: Boolean
) : PlaybackStagingWriter<T> {
    private val buffer = ArrayList<PlaybackRecord<T>>()
    private var bufferedBytes = 0L
    private var runs = 0
    private var generation = 0
    private var monotonic = true
    private var prefixOutput: PlaybackFileWriter? = null
    private var previousIdentity: ByteArray? = null
    private var previousDay = 0L
    private var closed = false
    private var failed = false
    private var finished = false
    private val createdFiles = LinkedHashSet<File>()
    private val order = Comparator<PlaybackRecord<T>> { left, right ->
        compareOrder(left.identity, left.day, right.identity, right.day)
    }

    override suspend fun append(value: T) = appendChecked { codec.record(value) }

    internal suspend fun appendValidatedRecord(record: PlaybackRecord<T>) = appendChecked {
        // reader.next 已验证 key 和 day，这里保留原始 payload，避免再次编码
        record
    }

    private suspend fun appendChecked(record: () -> PlaybackRecord<T>) {
        checkWritable()
        try {
            currentCoroutineContext().ensureActive()
            appendRecord(record())
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    private fun appendRecord(record: PlaybackRecord<T>) {
        require(record.payload.size <= MAX_PLAYBACK_RECORD_BYTES) { "Playback record exceeds decoding budget" }
        if (monotonic && appendPrefix(record)) return
        appendBufferedRecord(record)
    }

    private fun appendBufferedRecord(record: PlaybackRecord<T>) {
        if (buffer.isNotEmpty() && bufferedBytes + record.payload.size > SORT_BYTES) flush()
        buffer.add(record)
        bufferedBytes += record.payload.size
        if (buffer.size == SORT_RECORDS) flush()
    }

    private fun appendPrefix(record: PlaybackRecord<T>): Boolean {
        val previous = previousIdentity
        if (previous != null && compareOrder(previous, previousDay, record.identity, record.day) >= 0) {
            // 已写前缀本身有序，直接成为首个 run，保留重复记录的原始先后顺序
            val file = finishPrefix()
            saveMetadata(file)
            runs = 1
            monotonic = false
            return false
        }
        prefixWriter().write(record)
        previousIdentity = record.identity
        previousDay = record.day
        return true
    }

    private fun compareOrder(leftIdentity: ByteArray, leftDay: Long, rightIdentity: ByteArray, rightDay: Long): Int {
        val day = leftDay.compareTo(rightDay)
        if (dayFirst && day != 0) return day
        val identity = SyncPlaybackKeyOrder.compareBytes(leftIdentity, rightIdentity)
        return identity.takeIf { it != 0 } ?: day
    }

    private fun prefixWriter(): PlaybackFileWriter =
        prefixOutput ?: createWriter(path(0, 0)).also { prefixOutput = it }

    private fun createWriter(file: File): PlaybackFileWriter {
        createOwnedFile(file)
        return PlaybackFileWriter(file)
    }

    private fun createOwnedFile(file: File) {
        // 只登记本次成功创建的文件，失败清理不能碰预先存在的文件或目录
        Files.createFile(file.toPath())
        createdFiles.add(file)
    }

    private fun finishPrefix(): PlaybackFile = prefixWriter().use { it.finish() }.also {
        prefixOutput = null
        previousIdentity = null
    }

    private fun path(generation: Int, run: Int): File = File(directory, "$prefix-$generation-$run.bin")
    private fun metadata(file: File) = File(directory, file.name + ".meta")

    private fun flush() {
        if (buffer.isEmpty()) return
        buffer.sortWith(order)
        val file = path(generation, runs++)
        val result = createWriter(file).use { writer -> buffer.forEach { writer.write(it) }; writer.finish() }
        saveMetadata(result)
        buffer.clear()
        bufferedBytes = 0L
    }

    override suspend fun finish(): PlaybackFile {
        checkWritable()
        try {
            currentCoroutineContext().ensureActive()
            val result = if (monotonic) finishPrefix() else finishRuns()
            currentCoroutineContext().ensureActive()
            finished = true
            return result
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    private suspend fun finishRuns(): PlaybackFile {
        flush()
        while (runs > 1) {
            var outputRun = 0
            var first = 0
            while (first < runs) {
                currentCoroutineContext().ensureActive()
                mergeRuns(first, minOf(first + MERGE_FANOUT, runs), path(generation + 1, outputRun++))
                first += MERGE_FANOUT
            }
            generation++
            runs = outputRun
        }
        return readMetadata(path(generation, 0))
    }

    private fun checkWritable() {
        check(!closed) { "Playback sorter is already closed" }
        check(!failed) { "Playback sorter cannot continue after failure" }
        check(!finished) { "Playback sorter is already finished" }
    }

    private suspend fun mergeRuns(first: Int, end: Int, output: File) {
        val readers = ArrayList<PlaybackFileReader<T>>()
        val queue = PriorityQueue<Pair<Int, PlaybackRecordHeader>> { left, right ->
            val comparison = compareHeaders(left.second, right.second)
            comparison.takeIf { it != 0 } ?: left.first.compareTo(right.first)
        }
        try {
            for (index in first until end) {
                val reader = PlaybackFileReader(readMetadata(path(generation, index)), codec)
                readers.add(reader)
                reader.nextHeader()?.let { queue.add(readers.lastIndex to it) }
            }
            val result = createWriter(output).use { writer ->
                var count = 0L
                while (queue.isNotEmpty()) {
                    if (count++ % SYNC_PLAYBACK_PAGE_RECORDS == 0L) currentCoroutineContext().ensureActive()
                    val (readerIndex, record) = queue.remove()
                    writer.copy(readers[readerIndex], record)
                    readers[readerIndex].nextHeader()?.let { queue.add(readerIndex to it) }
                }
                writer.finish()
            }
            saveMetadata(result)
        } finally { readers.forEach { it.close() } }
        discardRuns(first, end)
    }

    private fun compareHeaders(left: PlaybackRecordHeader, right: PlaybackRecordHeader): Int {
        val day = left.day.compareTo(right.day)
        if (dayFirst && day != 0) return day
        val key = left.compareKey(right)
        return key.takeIf { it != 0 } ?: day
    }

    private fun discardRuns(first: Int, end: Int) {
        for (index in first until end) {
            val file = path(generation, index)
            discardCreatedFile(file)
            discardCreatedFile(metadata(file))
        }
    }

    private fun discardCreatedFile(file: File) {
        check(file in createdFiles) { "Playback sorting file is not owned by this writer" }
        if (!file.delete()) throw IOException("Unable to release playback sorting run")
        createdFiles.remove(file)
    }

    private fun saveMetadata(file: PlaybackFile) {
        val target = metadata(file.file)
        createOwnedFile(target)
        FileOutputStream(target).use { stream ->
            val output = DataOutputStream(stream)
            output.writeLong(file.records); output.writeLong(file.bytes); output.write(file.hash)
            output.flush(); stream.fd.sync()
        }
    }

    private fun readMetadata(file: File): PlaybackFile = DataInputStream(FileInputStream(metadata(file)).buffered(METADATA_BYTES)).use {
        val result = PlaybackFile(file, it.readLong(), it.readLong(), ByteArray(32).also(it::readFully))
        validateMetadataTotal(result.records)
        validateMetadataTotal(result.bytes)
        require(it.read() == -1) { "Unexpected playback staging metadata suffix" }
        result
    }
    private fun validateMetadataTotal(total: Long) = require(total >= 0L) { "Negative playback staging metadata total" }

    private fun releaseUnfinishedFiles() {
        if (finished) return
        var failure: Throwable? = null
        for (file in createdFiles) {
            try {
                Files.deleteIfExists(file.toPath())
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            Closeable { releaseUnfinishedFiles() }.use { prefixOutput?.close() }
        } finally {
            prefixOutput = null
            previousIdentity = null
            buffer.clear()
            bufferedBytes = 0L
            createdFiles.clear()
        }
    }

    private companion object { const val METADATA_BYTES = 48 }
}
