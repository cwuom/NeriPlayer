package moe.ouom.neriplayer.core.download.manager.batch

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isWifiBoundNetworkPolicyStillRequired
import moe.ouom.neriplayer.core.download.manager.admission.mutateWifiBoundNetworkPolicyIfStillRequired
import moe.ouom.neriplayer.core.download.manager.admission.promoteWaitingStorageMutationsForRecovery
import moe.ouom.neriplayer.core.download.manager.catalog.cancelScheduledDownloadedSongsCatalogPersist
import moe.ouom.neriplayer.core.download.manager.catalog.hasBlockingActiveDownloadOperationsForRecovery
import moe.ouom.neriplayer.core.download.manager.catalog.persistConfirmedEmptyDownloadedSongsCatalog
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.releaseDownloadArtifactAfterExecutionOwnershipLoss
import moe.ouom.neriplayer.core.download.manager.catalog.waitForActiveDownloadJobsToSettle
import moe.ouom.neriplayer.core.download.manager.catalog.waitForQueuedTasksToAttachToBatch
import moe.ouom.neriplayer.core.download.manager.commit.cleanupCancelledDownloadArtifacts
import moe.ouom.neriplayer.core.download.manager.commit.cleanupCancelledPendingDownloadArtifacts
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingResumableDownloads
import moe.ouom.neriplayer.core.download.manager.runtime.awaitSongCancellationSettled
import moe.ouom.neriplayer.core.download.manager.runtime.scheduleWifiBoundDownloadWakeTasks
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.manager.runtime.withSongExecutionLock
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadTask
import moe.ouom.neriplayer.core.download.model.applyWaitingNetworkStatus
import moe.ouom.neriplayer.core.download.policy.DOWNLOAD_CLEAR_HARD_DEADLINE_MS
import moe.ouom.neriplayer.core.download.policy.DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS
import moe.ouom.neriplayer.core.download.policy.DownloadAdmissionGate
import moe.ouom.neriplayer.core.download.policy.DownloadClearRoomTimeoutException
import moe.ouom.neriplayer.core.download.policy.DownloadClearVisibility
import moe.ouom.neriplayer.core.download.policy.awaitBatchDownloadJobsSettled
import moe.ouom.neriplayer.core.download.policy.awaitDownloadClearProviderCleanup
import moe.ouom.neriplayer.core.download.policy.hasDownloadClearExceededDeadline
import moe.ouom.neriplayer.core.download.policy.resolveDownloadClearRetainedTotalItemCount
import moe.ouom.neriplayer.core.download.policy.shouldBlockDownloadClearForPendingArtifacts
import moe.ouom.neriplayer.core.download.policy.shouldDeferDownloadClearAfterDurableRetry
import moe.ouom.neriplayer.core.download.policy.shouldPersistDownloadClearProgress
import moe.ouom.neriplayer.core.download.policy.shouldRetainUnresolvedCancellationSnapshot
import moe.ouom.neriplayer.core.download.policy.withDownloadClearRoomTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadClearSettlement
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadClearOwnershipCapture
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.WifiBoundNetworkPolicySnapshot
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearFenceReleaseResult
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearProgressStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import java.io.File


internal suspend fun GlobalDownloadManager.captureDownloadClearOwnership(
    context: Context,
    clearStartedAtMs: Long
): DownloadClearOwnershipCapture {
    val operationIdentities = DownloadExecutionRoomStore
        .listCancellationIdentitiesAnyLibrary(context)
        .filter { identity ->
            clearStartedAtMs <= 0L || identity.createdAtMs <= clearStartedAtMs
        }
    // 旧版本可能只留下私有 staging 恢复清单而没有 Room operation
    // 持久准入栅栏已经生效，此时枚举到的工作文件都属于本次清空前的任务
    val pendingWorkingDownloads = ManagedDownloadStorage
        .listPendingResumableDownloads(context)
    return DownloadClearOwnershipCapture(
        operationIdentities = operationIdentities,
        pendingWorkingDownloads = pendingWorkingDownloads
    )
}

internal suspend fun GlobalDownloadManager.runFastTaskClearPhase(
    context: Context,
    token: DownloadAdmissionGate.ClearToken,
    visibleTasks: Collection<DownloadTask>,
    ownerStableKeys: Collection<String>,
    persistProgress: Boolean = true
): Boolean {
    val startedAtMs = SystemClock.elapsedRealtime()
    val ownedSongKeys = ownerStableKeys.map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    val songKeys = (visibleTasks
        .map { task -> task.song.stableKey() } + ownedSongKeys)
        .filter(String::isNotBlank)
        .toSet()
    songKeys.forEach(::markSongCancelled)
    clearBatchDownloadPresentation()
    runDownloadClearStopAction("取消共享下载泵") {
        DownloadExecutionHosts.cancelAllOwned(context)
    }
    // 先写入批量取消状态，让任务列表在交互预算内立即收敛。持久围栏
    // 已经生效，宿主即使有极短暂的尾部写入也只能留下可恢复凭据
    val persistedCancellationCount = withTimeoutOrNull(
        DOWNLOAD_CLEAR_FAST_DB_WAIT_MS
    ) {
        try {
            DownloadExecutionRoomStore.requestCancelForStableKeysFast(
                context = context,
                stableKeys = ownedSongKeys
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "下载清空快速取消未能落库，交给恢复阶段重试: ${error.message}",
                error
            )
            0
        }
    }
    // 持久栅栏已经阻止新任务，宿主取消不应占用交互预算。把 用户发起的数据传输任务、
    // WorkManager 和前台宿主的取消放到独立协程，物理删除仍由取得
    // 目录租约的后台阶段执行
    scheduleImmediateDownloadExecutionStop(
        context = context,
        reason = "fast clear phase",
        stableKeys = ownedSongKeys
    )
    val acknowledgedCancellationCount = (persistedCancellationCount ?: 0)
        .coerceIn(0, songKeys.size)
    var lastPublishedCancellationCount = -1
    songKeys.forEachIndexed { index, songKey ->
        AudioDownloadManager.cancelSongDownload(songKey)
        val completedCount = minOf(index + 1, acknowledgedCancellationCount)
        val shouldPublish = completedCount != lastPublishedCancellationCount && (
            completedCount == acknowledgedCancellationCount ||
                completedCount % DOWNLOAD_CLEAR_PROGRESS_UPDATE_BATCH_SIZE == 0
            )
        if (shouldPublish) {
            lastPublishedCancellationCount = completedCount
            downloadClearVisibility.update(
                token = token,
                phase = DownloadClearVisibility.ClearPhase.CLEANING,
                completedSteps = 2,
                affectedItemCount = maxOf(visibleTasks.size, songKeys.size),
                completedItemCount = completedCount,
                totalItemCount = songKeys.size
            )
            yield()
        }
    }
    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
    if (elapsedMs >= DOWNLOAD_CLEAR_INTERACTIVE_BUDGET_MS) {
        NPLogger.w(
            TAG,
            "下载清空快速阶段超出预算，保留持久栅栏等待后台收敛: " +
                "elapsedMs=$elapsedMs, budgetMs=$DOWNLOAD_CLEAR_INTERACTIVE_BUDGET_MS, " +
                "persistedCancellationCount=$persistedCancellationCount"
        )
        return false
    }
    if (persistedCancellationCount == null) {
        // Room 可能仍在其独立线程中收尾，任务展示已经可以安全移除
        // 持久栅栏会让后台收敛重试同一批取消，不把数据库抖动暴露给用户
        NPLogger.w(
            TAG,
            "下载清空快速取消尚未得到数据库确认，先完成交互清空并等待后台重试"
        )
    }
    downloadClearVisibility.update(
        token = token,
        phase = DownloadClearVisibility.ClearPhase.CLEANING,
        completedSteps = 2,
        affectedItemCount = maxOf(visibleTasks.size, songKeys.size),
        failedItemCount = 0,
        completedItemCount = acknowledgedCancellationCount,
        totalItemCount = songKeys.size
    )
    if (persistProgress) {
        persistDownloadClearProgress(context, token)
    }
    NPLogger.i(
        TAG,
        "下载任务已快速清空，文件清理转入后台: " +
            "songs=${songKeys.size}, operations=$persistedCancellationCount, " +
            "elapsedMs=$elapsedMs"
    )
    return true
}

