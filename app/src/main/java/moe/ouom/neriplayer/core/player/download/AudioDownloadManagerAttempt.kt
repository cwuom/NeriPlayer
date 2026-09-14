package moe.ouom.neriplayer.core.player.download
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadAttemptFailureAction
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadStage

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.ResolvedDownloadSource
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadTransportKind
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadedPayloadSummary
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadCoreCommitTracker
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadExecutionAttemptState
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.PreparedDownloadAttempt
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.CoreCommittedAudio
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadCoreCommitPhase
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.clearSongCancelled
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.boundManagedDownloadFileName
import moe.ouom.neriplayer.core.download.resource.DownloadTransferPermitRegistry
import moe.ouom.neriplayer.core.download.resource.DownloadStorageSpaceDeferredException
import moe.ouom.neriplayer.core.download.resource.classifyDownloadStorageSpaceFailure
import moe.ouom.neriplayer.core.download.resource.isDefinitive
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.DownloadTransferAdmissionDeferredException
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.shouldRollbackCancelledAudio
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import moe.ouom.neriplayer.data.traffic.hasConfirmedInternetAccess
import okhttp3.Request
import java.io.File
import java.io.IOException

internal suspend fun AudioDownloadManager.handleDownloadSongFailure(
    context: Context,
    song: SongItem,
    songKey: String,
    effectiveOperationId: String,
    attemptId: Long?,
    state: DownloadExecutionAttemptState,
    error: Exception
): Nothing {
    if (
        error is DownloadStorageMutationDeferredException ||
            error is DownloadStorageSpaceDeferredException ||
            error is DownloadTransferAdmissionDeferredException
    ) {
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = effectiveOperationId
        )
        clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
        throw error
    }
    if (
        error is java.util.concurrent.CancellationException ||
            _isCancelled.value ||
            shouldPreserveArtifactsForNetworkPolicy(songKey) ||
            GlobalDownloadManager.isSongCancelled(songKey)
    ) {
        handleDownloadSongCancellation(
            context = context,
            song = song,
            songKey = songKey,
            effectiveOperationId = effectiveOperationId,
            attemptId = attemptId,
            state = state,
            error = error
        )
    }
    NPLogger.e(
        TAG,
        "下载失败: ${song.name}, 错误: ${error.javaClass.simpleName} - ${error.message}",
        error
    )
    deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
    clearVisibleProgressForSong(
        songKey = songKey,
        expectedAttemptId = attemptId,
        expectedOperationId = effectiveOperationId
    )
    clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
    clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
    throw error
}

internal suspend fun AudioDownloadManager.handleDownloadSongCancellation(
    context: Context,
    song: SongItem,
    songKey: String,
    effectiveOperationId: String,
    attemptId: Long?,
    state: DownloadExecutionAttemptState,
    error: Exception
): Nothing {
    val partialSidecarReferences = consumePartialSidecarReferences(
        songKey,
        operationId = effectiveOperationId
    )
        ?.retainCreatedOnly()
    NPLogger.d(TAG, "下载已取消: ${song.name}")
    val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
    val preserveCancellationArtifacts =
        shouldPreserveWorkingArtifactsAfterCancellation(
            cancellation = error is java.util.concurrent.CancellationException,
            allDownloadsCancelled = _isCancelled.value,
            songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
            networkPolicyPaused = preserveArtifacts
        )
    val clearFenceOwnsCancellationCleanup = isCancellationCleanupOwnedByClearFence(
        songKey = songKey,
        operationId = effectiveOperationId,
        preserveCancellationArtifacts = preserveCancellationArtifacts
    )
    if (
        !preserveCancellationArtifacts &&
        !clearFenceOwnsCancellationCleanup &&
        shouldRollbackCancelledAudio(state.coreCommitTracker.phase)
    ) {
        if (!state.cancellationCleanupAttempted) {
            state.cancellationCleanupAttempted = true
            val cleanupResult = cleanupCancelledPendingArtifactsWithLease(
                context = context,
                songKey = songKey,
                operationId = effectiveOperationId
            )
            if (cleanupResult.failedCount > 0) {
                NPLogger.w(
                    TAG,
                    "取消下载 pending 半成品暂未完全清理，保留恢复凭据: " +
                        "song=${song.name}, failed=${cleanupResult.failedCount}"
                )
            }
        }
        if (state.storedAudio != null || partialSidecarReferences?.isEmpty == false) {
            runCatching {
                NPLogger.d(
                    TAG,
                    "下载取消后回滚半成品: song=${song.name}, " +
                        "audio=${state.storedAudio?.reference}, " +
                        "sidecars=$partialSidecarReferences"
                )
                GlobalDownloadManager.rollbackCancelledDownload(
                    context = context,
                    song = song,
                    storedAudio = state.storedAudio,
                    sidecarReferences = partialSidecarReferences,
                    operationId = effectiveOperationId
                )
                state.storedAudio = null
            }.onFailure { rollbackError ->
                NPLogger.e(
                    TAG,
                    "回滚已取消下载失败: ${song.name}, ${rollbackError.message}",
                    rollbackError
                )
            }
        }
    }
    if (!preserveCancellationArtifacts) {
        deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
    }
    clearVisibleProgressForSong(
        songKey = songKey,
        expectedAttemptId = attemptId,
        expectedOperationId = effectiveOperationId
    )
    if (!preserveCancellationArtifacts && !clearFenceOwnsCancellationCleanup) {
        clearSongCancelled(songKey)
    }
    clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
    clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
    throw java.util.concurrent.CancellationException(
        if (preserveCancellationArtifacts) {
            "Download cancellation deferred for recovery"
        } else {
            "Download cancelled"
        }
    )
}

