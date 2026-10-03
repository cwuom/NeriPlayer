package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.mapping.toBiliVideoSkipRuleOrNull
import moe.ouom.neriplayer.data.sync.mapping.toFavoritePlaylist
import moe.ouom.neriplayer.data.sync.mapping.SyncLocalRestoreMapping
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalApplyHost
import moe.ouom.neriplayer.common.locale.LanguageManager

internal class AndroidSyncLocalApplyHost(
    appContext: Context,
    private val storage: SecureTokenStorage,
    private val playlistRepo: LocalPlaylistRepository,
    private val favoriteRepo: FavoritePlaylistRepository,
    private val playHistoryRepo: PlayHistoryRepository,
    private val playlistUsageRepo: PlaylistUsageRepository,
    private val localPlaylistPlaybackStatsRepo: LocalPlaylistPlaybackStatsRepository,
    private val biliVideoSkipRepo: BiliVideoSkipRepository,
    private val readLocalizedContext: () -> Context = { LanguageManager.applyLanguage(appContext) }
) : SyncLocalApplyHost {
    override suspend fun applyPlaylists(data: SyncData, expectedMutationVersion: Long): Boolean {
        val localizedContext = readLocalizedContext()
        val mergedLocalPlaylists = SyncLocalRestoreMapping(localizedContext, storage::retainLegacyLyricCandidates)
            .playlists(data, playlistRepo.playlists.value)
        val playlistsApplied = playlistRepo.applySyncedPlaylistsIfUnchanged(
            playlists = mergedLocalPlaylists,
            expectedMutationVersion = expectedMutationVersion
        )
        if (!playlistsApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local playlist epoch changed")
            return false
        }
        // 活跃恢复落进容器后才能清除旧墓碑，失败时仍保留阻止其它后端回流的依据
        return storage.setPlaylistDeletionStateIfMutationVersion(expectedMutationVersion, data.playlists)
    }

    override fun applyDeletions(data: SyncData, expectedMutationVersion: Long): Boolean {
        if (!storage.mergePlaylistUsageDeletionBarriersIfMutationVersion(expectedMutationVersion, data.playlistUsageDeletions)) return false
        if (!storage.setLyricOverridesIfMutationVersion(expectedMutationVersion, data.lyricOverrides)) return false
        if (!storage.setPlaylistDeletionStateIfMutationVersion(expectedMutationVersion, data.playlists, clearRestored = false)) return false
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
        val existingFavorites = favoriteRepo.favorites.value.associateBy { it.id to it.source }
        val legacyCandidates = mutableListOf<moe.ouom.neriplayer.data.model.sync.SyncSong>()
        val favorites = data.favoritePlaylists.map { it.toFavoritePlaylist(existingFavorites[it.id to it.source], legacyCandidates::add) }
        storage.retainLegacyLyricCandidates(legacyCandidates)
        val favoritesApplied = favoriteRepo.replaceFavoritesFromSyncIfUnchanged(
            favorites = favorites,
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
            val playHistory = SyncLocalRestoreMapping(localizedContext, storage::retainLegacyLyricCandidates)
                .history(data, playHistoryRepo.historyFlow.value)
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
        playlistUsageRepo.applyMergedStatsAndPersist(data.playlistUsageStats, data.playlistUsageDeletions)
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