internal fun GlobalDownloadManager.scheduleImmediateDownloadExecutionStop(
    context: Context,
    reason: String,
    stableKeys: Collection<String>? = null,
    operationIds: Collection<String>? = null
) {
    val appContext = context.applicationContext
    scope.launch {
        try {
            stopDownloadExecutionImmediately(
                context = appContext,
                reason = reason,
                stableKeys = stableKeys,
                operationIds = operationIds
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "后台取消下载宿主失败，交给持久收敛重试: ${error.message}",
                error
            )
        }
    }
}

internal fun GlobalDownloadManager.scheduleDeferredTaskClearRecovery(
    context: Context,
    purpose: DownloadClearPurpose = DownloadClearPurpose.TASK_PROGRESS
) {
    if (purpose == DownloadClearPurpose.FULL_LIBRARY_DELETE) {
        scheduleDeferredFullLibraryDeleteRecovery(context)
        return
    }
    if (purpose == DownloadClearPurpose.TASK_PROGRESS) {
        scheduleTaskClearHardDeadline(context)
    }
    if (!deferredTaskClearRecoveryScheduled.compareAndSet(false, true)) {
        return
    }
    val appContext = context.applicationContext
    scope.launch {
        try {
            delay(100L)
            if (finishReleasedTaskClearState(appContext)) {
                return@launch
            }
            var attempt = 0
            while (PersistentDownloadClearFenceStore.isActive(appContext)) {
                attempt++
                NPLogger.d(
                    TAG,
                    "开始后台收敛下载清空: attempt=$attempt"
                )
                val retryCompleted = try {
                    requestAllDownloadTaskCancellation(
                        purpose = purpose,
                        forceConvergence = true
                    ).join()
                    true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "后台本轮下载清空收敛失败，稍后继续重试: " +
                            error.message,
                        error
                    )
                    false
                }
                if (!retryCompleted) {
                    delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                    continue
                }
                if (finishReleasedTaskClearState(appContext)) {
                    return@launch
                }
                delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
            }
            finishReleasedTaskClearState(appContext)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "后台下载清空收敛失败，保留持久栅栏: ${error.message}",
                error
            )
        } finally {
            deferredTaskClearRecoveryScheduled.set(false)
        }
    }
}

internal fun GlobalDownloadManager.scheduleTaskClearHardDeadline(context: Context) {
    if (!taskClearHardDeadlineScheduled.compareAndSet(false, true)) {
        return
    }
    val appContext = context.applicationContext
    scope.launch {
        try {
            while (true) {
                if (
                    !PersistentDownloadClearFenceStore.isTaskClearActive(appContext) ||
                    PersistentDownloadClearFenceStore.activePurpose(appContext) !=
                        DownloadClearPurpose.TASK_PROGRESS
                ) {
                    return@launch
                }
                val requestedAtMs = PersistentDownloadClearFenceStore.requestedAtMs(
                    appContext
                )
                val remainingMs = if (requestedAtMs == null) {
                    0L
                } else {
                    DOWNLOAD_CLEAR_HARD_DEADLINE_MS -
                        (System.currentTimeMillis() - requestedAtMs)
                }
                if (remainingMs > 0L) {
                    delay(remainingMs)
                    continue
                }
                if (escalateExpiredTaskClear(appContext)) {
                    return@launch
                }
                delay(100L)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "下载清空硬截止恢复失败，保留持久栅栏等待下次启动: ${error.message}",
                error
            )
        } finally {
            taskClearHardDeadlineScheduled.set(false)
        }
    }
}

internal fun GlobalDownloadManager.escalateExpiredTaskClear(context: Context): Boolean {
    val appContext = context.applicationContext
    if (
        !PersistentDownloadClearFenceStore.isTaskClearActive(appContext) ||
        PersistentDownloadClearFenceStore.activePurpose(appContext) !=
            DownloadClearPurpose.TASK_PROGRESS
    ) {
        return false
    }
    val requestedAtMs = PersistentDownloadClearFenceStore.requestedAtMs(appContext)
    if (
        requestedAtMs != null &&
        !hasDownloadClearExceededDeadline(
            requestedAtMs = requestedAtMs,
            nowMs = System.currentTimeMillis()
        )
    ) {
        return false
    }
    val ownershipCaptureComplete =
        PersistentDownloadClearFenceStore.isOwnershipCaptureComplete(appContext)
    val ownership = PersistentDownloadClearFenceStore.ownership(appContext)
    stopDownloadExecutionImmediately(
        context = appContext,
        reason = "download clear hard deadline escalation",
        stableKeys = if (ownershipCaptureComplete) ownership?.stableKeys else null,
        operationIds = if (ownershipCaptureComplete) ownership?.operationIds else null
    )
    NPLogger.w(
        TAG,
        "下载清空达到 3 秒硬截止，已升级停止旧执行并保留持久栅栏: " +
            "ownedSongs=${ownership?.stableKeys?.size ?: 0}, " +
            "ownedOperations=${ownership?.operationIds?.size ?: 0}, " +
            "captureComplete=$ownershipCaptureComplete, " +
            "requestedAtMs=${requestedAtMs ?: 0L}"
    )
    return true
}

internal fun GlobalDownloadManager.finishReleasedTaskClearState(context: Context): Boolean {
    // delete intent 属于下载文件/catalog 删除事务，不属于任务清空横幅。
    // task fence 一旦释放，4/4 就必须结束，否则全选删除会一直显示“整理记录中”。
    if (PersistentDownloadClearFenceStore.isTaskProgressActive(context)) {
        return false
    }
    val openGeneration = downloadAdmissionGate.openTicketOrNull() ?: return false
    downloadClearVisibility.finishGeneration(openGeneration)
    taskStore.currentClearPresentationToken()?.let { token ->
        taskStore.finishClearPresentation(token) {
            !PersistentDownloadClearFenceStore.isTaskProgressActive(context) &&
                downloadAdmissionGate.openTicketOrNull() == openGeneration
        }
    }
    return true
}

internal suspend fun GlobalDownloadManager.awaitDownloadClearFenceRelease(context: Context): Boolean {
    val released = withTimeoutOrNull(DOWNLOAD_CLEAR_FENCE_WAIT_TIMEOUT_MS) {
        while (PersistentDownloadClearFenceStore.hasPersistedFence(context)) {
            delay(DOWNLOAD_CLEAR_FENCE_WAIT_POLL_MS)
        }
        true
    } == true
    if (!released) {
        NPLogger.w(
            TAG,
            "等待下载清空 fence 超时，保留持久恢复而不继续阻塞调用方: " +
                "timeoutMs=$DOWNLOAD_CLEAR_FENCE_WAIT_TIMEOUT_MS"
        )
    }
    return released
}

