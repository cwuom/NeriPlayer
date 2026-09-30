package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import android.os.Build
import moe.ouom.neriplayer.data.platform.bili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.mapping.fromFavoritePlaylist
import moe.ouom.neriplayer.data.sync.mapping.fromLocalPlaylist
import moe.ouom.neriplayer.data.sync.mapping.toSyncBiliVideoSkipRule
import moe.ouom.neriplayer.data.sync.policy.SyncBiliVideoSkipMergePolicy
import moe.ouom.neriplayer.data.sync.policy.copyWithNormalizedMembershipTokens

internal class AndroidSyncSnapshotBuilder(
    private val storage: SecureTokenStorage,
    private val playlistRepo: LocalPlaylistRepository,
    private val favoriteRepo: FavoritePlaylistRepository,
    private val playHistoryRepo: PlayHistoryRepository,
    private val playbackStatsRepo: PlaybackStatsRepository,
    private val playlistUsageRepo: PlaylistUsageRepository,
    private val localPlaylistPlaybackStatsRepo: LocalPlaylistPlaybackStatsRepository,
    private val biliVideoSkipRepo: BiliVideoSkipRepository
) {
    fun build(localizedContext: Context): SyncData {
        val playlists = playlistRepo.playlists.value
        val syncPlaylists = playlists.map { playlist ->
            SyncPlaylist.fromLocalPlaylist(playlist, playlist.modifiedAt, localizedContext)
        }.toMutableList()

        storage.getDeletedPlaylistTimestamps().forEach { (deletedId, deletedAt) ->
            if (playlists.none { it.id == deletedId }) {
                syncPlaylists += SyncPlaylist(
                    id = deletedId,
                    name = "",
                    songs = emptyList(),
                    createdAt = 0L,
                    modifiedAt = deletedAt,
                    isDeleted = true,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            }
        }

        val syncFavoritePlaylists = favoriteRepo.getSyncSnapshots().map {
            SyncFavoritePlaylist.fromFavoritePlaylist(it, localizedContext)
        }

        val syncRecentPlays = playHistoryRepo.historyFlow.value
            .filterNot {
                !it.localFilePath.isNullOrBlank() ||
                    LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
            }
            .take(500)
            .map { playedEntry ->
                SyncRecentPlay(
                    songId = playedEntry.id,
                    song = SyncSong(
                        id = playedEntry.id,
                        name = playedEntry.name,
                        artist = playedEntry.artist,
                        album = playedEntry.album,
                        albumId = playedEntry.albumId,
                        durationMs = playedEntry.durationMs,
                        coverUrl = playedEntry.coverUrl,
                        mediaUri = LocalSongSupport.sanitizeMediaUriForSync(playedEntry.mediaUri),
                        matchedLyric = playedEntry.matchedLyric,
                        matchedTranslatedLyric = playedEntry.matchedTranslatedLyric,
                        customCoverUrl = playedEntry.customCoverUrl,
                        customName = playedEntry.customName,
                        customArtist = playedEntry.customArtist,
                        originalName = playedEntry.originalName,
                        originalArtist = playedEntry.originalArtist,
                        originalCoverUrl = playedEntry.originalCoverUrl,
                        originalLyric = playedEntry.originalLyric,
                        originalTranslatedLyric = playedEntry.originalTranslatedLyric,
                        syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
                    ),
                    playedAt = playedEntry.playedAt,
                    deviceId = getDeviceId(),
                    resumePositionMs = playedEntry.resumePositionMs
                )
            }
        val syncRecentPlayDeletions = storage.getRecentPlayDeletions()
            .map {
                it.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri))
            }
        val syncPlaylistSongDeletions = storage.getPlaylistSongDeletions()
            .map {
                it.copyWithNormalizedMembershipTokens(
                    mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri)
                )
            }

        val playbackCounterSnapshot = playbackStatsRepo.syncCounterSnapshot()
        val syncPlaybackStats = playbackStatsRepo.statsFlow.value
            .filter { SyncPlaybackStatMapper.shouldSync(it, localizedContext) }
            .map { stat ->
                SyncPlaybackStatMapper.fromTrackStat(
                    stat = stat,
                    counterShards = playbackCounterSnapshot.trackShards(stat.identityKey)
                )
            }
        val syncPlaybackStatBuckets = playbackStatsRepo.dailyStatsFlow.value
            .filter { SyncPlaybackStatMapper.shouldSync(it, localizedContext) }
            .map { bucket ->
                SyncPlaybackStatMapper.fromPlaybackStatBucket(
                    bucket = bucket,
                    counterShards = playbackCounterSnapshot.dailyShards(
                        dayStartAt = bucket.dayStartAt,
                        identityKey = bucket.identityKey
                    )
                )
            }
        val syncPlaylistUsageStats = playlistUsageRepo.syncStats()
        val localPlaylistPlaybackSnapshot = localPlaylistPlaybackStatsRepo.syncSnapshot()
        val syncBiliVideoSkipRules = SyncBiliVideoSkipMergePolicy.sanitize(
            biliVideoSkipRepo.snapshot().map { rule -> rule.toSyncBiliVideoSkipRule() }
        )

        return SyncData(
            deviceId = getDeviceId(),
            deviceName = getDeviceName(),
            lastModified = System.currentTimeMillis(),
            playlists = syncPlaylists,
            favoritePlaylists = syncFavoritePlaylists,
            recentPlays = syncRecentPlays,
            syncLog = emptyList(),
            recentPlayDeletions = syncRecentPlayDeletions,
            playbackStats = syncPlaybackStats,
            playbackStatsClearedAt = playbackStatsRepo.statsClearedAtFlow.value,
            playbackStatBuckets = syncPlaybackStatBuckets,
            playlistSongDeletions = syncPlaylistSongDeletions,
            playlistUsageStats = syncPlaylistUsageStats,
            localPlaylistPlaybackStats = localPlaylistPlaybackSnapshot.stats,
            localPlaylistPlaybackBuckets = localPlaylistPlaybackSnapshot.buckets,
            biliVideoSkipRules = syncBiliVideoSkipRules
        )
    }

    private fun getDeviceId(): String {
        return storage.getOrCreateDeviceId()
    }

    private fun getDeviceName(): String {
        return try {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (_: Exception) {
            "Unknown Device"
        }
    }
}
