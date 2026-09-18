package moe.ouom.neriplayer.core.download.manager.commit

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.scheduleStartupArtifactRecovery
import moe.ouom.neriplayer.core.download.manager.batch.isDownloadRequestGenerationCurrent
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCompletedTaskRemoval
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadArtifactRepairRequired
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadArtifactRetryable
import moe.ouom.neriplayer.core.download.manager.recovery.resolveCoreRecoveryAudioCandidate
import moe.ouom.neriplayer.core.download.manager.runtime.publishDownloadStage
import moe.ouom.neriplayer.core.download.manager.runtime.withSongExecutionLock
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.policy.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.policy.resolvePostCoreEnrichmentTaskStatus
import moe.ouom.neriplayer.core.download.policy.shouldAcceptOrphanCoreCommit
import moe.ouom.neriplayer.core.download.policy.shouldPublishCoreCommit
import moe.ouom.neriplayer.core.download.policy.shouldSchedulePostCoreEnrichmentRetry
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.worker.PostCoreDownloadRecoveryWorker
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.state.isPostCoreDownloadOperationState
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import java.util.Locale


internal suspend fun GlobalDownloadManager.completeCoreDownloadAndEnqueueEnrichment(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    existingMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    artifactLeaseId: String?,
    expectedAttemptId: Long?,
    operationId: String?,
    allowMissingTask: Boolean,
    directoryMutationLeaseOwned: Boolean,
    directoryUri: String?,
    admissionTicket: Long?,
    expeditedAssetEnrichment: Boolean = false
) {
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        NPLogger.d(
            TAG,
            "core 提交票据已失效，保留音频凭据等待新代次恢复: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    val songKey = song.stableKey()
    if (!ManagedDownloadStorage.hasReadableContent(context, storedAudio)) {
        updateTaskStatus(songKey, DownloadStatus.FAILED, expectedAttemptId = expectedAttemptId)
        markDownloadArtifactRetryable(
            context = context,
            song = song,
            leaseId = artifactLeaseId,
            errorCode = "AUDIO_CORE_NOT_READABLE"
        )
        return
    }

    val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
    val traceToken = normalizedOperationId?.let { id ->
        DownloadOperationTrace.begin(
            operationId = id,
            attemptId = expectedAttemptId
        )
    }
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.CORE_COMMIT_REQUESTED
    )
    val operationState = normalizedOperationId?.let { id ->
        DownloadExecutionRoomStore.state(context, id)
    }
    if (operationState == "CANCEL_REQUESTED" || operationState == "CANCELLED") {
        NPLogger.d(
            TAG,
            "忽略已请求取消的 core commit，并只清理 operation 所有的半成品: " +
                "song=${song.name}, operationId=$operationId"
        )
        rollbackCancelledDownload(
            context = context,
            song = song,
            storedAudio = storedAudio,
            operationId = operationId
        )
        return
    }
    if (
        normalizedOperationId != null &&
            operationState != null &&
            !isDurableCoreOperationState(operationState)
    ) {
        val commitAdmission = when (operationState) {
            "COMMITTING",
            WAITING_STORAGE_MUTATION_OPERATION_STATE,
            "RETRYABLE",
            "STOPPED" -> {
                val recovery = DownloadExecutionRoomStore.reconcileCoreCommitJournal(
                    context = context,
                    operationId = normalizedOperationId,
                    stableKey = songKey,
                    expectedAttemptId = expectedAttemptId,
                    coreMetadataDurable = false
                )
                when (recovery.outcome) {
                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.COMMITTED,
                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.PREPARED -> true

                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.MISSING,
                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.BLOCKED -> {
                        NPLogger.w(
                            TAG,
                            "operation journal core commit 恢复被阻止: " +
                                "song=${song.name}, operationId=$normalizedOperationId, " +
                                "state=${recovery.state}, " +
                                "stopRequested=${recovery.stopRequestedByUser}"
                        )
                        false
                    }
                }
            }

            else -> DownloadExecutionRoomStore.markCommitting(
                context,
                normalizedOperationId
            )
        }
        if (!commitAdmission) {
            NPLogger.w(
                TAG,
                "operation journal 未确认进入 COMMITTING，保留音频等待恢复: " +
                    "song=${song.name}, operationId=$normalizedOperationId, " +
                    "state=$operationState"
            )
            return
        }
    }

    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.CORE_COMMIT_GRANTED
    )
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.CORE_COMMIT_STARTED
    )
    val coreCommitResult = withContext(NonCancellable) {
        val coreMetadataReady = existingMetadata?.downloadFinalized == true ||
            isDurableCoreArtifactState(
                existingMetadata?.artifactState
                    ?.trim()
                    ?.uppercase()
            )
        val coreMetadataWritten = if (!coreMetadataReady) {
            persistDownloadedMetadata(
                context = context,
                audio = storedAudio,
                song = song,
                sidecarReferences = null,
                downloadFinalized = false,
                resolveExistingSidecars = false,
                artifactStateOverride = ManagedDownloadArtifactState.CORE_COMMITTED.name,
                operationId = operationId,
                existingMetadataHint = existingMetadata
            ).also { written ->
                if (!written) {
                    NPLogger.w(
                        TAG,
                        "core metadata 写入失败，保留音频等待恢复: ${song.name}"
                    )
                }
            }
        } else {
            true
        }
        if (!shouldPublishCoreCommit(coreMetadataReady, coreMetadataWritten)) {
            return@withContext false
        }
        val pendingMetadataDeleted = runCatching {
            ManagedDownloadStorage.deletePendingAudioMetadata(
                context = context,
                audioName = storedAudio.logicalName
            )
        }.getOrElse { error ->
            NPLogger.w(
                TAG,
                "core metadata 已写入但 pending 清理失败，保留可恢复残留: " +
                    "audio=${storedAudio.logicalName}, error=${error.message}",
                error
            )
            false
        }
        if (!pendingMetadataDeleted) {
            NPLogger.w(
                TAG,
                "core metadata 已写入但 pending 清理未确认，最终发布后将重试: " +
                    "audio=${storedAudio.logicalName}"
            )
        }
        val journalCommitted = normalizedOperationId?.let { id ->
            if (DownloadExecutionRoomStore.markCoreCommitted(context, id)) {
                true
            } else {
                val recovery = DownloadExecutionRoomStore.reconcileCoreCommitJournal(
                    context = context,
                    operationId = id,
                    stableKey = songKey,
                    expectedAttemptId = expectedAttemptId,
                    coreMetadataDurable = coreMetadataReady || coreMetadataWritten
                )
                when (recovery.outcome) {
                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.COMMITTED -> {
                        true
                    }

                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.PREPARED -> {
                        false
                    }

                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.MISSING -> {
                        val accepted = shouldAcceptOrphanCoreCommit(
                            allowMissingTask = allowMissingTask,
                            operationState = recovery.state,
                            coreMetadataDurable = coreMetadataReady || coreMetadataWritten
                        )
                        if (accepted) {
                            NPLogger.i(
                                TAG,
                                "operation journal 已清空但 core 凭据完整，按孤儿音频收尾: " +
                                    "song=${song.name}, operationId=$id"
                            )
                        }
                        accepted
                    }

                    DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome.BLOCKED -> {
                        NPLogger.w(
                            TAG,
                            "core metadata 已写入但 operation journal 被取消或阻止: " +
                                "song=${song.name}, operationId=$id, " +
                                "state=${recovery.state}, " +
                                "stopRequested=${recovery.stopRequestedByUser}"
                        )
                        false
                    }
                }
            }
        } ?: true
        if (!journalCommitted) {
            NPLogger.w(
                TAG,
                "core metadata 已写入但 operation journal 未确认 CORE_COMMITTED: " +
                    "song=${song.name}, operationId=$operationId"
            )
            return@withContext false
        }
        true
    }
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.CORE_COMMIT_FINISHED
    )
    if (!coreCommitResult) {
        updateTaskStatus(songKey, DownloadStatus.FAILED, expectedAttemptId = expectedAttemptId)
        markDownloadArtifactRepairRequired(
            context = context,
            song = song,
            leaseId = artifactLeaseId,
            errorCode = "CORE_METADATA_WRITE_FAILED"
        )
        return
    }
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.CORE_COMMITTED
    )
    var sourceArtifactLeaseLookupFailed = false
    val sourceArtifactRootKey = if (directoryMutationLeaseOwned) {
        try {
            ManagedDownloadStorage.snapshotRootKeyForOperation(
                context = context,
                directoryUri = directoryUri,
                useDefaultRootWhenDirectoryUriMissing = true
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "迁移源 artifact 根身份读取失败，保留 core 凭据等待恢复: " +
                    "song=${song.name}, directoryUri=$directoryUri, " +
                    "error=${error.message}",
                error
            )
            null
        }
    } else {
        null
    }
    val artifactLeaseForCommit = if (
        directoryMutationLeaseOwned &&
            artifactLeaseId == null &&
            sourceArtifactRootKey != null
    ) {
        try {
            managedDownloadArtifactCoordinator.currentLeaseId(
                context = context,
                song = song,
                rootKeyOverride = sourceArtifactRootKey
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sourceArtifactLeaseLookupFailed = true
            NPLogger.w(
                TAG,
                "迁移源 artifact lease 读取失败，保留 core 凭据等待恢复: " +
                    "song=${song.name}, error=${error.message}",
                error
            )
            null
        }
    } else {
        artifactLeaseId
    }

    // 迁移持有目录栅栏时先把 core 音频移出 .tmp，避免释放栅栏后才发现源文件
    // 仍然属于旧目录，导致迁移完成但播放引用指向失效的临时 URI
    val committedAudio = if (directoryMutationLeaseOwned && storedAudio.isPendingAudioWrite) {
        val promoted = runCatching {
            ManagedDownloadStorage.promoteCoreCommittedPendingAudio(
                context = context,
                audio = storedAudio,
                directoryUri = directoryUri,
                promotePendingMetadata = directoryMutationLeaseOwned,
                useDefaultRootWhenDirectoryUriMissing = directoryMutationLeaseOwned
            )
        }.getOrElse { error ->
            NPLogger.w(
                TAG,
                "迁移持有栅栏时提升 core pending 音频失败，保留凭据等待重试: " +
                    "song=${song.name}, file=${storedAudio.name}, " +
                    "error=${error.message}",
                error
            )
            null
        }
        promoted ?: run {
            NPLogger.w(
                TAG,
                "core pending 音频未确认提升，暂停本次迁移收尾: " +
                    "song=${song.name}, file=${storedAudio.name}"
            )
            // 迁移持有目录栅栏时同样要留下可调度的恢复入口，不能只记录日志后
            // 返回。否则 Provider 短暂失败会把完整 core 长期困在 pending 目录
            deferPendingCorePublication(
                context = context,
                song = song,
                audio = storedAudio,
                existingMetadata = existingMetadata,
                artifactLeaseId = artifactLeaseForCommit,
                expectedAttemptId = expectedAttemptId,
                operationId = operationId,
                admissionTicket = admissionTicket,
                reason = "CORE_PUBLICATION_PENDING_DURING_DIRECTORY_MUTATION"
            )
            return
        }
    } else {
        storedAudio
    }
    // core 已完成完整性校验后立即离开 .tmp。元信息增强仍可异步执行，
    // 但“下载完成”不再把 pending 音频留给一个可能被宿主取消的收尾任务
    if (isDownloadClearFenceActive(context, stableKey = songKey, operationId = operationId)) {
        // 清空栅栏拥有删除优先级，不能在删除事务期间把 pending 重新发布到正式目录
        NPLogger.d(
            TAG,
            "core 提交后发现清空栅栏，跳过正式发布并保留恢复凭据: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    val publishedAudio = corePublicationCoordinator.promoteBeforePublication(
        context = context,
        song = song,
        audio = committedAudio
    )
    if (publishedAudio.isPendingAudioWrite) {
        // Provider 短暂不可用时不能把 pending 当成最终文件，也不能继续发布完成态
        deferPendingCorePublication(
            context = context,
            song = song,
            audio = publishedAudio,
            existingMetadata = existingMetadata,
            artifactLeaseId = artifactLeaseForCommit,
            expectedAttemptId = expectedAttemptId,
            operationId = operationId,
            admissionTicket = admissionTicket,
            reason = "CORE_PUBLICATION_PENDING"
        )
        return
    }
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = songKey,
                operationId = operationId
            )
    ) {
        // core 已经完整落盘，不能因为旧代次失效而把最终文件继续留在 .tmp
        // 这里只跳过旧代次的 artifact、UI 和增强发布，启动恢复会接管剩余步骤
        NPLogger.d(
            TAG,
            "core 提交并提升后清空代次已失效，跳过旧代次发布和资产增强: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }

    val artifactCommitResult = if (
        directoryMutationLeaseOwned &&
            (sourceArtifactRootKey == null || sourceArtifactLeaseLookupFailed)
    ) {
        null
    } else {
        runCatching {
            managedDownloadArtifactCoordinator.markCoreCommitted(
                context = context,
                song = song,
                storedAudio = publishedAudio,
                expectedLeaseId = artifactLeaseForCommit,
                rootKeyOverride = sourceArtifactRootKey
            )
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            NPLogger.w(
                TAG,
                "写入 core committed artifact 状态失败，保留 core 凭据等待恢复: " +
                    "song=${song.name}, error=${error.message}",
                error
            )
        }.getOrNull()
    }
    val artifactCommitted = artifactCommitResult?.isApplied == true
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = songKey,
                operationId = operationId
            )
    ) {
        NPLogger.d(
            TAG,
            "core artifact 提交后清空代次已失效，跳过目录和任务发布: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    if (!artifactCommitted) {
        val recoveryOperationId = try {
            ensureCoreRecoveryOperation(
                context = context,
                song = song,
                operationId = operationId ?: existingMetadata?.operationId,
                artifactLeaseId = artifactLeaseForCommit,
                expectedAttemptId = expectedAttemptId
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "补写 core artifact 恢复 operation 失败，保留 metadata 等待下次扫描: " +
                    "song=${song.name}, error=${error.message}",
                error
            )
            operationId ?: existingMetadata?.operationId ?: "unknown"
        }
        NPLogger.w(
            TAG,
            "core committed artifact 未确认，保留 core 音频，跳过播放桥和目录发布，" +
                "保持待收尾并安排恢复: " +
                "song=${song.name}, operationId=$recoveryOperationId, " +
                "result=$artifactCommitResult"
        )
        settlePostCoreEnrichmentFailure(
            context = context,
            song = song,
            operationId = recoveryOperationId,
            expectedAttemptId = expectedAttemptId,
            errorCode = "CORE_ARTIFACT_COMMIT_FAILED",
            scheduleRetry = false,
            admissionTicket = admissionTicket,
            // core metadata、音频内容和 Room journal 已经提交；
            // artifact ledger 只是后续收尾，不能把已完成歌曲降级成失败
            coreAudioCommitted = true
        )
        artifactLeaseForCommit?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        scheduleStartupArtifactRecovery(context)
        return
    }

    // core 已通过传输校验，先保留内存播放桥；它不是可展示的已下载成品。
    // 目录、Room 预览和已完成任务只能在最终完整性校验通过后发布
    AudioDownloadManager.rememberCompletedAudioReference(
        song = song,
        storedAudio = publishedAudio
    )

    // 收尾中的 core 必须保持活动态。若进程退出，Room/ledger 会恢复这条任务，
    // 而不是把半成品留在“已下载”目录或批次完成计数中
    updateTaskStatus(
        songKey = songKey,
        status = DownloadStatus.DOWNLOADING,
        expectedAttemptId = expectedAttemptId,
        settleBatchPresentation = false,
        operationId = normalizedOperationId
    )
    publishDownloadStage(
        song = song,
        stage = AudioDownloadManager.DownloadStage.ASSETS_ENRICHING,
        operationId = normalizedOperationId,
        attemptId = expectedAttemptId,
        bytesRead = publishedAudio.sizeBytes,
        totalBytes = publishedAudio.sizeBytes
    )

    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = songKey,
                operationId = operationId
            )
    ) {
        artifactLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        NPLogger.d(
            TAG,
            "core 发布后清空代次已失效，跳过资产增强 operation 登记: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }

    val enrichmentOperationId = ensureCoreRecoveryOperation(
        context = context,
        song = song,
        operationId = operationId ?: existingMetadata?.operationId,
        artifactLeaseId = artifactLeaseId,
        expectedAttemptId = expectedAttemptId
    )

    fun isEnrichmentAdmissionCurrent(): Boolean {
        return admissionTicket == null ||
            isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = songKey,
                operationId = enrichmentOperationId
            )
    }

    fun releaseEnrichmentMemoryOwnership() {
        AudioDownloadManager.clearCoreCommittedOperation(enrichmentOperationId)
        normalizedOperationId
            ?.takeUnless { it == enrichmentOperationId }
            ?.let(AudioDownloadManager::clearCoreCommittedOperation)
    }

    fun abandonStaleEnrichmentRegistration(stage: String) {
        artifactLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        NPLogger.d(
            TAG,
            "core 发布后清空代次已失效，跳过资产增强: " +
                "song=${song.name}, operationId=$enrichmentOperationId, stage=$stage"
        )
    }

    if (!isEnrichmentAdmissionCurrent()) {
        abandonStaleEnrichmentRegistration(stage = "operation_prepared")
        return
    }

    val enrichmentStatePersisted = if (directoryMutationLeaseOwned) {
        true
    } else {
        DownloadExecutionRoomStore.updateState(
            context = context,
            operationId = enrichmentOperationId,
            state = "ASSETS_ENRICHING"
        )
    }
    if (!enrichmentStatePersisted) {
        if (!isEnrichmentAdmissionCurrent()) {
            abandonStaleEnrichmentRegistration(stage = "state_rejected_after_clear")
            return
        }
        NPLogger.w(
            TAG,
            "资产增强 operation 未确认 ASSETS_ENRICHING，保留 core 凭据等待恢复: " +
                "song=${song.name}, operationId=$enrichmentOperationId"
        )
    }
    if (
        !isEnrichmentAdmissionCurrent()
    ) {
        abandonStaleEnrichmentRegistration(stage = "state_persisted")
        return
    }

    // core 已提交后把引用所有权交给后台收尾。先确认持久状态和清空代次，
    // 避免清空已经回收 registry 后由旧回调重新登记僵尸 operation
    AudioDownloadManager.markCoreCommittedOperation(enrichmentOperationId)
    normalizedOperationId
        ?.takeUnless { it == enrichmentOperationId }
        ?.let(AudioDownloadManager::markCoreCommittedOperation)
    if (!isEnrichmentAdmissionCurrent()) {
        releaseEnrichmentMemoryOwnership()
        abandonStaleEnrichmentRegistration(stage = "memory_owner_registered")
        return
    }
    if (directoryMutationLeaseOwned) {
        // 目录迁移期间不启动会等待同一栅栏的增强协程，释放栅栏后由统一恢复入口
        // 扫描 CORE_COMMITTED 音频并继续处理，避免迁移前置恢复自锁
        NPLogger.d(
            TAG,
            "core 音频已提交，延后资产增强到迁移栅栏释放后: " +
                "song=${song.name}, operationId=$enrichmentOperationId"
        )
        PostCoreDownloadRecoveryWorker.schedule(context)
        return
    }
    try {
        val enrichmentJob = assetEnrichmentCoordinator.tryEnqueue(
            operationId = enrichmentOperationId,
            attemptId = expectedAttemptId,
            allowSingleOverflow = expeditedAssetEnrichment,
            traceToken = traceToken,
            block = {
                val admitted = admissionTicket?.let { ticket ->
                    admitDownloadMutation(
                        context = context,
                        admissionTicket = ticket,
                        stableKey = songKey,
                        operationId = enrichmentOperationId
                    ) {
                        // 启动恢复和 core 回调可能同时为同一首歌排入增强；
                        // 串行化目录提升、metadata 和 artifact 收尾，避免后一条
                        // 协程用旧 pending 引用覆盖前一条已完成结果
                        withSongExecutionLock(songKey) {
                            enrichCoreCommittedDownload(
                                context = context,
                                song = song,
                                storedAudio = publishedAudio,
                                existingMetadataHint = existingMetadata,
                                operationId = enrichmentOperationId,
                                artifactLeaseId = artifactLeaseId,
                                expectedAttemptId = expectedAttemptId,
                                traceToken = traceToken,
                                allowMissingTask = allowMissingTask,
                                directoryMutationLeaseOwned = false,
                                admissionTicket = ticket
                            )
                        }
                    }
                } ?: false
                if (!admitted) {
                    NPLogger.d(
                        TAG,
                        "下载清空代次已失效，跳过资产增强: " +
                            "song=${song.name}, operationId=$enrichmentOperationId"
                    )
                }
            },
            onTimeout = { error ->
                val admitted = admissionTicket?.let { ticket ->
                    admitDownloadMutation(
                        context = context,
                        admissionTicket = ticket,
                        stableKey = songKey,
                        operationId = enrichmentOperationId
                    ) {
                        val timeoutCommitLease =
                            ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
                                context = context,
                                operationId = enrichmentOperationId
                            ) ?: throw DownloadStorageMutationDeferredException(
                                enrichmentOperationId
                            )
                        try {
                            withContext(NonCancellable) {
                                NPLogger.w(
                                    TAG,
                                    "下载资产补齐超时，保留 core audio: song=${song.name}, " +
                                        "operationId=$enrichmentOperationId, " +
                                        "timeout=${error.javaClass.simpleName}"
                                )
                                runCatching {
                                    persistDownloadedMetadata(
                                        context = context,
                                        audio = publishedAudio,
                                        song = song,
                                        existingMetadataHint = existingMetadata,
                                        sidecarReferences =
                                            AudioDownloadManager.DownloadedSidecarReferences(),
                                        downloadFinalized = false,
                                        resolveExistingSidecars = false,
                                        operationId = enrichmentOperationId
                                    )
                                }
                                runCatching {
                                    managedDownloadArtifactCoordinator.markDegradedComplete(
                                        context = context,
                                        song = song,
                                        expectedLeaseId = artifactLeaseId,
                                        errorCode = "ASSET_ENRICHMENT_TIMEOUT",
                                        retainLease = true
                                    )
                                }
                                val degradedStatePersisted = runCatching {
                                    DownloadExecutionRoomStore.updateState(
                                        context = context,
                                        operationId = enrichmentOperationId,
                                        state = "DEGRADED_COMPLETE",
                                        errorCode = "ASSET_ENRICHMENT_TIMEOUT"
                                    )
                                }.getOrElse { stateError ->
                                    NPLogger.w(
                                        TAG,
                                        "超时后的 operation 降级状态写入失败: " +
                                            "song=${song.name}, " +
                                            "operationId=$enrichmentOperationId, " +
                                            "error=${stateError.message}",
                                        stateError
                                    )
                                    false
                                }
                                if (!degradedStatePersisted) {
                                    NPLogger.w(
                                        TAG,
                                        "超时后的 operation 未确认 DEGRADED_COMPLETE: " +
                                            "song=${song.name}, " +
                                            "operationId=$enrichmentOperationId"
                                    )
                                }
                                settlePostCoreEnrichmentFailure(
                                    context = context,
                                    song = song,
                                    operationId = enrichmentOperationId,
                                    expectedAttemptId = expectedAttemptId,
                                    errorCode = "ASSET_ENRICHMENT_TIMEOUT",
                                    error = error,
                                    admissionTicket = ticket
                                )
                                scheduleCatalogReconcile(context, forceRefresh = true)
                            }
                        } finally {
                            timeoutCommitLease.close()
                        }
                    }
                } ?: false
                if (!admitted) {
                    NPLogger.d(
                        TAG,
                        "下载清空代次已失效，跳过资产增强超时收尾: " +
                            "song=${song.name}, operationId=$enrichmentOperationId"
                    )
                }
            },
            onCompletion = {
                artifactLeaseId?.let { leaseId ->
                    managedDownloadArtifactLeases.remove(songKey, leaseId)
                }
                PostCoreDownloadRecoveryWorker.schedule(context)
            }
        )
        if (enrichmentJob == null) {
            releaseEnrichmentMemoryOwnership()
            artifactLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases.remove(songKey, leaseId)
            }
            updateTaskStatus(
                songKey = songKey,
                status = DownloadStatus.QUEUED,
                expectedAttemptId = expectedAttemptId,
                settleBatchPresentation = false,
                operationId = enrichmentOperationId
            )
            publishDownloadStage(
                song = song,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST,
                operationId = enrichmentOperationId,
                attemptId = expectedAttemptId,
                bytesRead = publishedAudio.sizeBytes,
                totalBytes = publishedAudio.sizeBytes
            )
            PostCoreDownloadRecoveryWorker.schedule(context)
            NPLogger.d(
                TAG,
                "资产增强活动位已满，保留持久凭据等待共享 Worker: " +
                    "song=${song.name}, operationId=$enrichmentOperationId"
            )
            return
        }
        PostCoreDownloadRecoveryWorker.schedule(context)
    } catch (error: Throwable) {
        artifactLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        throw error
    }
}