internal fun GlobalDownloadManager.scheduleFullLibraryDeleteRecoveryAfterProviderCleanup(context: Context): Boolean {
    val cleanup = downloadClearProviderCleanupCoordinator.activeOrNull() ?: return false
    val shouldObserve = synchronized(deferredFullDeleteProviderCleanupRecoveryLock) {
        if (deferredFullDeleteProviderCleanup === cleanup.operation) {
            false
        } else {
            deferredFullDeleteProviderCleanup = cleanup.operation
            true
        }
    }
    if (!shouldObserve) {
        return true
    }
    val appContext = context.applicationContext
    cleanup.operation.invokeOnCompletion {
        val shouldResume = synchronized(deferredFullDeleteProviderCleanupRecoveryLock) {
            if (deferredFullDeleteProviderCleanup === cleanup.operation) {
                deferredFullDeleteProviderCleanup = null
                true
            } else {
                false
            }
        }
        if (
            shouldResume &&
                PersistentDownloadClearFenceStore.isActive(appContext) &&
                PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)
        ) {
            deferredFullDeleteProviderCleanupRecoveryPending.set(true)
            scheduleDeferredFullLibraryDeleteRecovery(appContext)
        }
    }
    return true
}

internal suspend fun GlobalDownloadManager.isFullLibraryDeleteCancellationSettled(context: Context): Boolean {
    if (downloadClearProviderCleanupCoordinator.activeOrNull() != null) {
        return false
    }
    if (assetEnrichmentCoordinator.activeOperationIds().isNotEmpty()) {
        return false
    }
    val cancellationIdentities = runCatching {
        DownloadExecutionRoomStore.listCancellationIdentitiesAnyLibrary(context)
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "全选删除恢复无法确认取消 owner，保留恢复意图: ${error.message}",
            error
        )
        return false
    }
    return cancellationIdentities.none { identity ->
        DownloadExecutionHosts.default.isExecuting(identity.operationId) ||
            AudioDownloadManager.isSongDownloadActive(identity.stableKey)
    }
}

internal fun GlobalDownloadManager.scheduleDeferredFullLibraryDeleteRecovery(context: Context) {
    if (!deferredFullDeleteRecoveryScheduled.compareAndSet(false, true)) {
        return
    }
    deferredFullDeleteProviderCleanupRecoveryPending.set(false)
    val appContext = context.applicationContext
    scope.launch {
        try {
            delay(100L)
            repeat(3) { attempt ->
                if (!PersistentDownloadClearFenceStore.isActive(appContext)) {
                    return@launch
                }
                val intent = PersistentDownloadedSongDeleteIntentStore.read(appContext)
                if (intent == null) {
                    NPLogger.e(
                        TAG,
                        "全选删除恢复意图不可读，保留栅栏等待下次启动"
                    )
                    return@launch
                }
                val currentRootKey = ManagedDownloadStorage.currentSnapshotCacheKey(appContext)
                if (intent.rootKey != currentRootKey) {
                    NPLogger.e(
                        TAG,
                        "全选删除恢复意图与当前目录不匹配，保留栅栏: " +
                            "intentRoot=${intent.rootKey}, currentRoot=$currentRootKey"
                    )
                    return@launch
                }
                // 恢复路径不能再次调用 deleteDownloadedSongsWithResult。
                // 该入口会重新建立全库删除会话，在已有 FULL fence 时等待
                // 自身释放，形成递归等待。先单独收敛任务取消，再按目录快照
                // 回放物理删除，完全不依赖 catalog
                val cancellationSettled = withTimeoutOrNull(
                    DOWNLOAD_CLEAR_FENCE_WAIT_TIMEOUT_MS
                ) {
                    requestAllDownloadTaskCancellation(
                        purpose = DownloadClearPurpose.FULL_LIBRARY_DELETE,
                        forceConvergence = true
                    ).join()
                    isFullLibraryDeleteCancellationSettled(appContext)
                } == true
                if (!cancellationSettled) {
                    val providerCleanupObserved =
                        scheduleFullLibraryDeleteRecoveryAfterProviderCleanup(appContext)
                    NPLogger.w(
                        TAG,
                        "全选删除恢复仍在等待旧清空，保留 fence 后续重试: " +
                            "attempt=${attempt + 1}/3, " +
                            "providerCleanupObserved=$providerCleanupObserved"
                    )
                    if (providerCleanupObserved) {
                        // 当前 Provider 调用仍持有目录 lease。等待 completion 回调
                        // 重新调度，避免每秒重复进入同一轮清空
                        return@launch
                    }
                    if (attempt < 2) {
                        delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                    }
                    return@repeat
                }
                if (!PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)) {
                    return@launch
                }
                // 正常删除和无 catalog 恢复共用同一把锁。否则恢复线程可能
                // 先删完引用，正常会话随后把空删除结果误判为失败并复活歌曲
                val replayed = downloadedSongDeleteMutex.withLock {
                    replayFullLibraryDeleteWithoutCatalog(appContext)
                }
                if (replayed) {
                    NPLogger.i(TAG, "进程重启后的全选删除已按目录快照收敛")
                    return@launch
                }
                NPLogger.w(
                    TAG,
                    "进程重启后的全选删除仍有残留: attempt=${attempt + 1}/3"
                )
                if (attempt < 2) {
                    delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                }
            }
            NPLogger.w(TAG, "全选删除恢复达到本轮重试上限，保留持久意图")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "全选删除恢复失败，保留持久意图: ${error.message}",
                error
            )
        } finally {
            deferredFullDeleteRecoveryScheduled.set(false)
            if (
                deferredFullDeleteProviderCleanupRecoveryPending.compareAndSet(true, false)
            ) {
                scheduleDeferredFullLibraryDeleteRecovery(appContext)
            }
        }
    }
}

internal suspend fun GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context: Context): Boolean {
    val appContext = context.applicationContext
    val plan = try {
        managedDownloadDeletePlanner.buildFullLibraryDeletePlan(appContext)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(TAG, "无 catalog 全库删除快照失败: ${error.message}", error)
        return false
    }
    if (!plan.snapshotComplete) {
        NPLogger.w(TAG, "无 catalog 全库删除快照不完整，保留恢复意图")
        return false
    }
    val requestedReferences = plan.requestedReferences
    val deletedReferences = try {
        if (requestedReferences.isEmpty()) {
            emptySet()
        } else {
            ManagedDownloadStorage.deleteFullLibraryReferences(
                context = appContext,
                references = requestedReferences
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(TAG, "无 catalog 全库删除引用失败: ${error.message}", error)
        return false
    }
    val remainingReferences = requestedReferences - deletedReferences
    if (remainingReferences.isNotEmpty()) {
        NPLogger.w(
            TAG,
            "无 catalog 全库删除仍有未确认引用: " +
                "requested=${requestedReferences.size}, " +
                "remaining=${remainingReferences.size}"
        )
        return false
    }
    val artifactCleanup = runCatching {
        managedDownloadArtifactCoordinator
            .deleteAllAfterCancellationSettled(appContext)
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "无 catalog 全库删除 artifact 收尾失败: ${error.message}",
            error
        )
    }.getOrNull()
    if (artifactCleanup?.isComplete != true) {
        NPLogger.w(
            TAG,
            "无 catalog 全库删除发现活动 artifact 租约，保留恢复意图"
        )
        return false
    }
    val fastIndexCleared = runCatching {
        ManagedDownloadStorage.clearFastIndexForConfirmedEmptyLibrary(appContext)
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "无 catalog 全库删除 fast index 收尾失败: ${error.message}",
            error
        )
    }.getOrDefault(false)
    if (!fastIndexCleared) {
        return false
    }
    synchronized(downloadedSongCatalogMutationLock) {
        publishDownloadedSongs(appContext, emptyList(), persistCatalog = false)
    }
    cancelScheduledDownloadedSongsCatalogPersist()
    val catalogPersisted = catalogPersistenceMutex.withLock {
        persistConfirmedEmptyDownloadedSongsCatalog(appContext)
    }
    if (!catalogPersisted) {
        NPLogger.w(TAG, "无 catalog 全库删除 catalog 未确认落盘，保留恢复意图")
        return false
    }
    if (!PersistentDownloadedSongDeleteIntentStore.clear(appContext)) {
        NPLogger.w(TAG, "无 catalog 全库删除恢复意图未清理，保留栅栏")
        return false
    }
    if (PersistentDownloadClearFenceStore.hasPersistedFence(appContext) &&
        !PersistentDownloadClearFenceStore.clear(appContext)
    ) {
        NPLogger.w(TAG, "无 catalog 全库删除栅栏未释放，等待下次恢复")
        return false
    }
    clearPersistedDownloadClearProgress(appContext)
    finishReleasedTaskClearState(appContext)
    wakeDownloadExecutionPump(appContext, "full_library_delete_released")
    return true
}

