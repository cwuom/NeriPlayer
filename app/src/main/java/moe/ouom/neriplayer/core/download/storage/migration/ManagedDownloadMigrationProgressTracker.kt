package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.MIGRATION_PROGRESS_EMIT_INTERVAL_MS

internal data class ManagedMigrationProgressEntry(
    val reference: String,
    val name: String,
    val sizeBytes: Long
)

internal class ManagedDownloadMigrationProgressTracker(
    private val totalFiles: Int,
    private val totalBytes: Long,
    private val metadataFilesTotal: Int,
    private val onProgress: (ManagedDownloadStorage.MigrationProgress) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    initialProgress: ManagedDownloadStorage.MigrationProgress? = null
) {
    private val lock = Any()
    private val activeCopyBytes = mutableMapOf<String, Long>()
    private val activeVerificationBytes = mutableMapOf<String, Long>()
    private val seededCopyReferences = mutableSetOf<String>()
    private val seededVerificationReferences = mutableSetOf<String>()
    private var stage: ManagedDownloadStorage.MigrationStage = ManagedDownloadStorage.MigrationStage.PREPARING
    private var currentFileName: String? = null
    private var completedCopyBytes = 0L
    private var copiedFiles = 0
    private var metadataFilesProcessed = 0
    private var verificationFilesProcessed = 0
    private var verificationFilesTotal = 0
    private var completedVerificationBytes = 0L
    private var verificationBytesTotal = 0L
    private var cleanupFilesProcessed = 0
    private var cleanupFilesTotal = 0
    private var lastEmitAtMs = 0L
    private var lastEmittedStage: ManagedDownloadStorage.MigrationStage? = null
    private var resumeFloor = initialProgress

    fun startPreparing(fileName: String? = null) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.PREPARING
            currentFileName = fileName
            emitLocked(force = true)
        }
    }

    fun startCopy(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.COPYING
            currentFileName = entry.name
            activeCopyBytes.putIfAbsent(entry.reference, 0L)
            emitLocked(force = false)
        }
    }

    fun onCopyProgress(entry: ManagedMigrationProgressEntry, copiedBytes: Long) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.COPYING
            currentFileName = entry.name
            activeCopyBytes[entry.reference] = maxOf(
                activeCopyBytes[entry.reference] ?: 0L,
                copiedBytes.coerceAtLeast(0L)
            )
            emitLocked(force = false)
        }
    }

    /** seeds durable receipts before the first resumed copy is dispatched */
    fun seedCompletedCopies(entries: Collection<ManagedMigrationProgressEntry>) {
        synchronized(lock) {
            entries.forEach { entry ->
                if (seededCopyReferences.add(entry.reference)) {
                    copiedFiles++
                    completedCopyBytes += entry.sizeBytes.coerceAtLeast(0L)
                }
            }
            emitLocked(force = true)
        }
    }

    /** removes a seed when the provider fingerprint no longer matches */
    fun unseedCopy(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            if (seededCopyReferences.remove(entry.reference)) {
                copiedFiles = (copiedFiles - 1).coerceAtLeast(0)
                completedCopyBytes = (completedCopyBytes - entry.sizeBytes.coerceAtLeast(0L))
                    .coerceAtLeast(0L)
                emitLocked(force = true)
            }
        }
    }

    fun completeCopy(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.COPYING
            currentFileName = entry.name
            val observedBytes = activeCopyBytes.remove(entry.reference)?.coerceAtLeast(0L) ?: 0L
            if (!seededCopyReferences.remove(entry.reference)) {
                val finishedBytes = maxOf(observedBytes, entry.sizeBytes.coerceAtLeast(0L))
                completedCopyBytes += finishedBytes
                copiedFiles++
            }
            emitLocked(force = false)
        }
    }

    fun failCopy(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            currentFileName = entry.name
            emitLocked(force = false)
        }
    }

    fun startRewrite(fileName: String?) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.REWRITING_METADATA
            currentFileName = fileName
            emitLocked(force = false)
        }
    }

    fun finishRewrite(fileName: String?) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.REWRITING_METADATA
            currentFileName = fileName
            metadataFilesProcessed++
            emitLocked(force = false)
        }
    }

    fun startVerification(entries: List<ManagedMigrationProgressEntry>) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.VERIFYING
            verificationFilesProcessed = 0
            verificationFilesTotal = entries.size
            completedVerificationBytes = 0L
            verificationBytesTotal = entries.sumOf { entry ->
                entry.sizeBytes.coerceAtLeast(0L)
            }
            activeVerificationBytes.clear()
            currentFileName = entries.firstOrNull()?.name
            emitLocked(force = true)
        }
    }

    /** publishes already verified receipts without waiting for every skip callback */
    fun seedVerifiedEntries(entries: Collection<ManagedMigrationProgressEntry>) {
        synchronized(lock) {
            entries.forEach { entry ->
                if (seededVerificationReferences.add(entry.reference)) {
                    verificationFilesProcessed = (verificationFilesProcessed + 1)
                        .coerceAtMost(verificationFilesTotal)
                    completedVerificationBytes += entry.sizeBytes.coerceAtLeast(0L)
                }
            }
            emitLocked(force = true)
        }
    }

    fun startVerificationEntry(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.VERIFYING
            currentFileName = entry.name
            activeVerificationBytes.putIfAbsent(entry.reference, 0L)
            emitLocked(force = false)
        }
    }

    fun onVerificationProgress(entry: ManagedMigrationProgressEntry, verifiedBytes: Long) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.VERIFYING
            currentFileName = entry.name
            val boundedBytes = verifiedBytes.coerceAtLeast(0L).let { observed ->
                if (entry.sizeBytes > 0L) observed.coerceAtMost(entry.sizeBytes) else observed
            }
            activeVerificationBytes[entry.reference] = maxOf(
                activeVerificationBytes[entry.reference] ?: 0L,
                boundedBytes
            )
            emitLocked(force = false)
        }
    }

    fun finishVerification(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.VERIFYING
            currentFileName = entry.name
            val observedBytes = activeVerificationBytes.remove(entry.reference)
                ?.coerceAtLeast(0L)
                ?: 0L
            if (!seededVerificationReferences.remove(entry.reference)) {
                completedVerificationBytes += maxOf(
                    observedBytes,
                    entry.sizeBytes.coerceAtLeast(0L)
                )
                verificationFilesProcessed = (verificationFilesProcessed + 1)
                    .coerceAtMost(verificationFilesTotal)
            }
            emitLocked(force = verificationFilesProcessed >= verificationFilesTotal)
        }
    }

    fun startCleanup(totalEntries: Int, fileName: String?) {
        synchronized(lock) {
            // a null file name is the existing batch boundary emitted before
            // source cleanup; reset so replacement cleanup cannot leak counts
            // into the next cleanup pass
            val resetBatch =
                stage != ManagedDownloadStorage.MigrationStage.CLEANING_UP ||
                    fileName == null
            if (resetBatch) {
                cleanupFilesProcessed = 0
            }
            stage = ManagedDownloadStorage.MigrationStage.CLEANING_UP
            cleanupFilesTotal = totalEntries
            currentFileName = fileName
            emitLocked(force = resetBatch)
        }
    }

    fun finishCleanup(fileName: String?) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.CLEANING_UP
            currentFileName = fileName
            cleanupFilesProcessed = if (cleanupFilesTotal > 0) {
                (cleanupFilesProcessed + 1).coerceAtMost(cleanupFilesTotal)
            } else {
                0
            }
            emitLocked(force = cleanupFilesProcessed >= cleanupFilesTotal)
        }
    }

    fun finishAll() {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.FINALIZING
            currentFileName = null
            emitLocked(force = true)
        }
    }

    private fun emitLocked(force: Boolean) {
        val now = nowMs()
        val stageChanged = stage != lastEmittedStage
        if (!force && !stageChanged && now - lastEmitAtMs < MIGRATION_PROGRESS_EMIT_INTERVAL_MS) {
            return
        }
        lastEmitAtMs = now
        lastEmittedStage = stage
        val inFlightBytes = activeCopyBytes.values.sum()
        val inFlightVerificationBytes = activeVerificationBytes.values.sum()
        val currentProgress = ManagedDownloadStorage.MigrationProgress(
            stage = stage,
            totalFiles = totalFiles,
            processedFiles = when (stage) {
                ManagedDownloadStorage.MigrationStage.PREPARING -> 0
                ManagedDownloadStorage.MigrationStage.COPYING -> copiedFiles
                // metadata, verification, and cleanup operate on entries that
                // were already counted by copying; their detailed counters
                // remain available through stageProcessed/stageTotal
                ManagedDownloadStorage.MigrationStage.REWRITING_METADATA,
                ManagedDownloadStorage.MigrationStage.VERIFYING,
                ManagedDownloadStorage.MigrationStage.CLEANING_UP -> copiedFiles
                ManagedDownloadStorage.MigrationStage.FINALIZING -> totalFiles
            }.coerceAtMost(totalFiles),
            copiedFiles = copiedFiles,
            copiedBytes = completedCopyBytes + inFlightBytes,
            totalBytes = totalBytes,
            metadataFilesProcessed = metadataFilesProcessed,
            metadataFilesTotal = metadataFilesTotal,
            cleanupFilesProcessed = cleanupFilesProcessed,
            cleanupFilesTotal = cleanupFilesTotal,
            currentFileName = currentFileName,
            verificationFilesProcessed = verificationFilesProcessed,
            verificationFilesTotal = verificationFilesTotal,
            verifiedBytes = completedVerificationBytes + inFlightVerificationBytes,
            verificationBytesTotal = verificationBytesTotal
        )
        val floor = resumeFloor
        val visibleProgress = mergeMigrationProgressFloor(floor, currentProgress)
        if (floor != null && isMigrationProgressAtLeast(currentProgress, floor)) {
            resumeFloor = null
        }
        onProgress(visibleProgress)
    }
}