internal suspend fun GlobalDownloadManager.recoverCorePublicationAfterExecutionCancellation(
    context: Context,
    song: SongItem,
    operationId: String?,
    expectedAttemptId: Long?,
    requestGeneration: Long,
    admissionTicket: Long?,
    artifactLeaseId: String?
): Boolean {
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return false
    val appContext = context.applicationContext
    return withContext(NonCancellable) {
        val operationState = runCatching {
            DownloadExecutionRoomStore.state(appContext, normalizedOperationId)
        }.getOrNull()
        val normalizedOperationState = operationState
            ?.trim()
            ?.uppercase(Locale.ROOT)
        val knownCoreCommitted = AudioDownloadManager.isCoreCommittedOperation(
            normalizedOperationId
        ) || isPostCoreDownloadOperationState(normalizedOperationState)
        if (!isDownloadRequestGenerationCurrent(song.stableKey(), requestGeneration)) {
            return@withContext false
        }
        val userStopped = runCatching {
            DownloadExecutionRoomStore.isStopped(appContext, normalizedOperationId)
        }.getOrDefault(true)
        val userCancelled = isSongCancelled(song.stableKey()) || runCatching {
            DownloadExecutionRoomStore.isUserCancellationRequested(
                appContext,
                normalizedOperationId
            )
        }.getOrDefault(true)
        if (
            userStopped ||
                userCancelled ||
                isDownloadClearFenceActive(
                    context = appContext,
                    stableKey = song.stableKey(),
                    operationId = normalizedOperationId
                ) ||
                admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = appContext,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = normalizedOperationId
                )
        ) {
            return@withContext false
        }
        val recoveryCandidate = resolveCoreRecoveryAudioCandidate(
            context = appContext,
            song = song,
            operationId = normalizedOperationId,
            allowFormalAudio = knownCoreCommitted
        )
        val coreCommitted = knownCoreCommitted ||
            recoveryCandidate != null
        if (!coreCommitted) {
            return@withContext false
        }

        if (normalizedOperationState == "COMMITTING") {
            // 宿主停止可能抢在 markCoreCommitted 之前发生。持久化 seed
            // 已经证明 core 完整时，先修复 operation 状态再交给发布恢复
            runCatching {
                DownloadExecutionRoomStore.markCoreCommitted(
                    context = appContext,
                    operationId = normalizedOperationId
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "提交后取消修复 operation core 状态失败，保留恢复凭据: " +
                        "song=${song.name}, operationId=$normalizedOperationId, " +
                        "error=${error.message}",
                    error
                )
            }
        }
        AudioDownloadManager.markCoreCommittedOperation(normalizedOperationId)
        val storedAudio = recoveryCandidate?.audio
        var recoveryMetadata = recoveryCandidate?.metadata
        if (storedAudio?.isPendingAudioWrite == true && recoveryMetadata == null) {
            // 极旧版本可能只留下 pending 音频而没有 seed metadata。已知
            // operation 越过 core 边界时补写最小 core 凭据，避免永久孤儿
            val metadataWritten = try {
                persistDownloadedMetadata(
                    context = appContext,
                    audio = storedAudio,
                    song = song,
                    downloadFinalized = false,
                    resolveExistingSidecars = false,
                    artifactStateOverride = ManagedDownloadArtifactState.CORE_COMMITTED.name,
                    operationId = normalizedOperationId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "提交后取消补写 core metadata 失败，保留 pending 凭据: " +
                        "song=${song.name}, operationId=$normalizedOperationId, " +
                        "error=${error.message}",
                    error
                )
                false
            }
            if (metadataWritten) {
                recoveryMetadata = runCatching {
                    readDownloadedMetadata(appContext, storedAudio)
                }.getOrNull()
            }
        }
        val publishedAudio = if (storedAudio?.isPendingAudioWrite == true) {
            // 宿主取消只结束执行窗口，core 已经完整校验时要在同一窗口尽快离开
            // pending。目录租约失败才交给持久恢复，不能让 sidecar 成为唯一出口
            val publicationLease = runCatching {
                ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
                    context = appContext,
                    operationId = normalizedOperationId
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "宿主取消后的 core 提升暂时无法取得目录租约: " +
                        "song=${song.name}, operationId=$normalizedOperationId, " +
                        "error=${error.message}",
                    error
                )
            }.getOrNull()
            if (publicationLease == null) {
                storedAudio
            } else {
                try {
                    corePublicationCoordinator.promoteBeforePublication(
                        context = appContext,
                        song = song,
                        audio = storedAudio
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "宿主取消后的 core 提升失败，转入持久恢复: " +
                            "song=${song.name}, operationId=$normalizedOperationId, " +
                            "error=${error.message}",
                        error
                    )
                    storedAudio
                } finally {
                    publicationLease.close()
                }
            }
        } else {
            storedAudio
        }
        if (publishedAudio?.isPendingAudioWrite == true) {
            val existingMetadata = recoveryMetadata
                ?: runCatching {
                    readDownloadedMetadata(appContext, publishedAudio)
                }.getOrNull()
            runCatching {
                deferPendingCorePublication(
                    context = appContext,
                    song = song,
                    audio = publishedAudio,
                    existingMetadata = existingMetadata,
                    artifactLeaseId = artifactLeaseId,
                    expectedAttemptId = expectedAttemptId,
                    operationId = normalizedOperationId,
                    admissionTicket = admissionTicket,
                    reason = "CORE_PUBLICATION_HOST_CANCELLED"
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "提交后取消的 pending core 恢复入口写入失败，保留启动扫描: " +
                        "song=${song.name}, operationId=$normalizedOperationId, " +
                        "error=${error.message}",
                    error
                )
            }
        }
        // 即使当前 Provider 暂时无法读取，也要让统一扫描在本进程继续接管
        scheduleStartupArtifactRecovery(appContext)
        val taskStatus = if (publishedAudio != null && !publishedAudio.isPendingAudioWrite) {
            DownloadStatus.DOWNLOADING
        } else {
            DownloadStatus.WAITING_NETWORK
        }
        updateTaskStatus(
            songKey = song.stableKey(),
            status = taskStatus,
            expectedAttemptId = expectedAttemptId,
            settleBatchPresentation = false
        )
        if (taskStatus == DownloadStatus.DOWNLOADING) {
            publishDownloadStage(
                song = song,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY,
                operationId = normalizedOperationId,
                attemptId = expectedAttemptId,
                bytesRead = publishedAudio?.sizeBytes ?: 0L,
                totalBytes = publishedAudio?.sizeBytes ?: 0L
            )
        }
        NPLogger.d(
            TAG,
            "宿主取消发生在 core 提交之后，已转入正式发布恢复: " +
                "song=${song.name}, operationId=$normalizedOperationId, " +
                "state=$operationState, pending=${publishedAudio?.isPendingAudioWrite}"
        )
        true
    }
}

