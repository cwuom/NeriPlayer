package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.GlobalDownloadManager.ActiveProgressCheckpointBinding
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PreparedConfirmedDownload
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.artifact.ownedLeaseIdOrNull
import moe.ouom.neriplayer.core.download.execution.DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.DownloadTransferAdmissionDeferredException
import moe.ouom.neriplayer.core.download.execution.DownloadStorageRecoveryWorker
import moe.ouom.neriplayer.core.download.execution.WifiBoundDownloadWakeWorker
import moe.ouom.neriplayer.core.download.resource.DOWNLOAD_STORAGE_SPACE_ERROR_CODE
import moe.ouom.neriplayer.core.download.resource.DownloadStorageSpaceDeferredException
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.DownloadSourceUnavailableException
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection


internal suspend fun GlobalDownloadManager.prepareConfirmedDownload(
    context: Context,
    song: SongItem,
    requestGeneration: Long,
    preparedAttemptId: Long?,
    operationId: String?,
    artifactLeaseOwnerId: String?,
    admissionTicket: Long
): PreparedConfirmedDownload? {
    val appContext = context.applicationContext
    val songKey = song.stableKey()
    var prepared: PreparedConfirmedDownload? = null
    var unhandedLeaseId: String? = null
    try {
        val admitted = admitDownloadMutation(
            context = appContext,
            admissionTicket = admissionTicket,
            stableKey = songKey,
            operationId = operationId
        ) admission@{
            if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                return@admission
            }
            if (
                preparedAttemptId != null &&
                    !taskStore.isDownloadAttemptCurrent(songKey, preparedAttemptId)
            ) {
                return@admission
            }
            if (
                operationId != null &&
                    !DownloadExecutionRoomStore.isExecutionOwned(
                        context = appContext,
                        operationId = operationId,
                        stableKey = songKey
                    )
            ) {
                return@admission
            }

            var persistedOperationRequest = operationId?.let { id ->
                DownloadExecutionRoomStore.read(appContext, id)
                    ?.takeIf { request -> request.song.stableKey() == songKey }
            }
            val durableArtifactLeaseOwnerId = artifactLeaseOwnerId
                ?: persistedOperationRequest?.artifactLeaseId
                ?: ManagedDownloadStorage.findQueuedOperationIdForSong(appContext, songKey)
                    ?.let { id ->
                        DownloadExecutionRoomStore.read(appContext, id)?.artifactLeaseId
                    }
            var artifactClaim = try {
                managedDownloadArtifactCoordinator.claim(
                    context = appContext,
                    song = song,
                    reconcileStorage = false,
                    leaseOwnerId = durableArtifactLeaseOwnerId,
                    allowFreshTransferReclaim = persistedOperationRequest?.userInitiated == true
                )
            } catch (cancellation: CancellationException) {
                durableArtifactLeaseOwnerId?.let { leaseId ->
                    releaseDownloadArtifactClaim(appContext, song, leaseId)
                }
                throw cancellation
            } catch (error: Exception) {
                NPLogger.w(TAG, "下载 artifact claim 失败，继续现有恢复路径: ${error.message}")
                null
            }
            // 用户点击可能与已经启动的 OS 宿主并发。宿主在第一次 claim
            // 后才读到新的 userInitiated 时，必须重新取得一次 claim，
            // 否则旧的 post-core 引用会继续把执行导向“只收尾不传输”。
            val latestPromotedRequest = operationId?.let { id ->
                DownloadExecutionRoomStore.read(appContext, id)
                    ?.takeIf { request -> request.song.stableKey() == songKey }
            }
            if (
                latestPromotedRequest?.userInitiated == true &&
                    persistedOperationRequest?.userInitiated != true &&
                    artifactClaim !is ManagedDownloadArtifactClaim.Acquired
            ) {
                val refreshedClaim = try {
                    managedDownloadArtifactCoordinator.claim(
                        context = appContext,
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
                        "用户重试意图已持久化，但 artifact claim 重试失败: " +
                            "song=${song.name}, operationId=$operationId, " +
                            "error=${error.message}",
                        error
                    )
                    null
                }
                if (refreshedClaim != null) {
                    artifactClaim = refreshedClaim
                    persistedOperationRequest = latestPromotedRequest
                    NPLogger.d(
                        TAG,
                        "检测到并发提升的用户重试意图，已刷新 artifact claim: " +
                            "song=${song.name}, operationId=$operationId, " +
                            "claim=${refreshedClaim.javaClass.simpleName}"
                    )
                }
            } else if (latestPromotedRequest?.userInitiated == true) {
                // 即使第一次读取已经看到了 true，也要把最新的 payload 传到
                // prepared 结果，避免 attempt/lease 仍使用旧快照
                persistedOperationRequest = latestPromotedRequest
            }
            artifactClaim = reclaimOrphanedTransferLeaseIfSafe(
                context = appContext,
                song = song,
                operationId = operationId,
                leaseOwnerId = persistedOperationRequest?.artifactLeaseId
                    ?: durableArtifactLeaseOwnerId,
                artifactClaim = artifactClaim,
                userInitiated = persistedOperationRequest?.userInitiated == true
            ) ?: artifactClaim
            val claimedArtifact = when (artifactClaim) {
                is ManagedDownloadArtifactClaim.AlreadyDownloaded -> artifactClaim.artifact
                is ManagedDownloadArtifactClaim.RepairRequired -> artifactClaim.artifact
                else -> null
            }
            val requiresFinalizationRecovery = claimedArtifact?.state
                ?.let(::requiresDownloadFinalizationRecovery) == true ||
                artifactClaim is ManagedDownloadArtifactClaim.RepairRequired &&
                claimedArtifact?.state == ManagedDownloadArtifactState.FINALIZED.name
            val acquiredLeaseId = artifactClaim.ownedLeaseIdOrNull()
                ?: claimedArtifact?.takeIf { artifact ->
                        requiresFinalizationRecovery &&
                            artifact.leaseId == durableArtifactLeaseOwnerId
                    }
                    ?.leaseId
            if (artifactClaim is ManagedDownloadArtifactClaim.Acquired ||
                requiresFinalizationRecovery
            ) {
                unhandedLeaseId = acquiredLeaseId
            }
            acquiredLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases[songKey] = leaseId
            }

            when (artifactClaim) {
                is ManagedDownloadArtifactClaim.AlreadyDownloaded -> {
                    if (!requiresFinalizationRecovery) {
                        val durableSettled = settleAlreadyDownloadedOperation(
                            context = appContext,
                            song = song,
                            operationId = operationId,
                            expectedAttemptId = persistedOperationRequest?.attemptId
                                ?: preparedAttemptId,
                            reason = "DOWNLOAD_ALREADY_PRESENT"
                        )
                        if (operationId != null && !durableSettled) {
                            NPLogger.w(
                                TAG,
                                "已下载 operation 尚未完成 CAS，保留任务等待重试: " +
                                    "song=${song.name}, operationId=$operationId"
                            )
                            return@admission
                        }
                        updateTaskStatus(
                            songKey,
                            DownloadStatus.COMPLETED,
                            expectedAttemptId = preparedAttemptId,
                            operationId = operationId
                        )
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                        NPLogger.d(
                            TAG,
                            "跳过已完成下载 artifact: song=${song.name}, songKey=$songKey"
                        )
                        return@admission
                    }
                }

                is ManagedDownloadArtifactClaim.InFlight -> {
                    NPLogger.d(TAG, "跳过重复下载请求: song=${song.name}, songKey=$songKey")
                    return@admission
                }

                is ManagedDownloadArtifactClaim.Acquired,
                is ManagedDownloadArtifactClaim.RepairRequired,
                null -> Unit
            }

            val attemptId = preparedAttemptId ?: taskStore.prepareDownloadTask(song) ?: run {
                if (artifactClaim is ManagedDownloadArtifactClaim.Acquired) {
                    markDownloadArtifactRetryable(
                        context = appContext,
                        song = song,
                        leaseId = artifactClaim.artifact.leaseId,
                        errorCode = "TASK_ALREADY_ACTIVE"
                    )
                    artifactClaim.artifact.leaseId?.let { leaseId ->
                        managedDownloadArtifactLeases.remove(songKey, leaseId)
                    }
                    unhandedLeaseId = null
                }
                return@admission
            }
            if (
                operationId != null &&
                    !DownloadExecutionRoomStore.isExecutionOwned(
                        context = appContext,
                        operationId = operationId,
                        stableKey = songKey
                    )
            ) {
                acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactAfterExecutionOwnershipLoss(
                        context = appContext,
                        song = song,
                        operationId = operationId,
                        expectedLeaseId = leaseId
                    )
                }
                unhandedLeaseId = null
                return@admission
            }
            prepared = PreparedConfirmedDownload(
                artifactClaim = artifactClaim,
                requiresFinalizationRecovery = requiresFinalizationRecovery,
                acquiredLeaseId = acquiredLeaseId,
                attemptId = attemptId,
                userInitiated = persistedOperationRequest?.userInitiated == true,
                isBatchOperation = persistedOperationRequest?.batchId != null
            )
        }
        if (admitted && prepared != null) {
            unhandedLeaseId = null
        }
        return prepared.takeIf { admitted }
    } finally {
        val leaseId = unhandedLeaseId
        if (leaseId != null) {
            withContext(NonCancellable) {
                runCatching {
                    if (operationId != null) {
                        releaseDownloadArtifactAfterExecutionOwnershipLoss(
                            context = appContext,
                            song = song,
                            operationId = operationId,
                            expectedLeaseId = leaseId
                        )
                    } else {
                        releaseDownloadArtifactClaim(
                            context = appContext,
                            song = song,
                            expectedLeaseId = leaseId
                        )
                    }
                }.onFailure { error ->
                    NPLogger.w(
                        TAG,
                        "下载准备异常时 artifact lease 未收敛，保留恢复凭据: " +
                            "song=${song.name}, error=${error.message}",
                        error
                    )
                }
            }
        }
    }
}

