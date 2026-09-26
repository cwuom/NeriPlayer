package moe.ouom.neriplayer.core.download.manager.batch

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.buildDownloadedSongCatalogIndex
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadArtifactFinalized
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadArtifactRetryable
import moe.ouom.neriplayer.core.download.manager.catalog.releaseDownloadArtifactAfterExecutionOwnershipLoss
import moe.ouom.neriplayer.core.download.manager.catalog.releaseDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.manager.recovery.resolveCoreRecoveryAudioCandidate
import moe.ouom.neriplayer.core.download.manager.runtime.loadFinalizationRecoverySnapshot
import moe.ouom.neriplayer.core.download.manager.runtime.publishDownloadStage
import moe.ouom.neriplayer.core.download.manager.runtime.reclaimOrphanedTransferLeaseIfSafe
import moe.ouom.neriplayer.core.download.manager.runtime.repairDownloadedCoverIfMissing
import moe.ouom.neriplayer.core.download.manager.runtime.resolveStoredAudio
import moe.ouom.neriplayer.core.download.manager.runtime.isRecoveryMetadataOwnedBySong
import moe.ouom.neriplayer.core.download.manager.runtime.settleAlreadyDownloadedOperation
import moe.ouom.neriplayer.core.download.model.BatchDownloadTerminalState
import moe.ouom.neriplayer.core.download.model.BatchOperationScheduleAction
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.QueuedDownloadRequest
import moe.ouom.neriplayer.core.download.model.canScheduleRecoveredDownloadOperation
import moe.ouom.neriplayer.core.download.model.resolveBatchOperationScheduleAction
import moe.ouom.neriplayer.core.download.model.resolveDownloadPreserveStaging
import moe.ouom.neriplayer.core.download.model.selectBatchArtifactLeaseForCancellation
import moe.ouom.neriplayer.core.download.model.shouldPreserveBatchPreparationForHandedOffOperation
import moe.ouom.neriplayer.core.download.model.shouldRehandoffRecoveredDownloadOperation
import moe.ouom.neriplayer.core.download.policy.requiresDownloadFinalizationRecovery
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.TrafficRiskDownloadRequest
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.BatchDownloadSession
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PreparedBatchArtifact
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogIndex
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionSchedule
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.execution.worker.PostCoreDownloadRecoveryWorker
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull


