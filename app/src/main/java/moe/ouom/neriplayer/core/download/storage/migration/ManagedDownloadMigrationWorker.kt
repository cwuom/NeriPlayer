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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.download.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.storage.MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE
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

/** pending 凭据是目录迁移的硬阻塞条件，不能因为 WorkManager 预算耗尽而丢入口 */
internal fun shouldRetainMigrationPendingRetry(error: Throwable): Boolean {
    return generateSequence(error) { it.cause }
        .filterIsInstance<ManagedDownloadMigrationException>()
        .any { candidate ->
            candidate.retryable &&
                candidate.message?.contains(
                    MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE
                ) == true
        }
}

internal fun shouldRetryMigrationAttempt(
    runAttemptCount: Int,
    maxRetryAttempts: Int
): Boolean {
    return maxRetryAttempts > 0 && runAttemptCount in 0 until maxRetryAttempts
}

internal fun shouldRearmMigrationWorkOnStartup(
    state: androidx.work.WorkInfo.State,
    runAttemptCount: Int,
    retryAttemptOffset: Int,
    maxRetryAttempts: Int,
    forceRecovery: Boolean = false
): Boolean {
    // 进程被杀后重试中的 WorkManager 行可能带着很长退避，新请求可以直接复用持久迁移日志
    // 历史尝试次数仍保存在持久请求中，替换请求不能借机重置终态重试上限
    if (state != androidx.work.WorkInfo.State.ENQUEUED) return false
    if (forceRecovery) return true
    return runAttemptCount > 0 &&
        migrationRetryAttemptCount(retryAttemptOffset, runAttemptCount) < maxRetryAttempts
}

