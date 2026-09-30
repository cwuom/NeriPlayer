package moe.ouom.neriplayer.core.player.audio.processing

import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal class Pcm16VolumeNormalizer : VolumeNormalizer() {
    override val bytesPerSample: Int = BYTES_PER_PCM16_SAMPLE

    override fun analyze(buffer: ByteBuffer, stats: Pcm16LevelStats) {
        analyzePcm16(buffer, stats)
    }

    override fun readNormalizedAt(buffer: ByteBuffer, byteIndex: Int): Float {
        return buffer.getShort(byteIndex) / PCM16_FULL_SCALE
    }

    override fun writeScaledSample(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        gain: Float
    ) {
        outputBuffer.putShort(scalePcm16(inputBuffer.short, gain))
    }

    private fun scalePcm16(sample: Short, gain: Float): Short {
        return (sample.toInt() * gain)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    companion object {
        private const val BYTES_PER_PCM16_SAMPLE = 2
        private const val PCM16_FULL_SCALE = 32768f
    }
}
