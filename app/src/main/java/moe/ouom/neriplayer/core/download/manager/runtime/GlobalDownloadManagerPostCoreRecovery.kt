package moe.ouom.neriplayer.core.download

import android.content.Context
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.METADATA_ACTION_REQUIRED_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
import moe.ouom.neriplayer.core.download.execution.isRetryDeadlineReady
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull

internal val POST_CORE_DOWNLOAD_OPERATION_STATES = listOf(
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "DEGRADED_COMPLETE"
)

internal enum class PostCoreDownloadRecoveryResult {
    SETTLED,
    CONTINUE_SOON,
    RETRY,
    WAITING_NETWORK,
    BLOCKED
}

internal data class PostCoreDownloadRecoveryCandidate(
    val operationId: String,
    val state: String,
    val queueOrder: Int,
    val updatedAtMs: Long,
    val requiresWifiNetwork: Boolean,
    val nextRetryAtMs: Long? = null
)

/** 旧失败优先，同时为首次收尾保留一个位置，避免失败项长期阻塞新任务 */
internal fun selectPostCoreDownloadRecoveryCandidates(
    candidates: Collection<PostCoreDownloadRecoveryCandidate>,
    capacity: Int,
    activeOperationIds: Set<String> = emptySet(),
    attemptedOperationIds: Set<String> = emptySet(),
    currentNetworkType: TrafficNetworkType?,
    mobileDataOverrideAllowed: Boolean,
    nowMs: Long = System.currentTimeMillis()
): List<PostCoreDownloadRecoveryCandidate> {
    val boundedCapacity = capacity.coerceAtLeast(0)
    if (boundedCapacity == 0 || currentNetworkType == null) return emptyList()
    val eligible = candidates
        .asSequence()
        .filter { candidate ->
            candidate.operationId !in activeOperationIds &&
                candidate.operationId !in attemptedOperationIds &&
                isRetryDeadlineReady(candidate.nextRetryAtMs, nowMs) &&
                isPostCoreRecoveryNetworkEligible(
                    requiresWifiNetwork = candidate.requiresWifiNetwork,
                    currentNetworkType = currentNetworkType,
                    mobileDataOverrideAllowed = mobileDataOverrideAllowed
                )
        }
        .distinctBy(PostCoreDownloadRecoveryCandidate::operationId)
        .toList()
    val retrying = eligible
        .filter { candidate -> candidate.state == "DEGRADED_COMPLETE" }
        .sortedWith(
            compareBy<PostCoreDownloadRecoveryCandidate> { it.updatedAtMs }
                .thenBy { it.queueOrder }
                .thenBy { it.operationId }
        )
    val fresh = eligible
        .filterNot { candidate -> candidate.state == "DEGRADED_COMPLETE" }
        .sortedWith(
            compareBy<PostCoreDownloadRecoveryCandidate> { it.queueOrder }
                .thenBy { it.updatedAtMs }
                .thenBy { it.operationId }
        )
    if (retrying.isEmpty()) return fresh.take(boundedCapacity)
    if (fresh.isEmpty() || boundedCapacity == 1) return retrying.take(boundedCapacity)

    val selected = ArrayList<PostCoreDownloadRecoveryCandidate>(boundedCapacity)
    selected += retrying.take(boundedCapacity - 1)
    selected += fresh.take(boundedCapacity - selected.size)
    if (selected.size < boundedCapacity) {
        selected += retrying.drop(selected.count { it.state == "DEGRADED_COMPLETE" })
            .take(boundedCapacity - selected.size)
    }
    return selected
}

internal fun isPostCoreRecoveryNetworkEligible(
    requiresWifiNetwork: Boolean,
    currentNetworkType: TrafficNetworkType?,
    mobileDataOverrideAllowed: Boolean
): Boolean {
    if (currentNetworkType == null) return false
    return !requiresWifiNetwork ||
        currentNetworkType == TrafficNetworkType.WIFI ||
        mobileDataOverrideAllowed
}