internal suspend fun AudioDownloadManager.runDownloadAttempts(
    context: Context,
    song: SongItem,
    batchSessionId: Long?,
    attemptId: Long?,
    effectiveOperationId: String,
    downloadAudioQuality: DownloadAudioQualitySelection,
    isYouTubeMusic: Boolean,
    isBili: Boolean,
    state: DownloadExecutionAttemptState
) {
    val songKey = song.stableKey()
    while (true) {
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "prepare",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        publishStageProgress(
            songId = song.id,
            songKey = songKey,
            fileName = state.activeWorkingFileName
                ?: ManagedDownloadStorage.buildDisplayBaseName(song),
            stage = DownloadStage.RESOLVING_SOURCE,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            bytesRead = resolveWorkingFileBytes(state.tempFile),
            totalBytes = progressStore.currentProgress()
                ?.takeIf { it.songKey == songKey }
                ?.totalBytes
                ?: 0L
        )
        try {
            if (
                executeDownloadAttempt(
                    context = context,
                    song = song,
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    effectiveOperationId = effectiveOperationId,
                    downloadAudioQuality = downloadAudioQuality,
                    isYouTubeMusic = isYouTubeMusic,
                    isBili = isBili,
                    state = state
                )
            ) {
                return
            }
        } catch (error: Exception) {
            when (
                handleDownloadAttemptFailure(
                    context = context,
                    song = song,
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    effectiveOperationId = effectiveOperationId,
                    isYouTubeMusic = isYouTubeMusic,
                    state = state,
                    error = error
                )
            ) {
                DownloadAttemptFailureAction.RETRY -> Unit
            }
        }
    }
}

internal suspend fun AudioDownloadManager.executeDownloadAttempt(
    context: Context,
    song: SongItem,
    batchSessionId: Long?,
    attemptId: Long?,
    effectiveOperationId: String,
    downloadAudioQuality: DownloadAudioQualitySelection,
    isYouTubeMusic: Boolean,
    isBili: Boolean,
    state: DownloadExecutionAttemptState
): Boolean {
    val songKey = song.stableKey()
    val traceToken = DownloadOperationTrace.begin(
        operationId = effectiveOperationId,
        attemptId = attemptId
    )
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.SOURCE_RESOLVE_STARTED
    )
    val resolved = try {
        resolveDownloadSourceForAttempt(
            song = song,
            downloadAudioQuality = downloadAudioQuality,
            isYouTubeMusic = isYouTubeMusic,
            isBili = isBili,
            state = state
        )
    } finally {
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.SOURCE_RESOLVE_FINISHED
        )
    }
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "source_resolved",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = effectiveOperationId
    )
    if (resolved == null) {
        val hasConfirmedInternetAccess = context.hasConfirmedInternetAccess()
        if (hasConfirmedInternetAccess) {
            state.confirmedSourceMissCount++
        }
        if (shouldStopRetryingMissingDownloadSource(
                confirmedMissCount = state.confirmedSourceMissCount,
                hasConfirmedInternetAccess = hasConfirmedInternetAccess
            )
        ) {
            throw DownloadSourceUnavailableException(
                "download source remained unavailable after " +
                    "${state.confirmedSourceMissCount} confirmed attempts: ${song.name}"
            )
        }
        if (state.attemptNumber >= TRANSIENT_DOWNLOAD_MAX_ATTEMPTS) {
            throw IOException(context.getString(R.string.download_no_url, song.name))
        }
        val retryDelayMs = resolveTransientDownloadRetryDelayMs(state.attemptNumber)
        publishRetryWaitingProgress(
            songId = song.id,
            songKey = songKey,
            fileName = state.activeWorkingFileName
                ?: ManagedDownloadStorage.buildDisplayBaseName(song),
            bytesRead = resolveWorkingFileBytes(state.tempFile),
            totalBytes = progressStore.currentProgress()
                ?.takeIf { it.songKey == songKey }
                ?.totalBytes
                ?: 0L,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        NPLogger.w(
            TAG,
            "下载链接暂时不可用，准备重试: song=${song.name}, " +
                "confirmedMiss=${state.confirmedSourceMissCount}/" +
                "${AudioDownloadTransferPolicy.SOURCE_RESOLVE_MAX_CONFIRMED_MISSES}, " +
                "attempt=${state.attemptNumber}/$TRANSIENT_DOWNLOAD_MAX_ATTEMPTS, " +
                "confirmedInternet=$hasConfirmedInternetAccess"
        )
        if (isYouTubeMusic) {
            state.forceRefreshYouTubeSource = true
        }
        evictDownloadConnections()
        waitForRetryOrCancellation(
            context = context,
            songKey = songKey,
            delayMs = retryDelayMs,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        state.attemptNumber++
        return false
    }
    state.confirmedSourceMissCount = 0
    state.forceRefreshYouTubeSource = false
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.PREPARE_STARTED
    )
    val prepared = try {
        prepareDownloadAttempt(
            context = context,
            song = song,
            resolved = resolved,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            effectiveOperationId = effectiveOperationId,
            state = state
        )
    } finally {
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.PREPARE_FINISHED
        )
    }
    transferAndCommitDownloadAttempt(
        context = context,
        songKey = songKey,
        prepared = prepared,
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        effectiveOperationId = effectiveOperationId,
        state = state
    )
    return true
}