internal suspend fun GlobalDownloadManager.startDownloadConfirmed(
    context: Context,
    song: SongItem,
    cleanupBeforeStart: Boolean,
    requestGeneration: Long,
    deferForNetworkPolicy: Boolean,
    preparedAttemptId: Long? = null,
    operationId: String? = null,
    artifactLeaseOwnerId: String? = null,
    downloadAudioQuality: DownloadAudioQualitySelection? = null,
    admissionTicket: Long? = null
) {
    val appContext = context.applicationContext
    val songKey = song.stableKey()
    if (
        operationId != null &&
            !DownloadExecutionRoomStore.isExecutionOwned(appContext, operationId, songKey)
    ) {
        return
    }
    if (
        admissionTicket != null &&
            downloadAdmissionGate.openTicketOrNull() != admissionTicket
    ) {
        NPLogger.d(
            TAG,
            "忽略已被清空代次取代的单曲下载启动: " +
                "song=${song.name}, admissionTicket=$admissionTicket"
        )
        return
    }
    if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
        NPLogger.d(TAG, "忽略过期单曲下载启动: song=${song.name}, generation=$requestGeneration")
        return
    }
    val effectiveAdmissionTicket = admissionTicket
        ?: openDownloadAdmissionTicketOrNull(appContext)
        ?: return
    val prepared = prepareConfirmedDownload(
        context = appContext,
        song = song,
        requestGeneration = requestGeneration,
        preparedAttemptId = preparedAttemptId,
        operationId = operationId,
        artifactLeaseOwnerId = artifactLeaseOwnerId,
        admissionTicket = effectiveAdmissionTicket
    ) ?: return
    val artifactClaim = prepared.artifactClaim
    val claimedArtifact = when (artifactClaim) {
        is ManagedDownloadArtifactClaim.AlreadyDownloaded -> artifactClaim.artifact
        is ManagedDownloadArtifactClaim.RepairRequired -> artifactClaim.artifact
        else -> null
    }
    val requiresFinalizationRecovery = prepared.requiresFinalizationRecovery
    val acquiredLeaseId = prepared.acquiredLeaseId
    val attemptId = prepared.attemptId
    try {
        withSongExecutionLock(songKey, releasable = true) {
            val cancellationSettled = withoutSongExecutionLock {
                awaitSongCancellationSettled(
                    songKey = songKey,
                    timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS
                )
            }
            if (!shouldClearNetworkPolicyPauseAfterCancellationSettled(cancellationSettled)) {
                NPLogger.w(
                    TAG,
                    "前一下载取消未在预算内收敛，保留网络暂停标记: " +
                        "song=${song.name}, songKey=$songKey"
                )
                if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                    updateTaskStatus(
                        songKey = songKey,
                        status = DownloadStatus.WAITING_NETWORK,
                        expectedAttemptId = attemptId
                    )
                    operationId?.let { id ->
                        runCatching {
                            DownloadExecutionRoomStore.updateState(
                                context = appContext,
                                operationId = id,
                                state = "RETRYABLE",
                                errorCode = "CANCELLATION_SETTLEMENT_PENDING"
                            )
                            WifiBoundDownloadWakeWorker.rearmAfterNetworkPolicyWait(
                                context = appContext,
                                operationId = id
                            )
                        }.onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "登记取消收敛后的 WIFI 唤醒失败: " +
                                    "operationId=$id, error=${error.message}"
                            )
                        }
                    }
                }
                return@withSongExecutionLock
            }
            AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
            if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                NPLogger.d(TAG, "单曲下载等待取消收敛后已过期: song=${song.name}, generation=$requestGeneration")
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
                return@withSongExecutionLock
            }
            if (
                operationId != null &&
                    !DownloadExecutionRoomStore.isExecutionOwned(
                        context = appContext,
                        operationId = operationId,
                        stableKey = songKey
                    )
            ) {
                acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactAfterExecutionOwnershipLoss(
                        context = appContext,
                        song = song,
                        operationId = operationId,
                        expectedLeaseId = leaseId
                    )
                }
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
                return@withSongExecutionLock
            }
            if (requiresFinalizationRecovery) {
                val storedAudio = findPendingAudioForFinalization(
                    context = appContext,
                    song = song,
                    operationId = operationId,
                    preferredAudioName = claimedArtifact?.audioName,
                    preferredAudioReference = claimedArtifact?.audioReference
                )
                if (storedAudio == null) {
                    NPLogger.w(
                        TAG,
                        "未找到可确认的已下载音频，保留 operation 等待恢复: " +
                            "song=${song.name}, operationId=$operationId, " +
                            "userInitiated=${prepared.userInitiated}, " +
                            "artifactState=${claimedArtifact?.state}, " +
                            "claim=${artifactClaim?.javaClass?.simpleName}"
                    )
                    return@withSongExecutionLock
                }
                finalizeCompletedDownload(
                    context = appContext,
                    song = song,
                    expectedAttemptId = attemptId,
                    operationId = operationId,
                    expectedArtifactLeaseId = acquiredLeaseId,
                    storedAudioHint = storedAudio,
                    allowMissingTask = true,
                    admissionTicket = admissionTicket
                )
                return@withSongExecutionLock
            }
            val preserveArtifactForRepair =
                artifactClaim is ManagedDownloadArtifactClaim.RepairRequired ||
                    (artifactClaim as? ManagedDownloadArtifactClaim.Acquired)
                        ?.preservesExistingReference == true
            val forceFreshTransfer = (artifactClaim as? ManagedDownloadArtifactClaim.Acquired)
                ?.preservesExistingReference == true
            if (cleanupBeforeStart && !preserveArtifactForRepair) {
                cleanupDownloadArtifactsBeforeFreshStart(
                    context = appContext,
                    song = song,
                    forceStorageRefresh = shouldForceFreshStartStorageScan(
                        isBatchOperation = prepared.isBatchOperation
                    )
                )
            }
            if (
                cleanupBeforeStart &&
                    operationId != null &&
                    !DownloadExecutionRoomStore.markStagingPrepared(
                        context = appContext,
                        operationId = operationId,
                        stableKey = songKey
                    )
            ) {
                error("failed to persist prepared download staging")
            }
            if (shouldSkipDownload(appContext, song)) {
                acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactClaim(appContext, song, leaseId)
                }
                val durableSettled = settleAlreadyDownloadedOperation(
                    context = appContext,
                    song = song,
                    operationId = operationId,
                    expectedAttemptId = attemptId,
                    reason = "LOCAL_SONG_SKIPPED"
                )
                if (operationId != null && !durableSettled) {
                    NPLogger.w(
                        TAG,
                        "本地歌曲跳过但 operation 尚未完成 CAS，保留任务等待重试: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@withSongExecutionLock
                }
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
                forgetPendingDownloadQueueEntriesIfCurrent(
                    appContext,
                    setOf(songKey),
                    requestGeneration
                )
                return@withSongExecutionLock
            }

            val fastCachedSong = if (forceFreshTransfer) {
                null
            } else {
                findFastCachedDownloadedSong(appContext, song)
            }
            if (fastCachedSong != null) {
                // 快索引可能先于 SAF 全量快照恢复；远端 SongItem 没有本地路径时，
                // 必须回退到 catalog 已验证的正式引用，否则缓存命中会被误判为 Retry
                val storedAudio = resolveStoredAudio(appContext, song)
                    ?: resolveStoredAudio(appContext, fastCachedSong.filePath)
                    ?: resolveStoredAudio(appContext, fastCachedSong.mediaUri)
                    ?: run {
                        NPLogger.w(
                            TAG,
                            "缓存命中但无法定位正式音频，保留 operation 等待重试: " +
                                "song=${song.name}, operationId=$operationId"
                        )
                        return@withSongExecutionLock
                    }
                if (!markDownloadArtifactFinalized(
                        context = appContext,
                        song = song,
                        storedAudio = storedAudio,
                        leaseId = acquiredLeaseId
                    )
                ) {
                    NPLogger.w(
                        TAG,
                        "缓存命中但 artifact 未完成收尾，保留 operation 等待重试: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@withSongExecutionLock
                }
                acquiredLeaseId?.let { leaseId ->
                    managedDownloadArtifactLeases.remove(songKey, leaseId)
                }
                val repairedSong = repairDownloadedCoverIfMissing(
                    context = appContext,
                    song = song,
                    downloadedSong = fastCachedSong
                )
                if (repairedSong != fastCachedSong) {
                    publishOptimisticDownloadedSongs(appContext, listOf(repairedSong))
                }
                NPLogger.d(TAG, "单曲下载命中下载目录缓存并直接完成: song=${song.name}, songKey=$songKey")
                val durableSettled = settleAlreadyDownloadedOperation(
                    context = appContext,
                    song = song,
                    operationId = operationId,
                    expectedAttemptId = attemptId,
                    reason = "DOWNLOAD_CACHE_HIT"
                )
                if (operationId != null && !durableSettled) {
                    NPLogger.w(
                        TAG,
                        "缓存命中但 operation 尚未完成 CAS，保留任务等待重试: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@withSongExecutionLock
                }
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
                forgetPendingDownloadQueueEntriesIfCurrent(
                    appContext,
                    setOf(songKey),
                    requestGeneration
                )
                return@withSongExecutionLock
            }

            val existingAudio = if (forceFreshTransfer) {
                null
            } else {
                findExistingDownloadedAudio(
                    context = appContext,
                    song = song,
                    snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(appContext),
                    allowStorageLookup = false
                )
            }
            val needsFinalization = existingAudio?.let { audio ->
                isUnfinalizedDownloadedMetadata(
                    readDownloadedMetadata(appContext, audio)
                )
            } == true
            val existingAudioAction = resolvePreExistingDownloadedAudioAction(
                hasExistingAudio = existingAudio != null,
                needsFinalization = needsFinalization
            )
            if (existingAudio != null) {
                if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                    NPLogger.d(TAG, "单曲下载命中已存在文件时已过期: song=${song.name}, generation=$requestGeneration")
                    removeDownloadTask(songKey, expectedAttemptId = attemptId)
                    return@withSongExecutionLock
                }
                if (existingAudioAction == PreExistingDownloadedAudioAction.FINALIZE_EXISTING) {
                    finalizeCompletedDownload(
                        context = appContext,
                        song = song,
                        expectedAttemptId = attemptId,
                        storedAudioHint = existingAudio,
                        operationId = operationId,
                        expectedArtifactLeaseId = acquiredLeaseId,
                        admissionTicket = admissionTicket
                    )
                    return@withSongExecutionLock
                }
                if (existingAudioAction == PreExistingDownloadedAudioAction.DIRECT_SETTLE) {
                    val optimisticSong = repairDownloadedCoverIfMissing(
                        context = appContext,
                        song = song,
                        downloadedSong = buildOptimisticDownloadedSong(
                            song = song,
                            storedAudio = existingAudio
                        )
                    )
                    if (!markDownloadArtifactFinalized(
                            context = appContext,
                            song = song,
                            storedAudio = existingAudio,
                            leaseId = acquiredLeaseId
                        )
                    ) {
                        NPLogger.w(
                            TAG,
                            "已有音频命中但 artifact 未完成收尾，保留 operation 等待重试: " +
                                "song=${song.name}, operationId=$operationId"
                        )
                        return@withSongExecutionLock
                    }
                    publishOptimisticDownloadedSongs(
                        appContext,
                        listOf(optimisticSong)
                    )
                    acquiredLeaseId?.let { leaseId ->
                        managedDownloadArtifactLeases.remove(songKey, leaseId)
                    }
                    val durableSettled = settleAlreadyDownloadedOperation(
                        context = appContext,
                        song = song,
                        operationId = operationId,
                        expectedAttemptId = attemptId,
                        reason = "EXISTING_AUDIO_DIRECT_SETTLE"
                    )
                    if (operationId != null && !durableSettled) {
                        NPLogger.w(
                            TAG,
                                "已有音频命中但 operation 尚未完成 CAS，保留任务等待重试: " +
                                    "song=${song.name}, operationId=$operationId"
                        )
                        return@withSongExecutionLock
                    }
                    removeDownloadTask(songKey, expectedAttemptId = attemptId)
                    forgetPendingDownloadQueueEntriesIfCurrent(
                        appContext,
                        setOf(songKey),
                        requestGeneration
                    )
                    scheduleCatalogReconcile(appContext, forceRefresh = false)
                    NPLogger.d(
                        TAG,
                        "单曲下载命中已存在音频并直接完成: song=${song.name}, songKey=$songKey, file=${existingAudio.name}"
                    )
                    return@withSongExecutionLock
                }
            }

            if (
                deferQueuedDownloadStartForNetworkPolicyIfNeeded(
                    context = appContext,
                    songs = listOf(song),
                    attemptIdsBySongKey = mapOf(songKey to attemptId),
                    requestGeneration = requestGeneration,
                    reason = "single_start",
                    deferForNetworkPolicy = deferForNetworkPolicy
                ).isNotEmpty()
            ) {
                return@withSongExecutionLock
            }

            if (
                operationId != null &&
                    !DownloadExecutionRoomStore.isExecutionOwned(
                        context = appContext,
                        operationId = operationId,
                        stableKey = songKey
                    )
            ) {
                acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactAfterExecutionOwnershipLoss(
                        context = appContext,
                        song = song,
                        operationId = operationId,
                        expectedLeaseId = leaseId
                    )
                }
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
                return@withSongExecutionLock
            }

            if (isSongCancelled(songKey)) {
                throw CancellationException("Download cancelled before start")
            }
            if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                updateTaskStatus(
                    songKey,
                    DownloadStatus.WAITING_NETWORK,
                    expectedAttemptId = attemptId
                )
                return@withSongExecutionLock
            }

            val transferAdmitted = admitDownloadTransferStart(
                context = appContext,
                song = song,
                attemptId = attemptId,
                requestGeneration = requestGeneration,
                operationId = operationId,
                admissionTicket = admissionTicket
            )
            if (!transferAdmitted) {
                acquiredLeaseId?.let { leaseId ->
                    if (operationId != null) {
                        releaseDownloadArtifactAfterExecutionOwnershipLoss(
                            context = appContext,
                            song = song,
                            operationId = operationId,
                            expectedLeaseId = leaseId
                        )
                    } else {
                        releaseDownloadArtifactClaim(
                            context = appContext,
                            song = song,
                            expectedLeaseId = leaseId
                        )
                    }
                }
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
                NPLogger.d(
                    TAG,
                    "清空或新请求已使音频传输启动过期: " +
                        "song=${song.name}, operationId=$operationId, " +
                        "admissionTicket=$admissionTicket, " +
                        "currentTicket=${downloadAdmissionGate.openTicketOrNull()}, " +
                        "generation=$requestGeneration"
                )
                return@withSongExecutionLock
            }
            try {
                clearInitialBatchDownloadPresentationOnTransferStart(
                    songKey = songKey,
                    attemptId = attemptId,
                    operationId = operationId
                )
                resumeBatchDownloadPresentationOnRetry(
                    songKey = songKey,
                    attemptId = attemptId
                )
                val progressCheckpointBinding = operationId?.let { id ->
                    ActiveProgressCheckpointBinding(
                        operationId = id,
                        attemptId = attemptId,
                        admissionTicket = admissionTicket
                    )
                }
                progressCheckpointBinding?.let { binding ->
                    activeProgressCheckpointBindings[songKey] = binding
                    AudioDownloadManager.latestProgressForSong(
                        songKey = songKey,
                        attemptId = binding.attemptId,
                        operationId = binding.operationId
                    )?.let { latestProgress ->
                        updateDownloadProgress(latestProgress)
                    }
                    restoreTaskProgressCheckpoint(
                        context = appContext,
                        song = song,
                        binding = binding
                    )
                }
                try {
                    val transferStartStillCurrent =
                        !isDownloadClearFenceActive(
                            appContext,
                            stableKey = songKey,
                            operationId = operationId
                        ) &&
                            (admissionTicket == null ||
                                downloadAdmissionGate.openTicketOrNull() == admissionTicket) &&
                            isDownloadRequestGenerationCurrent(
                                songKey,
                                requestGeneration
                            ) &&
                            !isSongCancelled(songKey) &&
                            taskStore.isDownloadAttemptCurrent(songKey, attemptId) &&
                            (operationId == null ||
                                DownloadExecutionRoomStore.isExecutionOwned(
                                    context = appContext,
                                    operationId = operationId,
                                    stableKey = songKey
                                ))
                    if (!transferStartStillCurrent) {
                        acquiredLeaseId?.let { leaseId ->
                            if (operationId != null) {
                                releaseDownloadArtifactAfterExecutionOwnershipLoss(
                                    context = appContext,
                                    song = song,
                                    operationId = operationId,
                                    expectedLeaseId = leaseId
                                )
                            } else {
                                releaseDownloadArtifactClaim(
                                    context = appContext,
                                    song = song,
                                    expectedLeaseId = leaseId
                                )
                            }
                        }
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        NPLogger.d(
                            TAG,
                            "最终准入复核发现下载已过期，跳过音频传输: " +
                                "song=${song.name}, operationId=$operationId, " +
                                "admissionTicket=$admissionTicket, " +
                                "currentTicket=${downloadAdmissionGate.openTicketOrNull()}, " +
                                "generation=$requestGeneration"
                        )
                        return@withSongExecutionLock
                    }
                    withoutSongExecutionLock {
                        AudioDownloadManager.downloadSong(
                            context = appContext,
                            song = song,
                            attemptId = attemptId,
                            operationId = operationId,
                            downloadAudioQuality = downloadAudioQuality,
                            forceFreshTransfer = forceFreshTransfer
                        )
                    }
                } finally {
                    progressCheckpointBinding?.let { binding ->
                        withContext(NonCancellable) {
                            persistLatestProgressCheckpointNow(
                                context = appContext,
                                songKey = songKey,
                                binding = binding
                            )
                        }
                        activeProgressCheckpointBindings.remove(songKey, binding)
                        clearLatestProgressForOperation(binding.operationId)
                    }
                }
                if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                    NPLogger.d(TAG, "单曲下载完成后已过期，转入过期结果回滚: song=${song.name}, generation=$requestGeneration")
                    finalizeCompletedDownload(
                        context = appContext,
                        song = song,
                        expectedAttemptId = attemptId,
                        operationId = operationId,
                        expectedArtifactLeaseId = acquiredLeaseId,
                        admissionTicket = admissionTicket
                    )
                    return@withSongExecutionLock
                }
                finalizeCompletedDownload(
                    context = appContext,
                    song = song,
                    expectedAttemptId = attemptId,
                    operationId = operationId,
                    expectedArtifactLeaseId = acquiredLeaseId,
                    admissionTicket = admissionTicket
                )
            } finally {
                taskStore.endDownloadTransfer()
            }
        }
    } catch (error: DownloadStorageMutationDeferredException) {
        markDownloadArtifactRetryable(
            context = appContext,
            song = song,
            leaseId = acquiredLeaseId,
            errorCode = DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
        )
        acquiredLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        removeDownloadTask(songKey, expectedAttemptId = attemptId)
        NPLogger.d(
            TAG,
            "下载提交已转入目录迁移持久等待: " +
                "song=${song.name}, operationId=$operationId"
        )
    } catch (error: DownloadTransferAdmissionDeferredException) {
        markDownloadArtifactRetryable(
            context = appContext,
            song = song,
            leaseId = acquiredLeaseId,
            errorCode = "HOST_TRANSFER_ADMISSION_DEFERRED"
        )
        acquiredLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        removeDownloadTask(songKey, expectedAttemptId = attemptId)
        NPLogger.d(
            TAG,
            "宿主未授予传输槽位，保留 operation 等待重试: " +
                "song=${song.name}, operationId=$operationId, reason=${error.message}"
        )
        wakeDownloadExecutionPumpAfterTransferAdmissionDeferred(appContext)
    } catch (error: DownloadStorageSpaceDeferredException) {
        if (error.cancelAllDownloads) {
            requestStorageExhaustionCancellation(
                context = appContext,
                operationId = error.operationId,
                failureKind = error.failureKind
            )
            NPLogger.e(
                TAG,
                "确认下载存储耗尽，已请求取消全部下载任务: " +
                    "song=${song.name}, operationId=${error.operationId}, " +
                    "kind=${error.failureKind}"
            )
            return
        }
        val operationMarked = try {
            DownloadExecutionRoomStore.markWaitingForStorageMutation(
                context = appContext,
                operationId = error.operationId,
                errorCode = DOWNLOAD_STORAGE_SPACE_ERROR_CODE
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (markError: Throwable) {
            NPLogger.w(
                TAG,
                "写入空间等待 operation 状态失败，保留 artifact 凭据: " +
                    "operationId=${error.operationId}, error=${markError.message}",
                markError
            )
            false
        }
        if (operationMarked) {
            markDownloadArtifactWaitingForStorage(
                context = appContext,
                song = song,
                leaseId = acquiredLeaseId,
                errorCode = DOWNLOAD_STORAGE_SPACE_ERROR_CODE
            )
            updateTaskStatus(
                songKey,
                DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = attemptId
            )
            DownloadStorageRecoveryWorker.schedule(appContext)
            NPLogger.w(
                TAG,
                "下载因空间不足转入可恢复等待，保留 operation、lease 和工作文件: " +
                    "song=${song.name}, operationId=${error.operationId}"
            )
        } else {
            NPLogger.d(
                TAG,
                "空间等待未覆盖当前 operation，保留取消或新代次结果: " +
                    "song=${song.name}, operationId=${error.operationId}"
            )
        }
    } catch (_: CancellationException) {
        val coreRecoveryScheduled = recoverCorePublicationAfterExecutionCancellation(
            context = appContext,
            song = song,
            operationId = operationId,
            expectedAttemptId = attemptId,
            requestGeneration = requestGeneration,
            admissionTicket = admissionTicket,
            artifactLeaseId = acquiredLeaseId
        )
        if (coreRecoveryScheduled) {
            // core 已经提交后，宿主取消只代表本次执行结束，不能把正式发布凭据
            // 当成普通取消释放，否则 pending 音频只能等到下一次冷启动
            return
        }
        val pausedForNetworkPolicy =
            AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey) ||
                operationId?.let(
                    AudioDownloadManager::isOperationPausedForExecutionHost
                ) == true
        if (!pausedForNetworkPolicy) {
            acquiredLeaseId?.let { leaseId ->
                if (!handOffDownloadArtifactLeaseToTaskClear(
                        context = appContext,
                        songKey = songKey,
                        operationId = operationId,
                        expectedLeaseId = leaseId
                    )
                ) {
                    releaseDownloadArtifactClaim(appContext, song, leaseId)
                }
            }
        }
        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
            return
        }
        if (pausedForNetworkPolicy) {
            updateTaskStatus(
                songKey,
                DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = attemptId
            )
        } else {
            clearSongCancelled(songKey)
            updateTaskStatus(
                songKey,
                DownloadStatus.CANCELLED,
                expectedAttemptId = attemptId
            )
            forgetPendingDownloadQueueEntriesIfCurrent(
                appContext,
                setOf(songKey),
                requestGeneration
            )
        }
    } catch (error: DownloadSourceUnavailableException) {
        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
            markDownloadArtifactRetryable(
                context = appContext,
                song = song,
                leaseId = acquiredLeaseId,
                errorCode = "STALE_DOWNLOAD_FAILED"
            )
            acquiredLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases.remove(songKey, leaseId)
            }
            return
        }
        val settled = withContext(NonCancellable) {
            settleUnavailableDownloadSourceFailure(
                context = appContext,
                song = song,
                operationId = operationId,
                expectedAttemptId = attemptId,
                expectedLeaseId = acquiredLeaseId,
                requestGeneration = requestGeneration
            )
        }
        if (!settled) {
            NPLogger.w(
                TAG,
                "下载来源不可用终态尚未持久化，保留 operation 重试: " +
                    "song=${song.name}, operationId=$operationId"
            )
            throw error
        }
    } catch (error: Exception) {
        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
            markDownloadArtifactRetryable(
                context = appContext,
                song = song,
                leaseId = acquiredLeaseId,
                errorCode = "STALE_DOWNLOAD_FAILED"
            )
            acquiredLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases.remove(songKey, leaseId)
            }
            return
        }
        NPLogger.e(TAG, "下载失败: ${song.name} - ${error.message}", error)
        updateTaskStatus(
            songKey,
            DownloadStatus.FAILED,
            expectedAttemptId = attemptId
        )
        markDownloadArtifactRetryable(
            context = appContext,
            song = song,
            leaseId = acquiredLeaseId,
            errorCode = "DOWNLOAD_FAILED"
        )
        acquiredLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        forgetPendingDownloadQueueEntriesIfCurrent(
            appContext,
            setOf(songKey),
            requestGeneration
        )
    } finally {
        withContext(NonCancellable) {
            runCatching {
                settleUnfinishedDownloadArtifactLease(
                    context = appContext,
                    song = song,
                    operationId = operationId,
                    expectedLeaseId = acquiredLeaseId
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "单曲下载退出时 artifact lease 收敛失败，保留恢复凭据: " +
                        "song=${song.name}, error=${error.message}",
                    error
                )
            }
            operationId?.let(AudioDownloadManager::clearOperationPauseForExecutionHost)
        }
    }
}

