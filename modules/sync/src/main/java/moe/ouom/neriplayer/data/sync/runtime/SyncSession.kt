package moe.ouom.neriplayer.data.sync.runtime

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetMergeResult
import moe.ouom.neriplayer.data.sync.merge.dataset.SyncDatasetMerger
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncUploadResolution
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.retry.LocalSyncMutationConflictException
import moe.ouom.neriplayer.data.sync.retry.SyncUploadRetryExecutor
import java.io.IOException

class SyncSession(
    private val local: SyncLocalDataStore,
    merger: SyncDataMerger,
    datasetStore: SyncPlaybackDatasetStore,
    private val noChangeMessage: String,
    private val initialUploadMessage: String,
    private val inProgressError: () -> Exception,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val deferredMessage: String
) {
    private val merger = SyncDatasetMerger(merger, datasetStore)
    suspend fun <TVersion> execute(backendFactory: () -> SyncBackend<TVersion>): Result<SyncResult> {
        if (!local.awaitInitialized()) {
            return Result.failure(IllegalStateException("Local playlist initialization failed"))
        }
        if (!SyncCoordinator.tryLock()) return Result.failure(inProgressError())
        try {
            return sync(backendFactory())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.failure(error)
        } finally {
            SyncCoordinator.unlock()
        }
    }

    private suspend fun <TVersion> sync(backend: SyncBackend<TVersion>): Result<SyncResult> {
        val mutationVersion = local.mutationVersion()
        local.snapshot().use { localData ->
            val fetched = backend.fetch()
            if (fetched.isFailure) return failure(backend, fetched.exceptionOrNull())
            val remote = fetched.getOrThrow()
            val initialRemoteMissing = remote.dataset == null
            val firstSync = backend.isFirstSync
            val resolved = resolveUpload(backend, localData, remote, mutationVersion)
            if (resolved.isFailure) return uploadFailure(backend, resolved.exceptionOrNull())
            val resolution = resolved.getOrThrow()
            resolution.merged.dataset.use {
                if (!SyncSessionCommitter(local, nowMs).commit(backend, resolution, firstSync, mutationVersion)) {
                    return Result.success(SyncResult(success = false, message = deferredMessage))
                }
                return Result.success(SyncSessionResultPolicy.result(
                    resolution, firstSync, initialRemoteMissing, noChangeMessage, initialUploadMessage
                ))
            }
        }
    }

    private suspend fun <TVersion> resolveUpload(
        backend: SyncBackend<TVersion>,
        localData: SyncDataset,
        remote: SyncDatasetRemoteSnapshot<TVersion>,
        mutationVersion: Long
    ): Result<SyncUploadResolution<SyncDatasetMergeResult, TVersion>> {
        val lastSyncTime = backend.lastSyncTime
        return SyncUploadRetryExecutor.execute(
            initialRemote = remote,
            initialVersion = remote.version,
            initialRemoteChangedDuringSync = backend.remoteChanged(remote.version),
            merge = { snapshot -> merger.merge(localData, snapshot.dataset, lastSyncTime) },
            hasMeaningfulChange = { snapshot, merged -> merger.changed(snapshot.dataset, merged.dataset, snapshot.requiresMigrationUpload) },
            upload = { merged, version ->
                if (local.mutationVersion() != mutationVersion) {
                    Result.failure(LocalSyncMutationConflictException(backend.mutationConflictMessage))
                } else {
                    backend.upload(merged.dataset, version)
                }
            },
            refetch = { version -> backend.refetch(version).map { it to it.version } },
            isConflict = backend::isConflict,
            disposeMerged = { it.dataset.close() },
            disposeRemote = { it.dataset?.close() }
        )
    }

    private fun <TVersion> uploadFailure(backend: SyncBackend<TVersion>, error: Throwable?): Result<SyncResult> {
        if (error is LocalSyncMutationConflictException) {
            backend.scheduleFollowUp()
            return Result.success(SyncResult(success = false, message = deferredMessage))
        }
        return failure(backend, error)
    }

    private fun <TVersion> failure(backend: SyncBackend<TVersion>, error: Throwable?): Result<SyncResult> {
        if (error is CancellationException) throw error
        val failure = error ?: IOException("Sync failed")
        backend.onFailure(failure)
        return Result.failure(failure)
    }
}