internal suspend fun GlobalDownloadManager.claimAndPrepareBatchArtifact(
    session: BatchDownloadSession,
    song: SongItem
): PreparedBatchArtifact {
    val songKey = song.stableKey()
    val operationId = session.operationIdsBySongKey[songKey]
        ?: throw IllegalStateException("batch item has no durable operation")
    var operationRequest = session.operationRequestsBySongKey[songKey]
        ?.takeIf { request -> request.song.stableKey() == songKey }
        ?: DownloadExecutionRoomStore.read(
            context = session.context,
            operationId = operationId
        )?.takeIf { request -> request.song.stableKey() == songKey }
        ?: throw IllegalStateException("batch item has no readable durable operation")
    val artifactLeaseId = session.artifactLeaseIdsBySongKey[songKey]
        ?: operationRequest.artifactLeaseId
    val attemptId = session.preparedAttemptIds[songKey]
    val preClaimRequestGenerationCurrent = isDownloadRequestGenerationCurrent(
        songKey,
        session.requestGeneration
    )
    val preClaimOperationState = DownloadExecutionRoomStore.state(
        context = session.context,
        operationId = operationId
    )
    if (
        !requiresDownloadFinalizationRecovery(preClaimOperationState) &&
            shouldPreserveBatchPreparationForHandedOffOperation(
                operationState = preClaimOperationState,
                requestMatchesSong = true,
                attemptId = attemptId,
                requestGenerationCurrent = preClaimRequestGenerationCurrent,
                isExecuting = DownloadExecutionHosts.default.isExecuting(operationId)
            )
    ) {
        session.handedOffSongKeys += songKey
        session.scheduledSongKeys += songKey
        NPLogger.d(
            TAG,
            "批量下载准备发现 operation 已由 OS 宿主接管，保留现有 attempt: " +
                "song=${song.name}, operationId=$operationId, state=$preClaimOperationState"
        )
        return PreparedBatchArtifact(
            operationId = operationId,
            artifactClaim = null,
            requiresFinalizationRecovery = false,
            acquiredLeaseId = null,
            attemptId = null
        )
    }
    var artifactClaim = session.artifactClaims[songKey]
        ?.takeUnless { claim -> claim is ManagedDownloadArtifactClaim.InFlight }
    if (artifactClaim == null) {
        artifactClaim = try {
            managedDownloadArtifactCoordinator.claim(
                context = session.context,
                song = song,
                reconcileStorage = false,
                leaseOwnerId = operationRequest.artifactLeaseId,
                allowFreshTransferReclaim = operationRequest.userInitiated
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "批量下载 artifact claim 失败，继续现有恢复路径: ${error.message}"
            )
            null
        }
    }
    // 批量准备与用户重试可能并发。若第一次 claim 使用了旧的
    // userInitiated=false 快照，立即重读并刷新一次，避免 RUNNING/post-core
    // operation 被误判为仅收尾任务。
    val latestPromotedRequest = DownloadExecutionRoomStore.read(
        context = session.context,
        operationId = operationId
    )?.takeIf { request -> request.song.stableKey() == songKey }
    if (
        latestPromotedRequest?.userInitiated == true &&
            operationRequest.userInitiated != true &&
            artifactClaim !is ManagedDownloadArtifactClaim.Acquired
    ) {
        val refreshedClaim = try {
            managedDownloadArtifactCoordinator.claim(
                context = session.context,
                song = song,
                reconcileStorage = false,
                leaseOwnerId = latestPromotedRequest.artifactLeaseId,
                allowFreshTransferReclaim = true
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "批量用户重试意图已持久化，但 artifact claim 重试失败: " +
                    "song=${song.name}, operationId=$operationId, " +
                    "error=${error.message}",
                error
            )
            null
        }
        if (refreshedClaim != null) {
            artifactClaim = refreshedClaim
            operationRequest = latestPromotedRequest
            NPLogger.d(
                TAG,
                "批量准备检测到并发提升的用户重试意图，已刷新 artifact claim: " +
                    "song=${song.name}, operationId=$operationId, " +
                    "claim=${refreshedClaim.javaClass.simpleName}"
            )
        }
    } else if (latestPromotedRequest?.userInitiated == true) {
        operationRequest = latestPromotedRequest
    }
    artifactClaim = reclaimOrphanedTransferLeaseIfSafe(
        context = session.context,
        song = song,
        operationId = operationId,
        leaseOwnerId = operationRequest.artifactLeaseId,
        artifactClaim = artifactClaim,
        userInitiated = operationRequest.userInitiated
    ) ?: artifactClaim
    if (artifactClaim is ManagedDownloadArtifactClaim.InFlight) {
        // InFlight 只代表这一刻的 owner，不能缓存到整个批次准备周期
        session.artifactClaims.remove(songKey)
    } else {
        session.artifactClaims[songKey] = artifactClaim
    }
    val claimedArtifact = when (artifactClaim) {
        is ManagedDownloadArtifactClaim.AlreadyDownloaded -> artifactClaim.artifact
        is ManagedDownloadArtifactClaim.RepairRequired -> artifactClaim.artifact
        else -> null
    }
    val requiresFinalizationRecovery = claimedArtifact
        ?.state
        ?.let(::requiresDownloadFinalizationRecovery) == true
    // 只有确认存在可收尾的 post-core 凭据时，才允许没有新 attempt 的恢复分支继续
    val canFinalizePreparedArtifact = requiresFinalizationRecovery
    if (artifactClaim is ManagedDownloadArtifactClaim.Acquired) {
        artifactClaim.artifact.leaseId?.let { leaseId ->
            managedDownloadArtifactLeases[songKey] = leaseId
        }
    }
    val acquiredLeaseId = (artifactClaim as? ManagedDownloadArtifactClaim.Acquired)
        ?.artifact?.leaseId ?: claimedArtifact
        ?.takeIf { artifact ->
            requiresFinalizationRecovery && artifact.leaseId == artifactLeaseId
        }
        ?.leaseId
    val requiresTask = artifactClaim !is ManagedDownloadArtifactClaim.AlreadyDownloaded ||
        requiresFinalizationRecovery
    val requestGenerationCurrent = isDownloadRequestGenerationCurrent(
        songKey,
        session.requestGeneration
    )
    val operationState = DownloadExecutionRoomStore.state(
        context = session.context,
        operationId = operationId
    )
    val operationScheduleAction = resolveBatchOperationScheduleAction(
        operationState = operationState,
        requestMatchesSong = true,
        isExecuting = DownloadExecutionHosts.default.isExecuting(operationId)
    )
    if (
        requiresTask &&
            shouldPreserveBatchPreparationForHandedOffOperation(
                operationState = operationState,
                requestMatchesSong = true,
                attemptId = attemptId,
                requestGenerationCurrent = requestGenerationCurrent,
                isExecuting = DownloadExecutionHosts.default.isExecuting(operationId)
            ) &&
        !canFinalizePreparedArtifact
    ) {
        // operation 在 artifact claim 期间进入 RUNNING/COMMITTING 等状态时，
        // 批量准备不再释放同一 execution owner 的 lease，也不能移除它的 task
        session.handedOffSongKeys += songKey
        session.scheduledSongKeys += songKey
        session.artifactClaims.remove(songKey)
        NPLogger.d(
            TAG,
            "批量下载准备期间 operation 已由 OS 宿主接管，保留现有 attempt: " +
                "song=${song.name}, operationId=$operationId, state=$operationState"
        )
        return PreparedBatchArtifact(
            operationId = operationId,
            artifactClaim = null,
            requiresFinalizationRecovery = false,
            acquiredLeaseId = null,
            attemptId = null
        )
    }
    if (
        requiresTask &&
            (
                attemptId == null && !canFinalizePreparedArtifact ||
                    !requestGenerationCurrent ||
                operationScheduleAction != BatchOperationScheduleAction.SCHEDULE &&
                        !canFinalizePreparedArtifact
            )
    ) {
        acquiredLeaseId?.let { leaseId ->
            releaseDownloadArtifactAfterExecutionOwnershipLoss(
                context = session.context,
                song = song,
                operationId = operationId,
                expectedLeaseId = leaseId
            )
        }
        session.artifactClaims.remove(songKey)
        attemptId?.let { preparedAttemptId ->
            session.settledAttemptIds[songKey] = preparedAttemptId
        }
        NPLogger.d(
            TAG,
            "跳过已过期批量任务: song=${song.name}, " +
                "generation=${session.requestGeneration}, state=$operationState, " +
                "action=$operationScheduleAction"
        )
        return PreparedBatchArtifact(
            operationId = operationId,
            artifactClaim = null,
            requiresFinalizationRecovery = false,
            acquiredLeaseId = null,
            attemptId = null
        )
    }
    return PreparedBatchArtifact(
        operationId = operationId,
        artifactClaim = artifactClaim,
        requiresFinalizationRecovery = requiresFinalizationRecovery,
        acquiredLeaseId = acquiredLeaseId,
        attemptId = attemptId
    )
}

