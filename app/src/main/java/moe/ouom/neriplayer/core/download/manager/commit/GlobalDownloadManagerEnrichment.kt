package moe.ouom.neriplayer.core.download.manager.commit

import moe.ouom.neriplayer.core.download.DownloadedAudioTagWriter
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.runNonCancellableDownloadRollback
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.scheduleStartupArtifactRecovery
import moe.ouom.neriplayer.core.download.manager.batch.forgetPendingDownloadQueueEntriesForOperation
import moe.ouom.neriplayer.core.download.manager.batch.isCancellationCleanupStillCurrent
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCompletedTaskRemoval
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadArtifactRepairRequired
import moe.ouom.neriplayer.core.download.manager.runtime.cleanupUnfinalizedDownloadForRetry
import moe.ouom.neriplayer.core.download.manager.runtime.publishCompletedDownloadOptimistically
import moe.ouom.neriplayer.core.download.manager.recovery.invalidCoreAudioReason
import moe.ouom.neriplayer.core.download.manager.recovery.requeueInvalidCoreAudio
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadedArtifactIntegrityResult
import moe.ouom.neriplayer.core.download.model.DownloadedArtifactReferenceState
import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.model.expectedDownloadedAudioDurationMs
import moe.ouom.neriplayer.core.download.model.shouldApplyTaskMutation
import moe.ouom.neriplayer.core.download.model.verifyDownloadedArtifactIntegrity
import moe.ouom.neriplayer.core.download.policy.FinalizedDownloadPublicationResult
import moe.ouom.neriplayer.core.download.policy.TerminalTemporaryWriteCleanupRetryPolicy
import moe.ouom.neriplayer.core.download.policy.finalizedTemporaryWriteTargetNames
import moe.ouom.neriplayer.core.download.policy.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.policy.shouldCleanupCancelledPendingArtifacts
import moe.ouom.neriplayer.core.download.policy.shouldFinalizeDownloadedSidecars
import moe.ouom.neriplayer.core.download.policy.shouldPreserveAudioForCancellationRollback
import moe.ouom.neriplayer.core.download.policy.shouldPurgeCancelledDownloadOperation
import moe.ouom.neriplayer.core.download.policy.shouldSchedulePostCoreEnrichmentRetry
import moe.ouom.neriplayer.core.download.policy.withDownloadClearRoomTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.CancelledPendingCleanupOutcome
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.MetadataPostProcessingResult
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.FinalizedDownloadedAudioProbe
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.recovery.isArtifactRecoveryAllowed
import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.persistence.METADATA_ACTION_REQUIRED_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.persistence.METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTraceToken
import moe.ouom.neriplayer.core.download.policy.TagPostProcessingAction
import moe.ouom.neriplayer.core.download.policy.tagPostProcessingAction
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadedSidecarStage
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import java.util.UUID


