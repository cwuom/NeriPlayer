package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.local.platform.bilibili.BiliVideoSkipRepositoryProvider
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataApplier
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.sanitize.SyncDataSanitizer
import moe.ouom.neriplayer.common.locale.LanguageManager

internal class AndroidSyncLocalDataStore(
    private val appContext: Context,
    private val storage: SecureTokenStorage
) : SyncLocalDataStore {
    private val playlistRepo = LocalPlaylistRepository.getInstance(appContext)
    private val favoriteRepo = FavoritePlaylistRepository.getInstance(appContext)
    private val playHistoryRepo = PlayHistoryRepository.getInstance(appContext)
    private val playbackStatsRepo = PlaybackStatsRepository.getInstance(appContext)
    private val playlistUsageRepo = PlaylistUsageRepository.getInstance(appContext)
    private val localPlaylistPlaybackStatsRepo = LocalPlaylistPlaybackStatsRepository.getInstance(appContext)
    private val biliVideoSkipRepo = BiliVideoSkipRepositoryProvider.getInstance(appContext)
    private val snapshotBuilder = AndroidSyncSnapshotBuilder(
        storage, playlistRepo, favoriteRepo, playHistoryRepo, playbackStatsRepo,
        playlistUsageRepo, localPlaylistPlaybackStatsRepo, biliVideoSkipRepo
    )
    private val sanitizer = SyncDataSanitizer(AndroidSyncSanitizationHost(appContext))
    private val applier = SyncLocalDataApplier(
        AndroidSyncLocalApplyHost(
            appContext, storage, playlistRepo, favoriteRepo, playHistoryRepo, playbackStatsRepo,
            playlistUsageRepo, localPlaylistPlaybackStatsRepo, biliVideoSkipRepo
        )
    )

    override suspend fun awaitInitialized(): Boolean = playlistRepo.awaitInitialized()
    override fun mutationVersion(): Long = storage.getSyncMutationVersion()
    override fun snapshot(): SyncData = sanitizer.sanitize(snapshotBuilder.build(LanguageManager.applyLanguage(appContext)))

    override suspend fun apply(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean =
        applier.apply(sanitizer.sanitize(data), remoteChanged, expectedMutationVersion)
}
