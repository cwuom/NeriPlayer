package moe.ouom.neriplayer.core.player.policy.offload

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import kotlin.math.abs

private const val PLAYBACK_PARAMETER_EPSILON = 0.001f

internal enum class PcmAudioRequirement {
    NETEASE_STREAM,
    BILIBILI_STREAM,
    USB_EXCLUSIVE,
    PLAYBACK_SPEED,
    PLAYBACK_PITCH,
    EQUALIZER,
    LOUDNESS,
    BALANCE,
    VOLUME_NORMALIZATION,
    HIGH_RESOLUTION,
    AUDIO_REACTIVE,
    LISTEN_TOGETHER_RATE,
}

internal fun pcmAudioRequirements(
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
): Set<PcmAudioRequirement> = buildSet {
    // 网易云直链和 B 站换源都容易触发系统 offload 残留缓冲，主动走 PCM 管线
    if (audioSource == PlaybackAudioSource.NETEASE) add(PcmAudioRequirement.NETEASE_STREAM)
    if (audioSource == PlaybackAudioSource.BILIBILI) add(PcmAudioRequirement.BILIBILI_STREAM)
    if (usbExclusivePlaybackEnabled) add(PcmAudioRequirement.USB_EXCLUSIVE)
    if (abs(playbackSpeed - 1f) > PLAYBACK_PARAMETER_EPSILON) add(PcmAudioRequirement.PLAYBACK_SPEED)
    if (abs(playbackPitch - 1f) > PLAYBACK_PARAMETER_EPSILON) add(PcmAudioRequirement.PLAYBACK_PITCH)
    if (equalizerEnabled) add(PcmAudioRequirement.EQUALIZER)
    if (loudnessGainMb != 0) add(PcmAudioRequirement.LOUDNESS)
    if (abs(volumeBalance) > PLAYBACK_PARAMETER_EPSILON) add(PcmAudioRequirement.BALANCE)
    if (volumeNormalizationEnabled) add(PcmAudioRequirement.VOLUME_NORMALIZATION)
    if (highResolutionOutputEnabled) add(PcmAudioRequirement.HIGH_RESOLUTION)
    if (audioReactiveActive) add(PcmAudioRequirement.AUDIO_REACTIVE)
    if (abs(listenTogetherPlaybackRate - 1f) > PLAYBACK_PARAMETER_EPSILON) {
        add(PcmAudioRequirement.LISTEN_TOGETHER_RATE)
    }
}

internal fun shouldUpdateAudioOffloadForReactiveChange(
    audioReactiveEnabled: Boolean,
    playbackActive: Boolean,
): Boolean {
    return audioReactiveEnabled || !playbackActive
}
