package moe.ouom.neriplayer.core.player.dlna.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Bit-exact integer PCM sample packing utilities without EQ, volume scaling,
 * dithering, or resampling.
 */
object PcmSampleConverter {

    fun convertToTargetLePcm(
        input: ByteBuffer,
        inputEncoding: Int,
        targetBitsPerSample: Int
    ): ByteArray {
        val dup = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return when (inputEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                when (targetBitsPerSample) {
                    16 -> {
                        val bytes = ByteArray(dup.remaining())
                        dup.get(bytes)
                        bytes
                    }
                    24 -> {
                        val sampleCount = dup.remaining() / 2
                        val out = ByteArray(sampleCount * 3)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val s16 = dup.short.toInt()
                            val s24 = s16 shl 8
                            out[outIdx++] = (s24 and 0xFF).toByte()
                            out[outIdx++] = ((s24 ushr 8) and 0xFF).toByte()
                            out[outIdx++] = ((s24 ushr 16) and 0xFF).toByte()
                        }
                        out
                    }
                    32 -> {
                        val sampleCount = dup.remaining() / 2
                        val out = ByteArray(sampleCount * 4)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val s16 = dup.short.toInt()
                            val s32 = s16 shl 16
                            out[outIdx++] = (s32 and 0xFF).toByte()
                            out[outIdx++] = ((s32 ushr 8) and 0xFF).toByte()
                            out[outIdx++] = ((s32 ushr 16) and 0xFF).toByte()
                            out[outIdx++] = ((s32 ushr 24) and 0xFF).toByte()
                        }
                        out
                    }
                    else -> error("Unsupported target bit depth: $targetBitsPerSample")
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                when (targetBitsPerSample) {
                    24 -> {
                        val bytes = ByteArray(dup.remaining())
                        dup.get(bytes)
                        bytes
                    }
                    32 -> {
                        val sampleCount = dup.remaining() / 3
                        val out = ByteArray(sampleCount * 4)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val b0 = dup.get()
                            val b1 = dup.get()
                            val b2 = dup.get()
                            out[outIdx++] = 0
                            out[outIdx++] = b0
                            out[outIdx++] = b1
                            out[outIdx++] = b2
                        }
                        out
                    }
                    16 -> {
                        val sampleCount = dup.remaining() / 3
                        val out = ByteArray(sampleCount * 2)
                        var outIdx = 0
                        repeat(sampleCount) {
                            dup.get() // drop LSB byte
                            out[outIdx++] = dup.get()
                            out[outIdx++] = dup.get()
                        }
                        out
                    }
                    else -> error("Unsupported target bit depth: $targetBitsPerSample")
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                when (targetBitsPerSample) {
                    32 -> {
                        val bytes = ByteArray(dup.remaining())
                        dup.get(bytes)
                        bytes
                    }
                    24 -> {
                        val sampleCount = dup.remaining() / 4
                        val out = ByteArray(sampleCount * 3)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val s32 = dup.int
                            val s24 = s32 shr 8
                            out[outIdx++] = (s24 and 0xFF).toByte()
                            out[outIdx++] = ((s24 ushr 8) and 0xFF).toByte()
                            out[outIdx++] = ((s24 ushr 16) and 0xFF).toByte()
                        }
                        out
                    }
                    16 -> {
                        val sampleCount = dup.remaining() / 4
                        val out = ByteArray(sampleCount * 2)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val s32 = dup.int
                            val s16 = s32 shr 16
                            out[outIdx++] = (s16 and 0xFF).toByte()
                            out[outIdx++] = ((s16 ushr 8) and 0xFF).toByte()
                        }
                        out
                    }
                    else -> error("Unsupported target bit depth: $targetBitsPerSample")
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val sampleCount = dup.remaining() / 4
                when (targetBitsPerSample) {
                    16 -> {
                        val out = ByteArray(sampleCount * 2)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val f = dup.float.coerceIn(-1.0f, 1.0f)
                            val s16 = (f * 32768.0).roundToInt().coerceIn(-32768, 32767)
                            out[outIdx++] = (s16 and 0xFF).toByte()
                            out[outIdx++] = ((s16 ushr 8) and 0xFF).toByte()
                        }
                        out
                    }
                    24 -> {
                        val out = ByteArray(sampleCount * 3)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val f = dup.float.coerceIn(-1.0f, 1.0f)
                            val s24 = (f * 8388608.0).roundToInt().coerceIn(-8388608, 8388607)
                            out[outIdx++] = (s24 and 0xFF).toByte()
                            out[outIdx++] = ((s24 ushr 8) and 0xFF).toByte()
                            out[outIdx++] = ((s24 ushr 16) and 0xFF).toByte()
                        }
                        out
                    }
                    32 -> {
                        val out = ByteArray(sampleCount * 4)
                        var outIdx = 0
                        repeat(sampleCount) {
                            val f = dup.float.coerceIn(-1.0f, 1.0f)
                            val s32 = (f.toDouble() * 2147483648.0)
                                .coerceIn(-2147483648.0, 2147483647.0)
                                .toLong()
                                .toInt()
                            out[outIdx++] = (s32 and 0xFF).toByte()
                            out[outIdx++] = ((s32 ushr 8) and 0xFF).toByte()
                            out[outIdx++] = ((s32 ushr 16) and 0xFF).toByte()
                            out[outIdx++] = ((s32 ushr 24) and 0xFF).toByte()
                        }
                        out
                    }
                    else -> error("Unsupported target bit depth: $targetBitsPerSample")
                }
            }
            else -> error("Unsupported input PCM encoding: $inputEncoding")
        }
    }
}