internal suspend fun GlobalDownloadManager.deferPendingCorePublication(
    context: Context,
    song: SongItem,
    audio: ManagedDownloadStorage.StoredEntry,
    existingMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    artifactLeaseId: String?,
    expectedAttemptId: Long?,
    operationId: String?,
    admissionTicket: Long?,
    reason: String
) {
    withContext(NonCancellable) {
        val appContext = context.applicationContext
        val songCancelled = isSongCancelled(song.stableKey())
        if (
            isDownloadClearFenceActive(
                context = appContext,
                stableKey = song.stableKey(),
                operationId = operationId
            ) ||
                songCancelled ||
                admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = appContext,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            NPLogger.d(
                TAG,
                "pending core 发布被清空代次阻止，保留原始凭据: " +
                    "song=${song.name}, file=${audio.name}, operationId=$operationId"
            )
            return@withContext
        }

        val recoveryOperationId = runCatching {
            ensureCoreRecoveryOperation(
                context = appContext,
                song = song,
                operationId = operationId ?: existingMetadata?.operationId,
                artifactLeaseId = artifactLeaseId,
                expectedAttemptId = expectedAttemptId
            )
        }.getOrElse { error ->
            NPLogger.w(
                TAG,
                "pending core 发布无法建立恢复 operation，保留凭据: " +
                    "song=${song.name}, file=${audio.name}, " +
                    "error=${error.message}",
                error
            )
            null
        }
        val currentState = recoveryOperationId?.let { id ->
            runCatching {
                DownloadExecutionRoomStore.state(appContext, id)
            }.getOrNull()
        }
        val cancellationState = currentState == "CANCEL_REQUESTED" ||
            currentState == "CANCELLED" ||
            currentState == "STOPPED"
        val statePersisted = if (
            recoveryOperationId != null && !cancellationState
        ) {
            runCatching {
                if (currentState == "COMPLETED" || currentState == "FINALIZED") {
                    // 只为已确认的 pending 音频打开旧终态，普通状态转移仍保持单向
                    DownloadExecutionRoomStore.reopenCorePublicationRecovery(
                        context = appContext,
                        operationId = recoveryOperationId,
                        stableKey = song.stableKey(),
                        errorCode = reason
                    )
                } else {
                    DownloadExecutionRoomStore.updateState(
                        context = appContext,
                        operationId = recoveryOperationId,
                        state = "DEGRADED_COMPLETE",
                        errorCode = reason
                    )
                }
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "pending core 发布恢复状态写入失败，保留下次扫描: " +
                        "song=${song.name}, operationId=$recoveryOperationId, " +
                        "error=${error.message}",
                    error
                )
                false
            }
        } else {
            false
        }
        val canSchedulePersistentRetry = statePersisted ||
            currentState == "DEGRADED_COMPLETE"
        if (recoveryOperationId != null && !cancellationState) {
            val pendingArtifactCommitResult = runCatching {
                // artifact 记录可以暂存 pending 引用，但不能把它误写成最终完成。
                // 这样下一次 claim 会走“已有 core，优先收尾”分支，不会清掉可恢复音频
                managedDownloadArtifactCoordinator.markCoreCommitted(
                    context = appContext,
                    song = song,
                    storedAudio = audio,
                    expectedLeaseId = artifactLeaseId
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "pending core artifact 凭据写入失败，仍保留 metadata 恢复入口: " +
                        "song=${song.name}, operationId=$recoveryOperationId, " +
                        "error=${error.message}",
                    error
                )
            }.getOrNull()
            val pendingArtifactCommitted = pendingArtifactCommitResult?.isApplied == true
            if (!pendingArtifactCommitted) {
                NPLogger.w(
                    TAG,
                    "pending core artifact 未确认，下一轮仍需优先保护 staging: " +
                        "song=${song.name}, operationId=$recoveryOperationId, " +
                        "result=$pendingArtifactCommitResult"
                )
            }
            runCatching {
                // 即使 artifact provider 暂时不可写，也要让重试请求保留 pending
                // staging，不能在下一次执行前被 fresh-start 清理掉
                DownloadExecutionRoomStore.markStagingPrepared(
                    context = appContext,
                    operationId = recoveryOperationId,
                    stableKey = song.stableKey()
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "pending core staging 保留标记写入失败: " +
                        "song=${song.name}, operationId=$recoveryOperationId, " +
                        "error=${error.message}",
                    error
                )
            }
            AudioDownloadManager.markCoreCommittedOperation(recoveryOperationId)
            updateTaskStatus(
                songKey = song.stableKey(),
                status = DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = expectedAttemptId,
                settleBatchPresentation = false
            )
            publishDownloadStage(
                song = song,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY,
                operationId = recoveryOperationId,
                attemptId = expectedAttemptId,
                bytesRead = audio.sizeBytes,
                totalBytes = audio.sizeBytes
            )
        }
        if (canSchedulePersistentRetry) {
            schedulePostCoreEnrichmentRetry(
                context = appContext,
                song = song,
                operationId = recoveryOperationId!!,
                expectedAttemptId = expectedAttemptId,
                reason = reason,
                admissionTicket = admissionTicket,
                allowInFlightState = true
            )
        }
        // 即使旧 operation 已经进入终态，也要让下一次有界扫描重新确认物理文件
        // 不能依赖一个可能已经被旧版本错误结算的 Room 状态
        scheduleStartupArtifactRecovery(appContext)
        NPLogger.w(
            TAG,
            "pending core 发布未确认，跳过完成态和资产增强: " +
                "song=${song.name}, file=${audio.name}, " +
                "operationId=$recoveryOperationId, state=$currentState"
        )
    }
}

