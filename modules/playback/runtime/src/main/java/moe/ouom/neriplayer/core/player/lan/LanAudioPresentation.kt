package moe.ouom.neriplayer.core.player.lan

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.lanltw.audio.LanAudioState

/** Publish room playback state through the flows consumed by UI and the media service */
internal class LanAudioPresentation {
    private var lastPlaying = false
    fun update(
        state: LanAudioState,
        playbackState: Int = if (state.durationSeconds > 0) Player.STATE_READY else Player.STATE_BUFFERING,
        playbackRequested: Boolean = state.playing,
    ) {
        if (!LanPlaybackBinding.externalAudio) return
        val player = PlayerManager
        player.stopProgressUpdates()
        val positionMs = (state.positionSeconds * 1000).toLong()
        val positionChanged = player._playbackPositionMs.value != positionMs
        player._playbackPositionMs.value = positionMs
        player._isPlayingFlow.value = state.playing
        player._playbackControlPlayingFlow.value = playbackRequested
        player._playWhenReadyFlow.value = playbackRequested
        if (state.durationSeconds > 0) player.playbackProgressOwner.setDuration((state.durationSeconds * 1000).toLong())
        player._playerPlaybackStateFlow.value = playbackState
        if (lastPlaying != state.playing) {
            lastPlaying = state.playing
            player.syncPlaybackStatsPlayingState(state.playing, reason = "lan_media3")
        }
        if (positionChanged) player.updateExternalBluetoothLyricLine(positionMs)
        if (state.playing) player.playbackStatsOwner.onProgress(positionMs, writesEnabled = true)
    }
}
