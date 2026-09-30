package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.runtime.stats.countedLocalPlaylistId
import moe.ouom.neriplayer.core.player.host.PlayerDependencies

internal object AppPlaybackStatsWritePort : PlaybackStatsWritePort {
    override suspend fun record(snapshot: PlaybackStatsSnapshot) {
        PlayerDependencies.repositories.playbackStatsRepo.recordListenDeltaNow(
            song = snapshot.song,
            listenedMs = snapshot.listenedMs,
            playCountIncrement = snapshot.playCountIncrement,
            scheduleSync = snapshot.scheduleSync
        )
        countedLocalPlaylistId(snapshot)?.let { playlistId ->
            PlayerDependencies.repositories.localPlaylistPlaybackStatsRepo.recordPlayNow(playlistId)
        }
    }

    override fun hasPendingWrites(): Boolean = PlayerDependencies.repositories.playbackStatsRepo.hasPendingWrites()

    override suspend fun flushPendingWrites() {
        PlayerDependencies.repositories.playbackStatsRepo.flushPendingWrites()
    }
}
