package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

internal data class ManagedMigrationEntry(
    val subdirectory: String?,
    val entry: ManagedDownloadStorage.StoredEntry
) {
    fun toRef(): ManagedMigrationEntryRef {
        return ManagedMigrationEntryRef(
            subdirectory = subdirectory,
            entry = entry
        )
    }

    fun toProgressEntry(): ManagedMigrationProgressEntry {
        return ManagedMigrationProgressEntry(
            reference = entry.reference,
            name = entry.name,
            sizeBytes = entry.sizeBytes
        )
    }
}

internal data class CopiedMigrationEntry(
    val original: ManagedMigrationEntry,
    val copiedEntry: ManagedDownloadStorage.StoredEntry,
    val createdNew: Boolean
)

internal data class StoredWriteResult(
    val entry: ManagedDownloadStorage.StoredEntry,
    val createdNew: Boolean
)

internal class ManagedMigrationProgressReporter(
    totalFiles: Int,
    totalBytes: Long,
    metadataFilesTotal: Int,
    onProgress: (ManagedDownloadStorage.MigrationProgress) -> Unit
) {
    private val delegate = ManagedDownloadMigrationProgressTracker(
        totalFiles = totalFiles,
        totalBytes = totalBytes,
        metadataFilesTotal = metadataFilesTotal,
        onProgress = onProgress
    )

    fun startPreparing(fileName: String? = null) {
        delegate.startPreparing(fileName)
    }

    fun startCopy(entry: ManagedMigrationEntry) {
        delegate.startCopy(entry.toProgressEntry())
    }

    fun onCopyProgress(entry: ManagedMigrationEntry, copiedBytes: Long) {
        delegate.onCopyProgress(entry.toProgressEntry(), copiedBytes)
    }

    fun completeCopy(entry: ManagedMigrationEntry) {
        delegate.completeCopy(entry.toProgressEntry())
    }

    fun failCopy(entry: ManagedMigrationEntry) {
        delegate.failCopy(entry.toProgressEntry())
    }

    fun startRewrite(fileName: String?) {
        delegate.startRewrite(fileName)
    }

    fun finishRewrite(fileName: String?) {
        delegate.finishRewrite(fileName)
    }

    fun startCleanup(totalEntries: Int, fileName: String?) {
        delegate.startCleanup(totalEntries, fileName)
    }

    fun finishCleanup(fileName: String?) {
        delegate.finishCleanup(fileName)
    }

    fun finishAll() {
        delegate.finishAll()
    }
}