internal suspend fun GlobalDownloadManager.settlePostCoreEnrichmentFailure(
    context: Context,
    song: SongItem,
    operationId: String?,
    expectedAttemptId: Long?,
    errorCode: String,
    error: Throwable? = null,
    scheduleRetry: Boolean = true,
    admissionTicket: Long? = null,
    coreAudioCommitted: Boolean = true
) {
    val appContext = context.applicationContext
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = appContext,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        NPLogger.d(
            TAG,
            "增强失败收尾票据已失效，跳过状态写回: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val operationState = normalizedOperationId?.let { id ->
        runCatching {
            DownloadExecutionRoomStore.state(appContext, id)
        }.getOrElse { stateError ->
            NPLogger.w(
                TAG,
                "读取收尾失败 operation 状态失败，保留持久凭据: " +
                    "song=${song.name}, operationId=$id, " +
                    "error=${stateError.message}",
                stateError
            )
            null
        }
    }
    val cancellationState = operationState == "CANCEL_REQUESTED" ||
        operationState == "CANCELLED" ||
        operationState == "STOPPED"
    if (cancellationState) {
        NPLogger.d(
            TAG,
            "收尾失败发生在取消或停止之后，跳过完成状态覆盖: " +
                "song=${song.name}, operationId=$normalizedOperationId, " +
                "state=$operationState"
        )
        return
    }
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = appContext,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = normalizedOperationId
            )
    ) {
        NPLogger.d(
            TAG,
            "增强失败收尾状态写回前票据已失效，跳过任务更新: " +
                "song=${song.name}, operationId=$normalizedOperationId"
        )
        return
    }

    // artifact 收尾可能被旧协程重复触发。只要 operation 已越过 core
    // 提交边界，就不能让迟到的“artifact 未确认”把可恢复任务改成失败。
    val coreCommitAlreadyRecorded = !coreAudioCommitted &&
        isDurableCoreOperationState(operationState)
    if (coreCommitAlreadyRecorded) {
        NPLogger.d(
            TAG,
            "忽略迟到的 core artifact 失败降级，保留已提交恢复态: " +
                "song=${song.name}, operationId=$normalizedOperationId, " +
                "state=$operationState, errorCode=$errorCode"
        )
    }
    val taskStatus = resolvePostCoreEnrichmentTaskStatus(
        coreAudioCommitted = coreAudioCommitted || coreCommitAlreadyRecorded
    )
    updateTaskStatus(
        songKey = song.stableKey(),
        status = taskStatus,
        expectedAttemptId = expectedAttemptId,
        settleBatchPresentation = false,
        operationId = normalizedOperationId
    )
    if (taskStatus == DownloadStatus.QUEUED) {
        publishDownloadStage(
            song = song,
            stage = AudioDownloadManager.DownloadStage.WAITING_RETRY,
            operationId = normalizedOperationId,
            attemptId = expectedAttemptId
        )
    } else if (taskStatus == DownloadStatus.COMPLETED) {
        scheduleCompletedTaskRemoval(
            context = appContext,
            songKey = song.stableKey(),
            expectedAttemptId = expectedAttemptId,
            admissionTicket = admissionTicket
        )
    }
    NPLogger.w(
        TAG,
        "core 音频未通过最终收尾，保留有界恢复任务: " +
            "song=${song.name}, operationId=$normalizedOperationId, " +
            "status=$taskStatus, errorCode=$errorCode, " +
            "error=${error?.javaClass?.simpleName}: ${error?.message}",
        error
    )

    if (!scheduleRetry || normalizedOperationId == null) {
        return
    }
    schedulePostCoreEnrichmentRetry(
        context = appContext,
        song = song,
        operationId = normalizedOperationId,
        expectedAttemptId = expectedAttemptId,
        reason = errorCode,
        admissionTicket = admissionTicket
    )
}

