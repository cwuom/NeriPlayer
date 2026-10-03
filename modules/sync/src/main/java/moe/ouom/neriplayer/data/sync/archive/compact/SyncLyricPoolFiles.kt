package moe.ouom.neriplayer.data.sync.archive.compact

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.PriorityQueue

internal class SyncLyricPoolFiles(private val directory: File, private val checkActive: () -> Unit) : Closeable {
    data class Ref(val hash: ByteArray, val offset: Long, val length: Int)
    private val bodies = RandomAccessFile(File(directory, "lyric-bodies"), "rw")
    private val occurrencesFile = File(directory, "lyric-occurrences")
    private val occurrences = openOccurrences(occurrencesFile, bodies)
    private var finished = false

    fun add(body: ByteArray): Ref {
        checkActive()
        require(body.size <= SyncCompactWire.BLOCK_BYTES) { "Lyric entry exceeds compact group budget" }
        val ref = Ref(MessageDigest.getInstance("SHA-256").digest(body), bodies.length(), body.size)
        bodies.seek(ref.offset)
        bodies.write(body)
        writeRef(occurrences, ref)
        return ref
    }

    fun read(ref: Ref): ByteArray {
        checkActive()
        val bytes = ByteArray(ref.length)
        bodies.seek(ref.offset)
        bodies.readFully(bytes)
        return bytes
    }

    fun encode(): List<File> {
        finish()
        val sorted = sortOccurrences()
        val writer = BodyGroups()
        var previous: Ref? = null
        DataInputStream(BufferedInputStream(sorted.inputStream())).use { input ->
            while (true) {
                val ref = readRef(input) ?: break
                checkActive()
                val old = previous
                if (old != null && old.hash.contentEquals(ref.hash)) {
                    validateSameBody(old, ref)
                    continue
                }
                writer.add(ref)
                previous = ref
            }
        }
        return writer.finish()
    }

    private fun validateSameBody(left: Ref, right: Ref) {
        require(left.length == right.length) { "Conflicting lyric hash length" }
        require(read(left).contentEquals(read(right))) { "Conflicting lyric hash payload" }
    }

    private inner class BodyGroups {
        private val parts = ArrayList<File>()
        private val group = ArrayList<ByteArray>()
        private var bytes = 0

        fun add(ref: Ref) {
            if (needsFlush(ref)) flush()
            group += read(ref)
            bytes += ref.length
            // 正文哈希形成稳定分组边界，局部插入不会重排后续所有字符表
            if (stableBoundary(ref)) flush()
        }

        private fun needsFlush(ref: Ref): Boolean = group.isNotEmpty() &&
            (bytes.toLong() + ref.length > GROUP_BYTES || group.size >= GROUP_ENTRIES)

        private fun stableBoundary(ref: Ref): Boolean = bytes >= GROUP_BYTES / 2 && ref.hash[0].toInt() and 7 == 0

        private fun flush() {
            writeGroup(group, parts)
            group.clear()
            bytes = 0
        }

        fun finish(): List<File> {
            if (group.isNotEmpty()) flush()
            if (parts.isEmpty()) parts += File(directory, "pool-empty").apply { writeBytes(MAGIC) }
            return parts
        }
    }

    private fun writeGroup(group: List<ByteArray>, parts: MutableList<File>) {
        val encoded = SyncLyricBodyCodec.encode(group, directory, checkActive)
        val length = groupLength(encoded)
        val first = File(directory, "pool-group-${parts.size}")
        first.outputStream().buffered().use { output ->
            if (parts.isEmpty()) output.write(MAGIC)
            SyncCompactWire.writeNumber(output, length)
            encoded.first().inputStream().use { it.copyTo(output) }
        }
        parts += first
        parts += encoded.drop(1)
    }

    private fun groupLength(parts: List<File>): Long {
        val length = parts.sumOf(File::length)
        require(length <= SyncCompactWire.MAX_PART_BYTES) { "Encoded lyric group exceeds safe budget" }
        return length
    }

