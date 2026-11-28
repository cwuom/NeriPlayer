package moe.ouom.neriplayer.core.player.dlna.protocol

import java.io.ByteArrayOutputStream

/**
 * Builds and validates RIFF "id3 " chunks with ID3v2.3 UTF-16LE (with BOM) frames,
 * plus exact WAV headers accounting for dynamic metadata prefix sizes.
 *
 * Designed around Shanling EM7 hardware behavior: EM7 displays Unknown Artist/Album
 * for RIFF INFO tags, but properly renders embedded ID3v2.3 UTF-16LE BOM frames
 * (TIT2, TPE1, TALB) inside a RIFF "id3 " chunk.
 */
object WavId3TagBuilder {

    const val STANDARD_WAV_HEADER_BYTES = 44
    const val EXTENSIBLE_WAV_HEADER_BYTES = 68
    const val MAX_WAV_ID3_CHUNK_BYTES = 64 * 1024
    const val MAX_WAV_ID3_TEXT_BYTES = 16 * 1024
    const val MAX_RIFF_TOTAL_BYTES = 0xFFFF_FFFFL + 8L

    private val ALLOWED_FRAME_IDS = setOf("TIT2", "TPE1", "TALB")
    private val KSDATAFORMAT_SUBTYPE_PCM = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )

    data class WavLayout(
        val headerBytes: ByteArray,
        val pcmStartOffset: Long,
        val totalBytes: Long,
        val pcmDataBytes: Long,
        val id3ChunkSize: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WavLayout) return false
            return pcmStartOffset == other.pcmStartOffset &&
                totalBytes == other.totalBytes &&
                pcmDataBytes == other.pcmDataBytes &&
                id3ChunkSize == other.id3ChunkSize &&
                headerBytes.contentEquals(other.headerBytes)
        }

        override fun hashCode(): Int {
            var result = headerBytes.contentHashCode()
            result = 31 * result + pcmStartOffset.hashCode()
            result = 31 * result + totalBytes.hashCode()
            result = 31 * result + pcmDataBytes.hashCode()
            result = 31 * result + id3ChunkSize
            return result
        }
    }

    fun buildRawId3v23Tag(title: String, artist: String, album: String): ByteArray {
        val entries = listOf(
            "TIT2" to title,
            "TPE1" to artist,
            "TALB" to album
        )
        val framesOut = ByteArrayOutputStream()
        for ((frameId, value) in entries) {
            if (value.isEmpty()) continue
            val encodedText = encodeUtf16LeStrict(value) ?: return ByteArray(0)
            val payloadSize = 3 + encodedText.size // encoding(1) + BOM(2) + text
            framesOut.write(frameId.toByteArray(Charsets.US_ASCII))
            writeBe32(framesOut, payloadSize)
            framesOut.write(0) // flag 0
            framesOut.write(0) // flag 1
            framesOut.write(1) // encoding: UTF-16 with BOM
            framesOut.write(0xFF) // UTF-16LE BOM low byte
            framesOut.write(0xFE) // UTF-16LE BOM high byte
            framesOut.write(encodedText)
        }
        val framesBytes = framesOut.toByteArray()
        if (framesBytes.isEmpty()) return ByteArray(0)

        val tagPayloadSize = framesBytes.size
        val tagOut = ByteArrayOutputStream(10 + tagPayloadSize)
        tagOut.write("ID3".toByteArray(Charsets.US_ASCII))
        tagOut.write(3) // ID3v2.3
        tagOut.write(0) // minor
        tagOut.write(0) // flags
        for (shift in 21 downTo 0 step 7) {
            tagOut.write((tagPayloadSize ushr shift) and 0x7F)
        }
        tagOut.write(framesBytes)
        return tagOut.toByteArray()
    }

    fun buildId3Chunk(title: String, artist: String, album: String): ByteArray {
        val rawTag = buildRawId3v23Tag(title, artist, album)
        if (rawTag.isEmpty()) return ByteArray(0)
        val chunkPayloadSize = rawTag.size
        val chunkOut = ByteArrayOutputStream(8 + chunkPayloadSize + 1)
        chunkOut.write("id3 ".toByteArray(Charsets.US_ASCII))
        writeLe32(chunkOut, chunkPayloadSize.toLong())
        chunkOut.write(rawTag)
        if ((chunkOut.size() and 1) != 0) {
            chunkOut.write(0) // RIFF chunk word alignment pad
        }
        val chunk = chunkOut.toByteArray()
        return if (isValidWavId3Chunk(chunk)) chunk else ByteArray(0)
    }

    fun isValidWavId3Chunk(chunk: ByteArray): Boolean {
        if (chunk.size < 18 || chunk.size > MAX_WAV_ID3_CHUNK_BYTES) return false
        if (chunk[0] != 'i'.code.toByte() || chunk[1] != 'd'.code.toByte() ||
            chunk[2] != '3'.code.toByte() || chunk[3] != ' '.code.toByte()
        ) {
            return false
        }
        val size = readLe32(chunk, 4).toInt()
        if (size < 10 || size > chunk.size - 8) return false
        val expectedChunkLen = 8 + size + (size and 1)
        if (expectedChunkLen != chunk.size) return false
        if ((size and 1) != 0 && chunk.last() != 0.toByte()) return false

        val tagOffset = 8
        if (chunk[tagOffset] != 'I'.code.toByte() || chunk[tagOffset + 1] != 'D'.code.toByte() ||
            chunk[tagOffset + 2] != '3'.code.toByte() || chunk[tagOffset + 3] != 3.toByte() ||
            chunk[tagOffset + 4] != 0.toByte() || chunk[tagOffset + 5] != 0.toByte()
        ) {
            return false
        }
        var framesSize = 0
        for (i in 6..9) {
            val b = chunk[tagOffset + i].toInt() and 0xFF
            if (b >= 128) return false
            framesSize = (framesSize shl 7) or b
        }
        if (framesSize + 10 != size) return false

        val seen = HashSet<String>(4)
        var pos = 10
        while (pos < size) {
            if (size - pos < 10) return false
            val frameOffset = tagOffset + pos
            val id = String(chunk, frameOffset, 4, Charsets.US_ASCII)
            if (id !in ALLOWED_FRAME_IDS || !seen.add(id)) return false
            if (chunk[frameOffset + 8] != 0.toByte() || chunk[frameOffset + 9] != 0.toByte()) return false
            val payload = readBe32(chunk, frameOffset + 4)
            if (payload < 5 || payload > (size - pos - 10).toLong()) return false
            val payloadInt = payload.toInt()
            if (chunk[frameOffset + 10] != 1.toByte() ||
                chunk[frameOffset + 11] != 0xFF.toByte() ||
                chunk[frameOffset + 12] != 0xFE.toByte()
            ) {
                return false
            }
            if (!isValidUtf16Le(chunk, frameOffset + 13, payloadInt - 3)) return false
            pos += 10 + payloadInt
        }
        return seen.isNotEmpty() && pos == size
    }

    fun buildWavLayout(
        sampleRateHz: Int,
        bitsPerSample: Int,
        channels: Int,
        pcmDataBytes: Long,
        id3Chunk: ByteArray = ByteArray(0),
        channelMask: Int? = null,
        validBitsPerSample: Int? = null,
        useExtensibleHeader: Boolean = false
    ): WavLayout {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(bitsPerSample == 8 || bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32) {
            "Unsupported WAV PCM bit depth: $bitsPerSample"
        }
        val emitExtensible = useExtensibleHeader ||
            channelMask != null ||
            (validBitsPerSample != null && validBitsPerSample != bitsPerSample)
        val maxChannels = if (emitExtensible) 16 else 8
        require(channels in 1..maxChannels) { "Unsupported channel count: $channels" }
        require(pcmDataBytes >= 0L) { "pcmDataBytes must be non-negative: $pcmDataBytes" }

        val safeId3 = if (id3Chunk.isNotEmpty()) {
            require(isValidWavId3Chunk(id3Chunk)) { "Invalid WAV ID3 chunk" }
            id3Chunk
        } else {
            ByteArray(0)
        }

        val fixedHeaderBytes = if (emitExtensible) EXTENSIBLE_WAV_HEADER_BYTES else STANDARD_WAV_HEADER_BYTES
        val prefixBytes = fixedHeaderBytes + safeId3.size
        val dataPadBytes = pcmDataBytes and 1L
        val totalBytes = prefixBytes.toLong() + pcmDataBytes + dataPadBytes
        if (totalBytes > MAX_RIFF_TOTAL_BYTES) {
            throw IllegalArgumentException("WAV entity exceeds RIFF 32-bit size limit: $totalBytes")
        }

        val blockAlign = channels * (bitsPerSample / 8)
        val byteRate = sampleRateHz.toLong() * blockAlign.toLong()
        val out = ByteArrayOutputStream(prefixBytes)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(out, totalBytes - 8L)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        if (emitExtensible) {
            writeLe32(out, 40L) // WAVE_FORMAT_EXTENSIBLE fmt chunk size
            writeLe16(out, 0xFFFE) // AudioFormat = WAVE_FORMAT_EXTENSIBLE
            writeLe16(out, channels)
            writeLe32(out, sampleRateHz.toLong())
            writeLe32(out, byteRate)
            writeLe16(out, blockAlign)
            writeLe16(out, bitsPerSample)
            writeLe16(out, 22) // cbSize = 22
            writeLe16(out, (validBitsPerSample ?: bitsPerSample).coerceIn(1, bitsPerSample))
            writeLe32(out, (channelMask ?: 0).toLong() and 0xFFFF_FFFFL)
            out.write(KSDATAFORMAT_SUBTYPE_PCM)
        } else {
            writeLe32(out, 16L) // PCM fmt chunk size
            writeLe16(out, 1) // AudioFormat = 1 (PCM)
            writeLe16(out, channels)
            writeLe32(out, sampleRateHz.toLong())
            writeLe32(out, byteRate)
            writeLe16(out, blockAlign)
            writeLe16(out, bitsPerSample)
        }
        if (safeId3.isNotEmpty()) {
            out.write(safeId3)
        }
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeLe32(out, pcmDataBytes)

        val header = out.toByteArray()
        return WavLayout(
            headerBytes = header,
            pcmStartOffset = header.size.toLong(),
            totalBytes = totalBytes,
            pcmDataBytes = pcmDataBytes,
            id3ChunkSize = safeId3.size
        )
    }

    private fun encodeUtf16LeStrict(text: String): ByteArray? {
        if (text.length > MAX_WAV_ID3_TEXT_BYTES / 2) return null
        val out = ByteArrayOutputStream(text.length * 2)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val code = ch.code
            if (code == 0) return null
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return null
                val low = text[i + 1]
                out.write(ch.code and 0xFF)
                out.write((ch.code ushr 8) and 0xFF)
                out.write(low.code and 0xFF)
                out.write((low.code ushr 8) and 0xFF)
                i += 2
            } else if (Character.isLowSurrogate(ch)) {
                return null
            } else {
                out.write(code and 0xFF)
                out.write((code ushr 8) and 0xFF)
                i += 1
            }
            if (out.size() > MAX_WAV_ID3_TEXT_BYTES) return null
        }
        val bytes = out.toByteArray()
        return if (isValidUtf16Le(bytes, 0, bytes.size)) bytes else null
    }

    private fun isValidUtf16Le(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0 || (length and 1) != 0 || length > MAX_WAV_ID3_TEXT_BYTES) return false
        var i = 0
        while (i < length) {
            val unit = (data[offset + i].toInt() and 0xFF) or
                ((data[offset + i + 1].toInt() and 0xFF) shl 8)
            if (unit == 0) return false
            if (unit in 0xD800..0xDBFF) {
                if (i + 3 >= length) return false
                val low = (data[offset + i + 2].toInt() and 0xFF) or
                    ((data[offset + i + 3].toInt() and 0xFF) shl 8)
                if (low !in 0xDC00..0xDFFF) return false
                i += 4
            } else if (unit in 0xDC00..0xDFFF) {
                return false
            } else {
                i += 2
            }
        }
        return true
    }

    private fun writeLe16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readLe32(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readBe32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFFL) shl 24) or
            ((data[offset + 1].toLong() and 0xFFL) shl 16) or
            ((data[offset + 2].toLong() and 0xFFL) shl 8) or
            (data[offset + 3].toLong() and 0xFFL)
    }
}