internal suspend fun AudioDownloadManager.resolveDownloadSourceForAttempt(
    song: SongItem,
    downloadAudioQuality: DownloadAudioQualitySelection,
    isYouTubeMusic: Boolean,
    isBili: Boolean,
    state: DownloadExecutionAttemptState
): ResolvedDownloadSource? {
    return sourceResolveSemaphore.withPermit {
        when {
            isYouTubeMusic -> resolveYouTubeMusic(
                song = song,
                preferredQuality = downloadAudioQuality.youtubeQuality,
                forceRefresh = state.forceRefreshYouTubeSource,
                avoidDirect = state.avoidYouTubeDirectSource
            )
            isBili -> resolveBili(
                song = song,
                preferredQuality = downloadAudioQuality.biliQuality
            )
            else -> resolveNetease(
                songId = song.id,
                preferredQuality = downloadAudioQuality.neteaseQuality
            )
        }
    }
}

internal suspend fun AudioDownloadManager.prepareDownloadAttempt(
    context: Context,
    song: SongItem,
    resolved: ResolvedDownloadSource,
    batchSessionId: Long?,
    attemptId: Long?,
    effectiveOperationId: String,
    state: DownloadExecutionAttemptState
): PreparedDownloadAttempt {
    val songKey = song.stableKey()
    val workingSong = if (
        song.durationMs == 0L &&
            resolved.durationMs != null &&
            resolved.durationMs > 0L
    ) {
        song.copy(durationMs = resolved.durationMs)
    } else {
        song
    }
    val url = resolved.url
    val mime = resolved.mimeType
    val extGuess = resolved.fileExtensionHint
    val ext = when {
        resolved.streamType == YouTubePlayableStreamType.HLS ->
            resolved.fileExtensionHint ?: "aac"
        !mime.isNullOrBlank() -> mimeToExt(mime)
        else -> extFromUrl(url) ?: extGuess
    }
    val baseName = ManagedDownloadStorage.buildDisplayBaseName(song)
    val fileName = boundManagedDownloadFileName(
        if (ext.isNullOrBlank()) baseName else "$baseName.$ext"
    )
    resolved.contentLength?.let { sourceExpectedBytes ->
        NPLogger.d(
            TAG,
            "下载来源长度提示: file=$fileName, sourceExpected=$sourceExpectedBytes"
        )
    }
    val requestBuilder = Request.Builder().url(url)
    if (song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)) {
        val cookieMap = AppContainer.biliCookieRepo.getCookiesOnce()
        val cookieHeader = cookieMap.entries.joinToString("; ") { (key, value) ->
            "$key=$value"
        }
        requestBuilder
            .header("User-Agent", BILI_UA)
            .header("Referer", BILI_REFERER)
            .apply {
                if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
            }
    } else if (isYouTubeMusicSong(song)) {
        val auth = AppContainer.youtubeAuthRepo.getAuthOnce().normalized()
        auth.buildYouTubeStreamRequestHeaders(
            refererOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
            streamUrl = url
        ).forEach { (name, value) -> requestBuilder.header(name, value) }
        // 直链统一交给分块传输，避免整档 Range 触发 googlevideo 风控
    }
    val request = requestBuilder.build()
    val transportKind = resolveDownloadTransportKind(
        streamType = resolved.streamType,
        request = request
    )
    publishStageProgress(
        songId = workingSong.id,
        songKey = songKey,
        fileName = fileName,
        stage = DownloadStage.PREPARING_STORAGE,
        attemptId = attemptId,
        operationId = effectiveOperationId,
        bytesRead = resolveWorkingFileBytes(state.tempFile),
        totalBytes = resolved.contentLength ?: 0L
    )
    if (state.tempFile == null) {
        state.tempFile = ManagedDownloadStorage.findWorkingFileForResume(
            context = context,
            songKey = songKey
        )
        state.tempFile?.let { file ->
            NPLogger.d(
                TAG,
                "复用 operation staging 断点: song=${workingSong.name}, file=${file.name}"
            )
        }
    }
    val workingFile = withNetworkPolicyMutationPermit(
        songKey = songKey,
        stage = "prepare_working_file",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = effectiveOperationId
    ) {
        if (
            state.tempFile == null ||
                (state.activeWorkingFileName != null &&
                    state.activeWorkingFileName != fileName) ||
                (state.activeTransportKind != null &&
                    state.activeTransportKind != transportKind)
        ) {
            deleteWorkingFile(state.tempFile)
            state.tempFile = ManagedDownloadStorage.createWorkingFile(
                context = context,
                songKey = songKey,
                fileName = fileName,
                operationId = effectiveOperationId
            )
        }
        state.activeWorkingFileName = fileName
        state.activeTransportKind = transportKind
        val currentWorkingFile = requireNotNull(state.tempFile)
        state.resumeMetadataAvailable = ManagedDownloadStorage.saveWorkingResumeMetadata(
            workingFile = currentWorkingFile,
            song = workingSong,
            operationId = effectiveOperationId
        )
        if (!state.resumeMetadataAvailable) {
            NPLogger.w(
                TAG,
                "续传元数据不可用，当前下载继续但不宣称可无损恢复: " +
                    "file=${currentWorkingFile.name}, operationId=$effectiveOperationId"
            )
        }
        currentWorkingFile
    }
    reconcileWorkingFileWithDurableCheckpoint(
        context = context,
        songKey = songKey,
        workingFile = workingFile,
        transportKind = transportKind,
        operationId = effectiveOperationId,
        attemptId = attemptId,
        batchSessionId = batchSessionId
    )
    return PreparedDownloadAttempt(
        resolved = resolved,
        workingSong = workingSong,
        request = request,
        transportKind = transportKind,
        fileName = fileName,
        mimeType = mime,
        workingFile = workingFile
    )
}

