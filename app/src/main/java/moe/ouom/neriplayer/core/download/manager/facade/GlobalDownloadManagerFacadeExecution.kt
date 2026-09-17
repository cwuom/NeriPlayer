package moe.ouom.neriplayer.core.download.manager.facade

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.openDownloadAdmissionTicketOrNull
import moe.ouom.neriplayer.core.download.manager.batch.rememberPendingDownloadQueue
import moe.ouom.neriplayer.core.download.manager.batch.reuseOrBeginDownloadRequestGeneration
import moe.ouom.neriplayer.core.download.manager.catalog.awaitDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadWaitingForDeleteCleanup
import moe.ouom.neriplayer.core.download.manager.catalog.scheduleDeleteCleanupRetry
import moe.ouom.neriplayer.core.download.manager.runtime.deferDownloadOperationExecutionForNetworkPolicyIfNeeded
import moe.ouom.neriplayer.core.download.manager.runtime.executionResultForOperation
import moe.ouom.neriplayer.core.download.manager.runtime.recoverPostCoreDownloadOperation
import moe.ouom.neriplayer.core.download.manager.runtime.reopenMissingPostCoreArtifactForFreshTransfer
import moe.ouom.neriplayer.core.download.manager.runtime.startDownloadConfirmed
import moe.ouom.neriplayer.core.download.manager.runtime.withSongExecutionLock
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.resolveDownloadPreserveStaging
import moe.ouom.neriplayer.core.download.policy.requiresDownloadFinalizationRecovery
import android.content.Context
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.state.isPostCoreDownloadOperationState
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

internal suspend fun GlobalDownloadManager.startDownloadImpl(
    context: Context,
    song: SongItem,
    operationId: String,
    preserveStaging: Boolean = false,
    preparedAttemptId: Long? = null
): DownloadExecutionResult {
    val songKey = song.stableKey()
    val admissionTicket = openDownloadAdmissionTicketOrNull(
        context = context,
        stableKey = songKey,
        operationId = operationId
    )
        ?: return DownloadExecutionResult.Cancelled
    if (!isDownloadAdmissionTicketCurrent(
            context = context,
            admissionTicket = admissionTicket,
            stableKey = songKey,
            operationId = operationId
        )
    ) {
        return DownloadExecutionResult.Cancelled
    }
    return executeDownloadOperation(
        context = context,
        song = song,
        operationId = operationId,
        preserveStaging = preserveStaging,
        preparedAttemptId = preparedAttemptId,
        admissionTicket = admissionTicket
    )
}

