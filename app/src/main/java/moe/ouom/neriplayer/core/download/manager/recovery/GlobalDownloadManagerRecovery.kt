package moe.ouom.neriplayer.core.download.manager.recovery

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.PendingDownloadRecoveryCandidate
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.isUnfinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.mergePendingDownloadRecoveryCandidates
import moe.ouom.neriplayer.core.download.manager.admission.admitArtifactRecoveryMutation
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.completeStartupProgressRestoreReady
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.promoteWaitingStorageMutationsForRecovery
import moe.ouom.neriplayer.core.download.manager.batch.findFastCompletedBatchSongKeys
import moe.ouom.neriplayer.core.download.manager.batch.loadBatchCompletionCatalogIndex
import moe.ouom.neriplayer.core.download.manager.batch.purgeSettledRecoveryEntries
import moe.ouom.neriplayer.core.download.manager.batch.recoverInFlightDownloadOperations
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.batch.settlePendingDownloadRecoveryDirectHits
import moe.ouom.neriplayer.core.download.manager.batch.startBatchDownload
import moe.ouom.neriplayer.core.download.manager.catalog.buildSongFromDurableMetadata
import moe.ouom.neriplayer.core.download.manager.catalog.hasBlockingActiveDownloadOperationsForRecovery
import moe.ouom.neriplayer.core.download.manager.catalog.waitForActiveDownloadJobsToSettle
import moe.ouom.neriplayer.core.download.manager.catalog.waitForQueuedTasksToAttachToBatch
import moe.ouom.neriplayer.core.download.manager.commit.cleanupCancelledPendingDownloadArtifacts
import moe.ouom.neriplayer.core.download.manager.commit.finalizeCompletedDownload
import moe.ouom.neriplayer.core.download.manager.commit.isDownloadMetadataPostProcessingEnabled
import moe.ouom.neriplayer.core.download.manager.commit.publishFinalizedDownload
import moe.ouom.neriplayer.core.download.manager.commit.schedulePersistedTerminalTemporaryWriteCleanup
import moe.ouom.neriplayer.core.download.manager.runtime.POST_CORE_DOWNLOAD_OPERATION_STATES
import moe.ouom.neriplayer.core.download.manager.runtime.currentWaitingNetworkTaskSongs
import moe.ouom.neriplayer.core.download.manager.runtime.deferPendingDownloadRecoveryForNetworkPolicyIfNeeded
import moe.ouom.neriplayer.core.download.manager.runtime.publishOptimisticDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.runtime.removeObsoleteWaitingNetworkTasks
import moe.ouom.neriplayer.core.download.manager.runtime.repairDownloadedCoverIfMissing
import moe.ouom.neriplayer.core.download.manager.runtime.shouldSkipDownload
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.manager.runtime.withSongExecutionLock
import moe.ouom.neriplayer.core.download.model.BatchDownloadPresentationState
import moe.ouom.neriplayer.core.download.model.BatchDownloadTerminalState
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.model.downloadProgressFraction
import moe.ouom.neriplayer.core.download.policy.FinalizedDownloadPublicationResult
import moe.ouom.neriplayer.core.download.policy.PendingDownloadRecoverySummary
import moe.ouom.neriplayer.core.download.policy.PendingWorkingProgressRecord
import moe.ouom.neriplayer.core.download.policy.PendingWorkingProgressSnapshot
import moe.ouom.neriplayer.core.download.policy.buildPendingWorkingProgressSnapshot
import moe.ouom.neriplayer.core.download.policy.durableOperationRecoveryPriority
import moe.ouom.neriplayer.core.download.policy.finalizedPublicationRecoveryLeaseOwnerId
import moe.ouom.neriplayer.core.download.policy.recoveredDownloadTaskPresentation
import moe.ouom.neriplayer.core.download.policy.requiresFinalizedPublicationRecovery
import moe.ouom.neriplayer.core.download.policy.resolveRecoveredDownloadProgress
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PendingDownloadRecoveryPlan
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PendingDownloadRecoveryDirectSettlement
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.RecoveryDirectSettlementResult
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactPublicationLease
import moe.ouom.neriplayer.core.download.artifact.finalizedPublicationLeaseOrNull
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.recovery.isArtifactRecoveryAllowed
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.worker.PostCoreDownloadRecoveryWorker
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.policy.recoveryOperationIdsForKeys
import moe.ouom.neriplayer.core.download.policy.shouldRecoverDownloadCandidateWithBatch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val PENDING_AUDIO_RECOVERY_PARALLELISM = 8

