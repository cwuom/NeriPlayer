package moe.ouom.neriplayer.core.download.storage.reference

import android.content.Context

/**
 * keeps provider failures separate from evidence that a managed reference is gone
 */
internal object ManagedDownloadReferenceLookup {
    sealed interface Result {
        data object Present : Result
        data object Missing : Result
        data object OutOfScope : Result
        data class PermissionLost(val cause: SecurityException) : Result
        data class ProviderFailure(val cause: Throwable) : Result
    }

    data class Observation(
        val result: Result,
        val sizeBytes: Long?
    )

    fun canMarkMissing(result: Result): Boolean = result is Result.Missing

    fun inspect(context: Context, reference: String?): Result {
        return inspectWithSize(context, reference).result
    }

    fun inspectWithSize(context: Context, reference: String?): Observation {
        val normalized = reference?.trim().orEmpty()
        if (normalized.isBlank()) return Observation(Result.OutOfScope, null)
        val observation = ManagedDownloadReferenceIo.inspectWithSize(context, normalized)
        val result = when (val access = observation.result) {
            ManagedDownloadReferenceIo.AccessResult.Accessible -> Result.Present
            ManagedDownloadReferenceIo.AccessResult.Missing -> Result.Missing
            ManagedDownloadReferenceIo.AccessResult.PermissionLost -> {
                Result.PermissionLost(SecurityException("SAF permission lost: $normalized"))
            }
            is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> {
                Result.ProviderFailure(access.error)
            }
        }
        return Observation(
            result = result,
            sizeBytes = observation.sizeBytes.takeIf { result == Result.Present }
        )
    }

    fun isMissingFailure(error: Throwable): Boolean {
        return ManagedDownloadReferenceIo.isMissingDocumentFailure(error)
    }

    internal fun classifyFailure(error: Throwable): Result {
        return when {
            ManagedDownloadReferenceIo.isPermissionDocumentFailure(error) -> {
                Result.PermissionLost(SecurityException(error.message, error))
            }
            isMissingFailure(error) -> Result.Missing
            else -> Result.ProviderFailure(error)
        }
    }

}
