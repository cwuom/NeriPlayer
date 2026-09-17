package moe.ouom.neriplayer.core.download.manager.facade

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.shouldRunInitialDownloadScan
import moe.ouom.neriplayer.core.download.manager.admission.admitArtifactRecoveryMutation
import moe.ouom.neriplayer.core.download.manager.admission.completeStartupProgressRestoreReady
import moe.ouom.neriplayer.core.download.manager.admission.dismissMobileDataDownloadInterruptionRequest
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.promoteWaitingStorageMutationsForRecovery
import moe.ouom.neriplayer.core.download.manager.admission.scheduleStartupArtifactRecovery
import moe.ouom.neriplayer.core.download.manager.batch.requestAllDownloadTaskCancellation
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.batch.scheduleDeferredFullLibraryDeleteRecovery
import moe.ouom.neriplayer.core.download.manager.batch.scheduleDeferredTaskClearRecovery
import moe.ouom.neriplayer.core.download.manager.batch.scheduleTaskClearHardDeadline
import moe.ouom.neriplayer.core.download.manager.catalog.hasBlockingActiveDownloadOperationsForRecovery
import moe.ouom.neriplayer.core.download.manager.catalog.observeDownloadProgress
import moe.ouom.neriplayer.core.download.manager.catalog.restorePersistedDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.waitForActiveDownloadJobsToSettle
import moe.ouom.neriplayer.core.download.manager.catalog.waitForQueuedTasksToAttachToBatch
import moe.ouom.neriplayer.core.download.manager.commit.schedulePersistedTerminalTemporaryWriteCleanup
import moe.ouom.neriplayer.core.download.manager.recovery.observeStorageStartupRecovery
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingAudioWritesFromRoot
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingDownloadsForStartup
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingResumableDownloads
import moe.ouom.neriplayer.core.download.manager.recovery.recoverUnfinalizedPublishedAudioFromRoot
import moe.ouom.neriplayer.core.download.manager.recovery.repairFinalizedDownloadedCoversFromRoot
import moe.ouom.neriplayer.core.download.manager.recovery.repairPersistedPostCoreBatchCompletions
import moe.ouom.neriplayer.core.download.manager.recovery.restorePersistedBatchDownloadPresentations
import moe.ouom.neriplayer.core.download.manager.recovery.restorePersistedDownloadProgress
import moe.ouom.neriplayer.core.download.manager.runtime.deferPendingDownloadRecoveryForNetworkPolicyIfNeeded
import moe.ouom.neriplayer.core.download.manager.runtime.pauseActiveDownloadsForNetworkPolicyIfNeeded
import moe.ouom.neriplayer.core.download.manager.runtime.pauseActiveDownloadsForUnknownNetwork
import moe.ouom.neriplayer.core.download.manager.runtime.resumePostCoreDownloadsAfterProgressRestore
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.manager.runtime.wakeStartupDownloadExecutionAfterProgressRestore
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.model.shouldHandoffBlockedWifiRecoveryToSharedPump
import moe.ouom.neriplayer.core.download.policy.PendingDownloadRecoverySummary
import moe.ouom.neriplayer.core.download.policy.runDownloadStartupRecoverySafely
import moe.ouom.neriplayer.core.download.policy.shouldContinueWifiRecoveryProbe
import moe.ouom.neriplayer.core.download.storage.operation.discardMigrationTemporaryDirectory
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.notification.DownloadExecutionNotificationController
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.worker.DownloadStorageRecoveryWorker
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.worker.PostCoreDownloadRecoveryWorker
import moe.ouom.neriplayer.core.download.execution.worker.WifiBoundDownloadWakeWorker
import moe.ouom.neriplayer.core.download.observability.DownloadStartupRecoveryJournal
import moe.ouom.neriplayer.core.download.observability.DownloadStartupTrace
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.startup.AppStartupWorkGate
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull

internal fun GlobalDownloadManager.onWifiBoundDownloadNetworkRestoredImpl(
    context: Context,
    reason: String,
    networkGeneration: Long = AudioDownloadManager.currentDownloadNetworkGeneration()
): Boolean {
    val appContext = context.applicationContext
    val capturedNetworkGeneration = networkGeneration.coerceAtLeast(0L)
    synchronized(wifiBoundNetworkPolicyMutationLock) {
        if (appContext.currentDownloadNetworkTypeOrNull() != TrafficNetworkType.WIFI) {
            return false
        }
        wifiBoundNetworkPolicyEpoch.incrementAndGet()
        mobileDataDownloadOverrideAllowed = false
        dismissMobileDataDownloadInterruptionRequest()
    }
    scope.launch {
        if (isDownloadClearFenceActive(appContext)) {
            return@launch
        }
        val clearResult = runCatching {
            DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(
                context = appContext,
                networkGeneration = capturedNetworkGeneration
            )
        }
        clearResult.onFailure { error ->
            NPLogger.w(
                TAG,
                "清除批次网络等待状态失败，保留后续恢复路径: ${error.message}",
                error
            )
        }
        val rearmedRetryableKeys = runCatching {
            DownloadExecutionRoomStore.clearRetryDeadlinesForImmediateRecovery(appContext)
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "网络恢复后重排下载退避队列失败，保留持久截止时间: ${error.message}",
                error
            )
        }.getOrDefault(emptySet())
        val networkStillCurrent =
            appContext.currentDownloadNetworkTypeOrNull() == TrafficNetworkType.WIFI &&
                AudioDownloadManager.currentDownloadNetworkGeneration() ==
                capturedNetworkGeneration
        if (!networkStillCurrent || isDownloadClearFenceActive(appContext)) {
            NPLogger.d(
                TAG,
                "WIFI 围栏解除完成前网络代次已变化，跳过旧恢复唤醒: " +
                    "reason=$reason, generation=$capturedNetworkGeneration"
            )
            return@launch
        }
        val pumpScheduled = wakeDownloadExecutionPump(
            context = appContext,
            reason = "wifi_network_fence_released_$reason"
        )
        val postCoreScheduled = PostCoreDownloadRecoveryWorker.schedule(appContext)
        if (clearResult.isFailure || !pumpScheduled) {
            WifiBoundDownloadWakeWorker.scheduleAll(appContext)
        }
        NPLogger.d(
            TAG,
            "WIFI 下载网络围栏已收敛并唤醒共享泵: reason=$reason, " +
                "generation=$capturedNetworkGeneration, " +
                "cleared=${clearResult.getOrDefault(0)}, " +
                "rearmed=${rearmedRetryableKeys.size}, pump=$pumpScheduled, " +
                "postCore=$postCoreScheduled"
        )
    }
    NPLogger.d(TAG, "WIFI 下载网络已恢复，正在解除批次网络围栏: reason=$reason")
    return true
}

