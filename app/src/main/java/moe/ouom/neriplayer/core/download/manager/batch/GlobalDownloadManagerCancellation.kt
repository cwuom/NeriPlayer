package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadTaskCancellationEntry
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadClearOwnershipCapture
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.CancellationConvergenceEntry
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.execution.DownloadClearOwnership
import moe.ouom.neriplayer.core.download.execution.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import java.io.File
import java.util.concurrent.atomic.AtomicInteger


internal fun GlobalDownloadManager.requestDownloadTaskCancellation(songKeys: Collection<String>): Job? {
    val appContext = AppContainer.applicationContext
    val requestedSongKeys = songKeys
        .asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (requestedSongKeys.isEmpty()) return null
    val tasksBySongKey = if (requestedSongKeys.size == 1) {
        val songKey = requestedSongKeys.single()
        mapOf(songKey to taskStore.findTask(songKey))
    } else {
        taskStore.currentTasks().associateBy { task -> task.song.stableKey() }
    }
    val cancellationKeys = requestedSongKeys.filter { songKey ->
        val task = tasksBySongKey[songKey]
        task == null || isDownloadTaskCancellationCandidate(task)
    }
    if (cancellationKeys.isEmpty()) return null
    cancellationKeys.forEach(::markSongCancelled)
    cancellationCleanupActiveSongKeys.addAll(cancellationKeys)
    val cancellationSnapshotAtMs = System.currentTimeMillis()
    cancellationKeys.forEach { songKey ->
        cancellationOperationSnapshotCutoffs.putIfAbsent(
            songKey,
            cancellationSnapshotAtMs
        )
    }
    // 先把旧 operation 身份放进独立快照 job，再让取消和新请求并发推进
    cancellationKeys.forEach { songKey ->
        val taskOperationId = tasksBySongKey[songKey]
            ?.progress
            ?.operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val hostOperationId = runCatching {
            DownloadExecutionHosts.default.operationIdForSong(
                context = appContext,
                songKey = songKey
            )
        }.getOrNull()
        val knownOperationId = taskOperationId ?: hostOperationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (taskOperationId != null) {
            cancellationOperationIdsBySongKey.merge(
                songKey,
                setOf(taskOperationId)
            ) { previous, current -> previous + current }
        }
        if (knownOperationId != null) {
            cancellationOperationIdsBySongKey.merge(
                songKey,
                setOf(knownOperationId)
            ) { previous, current -> previous + current }
        }
        requestCancellationOperationSnapshot(
            context = appContext,
            songKey = songKey,
            snapshotAtMs = cancellationSnapshotAtMs,
            knownOperationId = knownOperationId
        )
    }
    val invalidation = invalidateDownloadRequestGenerations(cancellationKeys)
    val cancellationEntries = cancellationKeys.map { songKey ->
        DownloadTaskCancellationEntry(
            songKey = songKey,
            task = tasksBySongKey[songKey],
            cancellationGeneration = invalidation.generationsBySongKey[songKey],
            operationIds = cancellationOperationIdsForSong(songKey)
        )
    }
    cancellationEntries.forEach { entry ->
        entry.task?.let { currentTask ->
            markBatchDownloadPresentationTerminal(
                songKey = entry.songKey,
                attemptId = currentTask.attemptId,
                terminalState = BatchDownloadTerminalState.CANCELLED,
                operationId = currentTask.progress?.operationId
            )
            removeDownloadTask(
                songKey = entry.songKey,
                expectedAttemptId = currentTask.attemptId
            )
        }
    }
    return scope.launch {
        cancelDownloadTasksDurably(
            context = appContext,
            entries = cancellationEntries
        )
    }
}

