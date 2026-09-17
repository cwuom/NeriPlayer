package moe.ouom.neriplayer.core.player.download
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadProgress
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadStage

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadExecutionAttemptState
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.HlsResumeState
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.resource.DownloadTransferPermitRegistry
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import moe.ouom.neriplayer.data.settings.resolveDownloadAudioQualitySelection
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal fun AudioDownloadManager.evictDownloadConnections() {
    runCatching {
        backgroundDownloadClient.connectionPool.evictAll()
    }
}

internal fun AudioDownloadManager.resolveWorkingFileBytes(tempFile: File?): Long {
    return tempFile?.takeIf(File::exists)?.length()?.coerceAtLeast(0L) ?: 0L
}

internal fun AudioDownloadManager.sha256FilePrefix(file: File, byteCount: Long): String {
    return hlsResumeStore.sha256FilePrefix(file, byteCount)
}

internal fun AudioDownloadManager.sha256FilePrefixDigest(file: File, byteCount: Long): MessageDigest {
    return hlsResumeStore.sha256FilePrefixDigest(file, byteCount)
}

internal fun AudioDownloadManager.digestHexSnapshot(
    digest: MessageDigest,
    file: File,
    byteCount: Long
): String {
    return hlsResumeStore.digestHexSnapshot(digest, file, byteCount)
}

internal fun AudioDownloadManager.truncateWorkingFile(file: File, byteCount: Long) {
    hlsResumeStore.truncate(file, byteCount)
}

internal fun AudioDownloadManager.rememberHlsResumeState(
    destFile: File,
    playlistFingerprint: String,
    nextSegmentIndex: Int,
    durableBytes: Long,
    durablePrefixSha256: String,
    operationId: String,
    mediaSequence: Long?
) {
    hlsResumeStore.remember(
        destFile = destFile,
        playlistFingerprint = playlistFingerprint,
        nextSegmentIndex = nextSegmentIndex,
        durableBytes = durableBytes,
        durablePrefixSha256 = durablePrefixSha256,
        operationId = operationId,
        mediaSequence = mediaSequence
    )
}

internal fun AudioDownloadManager.resolveHlsResumeState(
    destFile: File,
    playlistFingerprint: String,
    operationId: String = ""
): HlsResumeState? {
    return hlsResumeStore.resolve(destFile, playlistFingerprint, operationId)
}

internal fun AudioDownloadManager.hasHlsResumeState(destFile: File?): Boolean {
    return hlsResumeStore.has(destFile)
}

internal fun AudioDownloadManager.clearHlsResumeState(destFile: File?) {
    hlsResumeStore.clear(destFile)
}

internal fun AudioDownloadManager.deleteWorkingFile(tempFile: File?) {
    clearHlsResumeState(tempFile)
    ManagedDownloadStorage.deleteWorkingDownloadArtifacts(tempFile)
}

internal fun AudioDownloadManager.shouldPreserveArtifactsForNetworkPolicy(songKey: String): Boolean {
    return operationRegistry.isNetworkPolicyPaused(songKey)
}

internal fun <T> AudioDownloadManager.withNetworkPolicyMutationPermit(
    songKey: String,
    stage: String,
    batchSessionId: Long? = null,
    attemptId: Long? = null,
    operationId: String? = null,
    requireActiveAttempt: Boolean = true,
    block: () -> T
): T {
    return operationRegistry.withMutationLock {
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = stage,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId,
            requireActiveAttempt = requireActiveAttempt
        )
        block()
    }
}

internal fun AudioDownloadManager.deleteWorkingFileUnlessNetworkPolicyPaused(
    songKey: String,
    tempFile: File?
): Boolean {
    return operationRegistry.withMutationLock {
        if (shouldPreserveArtifactsForNetworkPolicy(songKey)) {
            false
        } else {
            deleteWorkingFile(tempFile)
            true
        }
    }
}

internal fun AudioDownloadManager.publishProgress(
    progress: DownloadProgress,
    force: Boolean = false
) {
    val normalizedOperationId = progress.operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (
        normalizedOperationId != null &&
            !operationRegistry.allowsReference(
                songKey = progress.songKey,
                operationId = normalizedOperationId,
                attemptId = progress.attemptId
            )
    ) {
        // 取消和重下交错时，已经在路上的旧回调不能污染替代任务的进度
        NPLogger.d(
            TAG,
            "忽略过期下载进度: songKey=${progress.songKey}, " +
                "operationId=$normalizedOperationId, attemptId=${progress.attemptId}"
        )
        return
    }
    if (
        progress.stage == DownloadStage.TRANSFERRING &&
            progress.transferGeneration != null
    ) {
        transferPermitRegistry.recordProgress(
            ownerKey = DownloadTransferPermitRegistry.ownerKey(
                operationId = progress.operationId,
                attemptId = progress.attemptId,
                stableKey = progress.songKey
            ),
            generation = progress.transferGeneration,
            absoluteBytes = progress.bytesRead
        )
    }
    progressStore.publish(
        progress = progress,
        nowNs = System.nanoTime(),
        force = force
    )
}

