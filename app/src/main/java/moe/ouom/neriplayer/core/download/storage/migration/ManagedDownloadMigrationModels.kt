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

/**
 * durable evidence for a target that was verified before its source was removed
 */
internal data class ManagedMigrationCleanupReceipt(
    val sourceReference: String,
    val sourceName: String,
    val sourceSubdirectory: String?,
    val targetEntry: ManagedDownloadStorage.StoredEntry,
    val targetDigest: String
)

/**
 * durable evidence that one source entry was copied to its planned target
 * before the migration reached the batch verification stage
 */
internal data class ManagedMigrationCopyReceipt(
    val sourceReference: String,
    val sourceName: String,
    val sourceSubdirectory: String?,
    val sourceSizeBytes: Long,
    val sourceLastModifiedMs: Long,
    val targetEntry: ManagedDownloadStorage.StoredEntry,
    val sourceDigest: String? = null,
    val verifiedTargetDigest: String? = null,
    val createdNew: Boolean,
    val sourceAuthoritative: Boolean,
    val replacementBackup: ManagedDownloadStorage.StoredEntry? = null
)

/**
 * stable source identity captured before the migration starts copying
 */
internal data class ManagedMigrationSourceEntry(
    val sourceReference: String,
    val sourceName: String,
    val sourceSubdirectory: String?,
    val sizeBytes: Long,
    val lastModifiedMs: Long
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
    val targetNamesByReference: Map<String, String> = emptyMap(),
    val cleanupReceipts: List<ManagedMigrationCleanupReceipt> = emptyList(),
    val cleanupComplete: Boolean = false,
    val sourceEntryCount: Int = 0,
    val sourceEntries: List<ManagedMigrationSourceEntry> = emptyList(),
    /** audio sources confirmed missing after the complete source scan */
    val deletedSourceAudioCount: Int = 0,
    /** distinguishes a complete empty source scan from an unknown legacy count */
    val sourceEntriesComplete: Boolean =
        sourceEntryCount > 0 || sourceEntries.isNotEmpty() || deletedSourceAudioCount > 0
) {
    /**
     * v1 journals never recorded the complete source set, so their count must
     * not be inferred from a later, potentially partial provider scan
     */
    val sourceEntryCountKnown: Boolean
        get() = version >= CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION &&
            (sourceEntriesComplete ||
                // copies of older in-memory journals may retain the default
                // marker while still carrying a non-empty complete manifest
                sourceEntryCount > 0 ||
                sourceEntries.isNotEmpty() ||
                deletedSourceAudioCount > 0)
}

/**
 * 持久化一次迁移的完整输入，进程被杀后可重新创建同一迁移任务
 */
internal data class ManagedMigrationRequest(
    val workId: String,
    val fromDirectoryUri: String?,
    val toDirectoryUri: String?,
    val targetLabel: String,
    val releasePreviousPermission: Boolean,
    val minimumSourceEntryCount: Int,
    val checkpointWorkId: String? = null,
    val autoResume: Boolean = true,
    /** retries consumed by workers replaced during startup recovery */
    val retryAttemptOffset: Int = 0
) {
    fun normalized(): ManagedMigrationRequest {
        return copy(
            workId = workId.trim(),
            fromDirectoryUri = fromDirectoryUri?.trim()?.takeIf(String::isNotBlank),
            toDirectoryUri = toDirectoryUri?.trim()?.takeIf(String::isNotBlank),
            targetLabel = targetLabel.trim(),
            minimumSourceEntryCount = minimumSourceEntryCount.coerceAtLeast(0),
            checkpointWorkId = checkpointWorkId?.trim()?.takeIf(String::isNotBlank),
            retryAttemptOffset = retryAttemptOffset.coerceAtLeast(0)
        )
    }
}

internal const val CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION = 2

