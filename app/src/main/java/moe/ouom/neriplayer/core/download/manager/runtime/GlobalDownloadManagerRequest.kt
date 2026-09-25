package moe.ouom.neriplayer.core.download.manager.runtime

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.execution.recovery.isArtifactRecoveryAllowed
import moe.ouom.neriplayer.core.download.shouldDeferDownloadExecutionForNetwork
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.awaitDownloadAdmissionTicket
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.isWifiBoundNetworkPolicyStillRequired
import moe.ouom.neriplayer.core.download.manager.admission.mutateWifiBoundNetworkPolicyIfStillRequired
import moe.ouom.neriplayer.core.download.manager.admission.openDownloadAdmissionTicketOrNull
import moe.ouom.neriplayer.core.download.manager.admission.promoteUserInitiatedInFlightRequests
import moe.ouom.neriplayer.core.download.manager.admission.recoverWifiBoundDownloadsIfNetworkPolicyExpired
import moe.ouom.neriplayer.core.download.manager.admission.scheduleStartupArtifactRecovery
import moe.ouom.neriplayer.core.download.manager.admission.stageAndPromotePendingDownloadQueue
import moe.ouom.neriplayer.core.download.manager.batch.cancellationOperationIdsForSong
import moe.ouom.neriplayer.core.download.manager.batch.clearSongCancellationForFreshStart
import moe.ouom.neriplayer.core.download.manager.batch.findPendingAudioForFinalization
import moe.ouom.neriplayer.core.download.manager.batch.isDownloadRequestGenerationCurrent
import moe.ouom.neriplayer.core.download.manager.batch.markBatchDownloadPresentationTerminal
import moe.ouom.neriplayer.core.download.manager.batch.maybeRequestTrafficRiskDownloadConfirmation
import moe.ouom.neriplayer.core.download.manager.batch.recoverInFlightDownloadOperations
import moe.ouom.neriplayer.core.download.manager.catalog.awaitDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.catalog.deferDownloadForDeleteCleanup
import moe.ouom.neriplayer.core.download.manager.catalog.scheduleDeleteCleanupRetry
import moe.ouom.neriplayer.core.download.manager.commit.finalizeCompletedDownload
import moe.ouom.neriplayer.core.download.manager.commit.isDownloadMetadataPostProcessingEnabled
import moe.ouom.neriplayer.core.download.manager.recovery.invalidCoreAudioReason
import moe.ouom.neriplayer.core.download.manager.recovery.requeueInvalidCoreAudio
import moe.ouom.neriplayer.core.download.manager.recovery.requeueConfirmedMissingCoreAudio
import moe.ouom.neriplayer.core.download.model.BatchDownloadTerminalState
import moe.ouom.neriplayer.core.download.model.BatchOperationScheduleAction
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.resolveBatchOperationScheduleAction
import moe.ouom.neriplayer.core.download.policy.isDownloadFinalizationDurablySettled
import moe.ouom.neriplayer.core.download.manager.recovery.claimArtifactForRecovery
import moe.ouom.neriplayer.core.download.policy.requiresDownloadFinalizationRecovery
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.MobileDataDownloadBatchIdentity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionSchedule
import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.persistence.METADATA_ACTION_REQUIRED_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.state.ARTIFACT_LEASE_CONTENDED_ERROR_CODE
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull


