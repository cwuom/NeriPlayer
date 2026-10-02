package moe.ouom.neriplayer.data.sync.merge.engine

import moe.ouom.neriplayer.data.model.sync.SyncMergeResult
import moe.ouom.neriplayer.data.sync.merge.history.SyncRecentPlayMerger
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistCollectionMerger
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.sync.policy.SyncBiliVideoSkipMergePolicy
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletionPolicy
import moe.ouom.neriplayer.data.model.sync.SyncResult

class SyncDataMerger(
    private val host: SyncMergeHost,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    fun merge(
        local: SyncData,
        remote: SyncData,
        lastSyncTime: Long
    ): SyncMergeResult {
        // 先保留输入中的歌词版本，容器删除和历史窗口不能丢掉编辑或恢复记录
        val mergedLyricOverrides = SyncSongLyricMergePolicy.mergeOverrides(
            SyncSongLyricMergePolicy.collectOverrides(local) + SyncSongLyricMergePolicy.collectOverrides(remote)
        )
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
        // 用完整合并日桶抬升聚合值，避免长周期统计大于总计
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
        val mergedUsageDeletions = SyncPlaylistUsageDeletionPolicy.merge(local.playlistUsageDeletions + remote.playlistUsageDeletions)
        val mergedPlaylistUsageStats = SyncPlaylistUsageStatsMergePolicy
            .mergePlaylistUsageStats(
                local = local.playlistUsageStats,
                remote = remote.playlistUsageStats,
                deletions = mergedUsageDeletions
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
            playlistUsageDeletions = mergedUsageDeletions,
            localPlaylistPlaybackStats = finalizedLocalPlaylistPlaybackStats.stats,
            localPlaylistPlaybackBuckets = finalizedLocalPlaylistPlaybackStats.buckets,
            biliVideoSkipRules = mergedBiliVideoSkipRules,
            lyricOverrides = mergedLyricOverrides
        )

        return SyncMergeResult(
            mergedData = SyncSongLyricMergePolicy.converge(mergedData),
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
        // 首次同步也用完整日桶校正总计，统计容量由分块协议承载
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
            mergedData = SyncSongLyricMergePolicy.converge(localData.copy(
                lastModified = nowMs(),
                playlistUsageDeletions = SyncPlaylistUsageDeletionPolicy.merge(localData.playlistUsageDeletions),
                playlistUsageStats = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
                    localData.playlistUsageStats, emptyList(), localData.playlistUsageDeletions
                ),
                playbackStats = finalizedInitialStats.stats,
                playbackStatBuckets = finalizedInitialStats.buckets,
                localPlaylistPlaybackStats = finalizedInitialLocalPlaylistStats.stats,
                localPlaylistPlaybackBuckets = finalizedInitialLocalPlaylistStats.buckets
            )),
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
