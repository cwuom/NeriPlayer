package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import java.io.File

internal object ManagedDownloadMigrationTargetResolver {
    fun resolveFileTarget(
        parent: File,
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        readExistingEntry: (File) -> ManagedDownloadStorage.StoredEntry?,
        reserveName: (String) -> String,
        rememberName: (String) -> Unit,
        onReuseMetadata: (ManagedDownloadStorage.StoredEntry) -> Unit,
        onReuseFile: (ManagedDownloadStorage.StoredEntry) -> Unit
    ): StoredWriteResult {
        val existing = File(parent, displayName)
        if (displayName in targetNames || existing.exists()) {
            reusedMetadataTarget(sourceEntry, targetEntry, readExistingEntry(existing))
                ?.let { existingEntry ->
                    onReuseMetadata(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            reusedEquivalentTarget(sourceEntry, targetEntry, readExistingEntry(existing))
                ?.let { existingEntry ->
                    onReuseFile(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            return plannedWriteResult(reserveName(displayName))
        }
        rememberName(displayName)
        return plannedWriteResult(displayName)
    }

    fun resolveTreeTarget(
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        existingChildEntry: ManagedDownloadStorage.StoredEntry?,
        reserveName: (String) -> String,
        rememberName: (String) -> Unit,
        onReuseMetadata: (ManagedDownloadStorage.StoredEntry) -> Unit,
        onReuseFile: (ManagedDownloadStorage.StoredEntry) -> Unit
    ): StoredWriteResult {
        if (displayName in targetNames) {
            reusedMetadataTarget(sourceEntry, targetEntry, existingChildEntry)
                ?.let { existingEntry ->
                    onReuseMetadata(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            reusedEquivalentTarget(sourceEntry, targetEntry, existingChildEntry)
                ?.let { existingEntry ->
                    onReuseFile(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            return plannedWriteResult(reserveName(displayName))
        }
        rememberName(displayName)
        return plannedWriteResult(displayName)
    }

    private fun reusedMetadataTarget(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        existingEntry: ManagedDownloadStorage.StoredEntry?
    ): ManagedDownloadStorage.StoredEntry? {
        if (!sourceEntry.name.endsWith(METADATA_SUFFIX)) {
            return null
        }
        return targetEntry ?: existingEntry
    }

    private fun reusedEquivalentTarget(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        existingEntry: ManagedDownloadStorage.StoredEntry?
    ): ManagedDownloadStorage.StoredEntry? {
        return targetEntry
            ?.takeIf { entry -> ManagedDownloadMigrationNamePlanner.isEquivalentMigrationTarget(sourceEntry, entry) }
            ?: existingEntry
                ?.takeIf { entry -> ManagedDownloadMigrationNamePlanner.isEquivalentMigrationTarget(sourceEntry, entry) }
    }

    private fun plannedWriteResult(displayName: String): StoredWriteResult {
        return StoredWriteResult(
            entry = ManagedDownloadStorage.StoredEntry(
                name = displayName,
                reference = "",
                mediaUri = "",
                localFilePath = null,
                sizeBytes = 0L,
                lastModifiedMs = 0L,
                isDirectory = false
            ),
            createdNew = true
        )
    }
}