internal suspend fun GlobalDownloadManager.enrichCoreCommittedDownload(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    existingMetadataHint: ManagedDownloadStorage.DownloadedAudioMetadata?,
    operationId: String,
    artifactLeaseId: String?,
    expectedAttemptId: Long?,
    traceToken: DownloadOperationTraceToken?,
    allowMissingTask: Boolean,
    directoryMutationLeaseOwned: Boolean,
    admissionTicket: Long?
) {
    if (!DownloadExecutionRoomStore.isArtifactRecoveryAllowed(context, operationId)) return
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
            "资产增强票据已失效，保留 core 凭据等待恢复: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    val directoryCommitLease = if (directoryMutationLeaseOwned) {
        null
    } else {
        ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
            context = context,
            operationId = operationId
        ) ?: throw DownloadStorageMutationDeferredException(operationId)
    }
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        directoryCommitLease?.close()
        NPLogger.d(
            TAG,
            "资产增强取目录租约后清空代次已失效，保留 core 凭据: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    // 启动恢复可能拿到旧版本留下的 pending 音频，增强前再次尝试正式提升
    val enrichmentAudio = try {
        val invalidReason = invalidCoreAudioReason(context, song, storedAudio, operationId)
        if (invalidReason != null) {
            check(requeueInvalidCoreAudio(
                context, song, operationId, storedAudio.reference, artifactLeaseId,
                invalidReason, directoryLeaseOwned = true
            )) { invalidReason }
            directoryCommitLease?.close()
            return
        }
        corePublicationCoordinator.promoteBeforePublication(
            context = context,
            song = song,
            audio = storedAudio
        )
    } catch (error: Throwable) {
        // 提升本身发生在 try 主体之前，异常路径也必须释放目录租约
        directoryCommitLease?.close()
        throw error
    }
    if (
        isDownloadClearFenceActive(
            context = context,
            stableKey = song.stableKey(),
            operationId = operationId
        ) ||
            admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        // Provider 提升期间可能刚好进入清空代次，不能再对 artifact 或 sidecar 写入
        directoryCommitLease?.close()
        NPLogger.d(
            TAG,
            "资产增强提升后准入已失效，保留 core 凭据: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    if (enrichmentAudio.isPendingAudioWrite) {
        // 增强阶段也不能对 pending 引用写 sidecar 或最终 metadata，否则宿主取消
        // 后会留下“UI 已完成但正式文件不存在”的假完成状态
        directoryCommitLease?.close()
        deferPendingCorePublication(
            context = context,
            song = song,
            audio = enrichmentAudio,
            existingMetadata = existingMetadataHint,
            artifactLeaseId = artifactLeaseId,
            expectedAttemptId = expectedAttemptId,
            operationId = operationId,
            admissionTicket = admissionTicket,
            reason = "CORE_PUBLICATION_PENDING"
        )
        return
    }
    var sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences()
    try {
        managedDownloadArtifactCoordinator.markAssetsEnriching(
            context = context,
            song = song,
            expectedLeaseId = artifactLeaseId
        )
        DownloadExecutionRoomStore.updateState(
            context = context,
            operationId = operationId,
            state = "ASSETS_ENRICHING"
        )
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = context,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            return
        }
        sidecarReferences = AudioDownloadManager.downloadSidecarsForCompletedAudio(
            context = context,
            song = song,
            storedAudio = enrichmentAudio,
            operationId = operationId,
            stageObserver = { stage, started ->
                val phase = when (stage) {
                    DownloadedSidecarStage.COVER -> if (started) {
                        DownloadOperationTracePhase.ENRICHMENT_COVER_STARTED
                    } else {
                        DownloadOperationTracePhase.ENRICHMENT_COVER_FINISHED
                    }

                    DownloadedSidecarStage.LYRICS -> if (started) {
                        DownloadOperationTracePhase.ENRICHMENT_LYRICS_STARTED
                    } else {
                        DownloadOperationTracePhase.ENRICHMENT_LYRICS_FINISHED
                    }
                }
                DownloadOperationTrace.mark(traceToken, phase)
            }
        )
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = context,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            return
        }
        val metadataPostProcessingEnabled =
            isDownloadMetadataPostProcessingEnabled(context)
        val coverReference = sidecarReferences.coverReference
        val coverAccessible = coverReference?.let { reference ->
            ManagedDownloadReferenceLookup.inspect(context, reference) is
                ManagedDownloadReferenceLookup.Result.Present
        } == true
        check(
            shouldFinalizeDownloadedSidecars(
                hasNetworkCoverCandidate = sidecarReferences.expectedCover,
                coverReference = coverReference,
                coverAccessible = coverAccessible
            )
        ) { "COVER_SIDECAR_MISSING" }
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = context,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            return
        }
        NPLogger.d(
            TAG,
            "开始下载元信息后处理: song=${song.name}, operationId=$operationId, " +
                "enabled=$metadataPostProcessingEnabled, " +
                "expectedCover=${sidecarReferences.expectedCover}, " +
                "expectedLyric=${sidecarReferences.expectedLyric}, " +
                "expectedTranslatedLyric=${sidecarReferences.expectedTranslatedLyric}, " +
                "expectedRomanizedLyric=${sidecarReferences.expectedRomanizedLyric}"
        )
        val metadataEmbeddingState: DownloadedAudioEmbeddingState
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.ENRICHMENT_METADATA_STARTED
        )
        try {
            metadataEmbeddingState = if (metadataPostProcessingEnabled) {
                when (
                    runDownloadedAudioMetadataPostProcessing(
                        context = context,
                        audio = enrichmentAudio,
                        song = song,
                        sidecarReferences = sidecarReferences,
                        traceToken = traceToken
                    )
                ) {
                    MetadataPostProcessingResult.EMBEDDED_VERIFIED ->
                        DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED

                    MetadataPostProcessingResult.UNSUPPORTED_CONTAINER -> {
                        preserveUnsupportedMetadataEmbedding(
                            context = context,
                            song = song,
                            storedAudio = enrichmentAudio,
                            sidecarReferences = sidecarReferences,
                            operationId = operationId,
                            artifactLeaseId = artifactLeaseId,
                            expectedAttemptId = expectedAttemptId,
                            admissionTicket = admissionTicket
                        )
                        return
                    }

                    MetadataPostProcessingResult.RETRYABLE_FAILURE ->
                        error("embedded metadata post-processing failed")
                }
            } else {
                DownloadedAudioEmbeddingState.USER_DISABLED
            }
            if (
                admissionTicket != null &&
                    !isDownloadAdmissionTicketCurrent(
                        context = context,
                        admissionTicket = admissionTicket,
                        stableKey = song.stableKey(),
                        operationId = operationId
                    )
            ) {
                return
            }
            check(
                persistDownloadedMetadata(
                    context = context,
                    audio = enrichmentAudio,
                    song = song,
                    existingMetadataHint = existingMetadataHint,
                    sidecarReferences = sidecarReferences,
                    downloadFinalized = true,
                    metadataEmbeddingState = metadataEmbeddingState,
                    resolveExistingSidecars = false,
                    operationId = operationId
                )
            ) { "final metadata persist failed" }
        } finally {
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.ENRICHMENT_METADATA_FINISHED
            )
        }
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = context,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            return
        }
        when (
            publishFinalizedDownload(
                context = context,
                song = song,
                storedAudio = enrichmentAudio,
                sidecarReferences = sidecarReferences,
                expectedAttemptId = expectedAttemptId,
                operationId = operationId,
                expectedArtifactLeaseId = artifactLeaseId,
                allowMissingTask = allowMissingTask,
                admissionTicket = admissionTicket
            )
        ) {
            FinalizedDownloadPublicationResult.PUBLISHED -> Unit
            FinalizedDownloadPublicationResult.STALE -> {
                NPLogger.d(
                    TAG,
                    "最终发布已由新代次接管，跳过旧收尾重试: " +
                        "song=${song.name}, operationId=$operationId"
                )
                return
            }

            FinalizedDownloadPublicationResult.RECOVERY_REQUIRED -> {
                error("final publication requires recovery; see integrity and artifact diagnostics")
            }
        }
        NPLogger.d(
            TAG,
            "下载资产补齐完成: song=${song.name}, operationId=$operationId"
        )
    } catch (error: CancellationException) {
        val retryScheduled = withContext(NonCancellable) {
            runCatching {
                val currentState = DownloadExecutionRoomStore.state(context, operationId)
                val userStopped = DownloadExecutionRoomStore.isStopped(context, operationId)
                val clearBlocked = isDownloadClearFenceActive(
                    context = context,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
                val canRetry = !clearBlocked &&
                    shouldSchedulePostCoreEnrichmentRetry(
                        coreAudioCommitted = true,
                        operationState = currentState,
                        metadataActionRequired = false,
                        userStopped = userStopped,
                        allowInFlightState = true,
                        songCancelled = isSongCancelled(song.stableKey())
                    )
                if (canRetry) {
                    if (!artifactLeaseId.isNullOrBlank()) {
                        runCatching {
                            managedDownloadArtifactCoordinator.markDegradedComplete(
                                context = context,
                                song = song,
                                expectedLeaseId = artifactLeaseId,
                                errorCode = "ASSET_ENRICHMENT_CANCELLED",
                                retainLease = true
                            )
                        }.onFailure { leaseError ->
                            NPLogger.w(
                                TAG,
                                "资产增强取消后的 artifact 租约保留失败: " +
                                    "song=${song.name}, operationId=$operationId, " +
                                    "error=${leaseError.message}",
                                leaseError
                            )
                        }
                    }
                    val statePersisted = if (currentState == "COMPLETED") {
                        // 普通状态机禁止 COMPLETED 回退，旧宿主取消仍需打开可恢复入口
                        DownloadExecutionRoomStore.reopenCorePublicationRecovery(
                            context = context,
                            operationId = operationId,
                            stableKey = song.stableKey(),
                            errorCode = "ASSET_ENRICHMENT_CANCELLED"
                        )
                    } else {
                        DownloadExecutionRoomStore.updateState(
                            context = context,
                            operationId = operationId,
                            state = "DEGRADED_COMPLETE",
                            errorCode = "ASSET_ENRICHMENT_CANCELLED"
                        )
                    }
                    if (!statePersisted) {
                        NPLogger.w(
                            TAG,
                            "资产增强取消后的降级状态未确认，保留启动恢复: " +
                                "song=${song.name}, operationId=$operationId, " +
                                "state=$currentState"
                        )
                    }
                    // ASSETS_ENRICHING 不是共享泵的可调度状态。直接触发 artifact
                    // 恢复扫描，既能提升 pending 音频，也能重新建立增强任务
                    scheduleStartupArtifactRecovery(context)
                    schedulePostCoreEnrichmentRetry(
                        context = context,
                        song = song,
                        operationId = operationId,
                        expectedAttemptId = expectedAttemptId,
                        reason = "ASSET_ENRICHMENT_CANCELLED",
                        admissionTicket = admissionTicket,
                        allowInFlightState = true
                    )
                } else if (!artifactLeaseId.isNullOrBlank()) {
                    runCatching {
                        managedDownloadArtifactCoordinator.settleLeaseAnyRoot(
                            context = context,
                            song = song,
                            expectedLeaseId = artifactLeaseId,
                            requestedState = ManagedDownloadArtifactState.DEGRADED_COMPLETE,
                            errorCode = "ASSET_ENRICHMENT_CANCELLED"
                        )
                    }.onFailure { leaseError ->
                        NPLogger.w(
                            TAG,
                            "资产增强取消后的 artifact 租约收尾失败: " +
                                "song=${song.name}, operationId=$operationId, " +
                                "error=${leaseError.message}",
                            leaseError
                        )
                    }
                }
                canRetry
            }.getOrElse { retryError ->
                NPLogger.w(
                    TAG,
                    "资产增强取消后的恢复调度失败，保留 core 凭据: " +
                        "song=${song.name}, operationId=$operationId, " +
                        "error=${retryError.message}",
                    retryError
                )
                false
            }
        }
        if (retryScheduled) {
            NPLogger.d(
                TAG,
                "资产增强被宿主取消，已保留持久重试: " +
                    "song=${song.name}, operationId=$operationId"
            )
        }
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "下载资产补齐失败，保留 core audio: song=${song.name}, " +
                "operationId=$operationId, error=${error.javaClass.simpleName}: " +
                error.message,
            error
        )
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
                "资产增强失败时清空代次已失效，跳过降级状态写回: " +
                    "song=${song.name}, operationId=$operationId"
            )
            return
        }
        runCatching {
            persistDownloadedMetadata(
                context = context,
                audio = enrichmentAudio,
                song = song,
                // 已成功写入的可复用侧载引用也必须留在可恢复 metadata 中。
                // 只保留本次新建项会让下一轮重启错误地再次下载或丢失既有封面/歌词
                sidecarReferences = sidecarReferences,
                downloadFinalized = false,
                resolveExistingSidecars = false,
                operationId = operationId
            )
        }
        runCatching {
            managedDownloadArtifactCoordinator.markDegradedComplete(
                context = context,
                song = song,
                expectedLeaseId = artifactLeaseId,
                errorCode = "ASSET_ENRICHMENT_FAILED",
                retainLease = true
            )
        }
        val degradedStatePersisted = runCatching {
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = "DEGRADED_COMPLETE",
                errorCode = "ASSET_ENRICHMENT_FAILED"
            )
        }.getOrElse { stateError ->
            NPLogger.w(
                TAG,
                "收尾异常后的 operation 降级状态写入失败: " +
                    "song=${song.name}, operationId=$operationId, " +
                    "error=${stateError.message}",
                stateError
            )
            false
        }
        if (!degradedStatePersisted) {
            NPLogger.w(
                TAG,
                "收尾异常后的 operation 未确认 DEGRADED_COMPLETE: " +
                    "song=${song.name}, operationId=$operationId"
            )
        }
        settlePostCoreEnrichmentFailure(
            context = context,
            song = song,
            operationId = operationId,
            expectedAttemptId = expectedAttemptId,
            errorCode = "ASSET_ENRICHMENT_FAILED",
            error = error,
            admissionTicket = admissionTicket
        )
    } finally {
        directoryCommitLease?.close()
    }
}

