package moe.ouom.neriplayer.core.player.dlna.protocol

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Pure-Kotlin ISO BMFF (M4A/MP4) faststart iTunes metadata (moov/udta/meta/ilst) writer.
 * Inserts/updates ©nam, ©ART, aART, and ©alb atoms while keeping mdat audio samples
 * 100% untouched and adjusting stco/co64 chunk offsets accurately.
 */
object Mp4MetadataWriter {

    private const val MAX_MOOV_BYTES = 32L * 1024L * 1024L

    private data class BoxRange(
        val type: String,
        val startOffset: Long,
        val size: Long,
        val headerSize: Int
    )

    fun hasMoofBox(file: File): Boolean {
        if (!file.isFile || file.length() < 8L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                readTopLevelBoxes(raf).any { it.type == "moof" }
            }
        }.getOrDefault(false)
    }

    fun writeTags(
        sourceFile: File,
        targetFile: File,
        title: String,
        artist: String,
        album: String
    ): Boolean {
        if (!sourceFile.isFile || sourceFile.length() < 16L) return false
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.m4a_tmp")
        return try {
            val boxes = RandomAccessFile(sourceFile, "r").use { raf -> readTopLevelBoxes(raf) }
            if (boxes.any { it.type == "moof" }) return false // fragmented MP4 must be defragmented first
            val ftypBox = boxes.firstOrNull { it.type == "ftyp" } ?: return false
            val moovBox = boxes.firstOrNull { it.type == "moov" } ?: return false
            val mdatBox = boxes.firstOrNull { it.type == "mdat" } ?: return false
            if (moovBox.size > MAX_MOOV_BYTES || ftypBox.size > 1024L * 1024L) return false

            val ftypBytes = readBoxBytes(sourceFile, ftypBox)
            val originalMoovBytes = readBoxBytes(sourceFile, moovBox)
            val originalMoovPayload = originalMoovBytes.copyOfRange(moovBox.headerSize, originalMoovBytes.size)

            val updatedMoovPayloadNoShift = updateMoovPayloadWithUdta(
                moovPayload = originalMoovPayload,
                title = title,
                artist = artist,
                album = album
            )
            val newMoovTotalSize = 8L + updatedMoovPayloadNoShift.size.toLong()
            val newMdatStartOffset = ftypBytes.size.toLong() + newMoovTotalSize
            val mdatShift = newMdatStartOffset - mdatBox.startOffset

            val finalMoovPayload = shiftChunkOffsetsInMoov(updatedMoovPayloadNoShift, mdatShift)
            val finalMoovBox = wrapBox("moov", finalMoovPayload)

            BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024).use { out ->
                out.write(ftypBytes)
                out.write(finalMoovBox)
                copyRange(sourceFile, mdatBox.startOffset, mdatBox.size, out)
                for (box in boxes) {
                    if (box.type !in setOf("ftyp", "moov", "mdat", "free", "skip")) {
                        copyRange(sourceFile, box.startOffset, box.size, out)
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

    private fun readTopLevelBoxes(raf: RandomAccessFile): List<BoxRange> {
        val totalLen = raf.length()
        val result = mutableListOf<BoxRange>()
        var offset = 0L
        val hdr = ByteArray(16)
        while (offset + 8L <= totalLen) {
            raf.seek(offset)
            raf.readFully(hdr, 0, 8)
            val size32 = readBe32(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSize: Long
            when (size32) {
                0L -> {
                    headerSize = 8
                    boxSize = totalLen - offset
                }
                1L -> {
                    if (offset + 16L > totalLen) break
                    raf.readFully(hdr, 8, 8)
                    headerSize = 16
                    boxSize = readBe64(hdr, 8)
                }
                else -> {
                    headerSize = 8
                    boxSize = size32
                }
            }
            if (boxSize < headerSize || offset + boxSize > totalLen) break
            result += BoxRange(type, offset, boxSize, headerSize)
            offset += boxSize
        }
        return result
    }

    private fun updateMoovPayloadWithUdta(
        moovPayload: ByteArray,
        title: String,
        artist: String,
        album: String
    ): ByteArray {
        val children = parseChildBoxes(moovPayload, 0, moovPayload.size)
        val out = ByteArrayOutputStream(moovPayload.size + 512)
        for ((type, bytes) in children) {
            if (type != "udta") {
                out.write(bytes)
            }
        }
        val existingUdta = children.firstOrNull { it.first == "udta" }?.second
        val udtaPayload = if (existingUdta != null && existingUdta.size > 8) {
            val udtaChildren = parseChildBoxes(existingUdta, 8, existingUdta.size)
            val uOut = ByteArrayOutputStream()
            for ((type, bytes) in udtaChildren) {
                if (type != "meta") uOut.write(bytes)
            }
            uOut.write(buildMetaBox(title, artist, album))
            uOut.toByteArray()
        } else {
            buildMetaBox(title, artist, album)
        }
        out.write(wrapBox("udta", udtaPayload))
        return out.toByteArray()
    }

    private fun buildMetaBox(title: String, artist: String, album: String): ByteArray {
        val hdlrPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0)) // version/flags
            write(byteArrayOf(0, 0, 0, 0)) // pre_defined
            write("mdir".toByteArray(Charsets.ISO_8859_1))
            write("appl".toByteArray(Charsets.ISO_8859_1))
            write(ByteArray(9)) // reserved + empty name
        }.toByteArray()

        val ilstPayload = ByteArrayOutputStream().apply {
            if (title.isNotBlank()) write(buildIlstTextAtom(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), title))
            if (artist.isNotBlank()) {
                write(buildIlstTextAtom(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), artist))
                write(buildIlstTextAtom("aART".toByteArray(Charsets.ISO_8859_1), artist))
            }
            if (album.isNotBlank()) write(buildIlstTextAtom(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), album))
        }.toByteArray()

        val metaPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0)) // FullBox version/flags
            write(wrapBox("hdlr", hdlrPayload))
            write(wrapBox("ilst", ilstPayload))
        }.toByteArray()
        return wrapBox("meta", metaPayload)
    }

    private fun buildIlstTextAtom(atomType4: ByteArray, text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val dataPayload = ByteArrayOutputStream(8 + utf8.size).apply {
            write(byteArrayOf(0, 0, 0, 1)) // type = 1 (UTF-8)
            write(byteArrayOf(0, 0, 0, 0)) // locale
            write(utf8)
        }.toByteArray()
        val dataBox = wrapBox("data", dataPayload)
        val out = ByteArrayOutputStream(8 + dataBox.size)
        writeBe32(out, (8 + dataBox.size).toLong())
        out.write(atomType4)
        out.write(dataBox)
        return out.toByteArray()
    }

    private fun shiftChunkOffsetsInMoov(moovPayload: ByteArray, delta: Long): ByteArray {
        if (delta == 0L) return moovPayload
        val copy = moovPayload.copyOf()
        shiftChunkOffsetsRecursive(copy, 0, copy.size, delta)
        return copy
    }

    private fun shiftChunkOffsetsRecursive(data: ByteArray, start: Int, end: Int, delta: Long) {
        var pos = start
        while (pos + 8 <= end) {
            val size = readBe32(data, pos).toInt()
            if (size < 8 || pos + size > end) break
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            when (type) {
                "trak", "mdia", "minf", "stbl" -> {
                    shiftChunkOffsetsRecursive(data, pos + 8, pos + size, delta)
                }
                "stco" -> {
                    if (size >= 16) {
                        val count = readBe32(data, pos + 12).toInt()
                        var entryPos = pos + 16
                        repeat(count) {
                            if (entryPos + 4 <= pos + size) {
                                val oldOff = readBe32(data, entryPos)
                                val newOff = (oldOff + delta).coerceAtLeast(0L)
                                putBe32(data, entryPos, newOff)
                                entryPos += 4
                            }
                        }
                    }
                }
                "co64" -> {
                    if (size >= 16) {
                        val count = readBe32(data, pos + 12).toInt()
                        var entryPos = pos + 16
                        repeat(count) {
                            if (entryPos + 8 <= pos + size) {
                                val oldOff = readBe64(data, entryPos)
                                val newOff = (oldOff + delta).coerceAtLeast(0L)
                                putBe64(data, entryPos, newOff)
                                entryPos += 8
                            }
                        }
                    }
                }
            }
            pos += size
        }
    }

    private fun parseChildBoxes(data: ByteArray, start: Int, end: Int): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        var pos = start
        while (pos + 8 <= end) {
            val size = readBe32(data, pos).toInt()
            if (size < 8 || pos + size > end) break
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            result += type to data.copyOfRange(pos, pos + size)
            pos += size
        }
        return result
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        writeBe32(out, (8 + payload.size).toLong())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun readBoxBytes(file: File, box: BoxRange): ByteArray {
        val bytes = ByteArray(box.size.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(box.startOffset)
            raf.readFully(bytes)
        }
        return bytes
    }

    private fun copyRange(file: File, offset: Long, length: Long, out: BufferedOutputStream) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0L) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val read = raf.read(buf, 0, want)
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun putBe32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    private fun putBe64(data: ByteArray, offset: Int, value: Long) {
        for (i in 0..7) {
            data[offset + i] = ((value ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
    }

    private fun readBe32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFFL) shl 24) or
            ((data[offset + 1].toLong() and 0xFFL) shl 16) or
            ((data[offset + 2].toLong() and 0xFFL) shl 8) or
            (data[offset + 3].toLong() and 0xFFL)
    }

    private fun readBe64(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) {
            result = (result shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return result
    }
}
