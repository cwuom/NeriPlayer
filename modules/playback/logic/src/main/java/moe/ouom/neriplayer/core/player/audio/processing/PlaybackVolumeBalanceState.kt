package moe.ouom.neriplayer.core.player.audio.processing

import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackVolumeBalance

object PlaybackVolumeBalanceState {
    @Volatile
    private var balance = DEFAULT_PLAYBACK_VOLUME_BALANCE

    fun update(balance: Float) {
        this.balance = normalizePlaybackVolumeBalance(balance)
    }

    fun current(): Float = balance
}