internal suspend fun GlobalDownloadManager.preserveUnsupportedMetadataEmbedding(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences,
    operationId: String,
    artifactLeaseId: String?,
    expectedAttemptId: Long?,
    admissionTicket: Long?
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
            "不支持内嵌元信息收尾票据已失效，保留 core 凭据: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    NPLogger.w(
        TAG,
        "下载容器不支持内嵌元信息，保留待处理文件: " +
            "song=${song.name}, file=${storedAudio.name}"
    )
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        return
    }
    val metadataPersisted = runCatching {
        persistDownloadedMetadata(
            context = context,
            audio = storedAudio,
            song = song,
            sidecarReferences = sidecarReferences,
            downloadFinalized = false,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
            resolveExistingSidecars = false,
            operationId = operationId
        )
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "记录不支持内嵌元信息状态失败，保留可重试文件: " +
                "file=${storedAudio.name}, error=${error.message}"
        )
        false
    }
    if (!metadataPersisted) {
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = context,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            return
        }
        markDownloadArtifactRepairRequired(
            context = context,
            song = song,
            leaseId = artifactLeaseId,
            errorCode = "METADATA_EMBEDDING_STATE_WRITE_FAILED"
        )
        runCatching {
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = "DEGRADED_COMPLETE",
                errorCode = "METADATA_EMBEDDING_STATE_WRITE_FAILED"
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "记录元信息状态失败后的 operation 降级状态写入失败: " +
                    "song=${song.name}, operationId=$operationId, " +
                    "error=${error.message}",
                error
            )
        }
        if (
            admissionTicket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = context,
                    admissionTicket = admissionTicket,
                    stableKey = song.stableKey(),
                    operationId = operationId
                )
        ) {
            return
        }
        settlePostCoreEnrichmentFailure(
            context = context,
            song = song,
            operationId = operationId,
            expectedAttemptId = expectedAttemptId,
            errorCode = "METADATA_EMBEDDING_STATE_WRITE_FAILED",
            scheduleRetry = true,
            admissionTicket = admissionTicket
        )
        return
    }
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        return
    }
    runCatching {
        managedDownloadArtifactCoordinator.markDegradedComplete(
            context = context,
            song = song,
            expectedLeaseId = artifactLeaseId,
            errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "记录不支持内嵌元信息 artifact 状态失败: ${error.message}")
    }
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = song.stableKey(),
                operationId = operationId
            )
    ) {
        return
    }
    runCatching {
        DownloadExecutionRoomStore.updateState(
            context = context,
            operationId = operationId,
            state = "DEGRADED_COMPLETE",
            errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "记录不支持内嵌元信息 operation 状态失败: ${error.message}")
    }
    settlePostCoreEnrichmentFailure(
        context = context,
        song = song,
        operationId = operationId,
        expectedAttemptId = expectedAttemptId,
        errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR,
        scheduleRetry = false,
        admissionTicket = admissionTicket
    )
}

