package moe.ouom.neriplayer.core.download.manager.commit

import moe.ouom.neriplayer.core.download.CompletedDownloadFinalizationAction
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.resolveCompletedDownloadFinalizationAction
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.openDownloadAdmissionTicketOrNull
import moe.ouom.neriplayer.core.download.manager.admission.scheduleStartupArtifactRecovery
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadArtifactRetryable
import moe.ouom.neriplayer.core.download.manager.runtime.resolveStoredAudio
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.shouldApplyTaskMutation
import moe.ouom.neriplayer.core.download.policy.shouldDemotePublishedAudioForFinalization
import android.content.Context
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.recovery.isArtifactRecoveryAllowed
import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey


internal suspend fun GlobalDownloadManager.finalizeCompletedDownload(
    context: Context,
    song: SongItem,
    expectedAttemptId: Long? = null,
    operationId: String? = null,
    expectedArtifactLeaseId: String? = null,
    storedAudioHint: ManagedDownloadStorage.StoredEntry? = null,
    allowMissingTask: Boolean = false,
    directoryMutationLeaseOwned: Boolean = false,
    directoryUri: String? = null,
    admissionTicket: Long? = null,
    admissionAlreadyHeld: Boolean = false,
    expeditedAssetEnrichment: Boolean = false
) {
    val appContext = context.applicationContext
    if (!admissionAlreadyHeld) {
        val effectiveAdmissionTicket = admissionTicket
            ?: openDownloadAdmissionTicketOrNull(appContext)
        if (effectiveAdmissionTicket == null) {
            NPLogger.d(
                TAG,
                "下载清空期间跳过完成收尾: song=${song.name}, operationId=$operationId"
            )
            return
        }
        val admitted = admitDownloadMutation(
            context = appContext,
            admissionTicket = effectiveAdmissionTicket,
            stableKey = song.stableKey(),
            operationId = operationId
        ) {
            finalizeCompletedDownload(
                context = appContext,
                song = song,
                expectedAttemptId = expectedAttemptId,
                operationId = operationId,
                expectedArtifactLeaseId = expectedArtifactLeaseId,
                storedAudioHint = storedAudioHint,
                allowMissingTask = allowMissingTask,
                directoryMutationLeaseOwned = directoryMutationLeaseOwned,
                directoryUri = directoryUri,
                admissionTicket = effectiveAdmissionTicket,
                admissionAlreadyHeld = true,
                expeditedAssetEnrichment = expeditedAssetEnrichment
            )
        }
        if (!admitted) {
            NPLogger.d(
                TAG,
                "下载清空代次已失效，跳过完成收尾: " +
                    "song=${song.name}, operationId=$operationId"
            )
        }
        return
    }
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
            "完成收尾准入票据已失效，保留持久凭据: " +
                "song=${song.name}, operationId=$operationId"
        )
        return
    }
    val songKey = song.stableKey()
    if (!DownloadExecutionRoomStore.isArtifactRecoveryAllowed(appContext, operationId)) {
        NPLogger.d(TAG, "持久下载状态禁止自动收尾: operationId=$operationId")
        return
    }
    val sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
    val currentTask = taskStore.findTask(songKey)
    if (
        (currentTask == null && !allowMissingTask) ||
        (currentTask != null && !shouldApplyTaskMutation(currentTask, expectedAttemptId))
    ) {
        NPLogger.d(
            TAG,
            "忽略过期下载完成回调: song=${song.name}, expectedAttemptId=$expectedAttemptId, currentAttemptId=${currentTask?.attemptId}"
        )
        rollbackStaleCompletedDownload(
            context = context,
            song = song,
            // 过期回调不能认领当前内存桥, 它可能已经属于更新后的下载
            storedAudio = storedAudioHint,
            sidecarReferences = sidecarReferences,
            operationId = operationId
        )
        storedAudioHint?.let { staleAudio ->
            AudioDownloadManager.releaseCompletedAudioReference(
                songKey = songKey,
                expectedAudio = staleAudio
            )
        }
        return
    }
    // core 音频已通过完整性校验，完成回调开始后的收尾阶段仍需保留引用供首播复用
    val completedAudio = AudioDownloadManager.peekCompletedAudioReference(song)
    if (expectedArtifactLeaseId != null) {
        runCatching {
            managedDownloadArtifactCoordinator.markCommitting(
                context = context,
                song = song,
                expectedLeaseId = expectedArtifactLeaseId
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "更新下载 artifact 提交状态失败: ${error.message}")
        }
    }
    val storedAudio = storedAudioHint
        ?: completedAudio
        ?: resolveStoredAudio(context, song)
        ?: ManagedDownloadStorage.findDownloadedAudio(context, song, forceRefresh = true)
    when (
        resolveCompletedDownloadFinalizationAction(
            hasStoredAudio = storedAudio != null,
            cancelled = isSongCancelled(songKey)
        )
    ) {
        CompletedDownloadFinalizationAction.ROLLBACK_CANCELLED -> {
            handleCancelledCompletedDownload(
                context = context,
                song = song,
                songKey = songKey,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences,
                expectedAttemptId = expectedAttemptId,
                operationId = operationId,
                expectedArtifactLeaseId = expectedArtifactLeaseId
            )
            return
        }
        CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO -> {
            NPLogger.w(TAG, "下载完成但暂未找到音频引用，保留任务等待自动恢复: ${song.name}")
            updateTaskStatus(
                songKey,
                DownloadStatus.QUEUED,
                expectedAttemptId = expectedAttemptId
            )
            val operationRetryPersisted = operationId?.let { id ->
                try {
                    DownloadExecutionRoomStore.updateState(
                        context = appContext,
                        operationId = id,
                        state = "RETRYABLE",
                        errorCode = "AUDIO_REFERENCE_MISSING"
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "持久化缺失音频引用重试状态失败，保留 artifact 恢复: " +
                            "song=${song.name}, operationId=$id, error=${error.message}",
                        error
                    )
                    false
                }
            } ?: false
            markDownloadArtifactRetryable(
                context = context,
                song = song,
                leaseId = expectedArtifactLeaseId,
                errorCode = "AUDIO_REFERENCE_MISSING"
            )
            scheduleCatalogReconcile(context, forceRefresh = true)
            scheduleStartupArtifactRecovery(appContext)
            val pumpScheduled = wakeDownloadExecutionPump(
                context = appContext,
                reason = "completed_audio_reference_missing"
            )
            NPLogger.d(
                TAG,
                "缺失音频引用已转入自动恢复: song=${song.name}, " +
                    "operationId=$operationId, persisted=$operationRetryPersisted, " +
                    "pump=$pumpScheduled"
            )
            return
        }
        CompletedDownloadFinalizationAction.COMPLETE -> Unit
    }
    val resolvedStoredAudio = storedAudio ?: run {
        return
    }

    if (
        handleCancelledCompletedDownload(
            context = context,
            song = song,
            songKey = songKey,
            storedAudio = resolvedStoredAudio,
            sidecarReferences = sidecarReferences,
            expectedAttemptId = expectedAttemptId,
            operationId = operationId,
            expectedArtifactLeaseId = expectedArtifactLeaseId
        )
    ) {
        AudioDownloadManager.releaseCompletedAudioReference(
            songKey = songKey,
            expectedAudio = completedAudio ?: resolvedStoredAudio
        )
        return
    }

    val directoryCommitOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: "untracked-finalization:$songKey"
    val directoryCommitLease = if (directoryMutationLeaseOwned) {
        null
    } else {
        ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
            context = context,
            operationId = directoryCommitOperationId
        ) ?: throw DownloadStorageMutationDeferredException(directoryCommitOperationId)
    }
    try {
    val existingMetadata = readDownloadedMetadata(
        context = context.applicationContext,
        audio = resolvedStoredAudio
    )
    val audioForFinalization = if (
        !resolvedStoredAudio.isPendingAudioWrite &&
            shouldDemotePublishedAudioForFinalization(existingMetadata)
    ) {
        ManagedDownloadStorage.demotePublishedAudioForFinalization(
            context = context.applicationContext,
            audio = resolvedStoredAudio,
            expectedMetadataFinalized = existingMetadata?.downloadFinalized
        ) ?: run {
            NPLogger.w(
                TAG,
                "未最终化音频无法安全回退为 pending，保留等待重试: " +
                    "song=${song.name}, file=${resolvedStoredAudio.name}"
            )
            return
        }
    } else {
        resolvedStoredAudio
    }
    val finalizationMetadata = if (audioForFinalization === resolvedStoredAudio) {
        existingMetadata
    } else {
        readDownloadedMetadata(
            context = context.applicationContext,
            audio = audioForFinalization
        )
    }
    // v15 元数据只作为输入，所有完成任务统一进入核心提交和增强流程
    completeCoreDownloadAndEnqueueEnrichment(
        context = context.applicationContext,
        song = song,
        storedAudio = audioForFinalization,
        existingMetadata = finalizationMetadata,
        artifactLeaseId = expectedArtifactLeaseId,
        expectedAttemptId = expectedAttemptId,
        operationId = operationId,
        allowMissingTask = allowMissingTask,
        directoryMutationLeaseOwned = directoryMutationLeaseOwned,
        directoryUri = directoryUri,
        admissionTicket = admissionTicket,
        expeditedAssetEnrichment = expeditedAssetEnrichment
    )
    } finally {
        directoryCommitLease?.close()
    }
}