    private fun finish() {
        if (finished) return
        finished = true
        occurrences.close()
    }

    private fun sortOccurrences(): File {
        var runs = ArrayList<File>()
        DataInputStream(BufferedInputStream(occurrencesFile.inputStream())).use { input ->
            while (true) {
                val entries = ArrayList<Ref>(SORT_ENTRIES)
                while (entries.size < SORT_ENTRIES) entries += readRef(input) ?: break
                if (entries.isEmpty()) break
                checkActive()
                entries.sortWith { left, right -> SyncCompactWire.compare(left.hash, right.hash) }
                val run = File(directory, "pool-sort-0-${runs.size}")
                DataOutputStream(BufferedOutputStream(run.outputStream())).use { output -> entries.forEach { writeRef(output, it) } }
                runs += run
            }
        }
        var generation = 1
        while (runs.size > 1) {
            val next = ArrayList<File>()
            for (batch in runs.chunked(MERGE_FANOUT)) {
                val target = File(directory, "pool-sort-$generation-${next.size}")
                merge(batch, target)
                batch.forEach { require(it.delete()) { "Unable to release lyric sort run" } }
                next += target
            }
            runs = next
            generation++
        }
        return runs.singleOrNull() ?: File(directory, "pool-sort-empty").apply { createNewFile() }
    }

    private fun merge(files: List<File>, target: File) {
        data class Head(val ref: Ref, val index: Int)
        val inputs = ArrayList<DataInputStream>()
        var failure: Throwable? = null
        try {
            files.forEach { inputs += openInput(it) }
            val queue = PriorityQueue<Head> { left, right -> SyncCompactWire.compare(left.ref.hash, right.ref.hash) }
            inputs.forEachIndexed { index, input -> readRef(input)?.let { queue += Head(it, index) } }
            DataOutputStream(BufferedOutputStream(target.outputStream())).use { output ->
                while (queue.isNotEmpty()) {
                    checkActive()
                    val head = queue.remove()
                    writeRef(output, head.ref)
                    readRef(inputs[head.index])?.let { queue += Head(it, head.index) }
                }
            }
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            closeAll(inputs, failure)
        }
    }

    override fun close() {
        closeAll(listOf(Closeable { finish() }, bodies), null)
    }

    class Index internal constructor(private val data: RandomAccessFile, private val index: RandomAccessFile) : Closeable {
        fun find(hash: ByteArray, length: Int): ByteArray {
            var low = 0L
            var high = index.length() / INDEX_BYTES - 1
            while (low <= high) {
                val middle = low + (high - low) / 2
                index.seek(middle * INDEX_BYTES)
                val stored = ByteArray(32).also(index::readFully)
                val order = SyncCompactWire.compare(stored, hash)
                when {
                    order < 0 -> low = middle + 1
                    order > 0 -> high = middle - 1
                    else -> {
                        val offset = index.readLong()
                        require(index.readInt() == length) { "Lyric reference length mismatch" }
                        data.seek(offset)
                        return ByteArray(length).also(data::readFully)
                    }
                }
            }
            error("Missing shared lyric body")
        }

        override fun close() {
            closeAll(listOf(data, index), null)
        }
    }

