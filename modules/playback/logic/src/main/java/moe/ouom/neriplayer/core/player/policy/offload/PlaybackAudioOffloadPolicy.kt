package moe.ouom.neriplayer.core.player.policy.offload

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

enum class PcmAudioRequirement {
    NETEASE_STREAM,
    BILIBILI_STREAM,
    USB_EXCLUSIVE,
    PLAYBACK_SPEED,
    PLAYBACK_PITCH,
    AUDIO_EFFECTS,
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
    audioEffectsActive: Boolean,
    volumeBalance: Float,
    volumeNormalizationEnabled: Boolean,
    highResolutionOutputEnabled: Boolean,
    audioReactiveActive: Boolean,
    audioSource: PlaybackAudioSource?,
    listenTogetherPlaybackRate: Float,
): Set<PcmAudioRequirement> = buildSet {
    addSourceRequirements(audioSource, usbExclusivePlaybackEnabled)
    addPlaybackParameterRequirements(playbackSpeed, playbackPitch)
    addSoundEffectRequirements(audioEffectsActive, volumeBalance, volumeNormalizationEnabled)
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
