package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import android.os.Build
import moe.ouom.neriplayer.data.platform.bili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.mapping.fromFavoritePlaylist
import moe.ouom.neriplayer.data.sync.mapping.buildPlaylistSyncSnapshots
import moe.ouom.neriplayer.data.sync.mapping.buildRecentPlaySyncSnapshots
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
        val syncPlaylists = buildPlaylistSyncSnapshots(playlistRepo.playlists.value, storage.getDeletedPlaylistTimestamps(), localizedContext)
        val syncFavoritePlaylists = favoritePlaylists(localizedContext)

        val syncRecentPlays = buildRecentPlaySyncSnapshots(playHistoryRepo.historyFlow.value, ::getDeviceId, localizedContext)
        val syncRecentPlayDeletions = recentPlayDeletions()
        val syncPlaylistSongDeletions = playlistSongDeletions()
        val playbackCounterSnapshot = playbackStatsRepo.syncCounterSnapshot()
        val syncPlaybackStats = playbackStats(localizedContext, playbackCounterSnapshot)
        val syncPlaybackStatBuckets = playbackBuckets(localizedContext, playbackCounterSnapshot)
        val syncPlaylistUsageStats = playlistUsageRepo.syncStats()
        val localPlaylistPlaybackSnapshot = localPlaylistPlaybackStatsRepo.syncSnapshot()
        val syncBiliVideoSkipRules = videoSkipRules()

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

    private fun recentPlayDeletions(): List<moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion> {
        val syncRecentPlayDeletions = storage.getRecentPlayDeletions()
            .map {
                it.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri))
            }
        return syncRecentPlayDeletions
    }

    private fun playlistSongDeletions(): List<moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion> {
        val syncPlaylistSongDeletions = storage.getPlaylistSongDeletions()
            .map {
                it.copyWithNormalizedMembershipTokens(
                    mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri)
                )
            }

        return syncPlaylistSongDeletions
    }

    private fun favoritePlaylists(localizedContext: Context): List<SyncFavoritePlaylist> {
        val syncFavoritePlaylists = favoriteRepo.getSyncSnapshots().map {
            SyncFavoritePlaylist.fromFavoritePlaylist(it, localizedContext)
        }

        return syncFavoritePlaylists
    }

    private fun videoSkipRules(): List<moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipRule> {
        val syncBiliVideoSkipRules = SyncBiliVideoSkipMergePolicy.sanitize(
            biliVideoSkipRepo.snapshot().map { rule -> rule.toSyncBiliVideoSkipRule() }
        )

        return syncBiliVideoSkipRules
    }

    private fun playbackStats(localizedContext: Context, playbackCounterSnapshot: PlaybackStatsSyncCounterSnapshot): List<SyncTrackStat> {
        val syncPlaybackStats = playbackStatsRepo.statsFlow.value
            .filter { SyncPlaybackStatMapper.shouldSync(it, localizedContext) }
            .map { stat ->
                SyncPlaybackStatMapper.fromTrackStat(
                    stat = stat,
                    counterShards = playbackCounterSnapshot.trackShards(stat.identityKey)
                )
            }
        return syncPlaybackStats
    }

    private fun playbackBuckets(localizedContext: Context, playbackCounterSnapshot: PlaybackStatsSyncCounterSnapshot): List<SyncPlaybackStatBucket> {
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
        return syncPlaybackStatBuckets
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