internal suspend fun GlobalDownloadManager.verifyFinalizedDownloadedArtifactForPublication(
    context: Context,
    song: SongItem,
    audio: ManagedDownloadStorage.StoredEntry,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
): DownloadedArtifactIntegrityResult {
    val metadata = readDownloadedMetadata(context, audio)
    val effectiveSidecars = sidecarReferences ?: AudioDownloadManager.DownloadedSidecarReferences(
        coverReference = metadata?.coverPath,
        lyricReference = metadata?.lyricPath,
        translatedLyricReference = metadata?.translatedLyricPath,
        romanizedLyricReference = metadata?.romanizedLyricPath,
        expectedCover = !metadata?.coverPath.isNullOrBlank() ||
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song).isNotEmpty(),
        expectedLyric = !metadata?.lyricPath.isNullOrBlank() ||
            !song.matchedLyric.isNullOrBlank() ||
            !song.originalLyric.isNullOrBlank(),
        expectedTranslatedLyric = !metadata?.translatedLyricPath.isNullOrBlank() ||
            !song.matchedTranslatedLyric.isNullOrBlank() ||
            !song.originalTranslatedLyric.isNullOrBlank(),
        expectedRomanizedLyric = !metadata?.romanizedLyricPath.isNullOrBlank() ||
            !song.matchedRomanizedLyric.isNullOrBlank() ||
            !song.originalRomanizedLyric.isNullOrBlank()
    )
    val audioProbe = inspectFinalizedDownloadedAudio(context, audio)
    NPLogger.d(
        TAG,
        "最终音频时长校验: operationId=${metadata?.operationId}, " +
            "expectedMs=${expectedDownloadedAudioDurationMs(song, metadata)}, " +
            "catalogMs=${song.durationMs}, actualMs=${audioProbe.durationMs}, " +
            "readable=${audioProbe.readable}"
    )
    val references = DownloadedArtifactReferenceState(
        audioReadable = audioProbe.readable,
        audioDurationMs = audioProbe.durationMs,
        coverReadable = isReadableManagedDownloadReference(
            context,
            metadata?.coverPath
        ),
        originalLyricReadable = isReadableManagedDownloadReference(
            context,
            metadata?.lyricPath
        ),
        translatedLyricReadable = isReadableManagedDownloadReference(
            context,
            metadata?.translatedLyricPath
        ),
        romanizedLyricReadable = isReadableManagedDownloadReference(
            context,
            metadata?.romanizedLyricPath
        )
    )
    return verifyDownloadedArtifactIntegrity(
        song = song,
        metadata = metadata,
        references = references,
        expectCover = effectiveSidecars.expectedCover,
        expectOriginalLyric = effectiveSidecars.expectedLyric,
        expectTranslatedLyric = effectiveSidecars.expectedTranslatedLyric,
        expectRomanizedLyric = effectiveSidecars.expectedRomanizedLyric
    )
}

internal suspend fun GlobalDownloadManager.inspectFinalizedDownloadedAudio(
    context: Context,
    audio: ManagedDownloadStorage.StoredEntry
): FinalizedDownloadedAudioProbe {
    if (!ManagedDownloadStorage.hasReadableContent(context, audio)) {
        return FinalizedDownloadedAudioProbe(readable = false, durationMs = null)
    }
    val playbackUri = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(audio)
        ?.toUri()
        ?: return FinalizedDownloadedAudioProbe(readable = false, durationMs = null)
    return withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, playbackUri, null)
            val audioDurationsUs = (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val hasAudioTrack = format.getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/", ignoreCase = true) == true
                if (!hasAudioTrack) {
                    null
                } else if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0L }
                } else {
                    0L
                }
            }
            if (audioDurationsUs.isEmpty()) {
                return@withContext FinalizedDownloadedAudioProbe(
                    readable = false,
                    durationMs = null
                )
            }
            val extractorDurationMs = audioDurationsUs
                .maxOrNull()
                ?.takeIf { it > 0L }
                ?.div(1_000L)
            // MP3 extractor 可能用文件大小和首帧码率估算，优先使用容器解析结果
            val containerDurationMs = runCatching {
                context.contentResolver.openFileDescriptor(playbackUri, "r")?.use { descriptor ->
                    TagLib.getAudioProperties(descriptor.dup().detachFd())
                        ?.length?.toLong()?.takeIf { it > 0L }
                }
            }.getOrNull()
            val durationMs = containerDurationMs ?: extractorDurationMs ?: readFinalizedAudioDuration(
                context = context,
                playbackUri = playbackUri
            )
            FinalizedDownloadedAudioProbe(
                readable = true,
                durationMs = durationMs
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "最终音频可读性或时长校验失败，保留恢复凭据: " +
                    "audio=${audio.logicalName}, error=${error.message}"
            )
            FinalizedDownloadedAudioProbe(readable = false, durationMs = null)
        } finally {
            runCatching { extractor.release() }
        }
    }
}

internal fun GlobalDownloadManager.readFinalizedAudioDuration(context: Context, playbackUri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, playbackUri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun GlobalDownloadManager.isReadableManagedDownloadReference(
    context: Context,
    reference: String?
): Boolean {
    val normalizedReference = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    return ManagedDownloadReferenceLookup.inspect(context, normalizedReference) is
        ManagedDownloadReferenceLookup.Result.Present
}

internal suspend fun GlobalDownloadManager.demoteFinalizedMetadataForIntegrityRecovery(
    context: Context,
    song: SongItem,
    audio: ManagedDownloadStorage.StoredEntry,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
    operationId: String?
) {
    val metadata = readDownloadedMetadata(context, audio)
    val recoverySidecars = sidecarReferences ?: AudioDownloadManager.DownloadedSidecarReferences(
        coverReference = metadata?.coverPath,
        lyricReference = metadata?.lyricPath,
        translatedLyricReference = metadata?.translatedLyricPath,
        romanizedLyricReference = metadata?.romanizedLyricPath,
        expectedCover = !metadata?.coverPath.isNullOrBlank() ||
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song).isNotEmpty(),
        expectedLyric = !metadata?.lyricPath.isNullOrBlank() ||
            !song.matchedLyric.isNullOrBlank() || !song.originalLyric.isNullOrBlank(),
        expectedTranslatedLyric = !metadata?.translatedLyricPath.isNullOrBlank() ||
            !song.matchedTranslatedLyric.isNullOrBlank() ||
            !song.originalTranslatedLyric.isNullOrBlank(),
        expectedRomanizedLyric = !metadata?.romanizedLyricPath.isNullOrBlank() ||
            !song.matchedRomanizedLyric.isNullOrBlank() ||
            !song.originalRomanizedLyric.isNullOrBlank()
    )
    val persisted = persistDownloadedMetadata(
        context = context,
        audio = audio,
        song = song,
        existingMetadataHint = metadata,
        sidecarReferences = recoverySidecars,
        downloadFinalized = false,
        metadataEmbeddingState = metadata?.metadataEmbeddingState,
        resolveExistingSidecars = false,
        artifactStateOverride = ManagedDownloadArtifactState.DEGRADED_COMPLETE.name,
        operationId = operationId
    )
    if (!persisted) {
        NPLogger.w(
            TAG,
            "最终完整性校验失败后无法降级 metadata，保留下一次恢复重试: " +
                "song=${song.name}, operationId=$operationId"
        )
    }
}

