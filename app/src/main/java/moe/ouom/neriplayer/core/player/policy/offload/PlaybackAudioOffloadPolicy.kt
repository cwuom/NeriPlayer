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
    // 网易云直链在部分设备的系统卸载输出会反复重配，保持 PCM 管线避免视觉设置影响播放
    return audioSource == PlaybackAudioSource.NETEASE ||
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
