@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.audio

import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

internal const val HIGH_RATE_PCM_SAMPLE_RATE_HZ = 176_400
private const val STANDARD_PCM_BUFFER_SECONDS = 1L
private const val HIGH_RATE_PCM_BUFFER_SECONDS = 2L
private const val PCM_BUFFER_HEADROOM_FACTOR = 2L
private const val PCM_BUFFER_BUDGET_BYTES = 4 * 1024 * 1024

internal object SystemAudioTrackBufferSizeProvider :
    DefaultAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()) {
    override fun getPcmBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        samplingRate: Int,
        frameSize: Int
    ): Int {
        val defaultSize = super.getPcmBufferSizeInBytes(minBufferSizeInBytes, samplingRate, frameSize)
        val minSeconds = if (samplingRate >= HIGH_RATE_PCM_SAMPLE_RATE_HZ) {
            HIGH_RATE_PCM_BUFFER_SECONDS
        } else {
            STANDARD_PCM_BUFFER_SECONDS
        }
        // 动态调度会用掉一部分已写入的音频，再留出一次延迟供数的余量
        val targetFrames = maxOf(
            samplingRate * minSeconds,
            defaultSize * PCM_BUFFER_HEADROOM_FACTOR / frameSize
        )
        val budgetFrames = PCM_BUFFER_BUDGET_BYTES.toLong() / frameSize
        val targetBytes = minOf(targetFrames, budgetFrames) * frameSize
        return maxOf(defaultSize, targetBytes.toInt())
    }
}
