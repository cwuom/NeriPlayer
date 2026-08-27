package moe.ouom.neriplayer.core.download.storage.migration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.data.settings.SettingsRepository
import kotlin.math.roundToInt

internal fun migrationProgressToWorkData(
    progress: ManagedDownloadStorage.MigrationProgress
): Data {
    return workDataOf(
        ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE to progress.stage.name,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_FRACTION to progress.fraction,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_PROCESSED_FILES to progress.processedFiles,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_FILES to progress.totalFiles,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_COPIED_FILES to progress.copiedFiles,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_COPIED_BYTES to progress.copiedBytes,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_BYTES to progress.totalBytes,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_METADATA_FILES_PROCESSED to
            progress.metadataFilesProcessed,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_METADATA_FILES_TOTAL to
            progress.metadataFilesTotal,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFICATION_FILES_PROCESSED to
            progress.verificationFilesProcessed,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFICATION_FILES_TOTAL to
            progress.verificationFilesTotal,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFIED_BYTES to progress.verifiedBytes,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFICATION_BYTES_TOTAL to
            progress.verificationBytesTotal,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_CLEANUP_FILES_PROCESSED to
            progress.cleanupFilesProcessed,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_CLEANUP_FILES_TOTAL to
            progress.cleanupFilesTotal,
        ManagedDownloadMigrationWorker.KEY_PROGRESS_HAS_CURRENT_FILE to
            (progress.currentFileName != null),
        ManagedDownloadMigrationWorker.KEY_PROGRESS_CURRENT_FILE to
            (progress.currentFileName ?: "")
    )
}

internal fun migrationProgressFromWorkData(
    data: Data
): ManagedDownloadStorage.MigrationProgress? {
    val stage = data.getString(ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE)
        ?.let { name ->
            ManagedDownloadStorage.MigrationStage.entries.firstOrNull { stage ->
                stage.name == name
            }
        }
        ?: return null
    val totalFiles = data.getInt(
        ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_FILES,
        0
    ).coerceAtLeast(0)
    val processedFiles = data.getInt(
        ManagedDownloadMigrationWorker.KEY_PROGRESS_PROCESSED_FILES,
        0
    ).coerceAtLeast(0)
    val legacyStageProcessed = inferLegacyStageProcessed(data, stage, totalFiles)
    val legacyCopiedFiles = when (stage) {
        ManagedDownloadStorage.MigrationStage.PREPARING -> 0
        ManagedDownloadStorage.MigrationStage.COPYING -> processedFiles.coerceAtMost(totalFiles)
        ManagedDownloadStorage.MigrationStage.REWRITING_METADATA,
        ManagedDownloadStorage.MigrationStage.VERIFYING,
        ManagedDownloadStorage.MigrationStage.CLEANING_UP,
        ManagedDownloadStorage.MigrationStage.FINALIZING -> totalFiles
    }
    return ManagedDownloadStorage.MigrationProgress(
        stage = stage,
        totalFiles = totalFiles,
        processedFiles = processedFiles,
        copiedFiles = data.getInt(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_COPIED_FILES,
            legacyCopiedFiles
        ),
        copiedBytes = data.getLong(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_COPIED_BYTES,
            0L
        ),
        totalBytes = data.getLong(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_BYTES,
            0L
        ),
        metadataFilesProcessed = data.getInt(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_METADATA_FILES_PROCESSED,
            if (stage == ManagedDownloadStorage.MigrationStage.REWRITING_METADATA) {
                legacyStageProcessed
            } else {
                0
            }
        ),
        metadataFilesTotal = data.getInt(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_METADATA_FILES_TOTAL,
            if (stage == ManagedDownloadStorage.MigrationStage.REWRITING_METADATA) {
                totalFiles
            } else {
                0
            }
        ),
        cleanupFilesProcessed = data.getInt(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_CLEANUP_FILES_PROCESSED,
            if (stage == ManagedDownloadStorage.MigrationStage.CLEANING_UP) {
                legacyStageProcessed
            } else {
                0
            }
        ),
        cleanupFilesTotal = data.getInt(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_CLEANUP_FILES_TOTAL,
            if (stage == ManagedDownloadStorage.MigrationStage.CLEANING_UP) {
                totalFiles
            } else {
                0
            }
        ),
        currentFileName = if (
            data.keyValueMap.containsKey(
                ManagedDownloadMigrationWorker.KEY_PROGRESS_HAS_CURRENT_FILE
            )
        ) {
            data.getString(ManagedDownloadMigrationWorker.KEY_PROGRESS_CURRENT_FILE)
                .takeIf {
                    data.getBoolean(
                        ManagedDownloadMigrationWorker.KEY_PROGRESS_HAS_CURRENT_FILE,
                        false
                    )
                }
        } else {
            data.getString(ManagedDownloadMigrationWorker.KEY_PROGRESS_CURRENT_FILE)
                ?.takeIf(String::isNotBlank)
        },
        verificationFilesProcessed = data.getInt(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFICATION_FILES_PROCESSED,
            if (stage == ManagedDownloadStorage.MigrationStage.VERIFYING) {
                legacyStageProcessed
            } else {
                0
            }
        ),
        verificationFilesTotal = data.getInt(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFICATION_FILES_TOTAL,
            if (stage == ManagedDownloadStorage.MigrationStage.VERIFYING) {
                totalFiles
            } else {
                0
            }
        ),
        verifiedBytes = data.getLong(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFIED_BYTES,
            0L
        ).coerceAtLeast(0L),
        verificationBytesTotal = data.getLong(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_VERIFICATION_BYTES_TOTAL,
            0L
        ).coerceAtLeast(0L)
    )
}

