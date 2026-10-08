package moe.ouom.neriplayer.core.player.audio.effects

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings

internal object PlayerManagerAudioEffectsPort : AudioEffectsPort {
    override fun usbBitPerfect(): Boolean = PlayerManager.usbExclusivePreferences.bitPerfect

    override fun onDspActiveChanged(active: Boolean, reason: String) {
        NPLogger.i("NERI-AudioEffects", "DSP active=$active reason=$reason")
        PlayerManager.updateAudioOffloadPreferences("audio_effects_$reason")
    }

    override suspend fun persist(settings: AudioEffectsSettings) {
        settingsRepo.setAudioEffectsSettings(settings)
    }
}