internal fun GlobalDownloadManager.scheduleUserDownload(
    context: Context,
    song: SongItem,
    skipTrafficRiskPrompt: Boolean,
    preserveStaging: Boolean = false,
    replacingAttemptId: Long? = null,
    requestedAdmissionTicket: Long? = null,
    manualRetry: Boolean = false
) {
    val appContext = context.applicationContext
    // 请求创建时记录代次，清空开始后旧协程只能退出不能重新登记任务
    val songKey = song.stableKey()
    val capturedAdmissionTicket = requestedAdmissionTicket
        ?: openDownloadAdmissionTicketOrNull(
            context = appContext,
            stableKey = songKey
        )
    scope.launch {
        val deletionSettled = awaitDownloadedSongDeletion(setOf(songKey))
        if (!deletionSettled) {
            val deferred = deferDownloadForDeleteCleanup(
                context = appContext,
                songs = listOf(song),
                userInitiated = true,
                admissionTicket = capturedAdmissionTicket
            )
            if (!deferred) {
                NPLogger.d(
                    TAG,
                    "删除清理等待意图已过期，放弃单曲下载请求: song=${song.name}"
                )
            } else {
                scheduleDeleteCleanupRetry(
                    context = appContext,
                    songKeys = setOf(songKey),
                    admissionTicket = capturedAdmissionTicket
                )
            }
            return@launch
        }
        val admissionTicket = capturedAdmissionTicket
            ?: awaitDownloadAdmissionTicket(
                context = appContext,
                stableKey = songKey
            )
        var inFlightRequestToRecover: DownloadExecutionRequest? = null
        var inFlightStateToRecover: String? = null
        var operationPersisted = false
        val admitted = admitDownloadMutation(
            context = appContext,
            admissionTicket = admissionTicket,
            stableKey = songKey
        ) admission@{
            if (!clearSongCancellationForFreshStart(appContext, setOf(songKey))) {
                NPLogger.w(
                    TAG,
                    "单曲下载暂缓，旧取消 operation 快照尚未完成: song=${song.name}"
                )
                return@admission
            }
            if (
                maybeRequestTrafficRiskDownloadConfirmation(
                    context = appContext,
                    songs = listOf(song),
                    isBatch = false,
                    skipTrafficRiskPrompt = skipTrafficRiskPrompt
                )
            ) {
                return@admission
            }
            val existingInFlightRequest = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = listOf(songKey),
                    states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                    excludeUserStoppedOperations = true,
                    excludedOperationIds = cancellationOperationIdsForSong(songKey)
                )[songKey]
                ?.takeIf { request -> request.song.stableKey() == songKey }
            if (existingInFlightRequest != null) {
                inFlightRequestToRecover = promoteUserInitiatedInFlightRequests(
                    context = appContext,
                    requests = listOf(existingInFlightRequest),
                    userInitiated = true
                ).single()
                inFlightStateToRecover = DownloadExecutionRoomStore.state(
                    context = appContext,
                    operationId = existingInFlightRequest.operationId
                )
                operationPersisted = true
                NPLogger.d(
                    TAG,
                    "单曲下载复用执行中 operation 并提升用户重试意图: " +
                        "song=${song.name}, operationId=${existingInFlightRequest.operationId}"
                )
                return@admission
            }
            val stagedQueue = stageAndPromotePendingDownloadQueue(
                context = appContext,
                songs = listOf(song),
                userInitiated = true
            )
            val operationId = stagedQueue.operationIds.singleOrNull()
            if (operationId == null) {
                NPLogger.w(TAG, "持久化下载 operation 失败: song=${song.name}")
                return@admission
            }
            operationPersisted = true
            var operationState = DownloadExecutionRoomStore.state(appContext, operationId)
            val persistedRequest = DownloadExecutionRoomStore.read(appContext, operationId)
            if (persistedRequest?.song?.stableKey() != song.stableKey()) {
                NPLogger.w(
                    TAG,
                    "持久化下载 operation 未确认，保留队列等待恢复: song=${song.name}"
                )
                return@admission
            }
            if (
                operationState == WAITING_STORAGE_MUTATION_OPERATION_STATE ||
                    ManagedDownloadDirectoryMutationFence.isActive(appContext)
            ) {
                taskStore.updateTaskStatus(
                    songKey = song.stableKey(),
                    status = DownloadStatus.WAITING_NETWORK,
                    expectedAttemptId = replacingAttemptId
                )
                NPLogger.d(
                    TAG,
                    "单曲下载恢复已登记等待目录迁移: " +
                        "song=${song.name}, operationId=$operationId"
                )
                return@admission
            }
            if (operationState == METADATA_ACTION_REQUIRED_OPERATION_STATE) {
                if (isDownloadMetadataPostProcessingEnabled(appContext)) {
                    NPLogger.w(
                        TAG,
                        "下载容器需要关闭内嵌元信息后才能继续: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@admission
                }
                val reopened = DownloadExecutionRoomStore.updateState(
                    context = appContext,
                    operationId = operationId,
                    state = "ASSETS_ENRICHING"
                )
                if (!reopened) {
                    NPLogger.w(
                        TAG,
                        "无法在关闭内嵌元信息后恢复下载收尾: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@admission
                }
                operationState = DownloadExecutionRoomStore.state(appContext, operationId)
            }
            if (operationState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES) {
                inFlightRequestToRecover = persistedRequest
                NPLogger.d(
                    TAG,
                    "单曲下载复用并检查执行中的 operation: " +
                        "song=${song.name}, operationId=$operationId"
                )
                return@admission
            }
            if (operationState !in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES) {
                NPLogger.w(
                    TAG,
                    "单曲下载 operation 不可调度: song=${song.name}, state=$operationState"
                )
                return@admission
            }
            val request = persistedRequest.copy(
                preserveStaging = persistedRequest.preserveStaging || preserveStaging,
                userInitiated = true
            )
            taskStore.updateTaskStatus(
                songKey = song.stableKey(),
                status = DownloadStatus.QUEUED,
                expectedAttemptId = replacingAttemptId
            )
            DownloadExecutionRoomStore.upsert(
                context = appContext,
                request = request,
                state = "QUEUED"
            )
            if (manualRetry) {
                requestManualRetryTransferBoost(request)
            }
            publishDownloadStage(
                song = song,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST,
                operationId = operationId,
                attemptId = taskStore.findTask(song.stableKey())?.attemptId
            )
            val schedule = DownloadExecutionHosts.default.schedule(
                context = appContext,
                request = request
            )
            if (schedule is DownloadExecutionSchedule.Deferred) {
                NPLogger.d(
                    TAG,
                    "单曲下载已登记等待全局宿主槽位: " +
                        "song=${song.name}, operationId=$operationId"
                )
            } else if (schedule is DownloadExecutionSchedule.Rejected) {
                val retryMarked = runCatching {
                    DownloadExecutionRoomStore.markScheduleRejectedRetryable(
                        context = appContext,
                        operationId = operationId,
                        stableKey = song.stableKey(),
                        errorCode = "OS_HOST_REJECTED"
                    )
                }.onFailure { error ->
                    NPLogger.w(TAG, "写入单曲 operation 重试状态失败: ${error.message}")
                }.getOrDefault(false)
                if (!retryMarked) {
                    val latestState = runCatching {
                        DownloadExecutionRoomStore.state(appContext, operationId)
                    }.getOrNull()
                    val latestRequest = runCatching {
                        DownloadExecutionRoomStore.read(appContext, operationId)
                    }.getOrNull()
                    if (
                        resolveBatchOperationScheduleAction(
                            operationState = latestState,
                            requestMatchesSong =
                                latestRequest?.song?.stableKey() == song.stableKey()
                        ) == BatchOperationScheduleAction.HANDED_OFF
                    ) {
                        NPLogger.d(
                            TAG,
                            "单曲下载调度竞态中 operation 已被接管: " +
                                "song=${song.name}, operationId=$operationId, " +
                                "state=$latestState"
                        )
                        return@admission
                    }
                }
                NPLogger.w(
                    TAG,
                    "OS 下载宿主调度失败: song=${song.name}, reason=${schedule.reason}"
                )
            }
        }
        if (admitted) {
            inFlightRequestToRecover?.let { request ->
                if (manualRetry && requiresDownloadFinalizationRecovery(inFlightStateToRecover)) {
                    recoverPostCoreDownloadOperation(
                        context = appContext,
                        song = request.song,
                        operationId = request.operationId,
                        expectedAttemptId = request.attemptId,
                        admissionTicket = admissionTicket,
                        expeditedAssetEnrichment = true
                    )
                } else {
                    if (manualRetry) {
                        requestManualRetryTransferBoost(request)
                    }
                    recoverInFlightDownloadOperations(
                        context = appContext,
                        requests = listOf(request),
                        admissionTicket = admissionTicket
                    )
                }
            }
            if (operationPersisted) {
                // 新 operation 已经落盘后立即唤醒共享泵，避免依赖下一次
                // 网络回调或 WorkManager 的不确定调度延迟
                wakeDownloadExecutionPump(
                    context = appContext,
                    reason = "user_download_queued"
                )
            }
        }
        if (!admitted) {
            NPLogger.d(TAG, "清空任务已使单曲下载请求过期: song=${song.name}")
        }
    }
}

internal suspend fun GlobalDownloadManager.reopenMissingPostCoreArtifactForFreshTransfer(
    context: Context,
    song: SongItem,
    operationId: String,
    expectedAttemptId: Long?
): Boolean {
    val request = DownloadExecutionRoomStore.read(context, operationId)
        ?.takeIf { persisted -> persisted.song.stableKey() == song.stableKey() }
        ?: return false
    val artifactClaim = try {
        managedDownloadArtifactCoordinator.claim(
            context = context,
            song = song,
            reconcileStorage = false,
            leaseOwnerId = request.artifactLeaseId,
            // 这是自动的 post-core 恢复，不是用户明确的重新下载。
            // 即使请求最初来自用户，也不能在已有音频引用时把收尾重新抢成传输。
            allowFreshTransferReclaim = false
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "检查缺失 core 音频引用失败，保留收尾恢复: " +
                "song=${song.name}, operationId=$operationId, error=${error.message}",
            error
        )
        return false
    }
    if (!shouldRestartPostCoreOperationForFreshTransfer(
            operationState = DownloadExecutionRoomStore.state(context, operationId),
            artifactClaim = artifactClaim
        )
    ) {
        return false
    }
    val acquiredArtifact = (artifactClaim as? ManagedDownloadArtifactClaim.Acquired)
        ?.artifact
        ?: return false
    val leaseId = acquiredArtifact.leaseId
        ?: return false
    // artifact 行可能在上一轮收尾中丢失引用，但正式目录或 .tmp 里仍保留
    // 同一 operation 的可恢复音频。先重新绑定物理凭据，禁止无谓重传。
    val recoverableAudio = findPendingAudioForFinalization(
        context = context,
        song = song,
        operationId = operationId,
        preferredAudioName = acquiredArtifact.audioName,
        preferredAudioReference = acquiredArtifact.audioReference
    )
    if (recoverableAudio != null) {
        val reboundResult = runCatching {
            managedDownloadArtifactCoordinator.markCoreCommitted(
                context = context,
                song = song,
                storedAudio = recoverableAudio,
                expectedLeaseId = leaseId
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "已有可恢复音频但 artifact 引用回写失败，保留收尾恢复: " +
                    "song=${song.name}, operationId=$operationId, " +
                    "error=${error.message}",
                error
            )
        }.getOrNull()
        val rebound = reboundResult?.isApplied == true
        if (rebound) {
            managedDownloadArtifactLeases[song.stableKey()] = leaseId
            NPLogger.d(
                TAG,
                "core operation 已有可恢复音频，跳过重新传输: " +
                    "song=${song.name}, operationId=$operationId, " +
                    "file=${recoverableAudio.name}"
            )
        } else {
            NPLogger.w(
                TAG,
                "已有可恢复音频但 artifact 引用未确认，保留收尾恢复: " +
                    "song=${song.name}, operationId=$operationId, result=$reboundResult"
            )
        }
        return false
    }
    val reopened = DownloadExecutionRoomStore.reopenMissingPostCoreArtifactForFreshTransfer(
        context = context,
        operationId = operationId,
        stableKey = song.stableKey(),
        expectedAttemptId = expectedAttemptId ?: request.attemptId,
        errorCode = "CORE_AUDIO_REFERENCE_MISSING"
    )
    if (!reopened) {
        return false
    }
    managedDownloadArtifactLeases[song.stableKey()] = leaseId
    NPLogger.w(
        TAG,
        "core operation 缺少音频引用，已重新开启真实传输: " +
            "song=${song.name}, operationId=$operationId"
    )
    return true
}

internal suspend fun GlobalDownloadManager.recoverPostCoreDownloadOperation(
    context: Context,
    song: SongItem,
    operationId: String,
    expectedAttemptId: Long?,
    admissionTicket: Long,
    expeditedAssetEnrichment: Boolean = false
): Boolean {
    var finalized = false
    withSongExecutionLock(song.stableKey()) {
        // Worker 可能在原下载登记增强协程之前选中同一 operation，拿到歌曲锁后
        // 必须重新检查进程内 owner，避免用恢复 lease 抢走仍在执行的原收尾任务
        if (assetEnrichmentCoordinator.isActive(operationId)) {
            NPLogger.d(
                TAG,
                "core operation 已由进程内资产增强接管，跳过重复恢复: " +
                    "song=${song.name}, operationId=$operationId"
            )
            return@withSongExecutionLock
        }
        val pendingRequest = DownloadExecutionRoomStore.read(context, operationId)
            ?.takeIf { request ->
                request.song.stableKey() == song.stableKey() &&
                    (expectedAttemptId == null || request.attemptId == expectedAttemptId)
            } ?: return@withSongExecutionLock
        if (!DownloadExecutionRoomStore.isArtifactRecoveryAllowed(context, operationId)) {
            return@withSongExecutionLock
        }
        // 收尾不再经过传输入口，必须独立解除旧网络暂停，且不能覆盖新的断网或取消
        val networkReady = synchronized(wifiBoundNetworkPolicyMutationLock) {
            if (!isDownloadAdmissionTicketCurrent(
                    context, admissionTicket,
                    stableKey = song.stableKey(), operationId = operationId
                ) ||
                isSongCancelled(song.stableKey()) ||
                !isPostCoreRecoveryNetworkEligible(
                    pendingRequest.requiresWifiNetwork,
                    context.currentDownloadNetworkTypeOrNull(),
                    mobileDataDownloadOverrideAllowed
                )
            ) {
                false
            } else {
                AudioDownloadManager.clearNetworkPolicyPause(setOf(song.stableKey()))
                AudioDownloadManager.clearOperationPauseForExecutionHost(operationId)
                true
            }
        }
        if (!networkReady) return@withSongExecutionLock
        val recoveryClaim = claimArtifactForRecovery(
            context = context,
            song = song,
            operationId = operationId
        )
        val claim = recoveryClaim?.claim
        if (claim == null || claim is ManagedDownloadArtifactClaim.InFlight) {
            NPLogger.d(
                TAG,
                "core operation 的 artifact 正由其它 owner 收尾，等待下一轮恢复: " +
                    "song=${song.name}, operationId=$operationId"
            )
            scheduleStartupArtifactRecovery(context)
            return@withSongExecutionLock
        }
        val request = recoveryClaim.request
        val artifact = recoveryClaim.artifact
        // 恢复使用独立且跨进程稳定的 owner，迟到的原下载回调仍携带 request lease
        // 因而不能再把当前恢复租约清成 FAILED_RETRYABLE
        val expectedArtifactLeaseId = artifact?.leaseId
        val referencedAudio = artifact?.audioReference?.let { resolveStoredAudio(context, it) }
        val invalidReason = referencedAudio?.let { invalidCoreAudioReason(context, song, it, operationId) }
        if (referencedAudio != null && invalidReason != null) {
            requeueInvalidCoreAudio(
                context, song, operationId, referencedAudio.reference,
                expectedArtifactLeaseId, invalidReason
            )
            return@withSongExecutionLock
        }
        val storedAudio = findPendingAudioForFinalization(
            context = context,
            song = song,
            operationId = operationId,
            preferredAudioName = artifact?.audioName,
            preferredAudioReference = artifact?.audioReference
        )
        if (storedAudio == null) {
            if (artifact != null && requeueConfirmedMissingCoreAudio(
                    context = context,
                    song = song,
                    operationId = operationId,
                    audioReference = artifact.audioReference,
                    audioName = artifact.audioName,
                    expectedLeaseId = expectedArtifactLeaseId
                )
            ) {
                return@withSongExecutionLock
            }
            NPLogger.w(
                TAG,
                "core operation 暂未找到可确认音频，保留恢复凭据: " +
                    "song=${song.name}, operationId=$operationId"
            )
            scheduleStartupArtifactRecovery(context)
        } else {
            val finalizationAttemptId = expectedAttemptId ?: request?.attemptId
            try {
                finalizeCompletedDownload(
                    context = context,
                    song = song,
                    expectedAttemptId = finalizationAttemptId,
                    operationId = operationId,
                    expectedArtifactLeaseId = expectedArtifactLeaseId,
                    storedAudioHint = storedAudio,
                    allowMissingTask = true,
                    admissionTicket = admissionTicket,
                    expeditedAssetEnrichment = expeditedAssetEnrichment
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: DownloadStorageMutationDeferredException) {
                NPLogger.d(
                    TAG,
                    "core operation 收尾遇到目录迁移，保留恢复凭据: " +
                        "song=${song.name}, operationId=$operationId"
                )
            } catch (error: Exception) {
                NPLogger.w(
                    TAG,
                    "core operation 收尾失败，保留恢复凭据: " +
                        "song=${song.name}, operationId=$operationId, " +
                        "error=${error.message}",
                    error
                )
            }
            val currentTask = taskStore.findTask(song.stableKey())
            val matchingCompletedTask = currentTask?.takeIf { task ->
                task.status == DownloadStatus.COMPLETED &&
                    (finalizationAttemptId == null ||
                        task.attemptId == finalizationAttemptId)
            }
            val operationState = runCatching {
                DownloadExecutionRoomStore.state(context, operationId)
            }.getOrNull()
            val artifactState = runCatching {
                managedDownloadArtifactCoordinator.currentStateAnyRoot(
                    context = context,
                    song = song,
                    expectedLeaseId = expectedArtifactLeaseId
                )
            }.getOrNull()
            val durablePostCore = isDownloadFinalizationDurablySettled(
                operationState = operationState,
                artifactState = artifactState?.name
            )
            val settledTaskAttemptId = settleAndRemoveRecoveredTask(
                songKey = song.stableKey(),
                expectedAttemptId = finalizationAttemptId,
                promoteStatus = durablePostCore
            )
            finalized = settledTaskAttemptId != null || durablePostCore
            if (!finalized) {
                NPLogger.w(
                    TAG,
                    "core operation 收尾未确认持久完成，保留恢复凭据: " +
                        "song=${song.name}, operationId=$operationId, " +
                        "operationState=$operationState, artifactState=$artifactState, " +
                        "taskStatus=${currentTask?.status}, " +
                        "taskAttemptId=${currentTask?.attemptId}, " +
                        "expectedAttemptId=$finalizationAttemptId"
                )
            } else {
                (
                    settledTaskAttemptId ?:
                        matchingCompletedTask?.attemptId ?:
                        finalizationAttemptId
                    )?.let { attemptId ->
                    markBatchDownloadPresentationTerminal(
                        songKey = song.stableKey(),
                        attemptId = attemptId,
                        terminalState = BatchDownloadTerminalState.COMPLETED,
                        operationId = operationId
                    )
                }
            }
        }
    }
    return finalized
}

internal fun GlobalDownloadManager.settleAndRemoveRecoveredTask(
    songKey: String,
    expectedAttemptId: Long?,
    promoteStatus: Boolean
): Long? {
    var task = taskStore.findTask(songKey) ?: return null
    if (expectedAttemptId != null && task.attemptId != expectedAttemptId) {
        return null
    }
    if (task.status != DownloadStatus.COMPLETED) {
        if (!promoteStatus || task.status == DownloadStatus.CANCELLED) {
            return null
        }
        updateTaskStatus(
            songKey = songKey,
            status = DownloadStatus.COMPLETED,
            expectedAttemptId = task.attemptId,
            settleBatchPresentation = false
        )
        task = taskStore.findTask(songKey) ?: return null
    }
    if (
        task.status != DownloadStatus.COMPLETED ||
        expectedAttemptId != null && task.attemptId != expectedAttemptId
    ) {
        return null
    }
    removeDownloadTask(
        songKey = songKey,
        expectedAttemptId = task.attemptId
    )
    return task.attemptId
}

internal suspend fun GlobalDownloadManager.executionResultForOperation(
    context: Context,
    operationId: String,
    songKey: String,
    expectedAttemptId: Long?
): DownloadExecutionResult {
    if (DownloadExecutionRoomStore.isStopped(context, operationId)) {
        return DownloadExecutionResult.UserStopped
    }
    when (DownloadExecutionRoomStore.state(context, operationId)) {
        "COMPLETED",
        "FINALIZED" -> return DownloadExecutionResult.Accepted
        "CANCEL_REQUESTED",
        "CANCELLED" -> return DownloadExecutionResult.Cancelled
        "STOPPED" -> return DownloadExecutionResult.UserStopped
        "INVALID" -> return DownloadExecutionResult.MissingOperation
        METADATA_ACTION_REQUIRED_OPERATION_STATE -> {
            return DownloadExecutionResult.UserActionRequired
        }
        "RETRYABLE" -> {
            val lastErrorCode = runCatching {
                DownloadExecutionRoomStore.readOperationHeaders(
                    context = context,
                    operationIds = listOf(operationId)
                )[operationId]?.lastErrorCode
            }.getOrNull()
            return if (lastErrorCode == ARTIFACT_LEASE_CONTENDED_ERROR_CODE) {
                DownloadExecutionResult.AlreadyHandled
            } else {
                DownloadExecutionResult.Retry
            }
        }
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "CORE_COMMITTED",
        "ASSETS_ENRICHING" -> return DownloadExecutionResult.AlreadyHandled
        "DEGRADED_COMPLETE" -> {
            if (isMetadataEmbeddingActionRequired(context, operationId, songKey)) {
                return DownloadExecutionResult.UserActionRequired
            }
            return DownloadExecutionResult.Retry
        }
        null -> return DownloadExecutionResult.MissingOperation
    }
    val task = taskStore.findTask(songKey)
    if (
        expectedAttemptId != null &&
        task != null &&
        task.attemptId != expectedAttemptId
    ) {
        return DownloadExecutionResult.Retry
    }
    return when (task?.status) {
        DownloadStatus.CANCELLED -> DownloadExecutionResult.Cancelled
        DownloadStatus.FAILED,
        DownloadStatus.WAITING_NETWORK -> DownloadExecutionResult.Retry
        DownloadStatus.COMPLETED -> DownloadExecutionResult.Accepted
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING -> DownloadExecutionResult.Retry
        null -> DownloadExecutionResult.Retry
    }
}

internal suspend fun GlobalDownloadManager.deferDownloadOperationExecutionForNetworkPolicyIfNeeded(
    context: Context,
    request: DownloadExecutionRequest,
    preparedAttemptId: Long?
): Boolean {
    val networkType = context.currentDownloadNetworkTypeOrNull()
    val networkGeneration = AudioDownloadManager.currentDownloadNetworkGeneration()
    if (!shouldDeferDownloadExecutionForNetwork(
            requiresWifiNetwork = request.requiresWifiNetwork,
            networkType = networkType,
            mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
        )
    ) {
        return false
    }
    val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()

    val songKey = request.song.stableKey()
    val durableAttemptIds = (preparedAttemptId ?: request.attemptId)
        ?.takeIf { attemptId -> attemptId > 0L }
        ?.let { attemptId -> mapOf(songKey to attemptId) }
        ?: emptyMap()
    if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
        NPLogger.d(
            TAG,
            "执行宿主网络策略已过期，保留 WIFI 恢复路径: operationId=${request.operationId}"
        )
        return false
    }
    var effectiveAttemptId: Long? = null
    val waitingStateCommitted = mutateWifiBoundNetworkPolicyIfStillRequired(
        context = context,
        snapshotEpoch = networkPolicyEpoch
    ) {
        effectiveAttemptId = taskStore.ensureDownloadTasks(
            songs = listOf(request.song),
            status = DownloadStatus.WAITING_NETWORK,
            durableAttemptIds = durableAttemptIds
        )[songKey]
        effectiveAttemptId?.let { attemptId ->
            taskStore.updateTaskStatus(
                songKey = songKey,
                status = DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = attemptId
            )
        }
        mobileDataDownloadOverrideAllowed = false
    }
    if (!waitingStateCommitted) {
        NPLogger.d(
            TAG,
            "执行宿主网络策略提交已过期，保留 WIFI 恢复路径: operationId=${request.operationId}"
        )
        return false
    }
    val requestBatchIdentity = request.batchId?.let { batchId ->
        request.batchGeneration?.let { generation ->
            MobileDataDownloadBatchIdentity(batchId, generation)
        }
    }
    requestBatchIdentity?.let { identity ->
        runCatching {
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context = context.applicationContext,
                identities = listOf(identity.toRoomBatchIdentity()),
                networkGeneration = networkGeneration,
                expectedNetworkGeneration = null
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "持久化执行批次网络等待状态失败: operationId=${request.operationId}, " +
                    "error=${error.message}",
                error
            )
        }
    }
    if (effectiveAttemptId != null) {
        runCatching {
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request.copy(attemptId = effectiveAttemptId),
                state = "RETRYABLE"
            )
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = request.operationId,
                state = "RETRYABLE",
                errorCode = "NETWORK_POLICY_WAITING"
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "写入 WIFI 下载等待状态失败: operationId=${request.operationId}, " +
                    "error=${error.message}"
            )
        }
    }
    if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
        recoverWifiBoundDownloadsIfNetworkPolicyExpired(
            context = context,
            snapshotEpoch = networkPolicyEpoch,
            reason = "execution_network_policy_stale"
        )
        return true
    }
    NPLogger.w(
        TAG,
        "下载执行宿主在非 WIFI 网络被阻止: operationId=${request.operationId}, " +
            "song=${request.song.name}, networkType=$networkType"
    )
    networkType?.let { confirmedNetworkType ->
        publishMobileDataDownloadInterruptionRequestIfNeeded(
            context = context,
            networkType = confirmedNetworkType,
            fallbackTaskCount = 1,
            reason = "execution_network_policy",
            batchIdentities = listOfNotNull(requestBatchIdentity),
            networkGeneration = networkGeneration
        )
    }
    recoverWifiBoundDownloadsIfNetworkPolicyExpired(
        context = context,
        snapshotEpoch = networkPolicyEpoch,
        reason = "execution_network_policy_publish_stale"
    )
    return true
}

