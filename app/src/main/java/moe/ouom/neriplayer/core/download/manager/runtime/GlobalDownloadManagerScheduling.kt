package moe.ouom.neriplayer.core.download.manager.runtime

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.FinalizationRecoverySnapshotCache
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionPumpResult
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.execution.worker.PostCoreDownloadRecoveryWorker
import moe.ouom.neriplayer.core.download.observability.DownloadStartupTrace
import moe.ouom.neriplayer.core.logging.NPLogger


internal fun GlobalDownloadManager.wakeDownloadExecutionPump(
    context: Context,
    reason: String,
    requestSuccessorWhenBusy: Boolean = true
): Boolean {
    val appContext = context.applicationContext
    val canRun = runCatching {
        downloadAdmissionGate.openTicketOrNull() != null &&
            !ForegroundDownloadWorker.isPumpBlocked(appContext)
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "唤醒共享下载泵前检查栅栏失败，保留持久队列: " +
                "reason=$reason, error=${error.message}",
            error
        )
        false
    }
    if (!canRun) return false

    // 进程内泵和 Worker 必须共享同一个代次。先占用进程内槽位，
    // 再预留代次，避免入队成功后 direct pump 结束却遗留 active generation
    val immediateSlotAcquired = immediatePumpRunning.compareAndSet(false, true)
    val reservedGeneration = if (immediateSlotAcquired) {
        ForegroundDownloadWorker.reservePumpGeneration(
            requestSuccessorWhenBusy = requestSuccessorWhenBusy
        )
    } else {
        null
    }
    if (immediateSlotAcquired && reservedGeneration == null) {
        // 已有 Worker 或进程内泵持有代次，request 已经记录 successor 意图
        immediatePumpRunning.set(false)
    }
    val scheduled = when {
        reservedGeneration != null -> runCatching {
            ForegroundDownloadWorker.schedulePumpGeneration(
                context = appContext,
                generation = reservedGeneration
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "唤醒共享下载泵失败，保留持久队列等待重试: " +
                    "reason=$reason, error=${error.message}",
                error
            )
        }.getOrDefault(false)

        immediateSlotAcquired -> true
        else -> runCatching {
            ForegroundDownloadWorker.schedulePump(
                context = appContext,
                requestSuccessorWhenBusy = requestSuccessorWhenBusy
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "唤醒共享下载泵失败，保留持久队列等待重试: " +
                    "reason=$reason, error=${error.message}",
                error
            )
        }.getOrDefault(false)
    }
    val immediateStarted = if (reservedGeneration != null) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var pumpResult = DownloadExecutionPumpResult.Completed
            try {
                pumpResult = DownloadExecutionHosts.pump(appContext)
            } catch (cancellation: CancellationException) {
                // 进程内泵被取消时不能把未完成队列误报为 Completed，
                // 否则本代会释放但不会留下可恢复的 successor
                pumpResult = DownloadExecutionPumpResult.Retry
                throw cancellation
            } catch (error: Throwable) {
                pumpResult = DownloadExecutionPumpResult.Retry
                NPLogger.w(
                    TAG,
                    "进程内共享下载泵执行失败，交给持久 Worker 重试: " +
                        "reason=$reason, error=${error.message}",
                    error
                )
            } finally {
                immediatePumpRunning.set(false)
                ForegroundDownloadWorker.completeImmediatePump(
                    context = appContext,
                    generation = reservedGeneration,
                    result = pumpResult
                )
            }
        }
        true
    } else {
        false
    }
    if (scheduled || immediateStarted) {
        NPLogger.d(
            TAG,
            "已唤醒共享下载泵: reason=$reason, " +
                "workManager=$scheduled, inProcess=$immediateStarted"
        )
    }
    return scheduled || immediateStarted
}

