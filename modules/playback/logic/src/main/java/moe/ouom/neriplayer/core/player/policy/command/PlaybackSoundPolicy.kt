package moe.ouom.neriplayer.core.player.policy.command

import moe.ouom.neriplayer.data.model.playback.PlaybackSoundConfig
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackPitch
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackSpeed
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackVolumeBalance

fun resolvePlaybackSoundConfigForEngine(
    baseConfig: PlaybackSoundConfig,
    listenTogetherSyncPlaybackRate: Float,
    usbExclusivePlaybackEnabled: Boolean = false
): PlaybackSoundConfig {
    val normalizedBaseConfig = baseConfig.copy(
            speed = normalizePlaybackSpeed(baseConfig.speed),
            pitch = normalizePlaybackPitch(baseConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(baseConfig.loudnessGainMb),
            volumeBalance = normalizePlaybackVolumeBalance(baseConfig.volumeBalance)
        )
    if (usbExclusivePlaybackEnabled) {
        return normalizedBaseConfig.copy(
            speed = 1f,
            pitch = 1f,
            loudnessGainMb = 0,
            volumeBalance = 0f,
            volumeNormalizationEnabled = false,
            equalizerEnabled = false
        )
    }
    val resolvedSyncRate = listenTogetherSyncPlaybackRate.coerceIn(0.95f, 1.05f)
    return normalizedBaseConfig.copy(
        speed = normalizePlaybackSpeed(normalizedBaseConfig.speed * resolvedSyncRate)
    )
}