internal suspend fun GlobalDownloadManager.settleFastCachedBatchDownload(
    session: BatchDownloadSession,
    song: SongItem,
    attemptId: Long,
    acquiredLeaseId: String?,
    downloadedSong: DownloadedSong
): Boolean {
    val songKey = song.stableKey()
    val operationId = session.operationIdsBySongKey[songKey] ?: run {
        NPLogger.w(TAG, "批量缓存命中但缺少 operation 身份: song=${song.name}")
        return false
    }
    // 快索引的引用已经通过 Present 校验，但冷启动时 SongItem 可能没有本地路径。
    // 优先使用 catalog 的 filePath/mediaUri，避免尾项永远落回 Retry。
    val storedAudio = resolveStoredAudio(session.context, song)
        ?: resolveStoredAudio(session.context, downloadedSong.filePath)
        ?: resolveStoredAudio(session.context, downloadedSong.mediaUri)
        ?: run {
            session.enqueue(
                song = song,
                attemptId = attemptId,
                operationId = operationId
            )
            NPLogger.w(
                TAG,
                "批量缓存命中但无法定位正式音频，保留 operation 等待重试: " +
                    "song=${song.name}, operationId=$operationId"
            )
            return false
        }
    if (!markDownloadArtifactFinalized(
            context = session.context,
            song = song,
            storedAudio = storedAudio,
            leaseId = acquiredLeaseId
        )
    ) {
        session.enqueue(
            song = song,
            attemptId = attemptId,
            operationId = operationId
        )
        NPLogger.w(
            TAG,
            "批量缓存命中但 artifact 未完成收尾，保留 operation 等待重试: " +
                "song=${song.name}, operationId=$operationId"
        )
        return false
    }
    val durableSettled = settleAlreadyDownloadedOperation(
        context = session.context,
        song = song,
        operationId = operationId,
        expectedAttemptId = attemptId,
        reason = "BATCH_CACHE_HIT"
    )
    if (!durableSettled) {
        session.enqueue(
            song = song,
            attemptId = attemptId,
            operationId = operationId
        )
        return false
    }
    acquiredLeaseId?.let { leaseId ->
        managedDownloadArtifactLeases.remove(songKey, leaseId)
    }
    val repairedSong = repairDownloadedCoverIfMissing(
        context = session.context,
        song = song,
        downloadedSong = downloadedSong
    )
    session.settledSongKeys += songKey
    session.settledAttemptIds[songKey] = attemptId
    markBatchDownloadPresentationTerminal(
        songKey = songKey,
        attemptId = attemptId,
        terminalState = BatchDownloadTerminalState.COMPLETED,
        operationId = session.operationIdsBySongKey[songKey]
    )
    if (repairedSong != downloadedSong) {
        session.optimisticDownloadedSongs += repairedSong
    }
    NPLogger.d(
        TAG,
        "批量下载命中下载目录缓存并直接完成: song=${song.name}, songKey=$songKey"
    )
    return true
}

internal suspend fun GlobalDownloadManager.settleExistingBatchDownload(
    session: BatchDownloadSession,
    song: SongItem,
    attemptId: Long,
    acquiredLeaseId: String?,
    storedAudio: ManagedDownloadStorage.StoredEntry
): Boolean {
    val songKey = song.stableKey()
    val operationId = session.operationIdsBySongKey[songKey] ?: run {
        NPLogger.w(TAG, "批量已有音频命中但缺少 operation 身份: song=${song.name}")
        return false
    }
    if (!markDownloadArtifactFinalized(
            context = session.context,
            song = song,
            storedAudio = storedAudio,
            leaseId = acquiredLeaseId
        )
    ) {
        session.enqueue(
            song = song,
            attemptId = attemptId,
            operationId = operationId
        )
        NPLogger.w(
            TAG,
            "批量已有音频命中但 artifact 未完成收尾，保留 operation 等待重试: " +
                "song=${song.name}, operationId=$operationId"
        )
        return false
    }
    val durableSettled = settleAlreadyDownloadedOperation(
        context = session.context,
        song = song,
        operationId = operationId,
        expectedAttemptId = attemptId,
        reason = "BATCH_EXISTING_AUDIO"
    )
    if (!durableSettled) {
        session.enqueue(
            song = song,
            attemptId = attemptId,
            operationId = operationId
        )
        return false
    }
    acquiredLeaseId?.let { leaseId ->
        managedDownloadArtifactLeases.remove(songKey, leaseId)
    }
    session.settledSongKeys += songKey
    session.settledAttemptIds[songKey] = attemptId
    markBatchDownloadPresentationTerminal(
        songKey = songKey,
        attemptId = attemptId,
        terminalState = BatchDownloadTerminalState.COMPLETED,
        operationId = session.operationIdsBySongKey[songKey]
    )
    session.optimisticDownloadedSongs += repairDownloadedCoverIfMissing(
        context = session.context,
        song = song,
        downloadedSong = buildOptimisticDownloadedSong(
            song = song,
            storedAudio = storedAudio
        )
    )
    NPLogger.d(
        TAG,
        "批量下载命中已存在音频并直接完成: song=${song.name}, " +
            "songKey=$songKey, file=${storedAudio.name}"
    )
    return true
}