internal suspend fun GlobalDownloadManager.publishFinalizedDownload(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
    expectedAttemptId: Long?,
    operationId: String?,
    expectedArtifactLeaseId: String?,
    allowMissingTask: Boolean,
    admissionTicket: Long? = null
): FinalizedDownloadPublicationResult {
    if (!DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
            context, operationId, respectRetryDeadline = false
        )
    ) return FinalizedDownloadPublicationResult.STALE
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
            "最终发布票据已失效，由当前代次接管: " +
                "song=${song.name}, operationId=$operationId"
        )
        return FinalizedDownloadPublicationResult.STALE
    }
    val songKey = song.stableKey()
    val currentTask = taskStore.findTask(songKey)
    if (
        (currentTask == null && !allowMissingTask) ||
            (currentTask != null && !shouldApplyTaskMutation(currentTask, expectedAttemptId))
    ) {
        NPLogger.d(
            TAG,
            "跳过过期下载最终发布: song=${song.name}, " +
                "expectedAttemptId=$expectedAttemptId"
        )
        return FinalizedDownloadPublicationResult.STALE
    }
    val terminalTemporaryWriteTargets =
        finalizedTemporaryWriteTargetNames(
            audioName = storedAudio.logicalName,
            pendingAudioName = storedAudio.name
        )
    // 并发恢复可能仍持有旧 pending 引用，而主流程已经发布并写入了内嵌标签
    // 先复用正式目录中的同一首音频，避免用写标签前的大小误判发布失败
    val publicationAudio = corePublicationCoordinator.promoteBeforePublication(
        context = context,
        song = song,
        audio = storedAudio
    )
    val promotion = ManagedDownloadStorage.promoteFinalizedPendingAudio(
        context = context,
        audio = publicationAudio
    ) ?: run {
        NPLogger.w(
            TAG,
            "最终发布音频提升未确认，保留恢复凭据: " +
                "song=${song.name}, operationId=$operationId"
        )
        return FinalizedDownloadPublicationResult.RECOVERY_REQUIRED
    }
    val finalizedAudio = promotion.audio
    val integrity = verifyFinalizedDownloadedArtifactForPublication(
        context = context,
        song = song,
        audio = finalizedAudio,
        sidecarReferences = sidecarReferences
    )
    if (!integrity.isValid) {
        demoteFinalizedMetadataForIntegrityRecovery(
            context = context,
            song = song,
            audio = finalizedAudio,
            sidecarReferences = sidecarReferences,
            operationId = operationId
        )
        NPLogger.w(
            TAG,
            "最终发布完整性校验未通过，禁止目录和完成态发布: " +
                "song=${song.name}, operationId=$operationId, issues=${integrity.issues}"
        )
        return FinalizedDownloadPublicationResult.RECOVERY_REQUIRED
    }
    if (!DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
            context, operationId, respectRetryDeadline = false
        ) || admissionTicket != null && !isDownloadAdmissionTicketCurrent(
            context, admissionTicket, stableKey = songKey, operationId = operationId
        )
    ) return FinalizedDownloadPublicationResult.STALE
    // promotion 会让旧 pending 引用立即失效。先把内存桥接切到正式引用，
    // 再发布 artifact 和 catalog，避免首播线程在这个窗口内拿到失效 URI
    AudioDownloadManager.rememberCompletedAudioReference(
        song = song,
        storedAudio = finalizedAudio
    )
    val artifactFinalizationResult = runCatching {
        managedDownloadArtifactCoordinator.markFinalized(
            context = context,
            song = song,
            storedAudio = finalizedAudio,
            expectedLeaseId = expectedArtifactLeaseId
        )
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "最终发布写入 artifact 状态失败，保留正式音频等待恢复: " +
                "song=${song.name}, operationId=$operationId, " +
                "error=${error.message}",
            error
        )
    }.getOrNull()
    val artifactFinalized = artifactFinalizationResult?.isApplied == true
    if (!artifactFinalized) {
        // promotion 已经把音频移出 pending，不能再让 catalog、task 或 operation
        // 先进入完成态。保留有界播放桥并安排恢复，下一轮会用同一正式引用重试
        AudioDownloadManager.releaseCompletedAudioReference(
            songKey = songKey,
            expectedAudio = finalizedAudio,
            retainForPlayback = true
        )
        scheduleStartupArtifactRecovery(context)
        NPLogger.w(
            TAG,
            "最终发布 artifact 未确认，跳过 catalog/task/operation 收口: " +
                "song=${song.name}, operationId=$operationId, " +
                "result=$artifactFinalizationResult"
        )
        return FinalizedDownloadPublicationResult.RECOVERY_REQUIRED
    }
    publishCompletedDownloadOptimistically(
        context = context,
        song = song,
        storedAudio = finalizedAudio,
        sidecarReferences = sidecarReferences
    )
    // catalog 已经在内存中可见后再清理桥接引用。清理失败时保留短期引用，
    // 由过期回收机制处理，不能为了释放内存重新暴露 pending URI
    AudioDownloadManager.releaseCompletedAudioReference(
        songKey = songKey,
        expectedAudio = finalizedAudio,
        // provider 在 promotion 后可能短暂返回 Missing, 保留有界内存桥
        // 让刚完成的歌曲继续使用已校验引用
        retainForPlayback = true
    )
    updateTaskStatus(
        songKey,
        DownloadStatus.COMPLETED,
        expectedAttemptId = expectedAttemptId,
        operationId = operationId
    )
    forgetPendingDownloadQueueEntriesForOperation(
        context = context,
        songKey = songKey,
        operationId = operationId
    )
    scheduleCompletedTaskRemoval(
        context = context,
        songKey = songKey,
        expectedAttemptId = expectedAttemptId,
        admissionTicket = admissionTicket
    )
    operationId?.let { id ->
        DownloadExecutionRoomStore.updateState(
            context = context,
            operationId = id,
            state = "FINALIZED"
        )
        AudioDownloadManager.clearCoreCommittedOperation(id)
    }
    cleanupFinalizedPendingArtifacts(
        context = context,
        pendingAudio = storedAudio,
        finalizedAudio = finalizedAudio,
        operationId = operationId,
        terminalTemporaryWriteTargets = terminalTemporaryWriteTargets,
        terminalTemporaryWriteCleanupRecorded =
            promotion.terminalTemporaryWriteCleanupRecorded
    )
    // publishCompletedDownloadOptimistically 已经写入内存和 Room delta；
    // 正常完成不再为每首歌曲触发一次完整目录扫描。真正需要对账的异常路径
    // 会显式请求 forceRefresh
    return FinalizedDownloadPublicationResult.PUBLISHED
}

