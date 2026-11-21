package moe.ouom.neriplayer.core.player.lan.relay

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

internal class LanSpoolSeekOutOfSpace : IOException("LOCAL_STORAGE")
internal class LanSpoolReadCancelled : IOException("LOCAL_TRANSFER")

/** 没有强校验器时只从一次 HTTP 响应取字节，成员回跳时读取已收到的部分 */
internal class LanRelaySpool(
    private val cacheDir: File,
    private val input: InputStream,
    private val expected: Long?,
) : Closeable {
    private val path = File.createTempFile("lan-audio-", ".tmp", cacheDir)
    private val file = try {
        RandomAccessFile(path, "rw").also { path.delete() }
    } catch (failure: Throwable) {
        path.delete()
        throw failure
    }
    private val chunk = ByteArray(64 * 1024)
    private val writer = Any()
    @Volatile private var stored = 0L
    @Volatile private var eof = false
    @Volatile private var failure: IOException? = null
    @Volatile private var closed = false
    private var checkSpaceAt = 0L
    val knownLength: Long? get() = expected ?: stored.takeIf { eof && failure == null }

    fun cursor(offset: Long, limit: Long? = null): InputStream = object : InputStream() {
        private var position = offset
        private var checkedSeek = false
        @Volatile private var cancelled = false
        override fun close() { cancelled = true }
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val left = (limit ?: Long.MAX_VALUE) - (position - offset)
            if (left <= 0) return -1
            if (!checkedSeek) {
                val gap = position - stored
                if (gap > SPACE_CHECK_BYTES && gap >= availableSpace()) throw LanSpoolSeekOutOfSpace()
                checkedSeek = true
            }
            while (true) {
                if (cancelled) throw LanSpoolReadCancelled()
                synchronized(this@LanRelaySpool) {
                    if (closed) throw IOException("LOCAL_TRANSFER")
                    if (position < stored) {
                        file.seek(position)
                        val count = minOf(len.toLong(), stored - position, left).toInt()
                        file.readFully(buffer, off, count)
                        position += count
                        return count
                    }
                    failure?.let { throw it }
                    if (eof) return -1
                }
                extendTo(position) { cancelled }
            }
        }
    }

    private fun extendTo(position: Long, cancelled: () -> Boolean) = synchronized(writer) {
        if (cancelled()) throw LanSpoolReadCancelled()
        if (closed) throw IOException("LOCAL_TRANSFER")
        if (stored > position || eof) return@synchronized
        failure?.let { throw it }
        try {
            append()
        } catch (error: IOException) {
            failure = error
            throw error
        }
    }

    private fun append() {
        if (stored >= checkSpaceAt) {
            val available = availableSpace()
            if (available <= 0) throw IOException("LOCAL_STORAGE")
            checkSpaceAt = stored + minOf(SPACE_CHECK_BYTES, available)
        }
        val count = input.read(chunk, 0, minOf(chunk.size.toLong(), checkSpaceAt - stored).toInt())
        if (count < 0) {
            eof = true
            input.close()
            if (expected != null && stored < expected) throw EOFException("SOURCE_CHANGED")
            return
        }
        if (count == 0) throw IOException("LOCAL_TRANSFER")
        synchronized(this) {
            if (closed) throw IOException("LOCAL_TRANSFER")
            try {
                file.seek(stored)
                file.write(chunk, 0, count)
            } catch (error: IOException) {
                throw IOException("LOCAL_STORAGE", error)
            }
            stored += count
        }
        if (stored == expected) {
            eof = true
            input.close()
        }
    }

    private fun availableSpace() = cacheDir.usableSpace - 16L * 1024 * 1024

    private companion object {
        const val SPACE_CHECK_BYTES = 4L * 1024 * 1024
    }

    override fun close() {
        try {
            synchronized(this) {
                if (closed) return
                closed = true
                try { file.close() } finally { if (path.exists()) path.delete() }
            }
        } finally {
            synchronized(writer) { input.close() }
        }
    }
}