internal suspend fun GlobalDownloadManager.cancelDownloadTasksDurably(
    context: Context,
    entries: Collection<DownloadTaskCancellationEntry>
) {
    val logSettlement = entries.size == 1
    val failureCount = AtomicInteger(0)
    val failureLock = Any()
    var firstFailure: Exception? = null
    runBoundedDownloadCancellationCleanup(
        items = entries,
        parallelism = DOWNLOAD_CANCEL_CLEANUP_PARALLELISM
    ) { entry ->
        try {
            cancelDownloadTaskDurably(
                context = context,
                task = entry.task,
                cancellationGeneration = entry.cancellationGeneration,
                songKey = entry.songKey,
                operationIds = entry.operationIds,
                logSettlement = logSettlement
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failureCount.incrementAndGet()
            synchronized(failureLock) {
                if (firstFailure == null) firstFailure = error
            }
        }
    }
    if (failureCount.get() > 0) {
        NPLogger.e(
            TAG,
            "批量下载取消持久清理失败: songs=${failureCount.get()}, " +
                "total=${entries.size}",
            firstFailure
        )
    }
}

internal suspend fun GlobalDownloadManager.cancelDownloadTaskDurably(
    context: Context,
    task: DownloadTask?,
    cancellationGeneration: Long?,
    songKey: String,
    operationIds: Set<String> = emptySet(),
    logSettlement: Boolean = true
) {
    val snapshotBoundary = hasCancellationSnapshotBoundary(songKey)
    val currentOperationId = runCatching {
        DownloadExecutionHosts.default.operationIdForSong(
            context = context,
            songKey = songKey
        )
    }.getOrNull()
    val capturedOperationIds = operationIds.ifEmpty {
        captureCancellationOperationIds(
            context = context,
            songKey = songKey,
            // 快照边界建立后，当前宿主 ID 可能已经属于替代请求
            fallbackOperationId = currentOperationId.takeUnless { snapshotBoundary }
        )
    }
    if (capturedOperationIds.isNotEmpty()) {
        cancellationOperationIdsBySongKey[songKey] = capturedOperationIds
    }
    if (
        shouldRetainUnresolvedCancellationSnapshot(
            snapshotBoundary = snapshotBoundary,
            snapshotResolved = isCancellationSnapshotResolved(songKey),
            operationIds = capturedOperationIds
        )
    ) {
        NPLogger.d(
            TAG,
            "取消 operation 快照尚未解析，保留持久取消凭据等待收敛: " +
                "songKey=$songKey"
        )
        scheduleCancellationConvergence(
            context = context,
            songKey = songKey,
            cancellationGeneration = cancellationGeneration,
            operationIds = capturedOperationIds
        )
        return
    }
    if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
        return
    }
    val operationStore = DownloadExecutionOperationStore()
    val operationRequests = capturedOperationIds
        .mapNotNull { operationId ->
            operationStore.read(context, operationId)
                ?.takeIf { request -> request.song.stableKey() == songKey }
        }
        .distinctBy(DownloadExecutionRequest::operationId)
    val safeCurrentOperationId = currentOperationId
        ?.takeUnless { snapshotBoundary || capturedOperationIds.isNotEmpty() }
    if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
        return
    }
    if (capturedOperationIds.isNotEmpty()) {
        capturedOperationIds.forEach { id ->
            DownloadExecutionHosts.default.cancel(context, id)
        }
    } else if (safeCurrentOperationId != null) {
        DownloadExecutionHosts.default.cancel(context, safeCurrentOperationId)
    } else if (!snapshotBoundary) {
        DownloadExecutionHosts.default.cancelForSong(context, songKey)
    }
    if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
        return
    }
    requestOperationCancellation(setOf(songKey))
    if (task == null) {
        val settled = awaitSongCancellationSettled(
            songKey = songKey,
            timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS,
            clearCancellationWhenSettled = false,
            logProgress = logSettlement,
            operationIds = capturedOperationIds
        )
        if (!settled) {
            scheduleCancellationConvergence(
                context = context,
                songKey = songKey,
                cancellationGeneration = cancellationGeneration,
                operationIds = capturedOperationIds
            )
            return
        }
        if (isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            operationRequests.forEach { request ->
                releaseDownloadArtifactAfterExecutionOwnershipLoss(
                    context = context,
                    song = request.song,
                    operationId = request.operationId,
                    expectedLeaseId = request.artifactLeaseId
                )
            }
            if (operationRequests.isEmpty() &&
                !snapshotBoundary &&
                capturedOperationIds.isEmpty()
            ) {
                ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
                    context,
                    setOf(songKey)
                )
            }
            val rootCleanupSucceeded = operationRequests
                .map { request ->
                    cleanupCancelledPendingDownloadArtifacts(
                        context = context,
                        song = request.song,
                        operationId = request.operationId
                    )
                }
                .all { it }
            if (shouldPurgeCancelledDownloadOperation(
                    keepCancellationOperation = false,
                    cleanupSucceeded = rootCleanupSucceeded
                )
            ) {
                if (capturedOperationIds.isEmpty() && !snapshotBoundary) {
                    DownloadExecutionRoomStore.purgeCancelled(context, setOf(songKey))
                } else if (capturedOperationIds.isNotEmpty()) {
                    DownloadExecutionRoomStore.purgeCancelledOperationIds(
                        context = context,
                        operationIds = capturedOperationIds
                    )
                } else {
                    // 已解析的空快照没有可安全删除的旧 operation，
                    // 不能按 stableKey 清理可能属于替代请求的行
                    NPLogger.d(
                        TAG,
                        "取消收尾没有可删除的旧 operation: songKey=$songKey"
                    )
                }
            } else {
                NPLogger.w(
                    TAG,
                    "无任务卡片的取消清理未确认，保留 Room 凭据: songKey=$songKey"
                )
                scheduleCancellationConvergence(
                    context = context,
                    songKey = songKey,
                    cancellationGeneration = cancellationGeneration,
                    operationIds = capturedOperationIds
                )
                return
            }
            clearSongCancelled(songKey)
            if (capturedOperationIds.isEmpty()) {
                if (snapshotBoundary && !isCancellationSnapshotResolved(songKey)) {
                    scheduleCancellationConvergence(
                        context = context,
                        songKey = songKey,
                        cancellationGeneration = cancellationGeneration,
                        operationIds = capturedOperationIds
                    )
                } else {
                    clearCancellationTracking(songKey)
                }
            } else {
                scheduleCancellationConvergence(
                    context = context,
                    songKey = songKey,
                    cancellationGeneration = cancellationGeneration,
                    operationIds = capturedOperationIds
                )
            }
        }
        return
    }
    when {
        capturedOperationIds.isNotEmpty() ->
            ManagedDownloadStorage.removePendingDownloadQueueOperationIds(
                context = context,
                operationIds = capturedOperationIds
            )
        !snapshotBoundary -> forgetPendingDownloadQueueEntries(context, setOf(songKey))
        else -> NPLogger.d(
            TAG,
            "取消快照存在替代请求，跳过按歌曲移除持久队列: songKey=$songKey"
        )
    }
    cancelDownloadTaskInBackground(
        task = task,
        cancellationGeneration = cancellationGeneration,
        operationRequests = operationRequests,
        operationIds = capturedOperationIds,
        logSettlement = logSettlement
    )
}

internal fun GlobalDownloadManager.scheduleCancellationConvergence(
    context: Context,
    songKey: String,
    cancellationGeneration: Long?,
    operationIds: Set<String> = emptySet()
) {
    if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
        return
    }
    val appContext = context.applicationContext
    val targetOperationIds = operationIds.ifEmpty {
        cancellationOperationIdsForSong(songKey)
    }
    val convergenceJob = scope.launch(start = CoroutineStart.LAZY) {
        runCancellationConvergence(
            context = appContext,
            songKey = songKey,
            cancellationGeneration = cancellationGeneration,
            operationIds = targetOperationIds
        )
    }
    val shouldStart = synchronized(cancellationConvergenceJobs) {
        val previous = cancellationConvergenceJobs[songKey]
        if (previous?.let { entry ->
                entry.generation == cancellationGeneration &&
                    entry.operationIds == targetOperationIds &&
                    entry.job.isActive
            } == true
        ) {
            convergenceJob.cancel()
            false
        } else {
            previous?.job?.cancel()
            cancellationConvergenceJobs[songKey] = CancellationConvergenceEntry(
                generation = cancellationGeneration,
                operationIds = targetOperationIds,
                job = convergenceJob
            )
            true
        }
    }
    if (shouldStart) {
        convergenceJob.invokeOnCompletion {
            synchronized(cancellationConvergenceJobs) {
                val current = cancellationConvergenceJobs[songKey]
                if (current?.job == convergenceJob) {
                    cancellationConvergenceJobs.remove(songKey)
                }
            }
        }
        convergenceJob.start()
    }
}

