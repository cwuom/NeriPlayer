package moe.ouom.neriplayer.core.player.lan

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.lanltw.audio.LanAudioState

/** Publish Web Audio state through the same native flows consumed by UI and the media service. */
internal class LanAudioPresentation {
    private var lastPlaying = false
    fun update(state: LanAudioState) {
        if (!LanPlaybackBinding.externalAudio) return
        val player = PlayerManager
        player.stopProgressUpdates()
        player._isPlayingFlow.value = state.playing
        player._playbackControlPlayingFlow.value = state.playing
        player._playWhenReadyFlow.value = state.playing
        player._playbackPositionMs.value = (state.positionSeconds * 1000).toLong()
        if (state.durationSeconds > 0) player.playbackProgressOwner.setDuration((state.durationSeconds * 1000).toLong())
        player._playerPlaybackStateFlow.value = if (state.durationSeconds > 0) Player.STATE_READY else Player.STATE_BUFFERING
        if (lastPlaying != state.playing) {
            lastPlaying = state.playing
            player.syncPlaybackStatsEnginePlayingState(state.playing)
        }
        if (state.playing) player.playbackStatsOwner.onProgress(player._playbackPositionMs.value, writesEnabled = true)
    }
}