internal fun GlobalDownloadManager.resolveBatchWaitingOperationStableKeyImpl(
    directRequest: DownloadExecutionRequest?,
    metadataStableKey: String?,
    identityStableKey: String?
): String? {
    return directRequest?.song?.stableKey()?.trim()?.takeIf(String::isNotBlank)
        ?: metadataStableKey?.trim()?.takeIf(String::isNotBlank)
        ?: identityStableKey?.trim()?.takeIf(String::isNotBlank)
}

internal fun GlobalDownloadManager.isBatchWaitingOperationReadableImpl(
    directRequest: DownloadExecutionRequest?,
    metadataAvailable: Boolean,
    stableKey: String?,
    identityStableKey: String?
): Boolean {
    if (directRequest == null && !metadataAvailable) return false
    if (stableKey.isNullOrBlank()) return false
    return directRequest == null ||
        identityStableKey.isNullOrBlank() ||
        directRequest.song.stableKey() == identityStableKey
}

internal fun GlobalDownloadManager.recoverPendingDownloadsAfterStorageMutationImpl(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
        withPendingDownloadRecoverySlot("directory_mutation_complete") {
            ManagedDownloadDirectoryMutationFence.awaitOpen()
            if (ManagedDownloadDirectoryMutationFence.isActive(appContext)) {
                return@withPendingDownloadRecoverySlot
            }
            val admissionTicket = downloadAdmissionGate.openTicketOrNull() ?: run {
                NPLogger.d(TAG, "清空期间跳过目录变更后的下载恢复")
                return@withPendingDownloadRecoverySlot
            }
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            // 失败或被杀的迁移仍持有 durable checkpoint，不能在重试前
            // 把旧根的 pending 当成当前根的孤儿处理
            if (ManagedDownloadMigrationWorker.hasPersistedMigrationRecovery(appContext)) {
                NPLogger.d(
                    TAG,
                    "迁移恢复凭据仍在，延后目录变更后的 artifact 收敛"
                )
                return@withPendingDownloadRecoverySlot
            }
            // 迁移完成后先回放 .tmp/legacy pending 凭据，再清理无法继续的
            // 半成品，此时目录栅栏已经打开，不会与复制阶段竞争
            recoverPendingAudioWritesFromRoot(
                context = appContext,
                admissionTicket = admissionTicket
            )
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            // 迁移前置恢复会把 core 音频提升为正式文件但不会在栅栏内
            // 启动增强队列，释放后立即补回这些可播放但未收尾的条目
            recoverUnfinalizedPublishedAudioFromRoot(
                context = appContext,
                admissionTicket = admissionTicket
            )
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            val artifactRecovery = runCatching {
                var recovery = ManagedDownloadStorage.StartupRecoveryResult(
                    failedCount = 1
                )
                val admitted = admitArtifactRecoveryMutation(
                    context = appContext,
                    admissionTicket = admissionTicket
                ) {
                    recovery =
                        ManagedDownloadStorage.reconcilePendingArtifactsAfterStorageMutation(
                            appContext
                        )
                }
                if (admitted) recovery else ManagedDownloadStorage.StartupRecoveryResult(
                    failedCount = 1
                )
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "迁移后 pending artifact 收敛失败，保留凭据等待重试: " +
                        error.message,
                    error
                )
                ManagedDownloadStorage.StartupRecoveryResult(failedCount = 1)
            }
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            if (artifactRecovery.failedCount > 0) {
                NPLogger.w(
                    TAG,
                    "迁移后仍有未确认 artifact，保留恢复入口: " +
                        "failed=${artifactRecovery.failedCount}"
                )
            }
            val promotedCount = promoteWaitingStorageMutationsForRecovery(
                context = appContext,
                admissionTicket = admissionTicket
            )
            wakeDownloadExecutionPump(
                context = appContext,
                reason = "directory_mutation_complete"
            )
            if (promotedCount > 0) {
                recoverPendingResumableDownloads(
                    context = appContext,
                    reason = "directory_mutation_complete",
                    admissionTicket = admissionTicket
                )
            }
        }
    }
}