internal suspend fun GlobalDownloadManager.recoverPendingAudioWritesFromRoot(
    context: Context,
    directoryMutationLeaseOwned: Boolean = false,
    directoryUri: String? = null,
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
): PendingDownloadRecoverySummary {
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(context, admissionTicket)
    ) {
        return PendingDownloadRecoverySummary(
            leaseAcquired = directoryMutationLeaseOwned
        )
    }
    // 迁移源可能是应用私有目录，此时 URI 本来就是 null，仍要按源根目录恢复
    val sourceRootRecovery = directoryMutationLeaseOwned
    var cancelledSourceCleanupFailedCount = 0
    if (sourceRootRecovery) {
        val cancelledOperations = runCatching {
            DownloadExecutionRoomStore.listByStatesAnyLibrary(
                context = context,
                states = listOf("CANCEL_REQUESTED", "CANCELLED")
            ).map { entry ->
                ManagedDownloadStorage.CancelledPendingDownloadOperation(
                    stableKey = entry.request.song.stableKey(),
                    operationId = entry.request.operationId
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w(
                TAG,
                "读取源目录取消 operation 失败，保留 pending 凭据: ${error.message}",
                error
            )
            cancelledSourceCleanupFailedCount = 1
            emptyList()
        }
        if (cancelledOperations.isNotEmpty()) {
            var cleanup = ManagedDownloadStorage.StartupRecoveryResult(
                failedCount = cancelledOperations.size
            )
            val cleanupAdmitted = admitArtifactRecoveryMutation(
                context = context,
                admissionTicket = admissionTicket
            ) {
                cleanup = runCatching {
                    ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts(
                        context = context,
                        operations = cancelledOperations,
                        directoryUri = directoryUri,
                        useDefaultRootWhenDirectoryUriMissing = true
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    NPLogger.w(
                        TAG,
                        "迁移前源目录取消 pending 清理失败，保留凭据: ${error.message}",
                        error
                    )
                    ManagedDownloadStorage.StartupRecoveryResult(
                        failedCount = cancelledOperations.size
                    )
                }
            }
            if (!cleanupAdmitted) {
                NPLogger.d(
                    TAG,
                    "下载清空准入已失效，延后迁移前源目录取消 pending 清理"
                )
                return PendingDownloadRecoverySummary(
                    leaseAcquired = directoryMutationLeaseOwned,
                    initialScanComplete = false,
                    discoveredAudioCount = cancelledOperations.size,
                    failedAudioCount = cancelledOperations.size.coerceAtLeast(1)
                )
            }
            cancelledSourceCleanupFailedCount += cleanup.failedCount
            if (cleanup.failedCount > 0) {
                NPLogger.w(
                    TAG,
                    "迁移前源目录仍有取消 pending 未确认清理: " +
                        "operations=${cancelledOperations.size}, " +
                        "failed=${cleanup.failedCount}"
                )
            }
        }
    }
    val metadataPostProcessingEnabled = isDownloadMetadataPostProcessingEnabled(context)
    val pendingScan = runCatching {
        ManagedDownloadStorage.scanPendingAudioWrites(
            context = context,
            forceRefresh = true,
            directoryUri = directoryUri,
            useDefaultRootWhenDirectoryUriMissing = sourceRootRecovery
        )
    }.getOrElse { error ->
        if (error is CancellationException) {
            throw error
        }
        NPLogger.w(TAG, "读取 pending 音频失败，保留文件等待下次启动: ${error.message}")
        return PendingDownloadRecoverySummary(
            leaseAcquired = directoryMutationLeaseOwned,
            initialScanComplete = false
        )
    }
    val pendingAudioWrites = pendingScan.entries
    val sourceArtifactRootKey = if (sourceRootRecovery) {
        runCatching {
            ManagedDownloadStorage.snapshotRootKeyForOperation(
                context = context,
                directoryUri = directoryUri,
                useDefaultRootWhenDirectoryUriMissing = true
            )
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            NPLogger.w(
                TAG,
                "读取迁移源 artifact 根身份失败，保留 pending 凭据: " +
                    "directoryUri=$directoryUri, error=${error.message}",
                error
            )
            null
        }
    } else {
        null
    }
    if (
        sourceRootRecovery &&
            pendingAudioWrites.isNotEmpty() &&
            sourceArtifactRootKey == null
    ) {
        NPLogger.w(
            TAG,
            "迁移源 artifact 根身份不可用，跳过 pending 提升并等待重试: " +
                "count=${pendingAudioWrites.size}"
        )
        return PendingDownloadRecoverySummary(
            leaseAcquired = directoryMutationLeaseOwned,
            initialScanComplete = pendingScan.isComplete,
            discoveredAudioCount = pendingAudioWrites.size,
            failedAudioCount = pendingAudioWrites.size
        )
    }
    val attemptedAudioCount = AtomicInteger(0)
    val failedAudioCount = AtomicInteger(cancelledSourceCleanupFailedCount)
    val indexedPendingAudioWrites = pendingAudioWrites.withIndex().toList()
    val nextPendingAudioIndex = AtomicInteger(0)
    val workerCount = indexedPendingAudioWrites.size.coerceAtMost(
        PENDING_AUDIO_RECOVERY_PARALLELISM
    )
    coroutineScope {
        List(workerCount) {
            async(Dispatchers.IO) {
                while (true) {
                    val index = nextPendingAudioIndex.getAndIncrement()
                    if (index >= indexedPendingAudioWrites.size) break
                    if (
                        admissionTicket != null &&
                            !isDownloadAdmissionTicketCurrent(context, admissionTicket)
                    ) {
                        break
                    }
                    if (
                        index > 0 &&
                            index % STARTUP_ARTIFACT_RECOVERY_YIELD_BATCH_SIZE == 0
                    ) {
                        yield()
                    }
                    val pendingAudio = indexedPendingAudioWrites[index].value
                    attemptedAudioCount.incrementAndGet()
                    var itemResolved = false
                    val result = runCatching {
                        val metadata = if (sourceRootRecovery) {
                            ManagedDownloadStorage.readDownloadedMetadataFromRoot(
                                context = context,
                                audio = pendingAudio,
                                directoryUri = directoryUri,
                                preferPendingMetadata = true,
                                useDefaultRootWhenDirectoryUriMissing = sourceRootRecovery
                            )
                        } else {
                            readDownloadedMetadata(context, pendingAudio)
                        } ?: return@runCatching
                        val song = buildSongFromDurableMetadata(pendingAudio, metadata)
                            ?: return@runCatching
                        val mutationAdmitted = admitArtifactRecoveryMutation(
                            context = context,
                            admissionTicket = admissionTicket
                        ) {
                            withSongExecutionLock(song.stableKey()) {
                                if (!DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                                        context, metadata.operationId
                                    )
                                ) return@withSongExecutionLock
                                if (sourceRootRecovery) {
                                    val normalizedOperationId = metadata.operationId
                                        ?.trim()
                                        ?.takeIf(String::isNotBlank)
                                    val operationState = normalizedOperationId?.let { operationId ->
                                        DownloadExecutionRoomStore.state(context, operationId)
                                    }
                                    if (
                                        operationState == "CANCEL_REQUESTED" ||
                                            operationState == "CANCELLED" ||
                                            operationState == "STOPPED"
                                    ) {
                                        NPLogger.d(
                                            TAG,
                                            "迁移前 pending 收尾遇到取消状态，保留半成品等待取消清理: " +
                                                "song=${song.name}, operationId=$normalizedOperationId, " +
                                                "state=$operationState"
                                        )
                                        return@withSongExecutionLock
                                    }
                                    if (normalizedOperationId != null && operationState != null) {
                                        val journalReady = if (
                                            operationState == "CORE_COMMITTED" ||
                                                operationState == "ASSETS_ENRICHING" ||
                                                operationState == "DEGRADED_COMPLETE" ||
                                                operationState == "COMPLETED"
                                        ) {
                                            true
                                        } else {
                                            DownloadExecutionRoomStore.markCommitting(
                                                context = context,
                                                operationId = normalizedOperationId
                                            )
                                            val recovery =
                                                DownloadExecutionRoomStore.reconcileCoreCommitJournal(
                                                    context = context,
                                                    operationId = normalizedOperationId,
                                                    stableKey = song.stableKey(),
                                                    coreMetadataDurable = true
                                                )
                                            recovery.outcome ==
                                                DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.COMMITTED
                                        }
                                        if (!journalReady) {
                                            NPLogger.w(
                                                TAG,
                                                "迁移前 pending core journal 未确认，保留半成品等待恢复: " +
                                                    "song=${song.name}, operationId=$normalizedOperationId, " +
                                                    "state=$operationState"
                                            )
                                            return@withSongExecutionLock
                                        }
                                    }
                                    val promoted = ManagedDownloadStorage.promoteCoreCommittedPendingAudio(
                                        context = context,
                                        audio = pendingAudio,
                                        directoryUri = directoryUri,
                                        promotePendingMetadata = true,
                                        useDefaultRootWhenDirectoryUriMissing = sourceRootRecovery
                                    )
                                    if (promoted == null || promoted.isPendingAudioWrite) {
                                        NPLogger.w(
                                            TAG,
                                            "迁移前源目录 pending 音频未能原子提升，保留凭据: " +
                                                "song=${song.name}, file=${pendingAudio.name}"
                                        )
                                    } else {
                                        val artifactLeaseId = normalizedOperationId
                                            ?.let { operationId ->
                                                DownloadExecutionRoomStore.read(context, operationId)
                                                    ?.artifactLeaseId
                                            }
                                            ?: managedDownloadArtifactCoordinator.currentLeaseId(
                                                context = context,
                                                song = song,
                                                rootKeyOverride = sourceArtifactRootKey
                                            )
                                        val artifactCommitResult = runCatching {
                                            managedDownloadArtifactCoordinator.markCoreCommitted(
                                                context = context,
                                                song = song,
                                                storedAudio = promoted,
                                                expectedLeaseId = artifactLeaseId,
                                                rootKeyOverride = sourceArtifactRootKey
                                            )
                                        }.onFailure { error ->
                                            NPLogger.w(
                                                TAG,
                                                "迁移前 pending 提升后写入 artifact 状态失败，保留恢复入口: " +
                                                    "song=${song.name}, error=${error.message}",
                                                error
                                            )
                                        }.getOrNull()
                                        val artifactCommitted = artifactCommitResult?.isApplied == true
                                        if (artifactCommitted) {
                                            itemResolved = true
                                        } else {
                                            NPLogger.w(
                                                TAG,
                                                "迁移前 pending 提升后的 artifact 状态未确认，保留恢复入口: " +
                                                    "song=${song.name}, result=$artifactCommitResult"
                                            )
                                        }
                                    }
                                    return@withSongExecutionLock
                                }
                                val currentMetadata = readDownloadedMetadata(context, pendingAudio)
                                    ?: return@withSongExecutionLock
                                if (
                                    metadataPostProcessingEnabled &&
                                        !directoryMutationLeaseOwned &&
                                        currentMetadata.metadataEmbeddingState ==
                                            DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
                                ) {
                                    NPLogger.d(
                                        TAG,
                                        "跳过不支持内嵌标签的 pending 音频自动恢复: " +
                                            "song=${song.name}, file=${pendingAudio.name}"
                                    )
                                    return@withSongExecutionLock
                                }
                                if (isFinalizedDownloadedMetadata(currentMetadata)) {
                                    val publicationLease =
                                        prepareFinalizedPublicationArtifactLease(
                                            context = context,
                                            song = song,
                                            operationId = currentMetadata.operationId
                                        ) ?: return@withSongExecutionLock
                                    val publicationResult = publishFinalizedDownload(
                                        context = context,
                                        song = song,
                                        storedAudio = pendingAudio,
                                        sidecarReferences = null,
                                        expectedAttemptId = null,
                                        operationId = currentMetadata.operationId,
                                        expectedArtifactLeaseId = publicationLease.leaseId,
                                        allowMissingTask = true,
                                        admissionTicket = admissionTicket
                                    )
                                    when (publicationResult) {
                                        FinalizedDownloadPublicationResult.PUBLISHED -> {
                                            itemResolved = true
                                        }

                                        FinalizedDownloadPublicationResult.STALE -> {
                                            itemResolved = true
                                            NPLogger.d(
                                                TAG,
                                                "pending 音频最终发布已被新代次接管: " +
                                                    "song=${song.name}, file=${pendingAudio.name}"
                                            )
                                        }

                                        FinalizedDownloadPublicationResult.RECOVERY_REQUIRED -> {
                                            NPLogger.w(
                                                TAG,
                                                "pending 音频已有完成凭据但提升未确认，保留等待恢复: " +
                                                    "song=${song.name}, file=${pendingAudio.name}"
                                            )
                                        }
                                    }
                                    return@withSongExecutionLock
                                }
                                val artifactLeaseId = currentMetadata.operationId
                                    ?.let { operationId ->
                                        DownloadExecutionRoomStore.read(context, operationId)
                                            ?.artifactLeaseId
                                    }
                                    ?: managedDownloadArtifactCoordinator.currentLeaseId(context, song)
                                NPLogger.d(
                                    TAG,
                                    "从 pending 音频恢复元信息收尾: " +
                                        "song=${song.name}, file=${pendingAudio.name}"
                                )
                                finalizeCompletedDownload(
                                    context = context,
                                    song = song,
                                    operationId = currentMetadata.operationId,
                                    expectedArtifactLeaseId = artifactLeaseId,
                                    storedAudioHint = pendingAudio,
                                    allowMissingTask = true,
                                    directoryMutationLeaseOwned = directoryMutationLeaseOwned,
                                    directoryUri = directoryUri,
                                    admissionTicket = admissionTicket,
                                    admissionAlreadyHeld = true
                                )
                                // finalize 可能因为 Provider 暂不可用只保留 pending 凭据。
                                // 不能把“调用返回”误记成“物理文件已正式发布”，否则迁移
                                // 会提前放行，下一次扫描又会重新遇到同一个 pending
                                val resolvedAudio = ManagedDownloadStorage.findDownloadedAudio(
                                    context = context,
                                    song = song,
                                    forceRefresh = true
                                )
                                itemResolved = resolvedAudio != null &&
                                    !resolvedAudio.isPendingAudioWrite
                                if (!itemResolved) {
                                    NPLogger.w(
                                        TAG,
                                        "pending 收尾返回但正式文件仍未确认，保留恢复凭据: " +
                                            "song=${song.name}, file=${pendingAudio.name}"
                                    )
                                }
                            }
                        }
                        if (!mutationAdmitted) return@runCatching
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        NPLogger.w(
                            TAG,
                            "恢复 pending 音频失败，保留等待重试: " +
                                "file=${pendingAudio.name}, error=${error.message}"
                        )
                    }
                    if (result.isFailure || !itemResolved) {
                        failedAudioCount.incrementAndGet()
                    }
                }
            }
        }.awaitAll()
    }
    return PendingDownloadRecoverySummary(
        leaseAcquired = directoryMutationLeaseOwned,
        initialScanComplete = pendingScan.isComplete,
        discoveredAudioCount = pendingAudioWrites.size,
        attemptedAudioCount = attemptedAudioCount.get(),
        failedAudioCount = failedAudioCount.get()
    )
}

internal suspend fun GlobalDownloadManager.recoverUnfinalizedPublishedAudioFromRoot(
    context: Context,
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
) {
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(context, admissionTicket)
    ) {
        return
    }
    val metadataPostProcessingEnabled = isDownloadMetadataPostProcessingEnabled(context)
    val snapshot = runCatching {
        ManagedDownloadStorage.buildDownloadLibrarySnapshot(
            context = context,
            forceRefresh = true,
            includeMetadataLessAudioForLegacyUpgrade = true
        )
    }.getOrElse { error ->
        if (error is CancellationException) {
            throw error
        }
        NPLogger.w(TAG, "读取待收尾下载音频失败，等待下次恢复: ${error.message}")
        return
    }
    val recoverableAudioEntries = (
        snapshot.audioEntries + snapshot.audioEntriesWithoutMetadata
        ).distinctBy(ManagedDownloadStorage.StoredEntry::reference)
    for ((index, audio) in recoverableAudioEntries.withIndex()) {
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(context, admissionTicket)
        ) {
            return
        }
        if (
            index > 0 &&
                index % STARTUP_ARTIFACT_RECOVERY_YIELD_BATCH_SIZE == 0
        ) {
            yield()
        }
        val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
            ?: readDownloadedMetadata(context, audio)
            ?: continue
        val song = buildSongFromDurableMetadata(audio, metadata) ?: continue
        runCatching {
            val mutationAdmitted = admitArtifactRecoveryMutation(
                context = context,
                admissionTicket = admissionTicket
            ) {
                withSongExecutionLock(song.stableKey()) {
                val currentMetadata = readDownloadedMetadata(context, audio)
                    ?: return@withSongExecutionLock
                if (!DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                        context, currentMetadata.operationId
                    )
                ) return@withSongExecutionLock
                if (
                    metadataPostProcessingEnabled &&
                        currentMetadata.metadataEmbeddingState ==
                            DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
                ) {
                    NPLogger.d(
                        TAG,
                        "跳过不支持内嵌标签的已发布音频自动恢复: " +
                            "song=${song.name}, file=${audio.name}"
                    )
                    return@withSongExecutionLock
                }
                val operationState = currentMetadata.operationId
                    ?.let { operationId -> DownloadExecutionRoomStore.state(context, operationId) }
                val artifactState = managedDownloadArtifactCoordinator.currentState(context, song)
                    ?.name
                    ?: currentMetadata.artifactState
                val metadataFinalized = isFinalizedDownloadedMetadata(currentMetadata)
                val recoveryLeaseOwned = metadataFinalized &&
                    artifactState == "DOWNLOADING" &&
                    managedDownloadArtifactCoordinator.currentLeaseIdsAnyRoot(
                        context = context,
                        song = song
                    ).contains(
                        finalizedPublicationRecoveryLeaseOwnerId(
                            stableKey = song.stableKey(),
                            operationId = currentMetadata.operationId
                        )
                    )
                if (
                    metadataFinalized &&
                        requiresFinalizedPublicationRecovery(
                            metadataFinalized = true,
                            operationState = operationState,
                            artifactState = artifactState,
                            recoveryLeaseOwned = recoveryLeaseOwned
                        )
                ) {
                    val publicationLease = prepareFinalizedPublicationArtifactLease(
                        context = context,
                        song = song,
                        operationId = currentMetadata.operationId
                    ) ?: return@withSongExecutionLock
                    val publicationResult = publishFinalizedDownload(
                        context = context,
                        song = song,
                        storedAudio = audio,
                        sidecarReferences = null,
                        expectedAttemptId = null,
                        operationId = currentMetadata.operationId,
                        expectedArtifactLeaseId = publicationLease.leaseId,
                        allowMissingTask = true,
                        admissionTicket = admissionTicket
                    )
                    if (publicationResult.requiresRecovery) {
                        NPLogger.w(
                            TAG,
                            "恢复完成凭据后的发布失败，保留等待重试: " +
                                "song=${song.name}, file=${audio.name}"
                        )
                    }
                    return@withSongExecutionLock
                }
                if (!isUnfinalizedDownloadedMetadata(currentMetadata)) {
                    return@withSongExecutionLock
                }
                val artifactLeaseId = currentMetadata.operationId
                    ?.let { operationId ->
                        DownloadExecutionRoomStore.read(context, operationId)?.artifactLeaseId
                    }
                    ?: managedDownloadArtifactCoordinator.currentLeaseId(context, song)
                finalizeCompletedDownload(
                    context = context,
                    song = song,
                    operationId = currentMetadata.operationId,
                    expectedArtifactLeaseId = artifactLeaseId,
                    storedAudioHint = audio,
                    allowMissingTask = true,
                    admissionTicket = admissionTicket,
                    admissionAlreadyHeld = true
                )
                }
            }
            if (!mutationAdmitted) {
                return@runCatching
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            NPLogger.w(
                TAG,
                "恢复未最终化已发布音频失败，保留等待重试: " +
                    "song=${song.name}, file=${audio.name}, error=${error.message}"
            )
        }
    }
}