internal data class CopiedMigrationEntry(
    val original: ManagedMigrationEntry,
    val copiedEntry: ManagedDownloadStorage.StoredEntry,
    val createdNew: Boolean,
    val sourceDigest: String? = null,
    val verifiedTargetDigest: String? = null,
    val replacementBackup: ManagedDownloadStorage.StoredEntry? = null,
    val sourceAuthoritative: Boolean = false,
    /** receipt-reused targets may have changed while the process was stopped */
    val reusedFromReceipt: Boolean = false
) {
    fun toCopyReceipt(): ManagedMigrationCopyReceipt {
        return ManagedMigrationCopyReceipt(
            sourceReference = original.entry.reference,
            sourceName = original.entry.name,
            sourceSubdirectory = original.subdirectory,
            sourceSizeBytes = original.entry.sizeBytes.coerceAtLeast(0L),
            sourceLastModifiedMs = original.entry.lastModifiedMs.coerceAtLeast(0L),
            targetEntry = copiedEntry,
            sourceDigest = sourceDigest,
            verifiedTargetDigest = verifiedTargetDigest,
            createdNew = createdNew,
            sourceAuthoritative = sourceAuthoritative,
            replacementBackup = replacementBackup
        )
    }

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

internal fun ManagedMigrationCopyReceipt.toCopiedMigrationEntry(
    original: ManagedMigrationEntry,
    targetEntry: ManagedDownloadStorage.StoredEntry = this.targetEntry,
    reusedFromReceipt: Boolean = false
): CopiedMigrationEntry {
    return CopiedMigrationEntry(
        original = original,
        copiedEntry = targetEntry,
        createdNew = createdNew,
        sourceDigest = sourceDigest,
        verifiedTargetDigest = verifiedTargetDigest,
        replacementBackup = replacementBackup,
        sourceAuthoritative = sourceAuthoritative,
        reusedFromReceipt = reusedFromReceipt
    )
}

internal data class ManagedMigrationMetadataRewriteResult(
    val copiedEntries: List<CopiedMigrationEntry>,
    val failedFiles: Int,
    val error: ManagedDownloadMigrationException? = null
)

internal data class ManagedMigrationVerificationResult(
    val failedFiles: Int,
    val error: ManagedDownloadMigrationException? = null,
    val verifiedEntries: List<CopiedMigrationEntry> = emptyList()
)

internal data class ManagedMigrationCleanupResult(
    val failedFiles: Int,
    val retryableFailedFiles: Int
)

internal fun resolveMinimumMigrationAudioCount(
    requestedMinimum: Int,
    discoveredSourceAudioCount: Int,
    deletedSourceAudioCount: Int = 0
): Int {
    return adjustMinimumMigrationAudioCountForDeletedSources(
        minimumAudioCount = maxOf(requestedMinimum, discoveredSourceAudioCount),
        deletedSourceAudioCount = deletedSourceAudioCount
    )
}

internal fun adjustMinimumMigrationAudioCountForDeletedSources(
    minimumAudioCount: Int,
    deletedSourceAudioCount: Int
): Int {
    return (minimumAudioCount.toLong() - deletedSourceAudioCount.coerceAtLeast(0).toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
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
    onProgress: (ManagedDownloadStorage.MigrationProgress) -> Unit,
    initialProgress: ManagedDownloadStorage.MigrationProgress? = null
) {
    private val delegate = ManagedDownloadMigrationProgressTracker(
        totalFiles = totalFiles,
        totalBytes = totalBytes,
        metadataFilesTotal = metadataFilesTotal,
        onProgress = onProgress,
        initialProgress = initialProgress
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

    fun seedCompletedCopies(entries: Collection<ManagedMigrationEntry>) {
        delegate.seedCompletedCopies(entries.map(ManagedMigrationEntry::toProgressEntry))
    }

    fun unseedCopy(entry: ManagedMigrationEntry) {
        delegate.unseedCopy(entry.toProgressEntry())
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

    fun seedVerifiedEntries(entries: Collection<CopiedMigrationEntry>) {
        delegate.seedVerifiedEntries(entries.map(CopiedMigrationEntry::toVerificationProgressEntry))
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
