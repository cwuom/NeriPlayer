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

    fun canMarkMissing(result: Result): Boolean = result is Result.Missing

    fun inspect(context: Context, reference: String?): Result {
        val normalized = reference?.trim().orEmpty()
        if (normalized.isBlank()) return Result.OutOfScope
        return when (val result = ManagedDownloadReferenceIo.inspect(context, normalized)) {
            ManagedDownloadReferenceIo.AccessResult.Accessible -> Result.Present
            ManagedDownloadReferenceIo.AccessResult.Missing -> Result.Missing
            ManagedDownloadReferenceIo.AccessResult.PermissionLost -> {
                Result.PermissionLost(SecurityException("SAF permission lost: $normalized"))
            }
            is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> {
                Result.ProviderFailure(result.error)
            }
        }
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