internal suspend fun GlobalDownloadManager.prepareFinalizedPublicationArtifactLease(
    context: Context,
    song: SongItem,
    operationId: String?
): ManagedDownloadArtifactPublicationLease? {
    val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
    val recoveryClaim = claimArtifactForRecovery(
        context = context,
        song = song,
        operationId = normalizedOperationId
    ) ?: return null
    val publicationLease = recoveryClaim.claim.finalizedPublicationLeaseOrNull()
    return publicationLease.also { lease ->
        if (lease == null) {
            NPLogger.d(
                TAG,
                "恢复最终发布遇到新的 artifact owner，保留等待接管者收口: " +
                    "song=${song.name}, operationId=$normalizedOperationId"
            )
        }
    }
}

internal fun GlobalDownloadManager.repairFinalizedDownloadedCoversFromRoot(
    context: Context,
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
) {
    val appContext = context.applicationContext
    if (!finalizedCoverRepairActive.compareAndSet(false, true)) {
        return
    }
    scope.launch {
        var directoryMutationLease: AutoCloseable? = null
        try {
            if (
                admissionTicket == null ||
                    !isDownloadAdmissionTicketCurrent(appContext, admissionTicket)
            ) {
                return@launch
            }
            if (appContext.currentDownloadNetworkTypeOrNull() != TrafficNetworkType.WIFI) {
                return@launch
            }
            directoryMutationLease =
                ManagedDownloadDirectoryMutationFence.acquireRecoveryLeaseOrNull(
                    appContext
                )
            if (directoryMutationLease == null) {
                NPLogger.d(
                    TAG,
                    "目录迁移占用封面恢复入口，保留封面修复凭据"
                )
                return@launch
            }
            if (!isDownloadAdmissionTicketCurrent(appContext, admissionTicket)) {
                NPLogger.d(
                    TAG,
                    "下载清空栅栏在封面恢复取租约后生效，保留封面修复凭据"
                )
                return@launch
            }
            val snapshot = runCatching {
                ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                    context = appContext,
                    forceRefresh = false
                )
            }.getOrElse { error ->
                NPLogger.w(TAG, "读取历史缺失封面失败，等待下次恢复: ${error.message}")
                return@launch
            }
            val candidates = snapshot.audioEntries.mapNotNull { audio ->
                val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
                    ?: readDownloadedMetadata(appContext, audio)
                    ?: return@mapNotNull null
                if (!isFinalizedDownloadedMetadata(metadata)) {
                    return@mapNotNull null
                }
                val song = buildSongFromDurableMetadata(audio, metadata)
                    ?: return@mapNotNull null
                if (AudioDownloadManager.buildCoverDownloadCandidateUrls(song).isEmpty()) {
                    return@mapNotNull null
                }
                song to audio
            }
            if (candidates.isEmpty()) {
                return@launch
            }
            val nextCandidateIndex = AtomicInteger(0)
            coroutineScope {
                List(
                    size = minOf(METADATA_POST_PROCESSING_PARALLELISM, candidates.size)
                ) {
                    async {
                        while (true) {
                            val candidate = candidates.getOrNull(nextCandidateIndex.getAndIncrement())
                                ?: return@async
                            if (
                                !isDownloadAdmissionTicketCurrent(
                                    appContext,
                                    admissionTicket
                                )
                            ) {
                                return@async
                            }
                            val (song, audio) = candidate
                            val mutationAdmitted = admitArtifactRecoveryMutation(
                                context = appContext,
                                admissionTicket = admissionTicket
                            ) {
                                withSongExecutionLock(song.stableKey()) {
                                    val currentMetadata =
                                        readDownloadedMetadata(appContext, audio)
                                            ?: return@withSongExecutionLock
                                    if (!isFinalizedDownloadedMetadata(currentMetadata)) {
                                        return@withSongExecutionLock
                                    }
                                    val beforeRepair =
                                        buildOptimisticDownloadedSong(song, audio)
                                    val repaired = repairDownloadedCoverIfMissing(
                                        context = appContext,
                                        song = song,
                                        downloadedSong = beforeRepair
                                    )
                                    if (repaired.coverPath != beforeRepair.coverPath) {
                                        publishOptimisticDownloadedSongs(
                                            appContext,
                                            listOf(repaired)
                                        )
                                    }
                                }
                            }
                            if (!mutationAdmitted) {
                                return@async
                            }
                        }
                    }
                }.awaitAll()
            }
            if (
                !isDownloadAdmissionTicketCurrent(appContext, admissionTicket)
            ) {
                return@launch
            }
            scheduleCatalogReconcile(appContext, forceRefresh = true)
        } finally {
            directoryMutationLease?.close()
            finalizedCoverRepairActive.set(false)
        }
    }
}