internal fun GlobalDownloadManager.wakeDownloadExecutionPumpAfterCoreCommitImpl(
    context: Context,
    operationId: String? = null,
    attemptId: Long? = null,
    transferOwnerToken: Long? = null
): Boolean {
    val releaseAccepted = operationId?.let { normalizedOperationId ->
        runCatching {
            DownloadExecutionHosts.onCoreCommitted(
                context = context.applicationContext,
                operationId = normalizedOperationId,
                attemptId = attemptId,
                transferOwnerToken = transferOwnerToken
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Core Commit 后释放传输槽位失败，等待 execute finally 收敛: " +
                    "operationId=$normalizedOperationId, error=${error.message}",
                error
            )
        }.getOrDefault(false)
    } ?: true
    if (!releaseAccepted) {
        NPLogger.d(
            TAG,
            "忽略未匹配的 Core Commit 传输槽位回调: " +
                "operationId=$operationId, attemptId=$attemptId, token=$transferOwnerToken"
        )
    }
    val wakeAccepted = runCatching {
        wakeDownloadExecutionPump(
            context = context,
            reason = "core_commit_durable",
            // Core Commit 已经向当前泵发送 transfer-release 信号；已有
            // owner 时不要再登记 successor，否则每首歌都会制造一条
            // WorkManager 接力链，拖慢真正的补位和尾项收口
            requestSuccessorWhenBusy = false
        )
    }.onFailure { error ->
        // 唤醒失败不能把已经 durable 的音频重新判成传输失败
        NPLogger.w(
            TAG,
            "core commit 后唤醒下载泵失败，保留持久队列: ${error.message}",
            error
        )
    }.getOrDefault(false)
    return releaseAccepted && wakeAccepted
}

internal fun GlobalDownloadManager.wakeDownloadExecutionPumpAfterTransferAdmissionDeferredImpl(context: Context) {
    runCatching {
        wakeDownloadExecutionPump(
            context = context.applicationContext,
            reason = "transfer_admission_deferred"
        )
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "传输槽位延期后唤醒下载泵失败，保留持久队列: ${error.message}",
            error
        )
    }
}

internal fun GlobalDownloadManager.wakeDownloadExecutionPumpAfterParallelismChangedImpl(context: Context) {
    runCatching {
        wakeDownloadExecutionPump(
            context = context.applicationContext,
            reason = "parallelism_changed"
        )
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "并行数变化后唤醒下载泵失败，保留持久队列: ${error.message}",
            error
        )
    }
}

