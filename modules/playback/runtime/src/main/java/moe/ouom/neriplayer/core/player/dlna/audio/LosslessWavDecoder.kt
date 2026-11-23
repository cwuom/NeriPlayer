package moe.ouom.neriplayer.core.player.dlna.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacHeaderInspector
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Lossless decoder that converts FLAC/ALAC/WAV to raw signed LE PCM file
 * preserving the original sample rate and bit depth with zero EQ, mixing,
 * volume scaling, or resampling.
 */
object LosslessWavDecoder {

    data class DecodedPcmResult(
        val pcmFile: File,
        val pcmDataOffsetInFile: Long,
        val pcmDataBytes: Long,
        val sampleRateHz: Int,
        val bitsPerSample: Int,
        val channelCount: Int,
        val validBitsPerSample: Int? = null,
        val channelMask: Int? = null,
        val isExtensibleWave: Boolean = false
    )

    fun decodeToPcmFile(
        sourceFile: File,
        inspected: FlacHeaderInspector.InspectedAudio,
        pcmOutputFile: File,
        hintBitDepth: Int? = null,
        isCancelled: () -> Boolean = { false }
    ): DecodedPcmResult {
        if (inspected.format == FlacHeaderInspector.ContainerFormat.WAV &&
            inspected.isLossless &&
            inspected.wavDataOffset != null &&
            inspected.wavDataSize != null &&
            (inspected.wavAudioFormatTag == 1 || inspected.wavAudioFormatTag == 0xFFFE)
        ) {
            return DecodedPcmResult(
                pcmFile = sourceFile,
                pcmDataOffsetInFile = inspected.wavDataOffset,
                pcmDataBytes = inspected.wavDataSize,
                sampleRateHz = inspected.sampleRateHz ?: 44_100,
                bitsPerSample = inspected.bitDepth ?: hintBitDepth?.takeIf { it > 0 } ?: 16,
                channelCount = inspected.channelCount ?: 2,
                validBitsPerSample = inspected.wavValidBitsPerSample,
                channelMask = inspected.wavChannelMask,
                isExtensibleWave = inspected.wavAudioFormatTag == 0xFFFE
            )
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val tempFile = File(
            pcmOutputFile.parentFile,
            "${pcmOutputFile.name}.${java.util.UUID.randomUUID().toString().take(8)}.tmp"
        )
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }
            val selectedFormat = inputFormat ?: error("No audio track found in ${sourceFile.name}")
            val mime = selectedFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
            if (inspected.format == FlacHeaderInspector.ContainerFormat.ALAC_MP4 &&
                !mime.equals("audio/alac", ignoreCase = true)
            ) {
                error("Selected MP4 audio track is $mime, expected audio/alac")
            }
            if (inspected.format == FlacHeaderInspector.ContainerFormat.FLAC &&
                !mime.equals("audio/flac", ignoreCase = true) &&
                !mime.equals("audio/x-flac", ignoreCase = true)
            ) {
                error("Selected audio track is $mime, expected audio/flac")
            }
            extractor.selectTrack(audioTrackIndex)

            val expectedSampleRate = inspected.sampleRateHz
                ?: selectedFormat.getOptionalInt(MediaFormat.KEY_SAMPLE_RATE)
                ?: 44_100
            val resolvedSourceBitDepth = inspected.bitDepth
                ?: hintBitDepth?.takeIf { it > 0 }
                ?: selectedFormat.getOptionalInt("bits-per-sample")
            if (resolvedSourceBitDepth == null &&
                inspected.format == FlacHeaderInspector.ContainerFormat.ALAC_MP4 &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            ) {
                error("Cannot verify ALAC bit depth on this Android version; aborting to avoid silent 16-bit truncation")
            }
            val expectedBitDepth = normalizeTargetBitDepth(resolvedSourceBitDepth ?: 16)

            // Request exact bit-preserving output encoding; never route 32-bit int through 24-bit float mantissa
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val desiredEncoding = when {
                    expectedBitDepth <= 16 -> AudioFormat.ENCODING_PCM_16BIT
                    expectedBitDepth == 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        AudioFormat.ENCODING_PCM_24BIT_PACKED
                    expectedBitDepth == 24 ->
                        AudioFormat.ENCODING_PCM_FLOAT // IEEE 754 binary32 has exact 24-bit significand
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        AudioFormat.ENCODING_PCM_32BIT
                    else ->
                        error("32-bit integer lossless WAV decode requires Android 12+ PCM_32BIT support")
                }
                selectedFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, desiredEncoding)
            } else if (expectedBitDepth > 16) {
                error("High-resolution $expectedBitDepth-bit WAV decode is unsupported on this Android version")
            }

            codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(selectedFormat, null, null, 0)
            } catch (err: Exception) {
                if (expectedBitDepth > 16) {
                    codec.release()
                    throw IllegalStateException(
                        "Device codec rejected $expectedBitDepth-bit lossless PCM output; aborting downgrade",
                        err
                    )
                }
                // Retry with clean track format without KEY_PCM_ENCODING for <= 16-bit default PCM decoders
                codec.release()
                val cleanDefaultFormat = extractor.getTrackFormat(audioTrackIndex)
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(cleanDefaultFormat, null, null, 0)
            }
            codec.start()

            var actualSampleRate = expectedSampleRate
            var actualChannels = inspected.channelCount
                ?: selectedFormat.getOptionalInt(MediaFormat.KEY_CHANNEL_COUNT)
                ?: 2
            var actualEncoding = AudioFormat.ENCODING_PCM_16BIT
            var formatChecked = false
            var totalPcmWritten = 0L

            fun verifyAndApplyOutputFormat(outFmt: MediaFormat) {
                actualSampleRate = outFmt.getOptionalInt(MediaFormat.KEY_SAMPLE_RATE)
                    ?: actualSampleRate
                actualChannels = outFmt.getOptionalInt(MediaFormat.KEY_CHANNEL_COUNT)
                    ?: actualChannels
                actualEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    outFmt.getOptionalInt(MediaFormat.KEY_PCM_ENCODING)
                        ?: AudioFormat.ENCODING_PCM_16BIT
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                if (inspected.sampleRateHz != null && actualSampleRate != inspected.sampleRateHz) {
                    error(
                        "Decoder altered sample rate (${inspected.sampleRateHz} -> $actualSampleRate); " +
                            "aborting to preserve lossless fidelity"
                    )
                }
                if (expectedBitDepth > 16 && actualEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                    error(
                        "Decoder downgraded $expectedBitDepth-bit PCM to 16-bit; " +
                            "aborting to preserve lossless fidelity"
                    )
                }
                if (expectedBitDepth >= 32 && actualEncoding != AudioFormat.ENCODING_PCM_32BIT) {
                    error(
                        "Decoder did not provide 32-bit integer PCM (got encoding=$actualEncoding); " +
                            "aborting to prevent precision loss"
                    )
                }
                formatChecked = true
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024).use { out ->
                while (!outputDone && !isCancelled()) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx) ?: ByteBuffer.allocate(0)
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            verifyAndApplyOutputFormat(codec.outputFormat)
                        }
                        outIdx >= 0 -> {
                            if (!formatChecked) {
                                verifyAndApplyOutputFormat(codec.outputFormat)
                            }
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = PcmSampleConverter.convertToTargetLePcm(
                                    input = outBuf,
                                    inputEncoding = actualEncoding,
                                    targetBitsPerSample = expectedBitDepth
                                )
                                out.write(chunk)
                                totalPcmWritten += chunk.size.toLong()
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                out.flush()
            }

            if (isCancelled()) {
                tempFile.delete()
                error("WAV decoding cancelled")
            }
            if (pcmOutputFile.exists()) pcmOutputFile.delete()
            check(tempFile.renameTo(pcmOutputFile)) {
                "Failed to finalize decoded PCM file"
            }

            return DecodedPcmResult(
                pcmFile = pcmOutputFile,
                pcmDataOffsetInFile = 0L,
                pcmDataBytes = totalPcmWritten,
                sampleRateHz = actualSampleRate,
                bitsPerSample = expectedBitDepth,
                channelCount = actualChannels
            )
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun normalizeTargetBitDepth(bitDepth: Int): Int = when {
        bitDepth <= 16 -> 16
        bitDepth <= 24 -> 24
        else -> 32
    }

    private fun MediaFormat.getOptionalInt(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