internal fun GlobalDownloadManager.observeStorageStartupRecovery(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
        ManagedDownloadStorage.startupRecoveryResults.collect { result ->
            if (!result.hasRecoveredEntries) {
                return@collect
            }
            if (result.failedCount > 0) {
                NPLogger.w(
                    TAG,
                    "后台下载启动清理存在未确认项，安排持久临时写入清理复查: " +
                        "failed=${result.failedCount}"
                )
                schedulePersistedTerminalTemporaryWriteCleanup(appContext)
            }
            NPLogger.d(
                TAG,
                "后台下载启动清理完成，安排目录对账: cleaned=${result.cleanedCount}, failed=${result.failedCount}"
            )
            scheduleCatalogReconcile(appContext, forceRefresh = true)
        }
    }
}

internal suspend fun GlobalDownloadManager.recoverPendingDownloadsForStartup(
    context: Context,
    admissionTicket: Long?
) {
    val appContext = context.applicationContext
    val capturedAdmissionTicket = admissionTicket ?: run {
        NPLogger.d(TAG, "清空期间跳过启动下载恢复")
        return
    }
    if (!isDownloadAdmissionTicketCurrent(appContext, capturedAdmissionTicket)) {
        NPLogger.d(TAG, "启动下载恢复票据已失效，跳过旧恢复请求")
        return
    }
    withPendingDownloadRecoverySlot("startup") {
        if (!isDownloadAdmissionTicketCurrent(appContext, capturedAdmissionTicket)) {
            NPLogger.d(TAG, "跳过启动下载恢复: 清空栅栏仍在生效")
            return@withPendingDownloadRecoverySlot
        }
        promoteWaitingStorageMutationsForRecovery(
            context = appContext,
            admissionTicket = capturedAdmissionTicket
        )
        if (!hasPendingRecoveryCandidates(appContext)) {
            return@withPendingDownloadRecoverySlot
        }
        reconcilePendingDownloadArtifacts(
            context = appContext,
            admissionTicket = capturedAdmissionTicket
        )
        if (!isDownloadAdmissionTicketCurrent(appContext, capturedAdmissionTicket)) {
            return@withPendingDownloadRecoverySlot
        }
        waitForActiveDownloadJobsToSettle()
        waitForQueuedTasksToAttachToBatch()
        if (hasBlockingActiveDownloadOperationsForRecovery()) {
            NPLogger.d(TAG, "延后启动下载恢复: 当前已有活动下载")
            return@withPendingDownloadRecoverySlot
        }
        val deferredSongKeys = deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(
            context = appContext,
            reason = "startup",
            admissionTicket = capturedAdmissionTicket
        )
        recoverPendingResumableDownloads(
            context = appContext,
            reason = "startup",
            excludedSongKeys = deferredSongKeys,
            admissionTicket = capturedAdmissionTicket
        )
        // startBatchDownload 已登记活动任务，只需让调度协程先运行一次
        yield()
    }
}

