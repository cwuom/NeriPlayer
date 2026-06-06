package moe.ouom.neriplayer.data.sync.github

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.stats.TrackStat

internal object SyncPlaybackStatMapper {
    fun shouldSync(stat: TrackStat, context: Context): Boolean {
        return stat.localFilePath.isNullOrBlank() &&
            !LocalSongSupport.isLocalSong(stat.album, stat.mediaUri, stat.albumId, context)
    }

    fun fromTrackStat(stat: TrackStat): SyncTrackStat {
        return SyncTrackStat(
            identityKey = stat.identityKey,
            name = stat.name,
            artist = stat.artist,
            album = stat.album,
            totalListenMs = stat.totalListenMs,
            playCount = stat.playCount,
            lastPlayedAt = stat.lastPlayedAt,
            firstPlayedAt = stat.firstPlayedAt,
            coverUrl = stat.coverUrl,
            durationMs = stat.durationMs,
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(stat.mediaUri),
            id = stat.id,
            albumId = stat.albumId
        )
    }

    fun sanitize(stat: SyncTrackStat, context: Context): SyncTrackStat? {
        if (stat.identityKey.isBlank()) return null
        if (LocalSongSupport.isLocalSong(stat.album, stat.mediaUri, stat.albumId, context)) {
            return null
        }
        val lastPlayedAt = stat.lastPlayedAt.coerceAtLeast(0L)
        val firstPlayedAt = stat.firstPlayedAt.coerceAtLeast(0L).let {
            if (lastPlayedAt > 0L && (it == 0L || it > lastPlayedAt)) lastPlayedAt else it
        }
        return stat.copy(
            totalListenMs = stat.totalListenMs.coerceAtLeast(0L),
            playCount = stat.playCount.coerceAtLeast(0),
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            durationMs = stat.durationMs.coerceAtLeast(0L),
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(stat.mediaUri)
        )
    }

    fun sameMetadata(a: SyncTrackStat, b: SyncTrackStat): Boolean {
        return a.identityKey == b.identityKey &&
            a.name == b.name &&
            a.artist == b.artist &&
            a.album == b.album &&
            a.totalListenMs == b.totalListenMs &&
            a.playCount == b.playCount &&
            a.lastPlayedAt == b.lastPlayedAt &&
            a.firstPlayedAt == b.firstPlayedAt &&
            a.coverUrl == b.coverUrl &&
            a.durationMs == b.durationMs &&
            a.mediaUri == b.mediaUri &&
            a.id == b.id &&
            a.albumId == b.albumId
    }
}
