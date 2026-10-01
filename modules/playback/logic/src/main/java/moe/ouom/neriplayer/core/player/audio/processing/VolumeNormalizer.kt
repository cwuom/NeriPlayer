package moe.ouom.neriplayer.core.player.audio.processing

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 与采样格式无关的响度归一化核心: 增益追踪, 限幅包络等纯数学逻辑集中在此
 * 采样点的读取/写入由子类按 PCM_16BIT 或 PCM_FLOAT 实现
 */
internal abstract class VolumeNormalizer {
    private var currentGain = 1f
    private var limiterGain = 1f
    private var accumulatedSumSquares = 0.0
    private var analyzedSampleCount = 0L
    private var analyzedPeak = 0f
    private var limiterEnvelope = FloatArray(0)

    /** 单个采样点的字节数 (16-bit=2, float=4) */
    protected abstract val bytesPerSample: Int

    /** 统计当前缓冲区的 rms/peak/采样点数, 不得移动 buffer 的 position */
    protected abstract fun analyze(buffer: ByteBuffer, stats: Pcm16LevelStats)

    /** 按绝对字节偏移读取归一化到 [-1, 1] 的采样值, 不得移动 buffer 的 position */
    protected abstract fun readNormalizedAt(buffer: ByteBuffer, byteIndex: Int): Float

    /** 顺序读取一个采样, 乘增益后写出, 读写各前进一个采样 */
    protected abstract fun writeScaledSample(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        gain: Float
    )

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
        analyze(inputBuffer, stats)
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
        writeNormalizedFrames(inputBuffer, outputBuffer, channelCount, frameCount)
        currentGain = nextGain
        limiterGain = limiterEnvelope[frameCount - 1]
    }

    private fun writeNormalizedFrames(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        channelCount: Int,
        frameCount: Int
    ) {
        repeat(frameCount) { frameIndex ->
            val gain = limiterEnvelope[frameIndex]
            repeat(channelCount) {
                writeScaledSample(inputBuffer, outputBuffer, gain)
            }
        }
        val tailGain = limiterEnvelope[frameCount - 1]
        while (inputBuffer.hasRemaining()) {
            if (inputBuffer.remaining() >= bytesPerSample) {
                writeScaledSample(inputBuffer, outputBuffer, tailGain)
            } else {
                outputBuffer.put(inputBuffer.get())
            }
        }
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
        val frameSizeBytes = channelCount * bytesPerSample
        val inputStart = inputBuffer.position()
        repeat(frameCount) { frameIndex ->
            val frameStart = inputStart + frameIndex * frameSizeBytes
            var framePeak = 0f
            repeat(channelCount) { channelIndex ->
                val sample = readNormalizedAt(
                    inputBuffer,
                    frameStart + channelIndex * bytesPerSample
                )
                framePeak = maxOf(framePeak, abs(sample))
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

    companion object {
        const val TARGET_RMS = 0.12589254f
        const val SILENCE_GATE_RMS = 0.00177828f
        const val MIN_GAIN = 0.25118864f
        const val MAX_GAIN = 1.9952623f
        const val PEAK_CEILING = 0.7943282f
        const val GAIN_REDUCTION_TIME_SECONDS = 0.25f
        const val GAIN_INCREASE_TIME_SECONDS = 4f
        const val LIMITER_ATTACK_TIME_SECONDS = 0.005f
        const val LIMITER_RELEASE_TIME_SECONDS = 0.1f
    }
}