internal fun AudioDownloadManager.clearPublishedProgress(
    songKey: String,
    expectedAttemptId: Long? = null,
    expectedOperationId: String? = null
) {
    progressStore.clearPublished(
        songKey = songKey,
        expectedAttemptId = expectedAttemptId,
        expectedOperationId = expectedOperationId
    )
}

internal fun AudioDownloadManager.storageSpaceOwnerKey(
    operationId: String?,
    attemptId: Long?,
    songKey: String,
    file: File
): String {
    return buildString {
        append("download-output:")
        append(operationId?.trim().orEmpty().ifBlank { "anonymous" })
        append('#')
        append(attemptId ?: 0L)
        append(':')
        append(songKey)
        append(':')
        append(file.absolutePath)
    }
}

internal fun AudioDownloadManager.markTransferNetworkActivity(
    operationId: String?,
    attemptId: Long?,
    songKey: String,
    transferGeneration: Long?
) {
    transferGeneration ?: return
    transferPermitRegistry.markNetworkActivity(
        ownerKey = DownloadTransferPermitRegistry.ownerKey(
            operationId = operationId,
            attemptId = attemptId,
            stableKey = songKey
        ),
        generation = transferGeneration
    )
}

internal fun AudioDownloadManager.beginSongDownloadOperation(
    songKey: String,
    operationId: String,
    attemptId: Long?
) {
    operationRegistry.beginSongDownloadOperation(songKey, operationId, attemptId)
}

internal fun AudioDownloadManager.endSongDownloadOperation(songKey: String, operationId: String) {
    operationRegistry.endSongDownloadOperation(songKey, operationId)
}

internal fun AudioDownloadManager.releaseReferenceOwnership(
    songKey: String,
    operationId: String
) {
    operationRegistry.releaseReferenceOwnership(songKey, operationId)
}

internal fun AudioDownloadManager.registerActiveCall(
    songKey: String,
    call: okhttp3.Call,
    operationId: String?
) {
    operationRegistry.registerActiveCall(songKey, call, operationId)
}

internal fun AudioDownloadManager.unregisterActiveCall(
    songKey: String,
    call: okhttp3.Call,
    operationId: String?
) {
    operationRegistry.unregisterActiveCall(songKey, call, operationId)
}

internal fun AudioDownloadManager.snapshotActiveCalls(songKey: String? = null): List<okhttp3.Call> {
    return operationRegistry.snapshotActiveCalls(songKey)
}

internal fun AudioDownloadManager.snapshotActiveCalls(operationIds: Collection<String>): List<okhttp3.Call> {
    return operationRegistry.snapshotActiveCalls(operationIds)
}

internal fun AudioDownloadManager.activeOperationIdsForSongLocked(songKey: String): Set<String> {
    return operationRegistry.activeOperationIdsForSong(songKey)
}

internal inline fun <T> AudioDownloadManager.executeTrackedCall(
    client: okhttp3.OkHttpClient,
    request: Request,
    songKey: String,
    operationId: String? = null,
    requireActiveAttempt: Boolean = true,
    block: (okhttp3.Response) -> T
): T {
    val call = client.newCall(request)
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val pausedBeforeExecution = operationRegistry.withMutationLock {
        registerActiveCall(songKey, call, normalizedOperationId)
        shouldPreserveArtifactsForNetworkPolicy(songKey) ||
            normalizedOperationId?.let(operationRegistry::isExecutionHostPaused) == true
    }
    try {
        if (pausedBeforeExecution) {
            call.cancel()
            throw java.util.concurrent.CancellationException(
                if (
                    normalizedOperationId?.let(
                        operationRegistry::isExecutionHostPaused
                    ) == true
                ) {
                    "Download execution host paused"
                } else {
                    "Download paused for network policy"
                }
            )
        }
        return call.execute().use(block)
    } catch (error: IOException) {
        if (
            call.isCanceled() ||
            (_isCancelled.value && requireActiveAttempt) ||
            shouldPreserveArtifactsForNetworkPolicy(songKey) ||
            GlobalDownloadManager.isSongCancelled(songKey)
        ) {
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedOperationId = normalizedOperationId
            )
            throw java.util.concurrent.CancellationException("Download cancelled").apply {
                initCause(error)
            }
        }
        throw error
    } finally {
        operationRegistry.withMutationLock {
            unregisterActiveCall(songKey, call, normalizedOperationId)
        }
    }
}

