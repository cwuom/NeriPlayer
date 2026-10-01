package moe.ouom.neriplayer.core.player.audio.processing

import java.nio.ByteBuffer

internal class FloatVolumeNormalizer : VolumeNormalizer() {
    override val bytesPerSample: Int = BYTES_PER_FLOAT_SAMPLE

    override fun analyze(buffer: ByteBuffer, stats: Pcm16LevelStats) {
        analyzePcmFloat(buffer, stats)
    }

    override fun readNormalizedAt(buffer: ByteBuffer, byteIndex: Int): Float {
        return buffer.getFloat(byteIndex)
    }

    override fun writeScaledSample(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        gain: Float
    ) {
        // float PCM 约定值域为 [-1, 1], 写出前钳位避免应用增益后溢出
        outputBuffer.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
    }
}


private const val BYTES_PER_FLOAT_SAMPLE = 4