internal suspend fun GlobalDownloadManager.cleanupFinalizedPendingArtifacts(
    context: Context,
    pendingAudio: ManagedDownloadStorage.StoredEntry,
    finalizedAudio: ManagedDownloadStorage.StoredEntry,
    operationId: String?,
    terminalTemporaryWriteTargets: Collection<String>,
    terminalTemporaryWriteCleanupRecorded: Boolean
) {
    try {
        val pendingMetadataDeleted = ManagedDownloadStorage.deletePendingAudioMetadata(
            context = context,
            audioName = finalizedAudio.logicalName
        )
        if (!pendingMetadataDeleted) {
            NPLogger.w(
                TAG,
                "最终发布后 pending metadata 清理未确认，保留下次恢复重试: " +
                    "audio=${finalizedAudio.logicalName}"
            )
        }
        val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
        val residualPendingAudio = if (
            normalizedOperationId != null &&
                pendingAudio.isPendingAudioWrite &&
                pendingAudio.reference != finalizedAudio.reference
        ) {
            ManagedDownloadStorage.queryStoredEntry(
                context = context,
                reference = pendingAudio.reference
            )?.takeIf { entry ->
                entry.isPendingAudioWrite && entry.logicalName == finalizedAudio.logicalName
            }
        } else {
            null
        }
        residualPendingAudio?.let { residualAudio ->
            val residualMetadata = readDownloadedMetadata(context, residualAudio)
            if (
                residualMetadata?.operationId == normalizedOperationId &&
                    isFinalizedDownloadedMetadata(residualMetadata)
            ) {
                val deletedReferences = ManagedDownloadStorage.deleteReferences(
                    context = context,
                    references = listOf(residualAudio.reference)
                )
                if (residualAudio.reference !in deletedReferences) {
                    NPLogger.w(
                        TAG,
                        "最终发布后 pending 音频清理未确认，保留下次恢复重试: " +
                            "audio=${residualAudio.name}"
                    )
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "最终发布后 pending 半成品清理失败，保留下次恢复重试: " +
                "audio=${finalizedAudio.logicalName}, error=${error.message}",
            error
        )
    }
    if (!terminalTemporaryWriteCleanupRecorded) {
        NPLogger.w(
            TAG,
            "最终发布后临时写入清理记录仍处于准备态，立即安排恢复重试: " +
                "targets=${terminalTemporaryWriteTargets.size}"
        )
    }
    scheduleFinalizedTemporaryWriteCleanup(
        context = context,
        targetNames = terminalTemporaryWriteTargets
    )
}

internal fun GlobalDownloadManager.scheduleFinalizedTemporaryWriteCleanup(
    context: Context,
    targetNames: Collection<String>
) {
    if (targetNames.isEmpty()) return

    schedulePersistedTerminalTemporaryWriteCleanup(
        context = context,
        targetNames = targetNames
    )
}

internal fun GlobalDownloadManager.schedulePersistedTerminalTemporaryWriteCleanup(
    context: Context,
    targetNames: Collection<String> = emptyList()
) {
    val appContext = context.applicationContext
    scope.launch {
        terminalTemporaryWriteCleanupMutex.withLock {
            terminalTemporaryWriteCleanupBatch.addAll(targetNames)
            terminalTemporaryWriteCleanupWakeRequested = true
            if (terminalTemporaryWriteCleanupJob?.isActive == true) {
                return@withLock
            }
            terminalTemporaryWriteCleanupJob = scope.launch cleanupLoop@{
                delay(TERMINAL_TEMPORARY_WRITE_CLEANUP_COALESCE_MS)
                var failedAttempt = 0
                var retryPending = false
                while (true) {
                    val (targets, cleanupRequested) =
                        terminalTemporaryWriteCleanupMutex.withLock {
                            val targets = terminalTemporaryWriteCleanupBatch.takeAll()
                            val requested = terminalTemporaryWriteCleanupWakeRequested
                            terminalTemporaryWriteCleanupWakeRequested = false
                            targets to requested
                        }
                    if (cleanupRequested || retryPending) {
                        val cleanupResult = try {
                            ManagedDownloadStorage
                                .cleanupPersistedTerminalTemporaryWriteArtifacts(appContext)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            NPLogger.w(
                                TAG,
                                "最终发布后临时写入清理执行失败，保留下次恢复重试: " +
                                    "targets=${targets.size}, error=${error.message}",
                                error
                            )
                            ManagedDownloadStorage.StartupRecoveryResult(
                                failedCount = targets.size.coerceAtLeast(1)
                            )
                        }
                        val failedCount = cleanupResult.failedCount
                        val retryableFailedCount =
                            cleanupResult.immediatelyRetryableFailedCount
                        if (failedCount > 0) {
                            if (retryableFailedCount == 0) {
                                NPLogger.w(
                                    TAG,
                                    "最终发布后临时写入清理等待权限或目录恢复: " +
                                        "targets=${targets.size}, failed=$failedCount, " +
                                        "external=${cleanupResult.externalSignalRequiredCount}"
                                )
                                failedAttempt = 0
                            } else {
                                failedAttempt += 1
                                val retryDelayMs =
                                    TerminalTemporaryWriteCleanupRetryPolicy
                                        .delayMsForFailedAttempt(failedAttempt)
                                if (retryDelayMs != null) {
                                    NPLogger.w(
                                        TAG,
                                        "最终发布后临时写入清理未完全确认，延后重试: " +
                                            "targets=${targets.size}, " +
                                            "failed=$retryableFailedCount, " +
                                            "attempt=$failedAttempt/" +
                                            "${TerminalTemporaryWriteCleanupRetryPolicy.MAX_FAILED_ATTEMPTS}, " +
                                            "delayMs=$retryDelayMs"
                                    )
                                    retryPending = true
                                    delay(retryDelayMs)
                                    continue
                                }
                                NPLogger.w(
                                    TAG,
                                    "最终发布后临时写入清理重试次数已用尽，保留下次恢复: " +
                                        "targets=${targets.size}, " +
                                        "failed=$retryableFailedCount, " +
                                        "attempts=$failedAttempt"
                                )
                                failedAttempt = 0
                            }
                        } else {
                            failedAttempt = 0
                        }
                        retryPending = false
                    }
                    val hasMoreCleanupRequests =
                        terminalTemporaryWriteCleanupMutex.withLock {
                            if (
                                terminalTemporaryWriteCleanupBatch.isEmpty() &&
                                    !terminalTemporaryWriteCleanupWakeRequested
                            ) {
                                terminalTemporaryWriteCleanupJob = null
                                false
                            } else {
                                true
                            }
                        }
                    if (!hasMoreCleanupRequests) return@cleanupLoop
                }
            }
        }
    }
}

internal fun GlobalDownloadManager.isDurableCoreOperationState(state: String?): Boolean {
    return state == "CORE_COMMITTED" ||
        state == "ASSETS_ENRICHING" ||
        state == "FINALIZED" ||
        state == "DEGRADED_COMPLETE" ||
        state == METADATA_ACTION_REQUIRED_OPERATION_STATE ||
        state == "COMPLETED"
}

internal suspend fun GlobalDownloadManager.ensureCoreRecoveryOperation(
    context: Context,
    song: SongItem,
    operationId: String?,
    artifactLeaseId: String?,
    expectedAttemptId: Long?
): String {
    val preferredOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
    if (
        preferredOperationId != null &&
            DownloadExecutionRoomStore.state(context, preferredOperationId) != null
    ) {
        return preferredOperationId
    }
    val stableKey = song.stableKey()
    val recoveryOperationId = preferredOperationId
        ?: ("core-recovery-" + UUID.nameUUIDFromBytes(
            stableKey.toByteArray(Charsets.UTF_8)
        ))
    val request = DownloadExecutionRequest(
        operationId = recoveryOperationId,
        song = song,
        preserveStaging = false,
        requiresWifiNetwork = false,
        attemptId = expectedAttemptId,
        artifactLeaseId = artifactLeaseId ?: UUID.randomUUID().toString(),
        userInitiated = false
    )
    runCatching {
        DownloadExecutionRoomStore.upsert(
            context = context,
            request = request,
            state = "CORE_COMMITTED"
        )
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "补写 core 恢复 operation 失败，仍保留稳定恢复 ID: " +
                "song=${song.name}, operationId=$recoveryOperationId, " +
                "error=${error.message}",
            error
        )
    }
    return recoveryOperationId
}

internal suspend fun GlobalDownloadManager.cleanupOrphanedCompletedSidecars(
    context: Context,
    song: SongItem,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
) {
    runNonCancellableDownloadRollback {
        val references = listOfNotNull(
            sidecarReferences?.coverReference,
            sidecarReferences?.lyricReference,
            sidecarReferences?.translatedLyricReference,
            sidecarReferences?.romanizedLyricReference
        )
        if (references.isEmpty()) {
            return@runNonCancellableDownloadRollback
        }
        runCatching {
            ManagedDownloadStorage.deleteReferences(context.applicationContext, references)
        }.onFailure { error ->
            NPLogger.e(TAG, "清理孤立下载关联文件失败: ${song.name}, ${error.message}", error)
        }
    }
}

internal suspend fun GlobalDownloadManager.runDownloadedAudioMetadataPostProcessing(
    context: Context,
    audio: ManagedDownloadStorage.StoredEntry,
    song: SongItem,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
    traceToken: DownloadOperationTraceToken? = null
): MetadataPostProcessingResult {
    val songKey = song.stableKey()
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.ENRICHMENT_TAG_STARTED
    )
    try {
        repeat(METADATA_POST_PROCESSING_MAX_ATTEMPTS) { attempt ->
            if (isSongCancelled(songKey)) {
                return MetadataPostProcessingResult.RETRYABLE_FAILURE
            }
            val writeResult = runCatching {
                metadataPostProcessingSemaphore.withPermit {
                    val standardizedLyricEmbeddingEnabled =
                        isStandardizedLyricEmbeddingEnabled(context)
                    DownloadedAudioTagWriter.write(
                        context = context,
                        audio = audio,
                        song = song,
                        sidecarReferences = sidecarReferences,
                        standardizedLyricEmbeddingEnabled = standardizedLyricEmbeddingEnabled
                    )
                }
            }
            writeResult.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            val hasRemainingAttempts =
                attempt < METADATA_POST_PROCESSING_MAX_ATTEMPTS - 1 && !isSongCancelled(songKey)
            when (tagPostProcessingAction(writeResult.getOrNull(), hasRemainingAttempts)) {
                TagPostProcessingAction.FINALIZE_TAGGED -> {
                    return MetadataPostProcessingResult.EMBEDDED_VERIFIED
                }
                TagPostProcessingAction.RETRY -> {
                    val lastError = writeResult.exceptionOrNull()
                        ?: IllegalStateException(
                            "TagLib 未确认标签写入成功: outcome=${writeResult.getOrNull()}"
                        )
                    NPLogger.w(
                        TAG,
                        "元信息后处理失败，准备重试(第${attempt + 1}次): " +
                            "${audio.name}, stage=tag_post_process, " +
                            "outcome=${writeResult.getOrNull()}, " +
                            "error=${lastError.javaClass.simpleName}: ${lastError.message}",
                        lastError
                    )
                    delay(METADATA_POST_PROCESSING_RETRY_DELAY_MS * (attempt + 1))
                }
                TagPostProcessingAction.PRESERVE_UNFINALIZED -> {
                    val reason = writeResult.exceptionOrNull()?.message
                        ?: writeResult.getOrNull()?.name
                    NPLogger.w(
                        TAG,
                        "标签写入持续失败，保留音频等待收尾重试: " +
                            "${audio.name}, stage=tag_post_process, reason=$reason",
                        writeResult.exceptionOrNull()
                    )
                    return if (
                        writeResult.getOrNull() ==
                            DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER
                    ) {
                        MetadataPostProcessingResult.UNSUPPORTED_CONTAINER
                    } else {
                        MetadataPostProcessingResult.RETRYABLE_FAILURE
                    }
                }
            }
        }
        return MetadataPostProcessingResult.RETRYABLE_FAILURE
    } finally {
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.ENRICHMENT_TAG_FINISHED
        )
    }
}

