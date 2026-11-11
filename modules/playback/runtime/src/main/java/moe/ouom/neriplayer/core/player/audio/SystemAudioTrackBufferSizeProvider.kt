@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.audio

import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import kotlin.math.max

internal const val HIGH_RATE_PCM_SAMPLE_RATE_HZ = 176_400
private const val HIGH_RATE_PCM_MIN_BUFFER_US = 640_000

internal object SystemAudioTrackBufferSizeProvider :
    DefaultAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()) {
    override fun getPcmBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        samplingRate: Int,
        frameSize: Int
    ): Int {
        val defaultSize = super.getPcmBufferSizeInBytes(minBufferSizeInBytes, samplingRate, frameSize)
        if (samplingRate < HIGH_RATE_PCM_SAMPLE_RATE_HZ) return defaultSize

        // 给高采样率 PCM 留出余量，吸收解码器或播放线程的短时供数间断
        return max(defaultSize, durationUsToBytes(HIGH_RATE_PCM_MIN_BUFFER_US, samplingRate, frameSize))
    }
}
