package moe.ouom.neriplayer.core.player.runtime.stats

interface PlaybackStatsWritePort {
    suspend fun record(snapshot: PlaybackStatsSnapshot)

    fun hasPendingWrites(): Boolean

    suspend fun flushPendingWrites()
}

fun countedLocalPlaylistId(snapshot: PlaybackStatsSnapshot): Long? =
    if (snapshot.playCountIncrement > 0) snapshot.localPlaylistId else null