internal fun AudioDownloadManager.safeToPlayableUri(reference: String?): String? {
    return runCatching {
        ManagedDownloadStorage.toPlayableUri(reference)
    }.getOrNull()
}

internal fun AudioDownloadManager.publishRetryWaitingProgress(
    songId: Long,
    songKey: String,
    fileName: String,
    bytesRead: Long,
    totalBytes: Long,
    attemptId: Long? = null,
    operationId: String? = null
) {
    publishProgress(
        DownloadProgress(
            songKey = songKey,
            songId = songId,
            fileName = fileName,
            bytesRead = bytesRead.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            speedBytesPerSec = 0L,
            stage = DownloadStage.WAITING_RETRY,
            attemptId = attemptId,
            operationId = operationId
        ),
        force = true
    )
}

internal fun AudioDownloadManager.ensureSongDownloadNotCancelled(
    songKey: String,
    stage: String,
    batchSessionId: Long? = null,
    attemptId: Long? = null,
    operationId: String? = null,
    requireActiveAttempt: Boolean = true
) {
    val attemptAllowsWork = if (requireActiveAttempt) {
        GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId)
    } else {
        attemptId == null || GlobalDownloadManager.isDownloadAttemptCurrent(songKey, attemptId)
    }
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val clearFenceAllowsWork = !isDownloadClearFenceBlockingWork(
        songKey = songKey,
        operationId = normalizedOperationId
    )
    val operationAllowsWork = normalizedOperationId?.let { id ->
        operationRegistry.allowsReference(songKey, id) &&
            !operationRegistry.isExecutionHostPaused(id) &&
            clearFenceAllowsWork
    } ?: clearFenceAllowsWork
    if (!shouldAbortDownloadWork(
            // 后台补齐复用已提交音频，不应继承上一轮全局取消标志
            allDownloadsCancelled = _isCancelled.value && requireActiveAttempt,
            batchSessionCurrent = isBatchSessionCurrent(batchSessionId),
            songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
            networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey),
            attemptAllowsWork = attemptAllowsWork,
            operationAllowsWork = operationAllowsWork
        )
    ) {
        return
    }
    NPLogger.d(TAG, "检测到下载取消: songKey=$songKey, stage=$stage")
    clearVisibleProgressForSong(
        songKey = songKey,
        expectedAttemptId = attemptId,
        expectedOperationId = operationId
    )
    throw java.util.concurrent.CancellationException("Download cancelled during $stage")
}

internal fun AudioDownloadManager.isDownloadClearFenceBlockingWork(
    songKey: String,
    operationId: String?
): Boolean {
    return PersistentDownloadClearFenceStore.isBlocked(
        context = AppContainer.applicationContext,
        stableKey = songKey,
        operationId = operationId
    )
}

internal fun AudioDownloadManager.isCancellationCleanupOwnedByClearFence(
    songKey: String,
    operationId: String,
    preserveCancellationArtifacts: Boolean
): Boolean {
    return !preserveCancellationArtifacts &&
        isDownloadClearFenceBlockingWork(
            songKey = songKey,
            operationId = operationId
        )
}

internal suspend fun AudioDownloadManager.buildCorePendingMetadata(
    context: Context,
    song: SongItem,
    audioTargetName: String,
    operationId: String
): String {
    val libraryId = ManagedDownloadStorage.ensureManagedLibraryManifest(context)
    val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
    val nowMs = System.currentTimeMillis()
    val identity = song.identity()
    val stableKey = song.stableKey()
    val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
        stableKey = stableKey,
        songId = song.id,
        identityAlbum = identity.album,
        album = song.album,
        name = song.name,
        artist = song.artist,
        coverUrl = song.coverUrl,
        matchedLyric = song.matchedLyric,
        matchedTranslatedLyric = song.matchedTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric,
        matchedLyricSource = song.matchedLyricSource?.name,
        matchedSongId = song.matchedSongId,
        userLyricOffsetMs = song.userLyricOffsetMs,
        customCoverUrl = song.customCoverUrl,
        customName = song.customName,
        customArtist = song.customArtist,
        originalName = song.originalName,
        originalArtist = song.originalArtist,
        originalCoverUrl = song.originalCoverUrl,
        originalLyric = song.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric,
        originalRomanizedLyric = song.originalRomanizedLyric,
        mediaUri = identity.mediaUri ?: song.mediaUri,
        channelId = song.channelId,
        audioId = song.audioId,
        subAudioId = song.subAudioId,
        playlistContextId = song.playlistContextId,
        durationMs = song.durationMs,
        downloadTimeMs = nowMs,
        downloadFinalized = false,
        createdAtMs = nowMs,
        createdAtSource = "CORE_COMMIT",
        artifactId = "managed:$libraryId:$stableKey",
        operationId = operationId,
        artifactState = "COMMITTING",
        audioFileName = audioTargetName,
        libraryId = libraryId,
        libraryAddedAtMs = nowMs
    )
    return ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata).apply {
        put("rootKey", rootKey)
    }.toString()
}