internal suspend fun GlobalDownloadManager.activateDownloadClearFence(context: Context): Boolean {
    for (retryRound in 1..DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS) {
        if (PersistentDownloadClearFenceStore.activate(context)) {
            return true
        }
        NPLogger.w(
            TAG,
            "下载清空栅栏落盘失败，保持清空状态并重试: retryRound=$retryRound"
        )
        if (shouldDeferDownloadClearAfterDurableRetry(retryRound)) {
            break
        }
        stopDownloadExecutionImmediately(
            context = context,
            reason = "waiting for durable download clear fence"
        )
        delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
    }
    NPLogger.e(
        TAG,
        "下载清空栅栏持久化达到有界重试上限，保留内存栅栏: " +
            "maxRounds=$DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS"
    )
    return false
}

internal fun GlobalDownloadManager.persistDownloadClearProgress(
    context: Context,
    token: DownloadAdmissionGate.ClearToken
) {
    if (!token.ownsClear) return
    downloadClearVisibility.progress.value?.let { progress ->
        PersistentDownloadClearProgressStore.save(context, progress)
    }
}

internal fun GlobalDownloadManager.restoreDownloadClearProgress(
    context: Context,
    token: DownloadAdmissionGate.ClearToken
) {
    if (!token.ownsClear) return
    PersistentDownloadClearProgressStore.read(context)?.let { progress ->
        downloadClearVisibility.restore(token, progress)
    }
}

internal fun GlobalDownloadManager.clearPersistedDownloadClearProgress(context: Context) {
    PersistentDownloadClearProgressStore.clear(context)
}

internal fun GlobalDownloadManager.cancelBatchDownloadJobsForClear(
    batchJobs: Collection<Job>,
    reason: String
) {
    batchJobs.forEach { job ->
        runDownloadClearStopAction("取消批量下载协程") {
            job.cancel(CancellationException(reason))
        }
    }
}

internal fun GlobalDownloadManager.stopDownloadExecutionImmediately(
    context: Context,
    reason: String,
    stableKeys: Collection<String>? = null,
    operationIds: Collection<String>? = null
) {
    val ownedKeys = stableKeys?.map(String::trim)?.filter(String::isNotBlank)?.toSet()
    val ownedOperationIds = operationIds?.map(String::trim)?.filter(String::isNotBlank)?.toSet()
    runDownloadClearStopAction("取消下载资产整理") {
        val cancelledCount = when {
            !ownedOperationIds.isNullOrEmpty() -> ownedOperationIds.count { operationId ->
                assetEnrichmentCoordinator.cancel(operationId)
            }

            ownedKeys == null && ownedOperationIds == null ->
                assetEnrichmentCoordinator.cancelAll(reason)

            else -> 0
        }
        if (cancelledCount > 0) {
            NPLogger.d(
                TAG,
                "已取消下载资产整理任务: count=$cancelledCount, reason=$reason"
            )
        }
    }
    runDownloadClearStopAction("取消全部下载宿主") {
        when {
            !ownedOperationIds.isNullOrEmpty() ->
                DownloadExecutionHosts.default.cancelAll(context, ownedOperationIds)

            !ownedKeys.isNullOrEmpty() -> ownedKeys.forEach { songKey ->
                DownloadExecutionHosts.default.cancelForSong(context, songKey)
            }

            ownedKeys == null && ownedOperationIds == null ->
                DownloadExecutionHosts.cancelAllOwned(context)
        }
    }
    if (ownedKeys == null && ownedOperationIds == null) {
        cancelBatchDownloadJobsForClear(
            batchJobs = activeBatchDownloadJobs.toList(),
            reason = reason
        )
    }
    runDownloadClearStopAction("取消音频下载") {
        when {
            !ownedKeys.isNullOrEmpty() -> ownedKeys.forEach { songKey ->
                AudioDownloadManager.cancelSongDownload(songKey)
            }

            ownedKeys == null && ownedOperationIds == null ->
                AudioDownloadManager.cancelDownload()
        }
    }
}

internal fun GlobalDownloadManager.runDownloadClearStopAction(
    action: String,
    block: () -> Unit
) {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        NPLogger.w(TAG, "$action 失败: ${error.message}", error)
    }
}

internal suspend fun GlobalDownloadManager.clearDownloadClearFence(
    context: Context,
    expectedEpoch: Long
): Boolean {
    for (retryRound in 1..DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS) {
        when (
            PersistentDownloadClearFenceStore.clearIfCurrent(
                context = context,
                expectedEpoch = expectedEpoch
            )
        ) {
            DownloadClearFenceReleaseResult.RELEASED,
            DownloadClearFenceReleaseResult.SUPERSEDED -> return true

            DownloadClearFenceReleaseResult.FAILED -> {
                NPLogger.w(
                    TAG,
                    "下载清空栅栏移除失败，继续阻止新下载并重试: retryRound=$retryRound"
                )
                if (shouldDeferDownloadClearAfterDurableRetry(retryRound)) {
                    break
                }
                stopDownloadExecutionImmediately(
                    context = context,
                    reason = "download clear fence still active"
                )
                delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
            }
        }
    }
    NPLogger.e(
        TAG,
        "下载清空栅栏释放达到有界重试上限，保留持久栅栏: " +
            "maxRounds=$DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS"
    )
    return false
}

internal suspend fun GlobalDownloadManager.cancelAllDownloadTasksAndWait() {
    requestAllDownloadTaskCancellation(forceConvergence = true).join()
}