internal fun GlobalDownloadManager.loadPendingWorkingProgressSnapshotOnce(
    context: Context
): PendingWorkingProgressSnapshot {
    if (pendingWorkingProgressSnapshotLoaded.get()) {
        return pendingWorkingProgressSnapshot
    }
    return synchronized(pendingWorkingProgressSnapshotLock) {
        if (pendingWorkingProgressSnapshotLoaded.get()) {
            return@synchronized pendingWorkingProgressSnapshot
        }
        var loadedSuccessfully = true
        val loaded = try {
            ManagedDownloadStorage.listPendingResumableDownloads(context)
                .map { pending ->
                    val bytesWritten = runCatching {
                        if (pending.workingFile.isFile) {
                            pending.workingFile.length()
                        } else {
                            0L
                        }
                    }.getOrDefault(0L)
                    PendingWorkingProgressRecord(
                        operationId = pending.operationId,
                        stableKey = pending.song.stableKey(),
                        bytesWritten = bytesWritten
                    )
                }
                .let(::buildPendingWorkingProgressSnapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            loadedSuccessfully = false
            NPLogger.w(
                TAG,
                "读取工作文件进度快照失败，回退到 Room 检查点: ${error.message}",
                error
            )
            PendingWorkingProgressSnapshot.Empty
        }
        if (loadedSuccessfully) {
            pendingWorkingProgressSnapshot = loaded
            pendingWorkingProgressSnapshotLoaded.set(true)
        } else {
            // SAF 或 staging 短暂不可见时不能把空快照永久标记为已加载
            // 后续宿主恢复会再次尝试，避免重启后进度卡在 Room 的 0
            NPLogger.d(TAG, "工作文件快照未完成，保留未加载状态等待重试")
        }
        loaded
    }
}

internal fun normalizedPostCoreRecoveryOperationIds(
    operationIds: Collection<String>
): Set<String> {
    return operationIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toCollection(linkedSetOf())
}

/** 修正旧版本把 core 已落盘误记为批次完成的持久快照 */
internal suspend fun GlobalDownloadManager.repairPersistedPostCoreBatchCompletions(
    context: Context,
    admissionTicket: Long?
) {
    val capturedAdmissionTicket = admissionTicket ?: return
    if (!isDownloadAdmissionTicketCurrent(context, capturedAdmissionTicket)) return
    val postCoreOperationIds = try {
        normalizedPostCoreRecoveryOperationIds(
            DownloadExecutionRoomStore.listByStatesAnyLibrary(
                context = context,
                states = POST_CORE_DOWNLOAD_OPERATION_STATES
            ).map { entry -> entry.request.operationId }
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(TAG, "读取历史批次误计数候选失败，保留原快照: ${error.message}", error)
        return
    }
    if (postCoreOperationIds.isEmpty()) return
    PostCoreDownloadRecoveryWorker.cancelLegacyPerOperationWork(
        context = context,
        operationIds = postCoreOperationIds
    )
    if (!isDownloadAdmissionTicketCurrent(context, capturedAdmissionTicket)) return
    val repairedCount = try {
        DownloadExecutionRoomStore.repairPrematurePostCoreBatchCompletions(
            context = context,
            operationIds = postCoreOperationIds
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(TAG, "修复历史批次误计数失败，保留原完成数量: ${error.message}", error)
        return
    }
    if (repairedCount > 0) {
        NPLogger.i(TAG, "已撤销历史批次中过早写入的完成标记: count=$repairedCount")
    }
}

internal suspend fun GlobalDownloadManager.restorePersistedBatchDownloadPresentations(context: Context) {
    val snapshots = try {
        DownloadExecutionRoomStore.readOpenBatchSnapshots(context)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "启动恢复持久下载批次失败，保留 operation 恢复路径: ${error.message}",
            error
        )
        return
    }
    val networkIsWifi = context.applicationContext.currentDownloadNetworkTypeOrNull() ==
        TrafficNetworkType.WIFI
    if (networkIsWifi) {
        runCatching {
            val networkGeneration = AudioDownloadManager.currentDownloadNetworkGeneration()
            DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(
                context = context.applicationContext,
                networkGeneration = networkGeneration
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "启动时清除已恢复 WIFI 的批次网络等待状态失败: ${error.message}",
                error
            )
        }
    }
    val consistentSnapshots = snapshots.filter(
        DownloadExecutionRoomStore.DownloadBatchRecoverySnapshot::isConsistent
    )
    if (consistentSnapshots.isEmpty()) {
        if (snapshots.isNotEmpty()) {
            NPLogger.w(TAG, "启动批次快照不完整，跳过展示恢复并保留 operation 调度")
        }
        return
    }
    val recovered = linkedMapOf<Long, BatchDownloadPresentationState>()
    consistentSnapshots.forEachIndexed { index, snapshot ->
        val presentationId = RECOVERED_DURABLE_BATCH_PRESENTATION_ID_START + index
        val memberAttemptIds = snapshot.members.associate { member ->
            member.stableKey to member.attemptId?.takeIf { it > 0L }
        }
        val memberOperationIds = snapshot.members.mapNotNull { member ->
            member.operationId?.takeIf(String::isNotBlank)
                ?.let { operationId -> member.stableKey to operationId }
        }.toMap()
        if (memberAttemptIds.isEmpty()) return@forEachIndexed
        if (!networkIsWifi && snapshot.batch.stateBits and DownloadBatchState.NETWORK_WAIT != 0) {
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(
                snapshot.members.map { member -> member.stableKey }
            )
        }
        val terminalStates = snapshot.members.mapNotNull { member ->
            val terminalState = when (member.terminalBits) {
                1 -> BatchDownloadTerminalState.COMPLETED
                2 -> BatchDownloadTerminalState.FAILED
                4 -> BatchDownloadTerminalState.CANCELLED
                else -> null
            }
            terminalState?.let { state -> member.stableKey to state }
        }.toMap()
        val maximumObservedFractions = snapshot.members.mapNotNull { member ->
            member.maxFractionMilli.coerceIn(0, 1_000)
                .takeIf { fraction -> fraction > 0 }
                ?.let { fraction -> member.stableKey to fraction / 1_000f }
        }.toMap()
        val initiallyCompletedSongKeys = snapshot.members
            .filter { member -> member.initiallyCompleted }
            .mapTo(linkedSetOf()) { member -> member.stableKey }
        durableBatchIdentityByPresentationId[presentationId] =
            DownloadExecutionRoomStore.DownloadBatchIdentity(
                batchId = snapshot.batch.batchId,
                generation = snapshot.batch.generation
            )
        recovered[presentationId] = BatchDownloadPresentationState(
            id = presentationId,
            memberAttemptIds = memberAttemptIds,
            memberOperationIds = memberOperationIds,
            terminalStates = terminalStates,
            maximumObservedFractions = maximumObservedFractions,
            initiallyCompletedSongKeys = initiallyCompletedSongKeys,
            batchId = snapshot.batch.batchId,
            batchGeneration = snapshot.batch.generation
        )
    }
    if (recovered.isNotEmpty()) {
        batchDownloadPresentationsMutable.update { presentations ->
            presentations + recovered
        }
        NPLogger.d(
            TAG,
            "启动恢复持久下载批次: batches=${recovered.size}, " +
                "members=${recovered.values.sumOf { presentation -> presentation.memberAttemptIds.size }}"
        )
    }
}

internal suspend fun GlobalDownloadManager.restorePersistedDownloadProgress(
    context: Context,
    admissionTicket: Long?
) {
    try {
        val capturedAdmissionTicket = admissionTicket ?: run {
            NPLogger.d(TAG, "清空期间跳过启动下载进度回填")
            return
        }
        if (!isDownloadAdmissionTicketCurrent(context, capturedAdmissionTicket)) {
            NPLogger.d(TAG, "启动下载进度回填票据已失效，跳过旧恢复请求")
            return
        }
        val entries = runCatching {
            DownloadExecutionRoomStore.listProgressEntriesAnyLibrary(context)
        }.getOrElse { error ->
            NPLogger.w(
                TAG,
                "启动恢复下载进度失败，保留 Room 状态等待后续恢复: ${error.message}",
                error
            )
            return
        }
        if (entries.isEmpty()) return
        // 逐条保留每首歌的最新记录，避免 groupBy 在大曲库恢复时
        // 同时创建整批候选和临时映射
        val latestBySongKey = linkedMapOf<
            String,
            DownloadExecutionRoomStore.ProgressEntry
        >()
        entries.forEach { entry ->
            val songKey = entry.request.song.stableKey()
            if (songKey.isBlank()) return@forEach
            val previous = latestBySongKey[songKey]
            if (
                previous == null ||
                    entry.updatedAtMs > previous.updatedAtMs ||
                    (entry.updatedAtMs == previous.updatedAtMs &&
                        entry.request.operationId > previous.request.operationId)
            ) {
                latestBySongKey[songKey] = entry
            }
        }
        val latestEntries = latestBySongKey.values.sortedWith(
            compareBy<DownloadExecutionRoomStore.ProgressEntry> { it.queueOrder }
                .thenBy { it.request.operationId }
        )
        if (latestEntries.isEmpty()) return
        val pendingWorkingProgressSnapshot =
            loadPendingWorkingProgressSnapshotOnce(context)
        val nowMs = System.currentTimeMillis()
        val presentationsByEntry = latestEntries.mapNotNull { entry ->
            recoveredDownloadTaskPresentation(
                operationState = entry.state,
                stopRequestedByUser = entry.stopRequestedByUser,
                batchStateBits = entry.batchStateBits,
                nextRetryAtMs = entry.nextRetryAtMs,
                lastErrorCode = entry.lastErrorCode,
                nowMs = nowMs
            )?.let { presentation -> entry to presentation }
        }.toMap()
        val restorableEntries = presentationsByEntry.keys.toList()
        val durableAttemptIds = restorableEntries.mapNotNull { entry ->
            entry.request.attemptId?.takeIf { it > 0L }?.let { attemptId ->
                entry.request.song.stableKey() to attemptId
            }
        }.toMap()
        var restoredCount = 0
        var blockedByDurableClear = false
        val admitted = downloadAdmissionGate.admit(capturedAdmissionTicket) admission@{
            if (isDownloadClearFenceActive(context)) {
                blockedByDurableClear = true
                return@admission
            }
            // 按队号一次恢复混合状态，不能按状态分组重新排列卡片
            val effectiveAttemptIds = taskStore.ensureDownloadTasks(
                songs = restorableEntries.map { it.request.song },
                durableAttemptIds = durableAttemptIds,
                statusesBySongKey = restorableEntries.associate {
                    it.request.song.stableKey() to presentationsByEntry.getValue(it).status
                }
            )
            restorableEntries.forEach { entry ->
                if (entry.request.attemptId == null) {
                    val attemptId = effectiveAttemptIds[entry.request.song.stableKey()]
                    if (attemptId != null) {
                        runCatching {
                            DownloadExecutionRoomStore.ensureAttemptId(
                                context = context,
                                operationId = entry.request.operationId,
                                stableKey = entry.request.song.stableKey(),
                                attemptId = attemptId
                            )
                        }.onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "补写旧下载 operation attemptId 失败，保留本次卡片恢复: " +
                                    "operationId=${entry.request.operationId}, " +
                                    "error=${error.message}"
                            )
                        }
                    }
                }
            }
            val restoredProgresses = restorableEntries.mapNotNull { entry ->
                val request = entry.request
                val songKey = request.song.stableKey()
                val attemptId = request.attemptId?.takeIf { it > 0L }
                    ?: effectiveAttemptIds[songKey]
                    ?: return@mapNotNull null
                val restoredProgress = resolveRecoveredDownloadProgress(
                    workingFileBytes = pendingWorkingProgressSnapshot.workingFileBytes(
                        operationId = request.operationId,
                        stableKey = songKey
                    ),
                    checkpointTotalBytes = entry.totalBytes,
                    checkpointBytesWritten = entry.bytesWritten
                )
                val presentation = presentationsByEntry.getValue(entry)
                AudioDownloadManager.DownloadProgress(
                    songKey = songKey,
                    songId = request.song.id,
                    fileName = request.song.name,
                    bytesRead = restoredProgress?.bytesRead ?: 0L,
                    totalBytes = restoredProgress?.totalBytes ?: 0L,
                    speedBytesPerSec = 0L,
                    stage = presentation.stage,
                    attemptId = attemptId,
                    operationId = request.operationId
                )
            }
            restoredCount = taskStore.restoreProgressBatch(restoredProgresses)
            val recoveredMemberAttemptIds = restorableEntries.filterNot {
                presentationsByEntry.getValue(it).status == DownloadStatus.FAILED
            }.associate { entry ->
                val songKey = entry.request.song.stableKey()
                songKey to (
                    entry.request.attemptId?.takeIf { attemptId -> attemptId > 0L }
                        ?: effectiveAttemptIds[songKey]
                    )
            }
            val recoveredMaximumObservedFractions = restoredProgresses
                .associate { progress ->
                    progress.songKey to downloadProgressFraction(progress)
                }
                .filterValues { fraction -> fraction > 0f }
            batchDownloadPresentationsMutable.update { presentations ->
                if (recoveredMemberAttemptIds.isEmpty()) {
                    presentations - RECOVERED_BATCH_DOWNLOAD_PRESENTATION_ID
                } else {
                    var matchedDurablePresentation = false
                    val updatedPresentations = presentations.mapValues { (_, presentation) ->
                        val matchingKeys = recoveredMemberAttemptIds.keys
                            .filter { songKey -> songKey in presentation.memberAttemptIds }
                        if (matchingKeys.isEmpty()) {
                            presentation
                        } else {
                            matchedDurablePresentation = true
                            presentation.copy(
                                memberAttemptIds = presentation.memberAttemptIds.mapValues { (songKey, currentAttemptId) ->
                                    recoveredMemberAttemptIds[songKey] ?: currentAttemptId
                                },
                                maximumObservedFractions = presentation.maximumObservedFractions
                                    .toMutableMap()
                                    .apply {
                                        matchingKeys.forEach { songKey ->
                                            val incoming = recoveredMaximumObservedFractions[songKey]
                                                ?: return@forEach
                                            this[songKey] = maxOf(this[songKey] ?: 0f, incoming)
                                        }
                                    }
                            )
                        }
                    }
                    if (matchedDurablePresentation) {
                        updatedPresentations - RECOVERED_BATCH_DOWNLOAD_PRESENTATION_ID
                    } else {
                        updatedPresentations + (
                            RECOVERED_BATCH_DOWNLOAD_PRESENTATION_ID to
                                BatchDownloadPresentationState(
                                    id = RECOVERED_BATCH_DOWNLOAD_PRESENTATION_ID,
                                    memberAttemptIds = recoveredMemberAttemptIds,
                                    maximumObservedFractions = recoveredMaximumObservedFractions
                                )
                        )
                    }
                }
            }
        }
        if (!admitted || blockedByDurableClear) {
            NPLogger.i(
                TAG,
                "下载清空已使启动进度快照过期，跳过任务卡片回填: " +
                    "operations=${latestEntries.size}"
            )
            return
        }
        NPLogger.d(
            TAG,
            "启动恢复下载进度任务卡片: operations=${latestEntries.size}, " +
                "restored=$restoredCount, explicitResume=${latestEntries.size - restorableEntries.size}, " +
                "withRoomBytes=${restorableEntries.count { it.bytesWritten > 0L }}, " +
                "withWorkingBytes=${restorableEntries.count { entry ->
                    pendingWorkingProgressSnapshot.workingFileBytes(
                        operationId = entry.request.operationId,
                        stableKey = entry.request.song.stableKey()
                    ) > 0L
                }}"
        )
    } finally {
        completeStartupProgressRestoreReady()
    }
}