internal suspend fun GlobalDownloadManager.executeDownloadOperationImpl(
    context: Context,
    song: SongItem,
    operationId: String,
    preserveStaging: Boolean = false,
    preparedAttemptId: Long? = null,
    admissionTicket: Long? = null
): DownloadExecutionResult {
    val appContext = context.applicationContext
    val songKey = song.stableKey()
    val capturedAdmissionTicket = admissionTicket
        ?: openDownloadAdmissionTicketOrNull(
            context = appContext,
            stableKey = songKey,
            operationId = operationId
        )
        ?: return DownloadExecutionResult.Cancelled
    if (!isDownloadAdmissionTicketCurrent(
            context = appContext,
            admissionTicket = capturedAdmissionTicket,
            stableKey = songKey,
            operationId = operationId
        )
    ) {
        return DownloadExecutionResult.Cancelled
    }
    if (
        ManagedDownloadDirectoryMutationFence.deferOperationIfActive(
            context = appContext,
            operationId = operationId
        )
    ) {
        return DownloadExecutionResult.AlreadyHandled
    }
    val operationStateBeforeDeletion = DownloadExecutionRoomStore.state(
        context = appContext,
        operationId = operationId
    )
    val restartMissingPostCoreArtifact = if (
        requiresDownloadFinalizationRecovery(operationStateBeforeDeletion)
    ) {
        // 重开判断必须和目录扫描/增强收尾共用歌曲锁，避免在另一条协程
        // 已经把 pending 提升为正式音频时仍按“无引用”重新传输
        withSongExecutionLock(songKey) {
            reopenMissingPostCoreArtifactForFreshTransfer(
                context = appContext,
                song = song,
                operationId = operationId,
                expectedAttemptId = preparedAttemptId
            )
        }
    } else {
        false
    }
    if (
        operationStateBeforeDeletion in setOf(
            "COMPLETED",
            "FINALIZED",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        ) && !restartMissingPostCoreArtifact
    ) {
        val recoveredPostCore = if (
            requiresDownloadFinalizationRecovery(operationStateBeforeDeletion)
        ) {
            recoverPostCoreDownloadOperation(
                context = appContext,
                song = song,
                operationId = operationId,
                expectedAttemptId = preparedAttemptId,
                admissionTicket = capturedAdmissionTicket
            )
        } else {
            true
        }
        if (!recoveredPostCore &&
            requiresDownloadFinalizationRecovery(
                DownloadExecutionRoomStore.state(appContext, operationId)
            )
        ) {
            // 收尾已交给独立队列或启动 artifact 恢复。不能让当前宿主把
            // CORE_COMMITTED/ASSETS_ENRICHING/DEGRADED_COMPLETE 覆盖为 RETRYABLE，
            // 否则下一次重启会把收尾任务错误显示成普通“等待重试”并重新走传输调度
            return DownloadExecutionResult.AlreadyHandled
        }
        return executionResultForOperation(
            context = appContext,
            operationId = operationId,
            songKey = songKey,
            expectedAttemptId = preparedAttemptId
        )
    }
    downloadAdmissionGate.awaitOpen()
    if (!awaitDownloadedSongDeletion(setOf(songKey))) {
        if (!isDownloadAdmissionTicketCurrent(
                context = appContext,
                admissionTicket = capturedAdmissionTicket,
                stableKey = songKey,
                operationId = operationId
            )
        ) {
            return DownloadExecutionResult.Cancelled
        }
        markDownloadWaitingForDeleteCleanup(
            context = appContext,
            songKey = songKey,
            operationId = operationId,
            expectedAttemptId = preparedAttemptId,
            admissionTicket = capturedAdmissionTicket
        )
        scheduleDeleteCleanupRetry(
            context = appContext,
            songKeys = setOf(songKey),
            admissionTicket = capturedAdmissionTicket
        )
        return DownloadExecutionResult.Retry
    }
    var admissionResult: DownloadExecutionResult? = null
    var admittedRequest: DownloadExecutionRequest? = null
    var admittedAttemptId: Long? = null
    var admittedPreserveStaging = false
    var admittedRequestGeneration: Long? = null
    // 清空快速阶段可能已经释放内存闸门，但持久栅栏仍会阻止旧请求回流
    val admitted = admitDownloadMutation(
        context = appContext,
        admissionTicket = capturedAdmissionTicket,
        stableKey = songKey,
        operationId = operationId
    ) admission@{
        val persistedRequest = DownloadExecutionRoomStore.read(appContext, operationId)
            ?.takeIf { request -> request.song.stableKey() == songKey }
            ?: run {
                admissionResult = DownloadExecutionResult.MissingOperation
                return@admission
            }
        if (!DownloadExecutionRoomStore.isExecutionOwned(appContext, operationId, songKey)) {
            admissionResult = executionResultForOperation(
                context = appContext,
                operationId = operationId,
                songKey = songKey,
                expectedAttemptId = preparedAttemptId
            )
            return@admission
        }
        if (
            deferDownloadOperationExecutionForNetworkPolicyIfNeeded(
                context = appContext,
                request = persistedRequest,
                preparedAttemptId = preparedAttemptId
            )
        ) {
            admissionResult = DownloadExecutionResult.NetworkPolicyWaiting
            return@admission
        }
        val durableAttemptIds = (preparedAttemptId ?: persistedRequest.attemptId)
            ?.takeIf { it > 0L }
            ?.let { attemptId -> mapOf(songKey to attemptId) }
            ?: emptyMap()
        val effectiveAttemptId = taskStore.ensureDownloadTasks(
            songs = listOf(song),
            status = DownloadStatus.QUEUED,
            durableAttemptIds = durableAttemptIds
        )[songKey] ?: run {
            admissionResult = DownloadExecutionResult.Retry
            return@admission
        }
        DownloadExecutionRoomStore.upsert(
            context = appContext,
            request = persistedRequest.copy(attemptId = effectiveAttemptId),
            state = "RUNNING"
        )
        runCatching {
            DownloadExecutionRoomStore.updateState(
                context = appContext,
                operationId = operationId,
                state = "RUNNING"
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "更新下载 operation 状态失败: ${error.message}")
        }
        if (!DownloadExecutionRoomStore.isExecutionOwned(appContext, operationId, songKey)) {
            taskStore.removeDownloadTask(
                songKey = songKey,
                expectedAttemptId = effectiveAttemptId
            )
            admissionResult = executionResultForOperation(
                context = appContext,
                operationId = operationId,
                songKey = songKey,
                expectedAttemptId = effectiveAttemptId
            )
            return@admission
        }
        admittedRequest = persistedRequest
        admittedAttemptId = effectiveAttemptId
        admittedPreserveStaging = resolveDownloadPreserveStaging(
            persistedPreserveStaging = persistedRequest.preserveStaging,
            preserveRequested = preserveStaging
        )
        admittedRequestGeneration = reuseOrBeginDownloadRequestGeneration(
            song = song,
            attemptId = effectiveAttemptId
        )
    }
    if (!admitted) {
        return DownloadExecutionResult.Cancelled
    }
    admissionResult?.let { return it }
    val persistedRequest = admittedRequest ?: return DownloadExecutionResult.Retry
    val effectiveAttemptId = admittedAttemptId ?: return DownloadExecutionResult.Retry
    val requestGeneration = admittedRequestGeneration ?: return DownloadExecutionResult.Retry
    startDownloadConfirmed(
        context = appContext,
        song = song,
        cleanupBeforeStart = !admittedPreserveStaging,
        requestGeneration = requestGeneration,
        admissionTicket = capturedAdmissionTicket,
        deferForNetworkPolicy = false,
        operationId = operationId,
        preparedAttemptId = effectiveAttemptId,
        artifactLeaseOwnerId = persistedRequest.artifactLeaseId,
        downloadAudioQuality = persistedRequest.downloadAudioQuality
    )
    return executionResultForOperation(
        context = appContext,
        operationId = operationId,
        songKey = songKey,
        expectedAttemptId = effectiveAttemptId
    )
}

