package moe.ouom.neriplayer.core.player.policy.offload

import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import kotlin.math.abs

private const val PLAYBACK_PARAMETER_EPSILON = 0.001f

internal fun requiresPcmAudioProcessing(
    usbExclusivePlaybackEnabled: Boolean,
    playbackSpeed: Float,
    playbackPitch: Float,
    equalizerEnabled: Boolean,
    loudnessGainMb: Int,
    volumeBalance: Float,
    volumeNormalizationEnabled: Boolean,
    highResolutionOutputEnabled: Boolean,
    audioReactiveActive: Boolean,
    audioSource: PlaybackAudioSource?,
    listenTogetherPlaybackRate: Float,
): Boolean {
    // 网易云直链和 B 站换源都容易触发系统 offload 残留缓冲，主动走 PCM 管线
    return audioSource == PlaybackAudioSource.NETEASE ||
        audioSource == PlaybackAudioSource.BILIBILI ||
        usbExclusivePlaybackEnabled ||
        abs(playbackSpeed - 1f) > PLAYBACK_PARAMETER_EPSILON ||
        abs(playbackPitch - 1f) > PLAYBACK_PARAMETER_EPSILON ||
        equalizerEnabled ||
        loudnessGainMb != 0 ||
        abs(volumeBalance) > PLAYBACK_PARAMETER_EPSILON ||
        volumeNormalizationEnabled ||
        highResolutionOutputEnabled ||
        audioReactiveActive ||
        abs(listenTogetherPlaybackRate - 1f) > PLAYBACK_PARAMETER_EPSILON
}

internal fun shouldUpdateAudioOffloadForReactiveChange(
    audioReactiveEnabled: Boolean,
    playbackActive: Boolean,
): Boolean {
    return audioReactiveEnabled || !playbackActive
}