internal suspend fun GlobalDownloadManager.settleUnavailableDownloadSourceFailure(
    context: Context,
    song: SongItem,
    operationId: String?,
    expectedAttemptId: Long?,
    expectedLeaseId: String?,
    requestGeneration: Long
): Boolean {
    val appContext = context.applicationContext
    val songKey = song.stableKey()
    val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
    val operationState = normalizedOperationId?.let { id ->
        val stateResult = runCatching {
            DownloadExecutionRoomStore.state(appContext, id)
        }
        if (stateResult.isFailure) {
            NPLogger.w(
                TAG,
                "下载来源不可用时读取 operation 状态失败，保留重试: " +
                    "song=${song.name}, operationId=$id, " +
                    "error=${stateResult.exceptionOrNull()?.message}"
            )
            return false
        }
        stateResult.getOrNull()
    }
    if (
        normalizedOperationId != null &&
            isDownloadSourceUnavailableSettlementSuperseded(operationState)
    ) {
        return true
    }

    val artifactSettled = expectedLeaseId?.let { leaseId ->
        runCatching {
            managedDownloadArtifactCoordinator.settleLeaseAnyRoot(
                context = appContext,
                song = song,
                expectedLeaseId = leaseId,
                requestedState = ManagedDownloadArtifactState.FAILED_RETRYABLE,
                errorCode = DOWNLOAD_SOURCE_UNAVAILABLE_ERROR_CODE
            )
        }.onFailure { settleError ->
            NPLogger.w(
                TAG,
                "下载来源不可用时收口 artifact 失败: " +
                    "song=${song.name}, operationId=$normalizedOperationId, " +
                    "error=${settleError.message}",
                settleError
            )
        }.getOrDefault(false)
    } ?: true
    if (!artifactSettled) {
        return false
    }

    if (normalizedOperationId != null) {
        val batchMemberPersisted = runCatching {
            DownloadExecutionRoomStore.markBatchMembersForOperation(
                context = appContext,
                operationId = normalizedOperationId,
                stableKey = songKey,
                attemptId = expectedAttemptId,
                terminalBits = DownloadBatchMemberTerminal.FAILED
            )
            true
        }.onFailure { persistError ->
            NPLogger.w(
                TAG,
                "下载来源不可用时写入批次成员终态失败: " +
                    "song=${song.name}, operationId=$normalizedOperationId, " +
                    "error=${persistError.message}",
                persistError
            )
        }.getOrDefault(false)
        if (!batchMemberPersisted) {
            return false
        }
        forgetPendingDownloadQueueEntriesForOperation(
            context = appContext,
            songKey = songKey,
            operationId = normalizedOperationId
        )

        val invalidated = runCatching {
            DownloadExecutionRoomStore.updateState(
                context = appContext,
                operationId = normalizedOperationId,
                state = "INVALID",
                errorCode = DOWNLOAD_SOURCE_UNAVAILABLE_ERROR_CODE
            )
        }.onFailure { persistError ->
            NPLogger.w(
                TAG,
                "下载来源不可用时终止 operation 失败: " +
                    "song=${song.name}, operationId=$normalizedOperationId, " +
                    "error=${persistError.message}",
                persistError
            )
        }.getOrDefault(false)
        if (!invalidated) {
            val latestStateResult = runCatching {
                DownloadExecutionRoomStore.state(appContext, normalizedOperationId)
            }
            if (latestStateResult.isFailure) {
                return false
            }
            if (!isDownloadSourceUnavailableSettlementSuperseded(latestStateResult.getOrNull())) {
                return false
            }
        }
    }

    expectedLeaseId?.let { leaseId ->
        managedDownloadArtifactLeases.remove(songKey, leaseId)
    }
    updateTaskStatus(
        songKey = songKey,
        status = DownloadStatus.FAILED,
        expectedAttemptId = expectedAttemptId,
        operationId = normalizedOperationId
    )
    if (normalizedOperationId == null) {
        forgetPendingDownloadQueueEntriesIfCurrent(
            context = appContext,
            songKeys = setOf(songKey),
            generation = requestGeneration
        )
    }
    wakeDownloadExecutionPump(
        context = appContext,
        reason = "download_source_unavailable"
    )
    NPLogger.w(
        TAG,
        "下载来源确认不可用，已停止自动重试并触发补位: " +
            "song=${song.name}, operationId=$normalizedOperationId"
    )
    return true
}

