package moe.ouom.neriplayer.core.player.policy.offload

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

enum class PcmAudioRequirement {
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

fun pcmAudioRequirements(
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
    addSourceRequirements(audioSource, usbExclusivePlaybackEnabled)
    addPlaybackParameterRequirements(playbackSpeed, playbackPitch)
    addSoundEffectRequirements(equalizerEnabled, loudnessGainMb, volumeBalance, volumeNormalizationEnabled)
    addOutputRequirements(highResolutionOutputEnabled, audioReactiveActive, listenTogetherPlaybackRate)
}

fun shouldUpdateAudioOffloadForReactiveChange(
    audioReactiveEnabled: Boolean,
    playbackActive: Boolean,
    currentAudioReactiveEnabled: Boolean = audioReactiveEnabled,
): Boolean {
    return audioReactiveEnabled == currentAudioReactiveEnabled &&
        (currentAudioReactiveEnabled || !playbackActive)
}
