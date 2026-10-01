package moe.ouom.neriplayer.core.player.audio.processing

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

internal data class Pcm16LevelStats(
    var rms: Float = 0f,
    var peak: Float = 0f,
    var sampleCount: Int = 0
)

internal fun analyzePcm16(buffer: ByteBuffer): Pcm16LevelStats {
    return analyzePcm16(buffer, Pcm16LevelStats())
}

internal fun analyzePcm16(
    buffer: ByteBuffer,
    result: Pcm16LevelStats
): Pcm16LevelStats {
    var sumSquares = 0.0
    var peak = 0.0
    var sampleCount = 0
    var index = buffer.position()
    val lastSampleStart = buffer.limit() - 2
    while (index <= lastSampleStart) {
        val normalized = buffer.getShort(index) / 32768.0
        val absolute = abs(normalized)
        sumSquares += normalized * normalized
        peak = maxOf(peak, absolute)
        sampleCount++
        index += 2
    }
    val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount).toFloat() else 0f
    result.rms = rms
    result.peak = peak.toFloat()
    result.sampleCount = sampleCount
    return result
}

internal fun analyzePcmFloat(buffer: ByteBuffer): Pcm16LevelStats {
    return analyzePcmFloat(buffer, Pcm16LevelStats())
}

internal fun analyzePcmFloat(
    buffer: ByteBuffer,
    result: Pcm16LevelStats
): Pcm16LevelStats {
    var sumSquares = 0.0
    var peak = 0.0
    var sampleCount = 0
    var index = buffer.position()
    val lastSampleStart = buffer.limit() - BYTES_PER_FLOAT_SAMPLE
    while (index <= lastSampleStart) {
        val normalized = buffer.getFloat(index).toDouble()
        val absolute = abs(normalized)
        sumSquares += normalized * normalized
        peak = maxOf(peak, absolute)
        sampleCount++
        index += BYTES_PER_FLOAT_SAMPLE
    }
    val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount).toFloat() else 0f
    result.rms = rms
    result.peak = peak.toFloat()
    result.sampleCount = sampleCount
    return result
}

internal fun smoothingFactor(durationSeconds: Float, timeConstantSeconds: Float): Float {
    if (durationSeconds <= 0f) return 0f
    if (timeConstantSeconds <= 0f) return 1f
    return (1.0 - exp(-durationSeconds / timeConstantSeconds)).toFloat().coerceIn(0f, 1f)
}

private const val BYTES_PER_FLOAT_SAMPLE = 4