internal fun GlobalDownloadManager.isDownloadSourceUnavailableSettlementSuperseded(state: String?): Boolean {
    return state == null || state in setOf(
        "INVALID",
        "CANCEL_REQUESTED",
        "CANCELLED",
        "STOPPED",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "FINALIZED",
        "DEGRADED_COMPLETE",
        "COMPLETED"
    )
}

internal suspend fun GlobalDownloadManager.requestStorageExhaustionCancellation(
    context: Context,
    operationId: String,
    failureKind: moe.ouom.neriplayer.core.download.resource.DownloadStorageSpaceFailureKind
) = withContext(NonCancellable) {
    runCatching {
        // 先用轻量事务把所有 operation 置为取消请求，防止当前执行在
        // 全局清空协程真正启动前被共享泵再次取出
        DownloadExecutionRoomStore.requestCancelAllFast(context.applicationContext)
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "空间耗尽快速取消标记失败，继续启动完整清空: " +
                "operationId=$operationId, kind=$failureKind, error=${error.message}",
            error
        )
    }
    if (!storageExhaustionCancellationScheduled.compareAndSet(false, true)) {
        return@withContext
    }
    val cancellationJob = try {
        requestAllDownloadTaskCancellation()
    } catch (error: Throwable) {
        // 清空入口同步失败时允许下一次空间错误重新触发全局取消
        storageExhaustionCancellationScheduled.set(false)
        NPLogger.e(
            TAG,
            "空间耗尽全局取消入口启动失败: " +
                "operationId=$operationId, kind=$failureKind, error=${error.message}",
            error
        )
        return@withContext
    }
    cancellationJob.invokeOnCompletion {
        storageExhaustionCancellationScheduled.set(false)
    }
}