internal suspend fun GlobalDownloadManager.schedulePostCoreEnrichmentRetry(
    context: Context,
    song: SongItem,
    operationId: String,
    expectedAttemptId: Long?,
    reason: String,
    admissionTicket: Long? = null,
    allowInFlightState: Boolean = false
) {
    val appContext = context.applicationContext
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = appContext,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        NPLogger.d(
            TAG,
            "增强重试票据已失效，跳过宿主调度: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    val request = runCatching {
        DownloadExecutionRoomStore.read(appContext, operationId)
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "读取收尾重试请求失败，保留 DEGRADED_COMPLETE 等待下次恢复: " +
                "song=${song.name}, operationId=$operationId, " +
                "error=${error.message}",
            error
        )
        return
    }?.takeIf { candidate -> candidate.song.stableKey() == song.stableKey() }
    if (request == null) {
        NPLogger.w(
            TAG,
            "收尾重试请求缺失或歌曲不匹配，保留持久音频: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    if (
        expectedAttemptId != null &&
            request.attemptId != null &&
            request.attemptId != expectedAttemptId
    ) {
        NPLogger.d(
            TAG,
            "跳过过期收尾重试请求: song=${song.name}, operationId=$operationId, " +
                "expectedAttempt=$expectedAttemptId, persistedAttempt=${request.attemptId}"
        )
        return
    }
    val operationState = runCatching {
        DownloadExecutionRoomStore.state(appContext, operationId)
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "读取收尾重试状态失败，保留下次启动恢复: " +
                "song=${song.name}, operationId=$operationId, " +
                "error=${error.message}",
            error
        )
        return
    }
    val userStopped = runCatching {
        DownloadExecutionRoomStore.isStopped(appContext, operationId)
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "读取收尾重试停止标记失败，暂不自动重试: " +
                "song=${song.name}, operationId=$operationId, " +
                "error=${error.message}",
            error
        )
        true
    }
    // 不再为每次失败额外扫描 SAF，明确不支持的容器格式由调用方停止重试
    // 其余失败均可安全交给持久化收尾宿主复查
    val metadataActionRequired = false
    if (!shouldSchedulePostCoreEnrichmentRetry(
            coreAudioCommitted = true,
            operationState = operationState,
            metadataActionRequired = metadataActionRequired,
            userStopped = userStopped,
            allowInFlightState = allowInFlightState,
            songCancelled = isSongCancelled(song.stableKey())
        )
    ) {
        NPLogger.d(
            TAG,
            "收尾重试暂不调度，保留持久状态: song=${song.name}, " +
                "operationId=$operationId, state=$operationState, " +
                "metadataActionRequired=$metadataActionRequired, " +
                "userStopped=$userStopped"
        )
        return
    }
    val scheduled = PersistentDownloadClearFenceStore.withSchedulingPermit(
        context = appContext,
        onFenceActive = { false },
        stableKey = song.stableKey(),
        operationId = operationId
    ) {
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = appContext,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            NPLogger.d(
                TAG,
                "增强重试调度许可内票据已失效，跳过宿主调度: " +
                    "song=${song.name}, operationId=$operationId"
            )
            false
        } else {
            PostCoreDownloadRecoveryWorker.schedule(appContext)
        }
    }
    if (scheduled == true) {
        NPLogger.d(
            TAG,
            "已交给唯一持久 Worker 分批重试 core 音频增强资产: " +
                "song=${song.name}, operationId=$operationId, reason=$reason"
        )
    } else {
        NPLogger.w(
            TAG,
            "core 音频增强资产共享 Worker 调度失败，保留下次恢复: " +
                "song=${song.name}, operationId=$operationId, reason=$reason"
        )
    }
}
