package moe.ouom.neriplayer.core.download.storage.recovery

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger

internal object ManagedDownloadPendingAudioWriteCleaner {
    fun cleanup(
        context: Context,
        root: ManagedDownloadRootHandle,
        names: ManagedDownloadPendingAudioWriteNames,
        treeChildRegistry: ManagedDownloadTreeChildRegistry,
        deleteTreeChild: (QueriedTreeChild) -> StorageMutationResult,
        preserveEntry: (String) -> Boolean = { false },
        tag: String
    ): ManagedDownloadStorage.StartupRecoveryResult {
        return runCatching {
            val pendingEntries = when (root) {
                is ManagedDownloadRootHandle.FileRoot -> root.dir.listFiles()
                    ?.filter(File::isFile)
                    ?.filter { file -> names.isPendingAudioWriteName(file.name) }
                    .orEmpty()

                is ManagedDownloadRootHandle.TreeRoot -> treeChildRegistry.queryTreeChildren(context, root.tree)
                    .filterNot(QueriedTreeChild::isDirectory)
                    .filter { child -> names.isPendingAudioWriteName(child.name) }
            }

            val result = deletePendingEntries(pendingEntries, deleteTreeChild, preserveEntry)
            if (result.cleanedCount > 0 || result.failedCount > 0) {
                NPLogger.d(
                    tag,
                    "清理下载提交残留完成: cleaned=${result.cleanedCount}, failed=${result.failedCount}"
                )
            }
            result
        }.onFailure {
            NPLogger.w(tag, "清理下载提交残留失败: ${it.message}")
        }.getOrDefault(ManagedDownloadStorage.StartupRecoveryResult())
    }

    private fun deletePendingEntries(
        pendingEntries: List<Any>,
        deleteTreeChild: (QueriedTreeChild) -> StorageMutationResult,
        preserveEntry: (String) -> Boolean
    ): ManagedDownloadStorage.StartupRecoveryResult {
        var cleanedCount = 0
        var failedCount = 0
        pendingEntries.forEach { entry ->
            val name = when (entry) {
                is File -> entry.name
                is QueriedTreeChild -> entry.name
                else -> ""
            }
            if (name.isNotBlank() && preserveEntry(name)) {
                return@forEach
            }
            val mutation = when (entry) {
                is File -> ManagedDownloadReferenceIo.deleteFileReference(entry).toStorageMutationResult()
                is QueriedTreeChild -> runCatching { deleteTreeChild(entry) }
                    .getOrElse { error ->
                        if (error is SecurityException) {
                            StorageMutationResult.PermissionLost
                        } else {
                            StorageMutationResult.ProviderFailure(error)
                        }
                    }
                else -> StorageMutationResult.OutOfScope
            }
            if (mutation is StorageMutationResult.Deleted || mutation is StorageMutationResult.Missing) {
                cleanedCount++
            } else {
                failedCount++
            }
        }
        return ManagedDownloadStorage.StartupRecoveryResult(
            cleanedCount = cleanedCount,
            failedCount = failedCount
        )
    }
}

private fun ManagedDownloadReferenceIo.DeleteResult.toStorageMutationResult(): StorageMutationResult {
    return when (this) {
        ManagedDownloadReferenceIo.DeleteResult.Deleted -> StorageMutationResult.Deleted
        ManagedDownloadReferenceIo.DeleteResult.Missing -> StorageMutationResult.Missing
        ManagedDownloadReferenceIo.DeleteResult.PermissionLost -> StorageMutationResult.PermissionLost
        is ManagedDownloadReferenceIo.DeleteResult.ProviderFailure -> {
            StorageMutationResult.ProviderFailure(error)
        }
    }
}