internal suspend fun GlobalDownloadManager.runCancellationConvergence(
    context: Context,
    songKey: String,
    cancellationGeneration: Long?,
    operationIds: Set<String>
) {
    var targetOperationIds = operationIds
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    for (attemptIndex in 0 until DOWNLOAD_CANCEL_CONVERGENCE_MAX_ATTEMPTS) {
        if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            return
        }
        if (
            targetOperationIds.isEmpty() &&
                hasCancellationSnapshotBoundary(songKey) &&
                !isCancellationSnapshotResolved(songKey) &&
                cancellationOperationSnapshotJobs[songKey] == null
        ) {
            requestCancellationOperationSnapshot(
                context = context,
                songKey = songKey
            )
        }
        if (targetOperationIds.isEmpty()) {
            captureCancellationOperationIds(
                context = context,
                songKey = songKey
            ).takeIf(Set<String>::isNotEmpty)?.let { capturedIds ->
                targetOperationIds = capturedIds
            }
        }
        val snapshotBoundary = hasCancellationSnapshotBoundary(songKey)
        val snapshotResolved = isCancellationSnapshotResolved(songKey)
        if (
            shouldRetainUnresolvedCancellationSnapshot(
                snapshotBoundary = snapshotBoundary,
                snapshotResolved = snapshotResolved,
                operationIds = targetOperationIds
            )
        ) {
            NPLogger.d(
                TAG,
                "取消收敛等待 operation 快照: " +
                    "songKey=$songKey, attempt=${attemptIndex + 1}"
            )
            cancellationConvergenceDelayMs(attemptIndex + 1)?.let { delayMs ->
                delay(delayMs)
            }
            continue
        }
        runCatching {
            if (targetOperationIds.isEmpty()) {
                if (!snapshotBoundary) {
                    DownloadExecutionHosts.default.cancelForSong(context, songKey)
                }
            } else {
                targetOperationIds.forEach { operationId ->
                    DownloadExecutionHosts.default.cancel(context, operationId)
                    DownloadExecutionOperationStore().requestCancel(context, operationId)
                }
                AudioDownloadManager.cancelOperationDownload(songKey, targetOperationIds)
            }
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "取消收敛再次请求宿主失败: songKey=$songKey, " +
                    "attempt=${attemptIndex + 1}, error=${error.message}",
                error
            )
        }
        val settled = if (snapshotBoundary && targetOperationIds.isEmpty()) {
            !AudioDownloadManager.isSongDownloadActive(songKey)
        } else {
            awaitSongCancellationSettled(
                songKey = songKey,
                timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS,
                clearCancellationWhenSettled = false,
                logProgress = false,
                operationIds = targetOperationIds
            )
        }
        if (settled && isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            val operationRequests = runCatching {
                DownloadExecutionRoomStore.listCancellationCandidatesAnyLibrary(context)
                    .map(DownloadExecutionRoomStore.StateEntry::request)
                    .filter { request ->
                        request.song.stableKey() == songKey &&
                            (targetOperationIds.isNotEmpty() &&
                                request.operationId in targetOperationIds)
                    }
                    .distinctBy(DownloadExecutionRequest::operationId)
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "取消收敛读取持久 operation 失败，保留凭据: " +
                        "songKey=$songKey, error=${error.message}",
                    error
                )
                emptyList()
            }
            var cleanupSucceeded = true
            operationRequests.forEach { request ->
                if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
                    return
                }
                releaseDownloadArtifactAfterExecutionOwnershipLoss(
                    context = context,
                    song = request.song,
                    operationId = request.operationId,
                    expectedLeaseId = request.artifactLeaseId
                )
                cleanupSucceeded = cleanupCancelledDownloadArtifacts(
                    context = context,
                    song = request.song,
                    operationId = request.operationId,
                    keepCancellationOperation = true
                ) && cleanupSucceeded
            }
            if (operationRequests.isEmpty() &&
                targetOperationIds.isEmpty() &&
                !snapshotBoundary
            ) {
                ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
                    context,
                    setOf(songKey)
                )
            }
            if (cleanupSucceeded && isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
                DownloadExecutionRoomStore.finalizeRequestedCancellations(
                    context = context,
                    operationIds = operationRequests.map(DownloadExecutionRequest::operationId)
                )
                if (targetOperationIds.isEmpty() && !snapshotBoundary) {
                    DownloadExecutionRoomStore.purgeCancelled(context, setOf(songKey))
                } else if (targetOperationIds.isNotEmpty()) {
                    DownloadExecutionRoomStore.purgeCancelledOperationIds(
                        context = context,
                        operationIds = targetOperationIds
                    )
                } else {
                    NPLogger.d(
                        TAG,
                        "取消收敛缺少可删除的旧 operation，保留替代请求: " +
                            "songKey=$songKey"
                    )
                }
                clearSongCancelled(songKey)
                wakeDownloadExecutionPump(context, "single_cancel_converged")
                NPLogger.d(TAG, "取消收敛完成: songKey=$songKey")
                clearCancellationTracking(songKey, targetOperationIds)
                return
            }
        }
        val retryDelayMs = cancellationConvergenceDelayMs(attemptIndex + 1)
        if (retryDelayMs != null) {
            delay(retryDelayMs)
        }
    }
    NPLogger.w(
        TAG,
        "取消收敛达到有界重试上限，保留持久凭据等待下次启动或迁移恢复: " +
            "songKey=$songKey"
    )
    val remainingOperationIds = cancellationOperationIdsForSong(songKey)
    if (
        !hasCancellationSnapshotBoundary(songKey) &&
            remainingOperationIds.isEmpty()
    ) {
        clearCancellationTracking(songKey)
    } else {
        // 旧 operation 未确认收尾时不能丢掉身份，否则下一次恢复只能
        // 按 stableKey 猜测，可能取消新请求或留下旧 staging 文件
        requestOperationCancellation(setOf(songKey))
    }
    runCatching { ForegroundDownloadWorker.schedulePump(context) }
}

