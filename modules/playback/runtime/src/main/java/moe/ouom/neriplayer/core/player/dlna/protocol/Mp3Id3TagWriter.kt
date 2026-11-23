package moe.ouom.neriplayer.core.player.dlna.protocol

import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-Kotlin MP3 ID3v2.3 (UTF-16LE BOM) writer that prepends/replaces ID3v2.3
 * TIT2/TPE1/TALB frames while copying every MPEG audio frame byte-for-byte untouched.
 */
object Mp3Id3TagWriter {

    private val attemptCounter = AtomicLong(0L)

    fun writeTags(
        sourceFile: File,
        targetFile: File,
        title: String,
        artist: String,
        album: String
    ): Boolean {
        if (!sourceFile.isFile || sourceFile.length() < 4L) return false
        val id3Tag = WavId3TagBuilder.buildRawId3v23Tag(title, artist, album)
        if (id3Tag.isEmpty()) return false

        val attemptId = attemptCounter.incrementAndGet()
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.mp3_${attemptId}.tmp")
        var succeeded = false
        return try {
            val audioOffset = detectExistingId3v2Length(sourceFile)
            BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024).use { out ->
                out.write(id3Tag)
                RandomAccessFile(sourceFile, "r").use { raf ->
                    val totalLen = raf.length()
                    if (audioOffset > totalLen) {
                        throw EOFException("MP3 audioOffset ($audioOffset) exceeds length ($totalLen)")
                    }
                    raf.seek(audioOffset)
                    var remaining = totalLen - audioOffset
                    val buf = ByteArray(64 * 1024)
                    while (remaining > 0L) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val read = raf.read(buf, 0, want)
                        if (read <= 0) {
                            throw EOFException("Unexpected EOF copying MP3 frames ($remaining bytes remaining)")
                        }
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
                out.flush()
            }
            if (targetFile.exists()) targetFile.delete()
            succeeded = tmpFile.renameTo(targetFile)
            succeeded
        } catch (_: Throwable) {
            false
        } finally {
            if (!succeeded && tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    internal fun detectExistingId3v2Length(file: File): Long {
        if (file.length() < 10L) return 0L
        val header = ByteArray(10)
        FileInputStream(file).use { input ->
            if (input.read(header) != 10) return 0L
        }
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return 0L
        }
        var tagSize = 0L
        for (i in 6..9) {
            val b = header[i].toInt() and 0xFF
            if (b >= 128) return 0L
            tagSize = (tagSize shl 7) or b.toLong()
        }
        val hasFooter = (header[5].toInt() and 0x10) != 0
        val total = 10L + tagSize + (if (hasFooter) 10L else 0L)
        return total.takeIf { it <= file.length() } ?: 0L
    }
}
