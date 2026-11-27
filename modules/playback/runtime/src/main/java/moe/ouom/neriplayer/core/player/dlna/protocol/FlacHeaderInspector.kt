package moe.ouom.neriplayer.core.player.dlna.protocol

import java.io.File
import java.io.RandomAccessFile

/**
 * Pure binary audio container & header inspector for lossless verification.
 * Extracts real sample rate, bit depth, channel count, and PCM layout directly
 * from file bytes rather than trusting URL extensions or quality switches.
 */
object FlacHeaderInspector {

    enum class ContainerFormat {
        FLAC,
        WAV,
        ALAC_MP4,
        MP3,
        AAC_ADTS,
        AAC_OR_MP4,
        OGG,
        WEBM,
        UNKNOWN
    }

    private val PCM_EXTENSIBLE_GUID_TAIL = byteArrayOf(
        0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
    )

    private val NON_AUDIO_MP4_SAMPLE_ENTRIES = setOf(
        "avc1", "avc3", "hvc1", "hev1", "vp08", "vp09", "av01", "mp4v", "encv",
        "jpeg", "png ", "tx3g", "wvtt", "stpp", "text", "mett", "meta", "tmcd"
    )

    data class InspectedAudio(
        val format: ContainerFormat,
        val mimeType: String,
        val isLossless: Boolean,
        val sampleRateHz: Int? = null,
        val bitDepth: Int? = null,
        val channelCount: Int? = null,
        val totalSamples: Long? = null,
        val wavDataOffset: Long? = null,
        val wavDataSize: Long? = null,
        val wavAudioFormatTag: Int? = null,
        val wavValidBitsPerSample: Int? = null,
        val wavChannelMask: Int? = null
    )

    private data class AlacSpec(
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val channelCount: Int?
    )