internal suspend fun GlobalDownloadManager.settleUnfinishedDownloadArtifactLease(
    context: Context,
    song: SongItem,
    operationId: String?,
    expectedLeaseId: String?
) {
    val leaseId = expectedLeaseId?.trim()?.takeIf(String::isNotBlank) ?: return
    val songKey = song.stableKey()
    if (handOffDownloadArtifactLeaseToTaskClear(
            context = context,
            songKey = songKey,
            operationId = operationId,
            expectedLeaseId = leaseId
        )
    ) {
        return
    }
    val operationState = operationId?.let { id ->
        runCatching { DownloadExecutionRoomStore.state(context, id) }.getOrNull()
    }
    val artifactState = runCatching {
        managedDownloadArtifactCoordinator.currentStateAnyRoot(
            context = context,
            song = song,
            expectedLeaseId = leaseId
        )
    }.getOrNull()
    val cancellationRequested = isSongCancelled(songKey) ||
        operationState == "CANCEL_REQUESTED" ||
        operationState == "CANCELLED" ||
        operationState == "STOPPED"
    if (cancellationRequested) {
        releaseDownloadArtifactClaim(context, song, leaseId)
        return
    }
    if (artifactState == ManagedDownloadArtifactState.FAILED_RETRYABLE) {
        managedDownloadArtifactLeases.remove(songKey, leaseId)
        return
    }
    if (artifactState in setOf(
            ManagedDownloadArtifactState.WAITING_STORAGE,
            ManagedDownloadArtifactState.COMMITTING,
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            ManagedDownloadArtifactState.FINALIZED
        )
    ) {
        // core 已经落盘，后续增强或启动恢复仍需要这条凭据
        if (artifactState == ManagedDownloadArtifactState.FINALIZED) {
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        return
    }
    markDownloadArtifactRetryable(
        context = context,
        song = song,
        leaseId = leaseId,
        errorCode = "DOWNLOAD_EXECUTION_EXITED"
    )
    managedDownloadArtifactLeases.remove(songKey, leaseId)
}