internal suspend fun GlobalDownloadManager.reconcilePendingDownloadArtifacts(
    context: Context,
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
) {
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(context, admissionTicket)
    ) {
        return
    }
    val durableSongs = try {
        DownloadExecutionRoomStore.listByStatesAnyLibrary(
            context = context,
            states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
            excludeUserStoppedOperations = true
        ).map { entry -> entry.request.song }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "读取持久下载 artifact 对账候选失败: ${error.message}",
            error
        )
        emptyList()
    }
    val songs = buildList {
        addAll(
            ManagedDownloadStorage.listPendingQueuedDownloads(context)
                .map(ManagedDownloadStorage.PendingDownloadQueueEntry::song)
        )
        addAll(
            ManagedDownloadStorage.listPendingResumableDownloads(context)
                .map(ManagedDownloadStorage.PendingResumableDownload::song)
        )
        addAll(durableSongs)
        addAll(currentWaitingNetworkTaskSongs())
    }.distinctBy(SongItem::stableKey)
    if (songs.isEmpty()) return
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(context, admissionTicket)
    ) {
        return
    }
    runCatching {
        admitArtifactRecoveryMutation(
            context = context,
            admissionTicket = admissionTicket
        ) {
            managedDownloadArtifactCoordinator.reconcilePendingStorage(
                context = context,
                songs = songs
            )
        }
    }.onFailure { error ->
        NPLogger.w(TAG, "启动恢复前对账 pending artifact 失败: ${error.message}")
    }
}

