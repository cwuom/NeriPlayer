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
        val wavAudioFormatTag: Int? = null
    )

    fun inspectFile(file: File): InspectedAudio {
        if (!file.isFile || file.length() < 4L) {
            return InspectedAudio(ContainerFormat.UNKNOWN, "application/octet-stream", isLossless = false)
        }
        val headSize = minOf(file.length(), 4096L).toInt()
        val head = ByteArray(headSize)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(head)
        }
        return inspectBytes(head, file.length())
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
            } else if (chunkId == "data") {
                dataOffset = payloadOffset.toLong()
                dataSize = minOf(chunkSize, maxOf(0L, totalFileLength - payloadOffset.toLong()))
                break
            }
            if (chunkSize < 0L || chunkSize > bytes.size) break
            val padded = chunkSize + (chunkSize and 1L)
            val next = payloadOffset.toLong() + padded
            if (next <= offset || next > Int.MAX_VALUE) break
            offset = next.toInt()
        }

        val lossless = audioFormatTag == null || audioFormatTag == 1 || audioFormatTag == 0xFFFE
        return InspectedAudio(
            format = ContainerFormat.WAV,
            mimeType = "audio/wav",
            isLossless = lossless,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channelCount = channelCount,
            wavDataOffset = dataOffset,
            wavDataSize = dataSize,
            wavAudioFormatTag = audioFormatTag
        )
    }

    private fun inspectMp4Container(bytes: ByteArray): InspectedAudio {
        // Heuristic scan of initial header boxes for 'alac' atom
        val alacTag = byteArrayOf('a'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte())
        var hasAlac = false
        for (i in 0..(bytes.size - 4)) {
            if (bytes[i] == alacTag[0] &&
                bytes[i + 1] == alacTag[1] &&
                bytes[i + 2] == alacTag[2] &&
                bytes[i + 3] == alacTag[3]
            ) {
                hasAlac = true
                break
            }
        }
        return if (hasAlac) {
            InspectedAudio(
                format = ContainerFormat.ALAC_MP4,
                mimeType = "audio/alac",
                isLossless = true
            )
        } else {
            InspectedAudio(
                format = ContainerFormat.AAC_OR_MP4,
                mimeType = "audio/mp4",
                isLossless = false
            )
        }
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
