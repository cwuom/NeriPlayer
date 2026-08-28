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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.logging.NPLogger
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

internal fun shouldRetryMigrationAttempt(
    runAttemptCount: Int,
    maxRetryAttempts: Int
): Boolean {
    return maxRetryAttempts > 0 && runAttemptCount in 0 until maxRetryAttempts
}

internal fun shouldRetryAfterMigrationFinalScan(
    outcome: ManagedLibraryRefreshOutcome
): Boolean = outcome !is ManagedLibraryRefreshOutcome.Published

internal fun mergeMigrationRequestForWorker(
    persisted: ManagedMigrationRequest?,
    input: ManagedMigrationRequest,
    inputKeys: Set<String>
): ManagedMigrationRequest {
    persisted ?: return input.normalized()

    fun hasInput(key: String): Boolean = key in inputKeys
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_FROM_DIRECTORY_URI) &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(
                persisted.fromDirectoryUri,
                input.fromDirectoryUri
            )
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与任务源目录不一致"
        )
    }
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_TO_DIRECTORY_URI) &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(
                persisted.toDirectoryUri,
                input.toDirectoryUri
            )
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与任务目标目录不一致"
        )
    }
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_TARGET_LABEL) &&
            persisted.targetLabel.isNotBlank() &&
            persisted.targetLabel != input.targetLabel
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与任务标签不一致"
        )
    }
    if (
        hasInput(ManagedDownloadMigrationWorker.KEY_RELEASE_PREVIOUS_PERMISSION) &&
            persisted.releasePreviousPermission != input.releasePreviousPermission
    ) {
        throw ManagedDownloadMigrationException.transient(
            "持久迁移请求与权限策略不一致"
        )
    }
    val checkpointWorkId = if (
        hasInput(ManagedDownloadMigrationWorker.KEY_CHECKPOINT_WORK_ID)
    ) {
        input.checkpointWorkId ?: persisted.checkpointWorkId
    } else {
        persisted.checkpointWorkId
    }
    return persisted.copy(
        workId = input.workId,
        fromDirectoryUri = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_FROM_DIRECTORY_URI)
        ) {
            input.fromDirectoryUri
        } else {
            persisted.fromDirectoryUri
        },
        toDirectoryUri = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_TO_DIRECTORY_URI)
        ) {
            input.toDirectoryUri
        } else {
            persisted.toDirectoryUri
        },
        targetLabel = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_TARGET_LABEL)
        ) {
            input.targetLabel
        } else {
            persisted.targetLabel
        },
        releasePreviousPermission = if (
            hasInput(ManagedDownloadMigrationWorker.KEY_RELEASE_PREVIOUS_PERMISSION)
        ) {
            input.releasePreviousPermission
        } else {
            persisted.releasePreviousPermission
        },
        minimumSourceEntryCount = maxOf(
            persisted.minimumSourceEntryCount,
            input.minimumSourceEntryCount
        ),
        checkpointWorkId = checkpointWorkId,
        autoResume = true
    ).normalized()
}

/**
 * 只有 durable request 已经属于活动 work 时才能认为两者已绑定
 * WorkInfo 不公开 inputData, 未知的 work 不能静默改写迁移目标
 */
internal fun shouldBindMigrationRequestToActiveWork(
    persisted: ManagedMigrationRequest?,
    fallback: ManagedMigrationRequest?,
    activeWorkId: String
): Boolean {
    val normalizedActiveId = activeWorkId.trim()
    if (normalizedActiveId.isBlank()) return false
    return when {
        persisted != null -> persisted.workId == normalizedActiveId
        else -> fallback?.workId == normalizedActiveId
    }
}

internal fun shouldBlockStartupForMigrationRecovery(
    request: ManagedMigrationRequest?,
    journal: ManagedMigrationReplacementJournal?
): Boolean {
    return request?.autoResume == true ||
        (journal != null &&
            journal.phase != ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED)
}

class ManagedDownloadMigrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val checkpointStore = ManagedDownloadMigrationCheckpointStore(applicationContext)
        val migrationWorkId = id.toString()
        val persistedProgress = checkpointStore.readProgress(migrationWorkId)
        coroutineScope {
            setForeground(createForegroundInfo())
            persistedProgress?.let { progress ->
                setProgress(migrationProgressToWorkData(progress))
                setForeground(createForegroundInfo(progress))
            }
            val progressJob = launch {
                var workProgressState = MigrationProgressThrottleState()
                var notificationProgressState = MigrationProgressThrottleState()
                var durableProgress = persistedProgress
                ManagedDownloadStorage.migrationProgressFlow.collectLatest { progress ->
                    progress ?: return@collectLatest
                    val visibleProgress = mergeMigrationProgressFloor(
                        floor = durableProgress,
                        current = progress
                    )
                    val nowMs = System.currentTimeMillis()
                    if (
                        shouldPublishMigrationProgress(
                            progress = visibleProgress,
                            nowMs = nowMs,
                            state = workProgressState,
                            minIntervalMs = WORK_PROGRESS_MIN_INTERVAL_MS,
                            percentDelta = WORK_PROGRESS_PERCENT_DELTA
                        )
                    ) {
                        durableProgress = checkpointStore.recordProgress(
                            migrationWorkId,
                            visibleProgress
                        )
                        setProgress(migrationProgressToWorkData(durableProgress))
                        workProgressState = updateMigrationProgressThrottleState(
                            durableProgress,
                            nowMs
                        )
                    }
                    val processingState = ManagedLibraryProcessingCoordinator.state.value
                    val operationId = processingState.operationId
                    if (
                        operationId != null &&
                        processingState.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE
                    ) {
                        ManagedLibraryProcessingCoordinator.updateProgress(
                            operationId = operationId,
                            processed = visibleProgress.processedFiles,
                            total = visibleProgress.totalFiles,
                            currentItem = visibleProgress.currentFileName,
                            context = applicationContext
                        )
                    }
                    if (
                        shouldPublishMigrationProgress(
                            progress = visibleProgress,
                            nowMs = nowMs,
                            state = notificationProgressState,
                            minIntervalMs = NOTIFICATION_MIN_INTERVAL_MS,
                            percentDelta = NOTIFICATION_PERCENT_DELTA
                        )
                    ) {
                        setForeground(createForegroundInfo(visibleProgress))
                        notificationProgressState =
                            updateMigrationProgressThrottleState(visibleProgress, nowMs)
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
            // write the complete input before opening either root. This closes the
            // crash window between WorkManager dispatch and the first checkpoint
            val inputRequest = ManagedMigrationRequest(
                workId = migrationWorkId,
                fromDirectoryUri = inputData.getString(KEY_FROM_DIRECTORY_URI),
                toDirectoryUri = inputData.getString(KEY_TO_DIRECTORY_URI),
                targetLabel = inputData.getString(KEY_TARGET_LABEL).orEmpty(),
                releasePreviousPermission = inputData.getBoolean(
                    KEY_RELEASE_PREVIOUS_PERMISSION,
                    false
                ),
                minimumSourceEntryCount = inputData.getInt(
                    KEY_MINIMUM_SOURCE_ENTRY_COUNT,
                    0
                ),
                checkpointWorkId = inputData.getString(KEY_CHECKPOINT_WORK_ID)
            )
            val effectiveRequest = mergeMigrationRequestForWorker(
                persisted = checkpointStore.readRequest(),
                input = inputRequest,
                inputKeys = inputData.keyValueMap.keys
            )
            checkpointStore.recordRequest(effectiveRequest)
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
            val fromDirectoryUri = effectiveRequest.fromDirectoryUri
            val toDirectoryUri = effectiveRequest.toDirectoryUri
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
            val targetLabel = effectiveRequest.targetLabel
            val releasePreviousPermission = effectiveRequest.releasePreviousPermission
            val checkpointWorkId = activeReplacementJournal?.workId
                ?.takeIf(String::isNotBlank)
                ?: effectiveRequest.checkpointWorkId
                ?: migrationWorkId
            val minimumSourceEntryCount = maxOf(
                effectiveRequest.minimumSourceEntryCount,
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
            val persistedProgress = checkpointStore.readProgress(migrationWorkId)
                ?: if (checkpointWorkId != migrationWorkId) {
                    checkpointStore.readProgress(checkpointWorkId)
                } else {
                    null
                }
            persistedProgress?.let { progress ->
                // keep the last durable stage visible while the replacement worker
                // reopens the provider and rebuilds its in-memory tracker
                setProgress(migrationProgressToWorkData(progress))
                setForeground(createForegroundInfo(progress))
                ManagedLibraryProcessingCoordinator.updateProgress(
                    operationId = operationId,
                    processed = progress.processedFiles,
                    total = progress.totalFiles,
                    currentItem = progress.currentFileName,
                    context = applicationContext
                )
            }
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
                onReplacementJournalUpdated = checkpointStore::recordReplacementJournal,
                persistedProgress = persistedProgress
            )
            if (!migrationResult.canSwitchDirectory) {
                val retryCleanup = migrationResult.hasOnlyRetryableCleanupFailures &&
                    shouldRetryMigrationAttempt(
                        runAttemptCount = runAttemptCount,
                        maxRetryAttempts = MAX_RETRY_ATTEMPTS
                    )
                if (retryCleanup) {
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = true
                    )
                    return Result.retry()
                }
                // A terminal cleanup result keeps the journal/checkpoint for an
                // explicit retry, but must release the UI operation fence.
                transitionProcessingState(
                    operationId = operationId,
                    waitForRetry = false
                )
                checkpointStore.markRequestTerminal(migrationWorkId)
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
                val retryFinalScan = shouldRetryMigrationAttempt(
                    runAttemptCount = runAttemptCount,
                    maxRetryAttempts = MAX_RETRY_ATTEMPTS
                )
                transitionProcessingState(
                    operationId = operationId,
                    waitForRetry = retryFinalScan
                )
                if (retryFinalScan) {
                    return Result.retry()
                }
                checkpointStore.markRequestTerminal(migrationWorkId)
                return Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to "迁移后下载目录扫描未发布")
                )
            }
            when (migrationCleanupWorkDecision(migrationResult)) {
                MigrationCleanupWorkDecision.RETRY -> {
                    val retryCleanup = shouldRetryMigrationAttempt(
                        runAttemptCount = runAttemptCount,
                        maxRetryAttempts = MAX_RETRY_ATTEMPTS
                    )
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = retryCleanup
                    )
                    if (retryCleanup) {
                        return Result.retry()
                    }
                    checkpointStore.markRequestTerminal(migrationWorkId)
                    return Result.failure(
                        workDataOf(
                            KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles,
                            KEY_VERIFIED_MINIMUM_AUDIO_COUNT to
                                checkpointStore.readMinimumAudioCount(migrationWorkId)
                        )
                    )
                }
                MigrationCleanupWorkDecision.FAILURE -> {
                    // Keep recovery data on disk while making a terminal result
                    // non-blocking for the next directory operation.
                    transitionProcessingState(operationId = operationId, waitForRetry = false)
                    checkpointStore.markRequestTerminal(migrationWorkId)
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
            check(
                checkpointStore.clearCompleted(
                    listOf(migrationWorkId, checkpointWorkId)
                )
            ) { "无法清理已完成的迁移凭据" }
            return Result.success(
                workDataOf(
                    KEY_MOVED_FILES to migrationResult.movedFiles,
                    KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles,
                    KEY_VERIFIED_MINIMUM_AUDIO_COUNT to verifiedMinimumAudioCount
                )
            )
        } catch (error: CancellationException) {
            processingOperationId?.let { operationId ->
                withContext(NonCancellable) {
                    // WorkManager cancellation can interrupt a provider call;
                    // keep the durable request and expose a resumable waiting state
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = true,
                        cause = error
                    )
                }
            }
            throw error
        } catch (error: Exception) {
            val retry = shouldRetryMigrationFailure(
                error = error,
                runAttemptCount = runAttemptCount,
                maxRetryAttempts = MAX_RETRY_ATTEMPTS
            )
            processingOperationId?.let { operationId ->
                transitionProcessingState(
                    operationId = operationId,
                    waitForRetry = retry,
                    cause = error
                )
            }
            if (!retry) {
                checkpointStore.markRequestTerminal(migrationWorkId)
            }
            return if (retry) {
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

    private suspend fun transitionProcessingState(
        operationId: String,
        waitForRetry: Boolean,
        cause: Throwable? = null
    ) {
        try {
            if (waitForRetry) {
                ManagedLibraryProcessingCoordinator.waitingForRetry(
                    applicationContext,
                    operationId
                )
            } else {
                ManagedLibraryProcessingCoordinator.complete(
                    applicationContext,
                    operationId
                )
            }
        } catch (stateError: Exception) {
            if (cause != null) {
                cause.addSuppressed(stateError)
            } else {
                NPLogger.w(
                    TAG,
                    "迁移处理状态更新失败: operationId=$operationId, " +
                        "waitForRetry=$waitForRetry, ${stateError.message}"
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
        const val KEY_CHECKPOINT_WORK_ID = "checkpoint_work_id"
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
        private const val TAG = "ManagedDownloadMigrationWorker"
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val WORK_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val WORK_PROGRESS_PERCENT_DELTA = 1
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_PERCENT_DELTA = 1
        private val enqueueMutex = Mutex()

        suspend fun enqueueOrGetActiveWorkId(
            context: Context,
            fromDirectoryUri: String?,
            toDirectoryUri: String?,
            targetLabel: String,
            releasePreviousPermission: Boolean,
            minimumSourceEntryCount: Int = GlobalDownloadManager.downloadedSongs.value.size
        ): String = withContext(Dispatchers.IO) {
            enqueueMutex.withLock {
                val appContext = context.applicationContext
                val checkpointStore = ManagedDownloadMigrationCheckpointStore(appContext)
                val requested = ManagedMigrationRequest(
                    workId = UUID.randomUUID().toString(),
                    fromDirectoryUri = fromDirectoryUri,
                    toDirectoryUri = toDirectoryUri,
                    targetLabel = targetLabel,
                    releasePreviousPermission = releasePreviousPermission,
                    minimumSourceEntryCount = minimumSourceEntryCount
                )
                val persisted = checkpointStore.readRequest()
                val durableRequest = if (persisted?.autoResume == true) {
                    persisted.copy(
                        minimumSourceEntryCount = maxOf(
                            persisted.minimumSourceEntryCount,
                            requested.minimumSourceEntryCount
                        )
                    )
                } else {
                    requested
                }
                val workManager = WorkManager.getInstance(appContext)
                val active = activeMigrationWorkInfo(workManager)
                if (active != null) {
                    ensureRequestForActiveWork(
                        checkpointStore = checkpointStore,
                        activeWork = active,
                        fallback = (persisted ?: durableRequest).copy(autoResume = true)
                    )
                    return@withLock active.id.toString()
                }
                // commit the intent immediately before enqueue. If enqueue fails,
                // startup can retry it without opening either storage root
                checkpointStore.recordRequest(durableRequest)
                enqueueDurableRequestLocked(
                    workManager = workManager,
                    checkpointStore = checkpointStore,
                    request = durableRequest
                )
            }
        }

        /**
         * requeues a request left by a killed process without touching the roots
         * on the startup coroutine. The worker performs all provider I/O.
         */
        suspend fun resumePersistedRequestIfNeeded(context: Context): String? =
            withContext(Dispatchers.IO) {
                enqueueMutex.withLock {
                    val appContext = context.applicationContext
                    val checkpointStore = ManagedDownloadMigrationCheckpointStore(appContext)
                    val workManager = WorkManager.getInstance(appContext)
                    val active = activeMigrationWorkInfo(workManager)
                    if (active != null) {
                        ensureRequestForActiveWork(
                            checkpointStore = checkpointStore,
                            activeWork = active,
                            fallback = checkpointStore.readRequest()
                                ?.copy(autoResume = true)
                        )
                        return@withLock active.id.toString()
                    }
                    val persisted = checkpointStore.readRequest()
                    val request = when {
                        persisted?.autoResume == true -> persisted
                        persisted != null -> return@withLock null
                        else -> checkpointStore.readReplacementJournal()
                            ?.takeIf { journal ->
                                journal.fromDirectoryUri != null ||
                                    journal.toDirectoryUri != null
                            }
                            ?.let { journal ->
                                ManagedMigrationRequest(
                                    workId = journal.workId,
                                    fromDirectoryUri = journal.fromDirectoryUri,
                                    toDirectoryUri = journal.toDirectoryUri,
                                    targetLabel = "",
                                    releasePreviousPermission = false,
                                    minimumSourceEntryCount = checkpointStore
                                        .readMinimumAudioCount(journal.workId),
                                    checkpointWorkId = journal.workId
                                )
                            }
                    } ?: return@withLock null
                    val resumed = request.copy(
                        workId = UUID.randomUUID().toString(),
                        checkpointWorkId = request.checkpointWorkId
                            ?: request.workId
                    )
                    checkpointStore.recordRequest(resumed)
                    enqueueDurableRequestLocked(
                        workManager = workManager,
                        checkpointStore = checkpointStore,
                        request = resumed
                    )
                }
            }

        /**
         * reports whether startup must keep a restored directory operation alive
         * when WorkManager inspection itself is temporarily unavailable
         */
        suspend fun hasPersistedMigrationRecovery(context: Context): Boolean =
            withContext(Dispatchers.IO) {
                val store = ManagedDownloadMigrationCheckpointStore(context.applicationContext)
                val request = store.readRequest()
                val journal = store.readReplacementJournal()
                shouldBlockStartupForMigrationRecovery(request, journal)
            }

        private fun enqueueDurableRequestLocked(
            workManager: WorkManager,
            checkpointStore: ManagedDownloadMigrationCheckpointStore,
            request: ManagedMigrationRequest
        ): String {
            val workRequest = OneTimeWorkRequestBuilder<ManagedDownloadMigrationWorker>()
                .apply {
                    request.workId.toUuidOrNull()?.let { id -> setId(id) }
                }
                .setInputData(
                    workDataOf(
                        KEY_FROM_DIRECTORY_URI to request.fromDirectoryUri,
                        KEY_TO_DIRECTORY_URI to request.toDirectoryUri,
                        KEY_TARGET_LABEL to request.targetLabel,
                        KEY_RELEASE_PREVIOUS_PERMISSION to
                            request.releasePreviousPermission,
                        KEY_MINIMUM_SOURCE_ENTRY_COUNT to
                            request.minimumSourceEntryCount.coerceAtLeast(0),
                        KEY_CHECKPOINT_WORK_ID to request.checkpointWorkId
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
                workRequest
            ).result.get()
            val activeAfter = activeMigrationWorkInfo(workManager)
            if (activeAfter != null && activeAfter.id.toString() != request.workId) {
                ensureRequestForActiveWork(
                    checkpointStore = checkpointStore,
                    activeWork = activeAfter,
                    fallback = request
                )
            }
            return activeAfter?.id?.toString() ?: workRequest.id.toString()
        }

        private fun activeMigrationWorkInfo(workManager: WorkManager) =
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                .firstOrNull { info -> !info.state.isFinished }

        private fun ensureRequestForActiveWork(
            checkpointStore: ManagedDownloadMigrationCheckpointStore,
            activeWork: androidx.work.WorkInfo,
            fallback: ManagedMigrationRequest?
        ) {
            val activeWorkId = activeWork.id.toString()
            val persisted = checkpointStore.readRequest()
            when {
                shouldBindMigrationRequestToActiveWork(
                    persisted = persisted,
                    fallback = fallback,
                    activeWorkId = activeWorkId
                ) && persisted != null -> Unit
                persisted != null -> {
                    // WorkInfo 不公开 inputData，无法证明一个旧的 active work
                    // 与当前请求属于同一目录。保留 durable request，交给 worker
                    // 启动时的字段校验处理，禁止静默改绑到错误目标
                    NPLogger.w(
                        TAG,
                        "活动迁移任务与持久请求标识不同，保留原请求等待 worker 校验: " +
                            "active=$activeWorkId, persisted=${persisted.workId}"
                    )
                }
                shouldBindMigrationRequestToActiveWork(
                    persisted = null,
                    fallback = fallback,
                    activeWorkId = activeWorkId
                ) -> {
                    fallback?.let(checkpointStore::recordRequest)
                }
            }
        }

        private fun String.toUuidOrNull(): UUID? = runCatching {
            UUID.fromString(this)
        }.getOrNull()
    }
}