internal suspend fun GlobalDownloadManager.isDownloadMetadataPostProcessingEnabled(context: Context): Boolean {
    val setting = AutoSettingsSchema.download.downloadMetadataPostProcessingEnabled
    return runCatching {
        context.applicationContext.autoSettingFlow(setting).first()
    }.getOrElse { error ->
        NPLogger.w(TAG, "读取元信息后处理设置失败，按默认值处理: ${error.message}")
        setting.defaultValue
    }
}

internal suspend fun GlobalDownloadManager.isStandardizedLyricEmbeddingEnabled(context: Context): Boolean {
    val setting = AutoSettingsSchema.download.standardizedLyricEmbeddingEnabled
    return runCatching {
        context.applicationContext.autoSettingFlow(setting).first()
    }.getOrElse { error ->
        NPLogger.w(TAG, "读取标准化歌词嵌入设置失败，按默认值处理: ${error.message}")
        setting.defaultValue
    }
}

internal suspend fun GlobalDownloadManager.handleCancelledCompletedDownload(
    context: Context,
    song: SongItem,
    songKey: String,
    storedAudio: ManagedDownloadStorage.StoredEntry?,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
    expectedAttemptId: Long? = null,
    operationId: String? = null,
    expectedArtifactLeaseId: String? = null
): Boolean {
    if (!isSongCancelled(songKey)) {
        return false
    }

    val currentState: String? = operationId?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { id -> DownloadExecutionRoomStore.state(context.applicationContext, id) }
    val durableCoreCommitted = isDurableCoreArtifactState(currentState) ||
        currentState == "COMMITTING"
    val storedMetadata = storedAudio?.let { audio ->
        readDownloadedMetadata(context.applicationContext, audio)
    }
    val preserveCommittedAudio = storedAudio != null &&
        (durableCoreCommitted || shouldPreserveAudioForCancellationRollback(
                audioIsPending = storedAudio.isPendingAudioWrite,
                metadataReadable = storedMetadata != null,
                downloadFinalized = storedMetadata?.downloadFinalized,
                artifactState = storedMetadata?.artifactState,
                metadataOperationId = storedMetadata?.operationId,
                operationId = operationId
            ))
    if (preserveCommittedAudio) {
        NPLogger.d(
            TAG,
            "core commit 后收到迟到取消，保留完整音频: " +
                "song=${song.name}, operationState=$currentState, " +
                "metadataState=${storedMetadata?.artifactState}"
        )
    } else {
        NPLogger.d(TAG, "下载最终入库阶段检测到取消，开始回滚: ${song.name}")
        runCatching {
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences,
                operationId = operationId
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "下载最终入库回滚失败: ${song.name}, ${error.message}", error)
        }
    }
    clearSongCancelled(songKey)
    if (preserveCommittedAudio) {
        runCatching {
            managedDownloadArtifactCoordinator.markDegradedComplete(
                context = context,
                song = song,
                expectedLeaseId = expectedArtifactLeaseId,
                errorCode = "CANCEL_AFTER_CORE_COMMIT"
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入 core audio 保留状态失败: ${error.message}")
        }
    } else {
        runCatching {
            if (expectedArtifactLeaseId != null) {
                managedDownloadArtifactCoordinator.settleLeaseAnyRoot(
                    context = context,
                    song = song,
                    expectedLeaseId = expectedArtifactLeaseId,
                    requestedState = ManagedDownloadArtifactState.CANCELLED,
                    errorCode = "USER_CANCELLED"
                )
            } else {
                managedDownloadArtifactCoordinator.markCancelled(
                    context = context,
                    song = song,
                    expectedLeaseId = null
                )
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载 artifact 取消状态失败: ${error.message}")
        }
    }
    expectedArtifactLeaseId?.let { leaseId ->
        managedDownloadArtifactLeases.remove(songKey, leaseId)
    }
    removeDownloadTask(
        songKey,
        expectedAttemptId = expectedAttemptId
    )
    forgetPendingDownloadQueueEntriesForOperation(
        context = context,
        songKey = songKey,
        operationId = operationId
    )
    return true
}

internal suspend fun GlobalDownloadManager.rollbackStaleCompletedDownload(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry?,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
    operationId: String? = null
) {
    if (storedAudio == null && (sidecarReferences?.isEmpty != false)) {
        return
    }
    runCatching {
        rollbackCancelledDownload(
            context = context,
            song = song,
            storedAudio = storedAudio,
            sidecarReferences = sidecarReferences,
            operationId = operationId
        )
    }.onFailure { error ->
        NPLogger.e(TAG, "过期下载结果回滚失败: ${song.name}, ${error.message}", error)
    }
}

internal suspend fun GlobalDownloadManager.cleanupDownloadArtifactsBeforeFreshStart(
    context: Context,
    song: SongItem,
    forceStorageRefresh: Boolean,
    preserveExistingAudio: Boolean = false
) {
    val appContext = context.applicationContext
    val songKey = song.stableKey()
    ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
    if (preserveExistingAudio) return
    cleanupUnfinalizedDownloadForRetry(
        context = appContext,
        song = song,
        forceStorageRefresh = forceStorageRefresh
    )
}

internal suspend fun GlobalDownloadManager.cleanupCancelledDownloadArtifacts(
    context: Context,
    song: SongItem,
    operationId: String? = null,
    keepCancellationOperation: Boolean = false,
    cleanupRootPendingArtifacts: Boolean = true
): Boolean {
    val appContext = context.applicationContext
    val songKey = song.stableKey()
    // 已有 operation 身份时只能由 planner 按 operation 删除，
    // 按 stableKey 扫描会误删替代请求刚写入的 staging 文件
    if (operationId.isNullOrBlank()) {
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
    }
    val rootCleanupSucceeded = if (cleanupRootPendingArtifacts) {
        cleanupCancelledPendingDownloadArtifacts(
            context = appContext,
            song = song,
            operationId = operationId
        )
    } else {
        true
    }
    if (!rootCleanupSucceeded) {
        NPLogger.w(
            TAG,
            "取消下载目录清理未确认，保留 Room 取消凭据等待恢复: " +
                "song=${song.name}, operationId=${operationId ?: "unknown"}"
        )
    }
    if (shouldPurgeCancelledDownloadOperation(
            keepCancellationOperation = keepCancellationOperation,
            cleanupSucceeded = rootCleanupSucceeded
        )
    ) {
        if (operationId.isNullOrBlank()) {
            DownloadExecutionRoomStore.purgeCancelled(appContext, setOf(songKey))
        } else {
            DownloadExecutionRoomStore.purgeCancelledOperationIds(
                context = appContext,
                operationIds = setOf(operationId)
            )
        }
    }
    return rootCleanupSucceeded
}

internal suspend fun GlobalDownloadManager.cleanupCancelledPendingDownloadArtifacts(
    context: Context,
    song: SongItem,
    operationId: String?
): Boolean {
    val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank) ?: return true
    val operationState = DownloadExecutionRoomStore.state(context, normalizedOperationId)
    val stopRequestedByUser = DownloadExecutionRoomStore.isStopped(
        context,
        normalizedOperationId
    )
    if (!shouldCleanupCancelledPendingArtifacts(operationState, stopRequestedByUser)) {
        NPLogger.d(
            TAG,
            "跳过非取消终态的 pending 清理: song=${song.name}, " +
                "operationId=$normalizedOperationId, state=$operationState, " +
                "stopped=$stopRequestedByUser"
        )
        return true
    }
    val deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(
        context.applicationContext
    ) ?: run {
        NPLogger.w(
            TAG,
            "目录迁移进行中，延后取消 pending 清理: " +
                "song=${song.name}, operationId=$normalizedOperationId"
        )
        return false
    }
    val result = try {
        ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts(
            context = context,
            stableKey = song.stableKey(),
            operationId = normalizedOperationId
        )
    } finally {
        deleteLease.close()
    }
    if (result.failedCount > 0) {
        NPLogger.w(
            TAG,
            "取消下载 pending 半成品未完全清理，保留下次恢复处理: " +
                "song=${song.name}, operationId=$normalizedOperationId, " +
                "failed=${result.failedCount}"
        )
        schedulePersistedTerminalTemporaryWriteCleanup(context)
    }
    return result.failedCount == 0
}

