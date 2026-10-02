package moe.ouom.neriplayer.data.sync.host

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import android.content.Context
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import java.io.File
import moe.ouom.neriplayer.data.local.platform.bilibili.BiliVideoSkipRepositoryProvider
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsCaptureBarrier
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataApplier
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.sanitize.SyncDataSanitizer
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
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
    private val datasetStore by lazy { FileSyncPlaybackDatasetStore(File(appContext.cacheDir, "sync-v3/playback-staging")) }
    private val snapshotBuilder = AndroidSyncSnapshotBuilder(
        storage, playlistRepo, favoriteRepo, playHistoryRepo,
        playlistUsageRepo, localPlaylistPlaybackStatsRepo, biliVideoSkipRepo
    )
    private val sanitizer = SyncDataSanitizer(AndroidSyncSanitizationHost(appContext))
    private val applier = SyncLocalDataApplier(
        AndroidSyncLocalApplyHost(
            appContext, storage, playlistRepo, favoriteRepo, playHistoryRepo,
            playlistUsageRepo, localPlaylistPlaybackStatsRepo, biliVideoSkipRepo, readLocalizedContext
        )
    )

    override suspend fun awaitInitialized(): Boolean {
        if (!playlistRepo.awaitInitialized() || !favoriteRepo.awaitInitialized() ||
            !playbackStatsRepo.awaitInitialized() || !playHistoryRepo.awaitInitialized() ||
            !playlistUsageRepo.awaitInitialized() || !localPlaylistPlaybackStatsRepo.awaitInitialized()
        ) return false
        PlaybackStatsCaptureBarrier.await(appContext)
        playbackStatsRepo.flushPendingWrites()
        return !playbackStatsRepo.hasPendingWrites()
    }
    override fun mutationVersion(): Long = storage.getSyncMutationVersion()
    override suspend fun snapshot(): SyncDataset {
        val captured = playbackStatsRepo.borrowSyncCapture(datasetStore)
        try {
            val core = playlistRepo.withCommittedSyncSnapshot {
                snapshotBuilder.build(readLocalizedContext(), captured.state.clearedAt)
            }
            val data = SyncSongLyricMergePolicy.converge(sanitizer.sanitize(core))
            return SyncDataset(data, captured.playback, captured.state.revision)
        } catch (failure: Throwable) {
            try { captured.playback.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
        if (!awaitInitialized()) return false
        val revision = checkNotNull(dataset.capturedPlaybackRevision) { "Playback snapshot revision is missing" }
        return applier.apply(sanitizer.sanitize(dataset.data), remoteChanged, expectedMutationVersion) {
            if (dataset.playbackMatchesCaptured) playbackStatsRepo.checkCapturedRevision(revision)
            else playbackStatsRepo.applySyncSnapshot(dataset.playback, dataset.data.playbackStatsClearedAt, revision)
        }
    }
}
