package moe.ouom.neriplayer.data.sync.merge

import moe.ouom.neriplayer.data.sync.merge.policy.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.merge.policy.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.merge.policy.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.sync.model.SyncBiliVideoSkipMergePolicy
import moe.ouom.neriplayer.data.sync.model.SyncData
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncResult

internal data class SyncMergeResult(val mergedData: SyncData, val syncResult: SyncResult)

internal class SyncDataMerger(
    private val host: SyncMergeHost,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    fun merge(
        local: SyncData,
        remote: SyncData,
        lastSyncTime: Long
    ): SyncMergeResult {
        val mergedPlaylistSongDeletions = SyncPlaylistDeletionPolicy.mergeDeletions(
            local.playlistSongDeletions, remote.playlistSongDeletions
        )
        val collection = SyncPlaylistCollectionMerger(host).merge(
            local.playlists, remote.playlists, lastSyncTime, mergedPlaylistSongDeletions
        )

        val mergedFavoritePlaylists = (local.favoritePlaylists + remote.favoritePlaylists)
            .groupBy { "${it.id}_${it.source}" }
            .map { (_, snapshots) ->
                snapshots.reduce(SyncPlaylistDeletionPolicy::mergeFavoritePlaylists)
            }
            .sortedByDescending { it.sortOrder }

        val mergedRecentPlayDeletions = SyncRecentPlayMerger.pruneRecentPlayDeletions(
            SyncRecentPlayMerger.mergeRecentPlayDeletions(local.recentPlayDeletions, remote.recentPlayDeletions),
            local.recentPlays + remote.recentPlays
        )
        val mergedPlaylists = collection.playlists
        val prunedPlaylistSongDeletions = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(
            deletions = mergedPlaylistSongDeletions,
            playlists = mergedPlaylists
        )
        val mergedRecentPlays = SyncRecentPlayMerger.mergeRecentPlays(
            local = local.recentPlays,
            remote = remote.recentPlays,
            deletions = mergedRecentPlayDeletions
        )
        val playbackStatsClearedAt = maxOf(local.playbackStatsClearedAt, remote.playbackStatsClearedAt)
        // 收尾顺序与桌面 three_way_merge 逐字一致: 先用"未裁剪"的合并日桶抬升聚合值 (消除"年 > 总") , 再分别裁剪
        val finalizedPlaybackStats = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
            mergedStats = SyncPlaybackStatsMergePolicy.merge(
                local = local.playbackStats,
                remote = remote.playbackStats,
                playbackStatsClearedAt = playbackStatsClearedAt
            ),
            mergedBuckets = SyncPlaybackStatsMergePolicy.mergeBuckets(
                local = local.playbackStatBuckets,
                remote = remote.playbackStatBuckets,
                playbackStatsClearedAt = playbackStatsClearedAt
            )
        )
        val mergedPlaybackStats = finalizedPlaybackStats.stats
        val mergedPlaybackStatBuckets = finalizedPlaybackStats.buckets
        val mergedPlaylistUsageStats = SyncPlaylistUsageStatsMergePolicy
            .mergePlaylistUsageStats(
                local = local.playlistUsageStats,
                remote = remote.playlistUsageStats
            )
        val finalizedLocalPlaylistPlaybackStats =
            SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(
                stats = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackStats(
                    local = local.localPlaylistPlaybackStats,
                    remote = remote.localPlaylistPlaybackStats
                ),
                buckets = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackBuckets(
                    local = local.localPlaylistPlaybackBuckets,
                    remote = remote.localPlaylistPlaybackBuckets
                )
            )
        val mergedBiliVideoSkipRules = SyncBiliVideoSkipMergePolicy.merge(
            local = local.biliVideoSkipRules,
            remote = remote.biliVideoSkipRules
        )

        val mergedData = SyncData(
            deviceId = local.deviceId,
            deviceName = local.deviceName,
            lastModified = nowMs(),
            playlists = mergedPlaylists,
            favoritePlaylists = mergedFavoritePlaylists,
            recentPlays = mergedRecentPlays,
            syncLog = (local.syncLog + remote.syncLog)
                .distinctBy { it.timestamp }
                .sortedByDescending { it.timestamp }
                .take(100),
            recentPlayDeletions = mergedRecentPlayDeletions,
            playbackStats = mergedPlaybackStats,
            playbackStatsClearedAt = playbackStatsClearedAt,
            playbackStatBuckets = mergedPlaybackStatBuckets,
            playlistSongDeletions = prunedPlaylistSongDeletions,
            playlistUsageStats = mergedPlaylistUsageStats,
            localPlaylistPlaybackStats = finalizedLocalPlaylistPlaybackStats.stats,
            localPlaylistPlaybackBuckets = finalizedLocalPlaylistPlaybackStats.buckets,
            biliVideoSkipRules = mergedBiliVideoSkipRules
        )

        return SyncMergeResult(
            mergedData = mergedData,
            syncResult = SyncResult(
                success = true,
                message = host.mergeSuccessMessage,
                playlistsAdded = collection.playlistsAdded,
                playlistsUpdated = collection.playlistsUpdated,
                playlistsDeleted = collection.playlistsDeleted,
                songsAdded = collection.songsAdded,
                songsRemoved = collection.songsRemoved,
                conflicts = collection.conflicts
            )
        )
    }

    fun initial(
        localData: SyncData
    ): SyncMergeResult {
        val playlistsAdded = localData.playlists.count { !it.isDeleted }
        val playlistsDeleted = localData.playlists.count(SyncPlaylist::isDeleted)
        val songsAdded = localData.playlists.sumOf { playlist -> playlist.songs.size }
        // 首次同步同样走收尾 (顺序与桌面一致: 先用"未裁剪"桶抬升, 再裁剪) , 避免初始快照突破同步正文安全上限
        val finalizedInitialStats = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
            mergedStats = localData.playbackStats,
            mergedBuckets = localData.playbackStatBuckets
        )
        val finalizedInitialLocalPlaylistStats =
            SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(
                stats = localData.localPlaylistPlaybackStats,
                buckets = localData.localPlaylistPlaybackBuckets
            )
        return SyncMergeResult(
            mergedData = localData.copy(
                lastModified = nowMs(),
                playbackStats = finalizedInitialStats.stats,
                playbackStatBuckets = finalizedInitialStats.buckets,
                localPlaylistPlaybackStats = finalizedInitialLocalPlaylistStats.stats,
                localPlaylistPlaybackBuckets = finalizedInitialLocalPlaylistStats.buckets
            ),
            syncResult = SyncResult(
                success = true,
                message = host.initialUploadMessage,
                playlistsAdded = playlistsAdded,
                playlistsDeleted = playlistsDeleted,
                songsAdded = songsAdded
            )
        )
    }
}
