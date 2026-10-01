package moe.ouom.neriplayer.data.sync.runtime

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncMergeResult
import moe.ouom.neriplayer.data.model.sync.SyncRemoteSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncUploadResolution
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.retry.LocalSyncMutationConflictException
import moe.ouom.neriplayer.data.sync.retry.SyncUploadRetryExecutor
import java.io.IOException

class SyncSession(
    private val local: SyncLocalDataStore,
    private val merger: SyncDataMerger,
    private val noChangeMessage: String,
    private val initialUploadMessage: String,
    private val inProgressError: () -> Exception,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
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
        val localData = local.snapshot()
        val fetched = backend.fetch()
        if (fetched.isFailure) return failure(backend, fetched.exceptionOrNull())
        val remote = fetched.getOrThrow()
        val firstSync = backend.isFirstSync
        val resolved = resolveUpload(backend, localData, remote, mutationVersion)
        if (resolved.isFailure) return uploadFailure(backend, resolved.exceptionOrNull())
        val resolution = resolved.getOrThrow()
        SyncSessionCommitter(local, nowMs).commit(backend, resolution, firstSync, mutationVersion)
        return Result.success(
            SyncSessionResultPolicy.result(
                resolution, firstSync, remote.data == null, noChangeMessage, initialUploadMessage
            )
        )
    }

    private suspend fun <TVersion> resolveUpload(
        backend: SyncBackend<TVersion>,
        localData: SyncData,
        remote: SyncRemoteSnapshot<TVersion>,
        mutationVersion: Long
    ): Result<SyncUploadResolution<SyncMergeResult, TVersion>> {
        val lastSyncTime = backend.lastSyncTime
        return SyncUploadRetryExecutor.execute(
            initialRemote = remote,
            initialVersion = remote.version,
            initialRemoteChangedDuringSync = backend.remoteChanged(remote.version),
            merge = { snapshot -> merge(localData, snapshot.data, lastSyncTime) },
            hasMeaningfulChange = { snapshot, merged -> shouldUpload(snapshot, merged.mergedData) },
            upload = { merged, version ->
                if (local.mutationVersion() != mutationVersion) {
                    Result.failure(LocalSyncMutationConflictException(backend.mutationConflictMessage))
                } else {
                    backend.upload(merged.mergedData, version)
                }
            },
            refetch = { version -> backend.refetch(version).map { it to it.version } },
            isConflict = backend::isConflict
        )
    }

    private fun merge(localData: SyncData, remoteData: SyncData?, lastSyncTime: Long): SyncMergeResult =
        if (remoteData == null) merger.initial(localData) else merger.merge(localData, remoteData, lastSyncTime)

    private fun <TVersion> shouldUpload(remote: SyncRemoteSnapshot<TVersion>, merged: SyncData): Boolean {
        return SyncUploadPolicy.shouldUpload(remote.data, remote.requiresMigrationUpload, merged)
    }

    private fun <TVersion> uploadFailure(backend: SyncBackend<TVersion>, error: Throwable?): Result<SyncResult> {
        if (error is LocalSyncMutationConflictException) {
            backend.scheduleFollowUp()
            return Result.success(SyncResult(success = true, message = noChangeMessage))
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
