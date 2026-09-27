package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import java.io.File
import java.io.IOException

abstract class ManagedDownloadStorageMigrationCompatTestSupport {




    internal fun deleteFile(reference: TrustedManagedRef): StorageMutationResult {
        val fileReference = reference.reference as? StorageReference.FileRef
            ?: return StorageMutationResult.Unsupported("file reference required")
        val file = File(fileReference.logicalPath)
        return if (!file.exists()) {
            StorageMutationResult.Missing
        } else if (file.delete()) {
            StorageMutationResult.Deleted
        } else {
            StorageMutationResult.ProviderFailure(IOException("delete failed"))
        }
    }















































































}