internal suspend fun GlobalDownloadManager.cleanupCancelledPendingDownloadArtifacts(
    context: Context,
    operationRequests: Collection<DownloadExecutionRequest>,
    cancellationGenerations: Map<String, Long?>,
    onProgress: ((completedItems: Int, totalItems: Int) -> Unit)? = null,
    protectedReferencesOut: MutableSet<String>? = null,
    boundedRoomWait: Boolean = false
): CancelledPendingCleanupOutcome {
    val operations = operationRequests
        .distinctBy(DownloadExecutionRequest::operationId)
        .mapNotNull { request ->
            val songKey = request.song.stableKey()
            if (!isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])) {
                return@mapNotNull null
            }
            val state = if (boundedRoomWait) {
                withDownloadClearRoomTimeout(
                    operation = "read cancellation operation state"
                ) {
                    DownloadExecutionRoomStore.state(context, request.operationId)
                }
            } else {
                DownloadExecutionRoomStore.state(context, request.operationId)
            }
            val stopRequestedByUser = if (boundedRoomWait) {
                withDownloadClearRoomTimeout(
                    operation = "read cancellation stop marker"
                ) {
                    DownloadExecutionRoomStore.isStopped(
                        context,
                        request.operationId
                    )
                }
            } else {
                DownloadExecutionRoomStore.isStopped(
                    context,
                    request.operationId
                )
            }
            if (!shouldCleanupCancelledPendingArtifacts(state, stopRequestedByUser)) {
                return@mapNotNull null
            }
            ManagedDownloadStorage.CancelledPendingDownloadOperation(
                stableKey = songKey,
                operationId = request.operationId
            )
    }
    if (operations.isEmpty()) {
        return CancelledPendingCleanupOutcome()
    }
    val result = ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts(
        context = context,
        operations = operations,
        onProgress = onProgress ?: { _, _ -> }
    )
    protectedReferencesOut?.addAll(result.protectedReferences)
    if (result.failedCount == 0) {
        return CancelledPendingCleanupOutcome()
    }
    val affectedSongKeys = result.failedStableKeys
    NPLogger.w(
        TAG,
        "批量取消 pending 半成品未完全清理，保持清空栅栏并重试: " +
            "operations=${operations.size}, failed=${result.failedCount}, " +
            "failedSongs=${affectedSongKeys.size}"
    )
    schedulePersistedTerminalTemporaryWriteCleanup(context)
    return CancelledPendingCleanupOutcome(
        residualSongKeys = affectedSongKeys,
        failedCount = result.failedCount
    )
}
