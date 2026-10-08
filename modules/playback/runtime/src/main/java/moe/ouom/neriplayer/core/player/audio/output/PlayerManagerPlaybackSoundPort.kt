package moe.ouom.neriplayer.core.player.audio.output

import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lifecycle.scheduleUsbAudioSinkReconfiguration
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundConfig

internal object PlayerManagerPlaybackSoundPort : PlaybackSoundPort {
    override fun lyriconEnabled(): Boolean = PlayerManager.lyriconEnabled

    override fun usbExclusiveEnabled(): Boolean = PlayerManager.usbExclusivePlaybackEnabled

    override fun updateLyriconSpeed(speed: Float) {
        LyriconManager.setPlaybackSpeed(speed)
    }

    override fun updateAudioOffloadPreferences(reason: String) {
        PlayerManager.updateAudioOffloadPreferences(reason)
    }

    override fun reconfigureUsbSinkForHighResolution() {
        PlayerManager.scheduleUsbAudioSinkReconfiguration(
            reason = "playback_high_resolution_output_changed",
            allowWhilePlaybackActive = true,
            bypassCooldown = true
        )
    }

    override fun debugStackHint(): String = PlayerManager.debugStackHint()

    override suspend fun persistConfig(config: PlaybackSoundConfig) {
        settingsRepo.setPlaybackSpeed(config.speed)
        settingsRepo.setPlaybackPitch(config.pitch)
        settingsRepo.setPlaybackVolumeBalance(config.volumeBalance)
        settingsRepo.setPlaybackVolumeNormalizationEnabled(config.volumeNormalizationEnabled)
    }

    override suspend fun persistHighResolution(enabled: Boolean) {
        settingsRepo.setPlaybackHighResolutionOutputEnabled(enabled)
    }
}