internal fun GlobalDownloadManager.shouldRestartPostCoreOperationForFreshTransferImpl(
    operationState: String?,
    artifactClaim: ManagedDownloadArtifactClaim
): Boolean {
    val acquiredArtifact = when (artifactClaim) {
        is ManagedDownloadArtifactClaim.Acquired -> artifactClaim.artifact
        else -> null
    }
    return requiresDownloadFinalizationRecovery(operationState) &&
        acquiredArtifact != null &&
        acquiredArtifact.audioReference.isNullOrBlank()
}

internal fun GlobalDownloadManager.stopDownloadOperationImpl(
    context: Context,
    songKey: String,
    expectedAttemptId: Long?,
    rememberForRetry: Boolean,
    operationId: String? = null,
    knownOperationState: String? = null
) {
    val appContext = context.applicationContext
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    // 任务卡片可能已经被替代 operation 更新，先按 operation 身份停止旧宿主。
    // 不能让后面的 current attempt 检查把旧网络调用留在执行槽位里
    normalizedOperationId?.let { oldOperationId ->
        if (
            !isPostCoreDownloadOperationState(knownOperationState) &&
                !AudioDownloadManager.isCoreCommittedOperation(oldOperationId)
        ) {
            AudioDownloadManager.pauseOperationDownloadForExecutionHost(oldOperationId)
        } else {
            AudioDownloadManager.clearOperationPauseForExecutionHost(oldOperationId)
            NPLogger.d(
                TAG,
                "停止请求跳过已提交 core operation: operationId=$oldOperationId, " +
                    "state=$knownOperationState"
            )
        }
    }
    if (
        isDownloadClearFenceActive(appContext, stableKey = songKey) ||
            !taskStore.isDownloadAttemptCurrent(songKey, expectedAttemptId)
    ) {
        return
    }
    val task = taskStore.findTask(songKey) ?: return
    if (
        task.status != DownloadStatus.QUEUED &&
        task.status != DownloadStatus.DOWNLOADING &&
        task.status != DownloadStatus.WAITING_NETWORK
    ) {
        return
    }
    if (normalizedOperationId == null) {
        AudioDownloadManager.pauseSongDownloadForExecutionHost(songKey)
    }
    if (
        isDownloadClearFenceActive(appContext, stableKey = songKey) ||
            !taskStore.isDownloadAttemptCurrent(songKey, expectedAttemptId)
    ) {
        return
    }
    updateTaskStatus(
        songKey = songKey,
        status = DownloadStatus.WAITING_NETWORK,
        expectedAttemptId = task.attemptId
    )
    if (
        rememberForRetry &&
            !isDownloadClearFenceActive(appContext, stableKey = songKey) &&
            taskStore.isDownloadAttemptCurrent(songKey, expectedAttemptId)
    ) {
        rememberPendingDownloadQueue(appContext, listOf(task.song))
    }
}

internal fun GlobalDownloadManager.cancelDownloadOperationFromHostImpl(
    songKey: String,
    operationId: String? = null
) {
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (normalizedOperationId == null) {
        AudioDownloadManager.cancelSongDownload(songKey)
    } else {
        AudioDownloadManager.cancelOperationDownload(
            songKey = songKey,
            operationIds = setOf(normalizedOperationId)
        )
    }
}