internal suspend fun GlobalDownloadManager.recoverPostCoreDownloadsForWorkerImpl(
    context: Context
): PostCoreDownloadRecoveryResult {
    val appContext = context.applicationContext
    val admissionTicket = openDownloadAdmissionTicketOrNull(appContext)
        ?: return PostCoreDownloadRecoveryResult.BLOCKED
    val attemptedOperationIds = linkedSetOf<String>()
    var completedWindows = 0
    var initialRemainingCount: Int? = null

    while (
        completedWindows < POST_CORE_RECOVERY_MAX_WINDOWS &&
            attemptedOperationIds.size < POST_CORE_RECOVERY_MAX_OPERATIONS
    ) {
        if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
            return PostCoreDownloadRecoveryResult.BLOCKED
        }
        val entries = loadPostCoreDownloadRecoveryEntries(appContext)
            ?: return PostCoreDownloadRecoveryResult.BLOCKED
        if (entries.isEmpty()) return PostCoreDownloadRecoveryResult.SETTLED
        if (initialRemainingCount == null) initialRemainingCount = entries.size

        val durableOperationIds = entries
            .mapTo(linkedSetOf()) { entry -> entry.request.operationId }
        val activeEnrichmentIds = assetEnrichmentCoordinator.activeOperationIds()
            .intersect(durableOperationIds)
        if (activeEnrichmentIds.isNotEmpty()) {
            val activeEntries = entries.filter { entry ->
                entry.request.operationId in activeEnrichmentIds
            }
            attemptedOperationIds += activeEnrichmentIds
            val settled = assetEnrichmentCoordinator.awaitCompletion(
                operationIds = activeEnrichmentIds,
                timeoutMs = POST_CORE_RECOVERY_WINDOW_WAIT_MS
            )
            completedWindows++
            if (!settled) return PostCoreDownloadRecoveryResult.RETRY
            settlePostCoreRecoveryAttempts(
                context = appContext,
                entries = activeEntries,
                admissionTicket = admissionTicket
            )
            continue
        }

        val availableCapacity = minOf(
            assetEnrichmentCoordinator.availableCapacity(),
            POST_CORE_RECOVERY_MAX_OPERATIONS - attemptedOperationIds.size
        )
        if (availableCapacity <= 0) {
            val allActiveIds = assetEnrichmentCoordinator.activeOperationIds()
            if (allActiveIds.isEmpty()) return PostCoreDownloadRecoveryResult.RETRY
            attemptedOperationIds += allActiveIds
            val settled = assetEnrichmentCoordinator.awaitCompletion(
                operationIds = allActiveIds,
                timeoutMs = POST_CORE_RECOVERY_WINDOW_WAIT_MS
            )
            completedWindows++
            if (!settled) return PostCoreDownloadRecoveryResult.RETRY
            continue
        }

        val hostActiveIds = entries
            .asSequence()
            .map { entry -> entry.request.operationId }
            .filter(DownloadExecutionHosts.default::isExecuting)
            .toSet()
        val currentNetworkType = appContext.currentDownloadNetworkTypeOrNull()
        val selectedCandidates = selectPostCoreDownloadRecoveryCandidates(
            candidates = entries.map { entry ->
                PostCoreDownloadRecoveryCandidate(
                    operationId = entry.request.operationId,
                    state = entry.state,
                    queueOrder = entry.queueOrder,
                    updatedAtMs = entry.updatedAtMs,
                    requiresWifiNetwork = entry.request.requiresWifiNetwork,
                    nextRetryAtMs = entry.nextRetryAtMs
                )
            },
            capacity = availableCapacity,
            activeOperationIds = hostActiveIds,
            attemptedOperationIds = attemptedOperationIds,
            currentNetworkType = currentNetworkType,
            mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
        )
        if (selectedCandidates.isEmpty()) {
            return classifyPostCoreDownloadRecovery(
                entries = entries,
                currentNetworkType = currentNetworkType,
                mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
            )
        }

        val entriesByOperationId = entries.associateBy { entry -> entry.request.operationId }
        selectedCandidates.forEach { candidate ->
            attemptedOperationIds += candidate.operationId
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return PostCoreDownloadRecoveryResult.BLOCKED
            }
            val entry = entriesByOperationId[candidate.operationId] ?: return@forEach
            try {
                recoverPostCoreDownloadOperation(
                    context = appContext,
                    song = entry.request.song,
                    operationId = entry.request.operationId,
                    expectedAttemptId = entry.request.attemptId,
                    admissionTicket = admissionTicket
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "持久收尾 Worker 恢复单曲失败，保留 operation 等待后续窗口: " +
                        "operationId=${entry.request.operationId}, error=${error.message}",
                    error
                )
            }
        }

        val selectedActiveIds = assetEnrichmentCoordinator.activeOperationIds()
            .intersect(selectedCandidates.mapTo(linkedSetOf()) { it.operationId })
        if (selectedActiveIds.isNotEmpty()) {
            val settled = assetEnrichmentCoordinator.awaitCompletion(
                operationIds = selectedActiveIds,
                timeoutMs = POST_CORE_RECOVERY_WINDOW_WAIT_MS
            )
            if (!settled) return PostCoreDownloadRecoveryResult.RETRY
        }
        settlePostCoreRecoveryAttempts(
            context = appContext,
            entries = selectedCandidates.mapNotNull { candidate ->
                entriesByOperationId[candidate.operationId]
            },
            admissionTicket = admissionTicket
        )
        completedWindows++
    }

    val remainingEntries = loadPostCoreDownloadRecoveryEntries(appContext)
        ?: return PostCoreDownloadRecoveryResult.BLOCKED
    val classified = classifyPostCoreDownloadRecovery(
        entries = remainingEntries,
        currentNetworkType = appContext.currentDownloadNetworkTypeOrNull(),
        mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
    )
    return if (
        classified == PostCoreDownloadRecoveryResult.RETRY &&
            remainingEntries.size < (initialRemainingCount ?: Int.MAX_VALUE)
    ) {
        PostCoreDownloadRecoveryResult.CONTINUE_SOON
    } else {
        classified
    }
}

