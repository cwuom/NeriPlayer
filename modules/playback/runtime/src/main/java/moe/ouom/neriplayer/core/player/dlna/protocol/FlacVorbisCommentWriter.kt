package moe.ouom.neriplayer.core.player.dlna.protocol

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Pure-Kotlin lossless FLAC METADATA_BLOCK_VORBIS_COMMENT writer.
 * Inserts or updates TITLE, ARTIST, and ALBUM tags in a staged FLAC file while
 * leaving STREAMINFO and all compressed audio frames 100% byte-for-byte untouched.
 */
object FlacVorbisCommentWriter {

    private const val BLOCK_TYPE_STREAMINFO = 0
    private const val BLOCK_TYPE_PADDING = 1
    private const val BLOCK_TYPE_VORBIS_COMMENT = 4

    fun writeTags(
        sourceFile: File,
        targetFile: File,
        title: String,
        artist: String,
        album: String
    ): Boolean {
        if (!sourceFile.isFile || sourceFile.length() < 42L) return false
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.flac_tmp")
        return try {
            val preservedBlocks = mutableListOf<Pair<Int, ByteArray>>()
            var existingComments = emptyList<String>()
            var audioFramesOffset = -1L

            RandomAccessFile(sourceFile, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "fLaC") return false

                while (true) {
                    val hdr = ByteArray(4)
                    if (raf.read(hdr) != 4) return false
                    val isLast = (hdr[0].toInt() and 0x80) != 0
                    val blockType = hdr[0].toInt() and 0x7F
                    val blockLength = ((hdr[1].toInt() and 0xFF) shl 16) or
                        ((hdr[2].toInt() and 0xFF) shl 8) or
                        (hdr[3].toInt() and 0xFF)
                    if (blockLength < 0 || raf.filePointer + blockLength > raf.length()) return false
                    val payload = ByteArray(blockLength)
                    raf.readFully(payload)

                    when (blockType) {
                        BLOCK_TYPE_PADDING -> Unit // skip padding
                        BLOCK_TYPE_VORBIS_COMMENT -> existingComments = parseVorbisComments(payload)
                        else -> preservedBlocks += blockType to payload
                    }
                    if (isLast) {
                        audioFramesOffset = raf.filePointer
                        break
                    }
                }
            }

            if (audioFramesOffset <= 0L || preservedBlocks.none { it.first == BLOCK_TYPE_STREAMINFO }) {
                return false
            }

            val vorbisPayload = buildVorbisCommentPayload(
                existingComments = existingComments,
                title = title,
                artist = artist,
                album = album
            )

            BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024).use { out ->
                out.write("fLaC".toByteArray(Charsets.US_ASCII))
                for ((blockType, payload) in preservedBlocks) {
                    writeMetadataBlock(out, isLast = false, blockType = blockType, payload = payload)
                }
                writeMetadataBlock(out, isLast = true, blockType = BLOCK_TYPE_VORBIS_COMMENT, payload = vorbisPayload)

                FileInputStream(sourceFile).use { input ->
                    var skipped = 0L
                    while (skipped < audioFramesOffset) {
                        val s = input.skip(audioFramesOffset - skipped)
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

    private fun parseVorbisComments(payload: ByteArray): List<String> {
        if (payload.size < 8) return emptyList()
        val vendorLen = readLe32(payload, 0)
        var pos = 4L + vendorLen
        if (pos + 4 > payload.size) return emptyList()
        val count = readLe32(payload, pos.toInt())
        pos += 4
        val result = ArrayList<String>(count.toInt().coerceIn(0, 64))
        for (i in 0 until count.toInt().coerceIn(0, 256)) {
            if (pos + 4 > payload.size) break
            val len = readLe32(payload, pos.toInt())
            pos += 4
            if (len < 0 || pos + len > payload.size) break
            result += String(payload, pos.toInt(), len.toInt(), Charsets.UTF_8)
            pos += len
        }
        return result
    }

    private fun buildVorbisCommentPayload(
        existingComments: List<String>,
        title: String,
        artist: String,
        album: String
    ): ByteArray {
        val managedKeys = setOf("TITLE", "ARTIST", "ALBUM", "ALBUMARTIST")
        val merged = existingComments.filterNot { entry ->
            val key = entry.substringBefore('=').trim().uppercase()
            key in managedKeys
        }.toMutableList()

        if (title.isNotBlank()) merged += "TITLE=$title"
        if (artist.isNotBlank()) {
            merged += "ARTIST=$artist"
            merged += "ALBUMARTIST=$artist"
        }
        if (album.isNotBlank()) merged += "ALBUM=$album"

        val out = ByteArrayOutputStream()
        val vendor = "NeriPlayer".toByteArray(Charsets.UTF_8)
        writeLe32(out, vendor.size.toLong())
        out.write(vendor)
        writeLe32(out, merged.size.toLong())
        for (comment in merged) {
            val utf8 = comment.toByteArray(Charsets.UTF_8)
            writeLe32(out, utf8.size.toLong())
            out.write(utf8)
        }
        return out.toByteArray()
    }

    private fun writeMetadataBlock(
        out: BufferedOutputStream,
        isLast: Boolean,
        blockType: Int,
        payload: ByteArray
    ) {
        val flagByte = (if (isLast) 0x80 else 0x00) or (blockType and 0x7F)
        val len = payload.size
        out.write(flagByte)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(payload)
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun readLe32(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
