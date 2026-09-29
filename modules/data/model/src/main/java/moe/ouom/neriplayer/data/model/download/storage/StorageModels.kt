package moe.ouom.neriplayer.data.model.download.storage

import android.net.Uri

sealed interface StorageReference {
    data class FileRef(val logicalPath: String) : StorageReference
    data class SafRef(val uri: Uri) : StorageReference
}

sealed interface StorageTarget {
    val temporaryWriteOwnerName: String?

    data class FileTarget(
        val logicalPath: String,
        override val temporaryWriteOwnerName: String? = null
    ) : StorageTarget

    data class SafTarget(
        val parent: StorageReference.SafRef,
        val displayName: String,
        val mimeType: String,
        override val temporaryWriteOwnerName: String? = null
    ) : StorageTarget
}

data class StorageStat(
    val reference: StorageReference,
    val displayName: String,
    val sizeBytes: Long?,
    val lastModifiedMs: Long?,
    val isDirectory: Boolean
)

data class StorageDirectorySnapshot(
    val entries: List<StorageStat>,
    val confidence: StorageConfidence
)

sealed interface StorageConfidence {
    data object Complete : StorageConfidence
    data object Missing : StorageConfidence
    data object OutOfScope : StorageConfidence
    data object PermissionLost : StorageConfidence
    data class ProviderFailure(val error: Throwable) : StorageConfidence
}

sealed interface StorageLookupResult<out T> {
    data class Found<T>(val value: T) : StorageLookupResult<T>
    data object Missing : StorageLookupResult<Nothing>
    data object OutOfScope : StorageLookupResult<Nothing>
    data object PermissionLost : StorageLookupResult<Nothing>
    data class ProviderFailure(val error: Throwable) : StorageLookupResult<Nothing>
    data class Unsupported(val operation: String) : StorageLookupResult<Nothing>
}

sealed interface StorageWriteResult {
    data class Written(val stat: StorageStat) : StorageWriteResult
    data object Missing : StorageWriteResult
    data object OutOfScope : StorageWriteResult
    data object PermissionLost : StorageWriteResult
    data class ProviderFailure(val error: Throwable) : StorageWriteResult
    data class Unsupported(val operation: String) : StorageWriteResult
}

sealed interface StorageMutationResult {
    data object Deleted : StorageMutationResult
    data object Missing : StorageMutationResult
    data object OutOfScope : StorageMutationResult
    data object PermissionLost : StorageMutationResult
    data class ProviderFailure(val error: Throwable) : StorageMutationResult
    data class Unsupported(val operation: String) : StorageMutationResult
}

sealed interface StorageRenameResult {
    data class Renamed(val stat: StorageStat) : StorageRenameResult
    data object Missing : StorageRenameResult
    data object OutOfScope : StorageRenameResult
    data object PermissionLost : StorageRenameResult
    data class ProviderFailure(val error: Throwable) : StorageRenameResult
    data class Unsupported(val operation: String) : StorageRenameResult
}

data class StorageCapabilities(
    val canRead: Boolean,
    val canWrite: Boolean,
    val canCreate: Boolean,
    val canDelete: Boolean,
    val canRename: Boolean,
    val canMove: Boolean,
    val canCopy: Boolean,
    val hasReliableSize: Boolean,
    val hasReliableLastModified: Boolean
)