internal suspend fun GlobalDownloadManager.admitDownloadTransferStart(
    context: Context,
    song: SongItem,
    attemptId: Long,
    requestGeneration: Long,
    operationId: String?,
    admissionTicket: Long?
): Boolean {
    val appContext = context.applicationContext
    val songKey = song.stableKey()
    val effectiveAdmissionTicket = admissionTicket
        ?: downloadAdmissionGate.openTicketOrNull()
        ?: return false
    var transferRegistered = false

    suspend fun registerIfCurrent() {
        if (
            isDownloadClearFenceActive(appContext, stableKey = songKey) ||
                downloadAdmissionGate.openTicketOrNull() != effectiveAdmissionTicket ||
                !isDownloadRequestGenerationCurrent(songKey, requestGeneration) ||
                isSongCancelled(songKey) ||
                !taskStore.isDownloadAttemptCurrent(songKey, attemptId) ||
                (operationId != null &&
                    !DownloadExecutionRoomStore.isExecutionOwned(
                        context = appContext,
                        operationId = operationId,
                        stableKey = songKey
                    ))
        ) {
            return
        }

        taskStore.beginDownloadTransfer()
        var registrationSucceeded = false
        try {
            taskStore.registerActiveDownloadTask(
                song = song,
                expectedAttemptId = attemptId
            )
            val registeredTask = taskStore.findTask(songKey)
            if (
                registeredTask == null ||
                    registeredTask.attemptId != attemptId ||
                    registeredTask.status != DownloadStatus.DOWNLOADING
            ) {
                return
            }
            // 注册与取消标志重置放在同一把闸门内，清空开始后不会被旧协程重新打开
            AudioDownloadManager.resetCancelFlag()
            registrationSucceeded = true
            transferRegistered = true
        } finally {
            if (!registrationSucceeded) {
                taskStore.endDownloadTransfer()
            }
        }
    }

    val admitted = downloadAdmissionGate.admit(effectiveAdmissionTicket) {
        registerIfCurrent()
    }
    return admitted && transferRegistered
}

