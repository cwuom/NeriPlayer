package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.platform.bili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.mapping.toBiliVideoSkipRuleOrNull
import moe.ouom.neriplayer.data.sync.mapping.toFavoritePlaylist
import moe.ouom.neriplayer.data.sync.mapping.SyncLocalRestoreMapping
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalApplyHost
import moe.ouom.neriplayer.util.platform.LanguageManager

internal class AndroidSyncLocalApplyHost(
    appContext: Context,
    private val storage: SecureTokenStorage,
    private val playlistRepo: LocalPlaylistRepository,
    private val favoriteRepo: FavoritePlaylistRepository,
    private val playHistoryRepo: PlayHistoryRepository,
    private val playbackStatsRepo: PlaybackStatsRepository,
    private val playlistUsageRepo: PlaylistUsageRepository,
    private val localPlaylistPlaybackStatsRepo: LocalPlaylistPlaybackStatsRepository,
    private val biliVideoSkipRepo: BiliVideoSkipRepository,
    private val readLocalizedContext: () -> Context = { LanguageManager.applyLanguage(appContext) }
) : SyncLocalApplyHost {
    override suspend fun applyPlaylists(data: SyncData, expectedMutationVersion: Long): Boolean {
        val localizedContext = readLocalizedContext()
        val mergedLocalPlaylists = SyncLocalRestoreMapping(localizedContext).playlists(data, playlistRepo.playlists.value)
        val playlistsApplied = playlistRepo.applySyncedPlaylistsIfUnchanged(
            playlists = mergedLocalPlaylists,
            expectedMutationVersion = expectedMutationVersion
        )
        if (!playlistsApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local playlist epoch changed")
            return false
        }
        return true
    }

    override fun applyDeletions(data: SyncData, expectedMutationVersion: Long): Boolean {
        val deletionStateApplied = storage.setDeletionStateIfMutationVersion(
            expectedMutationVersion = expectedMutationVersion,
            recentPlayDeletions = data.recentPlayDeletions,
            playlistSongDeletions = data.playlistSongDeletions
        )
        if (!deletionStateApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local deletion epoch changed")
            return false
        }
        return true
    }

    override suspend fun applyFavorites(data: SyncData, expectedMutationVersion: Long): Boolean {
        val favoritesApplied = favoriteRepo.replaceFavoritesFromSyncIfUnchanged(
            favorites = data.favoritePlaylists.map { it.toFavoritePlaylist() },
            expectedMutationVersion = expectedMutationVersion
        )
        if (!favoritesApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local favorites epoch changed")
            return false
        }
        return true
    }

    override suspend fun applyHistory(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
        val localizedContext = readLocalizedContext()
        val localPlayHistoryEmpty = playHistoryRepo.historyFlow.value.isEmpty()
        val shouldApplyRemoteHistory = remoteChanged ||
            (localPlayHistoryEmpty && data.recentPlays.isNotEmpty())

        if (shouldApplyRemoteHistory) {
            val playHistory = SyncLocalRestoreMapping(localizedContext).history(data, playHistoryRepo.historyFlow.value)
            val historyApplied = playHistoryRepo.updateHistoryIfUnchanged(
                entries = playHistory,
                expectedMutationVersion = expectedMutationVersion
            )
            if (!historyApplied) {
                NPLogger.w(TAG, "Skip applying merged sync data because local history epoch changed")
                return false
            }
        }
        return true
    }

    override suspend fun applyStatistics(data: SyncData) {
        playbackStatsRepo.applyMergedStats(
            syncStats = data.playbackStats,
            playbackStatsClearedAt = data.playbackStatsClearedAt,
            syncDailyStats = data.playbackStatBuckets
        )
        playlistUsageRepo.applyMergedStats(data.playlistUsageStats)
        localPlaylistPlaybackStatsRepo.applyMergedStats(
            stats = data.localPlaylistPlaybackStats,
            buckets = data.localPlaylistPlaybackBuckets
        )
    }

    override suspend fun applyVideoSkipRules(data: SyncData, expectedMutationVersion: Long): Boolean {
        val videoSkipRulesApplied = biliVideoSkipRepo.replaceFromSyncIfUnchanged(
            rules = data.biliVideoSkipRules.mapNotNull { rule ->
                rule.toBiliVideoSkipRuleOrNull()
            },
            expectedMutationVersion = expectedMutationVersion
        )
        if (!videoSkipRulesApplied) {
            NPLogger.w(TAG, "Skip applying Bili video skip rules because local state changed")
            return false
        }
        return true
    }

    private companion object {
        const val TAG = "SyncLocalDataStore"
    }
}