internal fun GlobalDownloadManager.initializeImpl(context: Context) {
    if (!initializationStarted.compareAndSet(false, true)) return
    val appContext = context.applicationContext
    val previousStartup = DownloadStartupRecoveryJournal.read(appContext)
    DownloadStartupRecoveryJournal.install(appContext)
    // 先记录启动恢复起点，后续轻量泵和首个真实传输共享同一代次
    val startupGeneration = DownloadStartupTrace.begin(previousStartup?.generation)
    previousStartup?.let { record ->
        NPLogger.d(
            TAG,
            "发现上一次启动恢复现场: generation=${record.generation}, " +
                "phase=${record.phase}, reason=${record.blockedReason}"
        )
    }
    observeDownloadProgress()
    DownloadExecutionNotificationController.initialize(appContext)
    observeStorageStartupRecovery(appContext)
    // 在调度启动协程前恢复持久横幅，让重启中的迁移首帧就能显示
    ManagedLibraryProcessingCoordinator.restoreImmediately(appContext)
    // 空间不足时由独立 worker 低频探测，避免共享泵在满盘上忙等
    DownloadStorageRecoveryWorker.schedule(appContext)
    scope.launch {
        // 启动恢复请求绑定创建时的代次，清空期间排队的旧请求不能换用新票据
        val startupAdmissionTicket = downloadAdmissionGate.openTicketOrNull()
        val startupRecovered = runDownloadStartupRecoverySafely(
            block = {
        val restoredProcessingState =
            ManagedLibraryProcessingCoordinator.restore(appContext)
        var restoredDirectoryOperationId = restoredProcessingState
            .takeIf {
                it.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE
            }
            ?.operationId
        startupRecoveryMutex.withLock recovery@{
            // 物理删除已经完成但 catalog 或 intent 尚未同时落盘时
            // 上一进程可能先释放了下载栅栏，重新启动时以 intent 为准
            // 先重建 FULL 栅栏，再进入统一回放路径
            val pendingFullDeleteIntent =
                PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)
            if (!PersistentDownloadClearFenceStore.isActive(appContext) &&
                pendingFullDeleteIntent
            ) {
                PersistentDownloadClearFenceStore.beginClear(
                    DownloadClearPurpose.FULL_LIBRARY_DELETE
                )
                if (!PersistentDownloadClearFenceStore.activate(appContext)) {
                    NPLogger.w(
                        TAG,
                        "待完成的全选删除栅栏暂未落盘，保留恢复意图等待重试"
                    )
                }
            }
            if (PersistentDownloadClearFenceStore.isActive(appContext)) {
                completeStartupProgressRestoreReady()
                val persistedClearPurpose =
                    PersistentDownloadClearFenceStore.activePurpose(appContext)
                val clearPurpose = if (
                    pendingFullDeleteIntent
                ) {
                    DownloadClearPurpose.FULL_LIBRARY_DELETE
                } else {
                    persistedClearPurpose
                }
                NPLogger.w(
                    TAG,
                    "检测到未完成的下载清空，跳过启动恢复并继续收敛: purpose=$clearPurpose"
                )
                DownloadExecutionHosts.cancelAllOwned(appContext)
                if (clearPurpose == DownloadClearPurpose.FULL_LIBRARY_DELETE &&
                    PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)
                ) {
                    // 全选删除必须先恢复旧 catalog，再按持久意图回放
                    // 否则进程在隐藏 catalog 后死亡会只剩一条无目标的清空栅栏
                    restorePersistedDownloadedSongs(appContext)
                    scheduleDeferredFullLibraryDeleteRecovery(appContext)
                } else {
                    // 清空的是任务展示，不是已经提交的音频，先恢复 catalog
                    // 防止进程死亡后的瞬时空扫描把可播放歌曲变成白色条目
                    restorePersistedDownloadedSongs(appContext)
                    scheduleTaskClearHardDeadline(appContext)
                    requestAllDownloadTaskCancellation(
                        purpose = clearPurpose,
                        forceConvergence = true
                    )
                    // 进程重启不能让栅栏一直等到下一次启动，持久栅栏存在时做一次有界重试
                    scheduleDeferredTaskClearRecovery(
                        context = appContext,
                        purpose = clearPurpose
                    )
                }
                restoredDirectoryOperationId?.let { operationId ->
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        appContext,
                        operationId
                    )
                }
                return@recovery
            }
            if (
                startupAdmissionTicket == null ||
                    !isDownloadAdmissionTicketCurrent(
                        appContext,
                        startupAdmissionTicket
                    )
            ) {
                completeStartupProgressRestoreReady()
                NPLogger.d(TAG, "启动恢复票据已失效，跳过旧恢复请求")
                return@recovery
            }
            // 先记录系统用户主动结束进程，再恢复任务卡片；否则旧的 RUNNING
            // 行会在卡片恢复后才被标记，下一轮刷新可能永久保留旧进度状态
            val processStoppedKeys = DownloadExecutionHosts.default
                .markUserRequestedProcessExitOperations(appContext)
            if (processStoppedKeys.isNotEmpty()) {
                NPLogger.i(
                    TAG,
                    "检测到系统用户停止进程，恢复未完成 UIDT 下载: " +
                        "count=${processStoppedKeys.size}"
                )
            }
            val orphanedRunningKeys = runCatching {
                DownloadExecutionRoomStore.requeueOrphanedRunningOperations(appContext)
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "回收旧进程 RUNNING 下载失败，保留状态等待下次启动: " +
                        error.message,
                    error
                )
                emptySet()
            }
            if (orphanedRunningKeys.isNotEmpty()) {
                NPLogger.i(
                    TAG,
                    "旧进程传输已重新排队: count=${orphanedRunningKeys.size}"
                )
            }
            // 先恢复 Room 进度再打开交互闸门，避免重启 Worker 以空任务列表运行
            repairPersistedPostCoreBatchCompletions(
                context = appContext,
                admissionTicket = startupAdmissionTicket
            )
            restorePersistedBatchDownloadPresentations(appContext)
            if (appContext.currentDownloadNetworkTypeOrNull() == TrafficNetworkType.WIFI) {
                val startupNetworkGeneration =
                    AudioDownloadManager.currentDownloadNetworkGeneration()
                runCatching {
                    DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(
                        context = appContext,
                        networkGeneration = startupNetworkGeneration
                    )
                }.onFailure { error ->
                    NPLogger.w(
                        TAG,
                        "在线冷启动解除旧网络等待围栏失败，保留网络唤醒兜底: " +
                            error.message,
                        error
                    )
                }
            }
            val rearmedRetryableKeys = try {
                DownloadExecutionRoomStore.rearmRetryableOperationsAfterProcessRestart(
                    appContext
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "重启下载退避队列失败，保留原截止时间等待持久唤醒: ${error.message}",
                    error
                )
                emptySet()
            }
            if (rearmedRetryableKeys.isNotEmpty()) {
                NPLogger.i(
                    TAG,
                    "重启待重试下载已立即重新排队: count=${rearmedRetryableKeys.size}"
                )
            }
            restorePersistedDownloadProgress(
                context = appContext,
                admissionTicket = startupAdmissionTicket
            )
            resumePostCoreDownloadsAfterProgressRestore(
                context = appContext,
                admissionTicket = startupAdmissionTicket
            )
            wakeStartupDownloadExecutionAfterProgressRestore(
                context = appContext,
                generation = startupGeneration
            )
            // 迁移凭据必须先于任何旧库回填或目录 I/O 恢复。迁移 worker
            // 会独占目录栅栏并在最终扫描发布后再放行后续启动流程
            val migrationRecoveryPresent = try {
                ManagedDownloadMigrationWorker.hasPersistedMigrationRecovery(appContext)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "迁移恢复凭据检查失败，保守跳过启动目录操作: ${error.message}",
                    error
                )
                restoredDirectoryOperationId?.let { operationId ->
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        appContext,
                        operationId
                    )
                }
                return@recovery
            }
            val resumedMigrationWorkId = try {
                ManagedDownloadMigrationWorker.resumePersistedRequestIfNeeded(appContext)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "迁移恢复检查暂时失败，保留请求并跳过启动目录操作: ${error.message}",
                    error
                )
                restoredDirectoryOperationId?.let { operationId ->
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        appContext,
                        operationId
                    )
                }
                return@recovery
            }
            // 终态迁移可能在上次进程终止时只留下 WaitingForRetry。恢复器会在
            // 无活动 Worker 时收尾该状态，因此不能继续用启动前捕获的旧 operationId。
            restoredDirectoryOperationId = ManagedLibraryProcessingCoordinator.state.value
                .takeIf {
                    it.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE
                }
                ?.operationId
            if (resumedMigrationWorkId != null || migrationRecoveryPresent) {
                NPLogger.i(
                    TAG,
                    if (resumedMigrationWorkId != null) {
                        "启动恢复迁移任务，跳过并行目录扫描: workId=$resumedMigrationWorkId"
                    } else {
                        "检测到待处理迁移凭据，跳过并行目录扫描，等待显式恢复"
                    }
                )
                return@recovery
            }
            // 旧下载数据库回填不得阻塞首屏，目录和 catalog 恢复完成后由调度器后台执行
            AppStartupWorkGate.awaitInteractiveContentOrTimeout()
            val startupRecovery = ManagedDownloadStorage.consumeStartupRecoveryResult()
            if (startupRecovery.failedCount > 0) {
                NPLogger.w(
                    TAG,
                    "启动存储恢复存在未确认项，安排持久临时写入清理复查: " +
                        "failed=${startupRecovery.failedCount}"
                )
                schedulePersistedTerminalTemporaryWriteCleanup(appContext)
            }
            val restoredCatalog = restorePersistedDownloadedSongs(appContext)
            DownloadExecutionOperationStore().pruneTerminalOperations(
                context = appContext,
                cutoffMs = System.currentTimeMillis() -
                    TERMINAL_OPERATION_RETENTION_MS,
                limit = TERMINAL_OPERATION_PRUNE_LIMIT
            )
            recoverPendingDownloadsForStartup(
                context = appContext,
                admissionTicket = startupAdmissionTicket
            )
            repairFinalizedDownloadedCoversFromRoot(
                context = appContext,
                admissionTicket = startupAdmissionTicket
            )
            LegacyJsonCleanupScheduler.schedule(appContext, "download-startup")
            if (
                !shouldRunInitialDownloadScan(
                    catalogReady = restoredCatalog,
                    hasRecoveredEntries = startupRecovery.hasRecoveredEntries
                ) && restoredDirectoryOperationId == null
            ) {
                return@recovery
            }
            // 让首屏协程先获得一次调度机会，避免为目录扫描固定空等
            yield()
            val refreshOutcome = withTimeoutOrNull(STARTUP_INITIAL_SCAN_WAIT_TIMEOUT_MS) {
                scanLocalFilesAwait(
                    appContext,
                    forceRefresh = true
                )
            }
            if (refreshOutcome == null) {
                NPLogger.w(
                    TAG,
                    "启动目录扫描超过交互等待预算，继续后台扫描并保留恢复状态: " +
                        "timeoutMs=$STARTUP_INITIAL_SCAN_WAIT_TIMEOUT_MS"
                )
                restoredDirectoryOperationId?.let { operationId ->
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        appContext,
                        operationId
                    )
                }
                scheduleCatalogReconcile(appContext, forceRefresh = true)
                return@recovery
            }
            if (refreshOutcome is ManagedLibraryRefreshOutcome.Published) {
                scheduleStartupArtifactRecovery(appContext)
            } else {
                NPLogger.w(
                    TAG,
                    "初始下载目录扫描未发布，保留 artifact 收尾凭据等待后续重试: " +
                        "outcome=${refreshOutcome::class.simpleName}"
                )
            }
            restoredDirectoryOperationId?.let { operationId ->
                if (refreshOutcome is ManagedLibraryRefreshOutcome.Published) {
                    ManagedLibraryProcessingCoordinator.complete(
                        appContext,
                        operationId
                    )
                } else {
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        appContext,
                        operationId
                    )
                }
            }
        }
            },
            maxAttempts = STARTUP_RECOVERY_MAX_ATTEMPTS,
            retryDelayMs = STARTUP_RECOVERY_RETRY_DELAY_MS,
            onFailure = { error ->
                NPLogger.e(
                    TAG,
                    "下载启动恢复尝试失败，保留持久化状态等待重试: " +
                        "error=${error.message}",
                    error
                )
            }
        )
        if (!startupRecovered) {
            completeStartupProgressRestoreReady()
            wakeStartupDownloadExecutionAfterProgressRestore(
                context = appContext,
                generation = startupGeneration
            )
        }
    }
}

