package moe.ouom.neriplayer.core.download.storage.migration

import java.io.IOException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

internal class ManagedDownloadMigrationException(
    message: String,
    val retryable: Boolean,
    val retryWithinEntry: Boolean = retryable,
    cause: Throwable? = null
) : IOException(message, cause) {
    companion object {
        fun permanent(message: String, cause: Throwable? = null): ManagedDownloadMigrationException {
            return ManagedDownloadMigrationException(message, retryable = false, cause = cause)
        }

        fun transient(message: String, cause: Throwable? = null): ManagedDownloadMigrationException {
            return ManagedDownloadMigrationException(message, retryable = true, cause = cause)
        }

        fun targetChanged(message: String, cause: Throwable? = null): ManagedDownloadMigrationException {
            return ManagedDownloadMigrationException(
                message = message,
                retryable = true,
                retryWithinEntry = false,
                cause = cause
            )
        }
    }
}

internal data class ManagedMigrationEntry(
    val subdirectory: String?,
    val entry: ManagedDownloadStorage.StoredEntry,
    val metadata: ManagedDownloadStorage.DownloadedAudioMetadata? = null
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

internal data class ManagedMigrationReplacementPlan(
    val sourceReference: String,
    val groupIdentity: String,
    val subdirectory: String?,
    val targetName: String,
    val targetEntry: ManagedDownloadStorage.StoredEntry,
    val backupName: String
)

internal enum class ManagedMigrationReplacementJournalPhase {
    PLANNED,
    TARGETS_VERIFIED,
    DIRECTORY_COMMITTED
}

internal data class ManagedMigrationReplacementJournal(
    val version: Int = CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION,
    val workId: String,
    val fromDirectoryUri: String?,
    val toDirectoryUri: String?,
    val backupNamespace: String,
    val phase: ManagedMigrationReplacementJournalPhase,
    val replacements: List<ManagedMigrationReplacementPlan>,
    val targetNamesByReference: Map<String, String> = emptyMap()
)

internal const val CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION = 1

internal data class CopiedMigrationEntry(
    val original: ManagedMigrationEntry,
    val copiedEntry: ManagedDownloadStorage.StoredEntry,
    val createdNew: Boolean,
    val sourceDigest: String? = null,
    val replacementBackup: ManagedDownloadStorage.StoredEntry? = null,
    val sourceAuthoritative: Boolean = false
) {
    fun toVerificationProgressEntry(): ManagedMigrationProgressEntry {
        val sourceBytes = if (sourceDigest.isNullOrBlank()) {
            original.entry.sizeBytes.coerceAtLeast(0L)
        } else {
            0L
        }
        val targetBytes = copiedEntry.sizeBytes.coerceAtLeast(0L)
        val logicalBytes = if (sourceBytes > Long.MAX_VALUE - targetBytes) {
            Long.MAX_VALUE
        } else {
            sourceBytes + targetBytes
        }
        return ManagedMigrationProgressEntry(
            reference = copiedEntry.reference,
            name = copiedEntry.name,
            sizeBytes = logicalBytes
        )
    }
}

internal data class ManagedMigrationMetadataRewriteResult(
    val copiedEntries: List<CopiedMigrationEntry>,
    val failedFiles: Int,
    val error: ManagedDownloadMigrationException? = null
)

internal data class ManagedMigrationVerificationResult(
    val failedFiles: Int,
    val error: ManagedDownloadMigrationException? = null
)

internal data class ManagedMigrationCleanupResult(
    val failedFiles: Int,
    val retryableFailedFiles: Int
)

internal fun resolveMinimumMigrationAudioCount(
    requestedMinimum: Int,
    discoveredSourceAudioCount: Int
): Int {
    return maxOf(requestedMinimum, discoveredSourceAudioCount).coerceAtLeast(0)
}

internal data class StoredWriteResult(
    val entry: ManagedDownloadStorage.StoredEntry,
    val createdNew: Boolean,
    /**
     * deterministic backup retained while a source-authoritative replacement is
     * being verified. The migration journal owns its eventual deletion.
     */
    val replacementBackup: ManagedDownloadStorage.StoredEntry? = null,
    val sourceAuthoritative: Boolean = false
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

    fun startVerification(entries: List<CopiedMigrationEntry>) {
        delegate.startVerification(entries.map(CopiedMigrationEntry::toVerificationProgressEntry))
    }

    fun startVerificationEntry(entry: CopiedMigrationEntry) {
        delegate.startVerificationEntry(entry.toVerificationProgressEntry())
    }

    fun onVerificationProgress(entry: CopiedMigrationEntry, verifiedBytes: Long) {
        delegate.onVerificationProgress(
            entry = entry.toVerificationProgressEntry(),
            verifiedBytes = verifiedBytes
        )
    }

    fun finishVerification(entry: CopiedMigrationEntry) {
        delegate.finishVerification(entry.toVerificationProgressEntry())
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