internal suspend fun AudioDownloadManager.downloadSongOnIo(
    context: Context,
    song: SongItem,
    batchSessionId: Long?,
    attemptId: Long?,
    operationId: String?,
    downloadAudioQuality: DownloadAudioQualitySelection?,
    forceFreshTransfer: Boolean
) {
    withContext(Dispatchers.IO) {
        executeDownloadSong(
            context = context,
            song = song,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId,
            downloadAudioQuality = downloadAudioQuality,
            forceFreshTransfer = forceFreshTransfer
        )
    }
}

internal suspend fun AudioDownloadManager.executeDownloadSong(
    context: Context,
    song: SongItem,
    batchSessionId: Long?,
    attemptId: Long?,
    operationId: String?,
    downloadAudioQuality: DownloadAudioQualitySelection?,
    forceFreshTransfer: Boolean
) {
    val songKey = song.stableKey()
    val effectiveOperationId = operationId?.trim()
        ?.takeIf(String::isNotBlank)
        ?: UUID.randomUUID().toString()
    val traceToken = DownloadOperationTrace.begin(
        operationId = effectiveOperationId,
        attemptId = attemptId
    )
    DownloadOperationTrace.mark(
        traceToken,
        DownloadOperationTracePhase.ENQUEUED
    )
    val state = DownloadExecutionAttemptState()
    // 进入新的真实传输前清掉旧代次的 core 标记，避免取消后复用 operation
    // 时把新下载误当成后台增强任务
    operationRegistry.clearCoreCommitted(effectiveOperationId)
    beginSongDownloadOperation(songKey, effectiveOperationId, attemptId)
    clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
    try {
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "prepare",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        if (LocalSongSupport.isLocalSong(song, context)) {
            NPLogger.d(TAG, "Skip local song download: ${song.name}")
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = effectiveOperationId
            )
            return
        }

        if (!forceFreshTransfer && hasFastCachedManagedDownloadForStart(context, song)) {
            NPLogger.d(
                TAG,
                "${context.getString(R.string.download_file_exists, song.name)}, songKey=$songKey"
            )
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = effectiveOperationId
            )
            return
        }

        val resolvedDownloadAudioQuality = downloadAudioQuality
            ?.let { quality ->
                DownloadAudioQualitySelection.normalized(
                    neteaseQuality = quality.neteaseQuality,
                    youtubeQuality = quality.youtubeQuality,
                    biliQuality = quality.biliQuality
                )
            }
            ?: resolveDownloadAudioQualitySelection(context)
        val isYouTubeMusic = isYouTubeMusicSong(song)
        val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
        // 阶段契约由尝试层按 stage = "source_resolved" 和
        // stage = "prepare_working_file" 顺序推进
        // 真实传输前才会调用 clearCompletedAudioReference(songKey)，再进入
        // downloadPayloadForTransport(...) 和 finalizeDownloadedAudio(...)
        // 取消收敛共享 cleanupCancelledPendingArtifactsWithLease(...) 的恢复凭据
        runDownloadAttempts(
            context = context,
            song = song,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            effectiveOperationId = effectiveOperationId,
            downloadAudioQuality = resolvedDownloadAudioQuality,
            isYouTubeMusic = isYouTubeMusic,
            isBili = isBili,
            state = state
        )
    } catch (error: Exception) {
        handleDownloadSongFailure(
            context = context,
            song = song,
            songKey = songKey,
            effectiveOperationId = effectiveOperationId,
            attemptId = attemptId,
            state = state,
            error = error
        )
    } finally {
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.TERMINAL
        )
        clearPublishedProgress(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = effectiveOperationId
        )
        endSongDownloadOperation(songKey, effectiveOperationId)
    }
}
