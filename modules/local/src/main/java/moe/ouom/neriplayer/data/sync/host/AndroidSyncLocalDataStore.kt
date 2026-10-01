package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.local.platform.bilibili.BiliVideoSkipRepositoryProvider
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
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
    private val storage: SecureTokenStorage,
    private val playlistRepo: LocalPlaylistRepository = LocalPlaylistRepository.getInstance(appContext),
    private val favoriteRepo: FavoritePlaylistRepository = FavoritePlaylistRepository.getInstance(appContext),
    private val playHistoryRepo: PlayHistoryRepository = PlayHistoryRepository.getInstance(appContext),
    private val playbackStatsRepo: PlaybackStatsRepository = PlaybackStatsRepository.getInstance(appContext),
    private val playlistUsageRepo: PlaylistUsageRepository = PlaylistUsageRepository.getInstance(appContext),
    private val localPlaylistPlaybackStatsRepo: LocalPlaylistPlaybackStatsRepository = LocalPlaylistPlaybackStatsRepository.getInstance(appContext),
    private val biliVideoSkipRepo: BiliVideoSkipRepository = BiliVideoSkipRepositoryProvider.getInstance(appContext),
    private val readLocalizedContext: () -> Context = { LanguageManager.applyLanguage(appContext) }
) : SyncLocalDataStore {
    private val snapshotBuilder = AndroidSyncSnapshotBuilder(
        storage, playlistRepo, favoriteRepo, playHistoryRepo, playbackStatsRepo,
        playlistUsageRepo, localPlaylistPlaybackStatsRepo, biliVideoSkipRepo
    )
    private val sanitizer = SyncDataSanitizer(AndroidSyncSanitizationHost(appContext))
    private val applier = SyncLocalDataApplier(
        AndroidSyncLocalApplyHost(
            appContext, storage, playlistRepo, favoriteRepo, playHistoryRepo, playbackStatsRepo,
            playlistUsageRepo, localPlaylistPlaybackStatsRepo, biliVideoSkipRepo, readLocalizedContext
        )
    )

    override suspend fun awaitInitialized(): Boolean {
        if (!playlistRepo.awaitInitialized() || !favoriteRepo.awaitInitialized() ||
            !playbackStatsRepo.awaitInitialized()
        ) return false
        playbackStatsRepo.flushPendingWrites()
        return !playbackStatsRepo.hasPendingWrites()
    }
    override fun mutationVersion(): Long = storage.getSyncMutationVersion()
    override fun snapshot(): SyncData = sanitizer.sanitize(snapshotBuilder.build(readLocalizedContext()))

    override suspend fun apply(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
        if (!awaitInitialized()) return false
        return applier.apply(sanitizer.sanitize(data), remoteChanged, expectedMutationVersion)
    }
}
