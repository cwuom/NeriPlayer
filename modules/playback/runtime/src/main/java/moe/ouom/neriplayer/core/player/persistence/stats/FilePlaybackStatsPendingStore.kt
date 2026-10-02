package moe.ouom.neriplayer.core.player.persistence.stats

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsPendingStore
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot

internal class FilePlaybackStatsPendingStore(
    private val directory: File,
    private val writeCursor: (File, String) -> Unit = { file, text -> file.writeTextAtomically(text) },
    private val syncFrame: (File) -> Unit = { file -> RandomAccessFile(file, "rw").use { it.fd.sync() } },
    private val writeFrame: (File, ByteArray) -> Unit = { file, bytes ->
        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { it.write(bytes) }
    },
    private val maxJournalBytes: Long = 64L * 1024 * 1024,
    private val maxJournalFrames: Int = 8192
) : PlaybackStatsPendingStore, Closeable {
    private val lock = Any()
    private var ownerChannel: FileChannel? = null
    private var ownerLock: FileLock? = null
    private var ownerKey: String? = null

    init {
        require(maxJournalBytes > 0) { "Playback journal byte budget must be positive" }
        require(maxJournalFrames > 0) { "Playback journal frame budget must be positive" }
    }

    override fun append(snapshot: PlaybackStatsSnapshot) = synchronized(lock) {
        val payload = PlaybackStatsJournalCodec.payload(snapshot)
        val hash = PlaybackStatsJournalCodec.hash(payload)
        openOwner()
        var cursor = recoverTail(readCursor())
        if (cursor.lastEventId == snapshot.eventId) {
            if (cursor.lastHash != hash) throw IOException("Playback event identity reused with different journal content")
            // 完整读回不能代替刷盘，返回失败后的重试仍须再次确认持久化
            val last = frameFile(cursor.tail - 1)
            if (last.exists()) { readFrame(last); syncFrame(last) }
            syncFrame(cursorFile())
            return@synchronized
        }
        if (cursor.tail == Long.MAX_VALUE) throw IOException("Playback journal sequence exhausted")
        // 确认但尚未删除的帧不能被当作已腾出的磁盘空间
        cursor = cleanup(cursor)
        val frame = PlaybackStatsJournalCodec.frame(payload)
        requireCapacity(cursor, frame.size)
        val target = frameFile(cursor.tail)
        rejectSymlink(target)
        publishFrame(target, frame)
        cursor = cursor.copy(tail = cursor.tail + 1, lastEventId = snapshot.eventId, lastHash = hash)
        saveCursor(cursor)
    }

    override fun first(): PlaybackStatsSnapshot? = synchronized(lock) {
        openOwner()
        val cursor = recoverTail(readCursor())
        cleanup(cursor)
        if (cursor.head == cursor.tail) null else readFrame(frameFile(cursor.head))
    }

    override fun acknowledge(eventId: String) {
        synchronized(lock) {
            openOwner()
            val cursor = readCursor()
            if (cursor.head == cursor.tail) throw IOException("Playback journal has no event to acknowledge")
            if (readFrame(frameFile(cursor.head)).eventId != eventId) throw IOException("Playback journal acknowledgement is out of order")
            val advanced = cursor.copy(head = cursor.head + 1)
            saveCursor(advanced)
            cleanup(advanced)
        }
    }

    private fun requireCapacity(cursor: Cursor, incomingBytes: Int) {
        if (cursor.tail - cursor.head >= maxJournalFrames) throw IOException("Playback journal frame budget exhausted")
        var remaining = maxJournalBytes - incomingBytes
        if (remaining < 0) throw IOException("Playback journal byte budget exhausted")
        var sequence = cursor.head
        // 先检查帧数，旧的超额队列仍可重放，预算检查不会无限遍历
        while (sequence < cursor.tail) {
            val file = frameFile(sequence)
            rejectSymlink(file)
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Playback journal frame is unavailable")
            }
            val size = Files.size(file.toPath())
            if (size > remaining) throw IOException("Playback journal byte budget exhausted")
            remaining -= size
            sequence++
        }
    }

    private fun publishFrame(target: File, frame: ByteArray) {
        val temporary = File(directory, ".npst-v1-frame-${UUID.randomUUID()}.tmp")
        try {
            writeFrame(temporary, frame)
            syncFrame(temporary)
            // 未完成的写入不能占据正式尾帧，否则进程退出后会堵住已确认的前缀
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    private fun recoverTail(initial: Cursor): Cursor {
        var cursor = initial
        val candidate = frameFile(cursor.tail)
        if (!candidate.exists()) return cursor
        rejectSymlink(candidate)
        val payload = PlaybackStatsJournalCodec.readPayload(candidate)
        val event = PlaybackStatsJournalCodec.read(candidate)
        syncFrame(candidate)
        if (cursor.tail == Long.MAX_VALUE) throw IOException("Playback journal sequence exhausted")
        cursor = cursor.copy(tail = cursor.tail + 1, lastEventId = event.eventId,
            lastHash = PlaybackStatsJournalCodec.hash(payload))
        saveCursor(cursor)
        return cursor
    }

    private fun cleanup(initial: Cursor): Cursor {
        var cleaned = initial.cleaned
        while (cleaned < initial.head) {
            val file = frameFile(cleaned)
            rejectSymlink(file)
            if (file.exists() && !file.delete()) throw IOException("Cannot remove an acknowledged playback journal frame")
            cleaned++
        }
        return initial.copy(cleaned = cleaned).also {
            if (cleaned != initial.cleaned) saveCursor(it)
        }
    }

    private fun readFrame(file: File): PlaybackStatsSnapshot {
        rejectSymlink(file)
        return PlaybackStatsJournalCodec.read(file)
    }

    private fun readCursor(): Cursor {
        val file = cursorFile()
        rejectSymlink(file)
        if (!file.exists()) {
            Files.newDirectoryStream(directory.toPath(), "*.delta").use {
                if (it.iterator().hasNext()) throw IOException("Playback journal frames have no trusted cursor")
            }
            return Cursor(0, 0, 0, null, null).also(::saveCursor)
        }
        if (file.length() > MAX_CURSOR_BYTES) throw IOException("Playback journal cursor exceeds its budget")
        try {
            val value = JsonParser.parseString(file.readText()).asJsonObject
            if (value.get("version").asString != "1") throw IOException("Unsupported playback journal cursor version")
            val cursor = Cursor(value.get("head").asString.toLong(), value.get("tail").asString.toLong(),
                value.get("cleaned").asString.toLong(), nullableText(value, "lastEventId"), nullableText(value, "lastHash"))
            if (cursor.cleaned < 0 || cursor.cleaned > cursor.head || cursor.head > cursor.tail) throw IOException("Invalid playback journal cursor order")
            val actual = value.get("checksum").asString
            if (actual != PlaybackStatsJournalCodec.hash(cursorPayload(cursor).toString().toByteArray(Charsets.UTF_8))) {
                throw IOException("Playback journal cursor checksum mismatch")
            }
            return cursor
        } catch (error: RuntimeException) {
            throw IOException("Malformed playback journal cursor", error)
        }
    }

    private fun saveCursor(cursor: Cursor) {
        rejectSymlink(cursorFile())
        val value = cursorPayload(cursor)
        value.addProperty("checksum", PlaybackStatsJournalCodec.hash(value.toString().toByteArray(Charsets.UTF_8)))
        writeCursor(cursorFile(), value.toString())
    }

    private fun cursorPayload(cursor: Cursor) = JsonObject().apply {
        addProperty("version", 1)
        addProperty("head", cursor.head)
        addProperty("tail", cursor.tail)
        addProperty("cleaned", cursor.cleaned)
        addProperty("lastEventId", cursor.lastEventId)
        addProperty("lastHash", cursor.lastHash)
    }

    private fun nullableText(value: JsonObject, key: String): String? = value.get(key).let {
        if (it.isJsonNull) null else it.asString
    }

    private fun cursorFile() = File(directory, "cursor.json")
    private fun frameFile(sequence: Long) = File(directory, sequence.toString().padStart(20, '0') + ".delta")

    private fun rejectSymlink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("Playback journal cannot follow a symbolic link")
    }

    private fun openOwner() {
        if (ownerChannel != null) return
        rejectSymlink(directory)
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Playback journal directory unavailable")
        val key = directory.canonicalPath
        synchronized(activeOwners) {
            if (key in activeOwners) throw IOException("Playback journal is already in use")
            val file = File(directory, ".owner")
            rejectSymlink(file)
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)
            try {
                val held = channel.tryLock() ?: throw IOException("Playback journal is owned by another process")
                ownerChannel = channel
                ownerLock = held
                ownerKey = key
                activeOwners.add(key)
            } catch (failure: Exception) {
                try { channel.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        cleanupUnpublishedFrames()
    }

    private fun cleanupUnpublishedFrames() {
        Files.newDirectoryStream(directory.toPath(), ".npst-v1-frame-*.tmp").use { paths ->
            for (path in paths) {
                val name = path.fileName.toString()
                val id = name.removePrefix(".npst-v1-frame-").removeSuffix(".tmp")
                if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) &&
                    !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            synchronized(activeOwners) {
                var failure: Exception? = null
                try { ownerLock?.release() } catch (error: Exception) { failure = error }
                try { ownerChannel?.close() } catch (error: Exception) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                } finally {
                    ownerKey?.let(activeOwners::remove)
                    ownerLock = null
                    ownerChannel = null
                    ownerKey = null
                }
                failure?.let { throw it }
            }
        }
    }

    private data class Cursor(val head: Long, val tail: Long, val cleaned: Long,
        val lastEventId: String?, val lastHash: String?)

    companion object {
        private const val MAX_CURSOR_BYTES = 4096L
        private val activeOwners = mutableSetOf<String>()
    }
}