internal fun GlobalDownloadManager.requestAllDownloadTaskCancellation(
    purpose: DownloadClearPurpose = DownloadClearPurpose.TASK_PROGRESS,
    forceConvergence: Boolean = false
): Job {
    val appContext = AppContainer.applicationContext
    // 全库删除先落盘删除意图再建立栅栏，不能把意图本身当成已有栅栏，
    // 否则 requestAllDownloadTaskCancellation 会返回等待自身释放的 Job
    val hadPersistedClearFence = PersistentDownloadClearFenceStore.hasPersistedFence(
        appContext
    )
    if (hadPersistedClearFence && !forceConvergence) {
        val activePurpose = PersistentDownloadClearFenceStore.activePurpose(appContext)
        if (
            activePurpose == DownloadClearPurpose.FULL_LIBRARY_DELETE &&
                PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)
        ) {
            scheduleDeferredFullLibraryDeleteRecovery(appContext)
        } else {
            scheduleDeferredTaskClearRecovery(
                context = appContext,
                purpose = activePurpose
            )
        }
        NPLogger.d(
            TAG,
            "已有下载清空栅栏，复用当前恢复流程并等待完成: purpose=$activePurpose"
        )
        return scope.launch {
            awaitDownloadClearFenceRelease(appContext)
        }
    }
    val activeBatchJobsAtClearStart = activeBatchDownloadJobs.toSet()
    val persistedClearOwnership = if (hadPersistedClearFence) {
        PersistentDownloadClearFenceStore.ownership(appContext)
    } else {
        null
    }
    // 先 hydrate 单调持久 epoch，避免进程重启后新的内存清空回退到旧代次
    PersistentDownloadClearFenceStore.currentEpoch(appContext)
    val clearToken = downloadAdmissionGate.beginClear()
    clearAllLatestProgress()
    val preClearTasks = taskStore.currentTasks()
    val initialClearItemCount = maxOf(
        preClearTasks.size,
        persistedClearOwnership?.stableKeys?.size ?: 0
    )
    // 先发布高水位，再切换任务展示，避免页面出现 0% (0项)
    downloadClearVisibility.begin(
        token = clearToken,
        affectedItemCount = initialClearItemCount,
        totalItemCount = 0,
        purpose = purpose
    )
    // 清空代次先锁住任务存储，异步清理启动前也不会让旧回调重新建卡片
    val taskPresentationToken = taskStore.beginClearPresentation(
        cleanupStableKeys = persistedClearOwnership?.stableKeys
    )
    var clearOwnerStableKeys = taskPresentationToken.blockedStableKeys.toSet()
    val clearOwnership = if (purpose == DownloadClearPurpose.TASK_PROGRESS) {
        DownloadClearOwnership(
            operationIds = persistedClearOwnership?.operationIds.orEmpty(),
            stableKeys = clearOwnerStableKeys
        )
    } else {
        null
    }
    val clearFenceEpoch = if (clearToken.ownsClear) {
        PersistentDownloadClearFenceStore.beginClear(
            purpose = purpose,
            ownership = clearOwnership
        )
    } else {
        null
    }
    dismissMobileDataDownloadInterruptionRequest()
    if (!clearToken.ownsClear) {
        return scope.launch {
            downloadAdmissionGate.awaitClear(clearToken)
        }
    }
    var preClearedTasks: List<DownloadTask>? = taskPresentationToken.visibleTasks
    return scope.launch {
        // 持久化、Room 和 Provider 工作都在下载管理器后台调度器执行
        // 持久栅栏必须在后台确认成功后才推进取消阶段
        val fenceActivatedImmediately = PersistentDownloadClearFenceStore.activate(
            context = appContext,
            ownership = clearOwnership
        )
        val startFastClearUndispatched =
            purpose == DownloadClearPurpose.TASK_PROGRESS &&
            !forceConvergence &&
            fenceActivatedImmediately
        if (fenceActivatedImmediately && !hadPersistedClearFence) {
            // 栅栏确认后立即保存高水位，进程被回收时页面仍能恢复细分进度
            downloadClearVisibility.markFencePersisted(clearToken)
            persistDownloadClearProgress(appContext, clearToken)
        }
        var retainClearVisibility = false
        var detachedFastTaskClear = false
        try {
            if (startFastClearUndispatched) {
                // 任务展示清空也要和启动恢复共用同一把锁，避免旧快照在清空后回填
                downloadAdmissionGate.runClear(clearToken) {
                    val visibleTasks = preClearedTasks ?: taskStore.currentTasks()
                    preClearedTasks = visibleTasks
                    cancelBatchDownloadJobsForClear(
                        batchJobs = activeBatchJobsAtClearStart,
                        reason = "cancel all download tasks"
                    )
                    clearBatchDownloadPresentation()
                    taskStore.clearAllTasks()
                    downloadClearVisibility.markFencePersisted(clearToken)
                    downloadClearVisibility.update(
                        token = clearToken,
                        phase = DownloadClearVisibility.ClearPhase.CLEANING,
                        completedSteps = 2,
                        affectedItemCount = visibleTasks.size,
                        failedItemCount = 0,
                        completedItemCount = 0,
                        totalItemCount = maxOf(
                            visibleTasks.size,
                            clearOwnerStableKeys.size
                        )
                    )
                    // 交互路径只需写入一次持久进度，详细 Provider 清理不在这里执行
                    persistDownloadClearProgress(appContext, clearToken)
                }
            }
            if (!startFastClearUndispatched) {
                if (!hadPersistedClearFence) {
                    clearPersistedDownloadClearProgress(appContext)
                } else {
                    restoreDownloadClearProgress(appContext, clearToken)
                }
                persistDownloadClearProgress(appContext, clearToken)
            }
            val fenceActivated = fenceActivatedImmediately ||
                activateDownloadClearFence(appContext)
            if (!fenceActivated) {
                val requestAbandoned = clearFenceEpoch?.let { epoch ->
                    PersistentDownloadClearFenceStore.abandonUnpersistedRequestIfCurrent(
                        context = appContext,
                        expectedEpoch = epoch
                    )
                } == true
                if (requestAbandoned) {
                    clearPersistedDownloadClearProgress(appContext)
                    NPLogger.e(
                        TAG,
                        "下载清空栅栏未能持久化，回收未落盘请求并保留任务"
                    )
                } else {
                    retainClearVisibility = true
                    downloadClearVisibility.update(
                        token = clearToken,
                        phase = DownloadClearVisibility.ClearPhase.CANCELLING,
                        completedSteps = 0,
                        affectedItemCount = taskStore.currentTasks().size,
                        failedItemCount = 1,
                        completedItemCount = 0,
                        totalItemCount = 1
                    )
                    persistDownloadClearProgress(appContext, clearToken)
                    NPLogger.e(
                        TAG,
                        "下载清空栅栏未能持久化，未删除任务或文件并等待下次恢复"
                    )
                }
                // 持久栅栏未确认时释放本进程闸门，让显式重试可以取得新的 owner
                downloadAdmissionGate.releaseFailedClear(clearToken)
                return@launch
            }
            val batchClearCapture = try {
                withDownloadClearRoomTimeout(
                    operation = "mark download batches clearing"
                ) {
                    DownloadExecutionRoomStore.beginBatchClear(
                        context = appContext,
                        clearEpoch = requireNotNull(clearFenceEpoch)
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                retainClearVisibility = true
                if (purpose == DownloadClearPurpose.TASK_PROGRESS) {
                    scheduleDeferredTaskClearRecovery(
                        context = appContext,
                        purpose = purpose
                    )
                } else {
                    scheduleDeferredFullLibraryDeleteRecovery(appContext)
                }
                NPLogger.w(
                    TAG,
                    "下载批次进入 CLEARING 失败，保留 durable fence 等待恢复: " +
                        error.message,
                    error
                )
                return@launch
            }
            if (batchClearCapture.stableKeys.isNotEmpty()) {
                clearOwnerStableKeys = clearOwnerStableKeys + batchClearCapture.stableKeys
                taskStore.addClearPresentationOwnership(
                    token = taskPresentationToken,
                    stableKeys = batchClearCapture.stableKeys
                )
            }
            if (purpose == DownloadClearPurpose.TASK_PROGRESS) {
                scheduleTaskClearHardDeadline(appContext)
            }
            downloadClearVisibility.markFencePersisted(clearToken)
            if (!startFastClearUndispatched) {
                persistDownloadClearProgress(appContext, clearToken)
                downloadClearVisibility.update(
                    token = clearToken,
                    phase = DownloadClearVisibility.ClearPhase.CANCELLING,
                    completedSteps = 1,
                    affectedItemCount = preClearedTasks?.size
                        ?: taskStore.currentTasks().size
                )
                persistDownloadClearProgress(appContext, clearToken)
                clearBatchDownloadPresentation()
            }
            var capturedClearOwnership: DownloadClearOwnershipCapture? = null
            if (
                purpose == DownloadClearPurpose.TASK_PROGRESS &&
                    !PersistentDownloadClearFenceStore.isOwnershipCaptureComplete(appContext)
            ) {
                val clearStartedAtMs = PersistentDownloadClearFenceStore.requestedAtMs(
                    appContext
                ) ?: System.currentTimeMillis()
                val capture = try {
                    // owner 捕获属于 durable 收敛，使用独立的 Room 上限而非交互预算
                    withDownloadClearRoomTimeout(
                        operation = "capture download clear ownership"
                    ) {
                        captureDownloadClearOwnership(
                            context = appContext,
                            clearStartedAtMs = clearStartedAtMs
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "下载清空 owner 快照读取失败，保留栅栏等待重试: " +
                            error.message,
                        error
                    )
                    null
                }
                val captureWithBatchOwners = capture?.let { ownerCapture ->
                    ownerCapture.copy(
                        operationIdentities = (
                            ownerCapture.operationIdentities +
                                batchClearCapture.operationIdentities
                            ).distinctBy(
                                DownloadExecutionRoomStore.OperationIdentity::operationId
                            )
                    )
                }
                if (captureWithBatchOwners == null) {
                    retainClearVisibility = true
                    val currentProgress = downloadClearVisibility.progress.value
                    val captureProgressTotal = maxOf(
                        currentProgress?.totalItemCount ?: 0,
                        clearOwnerStableKeys.size,
                        preClearedTasks?.size ?: 0,
                        1
                    )
                    downloadClearVisibility.update(
                        token = clearToken,
                        phase = DownloadClearVisibility.ClearPhase.CLEANING,
                        completedSteps = 2,
                        affectedItemCount = maxOf(
                            clearOwnerStableKeys.size,
                            preClearedTasks?.size ?: 0,
                            currentProgress?.affectedItemCount ?: 0
                        ),
                        failedItemCount = 1,
                        completedItemCount = 0,
                        totalItemCount = captureProgressTotal
                    )
                    persistDownloadClearProgress(appContext, clearToken)
                    scheduleDeferredTaskClearRecovery(
                        context = appContext,
                        purpose = DownloadClearPurpose.TASK_PROGRESS
                    )
                    NPLogger.w(
                        TAG,
                        "下载清空 owner 快照未完成，保持持久栅栏后台重试"
                    )
                    return@launch
                }
                capturedClearOwnership = captureWithBatchOwners
                val capturedStableKeys = clearOwnerStableKeys + captureWithBatchOwners.stableKeys
                clearOwnerStableKeys = capturedStableKeys
                taskStore.addClearPresentationOwnership(
                    token = taskPresentationToken,
                    stableKeys = capturedStableKeys
                )
                val ownershipPersisted = clearFenceEpoch?.let { epoch ->
                    PersistentDownloadClearFenceStore.setOwnership(
                        context = appContext,
                        expectedEpoch = epoch,
                        ownership = DownloadClearOwnership(
                            operationIds = (
                                persistedClearOwnership?.operationIds.orEmpty() +
                                    captureWithBatchOwners.operationIds
                                ).toSet(),
                            stableKeys = capturedStableKeys
                        )
                    )
                } ?: false
                if (!ownershipPersisted) {
                    retainClearVisibility = true
                    val currentProgress = downloadClearVisibility.progress.value
                    val captureProgressTotal = maxOf(
                        currentProgress?.totalItemCount ?: 0,
                        clearOwnerStableKeys.size,
                        preClearedTasks?.size ?: 0,
                        1
                    )
                    downloadClearVisibility.update(
                        token = clearToken,
                        phase = DownloadClearVisibility.ClearPhase.CLEANING,
                        completedSteps = 2,
                        affectedItemCount = maxOf(
                            clearOwnerStableKeys.size,
                            preClearedTasks?.size ?: 0,
                            currentProgress?.affectedItemCount ?: 0
                        ),
                        failedItemCount = 1,
                        completedItemCount = 0,
                        totalItemCount = captureProgressTotal
                    )
                    persistDownloadClearProgress(appContext, clearToken)
                    scheduleDeferredTaskClearRecovery(
                        context = appContext,
                        purpose = DownloadClearPurpose.TASK_PROGRESS
                    )
                    NPLogger.w(
                        TAG,
                        "下载清空 owner 快照未能持久化，保持栅栏后台重试"
                    )
                    return@launch
                }
            }
            val initiallyVisibleTasks = preClearedTasks ?: taskStore.currentTasks()
            val initiallyCancellationTasks = initiallyVisibleTasks
                .filter(::isDownloadTaskCancellationCandidate)
            if (!startFastClearUndispatched) {
                taskStore.clearAllTasks()
            }
            if (
                purpose == DownloadClearPurpose.TASK_PROGRESS &&
                    PersistentDownloadClearFenceStore
                        .isOwnershipCaptureComplete(appContext)
            ) {
                // owner 已持久化且任务展示已切走后，旧任务只能通过持久 owner
                // 收敛。不能让它们的 Provider I/O 继续阻止其他 stableKey 启动
                if (downloadAdmissionGate.detachClearForDurableRecovery(clearToken)) {
                    detachedFastTaskClear = true
                    NPLogger.d(
                        TAG,
                        "下载清空已脱离内存 gate，后台仅收敛已捕获 owner"
                    )
                }
            }
            if (purpose == DownloadClearPurpose.TASK_PROGRESS && !forceConvergence) {
                val fastPhaseCompleted = try {
                    runFastTaskClearPhase(
                        context = appContext,
                        token = clearToken,
                        visibleTasks = initiallyVisibleTasks,
                        ownerStableKeys = clearOwnerStableKeys,
                        persistProgress = !startFastClearUndispatched
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "下载清空快速阶段异常，转入持久收敛: ${error.message}",
                        error
                    )
                    false
                }
                if (fastPhaseCompleted) {
                    detachedFastTaskClear = true
                    // 分离的清理 Worker 还在确认没有活动临时产物，期间持久栅栏继续生效
                    scheduleDeferredTaskClearRecovery(
                        context = appContext,
                        purpose = DownloadClearPurpose.TASK_PROGRESS
                    )
                    downloadAdmissionGate.releaseFailedClear(clearToken)
                    return@launch
                }
                NPLogger.w(
                    TAG,
                    "下载清空快速阶段未完成，转入有界收敛流程"
                )
            }
            var clearDeferredForRetry = false
            downloadAdmissionGate.runClear(clearToken) {
                val cancellationTasksBySongKey = linkedMapOf<String, DownloadTask>()
                val clearOperationIds = linkedSetOf<String>().apply {
                    addAll(persistedClearOwnership?.operationIds.orEmpty())
                    addAll(capturedClearOwnership?.operationIds.orEmpty())
                    addAll(batchClearCapture.operationIdentities.map { it.operationId })
                }
                val clearOperationRequests = linkedMapOf<String, DownloadExecutionRequest>()
                // operation payload 在轻量 owner 捕获后按 operationId 分批读取，
                // 避免清空开始时一次性解析全部 sourceHintJson
                val clearSongKeys = linkedSetOf<String>()
                val clearWorkingFilesBySongKey = linkedMapOf<String, MutableSet<File>>()
                capturedClearOwnership?.pendingWorkingDownloads?.forEach { pending ->
                    val songKey = pending.song.stableKey()
                    clearWorkingFilesBySongKey
                        .getOrPut(songKey) { linkedSetOf() }
                        .add(pending.workingFile)
                }
                val clearBatchJobs = activeBatchJobsAtClearStart.toMutableSet()
                val cancellationGenerations = linkedMapOf<String, Long?>()
                var initialHostCancellationSnapshotCaptured = false
                var clearConvergenceRound = 0

                fun deferClearForRetry(
                    reason: String,
                    failedItemCount: Int
                ) {
                    clearDeferredForRetry = true
                    val previousProgress = downloadClearVisibility.progress.value
                    val totalItems = maxOf(
                        previousProgress?.totalItemCount ?: 0,
                        failedItemCount.coerceAtLeast(1)
                    )
                    val completedItems = maxOf(
                        previousProgress?.completedItemCount ?: 0,
                        (totalItems - failedItemCount).coerceAtLeast(0)
                    ).coerceAtMost(totalItems)
                    downloadClearVisibility.update(
                        token = clearToken,
                        phase = DownloadClearVisibility.ClearPhase.CLEANING,
                        completedSteps = 2,
                        affectedItemCount = maxOf(
                            clearSongKeys.size,
                            previousProgress?.affectedItemCount ?: 0
                        ),
                        failedItemCount = failedItemCount.coerceAtMost(totalItems),
                        completedItemCount = completedItems,
                        totalItemCount = totalItems
                    )
                    persistDownloadClearProgress(appContext, clearToken)
                    NPLogger.e(
                        TAG,
                        "下载清空暂未收敛，达到本进程最大重试次数，保留持久栅栏等待恢复: " +
                            "round=$clearConvergenceRound, failed=$failedItemCount, " +
                            "reason=$reason"
                    )
                }

                while (true) {
                    if (!PersistentDownloadClearFenceStore.isTaskClearActive(appContext)) {
                        return@runClear
                    }
                    try {
                        clearSongKeys += clearOwnerStableKeys
                        val lateVisibleTasks = taskStore.currentTasks().filter { task ->
                            task.song.stableKey() in clearOwnerStableKeys
                        }
                        val lateCancellationTasks = lateVisibleTasks
                            .filter(::isDownloadTaskCancellationCandidate)
                        (initiallyCancellationTasks + lateCancellationTasks).forEach { task ->
                            cancellationTasksBySongKey[task.song.stableKey()] = task
                        }
                        val cancellationTasks = cancellationTasksBySongKey.values.toList()
                        taskStore.clearAllTasks()
                        val visibleSongKeys = (initiallyVisibleTasks + lateVisibleTasks)
                            .mapTo(linkedSetOf()) { task -> task.song.stableKey() }
                        val allOperationIdentities = if (
                            initialHostCancellationSnapshotCaptured ||
                                capturedClearOwnership != null
                        ) {
                            emptyList()
                        } else {
                            withDownloadClearRoomTimeout(
                                operation = "list operation identities for clear"
                            ) {
                                DownloadExecutionRoomStore.listOperationIdentitiesForStableKeys(
                                    context = appContext,
                                    stableKeys = clearOwnerStableKeys
                                )
                            }
                        }
                        val newlyDiscoveredOperationIds = linkedSetOf<String>()
                        allOperationIdentities.forEach { identity ->
                            if (clearOperationIds.add(identity.operationId)) {
                                newlyDiscoveredOperationIds += identity.operationId
                            }
                            clearSongKeys += identity.stableKey
                        }
                        if (
                            !initialHostCancellationSnapshotCaptured &&
                                !PersistentDownloadClearFenceStore
                                    .isOwnershipCaptureComplete(appContext)
                        ) {
                            clearFenceEpoch?.let { epoch ->
                                PersistentDownloadClearFenceStore.setOwnership(
                                    context = appContext,
                                    expectedEpoch = epoch,
                                    ownership = DownloadClearOwnership(
                                        operationIds = clearOperationIds,
                                        stableKeys = clearOwnerStableKeys
                                    )
                                )
                            }
                        }
                        val pendingWorkingDownloads = ManagedDownloadStorage
                            .listPendingResumableDownloads(appContext)
                            .filter { pendingDownload ->
                                pendingDownload.song.stableKey() in clearOwnerStableKeys
                            }
                        pendingWorkingDownloads.forEach { pendingDownload ->
                            val songKey = pendingDownload.song.stableKey()
                            clearSongKeys += songKey
                            clearWorkingFilesBySongKey
                                .getOrPut(songKey) { linkedSetOf() }
                                .add(pendingDownload.workingFile)
                        }
                        val cancellationSnapshot = requestAllDownloadOperationCancellation(
                            context = appContext,
                            operationIds = clearOperationIds
                        )
                        val cancellationCandidates = cancellationSnapshot.entries
                        cancellationSnapshot.operationIds.forEach { operationId ->
                            if (clearOperationIds.add(operationId)) {
                                newlyDiscoveredOperationIds += operationId
                            }
                        }
                        cancellationCandidates.forEach { entry ->
                            clearOperationRequests[entry.request.operationId] = entry.request
                        }
                        // 快速阶段已把可取消任务写为 CANCEL_REQUESTED，跨提交边界的
                        // 任务则只保留用户停止标记。两者都不会再次出现在取消快照里，
                        // 但仍需带着 lease 身份进入后台收敛，避免下一次下载被旧 claim 阻塞
                        withDownloadClearRoomTimeout(
                            operation = "read clear operation snapshots"
                        ) {
                            DownloadExecutionRoomStore.readOperationSnapshots(
                                context = appContext,
                                operationIds = newlyDiscoveredOperationIds
                            )
                        }.values
                            .filter { snapshot ->
                                snapshot.state == "CANCEL_REQUESTED" ||
                                    snapshot.state in CLEAR_LEASE_RELEASE_OPERATION_STATES
                            }
                            .forEach { snapshot ->
                                clearOperationRequests.putIfAbsent(
                                    snapshot.request.operationId,
                                    snapshot.request
                                )
                        }
                        clearSongKeys += visibleSongKeys
                        clearSongKeys += cancellationSnapshot.stableKeys
                        if (!initialHostCancellationSnapshotCaptured) {
                            // 在首次 Provider 扫描前先写入真实高水位，避免长扫描期间
                            // 页面一直显示 0% (0 项) 而被误判成卡死
                            val initialProgressTotal = maxOf(
                                clearSongKeys.size,
                                initiallyVisibleTasks.size,
                                1
                            )
                            downloadClearVisibility.update(
                                token = clearToken,
                                phase = DownloadClearVisibility.ClearPhase.CLEANING,
                                completedSteps = 2,
                                affectedItemCount = clearSongKeys.size,
                                failedItemCount = 0,
                                completedItemCount = 0,
                                totalItemCount = initialProgressTotal
                            )
                            persistDownloadClearProgress(appContext, clearToken)
                        }
                        val newlyDiscoveredSongKeys = clearSongKeys.filterTo(linkedSetOf()) { songKey ->
                            songKey !in cancellationGenerations
                        }
                        newlyDiscoveredSongKeys.forEach(::markSongCancelled)
                        val newlyDiscoveredCancellation =
                            invalidateDownloadRequestGenerations(newlyDiscoveredSongKeys)
                        val newlyDiscoveredGenerations =
                            newlyDiscoveredCancellation.generationsBySongKey
                        newlyDiscoveredGenerations.forEach { (songKey, generation) ->
                            cancellationGenerations.putIfAbsent(songKey, generation)
                        }
                        clearPendingDownloadQueue(
                            context = appContext,
                            stableKeys = clearOwnerStableKeys
                        )
                        if (!initialHostCancellationSnapshotCaptured) {
                            stopDownloadExecutionImmediately(
                                context = appContext,
                                reason = "durable download cancellation recorded",
                                stableKeys = clearOwnerStableKeys,
                                operationIds = clearOperationIds
                            )
                        } else if (newlyDiscoveredOperationIds.isNotEmpty()) {
                            DownloadExecutionHosts.default.cancelAll(
                                context = appContext,
                                operationIds = newlyDiscoveredOperationIds
                            )
                        }
                        initialHostCancellationSnapshotCaptured = true
                        val finalizedCancellationCount =
                            withDownloadClearRoomTimeout(
                                operation = "finalize clear cancellations"
                            ) {
                                DownloadExecutionRoomStore.finalizeRequestedCancellations(
                                    context = appContext,
                                    operationIds = clearOperationIds
                                )
                            }
                        cancelBatchDownloadJobsForClear(
                            batchJobs = clearBatchJobs,
                            reason = "cancel all download tasks"
                        )
                        clearSongKeys.forEach(AudioDownloadManager::cancelSongDownload)
                        taskStore.clearAllTasks()
                        downloadClearVisibility.update(
                            token = clearToken,
                            phase = DownloadClearVisibility.ClearPhase.CLEANING,
                            completedSteps = 2,
                            affectedItemCount = clearSongKeys.size
                        )
                        persistDownloadClearProgress(appContext, clearToken)
                        NPLogger.d(
                            TAG,
                            "取消全部下载任务: memory=${cancellationTasks.size}, " +
                                "operations=${cancellationCandidates.size}, " +
                                "finalized=$finalizedCancellationCount, " +
                                "batchJobs=${clearBatchJobs.size}"
                        )
                        val settlement = cancelDownloadTasksInBackground(
                            context = appContext,
                            tasks = cancellationTasks,
                            batchJobs = clearBatchJobs,
                            additionalSongKeys = clearSongKeys,
                            cancelledSongKeysSnapshot = clearSongKeys,
                            cancellationGenerations = cancellationGenerations,
                            operationRequests = clearOperationRequests.values,
                            executionOperationIds = clearOperationIds,
                            workingFilesBySongKey = clearWorkingFilesBySongKey,
                            clearToken = clearToken
                        )
                        updateDownloadClearSettlementProgress(
                            context = appContext,
                            token = clearToken,
                            affectedItemCount = clearSongKeys.size,
                            settlement = settlement
                        )
                        if (!settlement.isSettled) {
                            if (settlement.providerCleanupInFlight) {
                                deferClearForRetry(
                                    reason = "provider_cleanup_in_flight",
                                    failedItemCount = settlement.residualPendingArtifactCount
                                        .coerceAtLeast(1)
                                )
                                when (purpose) {
                                    DownloadClearPurpose.TASK_PROGRESS -> {
                                        scheduleDeferredTaskClearRecovery(
                                            context = appContext,
                                            purpose = purpose
                                        )
                                    }

                                    DownloadClearPurpose.FULL_LIBRARY_DELETE -> {
                                        scheduleDeferredFullLibraryDeleteRecovery(appContext)
                                    }
                                }
                                return@runClear
                            }
                            clearConvergenceRound += 1
                            NPLogger.w(
                                TAG,
                                "下载清空仍在等待执行收敛: activeSongs=" +
                                    "${settlement.activeSongKeys.size}, activeOperations=" +
                                    "${settlement.activeOperationIds.size}, " +
                                    "batchJobsSettled=${settlement.batchJobsSettled}, " +
                                    "residualWorking=${settlement.residualWorkingSongKeys.size}, " +
                                    "residualPending=" +
                                    settlement.residualPendingArtifactCount
                            )
                            if (shouldDeferDownloadClearAfterConvergenceRound(clearConvergenceRound)) {
                                deferClearForRetry(
                                    reason = "residual_artifacts_or_active_execution",
                                    failedItemCount = (
                                        settlement.residualPendingArtifactCount +
                                            settlement.residualWorkingSongKeys.size +
                                            settlement.activeSongKeys.size +
                                            settlement.activeOperationIds.size
                                        ).coerceAtLeast(1)
                                )
                                return@runClear
                            }
                            taskStore.clearAllTasks()
                            stopDownloadExecutionImmediately(
                                context = appContext,
                                reason = "waiting for download clear convergence",
                                stableKeys = clearOwnerStableKeys,
                                operationIds = clearOperationIds
                            )
                            delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                            continue
                        }
                        downloadClearVisibility.update(
                            token = clearToken,
                            phase = DownloadClearVisibility.ClearPhase.PURGING,
                            completedSteps = 3,
                            affectedItemCount = clearSongKeys.size
                        )
                        persistDownloadClearProgress(appContext, clearToken)
                        val deletedOperationCount =
                            withDownloadClearRoomTimeout(
                                operation = "purge cleared download operations"
                            ) {
                                DownloadExecutionRoomStore.purgeFullyClearedOperations(
                                    context = appContext,
                                    operationIds = clearOperationIds
                                )
                            }
                        clearSongKeys.forEach(::clearSongCancelled)
                        NPLogger.d(
                            TAG,
                            "下载清空已物理移除持久任务: operations=$deletedOperationCount, " +
                                "songs=${clearSongKeys.size}"
                        )
                        taskStore.clearAllTasks()
                        // 3/4 表示文件与 Room 已经清理完成。4/4 必须等 durable fence
                        // 真正释放后再发布，避免留下“4/4 + fence 仍激活”的持久死状态
                        return@runClear
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (timeout: DownloadClearRoomTimeoutException) {
                        deferClearForRetry(
                            reason = "room_timeout",
                            failedItemCount = clearSongKeys.size.coerceAtLeast(1)
                        )
                        if (purpose == DownloadClearPurpose.TASK_PROGRESS) {
                            scheduleDeferredTaskClearRecovery(
                                context = appContext,
                                purpose = purpose
                            )
                        } else {
                            scheduleDeferredFullLibraryDeleteRecovery(appContext)
                        }
                        NPLogger.w(
                            TAG,
                            "下载清空 Room 查询超时，保留 durable fence 等待后台恢复: " +
                                timeout.message
                        )
                        return@runClear
                    } catch (error: Exception) {
                        clearConvergenceRound += 1
                        val failureReason = if (error is java.io.IOException) {
                            "io_exception"
                        } else {
                            "exception"
                        }
                        NPLogger.e(
                            TAG,
                            "下载清空流程失败，保持栅栏并重试: ${error.message}",
                            error
                        )
                        if (shouldDeferDownloadClearAfterConvergenceRound(clearConvergenceRound)) {
                            deferClearForRetry(
                                reason = failureReason,
                                failedItemCount = clearSongKeys.size.coerceAtLeast(1)
                            )
                            return@runClear
                        }
                        taskStore.clearAllTasks()
                        stopDownloadExecutionImmediately(
                            context = appContext,
                            reason = "retrying failed download clear",
                            stableKeys = clearOwnerStableKeys,
                            operationIds = clearOperationIds
                        )
                        delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                    }
                }
            }
            if (clearDeferredForRetry) {
                retainClearVisibility = true
                NPLogger.w(
                    TAG,
                    "下载清空本轮已退出，持久栅栏保持生效，等待下次启动或显式重试"
                )
                return@launch
            }
            val batchesFinalized = try {
                withDownloadClearRoomTimeout(
                    operation = "finalize cleared download batches"
                ) {
                    DownloadExecutionRoomStore.finalizeBatchClear(
                        context = appContext,
                        identities = batchClearCapture.identities
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "下载批次终态收敛失败，保留 durable fence 等待恢复: " +
                        error.message,
                    error
                )
                false
            }
            if (!batchesFinalized) {
                retainClearVisibility = true
                if (purpose == DownloadClearPurpose.TASK_PROGRESS) {
                    scheduleDeferredTaskClearRecovery(
                        context = appContext,
                        purpose = purpose
                    )
                } else {
                    scheduleDeferredFullLibraryDeleteRecovery(appContext)
                }
                return@launch
            }
            val fenceReleased = clearDownloadClearFence(
                context = appContext,
                expectedEpoch = requireNotNull(clearFenceEpoch)
            )
            if (!fenceReleased) {
                retainClearVisibility = true
                val previousProgress = downloadClearVisibility.progress.value
                val affectedItemCount = maxOf(
                    previousProgress?.affectedItemCount ?: 0,
                    preClearedTasks?.size ?: 0,
                    clearOwnerStableKeys.size
                )
                val totalItemCount = maxOf(
                    previousProgress?.totalItemCount ?: 0,
                    affectedItemCount,
                    1
                )
                val failedItemCount = maxOf(
                    previousProgress?.failedItemCount ?: 0,
                    1
                ).coerceAtMost(totalItemCount)
                downloadClearVisibility.update(
                    token = clearToken,
                    phase = DownloadClearVisibility.ClearPhase.CLEANING,
                    completedSteps = 3,
                    affectedItemCount = affectedItemCount,
                    failedItemCount = failedItemCount,
                    completedItemCount = minOf(
                        previousProgress?.completedItemCount ?: 0,
                        (totalItemCount - failedItemCount).coerceAtLeast(0)
                    ),
                    totalItemCount = totalItemCount
                )
                persistDownloadClearProgress(appContext, clearToken)
                NPLogger.e(
                    TAG,
                    "下载清空栅栏释放未确认，保留持久进度并等待下次恢复"
                )
                return@launch
            }
            val finalProgress = downloadClearVisibility.progress.value
            val finalItemCount = finalProgress?.totalItemCount ?: 0
            downloadClearVisibility.update(
                token = clearToken,
                phase = DownloadClearVisibility.ClearPhase.PURGING,
                completedSteps = 4,
                affectedItemCount = finalProgress?.affectedItemCount
                    ?: preClearedTasks?.size
                    ?: 0,
                failedItemCount = 0,
                completedItemCount = finalItemCount,
                totalItemCount = finalItemCount
            )
            // 4/4 只保留在内存里用于本帧收尾，不能再持久化；finally 会立即结束横幅
            clearPersistedDownloadClearProgress(appContext)
            if (purpose == DownloadClearPurpose.TASK_PROGRESS) {
                // 清空期间被取消的 core 收尾必须在栅栏释放后重新接管
                scheduleStartupArtifactRecovery(appContext)
            }
            // 栅栏释放后只登记一次后台对账，清空交互路径不等待目录扫描
            scheduleCatalogReconcile(appContext, forceRefresh = true)
        } finally {
            // 任务清空横幅只归 TASK/FULL_LIBRARY 的持久 task fence 所有。
            // 全库删除的 delete intent 还在时可以继续阻断新下载，但不能让已经完成的
            // “清空下载任务 4/4”横幅永久挂住。
            val durableTaskClearFenceActive =
                PersistentDownloadClearFenceStore.isTaskProgressActive(appContext)
            if (detachedFastTaskClear && durableTaskClearFenceActive) {
                // 后台任务清空还未确认完成，保留横幅阻止旧任务快照重新出现
                persistDownloadClearProgress(appContext, clearToken)
            } else if (shouldRetainDownloadClearVisibility(
                    retainInMemoryState = retainClearVisibility,
                    durableFenceActive = durableTaskClearFenceActive
                )
            ) {
                persistDownloadClearProgress(appContext, clearToken)
            } else {
                downloadClearVisibility.finish(clearToken)
            }
            if (!durableTaskClearFenceActive) {
                taskStore.finishClearPresentation(taskPresentationToken) {
                    !PersistentDownloadClearFenceStore.isTaskProgressActive(appContext) &&
                        downloadAdmissionGate.openTicketOrNull() == clearToken.generation
                }
            }
            // 快速阶段可能早于 runClear 取得互斥锁，释放仍活跃的 owner 会挡住后续请求
            downloadAdmissionGate.releaseFailedClear(clearToken)
            wakeDownloadExecutionPump(appContext, "download_clear_released")
        }
    }
}