private suspend fun GlobalDownloadManager.settlePostCoreRecoveryAttempts(
    context: Context,
    entries: Collection<DownloadExecutionRoomStore.StateEntry>,
    admissionTicket: Long
) {
    val currentEntries = loadPostCoreDownloadRecoveryEntries(context)
        ?.associateBy { entry -> entry.request.operationId }
        ?: return
    entries.forEach { attemptedEntry ->
        if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) return
        val operationId = attemptedEntry.request.operationId
        val currentEntry = currentEntries[operationId] ?: return@forEach
        if (!isPostCoreRecoveryNetworkEligible(
                requiresWifiNetwork = currentEntry.request.requiresWifiNetwork,
                currentNetworkType = context.currentDownloadNetworkTypeOrNull(),
                mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
            )
        ) {
            // 断网和 Wi-Fi 策略等待不消耗资产失败次数
            updateTaskStatus(
                songKey = currentEntry.request.song.stableKey(),
                status = DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = currentEntry.request.attemptId,
                settleBatchPresentation = false,
                operationId = operationId
            )
            return@forEach
        }
        if (
            currentEntry.lastErrorCode == METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR &&
                isMetadataEmbeddingActionRequired(
                    context = context,
                    operationId = operationId,
                    songKey = currentEntry.request.song.stableKey()
                )
        ) {
            val actionPersisted = DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = METADATA_ACTION_REQUIRED_OPERATION_STATE,
                errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
            )
            if (actionPersisted) {
                updateTaskStatus(
                    songKey = currentEntry.request.song.stableKey(),
                    status = DownloadStatus.FAILED,
                    expectedAttemptId = currentEntry.request.attemptId,
                    operationId = operationId
                )
            }
            return@forEach
        }
        val retryRecord = DownloadExecutionRoomStore.recordPostCoreRetryFailure(
            context = context,
            operationId = operationId,
            stableKey = currentEntry.request.song.stableKey(),
            expectedAttemptId = currentEntry.request.attemptId,
            errorCode = currentEntry.lastErrorCode ?: "POST_CORE_RECOVERY_NO_PROGRESS"
        ) ?: return@forEach
        if (retryRecord.retryCount < POST_CORE_ENRICHMENT_MAX_AUTO_RETRIES) {
            updateTaskStatus(
                songKey = currentEntry.request.song.stableKey(),
                status = DownloadStatus.QUEUED,
                expectedAttemptId = currentEntry.request.attemptId,
                settleBatchPresentation = false,
                operationId = operationId
            )
            publishDownloadStage(
                song = currentEntry.request.song,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY,
                operationId = operationId,
                attemptId = currentEntry.request.attemptId
            )
            return@forEach
        }
        settleExhaustedPostCoreRecovery(
            context = context,
            entry = currentEntry,
            retryRecord = retryRecord,
            admissionTicket = admissionTicket
        )
    }
}

