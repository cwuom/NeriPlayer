package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.core.player.LocalPlaylistPlaybackSource
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSessionSnapshot

internal data class LanGuestPlaybackSnapshot(
    val queueSession: PlayerQueueSessionSnapshot,
    val song: SongItem?,
    val positionMs: Long,
    val mediaUrl: String?,
    val mediaUrlResolvedAtMs: Long,
    val audioInfo: PlaybackAudioInfo?,
    val localPlaylistSource: LocalPlaylistPlaybackSource?,
) {
    fun restore() = with(PlayerManager) {
        queueStore.restoreSession(queueSession.queue, queueSession.shuffleEnabled, queueSession.shuffleRestore)
        bumpCurrentQueueDisplayRevision()
        localPlaylistPlaybackSource = localPlaylistSource
        _currentMediaUrl.value = mediaUrl
        currentMediaUrlResolvedAtMs = mediaUrlResolvedAtMs
        _currentPlaybackAudioInfo.value = audioInfo
        setCurrentSongForPlayback(song)
        _playbackPositionMs.value = positionMs
    }

    companion object {
        fun capture(): LanGuestPlaybackSnapshot = with(PlayerManager) {
            LanGuestPlaybackSnapshot(
                queueSession = queueStore.sessionSnapshot(),
                song = currentSongFlow.value,
                positionMs = playbackPositionFlow.value,
                mediaUrl = currentMediaUrlFlow.value,
                mediaUrlResolvedAtMs = currentMediaUrlResolvedAtMs,
                audioInfo = currentPlaybackAudioInfoFlow.value,
                localPlaylistSource = localPlaylistPlaybackSource,
            )
        }
    }
}