internal suspend fun GlobalDownloadManager.reconcileMaterializedLegacyDownloadsImpl(context: Context) {
    val appContext = context.applicationContext
    val admissionTicket = downloadAdmissionGate.openTicketOrNull() ?: return
    startupRecoveryMutex.withLock {
        if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) return
        recoverPendingAudioWritesFromRoot(
            context = appContext,
            admissionTicket = admissionTicket
        )
        recoverUnfinalizedPublishedAudioFromRoot(
            context = appContext,
            admissionTicket = admissionTicket
        )
        recoverPendingDownloadsForStartup(
            context = appContext,
            admissionTicket = admissionTicket
        )
        repairFinalizedDownloadedCoversFromRoot(
            context = appContext,
            admissionTicket = admissionTicket
        )
        if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) return
        scanLocalFilesAwait(appContext, forceRefresh = true)
    }
}

internal suspend fun GlobalDownloadManager.reconcilePendingDownloadsAfterMigrationBlockedImpl(
    context: Context,
    sourceDirectoryUri: String? = null
) {
    val summary = reconcilePendingDownloadsBeforeMigrationDetailed(
        context = context,
        sourceDirectoryUri = sourceDirectoryUri
    )
    if (!summary.isConverged) {
        NPLogger.i(
            TAG,
            "迁移 pending 尚未收敛，保留凭据等待下次 Worker: " +
                "remaining=${summary.remainingArtifactCount}, " +
                "initialComplete=${summary.initialScanComplete}, " +
                "pendingComplete=${summary.pendingScanComplete}"
        )
    }
}

