package moe.ouom.neriplayer.data.sync.github

import java.io.IOException

internal class LocalSyncMutationConflictException(message: String) : IOException(message)

internal object SyncUploadRetryExecutor {
    data class Resolution<TMerged, TVersion>(
        val merged: TMerged,
        val remoteVersion: TVersion,
        val uploadPerformed: Boolean,
        val remoteChangedDuringSync: Boolean
    )

    suspend fun <TRemote, TMerged, TVersion> execute(
        initialRemote: TRemote,
        initialVersion: TVersion,
        initialRemoteChangedDuringSync: Boolean,
        maxConflictRetries: Int = 3,
        merge: (TRemote) -> TMerged,
        hasMeaningfulChange: (TRemote, TMerged) -> Boolean,
        upload: suspend (TMerged, TVersion) -> Result<TVersion>,
        refetch: suspend (TVersion) -> Result<Pair<TRemote, TVersion>>,
        isConflict: (Throwable?) -> Boolean
    ): Result<Resolution<TMerged, TVersion>> {
        var remote = initialRemote
        var version = initialVersion
        var remoteChangedDuringSync = initialRemoteChangedDuringSync

        repeat(maxConflictRetries + 1) { attempt ->
            val merged = merge(remote)
            if (!hasMeaningfulChange(remote, merged)) {
                return Result.success(
                    Resolution(
                        merged = merged,
                        remoteVersion = version,
                        uploadPerformed = false,
                        remoteChangedDuringSync = remoteChangedDuringSync
                    )
                )
            }

            val uploadResult = upload(merged, version)
            if (uploadResult.isSuccess) {
                return Result.success(
                    Resolution(
                        merged = merged,
                        remoteVersion = uploadResult.getOrThrow(),
                        uploadPerformed = true,
                        remoteChangedDuringSync = remoteChangedDuringSync
                    )
                )
            }

            val error = uploadResult.exceptionOrNull()
            if (!isConflict(error) || attempt >= maxConflictRetries) {
                return Result.failure(error ?: IOException("Upload failed"))
            }

            val refetchResult = refetch(version)
            if (refetchResult.isFailure) {
                return Result.failure(
                    refetchResult.exceptionOrNull() ?: IOException("Refetch failed after conflict")
                )
            }

            val (freshRemote, freshVersion) = refetchResult.getOrThrow()
            remote = freshRemote
            version = freshVersion
            remoteChangedDuringSync = true
        }

        return Result.failure(IOException("Retry budget exhausted"))
    }
}