internal suspend fun GlobalDownloadManager.requestAllDownloadOperationCancellation(
    context: Context,
    operationIds: Collection<String>? = null
): DownloadExecutionRoomStore.CancellationSnapshot {
    for (retryRound in 1..DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS) {
        repeat(DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS) { attempt ->
            try {
                return withDownloadClearRoomTimeout(
                    operation = "request clear cancellations"
                ) {
                    if (operationIds == null) {
                        DownloadExecutionRoomStore.requestCancelAll(context)
                    } else {
                        DownloadExecutionRoomStore.requestCancelOperations(
                            context = context,
                            operationIds = operationIds
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (timeout: DownloadClearRoomTimeoutException) {
                NPLogger.w(
                    TAG,
                    "清空 Room 查询超时，保留 durable fence 并交由恢复重试: " +
                        timeout.message
                )
                throw timeout
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "批量标记持久下载取消失败: attempt=${attempt + 1}/" +
                        "$DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS, error=${error.message}",
                    error
                )
                if (attempt + 1 < DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS) {
                    delay(DOWNLOAD_CANCEL_JOURNAL_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        if (shouldDeferDownloadClearAfterDurableRetry(retryRound)) {
            break
        }
        NPLogger.w(
            TAG,
            "批量取消等待持久化存储恢复: retryRound=$retryRound"
        )
        stopDownloadExecutionImmediately(
            context = context,
            reason = "waiting for durable download cancellation",
            operationIds = operationIds
        )
        delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
    }
    throw IllegalStateException(
        "批量取消持久化在有界重试后仍不可用: " +
            "maxRounds=$DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS"
    )
}

internal fun GlobalDownloadManager.recoverPendingDownloadsOnCurrentNetwork(context: Context) {
    val appContext = context.applicationContext
    val admissionTicket = downloadAdmissionGate.openTicketOrNull()
    if (admissionTicket == null) {
        NPLogger.d(TAG, "清空期间跳过当前网络下载恢复")
        return
    }
    scope.launch {
        withPendingDownloadRecoverySlot("current_network") {
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            val networkType = appContext.currentDownloadNetworkTypeOrNull()
                ?: return@withPendingDownloadRecoverySlot
            if (networkType != TrafficNetworkType.WIFI && !mobileDataDownloadOverrideAllowed) {
                return@withPendingDownloadRecoverySlot
            }
            promoteWaitingStorageMutationsForRecovery(
                context = appContext,
                admissionTicket = admissionTicket
            )
            waitForActiveDownloadJobsToSettle()
            waitForQueuedTasksToAttachToBatch()
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            if (hasBlockingActiveDownloadOperationsForRecovery()) {
                return@withPendingDownloadRecoverySlot
            }
            recoverPendingResumableDownloads(
                context = appContext,
                reason = "mobile_data_user_confirmed",
                admissionTicket = admissionTicket
            )
            delay(1_500L)
        }
    }
}

internal suspend fun GlobalDownloadManager.cancelDownloadTaskInBackground(
    task: DownloadTask,
    cancellationGeneration: Long?,
    operationRequests: Collection<DownloadExecutionRequest>,
    operationIds: Set<String>,
    logSettlement: Boolean
) {
    val appContext = AppContainer.applicationContext
    val songKey = task.song.stableKey()
    if (isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
        requestOperationCancellation(setOf(songKey))
    }
    if (operationIds.isNotEmpty()) {
        AudioDownloadManager.cancelOperationDownload(songKey, operationIds)
    } else if (!hasCancellationSnapshotBoundary(songKey)) {
        AudioDownloadManager.cancelSongDownload(songKey)
    }
    val settled = awaitSongCancellationSettled(
        songKey = songKey,
        timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS,
        clearCancellationWhenSettled = false,
        logProgress = logSettlement,
        operationIds = operationIds
    )
    if (!settled) {
        scheduleCancellationConvergence(
            context = appContext,
            songKey = songKey,
            cancellationGeneration = cancellationGeneration,
            operationIds = operationIds
        )
        return
    }
    withSongExecutionLock(songKey) {
        if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            if (logSettlement) {
                NPLogger.d(
                    TAG,
                    "跳过过期单曲取消清理: song=${task.song.name}, songKey=$songKey"
                )
            }
            return@withSongExecutionLock
        }
        operationRequests.forEach { request ->
            releaseDownloadArtifactAfterExecutionOwnershipLoss(
                context = appContext,
                song = request.song,
                operationId = request.operationId,
                expectedLeaseId = request.artifactLeaseId
            )
        }
        val cleanupSucceeded = when {
            operationRequests.isNotEmpty() -> operationRequests.all { request ->
                cleanupCancelledDownloadArtifacts(
                    context = appContext,
                    song = request.song,
                    operationId = request.operationId
                )
            }
            operationIds.isEmpty() && !hasCancellationSnapshotBoundary(songKey) ->
                cleanupCancelledDownloadArtifacts(
                    context = appContext,
                    song = task.song,
                    operationId = null
                )
            else -> true
        }
        if (cleanupSucceeded) {
            clearSongCancelled(songKey)
            val trackedOperationIds = cancellationOperationIdsForSong(songKey)
            if (
                trackedOperationIds.isEmpty() &&
                    shouldRetainUnresolvedCancellationSnapshot(
                        snapshotBoundary = hasCancellationSnapshotBoundary(songKey),
                        snapshotResolved = isCancellationSnapshotResolved(songKey),
                        operationIds = trackedOperationIds
                    )
            ) {
                scheduleCancellationConvergence(
                    context = appContext,
                    songKey = songKey,
                    cancellationGeneration = cancellationGeneration,
                    operationIds = trackedOperationIds
                )
            } else if (trackedOperationIds.isEmpty()) {
                clearCancellationTracking(songKey)
            } else {
                scheduleCancellationConvergence(
                    context = appContext,
                    songKey = songKey,
                    cancellationGeneration = cancellationGeneration,
                    operationIds = trackedOperationIds
                )
            }
        } else {
            scheduleCancellationConvergence(
                context = appContext,
                songKey = songKey,
                cancellationGeneration = cancellationGeneration,
                operationIds = operationIds
            )
        }
    }
}

internal fun GlobalDownloadManager.createDownloadClearProgressReporter(
    token: DownloadAdmissionGate.ClearToken,
    affectedItemCount: Int
): (Int, Int) -> Unit {
    val progressLock = Any()
    var lastReportedItems = -1
    var lastReportedAtMs = 0L
    var lastPersistedItems = -1
    var lastPersistedAtMs = Long.MIN_VALUE
    return { completedItems, totalItems ->
        val normalizedTotal = totalItems.coerceAtLeast(0)
        val normalizedCompleted = completedItems
            .coerceAtLeast(0)
            .let { completed ->
                if (normalizedTotal > 0) {
                    completed.coerceAtMost(normalizedTotal)
                } else {
                    completed
                }
            }
        val nowMs = System.currentTimeMillis()
        synchronized(progressLock) {
            val shouldReport = lastReportedItems < 0 ||
                    normalizedTotal in 1..normalizedCompleted ||
                normalizedCompleted - lastReportedItems >=
                    DOWNLOAD_CLEAR_PROGRESS_UPDATE_BATCH_SIZE ||
                nowMs - lastReportedAtMs >= DOWNLOAD_CLEAR_PROGRESS_UPDATE_INTERVAL_MS
            if (!shouldReport) {
                return@synchronized
            }
            lastReportedItems = normalizedCompleted
            lastReportedAtMs = nowMs
            downloadClearVisibility.update(
                token = token,
                phase = DownloadClearVisibility.ClearPhase.CLEANING,
                completedSteps = 2,
                affectedItemCount = affectedItemCount,
                completedItemCount = normalizedCompleted,
                totalItemCount = normalizedTotal
            )
            if (shouldPersistDownloadClearProgress(
                    completedItemCount = normalizedCompleted,
                    totalItemCount = normalizedTotal,
                    lastPersistedItemCount = lastPersistedItems,
                    nowMs = nowMs,
                    lastPersistedAtMs = lastPersistedAtMs,
                    minIntervalMs = DOWNLOAD_CLEAR_PROGRESS_PERSIST_INTERVAL_MS,
                    batchSize = DOWNLOAD_CLEAR_PROGRESS_PERSIST_BATCH_SIZE
                )
            ) {
                persistDownloadClearProgress(
                    context = AppContainer.applicationContext,
                    token = token
                )
                lastPersistedItems = normalizedCompleted
                lastPersistedAtMs = nowMs
            }
        }
    }
}

internal fun GlobalDownloadManager.updateDownloadClearSettlementProgress(
    context: Context,
    token: DownloadAdmissionGate.ClearToken,
    affectedItemCount: Int,
    settlement: DownloadClearSettlement
) {
    val previousProgress = downloadClearVisibility.progress.value
    val totalItems = maxOf(
        previousProgress?.totalItemCount ?: 0,
        affectedItemCount,
        1
    )
    val residualSongKeys = buildSet {
        addAll(settlement.activeSongKeys)
        addAll(settlement.residualWorkingSongKeys)
        addAll(settlement.residualPendingArtifactSongKeys)
    }
    val remainingItems = maxOf(
        settlement.residualPendingArtifactCount,
        residualSongKeys.size + settlement.activeOperationIds.size +
            if (settlement.batchJobsSettled) 0 else 1
    ).coerceAtMost(totalItems)
    downloadClearVisibility.update(
        token = token,
        phase = DownloadClearVisibility.ClearPhase.CLEANING,
        completedSteps = 2,
        affectedItemCount = affectedItemCount,
        completedItemCount = (totalItems - remainingItems).coerceAtLeast(0),
        totalItemCount = totalItems
    )
    persistDownloadClearProgress(context, token)
}

internal fun GlobalDownloadManager.pendingDownloadClearProviderCleanupSettlement(
    activeKeys: Collection<String>,
    operationRequests: Collection<DownloadExecutionRequest>
): DownloadClearSettlement {
    val residualSongKeys = (activeKeys + operationRequests.map { request ->
        request.song.stableKey()
    }).filter(String::isNotBlank).toSet()
    return DownloadClearSettlement(
        activeSongKeys = emptySet(),
        activeOperationIds = emptySet(),
        batchJobsSettled = true,
        residualPendingArtifactSongKeys = residualSongKeys,
        residualPendingArtifactCount = residualSongKeys.size.coerceAtLeast(1),
        providerCleanupInFlight = true
    )
}

internal suspend fun GlobalDownloadManager.cancelDownloadTasksInBackground(
    context: Context,
    tasks: Collection<DownloadTask>,
    batchJobs: Collection<Job>,
    additionalSongKeys: Collection<String> = emptySet(),
    cancelledSongKeysSnapshot: Collection<String> = emptySet(),
    cancellationGenerations: Map<String, Long?> = emptyMap(),
    operationRequests: Collection<DownloadExecutionRequest> = emptyList(),
    executionOperationIds: Collection<String> = operationRequests.map(
        DownloadExecutionRequest::operationId
    ),
    workingFilesBySongKey: Map<String, Collection<File>> = emptyMap(),
    clearToken: DownloadAdmissionGate.ClearToken? = null,
    awaitProviderCleanup: Boolean = true,
    skipProviderArtifactCleanup: Boolean = false
): DownloadClearSettlement {
    val appContext = context.applicationContext
    if (!PersistentDownloadClearFenceStore.isTaskClearActive(appContext)) {
        return DownloadClearSettlement(
            activeSongKeys = emptySet(),
            activeOperationIds = emptySet(),
            batchJobsSettled = true
        )
    }
    val persistedKeys = cancelledSongKeysSnapshot
        .filter(String::isNotBlank)
        .toMutableSet()
    val activeKeys = tasks.mapTo(persistedKeys) { it.song.stableKey() }
    additionalSongKeys
        .filter(String::isNotBlank)
        .forEach(activeKeys::add)
    // Provider 清理必须跟持久 fence 绑定。交互清空超时后，恢复流程会创建新的
    // 内存 admission generation；继续使用它会把同一轮清理误判成不同工作，
    // 既不能等待原有 lease，也可能让恢复轮次长期停在 pending 状态
    val providerCleanupKey = PersistentDownloadClearFenceStore.currentEpoch(appContext)
    if (awaitProviderCleanup && !skipProviderArtifactCleanup) {
        val activeCleanup = downloadClearProviderCleanupCoordinator.activeOrNull()
        if (activeCleanup != null) {
            if (activeCleanup.key != providerCleanupKey) {
                return pendingDownloadClearProviderCleanupSettlement(
                    activeKeys = activeKeys,
                    operationRequests = operationRequests
                )
            }
            val completedCleanup = awaitDownloadClearProviderCleanup(
                cleanup = activeCleanup.operation,
                timeoutMs = DOWNLOAD_CLEAR_PROVIDER_CLEANUP_WAIT_TIMEOUT_MS
            )
            if (completedCleanup != null) {
                return completedCleanup
            }
            NPLogger.w(
                TAG,
                "Provider 临时文件清理仍在运行，保留目录 lease 等待下一轮恢复: " +
                    "timeoutMs=$DOWNLOAD_CLEAR_PROVIDER_CLEANUP_WAIT_TIMEOUT_MS"
            )
            return pendingDownloadClearProviderCleanupSettlement(
                activeKeys = activeKeys,
                operationRequests = operationRequests
            )
        }
    }
    val currentCancellationKeys = activeKeys
        .filter { songKey ->
            isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])
        }
        .toSet()
    currentCancellationKeys.forEach(::markSongCancelled)
    val activeDownloadTaskKeys = tasks.mapNotNullTo(linkedSetOf()) { task ->
        val songKey = task.song.stableKey()
        when {
            songKey !in activeKeys -> null
            task.status == DownloadStatus.DOWNLOADING -> songKey
            AudioDownloadManager.isSongDownloadActive(songKey) -> songKey
            else -> null
        }
    }
    currentCancellationKeys
        .filter(AudioDownloadManager::isSongDownloadActive)
        .forEach(activeDownloadTaskKeys::add)
    val activeSongKeys = awaitDownloadCancellationsSettled(activeDownloadTaskKeys)
    val batchJobsSettled = awaitBatchDownloadJobsAfterCancellation(
        batchJobs = batchJobs,
        phase = "clear_all_background_cleanup"
    )
    val normalizedExecutionOperationIds = executionOperationIds.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    val enrichmentJobsSettled = if (normalizedExecutionOperationIds.isEmpty()) {
        assetEnrichmentCoordinator.cancelAllAndJoin(
            reason = "waiting for download clear convergence",
            timeoutMs = DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS
        )
    } else {
        assetEnrichmentCoordinator.cancelAndJoin(
            operationIds = normalizedExecutionOperationIds,
            reason = "waiting for download clear convergence",
            timeoutMs = DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS
        )
    }
    val activeEnrichmentOperationIds = assetEnrichmentCoordinator.activeOperationIds()
        .let { activeIds ->
            if (normalizedExecutionOperationIds.isEmpty()) {
                activeIds
            } else {
                activeIds.intersect(normalizedExecutionOperationIds)
            }
        }
    if (!enrichmentJobsSettled) {
        NPLogger.w(
            TAG,
            "等待下载资产整理取消收敛超时: operations=" +
                activeEnrichmentOperationIds.size
        )
    }
    val activeOperationIds = normalizedExecutionOperationIds
        .filter(DownloadExecutionHosts.default::isExecuting)
        .toMutableSet()
        .apply { addAll(activeEnrichmentOperationIds) }
    if (
        activeSongKeys.isNotEmpty() ||
        activeOperationIds.isNotEmpty() ||
        !batchJobsSettled ||
        !enrichmentJobsSettled
    ) {
        return DownloadClearSettlement(
            activeSongKeys = activeSongKeys,
            activeOperationIds = activeOperationIds,
            batchJobsSettled = batchJobsSettled && enrichmentJobsSettled
        )
    }
    if (skipProviderArtifactCleanup) {
        // 全库删除会在同一恢复意图下按完整快照删除根文件和受管目录。
        // 这里重复逐 operation 清理 Provider 产物既抢占目录 lease，也会让交互入口误报超时
        return DownloadClearSettlement(
            activeSongKeys = emptySet(),
            activeOperationIds = emptySet(),
            batchJobsSettled = true
        )
    }
    if (awaitProviderCleanup) {
        val cleanupTasks = tasks.toList()
        val cleanupBatchJobs = batchJobs.toList()
        val cleanupAdditionalSongKeys = additionalSongKeys.toSet()
        val cleanupCancelledSongKeys = cancelledSongKeysSnapshot.toSet()
        val cleanupCancellationGenerations = cancellationGenerations.toMap()
        val cleanupOperationRequests = operationRequests.toList()
        val cleanupExecutionOperationIds = executionOperationIds.toSet()
        val cleanupWorkingFilesBySongKey = workingFilesBySongKey.mapValues {
                (_, workingFiles) -> workingFiles.toList()
            }
        val cleanup = downloadClearProviderCleanupCoordinator.getOrStart(
            key = providerCleanupKey
        ) {
            cancelDownloadTasksInBackground(
                context = appContext,
                tasks = cleanupTasks,
                batchJobs = cleanupBatchJobs,
                additionalSongKeys = cleanupAdditionalSongKeys,
                cancelledSongKeysSnapshot = cleanupCancelledSongKeys,
                cancellationGenerations = cleanupCancellationGenerations,
                operationRequests = cleanupOperationRequests,
                executionOperationIds = cleanupExecutionOperationIds,
                workingFilesBySongKey = cleanupWorkingFilesBySongKey,
                clearToken = clearToken,
                awaitProviderCleanup = false
            )
        }
        if (cleanup.key != providerCleanupKey) {
            return pendingDownloadClearProviderCleanupSettlement(
                activeKeys = activeKeys,
                operationRequests = operationRequests
            )
        }
        val completedCleanup = awaitDownloadClearProviderCleanup(
            cleanup = cleanup.operation,
            timeoutMs = DOWNLOAD_CLEAR_PROVIDER_CLEANUP_WAIT_TIMEOUT_MS
        )
        if (completedCleanup != null) {
            return completedCleanup
        }
        NPLogger.w(
            TAG,
            "Provider 临时文件清理超过等待预算，保留目录 lease 等待下一轮恢复: " +
                "timeoutMs=$DOWNLOAD_CLEAR_PROVIDER_CLEANUP_WAIT_TIMEOUT_MS"
        )
        return pendingDownloadClearProviderCleanupSettlement(
            activeKeys = activeKeys,
            operationRequests = operationRequests
        )
    }
    val deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(
        appContext
    ) ?: run {
        val residualSongKeys = (activeKeys + operationRequests.map {
            it.song.stableKey()
        }).filter(String::isNotBlank).toSet()
        NPLogger.w(
            TAG,
            "目录迁移占用下载目录，延后清空物理清理: songs=${residualSongKeys.size}"
        )
        return DownloadClearSettlement(
            activeSongKeys = emptySet(),
            activeOperationIds = emptySet(),
            batchJobsSettled = true,
            residualPendingArtifactSongKeys = residualSongKeys,
            residualPendingArtifactCount = 1
        )
    }
    try {
    operationRequests.distinctBy(DownloadExecutionRequest::operationId).forEach { request ->
        val songKey = request.song.stableKey()
        if (!isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])) {
            NPLogger.d(
                TAG,
                "跳过过期 operation 租约取消: " +
                    "song=${request.song.name}, operationId=${request.operationId}"
            )
            return@forEach
        }
        releaseDownloadArtifactAfterExecutionOwnershipLoss(
            context = appContext,
            song = request.song,
            operationId = request.operationId,
            expectedLeaseId = request.artifactLeaseId,
            boundedRoomWait = true
        )
    }
    // pending 音频和恢复元数据统一批量删除。逐 operation 扫描 staging 目录会把
    // 大批量清空退化为 O(歌曲数) 次目录遍历，且更容易撞上 SAF 瞬时失败。
    val clearProgressReporter = clearToken?.let { token ->
        createDownloadClearProgressReporter(
            token = token,
            affectedItemCount = activeKeys.size
        )
    }
    val protectedPendingReferences = linkedSetOf<String>()
    val pendingCleanup = cleanupCancelledPendingDownloadArtifacts(
        context = appContext,
        operationRequests = operationRequests,
        cancellationGenerations = cancellationGenerations,
        onProgress = clearProgressReporter,
        protectedReferencesOut = protectedPendingReferences,
        boundedRoomWait = true
    )
    val cleanupResidualSongKeys = pendingCleanup.residualSongKeys
    val pendingCleanupFailed = pendingCleanup.failedCount > 0
    // 清空是用户明确的破坏性操作，新版本 .tmp 只保存下载中间产物
    // 即使 Room operation 已在进程重启前丢失，也要把未跨过 core commit
    // 的孤儿清掉，已提交 core 的引用由 storage 层继续保护
    val orphanPendingCleanup = try {
        ManagedDownloadStorage.cleanupUnownedPendingDownloadArtifactsForClear(
            context = appContext,
            protectedReferences = protectedPendingReferences,
            onProgress = clearProgressReporter ?: { _, _ -> }
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "清空孤儿 pending 收敛异常，保留栅栏重试: ${error.message}",
            error
        )
        ManagedDownloadStorage.StartupRecoveryResult(failedCount = 1)
    }
    protectedPendingReferences += orphanPendingCleanup.protectedReferences
    val orphanPendingCleanupFailed = orphanPendingCleanup.failedCount > 0
    if (orphanPendingCleanupFailed) {
        NPLogger.w(
            TAG,
            "清空孤儿 pending 尚未完全收敛，保留栅栏重试: " +
                "failed=${orphanPendingCleanup.failedCount}, " +
                "protected=${orphanPendingCleanup.protectedCount}"
        )
    }
    val pendingArtifactScan = try {
        ManagedDownloadStorage.scanPendingDownloadArtifacts(
            context = appContext,
            protectedReferences = protectedPendingReferences
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "下载清空无法确认 pending artifact 快照，保持栅栏重试: " +
                error.message,
            error
        )
        ManagedDownloadStorage.PendingArtifactScanResult(
            count = 1,
            isComplete = false
        )
    }
    val blockingArtifactCount = pendingArtifactScan.blockingCount
    // 按 operation 清理时，无主扫描可能尚未返回最终结果
    // 后续完整扫描若确认残留都属于已提交核心音频，就不能把保护性保留
    // 当成清理失败，否则清空栅栏会在没有阻塞条目时反复重试
    val pendingArtifactsBlockClear =
        shouldBlockDownloadClearForPendingArtifacts(
            scanComplete = pendingArtifactScan.isComplete,
            blockingArtifactCount = blockingArtifactCount
        ) || pendingCleanupFailed || orphanPendingCleanupFailed
    if (pendingArtifactsBlockClear) {
        // 阻塞只表示仍有残留待重试，不会把已经完成的目录扫描降级为未知
        val scanComplete = pendingArtifactScan.isComplete
        val artifactProgress = downloadClearVisibility.resolveArtifactProgress(
            artifactCount = blockingArtifactCount,
            scanComplete = scanComplete,
            cleanupFailed = pendingCleanupFailed || orphanPendingCleanupFailed
        )
        clearToken?.let { token ->
            val currentProgress = downloadClearVisibility.progress.value
            // 完整扫描已经给出当前目录的真实待清理数量，不能再带入
            // 上一轮任务总数，否则目录只剩少量文件时进度仍无法收敛
            val retainedTotalItemCount = resolveDownloadClearRetainedTotalItemCount(
                currentTotalItemCount = currentProgress?.totalItemCount,
                artifactTotalItemCount = artifactProgress.totalItemCount,
                scanComplete = scanComplete
            )
            downloadClearVisibility.update(
                token = token,
                phase = DownloadClearVisibility.ClearPhase.CLEANING,
                completedSteps = 2,
                affectedItemCount = maxOf(
                    activeKeys.size,
                    currentProgress?.affectedItemCount ?: 0
                ),
                failedItemCount = artifactProgress.failedItemCount
                    .coerceAtMost(retainedTotalItemCount),
                completedItemCount = artifactProgress.completedItemCount
                    .coerceAtMost(retainedTotalItemCount),
                totalItemCount = retainedTotalItemCount,
                resetItemWatermark = scanComplete
            )
            persistDownloadClearProgress(
                context = appContext,
                token = token
            )
        }
        NPLogger.w(
            TAG,
            "下载清空发现 pending artifact 尚未收敛，暂不 purge: " +
                "count=${pendingArtifactScan.count}, " +
                "protected=${pendingArtifactScan.protectedCount}, " +
                "blocking=$blockingArtifactCount, " +
                "complete=${pendingArtifactScan.isComplete}, " +
                "effectiveComplete=$scanComplete, " +
                "residualArtifactCount=${artifactProgress.totalItemCount}, " +
                "cleanupResidualSongs=${cleanupResidualSongKeys.size}, " +
                "cleanupFailed=${pendingCleanupFailed || orphanPendingCleanupFailed}"
        )
        return DownloadClearSettlement(
            activeSongKeys = emptySet(),
            activeOperationIds = emptySet(),
            batchJobsSettled = true,
            residualPendingArtifactSongKeys = cleanupResidualSongKeys,
            residualPendingArtifactCount = artifactProgress.totalItemCount
        )
    }
    val batchCleanupKeys = activeKeys
        .filter { songKey ->
            isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])
        }
        .toSet()
    workingFilesBySongKey.forEach { (songKey, workingFiles) ->
        if (songKey !in batchCleanupKeys) return@forEach
        workingFiles.forEach(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)
    }
    ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
        appContext,
        batchCleanupKeys
    )
    val pendingWorkingSongKeys = ManagedDownloadStorage
        .listPendingResumableDownloads(appContext)
        .mapTo(linkedSetOf()) { pendingDownload ->
            pendingDownload.song.stableKey()
        }
    val residualWorkingSongKeys = activeKeys
        .filter { songKey ->
            isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])
        }
        .filterTo(linkedSetOf()) { songKey ->
            val rememberedFiles = workingFilesBySongKey[songKey].orEmpty()
            rememberedFiles.any(::hasWorkingDownloadArtifact) ||
                songKey in pendingWorkingSongKeys
        }
    return DownloadClearSettlement(
        activeSongKeys = emptySet(),
        activeOperationIds = emptySet(),
        batchJobsSettled = true,
        residualWorkingSongKeys = residualWorkingSongKeys,
        residualPendingArtifactSongKeys = emptySet(),
        residualPendingArtifactCount = 0
    )
    } finally {
        deleteLease.close()
    }
}