internal suspend fun GlobalDownloadManager.reconcilePendingDownloadsBeforeMigrationDetailedImpl(
    context: Context,
    sourceDirectoryUri: String? = null,
    directoryMutationLeaseOwned: Boolean = false
): PendingDownloadRecoverySummary {
    val appContext = context.applicationContext
    return startupRecoveryMutex.withLock {
        fun emptySummary(): PendingDownloadRecoverySummary {
            return PendingDownloadRecoverySummary(
                leaseAcquired = directoryMutationLeaseOwned
            )
        }
        if (PersistentDownloadClearFenceStore.isActive(appContext)) {
            NPLogger.d(TAG, "下载清空栅栏仍在生效，延后迁移前 pending 收敛")
            return@withLock emptySummary()
        }
        val admissionTicket = downloadAdmissionGate.openTicketOrNull()
        if (
            admissionTicket == null ||
                !isDownloadAdmissionTicketCurrent(appContext, admissionTicket)
        ) {
            NPLogger.d(
                TAG,
                "下载清空准入未打开，延后迁移前 pending 收敛"
            )
            return@withLock emptySummary()
        }
        val initialState = ManagedLibraryProcessingCoordinator.restore(appContext)
        if (initialState is ManagedLibraryProcessingState.Running) {
            NPLogger.d(
                TAG,
                "已有目录处理正在运行，迁移前 pending 收敛延后: " +
                    "operationId=${initialState.operationId}"
            )
            return@withLock emptySummary()
        }
        if (
            initialState is ManagedLibraryProcessingState.WaitingForRetry &&
                initialState.reason != ManagedLibraryProcessingReason.DIRECTORY_CHANGE
        ) {
            NPLogger.d(
                TAG,
                "已有其他类型处理等待重试，迁移前 pending 收敛延后: " +
                    "operationId=${initialState.operationId}"
            )
            return@withLock emptySummary()
        }
        val mutationLease = if (directoryMutationLeaseOwned) {
            null
        } else {
            try {
                ManagedDownloadDirectoryMutationFence.closeAndDrain()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(
                    TAG,
                    "迁移前 pending 收敛无法取得目录租约: ${error.message}",
                    error
                )
                return@withLock emptySummary()
            }
        }
        try {
            // 清空可能在等待目录租约时启动，重新确认持久栅栏后再触碰 pending
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                NPLogger.d(
                    TAG,
                    "下载清空栅栏在迁移前租约取得后生效，延后 pending 收敛"
                )
                return@withLock emptySummary()
            }
            val stateWhileLeased = ManagedLibraryProcessingCoordinator.restore(appContext)
            if (stateWhileLeased is ManagedLibraryProcessingState.Running) {
                NPLogger.d(
                    TAG,
                    "取得目录租约后发现目录处理已开始，迁移前 pending 收敛延后: " +
                        "operationId=${stateWhileLeased.operationId}"
                )
                return@withLock emptySummary()
            }
            if (
                stateWhileLeased is ManagedLibraryProcessingState.WaitingForRetry &&
                    stateWhileLeased.reason != ManagedLibraryProcessingReason.DIRECTORY_CHANGE
                ) {
                NPLogger.d(TAG, "目录租约内发现其他处理状态，迁移前 pending 收敛延后")
                return@withLock emptySummary()
            }
            NPLogger.i(
                TAG,
                "迁移状态占位前先收敛 pending 音频，使用独占目录租约"
            )
            // 迁移不需要保留下载中的中间产物，先整体删除 .tmp 再做状态检查
            ManagedDownloadStorage.discardMigrationTemporaryDirectory(
                context = appContext,
                directoryUri = sourceDirectoryUri
            )
            val recovery = recoverPendingAudioWritesFromRoot(
                context = appContext,
                directoryMutationLeaseOwned = true,
                directoryUri = sourceDirectoryUri,
                admissionTicket = admissionTicket
            )
            val pendingScan = runCatching {
                // 源目录只用于确认迁移阻塞项，写回仍遵循当前配置根目录
                ManagedDownloadStorage.scanPendingDownloadArtifacts(
                    context = appContext,
                    directoryUri = sourceDirectoryUri,
                    useDefaultRootWhenDirectoryUriMissing = true
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                NPLogger.w(
                    TAG,
                    "迁移前 pending 最终扫描失败，保留源目录凭据: ${error.message}",
                    error
                )
                return@withLock recovery.copy(
                    leaseAcquired = true,
                    pendingScanComplete = false
                )
            }
            val summary = recovery.copy(
                leaseAcquired = true,
                pendingScanComplete = pendingScan.isComplete,
                // metadata-only 凭据会被迁移保留，只有与 pending 音频配对的项需要恢复
                remainingArtifactCount = pendingScan.migrationBlockingArtifactCount
            )
            NPLogger.i(
                TAG,
                "迁移前 pending 收敛检查完成: " +
                    "audio=${summary.discoveredAudioCount}, " +
                    "attempted=${summary.attemptedAudioCount}, " +
                    "failed=${summary.failedAudioCount}, " +
                    "remaining=${summary.remainingArtifactCount}, " +
                    "metadataOnly=${pendingScan.migrationMetadataOnlyArtifactCount}, " +
                    "complete=${summary.pendingScanComplete}"
            )
            summary
        } finally {
            mutationLease?.close()
        }
    }
}

