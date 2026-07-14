package moe.ouom.neriplayer.core.player.policy.offload

import kotlin.math.abs

private const val PLAYBACK_PARAMETER_EPSILON = 0.001f

internal fun requiresPcmAudioProcessing(
    usbExclusivePlaybackEnabled: Boolean,
    playbackSpeed: Float,
    playbackPitch: Float,
    equalizerEnabled: Boolean,
    loudnessGainMb: Int,
    volumeBalance: Float,
    audioReactiveActive: Boolean,
    listenTogetherPlaybackRate: Float,
): Boolean {
    return usbExclusivePlaybackEnabled ||
        abs(playbackSpeed - 1f) > PLAYBACK_PARAMETER_EPSILON ||
        abs(playbackPitch - 1f) > PLAYBACK_PARAMETER_EPSILON ||
        equalizerEnabled ||
        loudnessGainMb != 0 ||
        abs(volumeBalance) > PLAYBACK_PARAMETER_EPSILON ||
        audioReactiveActive ||
        abs(listenTogetherPlaybackRate - 1f) > PLAYBACK_PARAMETER_EPSILON
}
