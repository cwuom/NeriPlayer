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
    private val onProgress: (ManagedDownloadStorage.MigrationProgress) -> Unit
) {
    private val lock = Any()
    private val activeCopyBytes = mutableMapOf<String, Long>()
    private var stage: ManagedDownloadStorage.MigrationStage = ManagedDownloadStorage.MigrationStage.PREPARING
    private var currentFileName: String? = null
    private var completedCopyBytes = 0L
    private var copiedFiles = 0
    private var metadataFilesProcessed = 0
    private var cleanupFilesProcessed = 0
    private var cleanupFilesTotal = 0
    private var lastEmitAtMs = 0L

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
            emitLocked(force = true)
        }
    }

    fun onCopyProgress(entry: ManagedMigrationProgressEntry, copiedBytes: Long) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.COPYING
            currentFileName = entry.name
            activeCopyBytes[entry.reference] = copiedBytes.coerceAtLeast(0L)
            emitLocked(force = false)
        }
    }

    fun completeCopy(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.COPYING
            currentFileName = entry.name
            val finishedBytes = activeCopyBytes.remove(entry.reference)
                ?: entry.sizeBytes.coerceAtLeast(0L)
            completedCopyBytes += finishedBytes.coerceAtLeast(0L)
            copiedFiles++
            emitLocked(force = true)
        }
    }

    fun failCopy(entry: ManagedMigrationProgressEntry) {
        synchronized(lock) {
            activeCopyBytes.remove(entry.reference)
            currentFileName = entry.name
            emitLocked(force = true)
        }
    }

    fun startRewrite(fileName: String?) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.REWRITING_METADATA
            currentFileName = fileName
            emitLocked(force = true)
        }
    }

    fun finishRewrite(fileName: String?) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.REWRITING_METADATA
            currentFileName = fileName
            metadataFilesProcessed++
            emitLocked(force = true)
        }
    }

    fun startCleanup(totalEntries: Int, fileName: String?) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.CLEANING_UP
            cleanupFilesTotal = totalEntries
            currentFileName = fileName
            emitLocked(force = true)
        }
    }

    fun finishCleanup(fileName: String?) {
        synchronized(lock) {
            stage = ManagedDownloadStorage.MigrationStage.CLEANING_UP
            currentFileName = fileName
            cleanupFilesProcessed++
            emitLocked(force = true)
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
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitAtMs < MIGRATION_PROGRESS_EMIT_INTERVAL_MS) {
            return
        }
        lastEmitAtMs = now
        val inFlightBytes = activeCopyBytes.values.sum()
        onProgress(
            ManagedDownloadStorage.MigrationProgress(
                stage = stage,
                totalFiles = totalFiles,
                processedFiles = when (stage) {
                    ManagedDownloadStorage.MigrationStage.PREPARING -> 0
                    ManagedDownloadStorage.MigrationStage.COPYING -> copiedFiles
                    ManagedDownloadStorage.MigrationStage.REWRITING_METADATA -> copiedFiles + metadataFilesProcessed
                    ManagedDownloadStorage.MigrationStage.CLEANING_UP -> copiedFiles + metadataFilesTotal + cleanupFilesProcessed
                    ManagedDownloadStorage.MigrationStage.FINALIZING -> totalFiles
                }.coerceAtMost(totalFiles),
                copiedFiles = copiedFiles,
                copiedBytes = completedCopyBytes + inFlightBytes,
                totalBytes = totalBytes,
                metadataFilesProcessed = metadataFilesProcessed,
                metadataFilesTotal = metadataFilesTotal,
                cleanupFilesProcessed = cleanupFilesProcessed,
                cleanupFilesTotal = cleanupFilesTotal,
                currentFileName = currentFileName
            )
        )
    }
}