internal fun migrationRetryAttemptCount(
    retryAttemptOffset: Int,
    runAttemptCount: Int
): Int {
    return (retryAttemptOffset.coerceAtLeast(0).toLong() +
        runAttemptCount.coerceAtLeast(0).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun shouldAbortSupersededMigrationWorker(
    persisted: ManagedMigrationRequest?,
    currentWorkId: String
): Boolean {
    val persistedWorkId = persisted?.workId?.trim()?.takeIf(String::isNotBlank)
        ?: return false
    val activeWorkId = currentWorkId.trim().takeIf(String::isNotBlank)
        ?: return false
    val persistedUuid = runCatching { UUID.fromString(persistedWorkId) }.getOrNull()
    val activeUuid = runCatching { UUID.fromString(activeWorkId) }.getOrNull()
    if (
        persistedWorkId == activeWorkId ||
        persistedUuid != null && persistedUuid == activeUuid
    ) {
        return false
    }
    val checkpointWorkId = persisted.checkpointWorkId?.trim()
        ?.takeIf(String::isNotBlank)
    val checkpointUuid = checkpointWorkId?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
    }
    if (
        checkpointWorkId != null &&
        (checkpointWorkId == activeWorkId ||
            checkpointUuid != null && checkpointUuid == activeUuid)
    ) {
        return true
    }
    // 正常入队会把请求 UUID 用作 WorkManager ID，两个 UUID 不一致就说明旧 Worker 已被替换
    return persistedUuid != null && activeUuid != null
}

internal fun shouldRetryAfterMigrationFinalScan(
    outcome: ManagedLibraryRefreshOutcome
): Boolean = outcome !is ManagedLibraryRefreshOutcome.Published

internal fun migrationProgressCheckpointIds(
    currentWorkId: String,
    inputCheckpointWorkId: String?,
    persistedRequest: ManagedMigrationRequest?,
    persistedJournal: ManagedMigrationReplacementJournal?
): List<String> {
    return sequenceOf(
        currentWorkId,
        inputCheckpointWorkId,
        persistedRequest?.checkpointWorkId,
        persistedRequest?.workId,
        persistedJournal?.workId
    ).mapNotNull { value ->
        value?.trim()?.takeIf(String::isNotBlank)
    }.distinct().toList()
}

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
        persisted != null -> migrationWorkIdsEqual(persisted.workId, normalizedActiveId)
        else -> migrationWorkIdsEqual(fallback?.workId, normalizedActiveId)
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
        val persistedRequest = runCatching { checkpointStore.readRequest() }.getOrNull()
        val persistedJournal = runCatching { checkpointStore.readReplacementJournal() }.getOrNull()
        val persistedProgress = selectMigrationProgressCheckpoint(
            checkpointIds = migrationProgressCheckpointIds(
                currentWorkId = migrationWorkId,
                inputCheckpointWorkId = inputData.getString(KEY_CHECKPOINT_WORK_ID),
                persistedRequest = persistedRequest,
                persistedJournal = persistedJournal
            ),
            readProgress = checkpointStore::readProgress
        )
        val latestRequest = runCatching { checkpointStore.readRequest() }.getOrNull()
        if (
            latestRequest != null &&
            shouldAbortSupersededMigrationWorker(latestRequest, migrationWorkId)
        ) {
            NPLogger.i(
                TAG,
                "迁移 Worker 在订阅进度前已被新请求替换，跳过旧任务: " +
                    "workId=$migrationWorkId"
            )
            return@withContext Result.success()
        }
        if (!ManagedDownloadStorage.beginMigrationProgressSession(
            ownerWorkId = migrationWorkId,
            persistedProgress = persistedProgress
        )) {
            NPLogger.i(
                TAG,
                "迁移进度仍由旧 Worker 持有，等待其释放后重试: " +
                    "workId=$migrationWorkId"
            )
            return@withContext Result.retry()
        }
        coroutineScope {
            setForeground(createForegroundInfo(persistedProgress))
            persistedProgress?.let { progress ->
                setProgress(migrationProgressToWorkData(progress))
            }
            val progressJob = launch {
                var workProgressState = MigrationProgressThrottleState()
                var notificationProgressState = MigrationProgressThrottleState()
                var durableProgress = persistedProgress
                ManagedDownloadStorage.migrationProgressFlow.collect { progress ->
                    progress ?: return@collect
                    if (!checkpointStore.isRequestCurrent(migrationWorkId)) {
                        return@collect
                    }
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
                        durableProgress = checkpointStore.recordProgressIfCurrent(
                            ownerWorkId = migrationWorkId,
                            workId = migrationWorkId,
                            progress = visibleProgress
                        ) ?: return@collect
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
                        val sharedProgress = migrationProgressForSharedProcessing(visibleProgress)
                        ManagedLibraryProcessingCoordinator.updateProgress(
                            operationId = operationId,
                            processed = sharedProgress.processed,
                            total = sharedProgress.total,
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
                ManagedDownloadStorage.endMigrationProgressSession(migrationWorkId)
            }
        }
    }

    private suspend fun runMigration(): Result {
        val migrationWorkId = id.toString()
        val checkpointStore = ManagedDownloadMigrationCheckpointStore(applicationContext)
        val receiptBatcher = ManagedDownloadMigrationCopyReceiptBatcher { receipts ->
            checkpointStore.recordCopyReceiptsIfCurrent(
                ownerWorkId = migrationWorkId,
                workId = migrationWorkId,
                receipts = receipts
            )
        }
        var processingOperationId: String? = null
        var directoryMutationLease: AutoCloseable? = null
        var sourceDirectoryUriForRecovery: String? = null
        var logicalRetryAttemptCount = migrationRetryAttemptCount(
            retryAttemptOffset = 0,
            runAttemptCount = runAttemptCount
        )
        var pendingArtifactPreflightBlocked = false
        try {
                // 先完整写入输入，再打开源和目标目录，堵住 Worker 分发到首个检查点之间的崩溃窗口
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
            val persistedRequestAtStart = checkpointStore.readRequest()
            if (shouldAbortSupersededMigrationWorker(persistedRequestAtStart, migrationWorkId)) {
                NPLogger.i(
                    TAG,
                    "迁移 Worker 已被新请求替换，跳过旧任务收尾: workId=$migrationWorkId"
                )
                return Result.success()
            }
            val effectiveRequest = mergeMigrationRequestForWorker(
                persisted = persistedRequestAtStart,
                input = inputRequest,
                inputKeys = inputData.keyValueMap.keys
            )
            sourceDirectoryUriForRecovery = effectiveRequest.fromDirectoryUri
            logicalRetryAttemptCount = migrationRetryAttemptCount(
                retryAttemptOffset = effectiveRequest.retryAttemptOffset,
                runAttemptCount = runAttemptCount
            )
            if (
                !checkpointStore.recordRequestIfCurrent(
                    expectedWorkId = persistedRequestAtStart?.workId,
                    request = effectiveRequest
                )
            ) {
                NPLogger.i(
                    TAG,
                    "迁移请求已在 Worker 启动期间被替换，跳过旧任务: workId=$migrationWorkId"
                )
                return Result.success()
            }
            if (PersistentDownloadClearFenceStore.isActive(applicationContext)) {
                NPLogger.i(
                    TAG,
                    "下载清空围栏生效，迁移 Worker 延后，避免目录复制与清理并发: " +
                        "workId=$migrationWorkId"
                )
                return Result.retry()
            }
            // 从 pending 预检开始就持有目录租约，避免预检与实际迁移之间又产生新文件
            directoryMutationLease = ManagedDownloadDirectoryMutationFence.closeAndDrain()
            // 旧进程可能在 Provider 调用中被杀，恢复后把同一迁移重新放入等待态
            val restoredProcessingState = ManagedLibraryProcessingCoordinator.restore(
                applicationContext
            )
            if (
                restoredProcessingState is ManagedLibraryProcessingState.Running &&
                    restoredProcessingState.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE
            ) {
                ManagedLibraryProcessingCoordinator.waitingForRetry(
                    context = applicationContext,
                    operationId = restoredProcessingState.operationId
                )
            }
            val preflightOperationId =
                ManagedLibraryProcessingCoordinator.ensureWaitingForRetry(
                    context = applicationContext,
                    reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE
                )
            processingOperationId = preflightOperationId
            val pendingRecovery =
                GlobalDownloadManager.reconcilePendingDownloadsBeforeMigrationDetailed(
                    context = applicationContext,
                    sourceDirectoryUri = effectiveRequest.fromDirectoryUri,
                    directoryMutationLeaseOwned = true
                )
            if (!pendingRecovery.isConverged) {
                throw ManagedDownloadMigrationException.transient(
                    "[$MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE] " +
                        "迁移前 pending 尚未收敛: " +
                        "remaining=${pendingRecovery.remainingArtifactCount}, " +
                        "initialComplete=${pendingRecovery.initialScanComplete}, " +
                        "pendingComplete=${pendingRecovery.pendingScanComplete}"
                )
            }
            ManagedLibraryProcessingCoordinator.restore(applicationContext)
            val operationId = ManagedLibraryProcessingCoordinator.tryBeginExclusive(
                context = applicationContext,
                reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
                phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
                resumeWaitingOperation = true
            ) ?: return Result.retry()
            processingOperationId = operationId
            if (PersistentDownloadClearFenceStore.isActive(applicationContext)) {
                NPLogger.i(
                    TAG,
                    "下载清空围栏在迁移占位后生效，迁移延后并保留恢复状态: " +
                        "workId=$migrationWorkId"
                )
                transitionProcessingState(
                    operationId = operationId,
                    waitForRetry = true
                )
                return Result.retry()
            }
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
            val persistedCopyReceipts = mergePersistedMigrationCopyReceipts(
                current = checkpointStore.readCopyReceipts(migrationWorkId),
                checkpoints = buildList {
                    if (checkpointWorkId != migrationWorkId) {
                        add(checkpointStore.readCopyReceipts(checkpointWorkId))
                    }
                    activeReplacementJournal?.workId
                        ?.takeIf(String::isNotBlank)
                        ?.takeUnless { it == migrationWorkId || it == checkpointWorkId }
                        ?.let { workId -> add(checkpointStore.readCopyReceipts(workId)) }
                }
            )
            val persistedProgress = selectMigrationProgressCheckpoint(
                checkpointIds = migrationProgressCheckpointIds(
                    currentWorkId = migrationWorkId,
                    inputCheckpointWorkId = inputData.getString(KEY_CHECKPOINT_WORK_ID),
                    persistedRequest = effectiveRequest,
                    persistedJournal = activeReplacementJournal
                ),
                readProgress = checkpointStore::readProgress
            )
            persistedProgress?.let { progress ->
                // 替换 Worker 重新打开 Provider 并重建内存追踪器时，继续显示最后持久阶段
                setProgress(migrationProgressToWorkData(progress))
                setForeground(createForegroundInfo(progress))
                val sharedProgress = migrationProgressForSharedProcessing(progress)
                ManagedLibraryProcessingCoordinator.updateProgress(
                    operationId = operationId,
                    processed = sharedProgress.processed,
                    total = sharedProgress.total,
                    currentItem = progress.currentFileName,
                    context = applicationContext
                )
            }
            if (!checkpointStore.isRequestCurrent(migrationWorkId)) {
                NPLogger.i(
                    TAG,
                    "迁移请求在打开目录前已被替换，跳过旧任务: workId=$migrationWorkId"
                )
                return Result.success()
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
                    checkpointStore.recordMinimumAudioCountIfCurrent(
                        ownerWorkId = migrationWorkId,
                        workId = migrationWorkId,
                        minimumAudioCount = resolvedMinimumAudioCount
                    )
                },
                onTargetNamePlanResolved = { targetNames ->
                    checkpointStore.recordTargetNamesIfCurrent(
                        ownerWorkId = migrationWorkId,
                        workId = migrationWorkId,
                        targetNames = targetNames
                    )
                },
                onTargetVerified = {
                    if (checkpointStore.isRequestCurrent(migrationWorkId)) {
                        settingsRepository.setDownloadDirectory(
                            uri = toDirectoryUri,
                            label = targetLabel
                        )
                        ManagedDownloadStorage.updateConfiguredTreeUri(toDirectoryUri)
                        ManagedDownloadStorage.updateCustomDirectoryLabel(targetLabel)
                    }
                },
                persistedReplacementJournal = activeReplacementJournal,
                replacementJournalWorkId = activeReplacementJournal?.workId
                    ?.takeIf(String::isNotBlank)
                    ?: migrationWorkId,
                onReplacementJournalUpdated = { journal ->
                    checkpointStore.recordReplacementJournalIfCurrent(
                        ownerWorkId = migrationWorkId,
                        journal = journal
                    )
                },
                persistedProgress = persistedProgress,
                progressOwnerWorkId = migrationWorkId,
                persistedCopyReceipts = persistedCopyReceipts,
                onCopyReceipt = { receipt ->
                    receiptBatcher.add(receipt)
                },
                onCopyReceiptInvalidated = { sourceReference ->
                    receiptBatcher.invalidate(sourceReference)
                    checkpointStore.clearCopyReceiptIfCurrent(
                        ownerWorkId = migrationWorkId,
                        workId = migrationWorkId,
                        sourceReference = sourceReference
                    )
                },
                onCopyReceiptsFlush = receiptBatcher::flush,
                // 预检和复制期间共用同一目录租约，避免 SAF 大目录被重复枚举
                pendingArtifactsPreflightVerified = true
            )
            if (!checkpointStore.isRequestCurrent(migrationWorkId)) {
                NPLogger.i(
                    TAG,
                    "迁移请求在目录处理期间已被替换，保留新任务继续执行: " +
                        "workId=$migrationWorkId"
                )
                return Result.success()
            }
            if (!migrationResult.canSwitchDirectory) {
                val retryCleanup = migrationResult.hasOnlyRetryableCleanupFailures &&
                    shouldRetryMigrationAttempt(
                        runAttemptCount = logicalRetryAttemptCount,
                        maxRetryAttempts = MAX_RETRY_ATTEMPTS
                    )
                if (retryCleanup) {
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = true
                    )
                    return Result.retry()
                }
                // 终态清理结果保留日志和检查点供显式重试，同时释放界面 operation 栅栏
                transitionProcessingState(
                    operationId = operationId,
                    waitForRetry = false
                )
                checkpointStore.markRequestRetryable(migrationWorkId)
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
                    runAttemptCount = logicalRetryAttemptCount,
                    maxRetryAttempts = MAX_RETRY_ATTEMPTS
                )
                transitionProcessingState(
                    operationId = operationId,
                    waitForRetry = retryFinalScan
                )
                if (retryFinalScan) {
                    return Result.retry()
                }
                checkpointStore.markRequestRetryable(migrationWorkId)
                return Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to "迁移后下载目录扫描未发布")
                )
            }
            when (migrationCleanupWorkDecision(migrationResult)) {
                MigrationCleanupWorkDecision.RETRY -> {
                    val retryCleanup = shouldRetryMigrationAttempt(
                        runAttemptCount = logicalRetryAttemptCount,
                        maxRetryAttempts = MAX_RETRY_ATTEMPTS
                    )
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = retryCleanup
                    )
                    if (retryCleanup) {
                        return Result.retry()
                    }
                    checkpointStore.markRequestRetryable(migrationWorkId)
                    return Result.failure(
                        workDataOf(
                            KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles,
                            KEY_VERIFIED_MINIMUM_AUDIO_COUNT to
                                checkpointStore.readMinimumAudioCount(migrationWorkId)
                        )
                    )
                }
                MigrationCleanupWorkDecision.FAILURE -> {
                    // 保留磁盘上的恢复数据，同时让终态结果不阻塞下一次目录操作
                    transitionProcessingState(operationId = operationId, waitForRetry = false)
                    checkpointStore.markRequestRetryable(migrationWorkId)
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
            when (
                val cleared = checkpointStore.clearCompletedAndRunIfCurrent(
                    ownerWorkId = migrationWorkId,
                    workIds = listOf(migrationWorkId, checkpointWorkId),
                    beforeClear = {
                        if (releasePreviousPermission &&
                            migrationResult.canReleasePreviousPermission
                        ) {
                            ManagedDownloadStorage.releasePersistedDirectoryPermission(
                                applicationContext,
                                fromDirectoryUri
                            )
                        }
                    }
                )
            ) {
                null -> {
                    NPLogger.i(
                        TAG,
                        "迁移清理阶段发现请求已被替换，保留新任务凭据: " +
                            "workId=$migrationWorkId"
                    )
                    // 替换请求已经接管持久状态，等它发布扫描结果后再隐藏旧 operation
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = true
                    )
                    return Result.success()
                }
                false -> error("无法清理已完成的迁移凭据")
                true -> Unit
            }
            // 检查点和替换日志都确认清除后才释放界面栅栏，避免目录看似空闲但迁移仍可恢复
            ManagedLibraryProcessingCoordinator.complete(
                applicationContext,
                operationId
            )
            return Result.success(
                workDataOf(
                    KEY_MOVED_FILES to migrationResult.movedFiles,
                    KEY_CLEANUP_FAILED_FILES to migrationResult.cleanupFailedFiles,
                    KEY_VERIFIED_MINIMUM_AUDIO_COUNT to verifiedMinimumAudioCount
                )
            )
        } catch (error: DownloadStorageMutationDeferredException) {
            // deferred 表示目录租约期间 pending 提交未完成，释放租约后需要旁路收敛
            pendingArtifactPreflightBlocked = true
            withContext(NonCancellable) {
                processingOperationId?.let { operationId ->
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = true,
                        cause = error
                    )
                }
                runCatching {
                    checkpointStore.markRequestRetryable(migrationWorkId)
                }.onFailure { stateError ->
                    error.addSuppressed(stateError)
                    NPLogger.w(
                        TAG,
                        "目录变更期间无法持久化迁移重试凭据，保留原始错误: " +
                            stateError.message,
                        stateError
                    )
                }
            }
            NPLogger.i(
                TAG,
                "目录变更期间提交被延后，迁移保留请求等待重试: " +
                    "workId=$migrationWorkId, operationId=${error.message}"
            )
            return Result.retry()
        } catch (error: CancellationException) {
            processingOperationId?.let { operationId ->
                withContext(NonCancellable) {
                    // WorkManager 取消可能打断 Provider 调用，保留持久请求并显示可恢复的等待状态
                    checkpointStore.markRequestRetryable(migrationWorkId)
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = true,
                        cause = error
                    )
                }
            }
            throw error
        } catch (error: Exception) {
            pendingArtifactPreflightBlocked = isPendingArtifactPreflightFailure(error)
            val retry = pendingArtifactPreflightBlocked ||
                shouldRetryMigrationFailure(
                    error = error,
                    runAttemptCount = logicalRetryAttemptCount,
                    maxRetryAttempts = MAX_RETRY_ATTEMPTS
                )
            withContext(NonCancellable) {
                processingOperationId?.let { operationId ->
                    transitionProcessingState(
                        operationId = operationId,
                        waitForRetry = retry,
                        cause = error
                    )
                }
                if (!retry) {
                    // pending 或 Provider 故障都要保留可恢复请求，不能把未完成文件
                    // 标成终态后让后续启动失去迁移入口
                    if (pendingArtifactPreflightBlocked) {
                        NPLogger.i(
                            TAG,
                            "迁移 pending 仍未收敛，达到本轮重试上限但保留请求等待外部恢复: " +
                                "workId=$migrationWorkId"
                        )
                    }
                    runCatching {
                        checkpointStore.markRequestRetryable(migrationWorkId)
                    }.onFailure { stateError ->
                        error.addSuppressed(stateError)
                        NPLogger.w(
                            TAG,
                            "迁移失败后无法持久化重试凭据，保留原始错误: " +
                                stateError.message,
                            stateError
                        )
                    }
                }
            }
            return if (retry) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (error.message ?: "")))
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { receiptBatcher.flush() }
                    .onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "迁移复制凭据最终刷盘失败: ${error.message}",
                            error
                        )
                    }
            }
            directoryMutationLease?.let { lease ->
                withContext(NonCancellable) {
                    val leaseClosed = runCatching { lease.close() }
                        .onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "迁移结束释放目录栅栏失败，暂不执行目录恢复: " +
                                    error.message,
                                error
                            )
                        }
                        .isSuccess
                    if (
                        leaseClosed &&
                            pendingArtifactPreflightBlocked &&
                            checkpointStore.isRequestCurrent(migrationWorkId)
                    ) {
                        runCatching {
                            GlobalDownloadManager.reconcilePendingDownloadsAfterMigrationBlocked(
                                context = applicationContext,
                                sourceDirectoryUri = sourceDirectoryUriForRecovery
                            )
                        }.onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "迁移 pending 旁路恢复失败，保留凭据等待下次重试: " +
                                    error.message,
                                error
                            )
                        }
                    }
                    if (leaseClosed) {
                        GlobalDownloadManager.recoverPendingDownloadsAfterStorageMutation(
                            applicationContext
                        )
                    }
                }
            }
        }
    }

    private fun isPendingArtifactPreflightFailure(error: Throwable): Boolean {
        return shouldRetainMigrationPendingRetry(error)
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
                val persistedRead = runCatching { checkpointStore.readRequest() }
                val persisted = persistedRead.getOrElse { error ->
                    NPLogger.w(
                        TAG,
                        "读取旧迁移请求失败，新目录请求将归档旧凭据后接管: ${error.message}",
                        error
                    )
                    null
                }
                val rootsChanged = persistedRead.isFailure ||
                    shouldSupersedePersistedMigrationRequest(
                        persistedRequest = persisted,
                        requestedRequest = requested
                    )
                val durableRequest = if (rootsChanged) {
                    requested
                } else if (persisted?.autoResume == true) {
                    persisted.copy(
                        minimumSourceEntryCount = maxOf(
                            persisted.minimumSourceEntryCount,
                            requested.minimumSourceEntryCount
                        )
                    )
                } else {
                    requested
                }
                if (rootsChanged) {
                    // 新目录必须接管活动请求，旧 Worker 的回写会因所有权变化被丢弃
                    checkpointStore.replaceActiveRequestForDifferentRoots(durableRequest)
                }
                val workManager = WorkManager.getInstance(appContext)
                val active = activeMigrationWorkInfo(
                    workManager = workManager,
                    preferredWorkId = durableRequest.workId,
                    fallbackWorkId = durableRequest.checkpointWorkId
                )
                if (active != null) {
                    if (
                        rootsChanged ||
                            shouldReplaceActiveMigrationWork(durableRequest, active.id.toString())
                    ) {
                        if (!rootsChanged) {
                            checkpointStore.recordRequest(durableRequest)
                        }
                        return@withLock enqueueDurableRequestLocked(
                            workManager = workManager,
                            checkpointStore = checkpointStore,
                            request = durableRequest,
                            existingWorkPolicy = ExistingWorkPolicy.REPLACE
                        )
                    }
                    ensureRequestForActiveWork(
                        checkpointStore = checkpointStore,
                        activeWork = active,
                        fallback = (persisted ?: durableRequest).copy(autoResume = true)
                    )
                    return@withLock active.id.toString()
                }
                // 入队前立即写入意图，入队失败时启动流程可以不打开目录就重试
                if (!rootsChanged) {
                    checkpointStore.recordRequest(durableRequest)
                }
                enqueueDurableRequestLocked(
                    workManager = workManager,
                    checkpointStore = checkpointStore,
                    request = durableRequest
                )
            }
        }

        /** 把被杀进程留下的请求重新入队，启动协程不触碰目录，Provider I/O 由 Worker 完成 */
        suspend fun resumePersistedRequestIfNeeded(context: Context): String? =
            withContext(Dispatchers.IO) {
                enqueueMutex.withLock {
                    val appContext = context.applicationContext
                    val checkpointStore = ManagedDownloadMigrationCheckpointStore(appContext)
                    val workManager = WorkManager.getInstance(appContext)
                    val persisted = checkpointStore.readRequest()
                    val journal = checkpointStore.readReplacementJournal()
                    val active = activeMigrationWorkInfo(
                        workManager = workManager,
                        preferredWorkId = persisted?.workId,
                        fallbackWorkId = persisted?.checkpointWorkId
                    )
                    val persistedProcessingState = runCatching {
                        // 只读取小型状态凭据，不触碰目录，避免冷启动被 SAF 扫描拖住
                        ManagedLibraryProcessingCoordinator.restoreImmediately(appContext)
                    }.onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "启动读取迁移处理状态失败，继续使用检查点恢复: ${error.message}",
                            error
                        )
                    }.getOrNull()
                    val processingNeedsRecovery =
                        persistedProcessingState is ManagedLibraryProcessingState.WaitingForRetry &&
                            persistedProcessingState.reason ==
                                ManagedLibraryProcessingReason.DIRECTORY_CHANGE
                    if (active != null) {
                        if (shouldReplaceActiveMigrationWork(persisted, active.id.toString())) {
                            val resumed = persisted!!.copy(
                                workId = UUID.randomUUID().toString(),
                                checkpointWorkId = active.id.toString()
                            )
                            checkpointStore.recordRequest(resumed)
                            NPLogger.i(
                                TAG,
                                "启动发现新请求与活动迁移不一致，立即替换旧任务: " +
                                    "old=${active.id}, new=${resumed.workId}"
                            )
                            return@withLock enqueueDurableRequestLocked(
                                workManager = workManager,
                                checkpointStore = checkpointStore,
                                request = resumed,
                                existingWorkPolicy = ExistingWorkPolicy.REPLACE
                            )
                        }
                        val journalNeedsRecovery = journal?.phase?.let { phase ->
                            phase != ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
                        } == true
                        if (
                                persisted != null &&
                                (persisted.autoResume || journalNeedsRecovery) &&
                                shouldRearmMigrationWorkOnStartup(
                                    state = active.state,
                                    runAttemptCount = active.runAttemptCount,
                                    retryAttemptOffset = persisted.retryAttemptOffset,
                                    maxRetryAttempts = MAX_RETRY_ATTEMPTS,
                                    // pending 收敛和未完成替换不能继续等待旧的指数退避
                                    forceRecovery = journalNeedsRecovery || processingNeedsRecovery
                                )
                            ) {
                            val resumed = persisted.copy(
                                workId = UUID.randomUUID().toString(),
                                checkpointWorkId = active.id.toString(),
                                autoResume = true,
                                retryAttemptOffset = migrationRetryAttemptCount(
                                    retryAttemptOffset = persisted.retryAttemptOffset,
                                    runAttemptCount = active.runAttemptCount
                                )
                            )
                            checkpointStore.recordRequest(resumed)
                            NPLogger.i(
                                TAG,
                                "启动立即重置迁移退避任务: " +
                                    "old=${active.id}, new=${resumed.workId}, " +
                                    "attempt=${active.runAttemptCount}"
                            )
                            return@withLock enqueueDurableRequestLocked(
                                workManager = workManager,
                                checkpointStore = checkpointStore,
                                request = resumed,
                                existingWorkPolicy = ExistingWorkPolicy.REPLACE
                            )
                        }
                        ensureRequestForActiveWork(
                            checkpointStore = checkpointStore,
                            activeWork = active,
                            fallback = persisted?.copy(autoResume = true)
                        )
                        return@withLock active.id.toString()
                    }
                    val request = when {
                        persisted?.autoResume == true -> persisted
                        persisted != null && journal?.phase?.let { phase ->
                            phase != ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
                        } == true ->
                            persisted.copy(autoResume = true)
                        persisted != null -> return@withLock null
                        else -> journal
                            ?.takeIf { replacement ->
                                replacement.fromDirectoryUri != null ||
                                    replacement.toDirectoryUri != null
                            }
                            ?.let { replacement ->
                                ManagedMigrationRequest(
                                    workId = replacement.workId,
                                    fromDirectoryUri = replacement.fromDirectoryUri,
                                    toDirectoryUri = replacement.toDirectoryUri,
                                    targetLabel = "",
                                    releasePreviousPermission = false,
                                    minimumSourceEntryCount = checkpointStore
                                        .readMinimumAudioCount(replacement.workId),
                                    checkpointWorkId = replacement.workId
                                )
                            }
                    } ?: return@withLock null
                    val resumed = request.copy(
                        workId = UUID.randomUUID().toString(),
                        checkpointWorkId = request.workId
                    )
                    checkpointStore.recordRequest(resumed)
                    enqueueDurableRequestLocked(
                        workManager = workManager,
                        checkpointStore = checkpointStore,
                        request = resumed
                    )
                }
            }

        /** 判断 WorkManager 暂时不可查询时启动流程是否仍需保留目录 operation */
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
            request: ManagedMigrationRequest,
            existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
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
                existingWorkPolicy,
                workRequest
            ).result.get()
            val activeAfter = activeMigrationWorkInfo(
                workManager = workManager,
                preferredWorkId = request.workId,
                fallbackWorkId = request.checkpointWorkId
            )
            if (activeAfter != null && !migrationWorkIdsEqual(
                    activeAfter.id.toString(),
                    request.workId
                )
            ) {
                NPLogger.w(
                    TAG,
                    "迁移新任务已入队但旧任务仍可见，保留新请求等待 WorkManager 替换: " +
                        "requested=${request.workId}, active=${activeAfter.id}"
                )
                return workRequest.id.toString()
            }
            return activeAfter?.id?.toString() ?: workRequest.id.toString()
        }

        private fun activeMigrationWorkInfo(
            workManager: WorkManager,
            preferredWorkId: String? = null,
            fallbackWorkId: String? = null
        ) = selectActiveMigrationWorkInfo(
            workInfos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get(),
            preferredWorkId = preferredWorkId,
            fallbackWorkId = fallbackWorkId
        )

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