internal suspend fun AudioDownloadManager.reconcileWorkingFileWithDurableCheckpoint(
    context: Context,
    songKey: String,
    workingFile: File,
    transportKind: DownloadTransportKind,
    operationId: String,
    attemptId: Long?,
    batchSessionId: Long?
) {
    // HLS 已经有带摘要的分段检查点，不能让普通字节检查点覆盖它
    if (transportKind == DownloadTransportKind.HLS || attemptId == null) {
        return
    }
    val checkpoint = DownloadExecutionRoomStore.readProgressCheckpoint(
        context = context.applicationContext,
        operationId = operationId,
        stableKey = songKey,
        attemptId = attemptId
    ) ?: return
    val safeBytes = checkpoint.bytesWritten.coerceAtLeast(0L)
    val fileBytes = resolveWorkingFileBytes(workingFile)
    if (fileBytes <= safeBytes) {
        return
    }
    withNetworkPolicyMutationPermit(
        songKey = songKey,
        stage = "truncate_to_durable_checkpoint",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = operationId
    ) {
        truncateWorkingFile(workingFile, safeBytes)
    }
    NPLogger.w(
        TAG,
        "工作文件尾部超过安全检查点，已截断后续传: " +
            "file=${workingFile.name}, disk=$fileBytes, safe=$safeBytes, " +
            "operationId=$operationId"
    )
}

