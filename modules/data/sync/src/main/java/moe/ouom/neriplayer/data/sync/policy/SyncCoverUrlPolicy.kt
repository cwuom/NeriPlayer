package moe.ouom.neriplayer.data.sync.policy

import androidx.core.net.toUri
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncSong

private val localUriSchemes = setOf("content", "file", "android.resource")

fun isLocalMediaUri(mediaUri: String?): Boolean {
    if (mediaUri.isNullOrBlank()) return false
    if (mediaUri.startsWith("/")) return true
    if (mediaUri.startsWith("file:", ignoreCase = true)) return true
    if (mediaUri.startsWith("content://", ignoreCase = true)) return true
    if (mediaUri.startsWith("android.resource://", ignoreCase = true)) return true

    val scheme = runCatching { mediaUri.toUri().scheme.orEmpty().lowercase() }
        .getOrDefault("")
    return scheme in localUriSchemes
}

fun sanitizeCoverUrlForSync(coverUrl: String?): String? {
    val normalizedUrl = coverUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return normalizedUrl.takeUnless(::isLocalMediaUri)
}

fun SyncSong.sanitizeCoverUrlsForSync(): SyncSong {
    return copy(
        coverUrl = sanitizeCoverUrlForSync(coverUrl),
        customCoverUrl = sanitizeCoverUrlForSync(customCoverUrl),
        originalCoverUrl = sanitizeCoverUrlForSync(originalCoverUrl)
    )
}

fun SyncData.sanitizeLocalCoverUrls(): SyncData {
    return copy(
        playlists = playlists.map { playlist ->
            playlist.copy(songs = playlist.songs.map(SyncSong::sanitizeCoverUrlsForSync))
        },
        favoritePlaylists = favoritePlaylists.map { playlist ->
            playlist.copy(
                coverUrl = sanitizeCoverUrlForSync(playlist.coverUrl),
                songs = playlist.songs.map(SyncSong::sanitizeCoverUrlsForSync)
            )
        },
        recentPlays = recentPlays.map { play ->
            play.copy(song = play.song.sanitizeCoverUrlsForSync())
        },
        playbackStats = playbackStats.map { stat ->
            stat.copy(coverUrl = sanitizeCoverUrlForSync(stat.coverUrl))
        },
        playbackStatBuckets = playbackStatBuckets.map { bucket ->
            bucket.copy(coverUrl = sanitizeCoverUrlForSync(bucket.coverUrl))
        },
        playlistUsageStats = playlistUsageStats.map { stat ->
            stat.copy(coverUrl = sanitizeCoverUrlForSync(stat.coverUrl))
        }
    )
}
