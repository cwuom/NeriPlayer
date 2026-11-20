package moe.ouom.neriplayer.core.player.dlna.protocol

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Pure-Kotlin MP3 ID3v2.3 (UTF-16LE BOM) writer that prepends/replaces ID3v2.3
 * TIT2/TPE1/TALB frames while copying every MPEG audio frame byte-for-byte untouched.
 */
object Mp3Id3TagWriter {

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

        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.mp3_tmp")
        return try {
            val audioOffset = detectExistingId3v2Length(sourceFile)
            BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024).use { out ->
                out.write(id3Tag)
                FileInputStream(sourceFile).use { input ->
                    var skipped = 0L
                    while (skipped < audioOffset) {
                        val s = input.skip(audioOffset - skipped)
                        if (s <= 0L) break
                        skipped += s
                    }
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
                out.flush()
            }
            if (targetFile.exists()) targetFile.delete()
            tmpFile.renameTo(targetFile)
        } catch (_: Throwable) {
            tmpFile.delete()
            false
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