internal suspend fun AudioDownloadManager.transferAndCommitDownloadAttempt(
    context: Context,
    songKey: String,
    prepared: PreparedDownloadAttempt,
    batchSessionId: Long?,
    attemptId: Long?,
    effectiveOperationId: String,
    state: DownloadExecutionAttemptState
) {
    val traceToken = DownloadOperationTrace.begin(
        operationId = effectiveOperationId,
        attemptId = attemptId
    )
    // 只有确认即将开始新的网络传输后才清理旧桥接
    clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
    publishStageProgress(
        songId = prepared.workingSong.id,
        songKey = songKey,
        fileName = prepared.fileName,
        stage = DownloadStage.TRANSFERRING,
        attemptId = attemptId,
        operationId = effectiveOperationId,
        bytesRead = resolveWorkingFileBytes(prepared.workingFile),
        totalBytes = prepared.resolved.contentLength ?: 0L
    )
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.NETWORK_PERMIT_REQUESTED
    )
    val ownerKey = DownloadTransferPermitRegistry.ownerKey(
        operationId = effectiveOperationId,
        attemptId = attemptId,
        stableKey = prepared.workingSong.stableKey()
    )
    var coreTransferReleaseDispatched = false
    val committedAudio = withTransferCyclePermit(
        context = context,
        ownerKey = ownerKey,
        traceToken = traceToken,
        operationId = effectiveOperationId,
        attemptId = attemptId
    ) { permit, markNetworkFinished, transferOwnerToken ->
        val downloadedPayload = transferWatchdog.run(permit) {
            downloadPayloadForTransport(
                transportKind = prepared.transportKind,
                resolved = prepared.resolved,
                request = prepared.request,
                workingFile = prepared.workingFile,
                fileName = prepared.fileName,
                workingSong = prepared.workingSong,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                effectiveOperationId = effectiveOperationId,
                transferGeneration = permit.generation
            )
        }
        markNetworkFinished()
        state.resumeMetadataAvailable = state.resumeMetadataAvailable &&
            downloadedPayload.resumeMetadataAvailable
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.CORE_COMMIT_REQUESTED
        )
        val committedAudio = coreCommitSemaphore.withPermit {
            // 网络阶段可以并行，最终文件提交必须和同曲目的新代次串行
            GlobalDownloadManager.withSongExecutionLock(songKey) {
                // 把 semaphore 和同曲目提交锁的等待合并为一个 Core admission 段
                DownloadOperationTrace.mark(
                    traceToken,
                    DownloadOperationTracePhase.CORE_COMMIT_GRANTED
                )
                DownloadOperationTrace.mark(
                    traceToken,
                    DownloadOperationTracePhase.CORE_COMMIT_STARTED
                )
                try {
                    ensureSongDownloadNotCancelled(
                        songKey = songKey,
                        stage = "core_commit_lock",
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        operationId = effectiveOperationId
                    )
                    finalizeDownloadedAudio(
                        context = context,
                        songKey = songKey,
                        workingSong = prepared.workingSong,
                        fileName = prepared.fileName,
                        mimeType = prepared.mimeType,
                        workingFile = prepared.workingFile,
                        payloadSummary = downloadedPayload,
                        effectiveOperationId = effectiveOperationId,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        coreCommitTracker = state.coreCommitTracker
                    )
                } finally {
                    DownloadOperationTrace.mark(
                        traceToken,
                        DownloadOperationTracePhase.CORE_COMMIT_FINISHED
                    )
                }
            }
        }
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.CORE_COMMITTED
        )
        val committedWithOwner = committedAudio.copy(transferOwnerToken = transferOwnerToken)
        if (committedWithOwner.operationCoreCommitted) {
            // permit 仍在本次传输的 finally 之前，先释放 host owner 并唤醒补位，
            // 避免下一个 operation 抢到网络 permit 后又被旧 owner 拒绝
            coreTransferReleaseDispatched = GlobalDownloadManager.wakeDownloadExecutionPumpAfterCoreCommit(
                context = context,
                operationId = effectiveOperationId,
                attemptId = attemptId,
                transferOwnerToken = transferOwnerToken
            )
        }
        committedWithOwner
    }
    // 只有 operation journal 的 CAS 成功后才释放宿主传输槽位；pending
    // 音频已经落盘但 journal 失败时必须留给恢复路径收敛
    if (!committedAudio.operationCoreCommitted) {
        NPLogger.w(
            TAG,
            "Core Commit journal 未确认，暂不释放传输槽位: " +
                "operationId=$effectiveOperationId, attemptId=$attemptId"
        )
    } else if (!coreTransferReleaseDispatched) {
        coreTransferReleaseDispatched = GlobalDownloadManager.wakeDownloadExecutionPumpAfterCoreCommit(
            context = context,
            operationId = effectiveOperationId,
            attemptId = attemptId,
            transferOwnerToken = committedAudio.transferOwnerToken
        )
    }
    state.storedAudio = committedAudio.audio
    publishStageProgress(
        songId = prepared.workingSong.id,
        songKey = songKey,
        fileName = committedAudio.audio.name,
        stage = DownloadStage.ASSETS_ENRICHING,
        bytesRead = committedAudio.transferredBytes,
        totalBytes = committedAudio.transferredBytes,
        attemptId = attemptId,
        operationId = effectiveOperationId
    )
    NPLogger.d(
        TAG,
        "音频落盘完成，sidecar 转入后台整理: " +
            "song=${prepared.workingSong.name}, audioFile=${committedAudio.audio.name}"
    )
    rememberCompletedAudioReference(
        song = prepared.workingSong,
        storedAudio = committedAudio.audio,
        operationId = effectiveOperationId
    )
    clearVisibleProgressForSong(
        songKey = songKey,
        expectedAttemptId = attemptId,
        expectedOperationId = effectiveOperationId
    )
    clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
}

