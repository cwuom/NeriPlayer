package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class PlaybackVolumeNormalizationSnapshot(
    val enabled: Boolean,
    val generation: Long
)

internal object PlaybackVolumeNormalizationState {
    private val ref = AtomicReference(
        PlaybackVolumeNormalizationSnapshot(enabled = false, generation = 0L)
    )

    fun updateEnabled(enabled: Boolean) {
        while (true) {
            val current = ref.get()
            if (current.enabled == enabled) return
            val next = PlaybackVolumeNormalizationSnapshot(
                enabled = enabled,
                generation = current.generation + 1L
            )
            if (ref.compareAndSet(current, next)) return
        }
    }

    fun resetForNewTrack() {
        while (true) {
            val current = ref.get()
            val next = current.copy(generation = current.generation + 1L)
            if (ref.compareAndSet(current, next)) return
        }
    }

    fun current(): PlaybackVolumeNormalizationSnapshot = ref.get()
}

@UnstableApi
internal class VolumeNormalizationAudioProcessor(
    private val stateProvider: () -> PlaybackVolumeNormalizationSnapshot =
        PlaybackVolumeNormalizationState::current
) : BaseAudioProcessor() {
    private val normalizer = Pcm16VolumeNormalizer()
    private val reusableStats = Pcm16LevelStats()
    private var sampleRate = 0
    private var channelCount = 0
    private var appliedGeneration = Long.MIN_VALUE

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0
        ) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        val outputBuffer = replaceOutputBuffer(inputSize)
        val state = stateProvider()
        if (state.generation != appliedGeneration) {
            normalizer.reset()
            appliedGeneration = state.generation
        }
        if (!state.enabled) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        normalizer.process(
            inputBuffer = inputBuffer,
            outputBuffer = outputBuffer,
            sampleRate = sampleRate,
            channelCount = channelCount,
            stats = reusableStats
        )
        outputBuffer.flip()
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        normalizer.reset()
        appliedGeneration = Long.MIN_VALUE
    }
}

internal class Pcm16VolumeNormalizer {
    private var currentGain = 1f
    private var limiterGain = 1f
    private var accumulatedSumSquares = 0.0
    private var analyzedSampleCount = 0L
    private var analyzedPeak = 0f
    private var limiterEnvelope = FloatArray(0)

    fun reset() {
        currentGain = 1f
        limiterGain = 1f
        accumulatedSumSquares = 0.0
        analyzedSampleCount = 0L
        analyzedPeak = 0f
    }

    fun process(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        sampleRate: Int,
        channelCount: Int,
        stats: Pcm16LevelStats = Pcm16LevelStats()
    ) {
        analyzePcm16(inputBuffer, stats)
        if (stats.sampleCount == 0 || sampleRate <= 0 || channelCount <= 0) {
            outputBuffer.put(inputBuffer)
            return
        }

        val frameCount = stats.sampleCount / channelCount
        if (frameCount == 0) {
            outputBuffer.put(inputBuffer)
            return
        }
        val blockDurationSeconds = frameCount.toFloat() / sampleRate
        val targetGain = observeAndResolveTargetGain(stats)
        val timeConstantSeconds = if (targetGain < currentGain) {
            GAIN_REDUCTION_TIME_SECONDS
        } else {
            GAIN_INCREASE_TIME_SECONDS
        }
        val smoothing = smoothingFactor(blockDurationSeconds, timeConstantSeconds)
        val nextGain = currentGain + (targetGain - currentGain) * smoothing
        val gainStepPerFrame = (nextGain - currentGain) / frameCount
        ensureLimiterEnvelopeCapacity(frameCount)
        buildLimiterEnvelope(
            inputBuffer = inputBuffer,
            sampleRate = sampleRate,
            channelCount = channelCount,
            frameCount = frameCount,
            baseGainStep = gainStepPerFrame
        )

        repeat(frameCount) { frameIndex ->
            val gain = limiterEnvelope[frameIndex]
            repeat(channelCount) {
                outputBuffer.putShort(scalePcm16(inputBuffer.short, gain))
            }
        }
        val tailGain = limiterEnvelope[frameCount - 1]
        while (inputBuffer.hasRemaining()) {
            if (inputBuffer.remaining() >= BYTES_PER_PCM16_SAMPLE) {
                outputBuffer.putShort(scalePcm16(inputBuffer.short, tailGain))
            } else {
                outputBuffer.put(inputBuffer.get())
            }
        }
        currentGain = nextGain
        limiterGain = tailGain
    }

