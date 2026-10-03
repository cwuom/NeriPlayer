package moe.ouom.neriplayer.data.sync.sanitize

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletionPolicy
import moe.ouom.neriplayer.data.sync.mapping.stats.SyncPlaybackStatMapping
import moe.ouom.neriplayer.data.sync.merge.song.SyncPlaylistSongMergePolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.sync.playlist.normalizedForDisplayOrder
import moe.ouom.neriplayer.data.sync.policy.SyncBiliVideoSkipMergePolicy
import moe.ouom.neriplayer.data.sync.policy.copyWithNormalizedMembershipTokens
import moe.ouom.neriplayer.data.sync.policy.hasResolvableSyncIdentity
import moe.ouom.neriplayer.data.sync.policy.sanitizeCoverUrlForSync
import moe.ouom.neriplayer.data.sync.policy.sanitizeCoverUrlsForSync

class SyncDataSanitizer(private val host: SyncSanitizationHost) {
    fun sanitize(data: SyncData): SyncData {
        return data.copy(
            playlists = data.playlists.mapNotNull { sanitizeSyncPlaylist(it) },
            favoritePlaylists = data.favoritePlaylists.map { sanitizeSyncFavoritePlaylist(it) },
            recentPlays = data.recentPlays.mapNotNull { sanitizeRecentPlay(it) },
            recentPlayDeletions = data.recentPlayDeletions.mapNotNull { sanitizeRecentPlayDeletion(it) },
            playlistSongDeletions = data.playlistSongDeletions.mapNotNull {
                sanitizePlaylistSongDeletion(it)
            },
            playbackStats = data.playbackStats.mapNotNull {
                SyncPlaybackStatMapping.sanitize(it, host)
            },
            playbackStatsClearedAt = data.playbackStatsClearedAt.coerceAtLeast(0L),
            playbackStatBuckets = data.playbackStatBuckets.mapNotNull {
                SyncPlaybackStatMapping.sanitize(it, host)
            },
            playlistUsageDeletions = SyncPlaylistUsageDeletionPolicy.merge(data.playlistUsageDeletions),
            playlistUsageStats = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
                data.playlistUsageStats, emptyList(), data.playlistUsageDeletions
            ),
            localPlaylistPlaybackStats = data.localPlaylistPlaybackStats.mapNotNull { stat ->
                SyncPlaylistUsageStatsMergePolicy.sanitize(stat)
            },
            localPlaylistPlaybackBuckets = data.localPlaylistPlaybackBuckets.mapNotNull { bucket ->
                SyncPlaylistUsageStatsMergePolicy.sanitize(bucket)
            },
            biliVideoSkipRules = SyncBiliVideoSkipMergePolicy.sanitize(data.biliVideoSkipRules),
            lyricOverrides = data.lyricOverrides.mapNotNull { sanitizeSyncSong(it) }
                .filter { it.lyricSyncRevision > 0L }
        )
    }

    private fun sanitizeSyncPlaylist(playlist: SyncPlaylist): SyncPlaylist? {
        val systemDescriptor = host.systemPlaylist(playlist.id, playlist.name)
        if (systemDescriptor?.id == host.localFilesPlaylistId) {
            return null
        }
        val canonical = canonicalizeSystemPlaylist(playlist, systemDescriptor)
        if (playlist.isDeleted) {
            return canonical.copy(songs = emptyList())
        }
        return canonical.copy(
            songs = SyncPlaylistSongMergePolicy.deduplicateSongs(
                playlist.songs.mapNotNull { sanitizeSyncSong(it) }
            )
        ).normalizedForDisplayOrder()
    }

    private fun canonicalizeSystemPlaylist(
        playlist: SyncPlaylist,
        descriptor: SyncSystemPlaylist?
    ): SyncPlaylist = if (descriptor == null) playlist else playlist.copy(
        id = descriptor.id,
        name = descriptor.currentName
    )

    private fun sanitizeSyncFavoritePlaylist(playlist: SyncFavoritePlaylist): SyncFavoritePlaylist {
        val sanitizedSongs = if (playlist.isDeleted) {
            emptyList()
        } else {
            SyncPlaylistSongMergePolicy.deduplicateSongs(
                playlist.songs.mapNotNull { sanitizeSyncSong(it) }
            )
        }
        return playlist.copy(
            coverUrl = sanitizeCoverUrlForSync(playlist.coverUrl),
            songs = sanitizedSongs,
            trackCount = if (playlist.isDeleted) 0 else maxOf(playlist.trackCount, sanitizedSongs.size)
        )
    }

    private fun sanitizeRecentPlay(play: SyncRecentPlay): SyncRecentPlay? {
        val sanitizedSong = sanitizeSyncSong(play.song) ?: return null
        return play.copy(songId = sanitizedSong.id, song = sanitizedSong)
    }

    private fun sanitizeRecentPlayDeletion(
        deletion: SyncRecentPlayDeletion
    ): SyncRecentPlayDeletion? {
        if (!deletion.hasResolvableSyncIdentity() || deletion.deletedAt <= 0L) {
            return null
        }
        if (host.isLocalSong(deletion.album, deletion.mediaUri, 0L)) {
            return null
        }
        return deletion.copy(mediaUri = host.sanitizeMediaUri(deletion.mediaUri))
    }

    private fun sanitizePlaylistSongDeletion(
        deletion: SyncPlaylistSongDeletion
    ): SyncPlaylistSongDeletion? {
        if (
            deletion.playlistId == 0L ||
            deletion.playlistId == host.localFilesPlaylistId ||
            !deletion.hasResolvableSyncIdentity() ||
            deletion.deletedAt <= 0L
        ) {
            return null
        }
        if (host.isLocalSong(deletion.album, deletion.mediaUri, 0L)) {
            return null
        }
        return deletion.copyWithNormalizedMembershipTokens(
            mediaUri = host.sanitizeMediaUri(deletion.mediaUri)
        )
    }

    private fun sanitizeSyncSong(song: SyncSong): SyncSong? {
        if (!song.hasResolvableSyncIdentity()) {
            return null
        }
        if (host.isLocalSong(song.album, song.mediaUri, song.albumId)) {
            return null
        }
        return song.sanitizeCoverUrlsForSync().copyWithNormalizedMembershipTokens(
            mediaUri = host.sanitizeMediaUri(song.mediaUri)
        )
    }
}