internal suspend fun GlobalDownloadManager.handleBatchDownloadPreparationFailure(
    session: BatchDownloadSession,
    song: SongItem
) {
    val songKey = song.stableKey()
    val attemptId = session.preparedAttemptIds[songKey]
    val operationId = session.operationIdsBySongKey[songKey]
    if (attemptId != null && operationId != null) {
        handleBatchDownloadScheduleFailure(
            context = session.context,
            request = QueuedDownloadRequest(
                song = song,
                attemptId = attemptId,
                operationId = operationId
            ),
            errorCode = "BATCH_ITEM_PREPARE_FAILED",
            expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
        )
        return
    }
    batchArtifactLeaseId(session, songKey)?.let { leaseId ->
        releaseDownloadArtifactClaim(session.context, song, leaseId)
    }
}

internal suspend fun GlobalDownloadManager.cancelPreparedBatchDownloadSession(session: BatchDownloadSession) {
    val admitted = withContext(NonCancellable) {
        admitDownloadMutation(
            context = session.context,
            admissionTicket = session.admissionTicket
        ) {
            cancelPreparedBatchDownloadSessionAdmitted(session)
        }
    }
    if (!admitted) {
        NPLogger.d(TAG, "清空期间跳过过期批量取消收尾")
    }
}

internal suspend fun GlobalDownloadManager.cancelPreparedBatchDownloadSessionAdmitted(
    session: BatchDownloadSession
) {
    val cancelledSongKeys = mutableSetOf<String>()
    session.requestedSongs.forEach { song ->
        val songKey = song.stableKey()
        if (!isDownloadRequestGenerationCurrent(songKey, session.requestGeneration)) {
            return@forEach
        }
        selectBatchArtifactLeaseForCancellation(
            handedOff = songKey in session.handedOffSongKeys,
            capturedLeaseId = batchArtifactLeaseId(session, songKey)
        )?.let { leaseId ->
            releaseDownloadArtifactClaim(session.context, song, leaseId)
        }
        if (songKey in session.handedOffSongKeys) {
            return@forEach
        }
        if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
            session.preparedAttemptIds[songKey]?.let { attemptId ->
                updateTaskStatus(
                    songKey,
                    DownloadStatus.WAITING_NETWORK,
                    expectedAttemptId = attemptId
                )
            }
            return@forEach
        }
        clearSongCancelled(songKey)
        session.preparedAttemptIds[songKey]?.let { attemptId ->
            markBatchDownloadPresentationTerminal(
                songKey = songKey,
                attemptId = attemptId,
                terminalState = BatchDownloadTerminalState.CANCELLED,
                operationId = session.operationIdsBySongKey[songKey]
            )
            removeDownloadTask(songKey, expectedAttemptId = attemptId)
        }
        cancelledSongKeys += songKey
    }
    forgetPendingDownloadQueueEntriesIfCurrent(
        context = session.context,
        songKeys = cancelledSongKeys,
        generation = session.requestGeneration
    )
}

internal suspend fun GlobalDownloadManager.failPreparedBatchDownloadSession(
    session: BatchDownloadSession,
    error: Exception
) {
    val admitted = withContext(NonCancellable) {
        admitDownloadMutation(
            context = session.context,
            admissionTicket = session.admissionTicket
        ) {
            NPLogger.e(TAG, "批量下载失败: ${error.message}", error)
            session.pendingSongs.forEach { request ->
                val songKey = request.song.stableKey()
                if (
                    songKey in session.handedOffSongKeys ||
                        !isDownloadRequestGenerationCurrent(
                            songKey,
                            session.requestGeneration
                        )
                ) {
                    return@forEach
                }
                handleBatchDownloadScheduleFailure(
                    context = session.context,
                    request = request,
                    errorCode = "BATCH_DOWNLOAD_FAILED",
                    expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
                )
            }
        }
    }
    if (!admitted) {
        NPLogger.d(TAG, "清空期间跳过过期批量失败收尾")
    }
}

internal fun GlobalDownloadManager.batchArtifactLeaseId(
    session: BatchDownloadSession,
    songKey: String
): String? {
    return (session.artifactClaims[songKey] as? ManagedDownloadArtifactClaim.Acquired)
        ?.artifact?.leaseId
}

internal suspend fun GlobalDownloadManager.schedulePendingBatchDownloads(
    session: BatchDownloadSession,
    pendingAttemptIds: Map<String, Long>,
    requests: List<QueuedDownloadRequest> = session.pendingSongs
) {
    for (request in requests) {
        val admitted = schedulePendingBatchDownload(
            session = session,
            request = request,
            pendingAttemptIds = pendingAttemptIds
        )
        if (!admitted) {
            break
        }
        if (session.shouldYieldToSharedPump) {
            break
        }
    }
}