internal fun GlobalDownloadManager.hasWorkingDownloadArtifact(workingFile: File): Boolean {
    return workingFile.exists() ||
        ManagedDownloadStorage.buildWorkingResumeMetadataFile(workingFile).exists() ||
        ManagedDownloadStorage.buildWorkingHlsCheckpointFile(workingFile).exists()
}

internal suspend fun GlobalDownloadManager.awaitDownloadCancellationsSettled(songKeys: Set<String>): Set<String> {
    if (songKeys.isEmpty()) {
        return emptySet()
    }
    val deadlineAt = System.currentTimeMillis() + DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS
    while (System.currentTimeMillis() < deadlineAt) {
        if (songKeys.none(AudioDownloadManager::isSongDownloadActive)) {
            break
        }
        delay(50L)
    }
    val stuckKeys = songKeys.filter(AudioDownloadManager::isSongDownloadActive)
    if (stuckKeys.isNotEmpty()) {
        NPLogger.w(TAG, "等待批量取消清理超时: count=${stuckKeys.size}")
    }
    return stuckKeys.toSet()
}

internal suspend fun GlobalDownloadManager.awaitBatchDownloadJobsAfterCancellation(
    batchJobs: Collection<Job>,
    phase: String
): Boolean {
    val settled = awaitBatchDownloadJobsSettled(
        jobs = batchJobs,
        timeoutMs = DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS
    )
    if (!settled) {
        NPLogger.w(
            TAG,
            "等待批量下载协程取消收敛超时: phase=$phase, " +
                "jobs=${batchJobs.size}, timeoutMs=$DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS"
        )
    }
    return settled
}