    companion object {
        private val MAGIC = "NPBODY01".toByteArray(Charsets.US_ASCII)
        private const val GROUP_BYTES = 4 * 1024 * 1024
        private const val GROUP_ENTRIES = 4096
        private const val SORT_ENTRIES = 16384
        private const val MERGE_FANOUT = 32
        private const val INDEX_BYTES = 44L

        fun decode(input: InputStream, directory: File, maximumBytes: Long, checkActive: () -> Unit): Index {
            require(SyncCompactWire.bytes(input, MAGIC.size).contentEquals(MAGIC)) { "Unknown compact lyric pool version" }
            val dataFile = File(directory, "pool-restored-bodies")
            val indexFile = File(directory, "pool-restored-index")
            DataOutputStream(BufferedOutputStream(indexFile.outputStream())).use { index ->
                BufferedOutputStream(dataFile.outputStream()).use { data ->
                    val writer = BodyIndexWriter(index, data, maximumBytes, checkActive)
                    while (true) {
                        checkActive()
                        val first = input.read()
                        if (first < 0) break
                        val bodies = readGroup(input, first, directory, checkActive)
                        bodies.forEach(writer::write)
                    }
                }
            }
            return openIndex(dataFile, indexFile)
        }

        private fun readGroup(input: InputStream, first: Int, directory: File, checkActive: () -> Unit): List<ByteArray> {
            val prefix = object : InputStream() {
                var pending = first
                override fun read(): Int = if (pending >= 0) pending.also { pending = -1 } else input.read()
            }
            val length = SyncCompactWire.count(prefix, SyncCompactWire.MAX_PART_BYTES)
            val group = LimitedInput(input, length.toLong())
            // 正文 codec 统一检查组内条目预算和精确 EOF，外层只限制帧边界
            return SyncLyricBodyCodec.decode(group, directory, checkActive)
        }

        private class BodyIndexWriter(private val index: DataOutputStream, private val data: BufferedOutputStream,
                                      private val maximum: Long, private val checkActive: () -> Unit) {
            private var previousHash: ByteArray? = null
            private var restored = 0L

            fun write(body: ByteArray) {
                checkActive()
                require(restored <= maximum - body.size) { "Shared lyric pool exceeds original stream budget" }
                val hash = MessageDigest.getInstance("SHA-256").digest(body)
                val previous = previousHash
                if (previous != null) require(SyncCompactWire.compare(previous, hash) < 0) { "Duplicate or unordered shared lyric body" }
                writeRef(index, Ref(hash, restored, body.size))
                data.write(body)
                restored += body.size
                previousHash = hash
            }
        }

        private fun openIndex(dataFile: File, indexFile: File): Index {
            val data = RandomAccessFile(dataFile, "r")
            try {
                return Index(data, RandomAccessFile(indexFile, "r"))
            } catch (failure: Throwable) {
                closeAll(listOf(data), failure)
                throw failure
            }
        }

        private fun openOccurrences(file: File, bodies: RandomAccessFile): DataOutputStream {
            var output: java.io.FileOutputStream? = null
            try {
                output = file.outputStream()
                return DataOutputStream(BufferedOutputStream(output))
            } catch (failure: Throwable) {
                closeAll(listOfNotNull(output, bodies), failure)
                throw failure
            }
        }

        private fun openInput(file: File): DataInputStream {
            val source = file.inputStream()
            try {
                return DataInputStream(BufferedInputStream(source))
            } catch (failure: Throwable) {
                closeAll(listOf(source), failure)
                throw failure
            }
        }

        private fun closeAll(resources: List<Closeable>, primary: Throwable?) {
            var failure = primary
            for (resource in resources) {
                try { resource.close() } catch (next: Throwable) { failure = appendFailure(failure, next) }
            }
            if (primary == null && failure != null) throw failure
        }

        private fun appendFailure(primary: Throwable?, next: Throwable): Throwable {
            if (primary == null) return next
            if (primary !== next) primary.addSuppressed(next)
            return primary
        }

        private fun writeRef(output: DataOutputStream, ref: Ref) {
            output.write(ref.hash)
            output.writeLong(ref.offset)
            output.writeInt(ref.length)
        }

        private fun readRef(input: DataInputStream): Ref? {
            val first = input.read()
            if (first < 0) return null
            val hash = ByteArray(32)
            hash[0] = first.toByte()
            input.readFully(hash, 1, 31)
            return Ref(hash, input.readLong(), input.readInt())
        }
    }
}

internal class LimitedInput(private val source: InputStream, var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = source.read()
        require(value >= 0) { "Truncated compact frame" }
        remaining--
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        require(count >= 0) { "Truncated compact frame" }
        remaining -= count
        return count
    }
}
