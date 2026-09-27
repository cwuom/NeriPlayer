package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.di.AppContainer

internal interface PlaybackStatsWritePort {
    suspend fun record(snapshot: PlaybackStatsSnapshot)

    fun hasPendingWrites(): Boolean

    suspend fun flushPendingWrites()
}

internal object AppPlaybackStatsWritePort : PlaybackStatsWritePort {
    override suspend fun record(snapshot: PlaybackStatsSnapshot) {
        AppContainer.playbackStatsRepo.recordListenDeltaNow(
            song = snapshot.song,
            listenedMs = snapshot.listenedMs,
            playCountIncrement = snapshot.playCountIncrement,
            scheduleSync = snapshot.scheduleSync
        )
        countedLocalPlaylistId(snapshot)?.let { playlistId ->
            AppContainer.localPlaylistPlaybackStatsRepo.recordPlayNow(playlistId)
        }
    }

    override fun hasPendingWrites(): Boolean = AppContainer.playbackStatsRepo.hasPendingWrites()

    override suspend fun flushPendingWrites() {
        AppContainer.playbackStatsRepo.flushPendingWrites()
    }
}

internal fun countedLocalPlaylistId(snapshot: PlaybackStatsSnapshot): Long? =
    if (snapshot.playCountIncrement > 0) snapshot.localPlaylistId else null
