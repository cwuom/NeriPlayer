@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.audio

import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

internal const val HIGH_RATE_PCM_SAMPLE_RATE_HZ = 176_400
private const val HIGH_RATE_PCM_BUFFER_SECONDS = 2L
private const val HIGH_RATE_PCM_BUFFER_BUDGET_BYTES = 4 * 1024 * 1024

internal object SystemAudioTrackBufferSizeProvider :
    DefaultAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()) {
    override fun getPcmBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        samplingRate: Int,
        frameSize: Int
    ): Int {
        val defaultSize = super.getPcmBufferSizeInBytes(minBufferSizeInBytes, samplingRate, frameSize)
        if (samplingRate < HIGH_RATE_PCM_SAMPLE_RATE_HZ) return defaultSize

        // 已观测到接近 1.5 秒的供数间隔，用实际 PCM 帧宽计算余量
        val targetFrames = samplingRate * HIGH_RATE_PCM_BUFFER_SECONDS
        val budgetFrames = HIGH_RATE_PCM_BUFFER_BUDGET_BYTES.toLong() / frameSize
        val targetBytes = minOf(targetFrames, budgetFrames) * frameSize
        return maxOf(defaultSize, targetBytes.toInt())
    }
}