internal suspend fun GlobalDownloadManager.recoverPendingResumableDownloads(
    context: Context,
    reason: String,
    excludedSongKeys: Set<String> = emptySet(),
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
): Boolean {
    val capturedAdmissionTicket = admissionTicket ?: run {
        NPLogger.d(TAG, "清空期间跳过未完成下载恢复: reason=$reason")
        return false
    }
    if (!isDownloadAdmissionTicketCurrent(context, capturedAdmissionTicket)) {
        NPLogger.d(TAG, "跳过未完成下载恢复: 清空栅栏仍在生效, reason=$reason")
        return false
    }
    if (ManagedDownloadDirectoryMutationFence.isActive(context)) {
        NPLogger.d(TAG, "跳过未完成下载恢复: 目录迁移栅栏仍在生效, reason=$reason")
        return false
    }
    return pendingDownloadRecoveryMutex.withLock {
        recoverPendingResumableDownloadsLocked(
            context = context,
            reason = reason,
            excludedSongKeys = excludedSongKeys,
            admissionTicket = capturedAdmissionTicket
        )
    }
}

internal suspend fun GlobalDownloadManager.recoverPendingResumableDownloadsLocked(
    context: Context,
    reason: String,
    excludedSongKeys: Set<String>,
    admissionTicket: Long
): Boolean {
    return try {
        val rehomeAdmitted = admitDownloadMutation(context, admissionTicket) {
            DownloadExecutionRoomStore.rehomeActiveOperationsToCurrentLibrary(context)
        }
        if (!rehomeAdmitted) {
            NPLogger.d(TAG, "清空使未完成下载恢复重绑定过期: reason=$reason")
            return false
        }
        promoteWaitingStorageMutationsForRecovery(context, admissionTicket)
        val recoveryPlan = resolvePendingDownloadRecoveryPlan(context)
        if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) {
            NPLogger.d(TAG, "清空使未完成下载恢复计划过期: reason=$reason")
            return false
        }
        val obsoleteTasksRemoved = admitDownloadMutation(context, admissionTicket) {
            removeObsoleteWaitingNetworkTasks(recoveryPlan.recoveryCandidateKeys)
        }
        if (!obsoleteTasksRemoved) {
            return false
        }
        if (recoveryPlan.recoveryCandidates.isEmpty()) {
            if (recoveryPlan.pendingQueuedDownloads.isEmpty() && recoveryPlan.pendingDownloads.isEmpty()) {
                val cancelledPurged = admitDownloadMutation(context, admissionTicket) {
                    DownloadExecutionRoomStore.purgeAllCancelled(context)
                }
                if (!cancelledPurged) {
                    return false
                }
            }
            return false
        }

        if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) {
            return false
        }
        var directSettlementResult = RecoveryDirectSettlementResult(
            settledSongKeys = emptySet(),
            settledOperationIds = emptySet(),
            failedSongKeys = emptySet()
        )
        val settledEntriesPurged = admitDownloadMutation(context, admissionTicket) {
            directSettlementResult = settlePendingDownloadRecoveryDirectHits(
                context = context,
                settlements = recoveryPlan.directSettlements
            )
            recoveryPlan.workingFilesToDelete.forEach(
                ManagedDownloadStorage::deleteWorkingDownloadArtifacts
            )
            purgeSettledRecoveryEntries(
                context,
                recoveryPlan.settledOperationIds + directSettlementResult.settledOperationIds,
                recoveryPlan.settledSongKeys + directSettlementResult.settledSongKeys
            )
        }
        if (!settledEntriesPurged) {
            return false
        }

        val resumableSongs = recoveryPlan.resumableSongs.filterNot { song ->
            song.stableKey() in excludedSongKeys ||
                song.stableKey() in directSettlementResult.settledSongKeys
        }
        if (resumableSongs.isEmpty()) {
            return false
        }

        val antiJoinedResumableSongs =
            (
                managedDownloadArtifactCoordinator.filterNotFinalized(
                    context = context,
                    songs = resumableSongs
                ) + resumableSongs.filter { song ->
                    song.stableKey() in directSettlementResult.failedSongKeys
                }
            ).distinctBy(SongItem::stableKey)
        val finalizedRecoveryKeys = resumableSongs
            .asSequence()
            .map(SongItem::stableKey)
            .filterNot { key -> key in directSettlementResult.failedSongKeys }
            .filterNot { key -> antiJoinedResumableSongs.any { it.stableKey() == key } }
            .toSet()
        if (finalizedRecoveryKeys.isNotEmpty()) {
            if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) {
                return false
            }
            val finalizedEntriesPurged = admitDownloadMutation(context, admissionTicket) {
                purgeSettledRecoveryEntries(
                    context,
                    recoveryOperationIdsForKeys(
                        candidates = recoveryPlan.recoveryCandidates,
                        songKeys = finalizedRecoveryKeys
                    ),
                    finalizedRecoveryKeys
                )
            }
            if (!finalizedEntriesPurged) {
                return false
            }
        }

        NPLogger.d(
            TAG,
            "检测到未完成下载，准备自动恢复: reason=$reason, " +
                "count=${antiJoinedResumableSongs.size}, " +
                "deferred=${excludedSongKeys.size}, " +
                "antiJoinedFinalized=${finalizedRecoveryKeys.size}, " +
                "explicitResume=${recoveryPlan.explicitResumeKeys.size}, " +
                "queued=${recoveryPlan.pendingQueuedDownloads.size}, " +
                "partial=${recoveryPlan.pendingDownloads.size}"
        )
        if (antiJoinedResumableSongs.isEmpty()) {
            return false
        }
        if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) {
            NPLogger.d(TAG, "清空使未完成下载恢复请求过期: reason=$reason")
            return false
        }
        // Room operation 是持久队列的唯一调度入口。只有带物理 partial 文件的
        // 歌曲需要批量恢复以保留 staging，其余任务直接交给共享泵，避免重启时
        // 为几百个已经落盘的 operation 再创建一轮批量协程
        val antiJoinedKeys = antiJoinedResumableSongs
            .mapTo(linkedSetOf(), SongItem::stableKey)
        val partialRecoverySongs = recoveryPlan.recoveryCandidates
            .asSequence()
            .filter { candidate ->
                shouldRecoverDownloadCandidateWithBatch(
                    songKey = candidate.song.stableKey(),
                    antiJoinedKeys = antiJoinedKeys,
                    hasWorkingFile = candidate.workingFile != null
                )
            }
            .sortedBy(PendingDownloadRecoveryCandidate::order)
            .map(PendingDownloadRecoveryCandidate::song)
            .distinctBy(SongItem::stableKey)
            .toList()
        val partialRecoveryKeys = partialRecoverySongs.mapTo(
            linkedSetOf(),
            SongItem::stableKey
        )
        val queuedOnlySongs = antiJoinedResumableSongs.filterNot { song ->
            song.stableKey() in partialRecoveryKeys
        }
        val queuedOnlyKeys = queuedOnlySongs.mapTo(
            linkedSetOf(),
            SongItem::stableKey
        )
        val directSettlementKeys = directSettlementResult.settledSongKeys
        val inFlightRequests = recoveryPlan.durableInFlightRequests.filter { request ->
            request.song.stableKey() in queuedOnlyKeys &&
                request.song.stableKey() !in directSettlementKeys
        }
        val partialRecoveryStarted = if (partialRecoverySongs.isEmpty()) {
            false
        } else {
            startBatchDownload(
                context = context,
                songs = partialRecoverySongs,
                skipTrafficRiskPrompt = true,
                cleanupBeforeStart = false,
                deferForNetworkPolicy = true,
                userInitiated = false,
                requestedAdmissionTicket = admissionTicket,
                awaitAdmissionWhenUnavailable = false
            ) != null
        }
        recoverInFlightDownloadOperations(
            context = context,
            requests = inFlightRequests,
            admissionTicket = admissionTicket
        )
        val pumpNeeded = queuedOnlySongs.isNotEmpty() || inFlightRequests.isNotEmpty()
        val pumpScheduled = if (pumpNeeded) {
            wakeDownloadExecutionPump(
                context = context,
                reason = "persisted_recovery"
            )
        } else {
            false
        }
        if (partialRecoveryStarted || pumpScheduled || pumpNeeded) {
            NPLogger.i(
                TAG,
                "持久下载恢复已分流到共享泵: " +
                    "queued=${queuedOnlySongs.size}, " +
                    "partial=${partialRecoverySongs.size}, " +
                    "inFlight=${inFlightRequests.size}, pump=${pumpScheduled}"
            )
            return true
        }
        false
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        NPLogger.e(TAG, "自动恢复未完成下载失败: ${error.message}", error)
        false
    }
}

