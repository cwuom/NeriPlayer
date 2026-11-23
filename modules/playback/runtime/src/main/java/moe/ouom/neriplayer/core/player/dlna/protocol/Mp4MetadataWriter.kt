package moe.ouom.neriplayer.core.player.dlna.protocol

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Pure-Kotlin ISO BMFF (M4A/MP4) faststart iTunes metadata (moov/udta/meta/ilst) writer.
 * Inserts/updates ©nam, ©ART, aART, and ©alb atoms while preserving existing untouched
 * ilst atoms (e.g. covr, trkn), keeping mdat audio samples 100% untouched, and validating
 * & adjusting stco/co64 chunk offsets accurately.
 */
object Mp4MetadataWriter {

    private const val MAX_MOOV_BYTES = 32L * 1024L * 1024L

    private val ATOM_TITLE = String(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), Charsets.ISO_8859_1)
    private val ATOM_ARTIST = String(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), Charsets.ISO_8859_1)
    private val ATOM_ALBUM_ARTIST = "aART"
    private val ATOM_ALBUM = String(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), Charsets.ISO_8859_1)

    private data class BoxRange(
        val type: String,
        val startOffset: Long,
        val size: Long,
        val headerSize: Int
    )

    private data class ParsedChildBox(
        val type: String,
        val headerSize: Int,
        val rawBytes: ByteArray
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
        val tmpFile = File(
            targetFile.parentFile,
            "${targetFile.name}.m4a_${java.util.UUID.randomUUID().toString().take(8)}.tmp"
        )
        var succeeded = false
        return try {
            val boxes = RandomAccessFile(sourceFile, "r").use { raf -> readTopLevelBoxes(raf) }
            if (boxes.any { it.type == "moof" }) return false // fragmented MP4 must not be rewritten
            val ftypBox = boxes.firstOrNull { it.type == "ftyp" } ?: return false
            val moovBox = boxes.firstOrNull { it.type == "moov" } ?: return false
            val mdatBoxes = boxes.filter { it.type == "mdat" }
            if (mdatBoxes.size != 1) return false
            val mdatBox = mdatBoxes.first()
            if (moovBox.size > MAX_MOOV_BYTES || ftypBox.size > 1024L * 1024L) return false

            val ftypBytes = readBoxBytes(sourceFile, ftypBox)
            val originalMoovBytes = readBoxBytes(sourceFile, moovBox)
            val originalMoovPayload = originalMoovBytes.copyOfRange(moovBox.headerSize, originalMoovBytes.size)

            val updatedMoovPayloadNoShift = updateMoovPayloadWithUdta(
                moovPayload = originalMoovPayload,
                title = title,
                artist = artist,
                album = album
            ) ?: return false

            val newMoovTotalSize = 8L + updatedMoovPayloadNoShift.size.toLong()
            if (newMoovTotalSize > MAX_MOOV_BYTES) return false
            val newMdatStartOffset = ftypBytes.size.toLong() + newMoovTotalSize
            val mdatShift = newMdatStartOffset - mdatBox.startOffset

            val finalMoovPayload = shiftChunkOffsetsInMoov(updatedMoovPayloadNoShift, mdatShift)
                ?: return false
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
            succeeded = tmpFile.renameTo(targetFile)
            succeeded
        } catch (_: Throwable) {
            false
        } finally {
            if (!succeeded) {
                tmpFile.delete()
            }
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
    ): ByteArray? {
        val children = parseChildBoxes(moovPayload, 0, moovPayload.size) ?: return null
        val out = ByteArrayOutputStream(moovPayload.size + 512)
        for (child in children) {
            if (child.type != "udta") {
                out.write(child.rawBytes)
            }
        }
        val existingUdta = children.firstOrNull { it.type == "udta" }
        val udtaPayload = if (existingUdta != null && existingUdta.rawBytes.size > existingUdta.headerSize) {
            val udtaChildren = parseChildBoxes(
                existingUdta.rawBytes,
                existingUdta.headerSize,
                existingUdta.rawBytes.size
            ) ?: return null
            val uOut = ByteArrayOutputStream()
            for (child in udtaChildren) {
                if (child.type != "meta") uOut.write(child.rawBytes)
            }
            val existingMeta = udtaChildren.firstOrNull { it.type == "meta" }
            val metaBytes = buildMetaBox(title, artist, album, existingMeta) ?: return null
            uOut.write(metaBytes)
            uOut.toByteArray()
        } else {
            buildMetaBox(title, artist, album, existingMeta = null) ?: return null
        }
        out.write(wrapBox("udta", udtaPayload))
        return out.toByteArray()
    }

    private fun buildMetaBox(
        title: String,
        artist: String,
        album: String,
        existingMeta: ParsedChildBox?
    ): ByteArray? {
        var existingMetaChildren: List<ParsedChildBox> = emptyList()
        var preservedIlstAtoms: List<ParsedChildBox> = emptyList()

        if (existingMeta != null) {
            val metaBodyStart = existingMeta.headerSize + 4 // FullBox 4-byte version/flags
            if (metaBodyStart <= existingMeta.rawBytes.size) {
                val parsedMeta = parseChildBoxes(
                    existingMeta.rawBytes,
                    metaBodyStart,
                    existingMeta.rawBytes.size
                )
                if (parsedMeta != null) {
                    existingMetaChildren = parsedMeta
                    val existingIlst = parsedMeta.firstOrNull { it.type == "ilst" }
                    if (existingIlst != null && existingIlst.headerSize <= existingIlst.rawBytes.size) {
                        val parsedIlst = parseChildBoxes(
                            existingIlst.rawBytes,
                            existingIlst.headerSize,
                            existingIlst.rawBytes.size
                        )
                        if (parsedIlst != null) {
                            val replacedTypes = buildSet {
                                if (title.isNotBlank()) add(ATOM_TITLE)
                                if (artist.isNotBlank()) {
                                    add(ATOM_ARTIST)
                                    add(ATOM_ALBUM_ARTIST)
                                }
                                if (album.isNotBlank()) add(ATOM_ALBUM)
                            }
                            preservedIlstAtoms = parsedIlst.filter { it.type !in replacedTypes }
                        }
                    }
                }
            }
        }

        val hdlrBox = existingMetaChildren.firstOrNull { it.type == "hdlr" }?.rawBytes ?: run {
            val hdlrPayload = ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0, 0)) // version/flags
                write(byteArrayOf(0, 0, 0, 0)) // pre_defined
                write("mdir".toByteArray(Charsets.ISO_8859_1))
                write("appl".toByteArray(Charsets.ISO_8859_1))
                write(ByteArray(9)) // reserved + empty name
            }.toByteArray()
            wrapBox("hdlr", hdlrPayload)
        }

        val ilstPayload = ByteArrayOutputStream().apply {
            if (title.isNotBlank()) {
                write(buildIlstTextAtom(ATOM_TITLE.toByteArray(Charsets.ISO_8859_1), title))
            }
            if (artist.isNotBlank()) {
                write(buildIlstTextAtom(ATOM_ARTIST.toByteArray(Charsets.ISO_8859_1), artist))
                write(buildIlstTextAtom(ATOM_ALBUM_ARTIST.toByteArray(Charsets.ISO_8859_1), artist))
            }
            if (album.isNotBlank()) {
                write(buildIlstTextAtom(ATOM_ALBUM.toByteArray(Charsets.ISO_8859_1), album))
            }
            for (atom in preservedIlstAtoms) {
                write(atom.rawBytes)
            }
        }.toByteArray()

        val metaPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0)) // FullBox version/flags
            write(hdlrBox)
            for (child in existingMetaChildren) {
                if (child.type != "hdlr" && child.type != "ilst") {
                    write(child.rawBytes)
                }
            }
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

    private fun shiftChunkOffsetsInMoov(moovPayload: ByteArray, delta: Long): ByteArray? {
        val copy = moovPayload.copyOf()
        // Validate stco/co64 structure even when delta == 0L so corrupt tables fail fast
        if (!shiftChunkOffsetsRecursive(copy, 0, copy.size, delta)) {
            return null
        }
        return copy
    }

    private fun shiftChunkOffsetsRecursive(data: ByteArray, start: Int, end: Int, delta: Long): Boolean {
        var pos = start
        while (pos < end) {
            if (pos + 8 > end) return false
            val size32 = readBe32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSizeLong: Long
            when (size32) {
                0L -> {
                    headerSize = 8
                    boxSizeLong = (end - pos).toLong()
                }
                1L -> {
                    if (pos + 16 > end) return false
                    headerSize = 16
                    boxSizeLong = readBe64(data, pos + 8)
                }
                else -> {
                    headerSize = 8
                    boxSizeLong = size32
                }
            }
            if (boxSizeLong < headerSize || pos.toLong() + boxSizeLong > end.toLong()) {
                return false
            }
            val boxEnd = (pos.toLong() + boxSizeLong).toInt()
            val payloadStart = pos + headerSize

            when (type) {
                "trak", "mdia", "minf", "stbl" -> {
                    if (!shiftChunkOffsetsRecursive(data, payloadStart, boxEnd, delta)) {
                        return false
                    }
                }
                "stco" -> {
                    if (boxEnd - payloadStart < 8) return false
                    val count = readBe32(data, payloadStart + 4)
                    val entriesStart = payloadStart + 8
                    val maxEntries = (boxEnd - entriesStart).toLong() / 4L
                    if (count < 0L || count > maxEntries) return false
                    var entryPos = entriesStart
                    repeat(count.toInt()) {
                        val oldOff = readBe32(data, entryPos)
                        val newOff = oldOff + delta
                        if (newOff !in 0L..0xFFFFFFFFL) return false
                        if (delta != 0L) {
                            putBe32(data, entryPos, newOff)
                        }
                        entryPos += 4
                    }
                }
                "co64" -> {
                    if (boxEnd - payloadStart < 8) return false
                    val count = readBe32(data, payloadStart + 4)
                    val entriesStart = payloadStart + 8
                    val maxEntries = (boxEnd - entriesStart).toLong() / 8L
                    if (count < 0L || count > maxEntries) return false
                    var entryPos = entriesStart
                    repeat(count.toInt()) {
                        val oldOff = readBe64(data, entryPos)
                        if (oldOff < 0L) return false
                        val newOff = oldOff + delta
                        if (newOff < 0L) return false
                        if (delta != 0L) {
                            putBe64(data, entryPos, newOff)
                        }
                        entryPos += 8
                    }
                }
            }
            pos = boxEnd
        }
        return pos == end
    }

    private fun parseChildBoxes(data: ByteArray, start: Int, end: Int): List<ParsedChildBox>? {
        if (start < 0 || end < start || end > data.size) return null
        val result = mutableListOf<ParsedChildBox>()
        var pos = start
        while (pos < end) {
            if (pos + 8 > end) return null
            val size32 = readBe32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSizeLong: Long
            when (size32) {
                0L -> {
                    headerSize = 8
                    boxSizeLong = (end - pos).toLong()
                }
                1L -> {
                    if (pos + 16 > end) return null
                    headerSize = 16
                    boxSizeLong = readBe64(data, pos + 8)
                }
                else -> {
                    headerSize = 8
                    boxSizeLong = size32
                }
            }
            if (boxSizeLong < headerSize || pos.toLong() + boxSizeLong > end.toLong()) {
                return null
            }
            val boxEnd = (pos.toLong() + boxSizeLong).toInt()
            result += ParsedChildBox(type, headerSize, data.copyOfRange(pos, boxEnd))
            pos = boxEnd
        }
        return if (pos == end) result else null
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
                if (read <= 0) {
                    throw EOFException("Unexpected EOF while copying MP4 box ($remaining bytes remaining)")
                }
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