internal fun mergeMigrationProgressFloor(
    floor: ManagedDownloadStorage.MigrationProgress?,
    current: ManagedDownloadStorage.MigrationProgress
): ManagedDownloadStorage.MigrationProgress {
    floor ?: return current
    if (current.stage.ordinal >= floor.stage.ordinal) {
        return current.copy(
            totalFiles = maxOf(current.totalFiles, floor.totalFiles).coerceAtLeast(0),
            processedFiles = maxOf(current.processedFiles, floor.processedFiles)
                .coerceAtLeast(0)
                .coerceAtMost(maxOf(current.totalFiles, floor.totalFiles).coerceAtLeast(0)),
            copiedFiles = maxOf(current.copiedFiles, floor.copiedFiles).coerceAtLeast(0),
            copiedBytes = maxOf(current.copiedBytes, floor.copiedBytes).coerceAtLeast(0L),
            totalBytes = maxOf(current.totalBytes, floor.totalBytes).coerceAtLeast(0L),
            metadataFilesProcessed = maxOf(
                current.metadataFilesProcessed,
                floor.metadataFilesProcessed
            ).coerceAtLeast(0),
            metadataFilesTotal = maxOf(current.metadataFilesTotal, floor.metadataFilesTotal)
                .coerceAtLeast(0),
            cleanupFilesProcessed = maxOf(
                current.cleanupFilesProcessed,
                floor.cleanupFilesProcessed
            ).coerceAtLeast(0),
            cleanupFilesTotal = maxOf(current.cleanupFilesTotal, floor.cleanupFilesTotal)
                .coerceAtLeast(0),
            currentFileName = current.currentFileName,
            verificationFilesProcessed = maxOf(
                current.verificationFilesProcessed,
                floor.verificationFilesProcessed
            ).coerceAtLeast(0),
            verificationFilesTotal = maxOf(
                current.verificationFilesTotal,
                floor.verificationFilesTotal
            ).coerceAtLeast(0),
            verifiedBytes = maxOf(current.verifiedBytes, floor.verifiedBytes).coerceAtLeast(0L),
            verificationBytesTotal = maxOf(
                current.verificationBytesTotal,
                floor.verificationBytesTotal
            ).coerceAtLeast(0L)
        ).boundedMigrationProgress()
    }
    return floor.copy(
        totalFiles = maxOf(current.totalFiles, floor.totalFiles).coerceAtLeast(0),
        totalBytes = maxOf(current.totalBytes, floor.totalBytes).coerceAtLeast(0L),
        currentFileName = floor.currentFileName
    ).boundedMigrationProgress()
}