internal suspend fun AudioDownloadManager.handleDownloadAttemptFailure(
    context: Context,
    song: SongItem,
    batchSessionId: Long?,
    attemptId: Long?,
    effectiveOperationId: String,
    isYouTubeMusic: Boolean,
    state: DownloadExecutionAttemptState,
    error: Exception
): DownloadAttemptFailureAction {
    val songKey = song.stableKey()
    if (
        error is DownloadStorageMutationDeferredException ||
            error is DownloadTransferAdmissionDeferredException
    ) {
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = effectiveOperationId
        )
        clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
        throw error
    }
    val storageFailureKind = classifyDownloadStorageSpaceFailure(error)
    if (storageFailureKind != null) {
        publishRetryWaitingProgress(
            songId = song.id,
            songKey = songKey,
            fileName = state.activeWorkingFileName
                ?: ManagedDownloadStorage.buildDisplayBaseName(song),
            bytesRead = resolveWorkingFileBytes(state.tempFile),
            totalBytes = progressStore.currentProgress()
                ?.takeIf { it.songKey == songKey }
                ?.totalBytes
                ?: 0L,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        if (storageFailureKind.isDefinitive) {
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = effectiveOperationId
            )
            // 只有已知真实容量耗尽或 Provider 返回 ENOSPC 才进入全局取消，
            // 进程内预留竞争和空间探测失败继续保留工作文件重试
            throw DownloadStorageSpaceDeferredException(
                operationId = effectiveOperationId,
                failureKind = storageFailureKind,
                cancelAllDownloads = true
            )
        }
        NPLogger.w(
            TAG,
            "下载空间检查暂不可用，保留工作文件短暂重试: " +
                "song=${song.name}, operationId=$effectiveOperationId, " +
                "kind=$storageFailureKind"
        )
        waitForRetryOrCancellation(
            context = context,
            songKey = songKey,
            delayMs = STORAGE_SPACE_CONTENTION_RETRY_DELAY_MS,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        return DownloadAttemptFailureAction.RETRY
    }
    val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
    val preserveCancellationArtifacts =
        shouldPreserveWorkingArtifactsAfterCancellation(
            cancellation = error is java.util.concurrent.CancellationException,
            allDownloadsCancelled = _isCancelled.value,
            songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
            networkPolicyPaused = preserveArtifacts
        )
    val clearFenceOwnsCancellationCleanup = isCancellationCleanupOwnedByClearFence(
        songKey = songKey,
        operationId = effectiveOperationId,
        preserveCancellationArtifacts = preserveCancellationArtifacts
    )
    if (
        error is java.util.concurrent.CancellationException ||
            _isCancelled.value ||
            preserveArtifacts ||
            GlobalDownloadManager.isSongCancelled(songKey)
    ) {
        val partialSidecarReferences = consumePartialSidecarReferences(
            songKey,
            operationId = effectiveOperationId
        )
            ?.retainCreatedOnly()
        NPLogger.d(TAG, "下载已取消: ${song.name}")
        if (
            !preserveCancellationArtifacts &&
                !clearFenceOwnsCancellationCleanup &&
                shouldRollbackCancelledAudio(state.coreCommitTracker.phase)
        ) {
            state.cancellationCleanupAttempted = true
            val cleanupResult = cleanupCancelledPendingArtifactsWithLease(
                context = context,
                songKey = songKey,
                operationId = effectiveOperationId
            )
            if (cleanupResult.failedCount > 0) {
                NPLogger.w(
                    TAG,
                    "取消下载 pending 半成品暂未完全清理，保留恢复凭据: " +
                        "song=${song.name}, failed=${cleanupResult.failedCount}"
                )
            }
            if (state.storedAudio != null || partialSidecarReferences?.isEmpty == false) {
                runCatching {
                    NPLogger.d(
                        TAG,
                        "下载取消后回滚半成品: song=${song.name}, " +
                            "audio=${state.storedAudio?.reference}, " +
                            "sidecars=$partialSidecarReferences"
                    )
                    GlobalDownloadManager.rollbackCancelledDownload(
                        context = context,
                        song = song,
                        storedAudio = state.storedAudio,
                        sidecarReferences = partialSidecarReferences,
                        operationId = effectiveOperationId
                    )
                    state.storedAudio = null
                }.onFailure { rollbackError ->
                    NPLogger.e(
                        TAG,
                        "回滚已取消下载失败: ${song.name}, ${rollbackError.message}",
                        rollbackError
                    )
                }
            }
        }
        if (
            !preserveCancellationArtifacts &&
                deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
        ) {
            state.tempFile = null
        }
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = effectiveOperationId
        )
        if (!preserveCancellationArtifacts && !clearFenceOwnsCancellationCleanup) {
            clearSongCancelled(songKey)
        }
        clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
        throw java.util.concurrent.CancellationException(
            if (preserveCancellationArtifacts) {
                "Download cancellation deferred for recovery"
            } else {
                "Download cancelled"
            }
        )
    }
    clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
    if (
        state.storedAudio == null &&
            state.attemptNumber < TRANSIENT_DOWNLOAD_MAX_ATTEMPTS &&
            shouldRetryDownloadFailureForSource(error, isYouTubeMusic)
    ) {
        val partialBytes = resolveWorkingFileBytes(state.tempFile)
        val preservePartial = shouldPreservePartialDownloadForRetry(
            transportKind = state.activeTransportKind,
            existingBytes = partialBytes,
            hasHlsResumeState = hasHlsResumeState(state.tempFile)
        ) && state.resumeMetadataAvailable
        if (
            !preservePartial &&
                deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
        ) {
            state.tempFile = null
        }
        val retryDelayMs = resolveTransientDownloadRetryDelayMs(state.attemptNumber)
        publishRetryWaitingProgress(
            songId = song.id,
            songKey = songKey,
            fileName = state.activeWorkingFileName
                ?: ManagedDownloadStorage.buildDisplayBaseName(song),
            bytesRead = if (preservePartial) partialBytes else 0L,
            totalBytes = progressStore.currentProgress()
                ?.takeIf { it.songKey == songKey }
                ?.totalBytes
                ?: 0L,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        if (isYouTubeMusic && shouldRefreshYouTubeDownloadSourceOnFailure(error)) {
            state.forceRefreshYouTubeSource = true
            if (isForbiddenYouTubeDownloadFailure(error)) {
                state.avoidYouTubeDirectSource = true
            }
        }
        NPLogger.w(
            TAG,
            "下载遇到网络波动，准备重试(${state.attemptNumber}/$TRANSIENT_DOWNLOAD_MAX_ATTEMPTS): " +
                "${song.name}, refreshYouTubeSource=${state.forceRefreshYouTubeSource}, " +
                "${error.javaClass.simpleName} - ${error.message}"
        )
        evictDownloadConnections()
        waitForRetryOrCancellation(
            context = context,
            songKey = songKey,
            delayMs = retryDelayMs,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        state.attemptNumber++
        return DownloadAttemptFailureAction.RETRY
    }
    if (deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)) {
        state.tempFile = null
    }
    NPLogger.e(
        TAG,
        "下载失败: ${song.name}, 错误: ${error.javaClass.simpleName} - ${error.message}",
        error
    )
    throw error
}