internal suspend fun GlobalDownloadManager.resolvePendingDownloadRecoveryPlan(
    context: Context
): PendingDownloadRecoveryPlan {
    val pendingQueuedDownloads = ManagedDownloadStorage.listPendingQueuedDownloads(context)
    val pendingDownloads = ManagedDownloadStorage.listPendingResumableDownloads(context)
    val durableOperationEntries = if (
        ManagedDownloadDirectoryMutationFence.isActive(context)
    ) {
        emptyList()
    } else try {
        // 这里只读已经绑定到当前目录的 operation，重绑定必须由上层准入区完成
        DownloadExecutionRoomStore.listByStates(
            context = context,
            states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
            excludeUserStoppedOperations = true
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "读取持久下载 operation 失败，保留文件队列等待重试: ${error.message}",
            error
        )
        emptyList()
    }
    val durableOperationEntryBySongKey = durableOperationEntries
        .groupBy { entry -> entry.request.song.stableKey() }
        .mapNotNull { (songKey, entries) ->
            entries.maxWithOrNull(
                compareBy<DownloadExecutionRoomStore.StateEntry> { it.updatedAtMs }
                    .thenBy { durableOperationRecoveryPriority(it.state) }
                    .thenBy { it.request.operationId }
            )?.let { entry -> songKey to entry }
        }
        .toMap()
    val durableOperationBySongKey = durableOperationEntryBySongKey
        .mapValues { (_, entry) -> entry.request }
    // Room 的 queue_order 是用户提交顺序的持久副本。恢复时保留它，
    // 不能按 UUID 排序，否则重启后会改变下载顺序
    val durableQueueDownloads = durableOperationEntryBySongKey.values
        .sortedWith(
            compareBy<DownloadExecutionRoomStore.StateEntry> { it.queueOrder }
                .thenBy { it.createdAtMs }
                .thenBy { it.request.operationId }
        )
        .mapIndexed { index, entry ->
            val request = entry.request
            ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = request.song.stableKey(),
                song = request.song,
                order = pendingQueuedDownloads.size + index,
                queuedAtMs = 0L,
                operationId = request.operationId,
                requiresWifiNetwork = request.requiresWifiNetwork
            )
        }
    val durableInFlightRequests = durableOperationEntryBySongKey.values
        .asSequence()
        .filter { entry -> entry.state in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES }
        .map { entry -> entry.request }
        .toList()
    val cancelledOperationEntries = DownloadExecutionRoomStore.listByStatesAnyLibrary(
        context = context,
        states = listOf("CANCEL_REQUESTED", "CANCELLED")
    )
    val cancelledDownloadKeys = cancelledOperationEntries
        .mapTo(linkedSetOf()) { entry -> entry.request.song.stableKey() }
    val cancelledOperationIds = cancelledOperationEntries
        .mapTo(linkedSetOf()) { entry -> entry.request.operationId }
    val externallyStoppedSongKeys = DownloadExecutionHosts.default
        .externallyStoppedSongKeys(context)
    val durableNetworkPoliciesBySongKey = durableOperationEntryBySongKey.values
        .sortedWith(
            compareBy<DownloadExecutionRoomStore.StateEntry> { it.updatedAtMs }
                .thenBy { durableOperationRecoveryPriority(it.state) }
                .thenBy { it.request.operationId }
        )
        .associate { entry ->
            entry.request.song.stableKey() to entry.request.requiresWifiNetwork
        }
    val recoveryCandidates = mergePendingDownloadRecoveryCandidates(
        queuedDownloads = pendingQueuedDownloads + durableQueueDownloads,
        resumableDownloads = pendingDownloads,
        cancelledKeys = cancelledDownloadKeys,
        cancelledOperationIds = cancelledOperationIds
    ).map { candidate ->
        val durableRequest = durableOperationBySongKey[candidate.song.stableKey()]
        candidate.copy(
            operationId = durableRequest?.operationId ?: candidate.operationId,
            requiresWifiNetwork = durableNetworkPoliciesBySongKey[
                candidate.song.stableKey()
            ] ?: candidate.requiresWifiNetwork
        )
    }
    // 固定每个候选在本次恢复计划中的 operation 身份，后续清理只能触碰这批身份
    // 不能因同一 stable key 的替代请求而误删新队列
    val resolvedRecoveryCandidates = recoveryCandidates.map { candidate ->
        if (candidate.operationId != null) {
            candidate
        } else {
            candidate.copy(
                operationId = DownloadExecutionHosts.default.operationIdForSong(
                    context,
                    candidate.song.stableKey()
                )
            )
        }
    }
    val recoveryCandidateKeys = resolvedRecoveryCandidates
        .mapTo(mutableSetOf()) { candidate -> candidate.song.stableKey() }
        .apply { addAll(externallyStoppedSongKeys) }
    val resumableSongs = mutableListOf<SongItem>()
    val settledSongKeys = mutableSetOf<String>()
    val settledOperationIds = mutableSetOf<String>()
    val workingFilesToDelete = mutableListOf<File>()
    val directSettlements = mutableListOf<PendingDownloadRecoveryDirectSettlement>()
    val explicitResumeKeys = mutableSetOf<String>()
    val batchCompletionCatalogIndex = loadBatchCompletionCatalogIndex(context)
    val fastCompletedSongKeys = findFastCompletedBatchSongKeys(
        context = context,
        songs = resolvedRecoveryCandidates.map(PendingDownloadRecoveryCandidate::song),
        catalogIndex = batchCompletionCatalogIndex
    )
    resolvedRecoveryCandidates.forEach { candidate ->
        val song = candidate.song
        val songKey = song.stableKey()
        val operationId = candidate.operationId
        val durableEntry = durableOperationEntryBySongKey[songKey]
            ?.takeIf { entry -> entry.request.operationId == operationId }
        val requiresExplicitResume = DownloadExecutionHosts.default
            .requiresExplicitResume(context, operationId)
        val alreadyPresent = shouldSkipDownload(context, song) ||
            songKey in fastCompletedSongKeys
        when {
            candidate.cancelled -> {
                candidate.workingFile?.let(workingFilesToDelete::add)
                settledSongKeys += songKey
                operationId?.let(settledOperationIds::add)
            }
            alreadyPresent && durableEntry?.state in
                DownloadExecutionRoomStore.DIRECT_CACHED_COMPLETION_SOURCE_STATES -> {
                directSettlements += PendingDownloadRecoveryDirectSettlement(
                    song = song,
                    operationId = checkNotNull(operationId),
                    attemptId = durableEntry?.request?.attemptId,
                    workingFile = candidate.workingFile
                )
                // CAS 失败时必须仍能回到共享泵/恢复入口，不能先删掉队列证据
                resumableSongs += song
            }
            alreadyPresent && durableEntry?.state in
                DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                    listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE) -> {
                // post-core 或目录等待状态必须走各自的恢复路径，不能用缓存命中
                // 捷径把 operation 从持久队列中抹掉
                resumableSongs += song
            }
            alreadyPresent -> {
                candidate.workingFile?.let(workingFilesToDelete::add)
                settledSongKeys += songKey
                operationId?.let(settledOperationIds::add)
            }
            songKey in externallyStoppedSongKeys || requiresExplicitResume -> {
                explicitResumeKeys += songKey
            }
            else -> {
                resumableSongs += song
            }
        }
    }
    return PendingDownloadRecoveryPlan(
        pendingQueuedDownloads = pendingQueuedDownloads,
        pendingDownloads = pendingDownloads,
        recoveryCandidates = resolvedRecoveryCandidates,
        recoveryCandidateKeys = recoveryCandidateKeys,
        resumableSongs = resumableSongs,
        directSettlements = directSettlements,
        settledSongKeys = settledSongKeys,
        settledOperationIds = settledOperationIds,
        workingFilesToDelete = workingFilesToDelete,
        explicitResumeKeys = explicitResumeKeys,
        durableInFlightRequests = durableInFlightRequests
    )
}
