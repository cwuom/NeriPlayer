package moe.ouom.neriplayer.data.sync.retry

import moe.ouom.neriplayer.data.model.sync.SyncUploadResolution
import java.io.IOException

internal class LocalSyncMutationConflictException(message: String) : IOException(message)

object SyncUploadRetryExecutor {
    suspend fun <TRemote, TMerged, TVersion> execute(
        initialRemote: TRemote,
        initialVersion: TVersion,
        initialRemoteChangedDuringSync: Boolean,
        maxConflictRetries: Int = 3,
        merge: suspend (TRemote) -> TMerged,
        hasMeaningfulChange: suspend (TRemote, TMerged) -> Boolean,
        upload: suspend (TMerged, TVersion) -> Result<TVersion>,
        refetch: suspend (TVersion) -> Result<Pair<TRemote, TVersion>>,
        isConflict: (Throwable?) -> Boolean,
        disposeMerged: (TMerged) -> Unit = {},
        disposeRemote: (TRemote) -> Unit = {}
    ): Result<SyncUploadResolution<TMerged, TVersion>> {
        val resources = SyncRetryResources(initialRemote, disposeRemote, disposeMerged)
        var version = initialVersion
        var remoteChanged = initialRemoteChangedDuringSync
        var failure: Throwable? = null
        try {
            repeat(maxConflictRetries + 1) { attempt ->
                val changed = resources.stage(merge, hasMeaningfulChange)
                if (!changed) return resources.success(version, false, remoteChanged)
                val uploadResult = upload(resources.merged(), version)
                if (uploadResult.isSuccess) return resources.success(uploadResult.getOrThrow(), true, remoteChanged)
                resources.releaseMerged()
                val fresh = refetchAfterConflict(
                    uploadResult.exceptionOrNull(), attempt, maxConflictRetries, version, refetch, isConflict
                )
                if (fresh.isFailure) return Result.failure(fresh.exceptionOrNull() ?: IOException("Refetch failed after conflict"))
                val (remote, nextVersion) = fresh.getOrThrow()
                resources.replaceRemote(remote)
                version = nextVersion
                remoteChanged = true
            }
            return Result.failure(IOException("Retry budget exhausted"))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            resources.close(failure)
        }
    }

    private suspend fun <TRemote, TVersion> refetchAfterConflict(
        error: Throwable?,
        attempt: Int,
        maxConflictRetries: Int,
        version: TVersion,
        refetch: suspend (TVersion) -> Result<Pair<TRemote, TVersion>>,
        isConflict: (Throwable?) -> Boolean
    ): Result<Pair<TRemote, TVersion>> {
        if (!isConflict(error) || attempt >= maxConflictRetries) {
            return Result.failure(error ?: IOException("Upload failed"))
        }
        return refetch(version)
    }
}

private class SyncRetryResources<TRemote, TMerged>(
    remote: TRemote,
    private val disposeRemote: (TRemote) -> Unit,
    private val disposeMerged: (TMerged) -> Unit
) {
    private class Held<T>(val value: T)
    private var remote: Held<TRemote>? = Held(remote)
    private var merged: Held<TMerged>? = null

    suspend fun stage(merge: suspend (TRemote) -> TMerged, changed: suspend (TRemote, TMerged) -> Boolean): Boolean {
        val input = checkNotNull(remote).value
        val value = merge(input)
        merged = Held(value)
        val result = changed(input, value)
        releaseRemote()
        return result
    }

    fun merged(): TMerged = checkNotNull(merged).value

    fun <TVersion> success(version: TVersion, uploaded: Boolean, remoteChanged: Boolean): Result<SyncUploadResolution<TMerged, TVersion>> {
        val value = merged()
        merged = null
        return Result.success(SyncUploadResolution(value, version, uploaded, remoteChanged))
    }

    fun replaceRemote(value: TRemote) { remote = Held(value) }

    fun releaseMerged() {
        val held = merged ?: return
        merged = null
        disposeMerged(held.value)
    }

    private fun releaseRemote() {
        val held = remote ?: return
        remote = null
        disposeRemote(held.value)
    }

    fun close(original: Throwable?) {
        var failure = original
        failure = cleanup(failure, ::releaseMerged)
        failure = cleanup(failure, ::releaseRemote)
        if (original == null && failure != null) throw failure
    }

    private fun cleanup(original: Throwable?, release: () -> Unit): Throwable? {
        try {
            release()
        } catch (error: Throwable) {
            if (original == null) return error
            original.addSuppressed(error)
        }
        return original
    }
}
