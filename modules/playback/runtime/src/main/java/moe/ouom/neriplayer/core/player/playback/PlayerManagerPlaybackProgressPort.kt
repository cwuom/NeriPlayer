package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.player.runtime.progress.PlaybackProgressPort

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.currentPositionMsOrZero
import moe.ouom.neriplayer.core.player.durationMsOrZero
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.data.model.SongItem

internal object PlayerManagerPlaybackProgressPort : PlaybackProgressPort {
    override fun rememberLongFormEnabled(): Boolean = PlayerManager.rememberLongFormPlaybackProgressEnabled

    override fun rememberedPosition(song: SongItem): Long =
        PlayerDependencies.repositories.playHistoryRepo.rememberedPlaybackPosition(song)

    override fun writeRememberedPosition(song: SongItem, positionMs: Long) {
        PlayerDependencies.repositories.playHistoryRepo.updateRememberedPlaybackPosition(song, positionMs)
    }

    override fun currentSong(): SongItem? = PlayerManager.currentSongFlow.value

    override fun reportedPositionMs(): Long = PlayerManager.playbackPositionFlow.value

    override fun playerPositionMs(): Long? {
        if (!PlayerManager.isPlayerInitialized()) return null
        return PlayerManager.player.currentPositionMsOrZero
    }

    override fun playerDurationMs(): Long? {
        if (!PlayerManager.isPlayerInitialized()) return null
        return PlayerManager.player.durationMsOrZero
    }

    override fun updateQueuedDurationIfUnknown(song: SongItem, durationMs: Long): Boolean =
        PlayerManager.updateQueuedSong(song) { queued ->
            queuedDurationIfUnknown(queued, durationMs)
        } != null

    private fun queuedDurationIfUnknown(queued: SongItem, durationMs: Long): SongItem? =
        if (queued.durationMs <= 0L) queued.copy(durationMs = durationMs) else null

    override fun replaceCurrentSong(song: SongItem) {
        PlayerManager.setCurrentSongForPlayback(song)
    }

    override fun scheduleImmediateStatePersist() {
        PlayerManager.scheduleStatePersist(debounceMs = 0L)
    }

    override fun pendingMediaLoadActive(): Boolean = PlayerManager.isPendingMediaLoadActive()

    override fun pendingMediaLoadPositionMs(): Long = PlayerManager.pendingMediaLoadPositionMs
}