internal suspend fun GlobalDownloadManager.pauseDownloadTasksForNetworkPolicy(
    context: Context,
    policySnapshot: WifiBoundNetworkPolicySnapshot,
    networkPolicyEpoch: Long = wifiBoundNetworkPolicyEpoch.get(),
    networkGeneration: Long = AudioDownloadManager.currentDownloadNetworkGeneration()
): Boolean {
    if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
        return false
    }
    if (policySnapshot.affectedSongKeys.isEmpty()) {
        return false
    }
    val paused = mutateWifiBoundNetworkPolicyIfStillRequired(
        context = context,
        snapshotEpoch = networkPolicyEpoch
    ) {
        AudioDownloadManager.pauseDownloadsForNetworkPolicy(
            policySnapshot.policyBoundSongKeys
        )
        taskStore.applyWaitingNetworkStatus(policySnapshot.displayWaitingTasks)
        policySnapshot.policyBoundTasks.filter { task ->
            task.status == DownloadStatus.DOWNLOADING ||
                AudioDownloadManager.isSongDownloadActive(task.song.stableKey())
        }.forEach { task ->
            DownloadExecutionHosts.default.stopForSong(
                context = context.applicationContext,
                songKey = task.song.stableKey(),
                preventReschedule = false
            )
        }
        mobileDataDownloadOverrideAllowed = false
    }
    if (!paused) {
        return false
    }
    if (policySnapshot.batchIdentities.isNotEmpty()) {
        runCatching {
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context = context.applicationContext,
                identities = policySnapshot.batchIdentities.map { identity ->
                    identity.toRoomBatchIdentity()
                },
                networkGeneration = networkGeneration,
                expectedNetworkGeneration = null
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "持久化批次网络等待状态失败，保留 operation 恢复路径: ${error.message}",
                error
            )
        }
    }
    scheduleWifiBoundDownloadWakeTasks(context, policySnapshot.affectedSongKeys)
    return true
}