internal suspend fun GlobalDownloadManager.reclaimOrphanedTransferLeaseIfSafe(
    context: Context,
    song: SongItem,
    operationId: String?,
    leaseOwnerId: String?,
    artifactClaim: ManagedDownloadArtifactClaim?,
    userInitiated: Boolean
): ManagedDownloadArtifactClaim? {
    val inFlight = artifactClaim as? ManagedDownloadArtifactClaim.InFlight
        ?: return artifactClaim
    if (!userInitiated) return artifactClaim
    val artifact = inFlight.artifact
    val leaseId = artifact.leaseId?.trim()?.takeIf(String::isNotBlank)
        ?: return artifactClaim
    val nowMs = System.currentTimeMillis()
    if (
        artifact.updatedAtMs <= 0L ||
            nowMs - artifact.updatedAtMs < ORPHANED_TRANSFER_LEASE_MIN_AGE_MS
    ) {
        return artifactClaim
    }
    val stableKey = song.stableKey()
    val operationIds = try {
        DownloadExecutionRoomStore.findOperationIdsForSong(
            context = context.applicationContext,
            songKey = stableKey
        )
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "读取 artifact lease owner 失败，保留传输 lease: " +
                "song=${song.name}, operationId=$operationId, error=${error.message}",
            error
        )
        return artifactClaim
    }
    val snapshots = try {
        DownloadExecutionRoomStore.readOperationSnapshots(
            context = context.applicationContext,
            operationIds = operationIds
        )
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "读取 artifact lease owner 快照失败，保留传输 lease: " +
                "song=${song.name}, operationId=$operationId, error=${error.message}",
            error
        )
        return artifactClaim
    }
    // RETRYABLE/DEGRADED_COMPLETE/CANCEL_REQUESTED/STOPPED 都不等于当前
    // 有网络 owner；只要没有 executing owner，新用户代次就可以接管残留 lease。
    val liveOwnerStates = setOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING"
    )
    val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
    val explicitlyCancelledOperationIds = cancellationOperationIdsForSong(stableKey)
    // source_hint_json 可能因历史大载荷而无法解码。表头仍足以判断它是否
    // 真正处于活动态；缺失表头则表示 operation 已被清空，可以安全回收 lease。
    val headers = try {
        DownloadExecutionRoomStore.readOperationHeaders(
            context = context.applicationContext,
            operationIds = operationIds
        )
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "读取 artifact lease owner 表头失败，保留传输 lease: " +
                "song=${song.name}, operationId=$operationId, error=${error.message}",
            error
        )
        return artifactClaim
    }
    val hasLiveOwner = operationIds.any { candidateId ->
        // 当前 operation 正在执行但尚未拿到这条 artifact lease，不能把自己
        // 当成 owner，否则每次重试都会自锁在 InFlight，尾项永远无法补位
        if (candidateId == normalizedOperationId) return@any false
        val snapshot = snapshots[candidateId]
        val headerState = headers[candidateId]?.state
        val explicitlyCancelled = candidateId in explicitlyCancelledOperationIds
        val ownsLease = snapshot != null &&
            snapshot.request.song.stableKey() == stableKey &&
            snapshot.request.artifactLeaseId == leaseId &&
            snapshot.state in liveOwnerStates
        val executing = DownloadExecutionHosts.default.isExecuting(candidateId)
        val enriching = assetEnrichmentCoordinator.isActive(candidateId)
        isLiveArtifactLeaseOwner(
            candidateOwnsLease = ownsLease,
            payloadReadable = snapshot != null,
            headerInLiveState = headerState != null && headerState in liveOwnerStates,
            executionActive = executing,
            enrichmentActive = enriching,
            explicitlyCancelled = explicitlyCancelled
        )
    }
    if (hasLiveOwner) return artifactClaim

    val released = try {
        managedDownloadArtifactCoordinator.settleLeaseAnyRoot(
            context = context.applicationContext,
            song = song,
            expectedLeaseId = leaseId,
            requestedState = ManagedDownloadArtifactState.FAILED_RETRYABLE,
            errorCode = "ORPHANED_TRANSFER_LEASE"
        )
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "回收孤儿 artifact lease 失败，保留传输 lease: " +
                "song=${song.name}, operationId=$operationId, error=${error.message}",
            error
        )
        false
    }
    if (!released) return artifactClaim
    NPLogger.d(
        TAG,
        "已回收无 owner 的 artifact lease，允许新 operation 接管: " +
            "song=${song.name}, operationId=$operationId, leaseId=$leaseId"
    )
    return try {
        managedDownloadArtifactCoordinator.claim(
            context = context.applicationContext,
            song = song,
            reconcileStorage = false,
            leaseOwnerId = leaseOwnerId,
            allowFreshTransferReclaim = true
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "回收孤儿 lease 后重新 claim 失败，保留恢复路径: " +
                "song=${song.name}, operationId=$operationId, error=${error.message}",
            error
        )
        artifactClaim
    }
}

internal fun isLiveArtifactLeaseOwner(
    candidateOwnsLease: Boolean,
    payloadReadable: Boolean,
    headerInLiveState: Boolean,
    executionActive: Boolean,
    enrichmentActive: Boolean,
    explicitlyCancelled: Boolean
): Boolean {
    if (explicitlyCancelled || (!executionActive && !enrichmentActive)) return false
    return if (payloadReadable) candidateOwnsLease else headerInLiveState
}
