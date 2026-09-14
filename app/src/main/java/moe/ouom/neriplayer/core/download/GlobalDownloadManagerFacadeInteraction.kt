package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.GlobalDownloadManager.TrafficRiskDownloadRequest
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.MobileDataDownloadInterruptionRequest
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull

internal fun GlobalDownloadManager.confirmTrafficRiskDownloadImpl(
    context: Context,
    request: TrafficRiskDownloadRequest
) {
    if (request.isBatch) {
        startBatchDownload(context, request.songs, skipTrafficRiskPrompt = true)
        return
    }
    request.songs.firstOrNull()?.let { song ->
        scheduleUserDownload(context, song, skipTrafficRiskPrompt = true)
    }
}

internal fun GlobalDownloadManager.updateTaskStatusImpl(
    songKey: String,
    status: DownloadStatus,
    expectedAttemptId: Long? = null,
    settleBatchPresentation: Boolean = true,
    operationId: String? = null
) {
    val updated = taskStore.updateTaskStatus(
        songKey = songKey,
        status = status,
        expectedAttemptId = expectedAttemptId
    )
    if ((!updated && operationId.isNullOrBlank()) || !settleBatchPresentation) {
        return
    }
    val terminalOperationId = operationId?.takeIf(String::isNotBlank)
        ?: batchOperationIdForAttempt(songKey, expectedAttemptId)
    when (status) {
        DownloadStatus.COMPLETED -> markBatchDownloadPresentationTerminal(
            songKey = songKey,
            attemptId = expectedAttemptId,
            terminalState = BatchDownloadTerminalState.COMPLETED,
            operationId = terminalOperationId
        )

        DownloadStatus.FAILED -> markBatchDownloadPresentationTerminal(
            songKey = songKey,
            attemptId = expectedAttemptId,
            terminalState = BatchDownloadTerminalState.FAILED,
            operationId = terminalOperationId
        )

        DownloadStatus.CANCELLED -> markBatchDownloadPresentationTerminal(
            songKey = songKey,
            attemptId = expectedAttemptId,
            terminalState = BatchDownloadTerminalState.CANCELLED,
            operationId = terminalOperationId
        )

        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.WAITING_NETWORK -> Unit
    }
}

internal fun GlobalDownloadManager.clearBatchDownloadPresentationImpl(batchId: Long? = null) {
    if (batchId == null) {
        durableBatchIdentityByPresentationId.clear()
    } else {
        durableBatchIdentityByPresentationId.remove(batchId)
    }
    _batchDownloadPresentations.update { presentations ->
        if (batchId == null) {
            emptyMap()
        } else {
            presentations - batchId
        }
    }
}

internal fun GlobalDownloadManager.interruptDownloadsForWifiDisconnectedImpl(
    callbackNetworkType: TrafficNetworkType?,
    networkGeneration: Long? = null
) {
    val appContext = AppContainer.applicationContext
    val capturedNetworkGeneration = networkGeneration
        ?: AudioDownloadManager.currentDownloadNetworkGeneration()
    val initialNetworkType = appContext.currentDownloadNetworkTypeOrNull()
    if (callbackNetworkType == null || initialNetworkType == null) {
        mobileDataDownloadOverrideAllowed = false
        scope.launch {
            pauseActiveDownloadsForUnknownNetwork(
                context = appContext,
                reason = "wifi_disconnected_unknown_network"
            )
        }
        return
    }
    if (!shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
            callbackNetworkType = callbackNetworkType,
            currentNetworkType = initialNetworkType
        )
    ) {
        NPLogger.d(
            TAG,
            "忽略未生效的 WIFI 断开回调: callbackType=$callbackNetworkType, " +
                "currentType=$initialNetworkType"
        )
        return
    }
    mobileDataDownloadOverrideAllowed = false
    scope.launch {
        val currentNetworkType = appContext.currentDownloadNetworkTypeOrNull()
        if (currentNetworkType == null) {
            pauseActiveDownloadsForUnknownNetwork(
                context = appContext,
                reason = "wifi_disconnected_network_snapshot_lost"
            )
            return@launch
        }
        if (!shouldPauseDownloadsForWifiDisconnect(
                callbackNetworkType = callbackNetworkType,
                currentNetworkType = currentNetworkType
            )
        ) {
            NPLogger.d(
                TAG,
                "忽略未生效的 WIFI 断开回调: callbackType=$callbackNetworkType, currentType=$currentNetworkType"
            )
            return@launch
        }
        NPLogger.w(
            TAG,
            "WIFI 已断开，开始快速收敛下载网络策略: " +
                "callbackType=$callbackNetworkType, currentType=$currentNetworkType"
        )
        pauseActiveDownloadsForNetworkPolicyIfNeeded(
            context = appContext,
            networkType = currentNetworkType,
            reason = "wifi_disconnected",
            networkGeneration = capturedNetworkGeneration
        )
    }
}