internal suspend fun AudioDownloadManager.downloadPayloadForTransport(
    transportKind: DownloadTransportKind,
    resolved: ResolvedDownloadSource,
    request: Request,
    workingFile: File,
    fileName: String,
    workingSong: SongItem,
    batchSessionId: Long?,
    attemptId: Long?,
    effectiveOperationId: String,
    transferGeneration: Long
): DownloadedPayloadSummary {
    val client = backgroundDownloadClient
    return when (transportKind) {
        DownloadTransportKind.HLS -> hlsTransfer.download(
            client = client,
            playlistRequest = request,
            destFile = workingFile,
            displayFileName = fileName,
            songId = workingSong.id,
            songKey = workingSong.stableKey(),
            totalBytesHint = resolved.contentLength ?: 0L,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            transferGeneration = transferGeneration
        )
        DownloadTransportKind.DIRECT,
        DownloadTransportKind.CHUNKED_RANGE -> singleThreadDownload(
            client = client,
            request = request,
            destFile = workingFile,
            displayFileName = fileName,
            songId = workingSong.id,
            songKey = workingSong.stableKey(),
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            transferGeneration = transferGeneration
        )
    }
}

internal suspend fun AudioDownloadManager.finalizeDownloadedAudio(
    context: Context,
    songKey: String,
    workingSong: SongItem,
    fileName: String,
    mimeType: String?,
    workingFile: File,
    payloadSummary: DownloadedPayloadSummary,
    effectiveOperationId: String,
    batchSessionId: Long?,
    attemptId: Long?,
    coreCommitTracker: DownloadCoreCommitTracker
): CoreCommittedAudio {
    publishStageProgress(
        songId = workingSong.id,
        songKey = songKey,
        fileName = fileName,
        stage = DownloadStage.VERIFYING_AUDIO,
        attemptId = attemptId,
        operationId = effectiveOperationId,
        bytesRead = workingFile.length().coerceAtLeast(0L),
        totalBytes = payloadSummary.expectedBytes ?: workingFile.length().coerceAtLeast(0L)
    )
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "audio_finalize_prepare",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = effectiveOperationId
    )
    verifyDownloadedAudioPayload(
        song = workingSong,
        tempFile = workingFile,
        displayFileName = fileName,
        payloadSummary = payloadSummary
    )
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "audio_verified",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = effectiveOperationId
    )
    val directoryCommitLease =
        ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
            context = context,
            operationId = effectiveOperationId
        ) ?: throw DownloadStorageMutationDeferredException(effectiveOperationId)
    try {
    val bytesBeforeMetadata = workingFile.length().coerceAtLeast(0L)
    val pendingMetadata = buildCorePendingMetadata(
        context = context,
        song = workingSong,
        audioTargetName = fileName,
        operationId = effectiveOperationId
    )
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "audio_pending_metadata",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = effectiveOperationId
    )
    if (!ManagedDownloadStorage.writePendingAudioMetadata(
            context = context,
            audioName = fileName,
            json = pendingMetadata,
            operationId = effectiveOperationId
        )
    ) {
        throw IOException("无法写入下载 pending metadata: $fileName")
    }
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "audio_pending_metadata_written",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = effectiveOperationId
    )

    val bytesAtCommit = workingFile.length().coerceAtLeast(0L)
    val commitExpectedBytes = resolveAudioCommitExpectedSize(
        transferExpectedBytes = payloadSummary.expectedBytes,
        bytesBeforeMetadata = bytesBeforeMetadata,
        bytesAtCommit = bytesAtCommit
    )
    NPLogger.d(
        TAG,
        "音频提交长度诊断: file=$fileName, " +
            "transferReported=${payloadSummary.actualBytes}, " +
            "transferFile=$bytesBeforeMetadata, " +
            "transferExpected=${payloadSummary.expectedBytes}, " +
            "taggedFile=$bytesAtCommit, " +
            "commitExpected=$commitExpectedBytes"
    )

    val transferredBytes = workingFile.length().coerceAtLeast(0L)
    publishStageProgress(
        songId = workingSong.id,
        songKey = workingSong.stableKey(),
        fileName = fileName,
        stage = DownloadStage.COMMITTING_CORE,
        attemptId = attemptId,
        operationId = effectiveOperationId,
        bytesRead = transferredBytes,
        totalBytes = transferredBytes
    )
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "audio_commit",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = effectiveOperationId
    )
    coreCommitTracker.phase = DownloadCoreCommitPhase.COMMITTING
    val committingMarked = try {
        DownloadExecutionRoomStore.markCommitting(
            context = context,
            operationId = effectiveOperationId
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw IOException(
            "无法确认下载 operation 的提交所有权",
            error
        )
    }
    if (!committingMarked) {
        throw java.util.concurrent.CancellationException(
            "下载 operation 已失去提交所有权"
        )
    }
    // 待提交音频由可恢复写入器在完整校验后一次性显现。把核心状态
    // 作为种子元数据同步写入，进程在收尾回调前退出时仍能安全恢复首播
    val coreCommittedSeedMetadata = coreCommittedSeedMetadataJson(pendingMetadata)
    val committedAudio = withContext(NonCancellable) {
        ManagedDownloadStorage.saveAudioFromTemp(
            context = context,
            fileName = fileName,
            tempFile = workingFile,
            mimeType = mimeType,
            expectedSizeBytes = commitExpectedBytes,
            transferSizeVerified = true,
            seedMetadataJson = coreCommittedSeedMetadata,
            pendingMetadataJson = pendingMetadata
        )
    }
    coreCommitTracker.phase = DownloadCoreCommitPhase.CORE_COMMITTED
    val coreMarkerOwned = operationRegistry.markCoreCommittedIfOwned(
        songKey = songKey,
        operationId = effectiveOperationId,
        attemptId = attemptId
    )
    if (!coreMarkerOwned) {
        NPLogger.d(
            TAG,
            "core 提交后发现 operation 引用已被撤销，不重新打开宿主保护: " +
                "song=${workingSong.name}, operationId=$effectiveOperationId"
        )
    }
    val coreOperationMarked = try {
        DownloadExecutionRoomStore.markCoreCommitted(
            context = context,
            operationId = effectiveOperationId
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "写入下载 operation core commit 阶段失败: ${error.message}"
        )
        false
    }
    if (!coreOperationMarked) {
        NPLogger.w(
            TAG,
            "pending metadata 已提交但 operation journal 未确认，" +
                "保留音频等待收尾恢复: operationId=$effectiveOperationId"
        )
    } else {
        // 只有目标写入成功且 durable operation 已确认后才删除 HLS 断点
        clearHlsResumeState(workingFile)
    }
    if (committedAudio.isPendingAudioWrite) {
        NPLogger.d(
            TAG,
            "音频 core 已提交，交由发布阶段提升为正式文件: " +
                "song=${workingSong.name}, file=${committedAudio.name}"
        )
    }
    if (coreOperationMarked) {
        ManagedDownloadStorage.deleteWorkingResumeMetadata(workingFile)
    }
    publishStageProgress(
        songId = workingSong.id,
        songKey = songKey,
        fileName = fileName,
        stage = DownloadStage.ASSETS_ENRICHING,
        attemptId = attemptId,
        operationId = effectiveOperationId,
        bytesRead = transferredBytes,
        totalBytes = transferredBytes
    )
    return CoreCommittedAudio(
        audio = committedAudio,
        transferredBytes = transferredBytes,
        operationCoreCommitted = coreOperationMarked
    )
    } finally {
        directoryCommitLease.close()
    }
}