internal fun GlobalDownloadManager.scheduleStartupDispatchWatchdog(
    context: Context,
    generation: Long
) {
    val appContext = context.applicationContext
    synchronized(startupWatchdogLock) {
        startupWatchdogJob?.cancel()
        startupWatchdogJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(STARTUP_FIRST_TRANSFER_DEADLINE_MS)
            val firstSnapshot = DownloadStartupTrace.snapshot()
            if (
                firstSnapshot.generation != generation ||
                    firstSnapshot.t2Ns != null ||
                    firstSnapshot.blockedReason != null
            ) {
                return@launch
            }
            val hasPendingWork = try {
                DownloadExecutionRoomStore.listSchedulableForPumpPage(
                    context = appContext,
                    afterCursor = null,
                    limit = 1
                ).requests.isNotEmpty()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "启动首发看门狗读取持久队列失败，保留原恢复状态: ${error.message}",
                    error
                )
                false
            }
            if (!hasPendingWork) return@launch
            if (ForegroundDownloadWorker.isPumpBlocked(appContext)) {
                DownloadStartupTrace.markBlocked(
                    reason = "startup_execution_fence",
                    generation = generation
                )
                return@launch
            }
            val rescheduled = wakeDownloadExecutionPump(
                context = appContext,
                reason = "startup_first_transfer_deadline"
            )
            NPLogger.d(
                TAG,
                "启动首发看门狗完成一次有界补偿: " +
                    "generation=$generation, rescheduled=$rescheduled"
            )
            delay(STARTUP_WATCHDOG_RECHECK_DELAY_MS)
            val recheck = DownloadStartupTrace.snapshot()
            if (
                recheck.generation == generation &&
                    recheck.t2Ns == null &&
                    recheck.blockedReason == null &&
                    ForegroundDownloadWorker.isPumpBlocked(appContext)
            ) {
                DownloadStartupTrace.markBlocked(
                    reason = "startup_execution_fence_after_retry",
                    generation = generation
                )
            }
        }
    }
}

internal fun GlobalDownloadManager.wakeStartupDownloadExecutionAfterProgressRestore(
    context: Context,
    generation: Long
) {
    val appContext = context.applicationContext
    if (isDownloadClearFenceActive(appContext)) {
        NPLogger.d(TAG, "下载清空栅栏生效，跳过启动队列唤醒")
        return
    }
    if (!onWifiBoundDownloadNetworkRestored(appContext, "startup_progress_restored")) {
        wakeDownloadExecutionPump(appContext, "startup_progress_restored")
    }
    scheduleStartupDispatchWatchdog(
        context = appContext,
        generation = generation
    )
}

internal fun GlobalDownloadManager.resumePostCoreDownloadsAfterProgressRestore(
    context: Context,
    admissionTicket: Long?
) {
    val appContext = context.applicationContext
    val capturedAdmissionTicket = admissionTicket ?: return
    if (!isDownloadAdmissionTicketCurrent(appContext, capturedAdmissionTicket)) {
        return
    }
    val scheduled = PostCoreDownloadRecoveryWorker.wake(appContext)
    if (scheduled) {
        NPLogger.d(TAG, "启动已唤醒 core 收尾，进程内恢复与持久 Worker 共用同一代次")
    } else {
        NPLogger.w(TAG, "启动调度 core 收尾 Worker 失败，保留下次恢复入口")
    }
}

internal suspend fun GlobalDownloadManager.loadFinalizationRecoverySnapshot(
    context: Context,
    forceRefresh: Boolean,
    allowFreshCacheReuse: Boolean = true
): ManagedDownloadStorage.DownloadLibrarySnapshot? {
    val appContext = context.applicationContext
    val cacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(appContext)
    fun isFresh(cache: FinalizationRecoverySnapshotCache, nowMs: Long): Boolean {
        return cache.cacheKey == cacheKey &&
            nowMs - cache.loadedAtElapsedMs < FINALIZATION_RECOVERY_SNAPSHOT_TTL_MS
    }
    fun canReuse(
        cache: FinalizationRecoverySnapshotCache,
        nowMs: Long
    ): Boolean {
        return allowFreshCacheReuse &&
            isFresh(cache, nowMs) &&
            (!forceRefresh || cache.forceRefreshed)
    }

    val nowMs = SystemClock.elapsedRealtime()
    finalizationRecoverySnapshotCache
        ?.takeIf { cache -> canReuse(cache, nowMs) }
        ?.let { cache -> return cache.snapshot }
    return finalizationRecoverySnapshotMutex.withLock {
        val lockedNowMs = SystemClock.elapsedRealtime()
        finalizationRecoverySnapshotCache
            ?.takeIf { cache -> canReuse(cache, lockedNowMs) }
            ?.let { cache -> return@withLock cache.snapshot }
        val snapshot = try {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = appContext,
                forceRefresh = forceRefresh,
                // 收尾恢复必须看到没有 metadata 的正式音频，避免把已落盘文件当成不存在
                includeMetadataLessAudioForLegacyUpgrade = true
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "core 收尾共享目录快照失败，改用直接引用: error=${error.message}",
                error
            )
            null
        }
        finalizationRecoverySnapshotCache = FinalizationRecoverySnapshotCache(
            cacheKey = cacheKey,
            snapshot = snapshot,
            loadedAtElapsedMs = SystemClock.elapsedRealtime(),
            forceRefreshed = forceRefresh
        )
        snapshot
    }
}
