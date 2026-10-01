package moe.ouom.neriplayer.core.player.policy.offload

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import kotlin.math.abs

private const val PLAYBACK_PARAMETER_EPSILON = 0.001f

internal fun MutableSet<PcmAudioRequirement>.addSourceRequirements(
    audioSource: PlaybackAudioSource?,
    usbExclusivePlaybackEnabled: Boolean
) {
    // 网易云直链和 B 站换源都容易触发系统 offload 残留缓冲，主动走 PCM 管线
    if (audioSource == PlaybackAudioSource.NETEASE) add(PcmAudioRequirement.NETEASE_STREAM)
    if (audioSource == PlaybackAudioSource.BILIBILI) add(PcmAudioRequirement.BILIBILI_STREAM)
    if (usbExclusivePlaybackEnabled) add(PcmAudioRequirement.USB_EXCLUSIVE)
}

internal fun MutableSet<PcmAudioRequirement>.addPlaybackParameterRequirements(
    playbackSpeed: Float,
    playbackPitch: Float
) {
    if (abs(playbackSpeed - 1f) > PLAYBACK_PARAMETER_EPSILON) add(PcmAudioRequirement.PLAYBACK_SPEED)
    if (abs(playbackPitch - 1f) > PLAYBACK_PARAMETER_EPSILON) add(PcmAudioRequirement.PLAYBACK_PITCH)
}

internal fun MutableSet<PcmAudioRequirement>.addSoundEffectRequirements(
    equalizerEnabled: Boolean,
    loudnessGainMb: Int,
    volumeBalance: Float,
    volumeNormalizationEnabled: Boolean
) {
    if (equalizerEnabled) add(PcmAudioRequirement.EQUALIZER)
    if (loudnessGainMb != 0) add(PcmAudioRequirement.LOUDNESS)
    if (abs(volumeBalance) > PLAYBACK_PARAMETER_EPSILON) add(PcmAudioRequirement.BALANCE)
    if (volumeNormalizationEnabled) add(PcmAudioRequirement.VOLUME_NORMALIZATION)
}

internal fun MutableSet<PcmAudioRequirement>.addOutputRequirements(
    highResolutionOutputEnabled: Boolean,
    audioReactiveActive: Boolean,
    listenTogetherPlaybackRate: Float
) {
    if (highResolutionOutputEnabled) add(PcmAudioRequirement.HIGH_RESOLUTION)
    if (audioReactiveActive) add(PcmAudioRequirement.AUDIO_REACTIVE)
    if (abs(listenTogetherPlaybackRate - 1f) > PLAYBACK_PARAMETER_EPSILON) {
        add(PcmAudioRequirement.LISTEN_TOGETHER_RATE)
    }
}