internal fun GlobalDownloadManager.continueDownloadsOnMobileDataImpl(
    context: Context,
    request: MobileDataDownloadInterruptionRequest
) {
    scope.launch {
        val appContext = context.applicationContext
        var accepted = false
        mobileDataDownloadInterruptionRequestMutex.withLock {
            val currentRequest = _mobileDataDownloadInterruptionRequest.value
            if (currentRequest?.id != request.id ||
                currentRequest.networkGeneration != request.networkGeneration ||
                currentRequest.batchIdentities != request.batchIdentities
            ) {
                return@withLock
            }
            val currentNetworkType = appContext.currentDownloadNetworkTypeOrNull()
            val currentGeneration = AudioDownloadManager.currentDownloadNetworkGeneration()
            if (currentNetworkType == TrafficNetworkType.WIFI ||
                currentGeneration != request.networkGeneration
            ) {
                NPLogger.d(
                    TAG,
                    "移动网络确认已过期，拒绝恢复: requestId=${request.id}, " +
                        "requestGeneration=${request.networkGeneration}, " +
                        "currentGeneration=$currentGeneration"
                )
                dismissMobileDataDownloadInterruptionRequest()
                return@withLock
            }
            val identities = request.batchIdentities
                .distinct()
                .map { identity -> identity.toRoomBatchIdentity() }
            val allowedCount = if (identities.isEmpty()) {
                0
            } else {
                runCatching {
                    DownloadExecutionRoomStore.allowBatchesMobileData(
                        context = appContext,
                        identities = identities,
                        expectedNetworkGeneration = request.networkGeneration,
                        networkGeneration = currentGeneration
                    )
                }.getOrElse { error ->
                    NPLogger.w(
                        TAG,
                        "移动网络确认批次 CAS 失败: ${error.message}",
                        error
                    )
                    0
                }
            }
            if (identities.isNotEmpty() && allowedCount != identities.size) {
                NPLogger.w(
                    TAG,
                    "移动网络确认批次已过期或不完整，拒绝全局恢复: " +
                        "requestId=${request.id}, allowed=$allowedCount, expected=${identities.size}"
                )
                return@withLock
            }
            synchronized(wifiBoundNetworkPolicyMutationLock) {
                if (appContext.currentDownloadNetworkTypeOrNull() == currentNetworkType &&
                    AudioDownloadManager.currentDownloadNetworkGeneration() == currentGeneration
                ) {
                    mobileDataDownloadOverrideAllowed = true
                    dismissMobileDataDownloadInterruptionRequest()
                    accepted = true
                }
            }
        }
        if (!accepted) return@launch
        recoverPendingDownloadsOnCurrentNetwork(appContext)
    }
}

internal fun GlobalDownloadManager.waitDownloadsForWifiImpl(request: MobileDataDownloadInterruptionRequest) {
    if (_mobileDataDownloadInterruptionRequest.value?.id != request.id) {
        return
    }
    dismissMobileDataDownloadInterruptionRequest()
    mobileDataDownloadOverrideAllowed = false
    scope.launch {
        val appContext = AppContainer.applicationContext
        val policySnapshot = captureWifiBoundNetworkPolicySnapshot(
            context = appContext,
            candidateTasks = currentActiveNetworkPolicyTasks()
        )
        val paused = pauseDownloadTasksForNetworkPolicy(
            context = appContext,
            policySnapshot = policySnapshot
        )
        if (!paused &&
            appContext.currentDownloadNetworkTypeOrNull() == TrafficNetworkType.WIFI
        ) {
            recoverPendingDownloadsForNetworkRestored(
                context = appContext,
                reason = "user_wait_wifi_network_already_restored"
            )
        }
    }
}

internal suspend fun GlobalDownloadManager.recoverPendingDownloadsFromWifiWakeImpl(
    context: Context,
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
): Boolean {
    val appContext = context.applicationContext
    return try {
        withPendingDownloadRecoverySlot("wifi_wake") {
        if (admissionTicket == null ||
            !isDownloadAdmissionTicketCurrent(appContext, admissionTicket)
        ) {
            return@withPendingDownloadRecoverySlot true
        }
        if (appContext.currentDownloadNetworkTypeOrNull() != TrafficNetworkType.WIFI) {
            return@withPendingDownloadRecoverySlot false
        }
        if (!onWifiBoundDownloadNetworkRestored(appContext, "wifi_wake")) {
            return@withPendingDownloadRecoverySlot false
        }
        promoteWaitingStorageMutationsForRecovery(
            context = appContext,
            admissionTicket = admissionTicket
        )
        if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
            return@withPendingDownloadRecoverySlot true
        }
        if (!admitArtifactRecoveryMutation(appContext, admissionTicket) {
                repairFinalizedDownloadedCoversFromRoot(appContext)
            }
        ) {
            return@withPendingDownloadRecoverySlot true
        }
        if (!hasPendingRecoveryCandidates(appContext)) {
            true
        } else {
            reconcilePendingDownloadArtifacts(
                context = appContext,
                admissionTicket = admissionTicket
            )
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                return@withPendingDownloadRecoverySlot true
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
                    reason = "wifi_wake_active_handoff"
                )
                NPLogger.d(
                    TAG,
                    "WIFI 唤醒仍有活动传输，已交给共享下载泵继续排队: " +
                        "pump=$pumpScheduled"
                )
                pumpScheduled
            } else {
                val accepted = recoverPendingResumableDownloads(
                    context = appContext,
                    reason = "wifi_wake",
                    admissionTicket = admissionTicket
                )
                if (!hasPendingRecoveryCandidates(appContext)) {
                    true
                } else {
                    NPLogger.d(
                        TAG,
                        "WIFI 唤醒恢复尚未成为终态，保留 WorkManager 重试: " +
                            "accepted=$accepted"
                    )
                    false
                }
            }
        }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "WIFI 唤醒恢复失败，将由 WorkManager 重试: ${error.message}",
            error
        )
        false
    }
}