internal fun GlobalDownloadManager.recoverPendingDownloadsForNetworkRestoredImpl(context: Context, reason: String) {
    val appContext = context.applicationContext
    val admissionTicket = downloadAdmissionGate.openTicketOrNull()
    if (admissionTicket == null) {
        NPLogger.d(TAG, "清空期间跳过网络恢复: reason=$reason")
        return
    }
    scope.launch {
        withPendingDownloadRecoverySlot("network:$reason") {
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            val restoredNetworkType = appContext.currentDownloadNetworkTypeOrNull()
                ?: return@withPendingDownloadRecoverySlot
            if (restoredNetworkType != TrafficNetworkType.WIFI) {
                val rearmedRetryableKeys = runCatching {
                    DownloadExecutionRoomStore.clearRetryDeadlinesForImmediateRecovery(
                        appContext
                    )
                }.onFailure { error ->
                    NPLogger.w(
                        TAG,
                        "移动网络恢复后重排下载退避队列失败，保留持久唤醒: " +
                            error.message,
                        error
                    )
                }.getOrDefault(emptySet())
                val pumpScheduled = wakeDownloadExecutionPump(
                    context = appContext,
                    reason = "confirmed_network_recovered_$reason"
                )
                NPLogger.d(
                    TAG,
                    "已在确认可用的移动网络上立即恢复可运行下载: " +
                        "count=${rearmedRetryableKeys.size}, pump=$pumpScheduled"
                )
                return@withPendingDownloadRecoverySlot
            }
            if (!onWifiBoundDownloadNetworkRestored(appContext, "recovery_$reason")) {
                return@withPendingDownloadRecoverySlot
            }
            promoteWaitingStorageMutationsForRecovery(
                context = appContext,
                admissionTicket = admissionTicket
            )
            // 核心文件可能在进程终止后仍然存在，但内存任务和可续传记录已经丢失
            // 因此先修复收尾，再检查普通队列
            recoverPendingAudioWritesFromRoot(
                context = appContext,
                admissionTicket = admissionTicket
            )
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            recoverUnfinalizedPublishedAudioFromRoot(
                context = appContext,
                admissionTicket = admissionTicket
            )
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            if (!admitArtifactRecoveryMutation(appContext, admissionTicket) {
                    repairFinalizedDownloadedCoversFromRoot(appContext)
                }
            ) {
                return@withPendingDownloadRecoverySlot
            }
            if (!hasPendingRecoveryCandidates(appContext)) {
                return@withPendingDownloadRecoverySlot
            }
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            waitForActiveDownloadJobsToSettle()
            waitForQueuedTasksToAttachToBatch()
            val hasBlockingActiveOperations = hasBlockingActiveDownloadOperationsForRecovery()
            if (shouldHandoffBlockedWifiRecoveryToSharedPump(
                    hasPendingCandidates = true,
                    hasBlockingActiveOperations = hasBlockingActiveOperations
                )
            ) {
                val pumpScheduled = wakeDownloadExecutionPump(
                    context = appContext,
                    reason = "network_recovery_active_handoff"
                )
                if (!pumpScheduled) {
                    WifiBoundDownloadWakeWorker.scheduleAll(appContext)
                }
                NPLogger.d(
                    TAG,
                    "WIFI 恢复仍有活动传输，已交给共享下载泵继续排队: " +
                        "pump=$pumpScheduled"
                )
                return@withPendingDownloadRecoverySlot
            }
            recoverPendingResumableDownloads(
                context = appContext,
                reason = reason,
                admissionTicket = admissionTicket
            )
            delay(1_500L)
        }
    }
}