private fun isMigrationProgressAtLeast(
    current: ManagedDownloadStorage.MigrationProgress,
    floor: ManagedDownloadStorage.MigrationProgress
): Boolean {
    if (current.stage.ordinal > floor.stage.ordinal) return true
    if (current.stage != floor.stage) return false
    return when (floor.stage) {
        ManagedDownloadStorage.MigrationStage.PREPARING -> true
        ManagedDownloadStorage.MigrationStage.COPYING ->
            current.copiedFiles >= floor.copiedFiles &&
                current.copiedBytes >= floor.copiedBytes
        ManagedDownloadStorage.MigrationStage.REWRITING_METADATA ->
            current.metadataFilesProcessed >= floor.metadataFilesProcessed
        ManagedDownloadStorage.MigrationStage.VERIFYING ->
            current.verificationFilesProcessed >= floor.verificationFilesProcessed &&
                current.verifiedBytes >= floor.verifiedBytes
        ManagedDownloadStorage.MigrationStage.CLEANING_UP ->
            current.cleanupFilesProcessed >= floor.cleanupFilesProcessed
        ManagedDownloadStorage.MigrationStage.FINALIZING -> true
    }
}

private fun ManagedDownloadStorage.MigrationProgress.boundedMigrationProgress():
    ManagedDownloadStorage.MigrationProgress {
    val boundedTotalFiles = totalFiles.coerceAtLeast(0)
    val boundedMetadataTotal = metadataFilesTotal.coerceAtLeast(0)
    val boundedVerificationTotal = verificationFilesTotal.coerceAtLeast(0)
    val boundedCleanupTotal = cleanupFilesTotal.coerceAtLeast(0)
    return copy(
        totalFiles = boundedTotalFiles,
        processedFiles = processedFiles.coerceAtLeast(0).coerceAtMost(boundedTotalFiles),
        copiedFiles = copiedFiles.coerceAtLeast(0).coerceAtMost(boundedTotalFiles),
        copiedBytes = copiedBytes.coerceAtLeast(0L),
        totalBytes = totalBytes.coerceAtLeast(0L),
        metadataFilesProcessed = metadataFilesProcessed.coerceAtLeast(0)
            .coerceAtMost(boundedMetadataTotal),
        metadataFilesTotal = boundedMetadataTotal,
        cleanupFilesProcessed = cleanupFilesProcessed.coerceAtLeast(0)
            .coerceAtMost(boundedCleanupTotal),
        cleanupFilesTotal = boundedCleanupTotal,
        verificationFilesProcessed = verificationFilesProcessed.coerceAtLeast(0)
            .coerceAtMost(boundedVerificationTotal),
        verificationFilesTotal = boundedVerificationTotal,
        verifiedBytes = verifiedBytes.coerceAtLeast(0L),
        verificationBytesTotal = verificationBytesTotal.coerceAtLeast(0L),
        currentFileName = currentFileName?.trim()?.takeIf(String::isNotBlank)
    )
}
