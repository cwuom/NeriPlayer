package moe.ouom.neriplayer.data.sync.host

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import android.content.Context
import kotlinx.coroutines.CancellationException
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
import moe.ouom.neriplayer.common.logging.NPLogger

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
        val started = System.nanoTime()
        var outcome = "failed"
        try {
            return awaitLocalRepositories().also { ready ->
                outcome = if (ready) "ready" else "not_ready"
            }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            NPLogger.i("SyncLocalDataStore", "Sync initialize: ${(System.nanoTime() - started) / 1_000_000L} ms, outcome=$outcome")
        }
    }

    private suspend fun awaitLocalRepositories(): Boolean {
        if (!awaitReadiness("playlists", playlistRepo::awaitInitialized) ||
            !awaitReadiness("favorites", favoriteRepo::awaitInitialized) ||
            !awaitReadiness("playback statistics", playbackStatsRepo::awaitInitialized) ||
            !awaitReadiness("play history", playHistoryRepo::awaitInitialized) ||
            !awaitReadiness("playlist usage", playlistUsageRepo::awaitInitialized) ||
            !awaitReadiness("local playlist statistics", localPlaylistPlaybackStatsRepo::awaitInitialized)
        ) return false
        awaitReadiness("playback journal") {
            PlaybackStatsCaptureBarrier.await(appContext)
            true
        }
        return awaitReadiness("playback pending writes") {
            playbackStatsRepo.flushPendingWrites()
            !playbackStatsRepo.hasPendingWrites()
        }
    }

    private suspend fun awaitReadiness(stage: String, action: suspend () -> Boolean): Boolean = try {
        action().also { ready ->
            if (!ready) NPLogger.w("SyncLocalDataStore", "Sync readiness: stage=$stage, outcome=not_ready")
        }
    } catch (error: Exception) {
        val outcome = if (error is CancellationException) "cancelled" else "failed:${error.javaClass.simpleName}"
        NPLogger.w("SyncLocalDataStore", "Sync readiness: stage=$stage, outcome=$outcome")
        throw error
    }
    override fun mutationVersion(): Long = storage.getSyncMutationVersion()
    override suspend fun snapshot(): SyncDataset {
        val captureStarted = System.nanoTime()
        val captured = playbackStatsRepo.borrowSyncCapture(datasetStore)
        try {
            logDuration("playback capture", captureStarted)
            val coreStarted = System.nanoTime()
            val core = playlistRepo.withCommittedSyncSnapshot {
                snapshotBuilder.build(readLocalizedContext(), captured.state.clearedAt)
            }
            val data = SyncSongLyricMergePolicy.converge(sanitizer.sanitize(core))
            logDuration("core snapshot", coreStarted)
            return SyncDataset(data, captured.playback, captured.state.revision)
        } catch (failure: Throwable) {
            try { captured.playback.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
        val started = System.nanoTime()
        try {
            return applyLocalDataset(dataset, remoteChanged, expectedMutationVersion)
        } finally {
            logDuration("apply", started)
        }
    }

    private suspend fun applyLocalDataset(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
        if (!awaitInitialized()) return false
        val revision = checkNotNull(dataset.capturedPlaybackRevision) { "Playback snapshot revision is missing" }
        return applier.apply(sanitizer.sanitize(dataset.data), remoteChanged, expectedMutationVersion) {
            if (dataset.playbackMatchesCaptured) playbackStatsRepo.checkCapturedRevision(revision)
            else playbackStatsRepo.applySyncSnapshot(dataset.playback, dataset.data.playbackStatsClearedAt, revision)
        }
    }

    private fun logDuration(stage: String, started: Long) {
        NPLogger.i("SyncLocalDataStore", "Sync $stage: ${(System.nanoTime() - started) / 1_000_000L} ms")
    }
}