internal fun GlobalDownloadManager.scheduleWifiRecoveryProbeImpl(context: Context, reason: String) {
    val appContext = context.applicationContext
    val admissionTicket = downloadAdmissionGate.openTicketOrNull()
    if (admissionTicket == null) {
        NPLogger.d(TAG, "清空期间跳过 WIFI 恢复探测: reason=$reason")
        return
    }
    synchronized(wifiRecoveryProbeLock) {
        if (wifiRecoveryProbeJob?.isActive == true) {
            return
        }
        wifiRecoveryProbeJob = scope.launch {
            try {
                repeat(WIFI_RECOVERY_PROBE_ATTEMPTS) { attempt ->
                    if (attempt > 0) {
                        delay(WIFI_RECOVERY_PROBE_DELAY_MS)
                    }
                    if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                        return@launch
                    }
                    val networkType = runCatching {
                        appContext.currentDownloadNetworkTypeOrNull()
                    }.getOrNull()
                    val hasPendingCandidates = runCatching {
                        hasPendingRecoveryCandidates(appContext)
                    }.getOrElse { error ->
                        NPLogger.d(
                            TAG,
                            "WIFI 恢复探测无法读取候选，保留下一次探测: " +
                                "reason=$reason, error=${error.message}"
                        )
                        true
                    }
                    if (!shouldContinueWifiRecoveryProbe(
                            networkType = networkType,
                            hasPendingCandidates = hasPendingCandidates,
                            attempt = attempt,
                            maxAttempts = WIFI_RECOVERY_PROBE_ATTEMPTS
                        ) &&
                        !(networkType == TrafficNetworkType.WIFI && hasPendingCandidates)
                    ) {
                        return@launch
                    }
                    val settled = recoverPendingDownloadsFromWifiWake(
                        context = appContext,
                        admissionTicket = admissionTicket
                    )
                    if (settled) {
                        return@launch
                    }
                }
            } finally {
                synchronized(wifiRecoveryProbeLock) {
                    wifiRecoveryProbeJob = null
                }
            }
        }
    }
}

internal fun GlobalDownloadManager.hasPendingRecoveryCandidatesImpl(context: Context): Boolean {
    val appContext = context.applicationContext
    if (isDownloadClearFenceActive(appContext)) {
        return false
    }
    val durableCandidates = runCatching {
        DownloadExecutionRoomStore.hasAnyByStatesAnyLibrary(
            context = appContext,
            states = NETWORK_POLICY_OPERATION_STATES
        )
    }.getOrElse { error ->
        NPLogger.d(
            TAG,
            "检查持久下载候选失败，保守保留恢复机会: ${error.message}"
        )
        true
    }
    if (durableCandidates) {
        return true
    }
    if (ManagedDownloadStorage.listPendingQueuedDownloads(appContext).isNotEmpty()) {
        return true
    }
    if (ManagedDownloadStorage.listPendingResumableDownloads(appContext).isNotEmpty()) {
        return true
    }
    return downloadTasks.value.any { task ->
        task.status == DownloadStatus.WAITING_NETWORK
    }
}

internal fun GlobalDownloadManager.requestPendingDownloadRecoveryDecisionIfNeededImpl(
    context: Context,
    reason: String
) {
    val appContext = context.applicationContext
    val admissionTicket = downloadAdmissionGate.openTicketOrNull()
    if (admissionTicket == null) {
        NPLogger.d(TAG, "清空期间跳过移动网络下载恢复复查: reason=$reason")
        return
    }
    scope.launch {
        withPendingDownloadRecoverySlot("decision:$reason") {
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            val networkType = appContext.currentDownloadNetworkTypeOrNull()
            if (networkType == TrafficNetworkType.WIFI) {
                onWifiBoundDownloadNetworkRestored(appContext, "decision_$reason")
                NPLogger.d(TAG, "跳过移动网络下载恢复复查: 当前是 WIFI, reason=$reason")
                return@withPendingDownloadRecoverySlot
            }
            val currentTasks = taskStore.currentTasks()
            val waitingTaskCount = currentTasks.count { task ->
                task.status == DownloadStatus.WAITING_NETWORK
            }
            val activeTaskCount = currentTasks.count { task ->
                task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
            }
            NPLogger.d(
                TAG,
                "复查移动网络下载恢复: reason=$reason, networkType=$networkType, " +
                    "waiting=$waitingTaskCount, active=$activeTaskCount, " +
                    "batchJobs=${activeBatchDownloadJobs.size}, " +
                    "single=${taskStore.isSingleDownloading}, " +
                    "pendingDialog=${mobileDataDownloadInterruptionRequestMutable.value != null}"
            )
            if (networkType == null) {
                pauseActiveDownloadsForUnknownNetwork(
                    context = appContext,
                    reason = reason,
                    admissionTicket = admissionTicket
                )
                if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                    return@withPendingDownloadRecoverySlot
                }
                deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(
                    context = appContext,
                    reason = reason,
                    admissionTicket = admissionTicket
                )
                return@withPendingDownloadRecoverySlot
            }
            if (
                pauseActiveDownloadsForNetworkPolicyIfNeeded(
                    context = appContext,
                    networkType = networkType,
                    reason = reason,
                    admissionTicket = admissionTicket
                )
            ) {
                return@withPendingDownloadRecoverySlot
            }
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            if (!hasPendingRecoveryCandidates(appContext)) {
                NPLogger.d(TAG, "跳过移动网络下载恢复复查: 没有恢复候选, reason=$reason")
                return@withPendingDownloadRecoverySlot
            }
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            waitForActiveDownloadJobsToSettle()
            waitForQueuedTasksToAttachToBatch()
            if (hasBlockingActiveDownloadOperationsForRecovery()) {
                NPLogger.d(TAG, "跳过移动网络下载恢复复查: 仍有活动下载, reason=$reason")
                return@withPendingDownloadRecoverySlot
            }
            val deferredSongKeys = deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(
                context = appContext,
                reason = reason,
                admissionTicket = admissionTicket
            )
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot
            }
            recoverPendingResumableDownloads(
                context = appContext,
                reason = reason,
                excludedSongKeys = deferredSongKeys,
                admissionTicket = admissionTicket
            )
        }
    }
}