internal suspend fun GlobalDownloadManager.schedulePendingBatchDownload(
    session: BatchDownloadSession,
    request: QueuedDownloadRequest,
    pendingAttemptIds: Map<String, Long>
): Boolean {
    val song = request.song
    val songKey = song.stableKey()
    if (!session.scheduledSongKeys.add(songKey)) {
        return true
    }
    try {
        val admitted = admitDownloadMutation(
            context = session.context,
            admissionTicket = session.admissionTicket,
            stableKey = songKey,
            operationId = request.operationId
        ) scheduleAdmission@{
            val operationId = request.operationId
            val scheduleMetadata = session.scheduleMetadataBySongKey[songKey]
            val operationState = DownloadExecutionRoomStore.state(
                context = session.context,
                operationId = operationId
            )
            when (resolveBatchOperationScheduleAction(
                operationState = operationState,
                requestMatchesSong = scheduleMetadata?.operationId == operationId,
                isExecuting = DownloadExecutionHosts.default.isExecuting(operationId)
            )) {
                BatchOperationScheduleAction.HANDED_OFF -> {
                    session.handedOffSongKeys += songKey
                    NPLogger.d(
                        TAG,
                        "批量下载 operation 已由 OS 宿主接管: " +
                            "song=${song.name}, operationId=$operationId, state=$operationState"
                    )
                    return@scheduleAdmission
                }

                BatchOperationScheduleAction.INVALID -> {
                    handleBatchDownloadScheduleFailure(
                        context = session.context,
                        request = request,
                        errorCode = "OPERATION_NOT_SCHEDULABLE",
                        expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
                    )
                    NPLogger.w(
                        TAG,
                        "批量下载 operation 在调度前失效: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@scheduleAdmission
                }

                BatchOperationScheduleAction.RELEASE -> {
                    batchArtifactLeaseId(session, songKey)?.let { leaseId ->
                        releaseDownloadArtifactClaim(session.context, song, leaseId)
                    }
                    NPLogger.d(
                        TAG,
                        "批量下载 operation 已取消或停止，释放 artifact 租约: " +
                            "song=${song.name}, operationId=$operationId, state=$operationState"
                    )
                    return@scheduleAdmission
                }

                BatchOperationScheduleAction.SETTLED -> {
                    batchArtifactLeaseId(session, songKey)?.let { leaseId ->
                        managedDownloadArtifactLeases.remove(songKey, leaseId)
                    }
                    NPLogger.d(
                        TAG,
                        "批量下载 operation 状态已收敛，跳过重复调度: " +
                            "song=${song.name}, operationId=$operationId, state=$operationState"
                    )
                    return@scheduleAdmission
                }

                BatchOperationScheduleAction.SCHEDULE -> Unit
            }
            val executionRequest = scheduleMetadata?.let { metadata ->
                val persistedRequest = session.operationRequestsBySongKey[songKey]
                val persistedBatchIdentity = persistedRequest?.let { request ->
                    if (request.batchId != null && request.batchGeneration != null) {
                        DownloadExecutionRoomStore.DownloadBatchIdentity(
                            batchId = request.batchId,
                            generation = request.batchGeneration
                        )
                    } else {
                        null
                    }
                }
                val batchIdentity = persistedBatchIdentity ?: session.durableBatchIdentity
                DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    preserveStaging = resolveDownloadPreserveStaging(
                        persistedPreserveStaging = metadata.preserveStaging,
                        preserveRequested = !session.cleanupBeforeStart
                    ),
                    requiresWifiNetwork = metadata.requiresWifiNetwork,
                    attemptId = pendingAttemptIds[songKey] ?: metadata.attemptId,
                    artifactLeaseId = metadata.artifactLeaseId,
                    userInitiated = session.userInitiated || metadata.userInitiated,
                    downloadAudioQuality = metadata.downloadAudioQuality,
                    batchId = batchIdentity?.batchId,
                    batchGeneration = batchIdentity?.generation
                )
            } ?: run {
                handleBatchDownloadScheduleFailure(
                    context = session.context,
                    request = request,
                    errorCode = "OPERATION_PAYLOAD_UNAVAILABLE",
                    expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
                )
                return@scheduleAdmission
            }
            publishDownloadStage(
                song = song,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST,
                operationId = operationId,
                attemptId = executionRequest.attemptId
            )
            val schedule = DownloadExecutionHosts.default.schedule(
                context = session.context,
                request = executionRequest
            )
            if (schedule is DownloadExecutionSchedule.Deferred) {
                session.shouldYieldToSharedPump = true
                val pumpScheduled = ForegroundDownloadWorker.schedulePump(session.context)
                if (!pumpScheduled) {
                    NPLogger.w(
                        TAG,
                        "批量下载宿主槽位已满且共享泵调度失败，保留持久队列: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                }
                session.handedOffSongKeys += songKey
                NPLogger.d(
                    TAG,
                    "批量下载已登记等待全局宿主槽位: " +
                        "song=${song.name}, operationId=$operationId"
                )
            } else if (schedule is DownloadExecutionSchedule.Rejected) {
                val wasHandedOff = handleBatchDownloadScheduleFailure(
                    context = session.context,
                    request = request,
                    errorCode = "OS_HOST_REJECTED",
                    expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
                )
                if (wasHandedOff) {
                    session.handedOffSongKeys += songKey
                    NPLogger.d(
                        TAG,
                        "批量下载调度竞态中 operation 已被接管: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                } else {
                    NPLogger.w(
                        TAG,
                        "批量下载宿主调度失败: " +
                            "song=${song.name}, reason=${schedule.reason}"
                    )
                }
            } else {
                session.handedOffSongKeys += songKey
                NPLogger.d(
                    TAG,
                    "批量下载已交给 OS 宿主: song=${song.name}, operationId=$operationId"
                )
            }
        }
        if (!admitted) {
            batchArtifactLeaseId(session, songKey)?.let { leaseId ->
                releaseDownloadArtifactAfterExecutionOwnershipLoss(
                    context = session.context,
                    song = song,
                    operationId = request.operationId,
                    expectedLeaseId = leaseId
                )
            }
            session.artifactClaims.remove(songKey)
            NPLogger.d(
                TAG,
                "跳过过期批量宿主调度: song=${song.name}, " +
                    "operationId=${request.operationId}"
            )
            return false
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        val latestState = runCatching {
            DownloadExecutionRoomStore.state(session.context, request.operationId)
        }.getOrNull()
        val latestRequest = runCatching {
            DownloadExecutionRoomStore.read(session.context, request.operationId)
        }.getOrNull()
        if (
            latestState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES &&
                latestRequest?.song?.stableKey() == songKey
        ) {
            session.handedOffSongKeys += songKey
            NPLogger.d(
                TAG,
                "批量下载单项异常时 operation 已被接管: " +
                    "song=${song.name}, operationId=${request.operationId}"
            )
            return true
        }
        handleBatchDownloadScheduleFailure(
            context = session.context,
            request = request,
            errorCode = "BATCH_ITEM_SCHEDULE_FAILED",
            expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
        )
        NPLogger.e(
            TAG,
            "批量下载单项调度失败: song=${song.name}, error=${error.message}",
            error
        )
    }
    return true
}

internal fun GlobalDownloadManager.recoverInFlightDownloadOperations(
    context: Context,
    requests: Collection<DownloadExecutionRequest>,
    admissionTicket: Long
) {
    val distinctRequests = requests.distinctBy(DownloadExecutionRequest::operationId)
    if (distinctRequests.isEmpty()) return
    val appContext = context.applicationContext
    val recoveryJob = scope.launch {
        var postCoreWorkerHandoffAttempted = false
        for (candidate in distinctRequests) {
            var schedule: DownloadExecutionSchedule? = null
            var handedOffPostCore = false
            val admitted = try {
                admitDownloadMutation(
                    context = appContext,
                    admissionTicket = admissionTicket,
                    stableKey = candidate.song.stableKey(),
                    operationId = candidate.operationId
                ) recoveryAdmission@{
                    val latest = DownloadExecutionRoomStore.read(
                        context = appContext,
                        operationId = candidate.operationId
                    ) ?: return@recoveryAdmission
                    val state = DownloadExecutionRoomStore.state(
                        context = appContext,
                        operationId = candidate.operationId
                    )
                    val requestMatchesSong =
                        latest.song.stableKey() == candidate.song.stableKey()
                    val userStopped = requestMatchesSong &&
                        canScheduleRecoveredDownloadOperation(state) &&
                        DownloadExecutionRoomStore.isStopped(
                            context = appContext,
                            operationId = candidate.operationId
                        )
                    if (
                        !shouldRehandoffRecoveredDownloadOperation(
                            operationState = state,
                            requestMatchesSong = requestMatchesSong,
                            isExecuting = DownloadExecutionHosts.default
                                .isExecuting(candidate.operationId),
                            isStoppedByUser = userStopped
                        )
                    ) {
                        return@recoveryAdmission
                    }
                    if (requiresDownloadFinalizationRecovery(state)) {
                        if (!postCoreWorkerHandoffAttempted) {
                            postCoreWorkerHandoffAttempted = true
                            val postCoreWorkerScheduled =
                                PostCoreDownloadRecoveryWorker.schedule(appContext)
                            NPLogger.d(
                                TAG,
                                "遗留 core operation 已统一交给共享收尾 Worker: " +
                                    "scheduled=$postCoreWorkerScheduled"
                            )
                        }
                        handedOffPostCore = true
                        return@recoveryAdmission
                    }
                    schedule = DownloadExecutionHosts.default.schedule(
                        context = appContext,
                        request = latest
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                NPLogger.w(
                    TAG,
                    "读取遗留下载 operation 失败，保留状态等待下次恢复: " +
                        "song=${candidate.song.name}, operationId=${candidate.operationId}, " +
                        "error=${error.message}",
                    error
                )
                continue
            }
            if (!admitted) {
                NPLogger.d(
                    TAG,
                    "清空任务已使遗留 operation 恢复请求过期: " +
                        "operationId=${candidate.operationId}"
                )
                return@launch
            }
            if (handedOffPostCore) {
                continue
            }
            when (val result = schedule) {
                is DownloadExecutionSchedule.Scheduled -> {
                    NPLogger.d(
                        TAG,
                        "已重新交给 OS 宿主的遗留下载 operation: " +
                            "song=${candidate.song.name}, " +
                            "operationId=${candidate.operationId}, backend=${result.backend}"
                    )
                }

                is DownloadExecutionSchedule.Rejected -> {
                    NPLogger.w(
                        TAG,
                        "遗留下载 operation 恢复调度被拒绝，保留状态等待下次恢复: " +
                            "song=${candidate.song.name}, " +
                            "operationId=${candidate.operationId}, reason=${result.reason}"
                    )
                }

                is DownloadExecutionSchedule.Deferred -> {
                    NPLogger.d(
                        TAG,
                        "遗留下载已登记等待全局宿主槽位: " +
                            "song=${candidate.song.name}, " +
                            "operationId=${candidate.operationId}"
                    )
                }

                null -> Unit
            }
        }
    }
    registerActiveBatchDownloadJob(recoveryJob)
}

internal fun GlobalDownloadManager.registerActiveBatchDownloadJob(job: Job) {
    activeBatchDownloadJobs += job
    taskStore.setActiveBatchDownloadJobCount(activeBatchDownloadJobs.size)
    job.invokeOnCompletion {
        activeBatchDownloadJobs.remove(job)
        taskStore.setActiveBatchDownloadJobCount(activeBatchDownloadJobs.size)
    }
}

internal suspend fun GlobalDownloadManager.handleBatchDownloadScheduleFailure(
    context: Context,
    request: QueuedDownloadRequest,
    errorCode: String,
    expectedArtifactLeaseId: String?
): Boolean {
    val songKey = request.song.stableKey()
    val retryMarked = runCatching {
        DownloadExecutionRoomStore.markScheduleRejectedRetryable(
            context = context,
            operationId = request.operationId,
            stableKey = songKey,
            errorCode = errorCode
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "写入批量 operation 重试状态失败: ${error.message}")
    }.getOrDefault(false)
    if (retryMarked) {
        // durable operation 已经成功回到 RETRYABLE，就不能把 UI/task terminal 同时标成 FAILED。
        // 立即唤醒共享泵，让批量成员继续保持 handed-off，避免形成“18/19 + 1 个残留任务”。
        val pumpScheduled = ForegroundDownloadWorker.schedule(
            context = context,
            operationId = request.operationId
        )
        if (pumpScheduled) {
            updateTaskStatus(
                songKey,
                DownloadStatus.QUEUED,
                expectedAttemptId = request.attemptId
            )
            publishDownloadStage(
                song = request.song,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST,
                operationId = request.operationId,
                attemptId = request.attemptId
            )
            return true
        }
    } else {
        val latestState = runCatching {
            DownloadExecutionRoomStore.state(context, request.operationId)
        }.getOrNull()
        val latestRequest = runCatching {
            DownloadExecutionRoomStore.read(context, request.operationId)
        }.getOrNull()
        if (
            latestState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES &&
                latestRequest?.song?.stableKey() == songKey
        ) {
            return true
        }
    }
    updateTaskStatus(
        songKey,
        DownloadStatus.FAILED,
        expectedAttemptId = request.attemptId
    )
    runCatching {
        expectedArtifactLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        markDownloadArtifactRetryable(
            context = context,
            song = request.song,
            leaseId = expectedArtifactLeaseId,
            errorCode = errorCode
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "释放批量 artifact 租约失败: ${error.message}")
    }
    return false
}

internal suspend fun GlobalDownloadManager.maybeRequestTrafficRiskDownloadConfirmation(
    context: Context,
    songs: List<SongItem>,
    isBatch: Boolean,
    skipTrafficRiskPrompt: Boolean
): Boolean {
    if (skipTrafficRiskPrompt) {
        return false
    }
    val distinctSongs = songs.distinctBy { it.stableKey() }
    if (distinctSongs.isEmpty()) {
        return false
    }
    val networkType = context.currentDownloadNetworkTypeOrNull()
    if (networkType == TrafficNetworkType.WIFI) {
        return false
    }
    if (networkType == null) {
        NPLogger.d(TAG, "下载网络类型未知，暂不展示移动网络确认提示")
        return false
    }
    if (!AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow.first()) {
        return false
    }

    trafficRiskDownloadRequestsMutable.emit(
        TrafficRiskDownloadRequest(
            id = trafficRiskRequestIdGenerator.incrementAndGet(),
            songs = distinctSongs,
            networkType = networkType,
            isBatch = isBatch
        )
    )
    return true
}

internal suspend fun GlobalDownloadManager.findPendingAudioForFinalization(
    context: Context,
    song: SongItem,
    operationId: String?,
    preferredAudioName: String?,
    preferredAudioReference: String? = null
): ManagedDownloadStorage.StoredEntry? {
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val normalizedPreferredReference = preferredAudioReference
        ?.trim()
        ?.takeIf(String::isNotBlank)
    normalizedPreferredReference?.let { reference ->
        resolveStoredAudio(context, reference)
            ?.takeIf(::isUsableFinalizationAudioEntry)
            ?.let { audio ->
                val metadata = readDownloadedMetadata(context, audio)
                if (metadata != null &&
                    isRecoveryMetadataOwnedBySong(metadata, song, normalizedOperationId)
                ) {
                    return audio
                }
            }
    }
    resolveCoreRecoveryAudioCandidate(
        context = context,
        song = song,
        operationId = normalizedOperationId,
        allowFormalAudio = true,
        preferredAudioName = preferredAudioName
    )?.let { candidate ->
        return candidate.audio
    }

    val snapshot = loadFinalizationRecoverySnapshot(
        context = context,
        forceRefresh = true
    ) ?: return null
    val candidates = linkedMapOf<String, ManagedDownloadStorage.StoredEntry>()
    fun addCandidate(audio: ManagedDownloadStorage.StoredEntry?) {
        if (audio == null || audio.reference.isBlank()) {
            return
        }
        candidates.putIfAbsent(audio.reference, audio)
    }

    val normalizedPreferredName = preferredAudioName
        ?.trim()
        ?.takeIf(String::isNotBlank)
    normalizedPreferredReference?.let { reference ->
        addCandidate(
            snapshot.audioEntriesByLookupKey[reference]
                ?: snapshot.audioEntries.firstOrNull { entry ->
                    entry.reference == reference ||
                        entry.mediaUri == reference ||
                        entry.localFilePath == reference
                }
                ?: snapshot.pendingAudioEntries.firstOrNull { entry ->
                    entry.reference == reference ||
                        entry.mediaUri == reference ||
                        entry.localFilePath == reference
                }
        )
    }
    val preferredEntries = normalizedPreferredName?.let { name ->
        listOfNotNull(
            snapshot.audioEntries.firstOrNull { entry ->
                listOf(entry.name, entry.logicalName).any { actualName ->
                    matchesFinalizationStoredName(
                        actualName = actualName,
                        expectedName = name
                    )
                }
            },
            snapshot.pendingAudioEntries.firstOrNull { entry ->
                listOf(entry.name, entry.logicalName).any { actualName ->
                    matchesFinalizationStoredName(
                        actualName = actualName,
                        expectedName = name
                    )
                }
            }
        )
    }.orEmpty()
    preferredEntries.forEach(::addCandidate)
    addCandidate(ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song))
    addCandidate(ManagedDownloadStorage.findDownloadedAudio(snapshot, song))

    val likelyNames = ManagedDownloadStorage.buildCandidateBaseNames(song).toSet()
    (snapshot.audioEntries + snapshot.audioEntriesWithoutMetadata)
        .asSequence()
        .filter(::isUsableFinalizationAudioEntry)
        .filter { audio ->
            matchesFinalizationCandidateName(
                audio = audio,
                candidateBaseNames = likelyNames
            )
        }
        .forEach(::addCandidate)
    var directMetadataProbeBudget = 8
    candidates.values.forEach { audio ->
        val indexedMetadata = ManagedDownloadStorage.metadataForAudioEntry(
            snapshot = snapshot,
            audio = audio
        )
        val metadata = indexedMetadata ?: if (directMetadataProbeBudget > 0) {
            directMetadataProbeBudget--
            runCatching { readDownloadedMetadata(context, audio) }.getOrNull()
        } else {
            null
        }
        if (
            metadata != null &&
                isRecoveryMetadataOwnedBySong(metadata, song, normalizedOperationId)
        ) {
            return audio
        }
    }

    (snapshot.pendingAudioEntries + snapshot.audioEntries + snapshot.audioEntriesWithoutMetadata)
        .asSequence()
        .filter(::isUsableFinalizationAudioEntry).singleOrNull { audio ->
            matchesFinalizationCandidateName(
                audio = audio,
                candidateBaseNames = likelyNames
            )
        }
        ?.let { audio ->
            val metadata = runCatching {
                readDownloadedMetadata(context, audio)
            }.getOrNull()
            if (
                metadata != null &&
                    isRecoveryMetadataOwnedBySong(metadata, song, normalizedOperationId)
            ) {
                return audio
            }
        }
    return null
}

internal fun GlobalDownloadManager.isUsableFinalizationAudioEntry(
    audio: ManagedDownloadStorage.StoredEntry
): Boolean {
    return !audio.isDirectory &&
        (audio.sizeBytes > 0L || !audio.sizeKnown) &&
        ManagedDownloadStorage.resolveStoredEntryPlaybackUri(
            entry = audio,
            allowPending = true
        ) != null
}

internal fun GlobalDownloadManager.matchesFinalizationCandidateName(
    audio: ManagedDownloadStorage.StoredEntry,
    candidateBaseNames: Collection<String>
): Boolean {
    val actualName = ManagedDownloadTreeNaming.logicalAudioName(audio.name)
        .substringBeforeLast('.', ManagedDownloadTreeNaming.logicalAudioName(audio.name))
        .replace(Regex(" \\(\\d+\\)$"), "")
        .let(ManagedDownloadTreeNaming::canonicalLookupName)
    return candidateBaseNames.any { baseName ->
        ManagedDownloadTreeNaming.canonicalLookupName(
            ManagedDownloadTreeNaming.logicalAudioName(baseName)
        ) == actualName
    }
}

internal fun GlobalDownloadManager.matchesFinalizationStoredName(
    actualName: String,
    expectedName: String
): Boolean {
    val canonicalActualName = ManagedDownloadTreeNaming.canonicalLookupName(
        ManagedDownloadTreeNaming.logicalAudioName(actualName)
    )
    val canonicalExpectedName = ManagedDownloadTreeNaming.canonicalLookupName(
        ManagedDownloadTreeNaming.logicalAudioName(expectedName)
    )
    return canonicalActualName == canonicalExpectedName ||
        ManagedDownloadTreeNaming.providerNumberedNameOrdinal(
            actualName = canonicalActualName,
            expectedName = canonicalExpectedName
        ) != null
}

internal suspend fun GlobalDownloadManager.buildBatchDownloadLibrarySnapshot(
    context: Context
): ManagedDownloadStorage.DownloadLibrarySnapshot? {
    val refreshedSnapshot = withTimeoutOrNull(
        STARTUP_INITIAL_SCAN_WAIT_TIMEOUT_MS
    ) {
        loadFinalizationRecoverySnapshot(
            context = context,
            forceRefresh = true,
            allowFreshCacheReuse = false
        )
    }
    if (refreshedSnapshot == null || !refreshedSnapshot.rootEntriesComplete) {
        NPLogger.w(
            TAG,
            "批量下载未能在启动预算内取得完整目录快照: " +
                "snapshot=" + (refreshedSnapshot != null)
        )
        scheduleCatalogReconcile(context, forceRefresh = true)
    }
    return refreshedSnapshot
}

internal fun GlobalDownloadManager.loadBatchCompletionCatalogIndex(
    context: Context
): DownloadedSongCatalogIndex {
    if (downloadedSongCatalogReady) {
        return downloadedSongCatalogIndex
    }
    val restoredSongs = runCatching {
        downloadedSongCatalogStore.restore(context.applicationContext)
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "批量预检读取持久下载目录失败，继续使用当前索引: ${error.message}",
            error
        )
    }.getOrNull()
    return restoredSongs?.let(::buildDownloadedSongCatalogIndex)
        ?: downloadedSongCatalogIndex
}