    fun inspectFile(file: File): InspectedAudio {
        if (!file.isFile || file.length() < 4L) {
            return InspectedAudio(ContainerFormat.UNKNOWN, "application/octet-stream", isLossless = false)
        }
        val headSize = minOf(file.length(), 4096L).toInt()
        val head = ByteArray(headSize)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(head)
            val initial = inspectBytes(head, file.length())
            if (initial.format == ContainerFormat.WAV &&
                (initial.wavAudioFormatTag == null ||
                    initial.wavDataOffset == null ||
                    (initial.wavAudioFormatTag == 0xFFFE && initial.wavChannelMask == null))
            ) {
                return inspectWavFile(raf, file.length())
            }
            if (initial.format == ContainerFormat.AAC_OR_MP4 ||
                (initial.format == ContainerFormat.ALAC_MP4 && initial.bitDepth == null)
            ) {
                return inspectMp4File(raf, file.length()) ?: initial
            }
            return initial
        }
    }

    fun inspectBytes(bytes: ByteArray, totalFileLength: Long = bytes.size.toLong()): InspectedAudio {
        if (bytes.size >= 4 &&
            bytes[0] == 'f'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() &&
            bytes[3] == 'C'.code.toByte()
        ) {
            return parseFlac(bytes)
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        ) {
            return parseWav(bytes, totalFileLength)
        }
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            return inspectMp4Container(bytes)
        }
        if (bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()
        ) {
            return InspectedAudio(ContainerFormat.OGG, "audio/ogg", isLossless = false)
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return InspectedAudio(ContainerFormat.WEBM, "audio/webm", isLossless = false)
        }
        if (isAacAdtsSignature(bytes)) {
            return InspectedAudio(ContainerFormat.AAC_ADTS, "audio/aac", isLossless = false)
        }
        if (isMp3Signature(bytes)) {
            return InspectedAudio(ContainerFormat.MP3, "audio/mpeg", isLossless = false)
        }
        return InspectedAudio(ContainerFormat.UNKNOWN, "application/octet-stream", isLossless = false)
    }

    private fun parseFlac(bytes: ByteArray): InspectedAudio {
        // After 'fLaC' (4 bytes), first metadata block header is 4 bytes, STREAMINFO is 34 bytes
        var offset = 4
        while (offset + 4 <= bytes.size) {
            val headerByte = bytes[offset].toInt() and 0xFF
            val isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val blockLength = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            if (blockType == 0 && blockLength >= 34 && offset + 34 <= bytes.size) {
                // STREAMINFO bytes 10..17 contain sampleRate(20), channels-1(3), bps-1(5), totalSamples(36)
                val b10 = bytes[offset + 10].toInt() and 0xFF
                val b11 = bytes[offset + 11].toInt() and 0xFF
                val b12 = bytes[offset + 12].toInt() and 0xFF
                val b13 = bytes[offset + 13].toInt() and 0xFF
                val b14 = bytes[offset + 14].toInt() and 0xFF
                val b15 = bytes[offset + 15].toInt() and 0xFF
                val b16 = bytes[offset + 16].toInt() and 0xFF
                val b17 = bytes[offset + 17].toInt() and 0xFF

                val sampleRateHz = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                val channelCount = ((b12 ushr 1) and 0x07) + 1
                val bitDepth = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                val totalSamples = ((b13.toLong() and 0x0FL) shl 32) or
                    (b14.toLong() shl 24) or
                    (b15.toLong() shl 16) or
                    (b16.toLong() shl 8) or
                    b17.toLong()
                return InspectedAudio(
                    format = ContainerFormat.FLAC,
                    mimeType = "audio/x-flac",
                    isLossless = true,
                    sampleRateHz = sampleRateHz.takeIf { it > 0 },
                    bitDepth = bitDepth.takeIf { it > 0 },
                    channelCount = channelCount.takeIf { it > 0 },
                    totalSamples = totalSamples.takeIf { it > 0L }
                )
            }
            if (isLast || blockLength < 0 || offset + blockLength > bytes.size) break
            offset += blockLength
        }
        return InspectedAudio(
            format = ContainerFormat.FLAC,
            mimeType = "audio/x-flac",
            isLossless = true
        )
    }

    private fun parseWav(bytes: ByteArray, totalFileLength: Long): InspectedAudio {
        var offset = 12
        var sampleRateHz: Int? = null
        var bitDepth: Int? = null
        var channelCount: Int? = null
        var audioFormatTag: Int? = null
        var validBitsPerSample: Int? = null
        var channelMask: Int? = null
        var extensiblePcmVerified = false
        var dataOffset: Long? = null
        var dataSize: Long? = null

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readLe32(bytes, offset + 4)
            val payloadOffset = offset + 8
            if (chunkId == "fmt " && chunkSize >= 16L && payloadOffset + 16 <= bytes.size) {
                audioFormatTag = readLe16(bytes, payloadOffset)
                channelCount = readLe16(bytes, payloadOffset + 2)
                sampleRateHz = readLe32(bytes, payloadOffset + 4).toInt()
                bitDepth = readLe16(bytes, payloadOffset + 14)
                if (audioFormatTag == 0xFFFE && chunkSize >= 40L && payloadOffset + 40 <= bytes.size) {
                    validBitsPerSample = readLe16(bytes, payloadOffset + 18).takeIf { it > 0 }
                    channelMask = readLe32(bytes, payloadOffset + 20).toInt()
                    val subFormatTag = readLe16(bytes, payloadOffset + 24)
                    extensiblePcmVerified = subFormatTag == 1 &&
                        matchesPcmExtensibleTail(bytes, payloadOffset + 26)
                }
            } else if (chunkId == "data") {
                dataOffset = payloadOffset.toLong()
                dataSize = minOf(chunkSize, maxOf(0L, totalFileLength - payloadOffset.toLong()))
                if (audioFormatTag != null) break
            }
            if (chunkSize < 0L || chunkSize > bytes.size) break
            val padded = chunkSize + (chunkSize and 1L)
            val next = payloadOffset.toLong() + padded
            if (next <= offset || next > Int.MAX_VALUE) break
            offset = next.toInt()
        }

        val lossless = isLosslessWavFormat(audioFormatTag, extensiblePcmVerified)
        return InspectedAudio(
            format = ContainerFormat.WAV,
            mimeType = "audio/wav",
            isLossless = lossless,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channelCount = channelCount,
            wavDataOffset = dataOffset,
            wavDataSize = dataSize,
            wavAudioFormatTag = audioFormatTag,
            wavValidBitsPerSample = validBitsPerSample,
            wavChannelMask = channelMask
        )
    }

    private fun inspectWavFile(raf: RandomAccessFile, fileLength: Long): InspectedAudio {
        var offset = 12L
        val chunkHeader = ByteArray(8)
        val fmtBuf = ByteArray(40)
        var sampleRateHz: Int? = null
        var bitDepth: Int? = null
        var channelCount: Int? = null
        var audioFormatTag: Int? = null
        var validBitsPerSample: Int? = null
        var channelMask: Int? = null
        var extensiblePcmVerified = false
        var dataOffset: Long? = null
        var dataSize: Long? = null

        while (offset + 8L <= fileLength) {
            raf.seek(offset)
            raf.readFully(chunkHeader, 0, 8)
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = readLe32(chunkHeader, 4)
            val payloadOffset = offset + 8L
            if (chunkSize < 0L || payloadOffset > fileLength) break

            if (chunkId == "fmt " && chunkSize >= 16L && payloadOffset + 16L <= fileLength) {
                val toRead = minOf(chunkSize, 40L, fileLength - payloadOffset).toInt()
                raf.seek(payloadOffset)
                raf.readFully(fmtBuf, 0, toRead)
                audioFormatTag = readLe16(fmtBuf, 0)
                channelCount = readLe16(fmtBuf, 2)
                sampleRateHz = readLe32(fmtBuf, 4).toInt()
                bitDepth = readLe16(fmtBuf, 14)
                if (audioFormatTag == 0xFFFE && chunkSize >= 40L && toRead >= 40) {
                    validBitsPerSample = readLe16(fmtBuf, 18).takeIf { it > 0 }
                    channelMask = readLe32(fmtBuf, 20).toInt()
                    val subFormatTag = readLe16(fmtBuf, 24)
                    extensiblePcmVerified = subFormatTag == 1 && matchesPcmExtensibleTail(fmtBuf, 26)
                }
            } else if (chunkId == "data") {
                dataOffset = payloadOffset
                dataSize = minOf(chunkSize, maxOf(0L, fileLength - payloadOffset))
            }

            if (audioFormatTag != null && dataOffset != null) break

            val padded = chunkSize + (chunkSize and 1L)
            val next = payloadOffset + padded
            if (next <= offset || next > fileLength) break
            offset = next
        }

        val lossless = isLosslessWavFormat(audioFormatTag, extensiblePcmVerified)
        return InspectedAudio(
            format = ContainerFormat.WAV,
            mimeType = "audio/wav",
            isLossless = lossless,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channelCount = channelCount,
            wavDataOffset = dataOffset,
            wavDataSize = dataSize,
            wavAudioFormatTag = audioFormatTag,
            wavValidBitsPerSample = validBitsPerSample,
            wavChannelMask = channelMask
        )
    }

    private fun isLosslessWavFormat(audioFormatTag: Int?, extensiblePcmVerified: Boolean): Boolean {
        // Never treat an unread/missing fmt chunk or unverified extensible SubFormat GUID as lossless
        return when (audioFormatTag) {
            1 -> true
            0xFFFE -> extensiblePcmVerified
            else -> false
        }
    }

    private fun matchesPcmExtensibleTail(buf: ByteArray, offset: Int): Boolean {
        if (offset + PCM_EXTENSIBLE_GUID_TAIL.size > buf.size) return false
        for (i in PCM_EXTENSIBLE_GUID_TAIL.indices) {
            if (buf[offset + i] != PCM_EXTENSIBLE_GUID_TAIL[i]) return false
        }
        return true
    }

    private fun inspectMp4Container(bytes: ByteArray): InspectedAudio {
        val alac = findAlacSpecInBoxRange(bytes, 0, bytes.size)
        return if (alac != null) {
            InspectedAudio(
                format = ContainerFormat.ALAC_MP4,
                mimeType = "audio/alac",
                isLossless = true,
                sampleRateHz = alac.sampleRateHz,
                bitDepth = alac.bitDepth,
                channelCount = alac.channelCount
            )
        } else {
            InspectedAudio(
                format = ContainerFormat.AAC_OR_MP4,
                mimeType = "audio/mp4",
                isLossless = false
            )
        }
    }

    private fun inspectMp4File(raf: RandomAccessFile, fileLength: Long): InspectedAudio? {
        var offset = 0L
        val hdr = ByteArray(16)
        while (offset + 8L <= fileLength) {
            raf.seek(offset)
            raf.readFully(hdr, 0, 8)
            val size32 = readBe32(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            val headerSize: Int
            val boxSize: Long
            if (size32 == 1L) {
                if (offset + 16L > fileLength) break
                raf.readFully(hdr, 8, 8)
                headerSize = 16
                boxSize = readBe64(hdr, 8)
            } else if (size32 == 0L) {
                headerSize = 8
                boxSize = fileLength - offset
            } else {
                headerSize = 8
                boxSize = size32
            }
            if (boxSize < headerSize || boxSize > fileLength - offset) break
            if (type == "moov" && boxSize <= 16L * 1024L * 1024L) {
                val payloadLen = (boxSize - headerSize).toInt()
                val moovPayload = ByteArray(payloadLen)
                raf.seek(offset + headerSize)
                raf.readFully(moovPayload)
                val alac = findAlacSpecInContainer(moovPayload, 0, moovPayload.size, "moov")
                return if (alac != null) {
                    InspectedAudio(
                        format = ContainerFormat.ALAC_MP4,
                        mimeType = "audio/alac",
                        isLossless = true,
                        sampleRateHz = alac.sampleRateHz,
                        bitDepth = alac.bitDepth,
                        channelCount = alac.channelCount
                    )
                } else {
                    InspectedAudio(format = ContainerFormat.AAC_OR_MP4, mimeType = "audio/mp4", isLossless = false)
                }
            }
            offset += boxSize
        }
        return null
    }

    private fun findAlacSpecInBoxRange(bytes: ByteArray, start: Int, end: Int): AlacSpec? {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readBe32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val headerSize = if (size32 == 1L) 16 else 8
            val boxSize = when (size32) {
                1L -> if (pos + 16 <= end) readBe64(bytes, pos + 8) else break
                0L -> (end - pos).toLong()
                else -> size32
            }
            if (boxSize < headerSize) break
            val boxEnd = if (boxSize >= (end - pos).toLong()) end else pos + boxSize.toInt()
            if (type == "moov") {
                findAlacSpecInContainer(bytes, pos + headerSize, boxEnd, "moov")?.let { return it }
            }
            if (boxSize > (end - pos).toLong()) break
            pos = (pos.toLong() + boxSize).toInt()
        }
        return null
    }

    private fun findAlacSpecInContainer(
        bytes: ByteArray,
        start: Int,
        end: Int,
        containerType: String
    ): AlacSpec? {
        if (containerType != "moov") return null
        // Inspect ONLY the first audio 'trak' in moov so inspector and LosslessWavDecoder always agree on the delivered track
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readBe32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val headerSize = if (size32 == 1L) 16 else 8
            val boxSize = when (size32) {
                1L -> if (pos + 16 <= end) readBe64(bytes, pos + 8) else break
                0L -> (end - pos).toLong()
                else -> size32
            }
            if (boxSize < headerSize) break
            val boxEnd = if (boxSize >= (end - pos).toLong()) end else pos + boxSize.toInt()
            if (type == "trak") {
                val trackClassification = classifyMp4Trak(bytes, pos + headerSize, boxEnd)
                if (trackClassification != null) {
                    // First audio track found: return its ALAC spec if ALAC, or null if lossy audio (e.g. AAC)
                    return trackClassification.alacSpec
                }
            }
            if (boxSize > (end - pos).toLong()) break
            pos = (pos.toLong() + boxSize).toInt()
        }
        return null
    }

    private data class Mp4AudioTrackClassification(val alacSpec: AlacSpec?)

    private fun classifyMp4Trak(bytes: ByteArray, trakStart: Int, trakEnd: Int): Mp4AudioTrackClassification? {
        val (mdiaStart, mdiaEnd) = findChildBoxPayload(bytes, trakStart, trakEnd, "mdia") ?: return null
        val hdlrRange = findChildBoxPayload(bytes, mdiaStart, mdiaEnd, "hdlr")
        if (hdlrRange != null) {
            val (hdlrStart, hdlrEnd) = hdlrRange
            // FullBox(4) + pre_defined(4) + handler_type(4)
            if (hdlrStart + 12 <= hdlrEnd) {
                val handlerType = String(bytes, hdlrStart + 8, 4, Charsets.ISO_8859_1)
                if (handlerType != "soun") return null
            }
        }
        val (minfStart, minfEnd) = findChildBoxPayload(bytes, mdiaStart, mdiaEnd, "minf") ?: return null
        val (stblStart, stblEnd) = findChildBoxPayload(bytes, minfStart, minfEnd, "stbl") ?: return null
        val (stsdStart, stsdEnd) = findChildBoxPayload(bytes, stblStart, stblEnd, "stsd") ?: return null
        // stsd FullBox: 4 bytes version+flags + 4 bytes entry_count
        if (stsdStart + 8 > stsdEnd) return null
        val entryCount = readBe32(bytes, stsdStart + 4).toInt().coerceIn(0, 64)
        if (entryCount <= 0) return null
        val pos = stsdStart + 8
        if (pos + 8 > stsdEnd) return null
        val entrySize = readBe32(bytes, pos)
        if (entrySize < 8L) return null
        val entryFormat = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
        if (hdlrRange == null && entryFormat in NON_AUDIO_MP4_SAMPLE_ENTRIES) {
            return null
        }
        val entryEnd = minOf(stsdEnd.toLong(), pos.toLong() + entrySize).toInt()
        return if (entryFormat == "alac") {
            Mp4AudioTrackClassification(parseAlacSampleEntry(bytes, pos, entryEnd))
        } else {
            Mp4AudioTrackClassification(alacSpec = null)
        }
    }

    private fun findChildBoxPayload(bytes: ByteArray, start: Int, end: Int, targetType: String): Pair<Int, Int>? {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readBe32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val headerSize = if (size32 == 1L) 16 else 8
            val boxSize = when (size32) {
                1L -> if (pos + 16 <= end) readBe64(bytes, pos + 8) else return null
                0L -> (end - pos).toLong()
                else -> size32
            }
            if (boxSize < headerSize) return null
            val boxEnd = if (boxSize >= (end - pos).toLong()) end else pos + boxSize.toInt()
            if (type == targetType) {
                return (pos + headerSize) to boxEnd
            }
            if (boxSize > (end - pos).toLong()) return null
            pos = (pos.toLong() + boxSize).toInt()
        }
        return null
    }

    private fun parseAlacSampleEntry(bytes: ByteArray, entryStart: Int, entryEnd: Int): AlacSpec {
        // AudioSampleEntry header is 36 bytes:
        // [24..25] channelcount, [26..27] samplesize, [32..33] samplerate integer part (16.16)
        var fallbackChannels: Int? = null
        var fallbackBitDepth: Int? = null
        var fallbackSampleRate: Int? = null
        if (entryStart + 36 <= entryEnd) {
            fallbackChannels = readBe16(bytes, entryStart + 24).takeIf { it in 1..16 }
            fallbackBitDepth = readBe16(bytes, entryStart + 26).takeIf { it in setOf(16, 20, 24, 32) }
            fallbackSampleRate = readBe16(bytes, entryStart + 32).takeIf { it > 0 }
        }
        // Search child boxes after 36-byte AudioSampleEntry header for 'alac' SpecificConfig FullBox (36 bytes total)
        var childPos = entryStart + 36
        while (childPos + 8 <= entryEnd) {
            val childSize = readBe32(bytes, childPos)
            val childType = String(bytes, childPos + 4, 4, Charsets.ISO_8859_1)
            if (childSize < 8L) break
            val childEnd = minOf(entryEnd.toLong(), childPos.toLong() + childSize).toInt()
            // ALACSpecificConfig starts after 8-byte box header + 4-byte FullBox version/flags = offset + 12, 24 bytes long
            if (childType == "alac" && childPos + 36 <= childEnd) {
                val cfg = childPos + 12
                val bitDepth = (bytes[cfg + 5].toInt() and 0xFF).takeIf { it in setOf(8, 16, 20, 24, 32) }
                val channels = (bytes[cfg + 9].toInt() and 0xFF).takeIf { it in 1..16 }
                val sampleRate = readBe32(bytes, cfg + 20).toInt().takeIf { it in 8_000..768_000 }
                return AlacSpec(
                    sampleRateHz = sampleRate ?: fallbackSampleRate,
                    bitDepth = bitDepth ?: fallbackBitDepth,
                    channelCount = channels ?: fallbackChannels
                )
            }
            if (childPos.toLong() + childSize > entryEnd) break
            childPos = (childPos.toLong() + childSize).toInt()
        }
        return AlacSpec(
            sampleRateHz = fallbackSampleRate,
            bitDepth = fallbackBitDepth,
            channelCount = fallbackChannels
        )
    }

    private fun readBe16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }

    private fun readBe32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFFL) shl 24) or
            ((data[offset + 1].toLong() and 0xFFL) shl 16) or
            ((data[offset + 2].toLong() and 0xFFL) shl 8) or
            (data[offset + 3].toLong() and 0xFFL)
    }

    private fun readBe64(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFFL) shl 56) or
            ((data[offset + 1].toLong() and 0xFFL) shl 48) or
            ((data[offset + 2].toLong() and 0xFFL) shl 40) or
            ((data[offset + 3].toLong() and 0xFFL) shl 32) or
            ((data[offset + 4].toLong() and 0xFFL) shl 24) or
            ((data[offset + 5].toLong() and 0xFFL) shl 16) or
            ((data[offset + 6].toLong() and 0xFFL) shl 8) or
            (data[offset + 7].toLong() and 0xFFL)
    }

    private fun isAacAdtsSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // ADTS sync: 11111111 1111x00x (layer bits == 00)
        return b0 == 0xFF && (b1 and 0xF6) == 0xF0
    }

    private fun isMp3Signature(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) {
            return true
        }
        if (bytes.size < 2) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // MPEG audio frame sync: 11111111 111xxxxx with non-zero layer bits (excludes ADTS)
        return b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0
    }

    private fun readLe16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLe32(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