private suspend fun GlobalDownloadManager.settleExhaustedPostCoreRecovery(
    context: Context,
    entry: DownloadExecutionRoomStore.StateEntry,
    retryRecord: DownloadExecutionRoomStore.PostCoreRetryRecord,
    admissionTicket: Long
) {
    if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) return
    val request = entry.request
    val song = request.song
    val songKey = song.stableKey()
    val durableLeaseId = request.artifactLeaseId.trim().takeIf(String::isNotBlank)
    val currentLeaseIds = managedDownloadArtifactCoordinator.currentLeaseIdsAnyRoot(
        context = context,
        song = song
    )
    val hasNewerArtifactLease = currentLeaseIds.any { leaseId -> leaseId != durableLeaseId }
    if (hasNewerArtifactLease) {
        NPLogger.w(
            TAG,
            "收尾重试耗尽时发现新 artifact lease，仅终止旧 operation: " +
                "operationId=${request.operationId}, leases=${currentLeaseIds.size}"
        )
    }
    if (
        !hasNewerArtifactLease &&
        durableLeaseId != null &&
            currentLeaseIds.isNotEmpty() &&
            !managedDownloadArtifactCoordinator.settleLeaseAnyRoot(
                context = context,
                song = song,
                expectedLeaseId = durableLeaseId,
                requestedState = ManagedDownloadArtifactState.FAILED_RETRYABLE,
                errorCode = POST_CORE_ENRICHMENT_RETRY_EXHAUSTED_ERROR
            )
    ) {
        NPLogger.w(
            TAG,
            "收尾重试耗尽但 artifact lease 未释放，保留恢复状态: " +
                "operationId=${request.operationId}"
        )
        return
    }
    val terminalPersisted = DownloadExecutionRoomStore.markPostCoreRetryExhausted(
        context = context,
        operationId = request.operationId,
        stableKey = songKey,
        expectedAttemptId = request.attemptId,
        minimumRetryCount = POST_CORE_ENRICHMENT_MAX_AUTO_RETRIES,
        errorCode = POST_CORE_ENRICHMENT_RETRY_EXHAUSTED_ERROR
    )
    if (!terminalPersisted) return

    durableLeaseId?.let { leaseId -> managedDownloadArtifactLeases.remove(songKey, leaseId) }
    forgetPendingDownloadQueueEntriesForOperation(
        context = context,
        songKey = songKey,
        operationId = request.operationId
    )
    updateTaskStatus(
        songKey = songKey,
        status = DownloadStatus.FAILED,
        expectedAttemptId = request.attemptId,
        operationId = request.operationId
    )
    NPLogger.w(
        TAG,
        "下载收尾连续失败，已停止自动重试并保留 core 音频供手动重试: " +
            "song=${song.name}, operationId=${request.operationId}, " +
            "attempts=${retryRecord.retryCount}"
    )
}

private suspend fun loadPostCoreDownloadRecoveryEntries(
    context: Context
): List<DownloadExecutionRoomStore.StateEntry>? {
    return try {
        DownloadExecutionRoomStore.listByStatesAnyLibrary(
            context = context,
            states = POST_CORE_DOWNLOAD_OPERATION_STATES,
            excludeUserStoppedOperations = true
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            GlobalDownloadManager.TAG,
            "读取持久收尾队列失败，保留下一次 Worker 重试: ${error.message}",
            error
        )
        null
    }
}

private fun classifyPostCoreDownloadRecovery(
    entries: Collection<DownloadExecutionRoomStore.StateEntry>,
    currentNetworkType: TrafficNetworkType?,
    mobileDataOverrideAllowed: Boolean
): PostCoreDownloadRecoveryResult {
    if (entries.isEmpty()) return PostCoreDownloadRecoveryResult.SETTLED
    val hasNetworkEligibleEntry = entries.any { entry ->
        isPostCoreRecoveryNetworkEligible(
            requiresWifiNetwork = entry.request.requiresWifiNetwork,
            currentNetworkType = currentNetworkType,
            mobileDataOverrideAllowed = mobileDataOverrideAllowed
        )
    }
    return if (hasNetworkEligibleEntry) {
        PostCoreDownloadRecoveryResult.RETRY
    } else {
        PostCoreDownloadRecoveryResult.WAITING_NETWORK
    }
}