    internal fun observeAndResolveTargetGain(stats: Pcm16LevelStats): Float {
        if (stats.rms < SILENCE_GATE_RMS) return currentGain
        accumulatedSumSquares += stats.rms * stats.rms * stats.sampleCount
        analyzedSampleCount += stats.sampleCount
        analyzedPeak = maxOf(analyzedPeak, stats.peak)
        val integratedRms = sqrt(accumulatedSumSquares / analyzedSampleCount).toFloat()
        val rmsGain = (TARGET_RMS / integratedRms).coerceIn(MIN_GAIN, MAX_GAIN)
        val peakGain = resolvePeakSafeGain(analyzedPeak)
        return min(rmsGain, peakGain).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    private fun resolvePeakSafeGain(peak: Float): Float {
        if (peak <= 0f) return MAX_GAIN
        return (PEAK_CEILING / peak).coerceAtMost(MAX_GAIN)
    }

    private fun ensureLimiterEnvelopeCapacity(frameCount: Int) {
        if (limiterEnvelope.size >= frameCount) return
        limiterEnvelope = FloatArray(Integer.highestOneBit(frameCount - 1).coerceAtLeast(1) shl 1)
    }

    private fun buildLimiterEnvelope(
        inputBuffer: ByteBuffer,
        sampleRate: Int,
        channelCount: Int,
        frameCount: Int,
        baseGainStep: Float
    ) {
        val frameSizeBytes = channelCount * BYTES_PER_PCM16_SAMPLE
        val inputStart = inputBuffer.position()
        repeat(frameCount) { frameIndex ->
            val frameStart = inputStart + frameIndex * frameSizeBytes
            var framePeak = 0f
            repeat(channelCount) { channelIndex ->
                val sample = inputBuffer.getShort(
                    frameStart + channelIndex * BYTES_PER_PCM16_SAMPLE
                )
                framePeak = maxOf(framePeak, kotlin.math.abs(sample / 32768f))
            }
            val baseGain = currentGain + baseGainStep * (frameIndex + 1)
            limiterEnvelope[frameIndex] = min(baseGain, resolvePeakSafeGain(framePeak))
        }

        val attackStep = limiterGainStep(sampleRate, LIMITER_ATTACK_TIME_SECONDS)
        for (frameIndex in frameCount - 2 downTo 0) {
            limiterEnvelope[frameIndex] = min(
                limiterEnvelope[frameIndex],
                limiterEnvelope[frameIndex + 1] + attackStep
            )
        }

        val releaseStep = limiterGainStep(sampleRate, LIMITER_RELEASE_TIME_SECONDS)
        var previousGain = limiterGain
        repeat(frameCount) { frameIndex ->
            val releasedGain = min(limiterEnvelope[frameIndex], previousGain + releaseStep)
            limiterEnvelope[frameIndex] = releasedGain
            previousGain = releasedGain
        }
    }

    private fun limiterGainStep(sampleRate: Int, durationSeconds: Float): Float {
        val durationFrames = sampleRate * durationSeconds
        if (durationFrames <= 1f) return MAX_GAIN - MIN_GAIN
        return (MAX_GAIN - MIN_GAIN) / durationFrames
    }

    private fun scalePcm16(sample: Short, gain: Float): Short {
        return (sample.toInt() * gain)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    companion object {
        private const val BYTES_PER_PCM16_SAMPLE = 2
        private const val TARGET_RMS = 0.12589254f
        private const val SILENCE_GATE_RMS = 0.00177828f
        private const val MIN_GAIN = 0.25118864f
        private const val MAX_GAIN = 1.9952623f
        private const val PEAK_CEILING = 0.7943282f
        private const val GAIN_REDUCTION_TIME_SECONDS = 0.25f
        private const val GAIN_INCREASE_TIME_SECONDS = 4f
        private const val LIMITER_ATTACK_TIME_SECONDS = 0.005f
        private const val LIMITER_RELEASE_TIME_SECONDS = 0.1f
    }
}

internal class Pcm16LevelStats(
    var rms: Float = 0f,
    var peak: Float = 0f,
    var sampleCount: Int = 0
)

internal fun analyzePcm16(buffer: ByteBuffer): Pcm16LevelStats {
    return analyzePcm16(buffer, Pcm16LevelStats())
}

private fun analyzePcm16(
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
        val absolute = kotlin.math.abs(normalized)
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

internal fun smoothingFactor(durationSeconds: Float, timeConstantSeconds: Float): Float {
    if (durationSeconds <= 0f) return 0f
    if (timeConstantSeconds <= 0f) return 1f
    return (1.0 - exp(-durationSeconds / timeConstantSeconds)).toFloat().coerceIn(0f, 1f)
}