private fun inferLegacyStageProcessed(
    data: Data,
    stage: ManagedDownloadStorage.MigrationStage,
    totalFiles: Int
): Int {
    if (totalFiles <= 0) return 0
    val fraction = data.getFloat(
        ManagedDownloadMigrationWorker.KEY_PROGRESS_FRACTION,
        0f
    ).coerceIn(0f, 1f)
    val stageFraction = when (stage) {
        ManagedDownloadStorage.MigrationStage.PREPARING -> 0f
        ManagedDownloadStorage.MigrationStage.COPYING -> (fraction - 0.02f) / 0.83f
        ManagedDownloadStorage.MigrationStage.REWRITING_METADATA -> (fraction - 0.85f) / 0.10f
        ManagedDownloadStorage.MigrationStage.VERIFYING -> (fraction - 0.92f) / 0.05f
        ManagedDownloadStorage.MigrationStage.CLEANING_UP -> (fraction - 0.95f) / 0.04f
        ManagedDownloadStorage.MigrationStage.FINALIZING -> 1f
    }.coerceIn(0f, 1f)
    return (stageFraction * totalFiles).roundToInt().coerceIn(0, totalFiles)
}

internal fun shouldRetryMigrationFailure(
    error: Throwable,
    runAttemptCount: Int,
    maxRetryAttempts: Int
): Boolean {
    if (runAttemptCount >= maxRetryAttempts) return false
    return when (error) {
        is ManagedDownloadMigrationException -> error.retryable
        is IOException -> true
        else -> false
    }
}

internal fun shouldRetryAfterMigrationFinalScan(
    outcome: ManagedLibraryRefreshOutcome
): Boolean = outcome !is ManagedLibraryRefreshOutcome.Published

class ManagedDownloadMigrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        coroutineScope {
            setForeground(createForegroundInfo())
            val progressJob = launch {
                var workProgressState = MigrationProgressThrottleState()
                var notificationProgressState = MigrationProgressThrottleState()
                ManagedDownloadStorage.migrationProgressFlow.collectLatest { progress ->
                    progress ?: return@collectLatest
                    val nowMs = System.currentTimeMillis()
                    if (
                        shouldPublishMigrationProgress(
                            progress = progress,
                            nowMs = nowMs,
                            state = workProgressState,
                            minIntervalMs = WORK_PROGRESS_MIN_INTERVAL_MS,
                            percentDelta = WORK_PROGRESS_PERCENT_DELTA
                        )
                    ) {
                        setProgress(migrationProgressToWorkData(progress))
                        workProgressState = updateMigrationProgressThrottleState(progress, nowMs)
                    }
                    if (
                        shouldPublishMigrationProgress(
                            progress = progress,
                            nowMs = nowMs,
                            state = notificationProgressState,
                            minIntervalMs = NOTIFICATION_MIN_INTERVAL_MS,
                            percentDelta = NOTIFICATION_PERCENT_DELTA
                        )
                    ) {
                        setForeground(createForegroundInfo(progress))
                        notificationProgressState =
                            updateMigrationProgressThrottleState(progress, nowMs)
                    }
                }
            }
            try {
                runMigration()
            } finally {
                progressJob.cancelAndJoin()
            }
        }
    }

    private suspend fun runMigration(): Result {
        val migrationWorkId = id.toString()
        val checkpointStore = ManagedDownloadMigrationCheckpointStore(applicationContext)
        var processingOperationId: String? = null
        var directoryMutationLease: AutoCloseable? = null
        try {
            ManagedLibraryProcessingCoordinator.restore(applicationContext)
            val operationId = ManagedLibraryProcessingCoordinator.tryBeginExclusive(
                context = applicationContext,
                reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
                phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
                resumeWaitingOperation = true
            ) ?: return Result.retry()
            processingOperationId = operationId
            directoryMutationLease =
                ManagedDownloadDirectoryMutationFence.closeAndDrain()
            val fromDirectoryUri = inputData.getString(KEY_FROM_DIRECTORY_URI)
            val toDirectoryUri = inputData.getString(KEY_TO_DIRECTORY_URI)
            val activeReplacementJournal = checkpointStore.readReplacementJournal()
            if (
                activeReplacementJournal != null &&
                (!ManagedDownloadStorage.areEquivalentDirectoryUris(
                    fromDirectoryUri,
                    activeReplacementJournal.fromDirectoryUri
                ) || !ManagedDownloadStorage.areEquivalentDirectoryUris(
                    toDirectoryUri,
                    activeReplacementJournal.toDirectoryUri
                ))
            ) {
                throw ManagedDownloadMigrationException.transient(
                    "已有迁移替换事务等待恢复"
                )
            }
            val targetLabel = inputData.getString(KEY_TARGET_LABEL)
            val releasePreviousPermission = inputData.getBoolean(KEY_RELEASE_PREVIOUS_PERMISSION, false)
            val checkpointWorkId = activeReplacementJournal?.workId
                ?.takeIf(String::isNotBlank)
                ?: migrationWorkId
            val minimumSourceEntryCount = maxOf(
                inputData.getInt(KEY_MINIMUM_SOURCE_ENTRY_COUNT, 0),
                checkpointStore.readMinimumAudioCount(migrationWorkId),
                if (checkpointWorkId == migrationWorkId) {
                    0
                } else {
                    checkpointStore.readMinimumAudioCount(checkpointWorkId)
                }
            ).coerceAtLeast(0)
            val persistedTargetNames = mergePersistedMigrationTargetNames(
                buildList {
                    add(checkpointStore.readTargetNames(migrationWorkId))
                    if (checkpointWorkId != migrationWorkId) {
                        add(checkpointStore.readTargetNames(checkpointWorkId))
                    }
                    activeReplacementJournal?.let { journal ->
                        add(persistedMigrationJournalTargetNames(journal))
                    }
                }
            )
            val settingsRepository = SettingsRepository(applicationContext)
            val targetPreviouslyCommitted = ManagedDownloadStorage.areEquivalentDirectoryUris(
                settingsRepository.downloadDirectoryUriFlow.first(),
                toDirectoryUri
            )
            val migrationResult = ManagedDownloadStorage.migrateManagedDownloads(
                context = applicationContext,
                fromDirectoryUri = fromDirectoryUri,
                toDirectoryUri = toDirectoryUri,
                minimumSourceEntryCount = minimumSourceEntryCount,
                targetPreviouslyCommitted = targetPreviouslyCommitted,
                persistedTargetNames = persistedTargetNames,
                onSourceAudioCountResolved = { resolvedMinimumAudioCount ->
                    checkpointStore.recordMinimumAudioCount(
                        workId = migrationWorkId,
                        minimumAudioCount = resolvedMinimumAudioCount
                    )
                },
                onTargetNamePlanResolved = { targetNames ->
                    checkpointStore.recordTargetNames(
                        workId = migrationWorkId,
                        targetNames = targetNames
                    )
                },
                onTargetVerified = {
                    settingsRepository.setDownloadDirectory(
                        uri = toDirectoryUri,
                        label = targetLabel
                    )
                    ManagedDownloadStorage.updateConfiguredTreeUri(toDirectoryUri)
                    ManagedDownloadStorage.updateCustomDirectoryLabel(targetLabel)
                },
                persistedReplacementJournal = activeReplacementJournal,
                replacementJournalWorkId = activeReplacementJournal?.workId
                    ?.takeIf(String::isNotBlank)
                    ?: migrationWorkId,
                onReplacementJournalUpdated = checkpointStore::recordReplacementJournal
            )
            if (!migrationResult.canSwitchDirectory) {
                ManagedLibraryProcessingCoordinator.waitingForRetry(
                    applicationContext,
                    operationId
                )
                if (migrationResult.hasOnlyRetryableCleanupFailures) {
                    return Result.retry()
                }
                return Result.failure(
                    workDataOf(
                        KEY_SKIPPED_FILES to migrationResult.skippedFiles,
                        KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles
                    )
                )
            }
            // 目录切换只有在最终扫描发布后才算完成, 否则 UI 可能短暂显示空列表
            val finalScanOutcome = GlobalDownloadManager.scanLocalFilesAwait(
                applicationContext,
                forceRefresh = true
            )
            if (shouldRetryAfterMigrationFinalScan(finalScanOutcome)) {
                ManagedLibraryProcessingCoordinator.waitingForRetry(
                    applicationContext,
                    operationId
                )
                return Result.retry()
            }
            when (migrationCleanupWorkDecision(migrationResult)) {
                MigrationCleanupWorkDecision.RETRY -> {
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        applicationContext,
                        operationId
                    )
                    return Result.retry()
                }
                MigrationCleanupWorkDecision.FAILURE -> {
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        applicationContext,
                        operationId
                    )
                    return Result.failure(
                        workDataOf(
                            KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles,
                            KEY_VERIFIED_MINIMUM_AUDIO_COUNT to
                                checkpointStore.readMinimumAudioCount(migrationWorkId)
                        )
                    )
                }
                MigrationCleanupWorkDecision.COMPLETE -> Unit
            }
            val verifiedMinimumAudioCount = checkpointStore.readMinimumAudioCount(migrationWorkId)
            ManagedLibraryProcessingCoordinator.complete(
                applicationContext,
                operationId
            )
            if (releasePreviousPermission && migrationResult.canReleasePreviousPermission) {
                ManagedDownloadStorage.releasePersistedDirectoryPermission(
                    applicationContext,
                    fromDirectoryUri
                )
            }
            checkpointStore.clear(migrationWorkId)
            if (checkpointWorkId != migrationWorkId) {
                checkpointStore.clear(checkpointWorkId)
            }
            checkpointStore.clearReplacementJournal()
            return Result.success(
                workDataOf(
                    KEY_MOVED_FILES to migrationResult.movedFiles,
                    KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles,
                    KEY_VERIFIED_MINIMUM_AUDIO_COUNT to verifiedMinimumAudioCount
                )
            )
        } catch (error: CancellationException) {
            processingOperationId?.let { operationId ->
                try {
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        applicationContext,
                        operationId
                    )
                } catch (stateError: Exception) {
                    error.addSuppressed(stateError)
                }
            }
            throw error
        } catch (error: Exception) {
            processingOperationId?.let { operationId ->
                try {
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        applicationContext,
                        operationId
                    )
                } catch (stateError: Exception) {
                    error.addSuppressed(stateError)
                }
            }
            return if (
                shouldRetryMigrationFailure(
                    error = error,
                    runAttemptCount = runAttemptCount,
                    maxRetryAttempts = MAX_RETRY_ATTEMPTS
                )
            ) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (error.message ?: "")))
            }
        } finally {
            directoryMutationLease?.let { lease ->
                lease.close()
                GlobalDownloadManager.recoverPendingDownloadsAfterStorageMutation(
                    applicationContext
                )
            }
        }
    }

    private fun createForegroundInfo(
        progress: ManagedDownloadStorage.MigrationProgress? = null
    ): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.settings_download_directory_migrating),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val progressPercent = progress?.fraction
            ?.coerceIn(0f, 1f)
            ?.times(100f)
            ?.toInt()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(applicationContext.getString(R.string.settings_download_directory_migrating))
            .setContentText(
                progress?.currentFileName?.takeIf(String::isNotBlank)
                    ?: applicationContext.getString(
                        R.string.settings_download_directory_migrating_desc
                    )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                100,
                progressPercent ?: 0,
                progressPercent == null
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "managed_download_migration"
        const val KEY_FROM_DIRECTORY_URI = "from_directory_uri"
        const val KEY_TO_DIRECTORY_URI = "to_directory_uri"
        const val KEY_TARGET_LABEL = "target_label"
        const val KEY_RELEASE_PREVIOUS_PERMISSION = "release_previous_permission"
        const val KEY_MINIMUM_SOURCE_ENTRY_COUNT = "minimum_source_entry_count"
        const val KEY_MOVED_FILES = "moved_files"
        const val KEY_SKIPPED_FILES = "skipped_files"
        const val KEY_CLEANUP_FAILED_FILES = "cleanup_failed_files"
        const val KEY_VERIFIED_MINIMUM_AUDIO_COUNT = "verified_minimum_audio_count"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        const val KEY_PROGRESS_PROCESSED_FILES = "progress_processed_files"
        const val KEY_PROGRESS_TOTAL_FILES = "progress_total_files"
        const val KEY_PROGRESS_COPIED_FILES = "progress_copied_files"
        const val KEY_PROGRESS_COPIED_BYTES = "progress_copied_bytes"
        const val KEY_PROGRESS_TOTAL_BYTES = "progress_total_bytes"
        const val KEY_PROGRESS_METADATA_FILES_PROCESSED =
            "progress_metadata_files_processed"
        const val KEY_PROGRESS_METADATA_FILES_TOTAL = "progress_metadata_files_total"
        const val KEY_PROGRESS_VERIFICATION_FILES_PROCESSED =
            "progress_verification_files_processed"
        const val KEY_PROGRESS_VERIFICATION_FILES_TOTAL =
            "progress_verification_files_total"
        const val KEY_PROGRESS_VERIFIED_BYTES = "progress_verified_bytes"
        const val KEY_PROGRESS_VERIFICATION_BYTES_TOTAL =
            "progress_verification_bytes_total"
        const val KEY_PROGRESS_CLEANUP_FILES_PROCESSED =
            "progress_cleanup_files_processed"
        const val KEY_PROGRESS_CLEANUP_FILES_TOTAL = "progress_cleanup_files_total"
        const val KEY_PROGRESS_HAS_CURRENT_FILE = "progress_has_current_file"
        const val KEY_PROGRESS_CURRENT_FILE = "progress_current_file"

        private const val NOTIFICATION_CHANNEL_ID = "managed_download_migration"
        private const val NOTIFICATION_ID = 1004
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val WORK_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val WORK_PROGRESS_PERCENT_DELTA = 1
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_PERCENT_DELTA = 1

        suspend fun enqueueOrGetActiveWorkId(
            context: Context,
            fromDirectoryUri: String?,
            toDirectoryUri: String?,
            targetLabel: String,
            releasePreviousPermission: Boolean,
            minimumSourceEntryCount: Int = GlobalDownloadManager.downloadedSongs.value.size
        ): String = withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                .firstOrNull { info -> !info.state.isFinished }
                ?.id
                ?.toString()
                ?.let { return@withContext it }
            val request = OneTimeWorkRequestBuilder<ManagedDownloadMigrationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_FROM_DIRECTORY_URI to fromDirectoryUri,
                        KEY_TO_DIRECTORY_URI to toDirectoryUri,
                        KEY_TARGET_LABEL to targetLabel,
                        KEY_RELEASE_PREVIOUS_PERMISSION to releasePreviousPermission,
                        KEY_MINIMUM_SOURCE_ENTRY_COUNT to minimumSourceEntryCount.coerceAtLeast(0)
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    10L,
                    TimeUnit.SECONDS
                )
                .setInitialDelay(0L, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            ).result.get()
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                .firstOrNull { info -> !info.state.isFinished }
                ?.id
                ?.toString()
                ?: request.id.toString()
        }
    }
}
