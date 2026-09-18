package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadedSidecarReferences
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadedSidecarStage
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadedPayloadSummary
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.hasDownloadedAudioDurationMismatch
import moe.ouom.neriplayer.core.download.resource.DownloadTransferPermitRegistry
import moe.ouom.neriplayer.core.download.observability.DownloadStartupTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTraceToken
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.host.DownloadTransferAdmissionDeferredException
import moe.ouom.neriplayer.core.download.policy.shouldUseIndexedSidecarLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.traffic.hasConfirmedInternetAccess
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal suspend fun <T> AudioDownloadManager.withTransferCyclePermit(
    context: Context,
    ownerKey: String,
    traceToken: DownloadOperationTraceToken?,
    operationId: String? = null,
    attemptId: Long? = null,
    block: suspend (
        permit: DownloadTransferPermitRegistry.Permit,
        markNetworkFinished: () -> Unit,
        transferOwnerToken: Long?
    ) -> T
): T {
    val configured = currentDownloadParallelismSnapshot(context)
    transferPermitRegistry.updateConfiguredParallelism(
        requestedParallelism = configured.value,
        reason = "user_setting",
        configurationRevision = configured.revision
    )
    val allowSingleOverflow = operationId?.let { normalizedOperationId ->
        GlobalDownloadManager.consumeManualRetryTransferBoost(normalizedOperationId)
    } == true
    val permit = transferPermitRegistry.acquire(
        ownerKey = ownerKey,
        allowSingleOverflow = allowSingleOverflow
    )
    var networkFinished = false
    fun markNetworkFinished() {
        if (networkFinished) return
        permit.markNetworkIoFinished()
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.NETWORK_FINISHED
        )
        networkFinished = true
    }
    try {
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.NETWORK_PERMIT_GRANTED
        )
        val transferOwnerToken = operationId?.let { normalizedOperationId ->
            DownloadExecutionHosts.onTransferStarted(
                context = context.applicationContext,
                operationId = normalizedOperationId,
                attemptId = attemptId,
                transferPermitOwnerKey = permit.ownerKey
            )
        }
        if (operationId != null && transferOwnerToken == null) {
            throw DownloadTransferAdmissionDeferredException(
                operationId = operationId,
                attemptId = attemptId
            )
        }
        permit.markNetworkIoStarted()
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.NETWORK_STARTED
        )
        DownloadStartupTrace.markTransferStarted()
        return block(permit, ::markNetworkFinished, transferOwnerToken)
    } finally {
        withContext(NonCancellable) {
            markNetworkFinished()
            permit.release()
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.NETWORK_PERMIT_RELEASED
            )
        }
    }
}

internal suspend fun AudioDownloadManager.downloadSidecars(
    context: Context,
    song: SongItem,
    songKey: String,
    baseName: String,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    batchSessionId: Long? = null,
    attemptId: Long? = null,
    requireActiveAttempt: Boolean = true,
    operationId: String? = null,
    stageObserver: ((DownloadedSidecarStage, Boolean) -> Unit)? = null
): DownloadedSidecarReferences {
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "sidecar_prepare",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = operationId,
        requireActiveAttempt = requireActiveAttempt
    )
    val useSequentialSidecarWrites = ManagedDownloadStorage.usesDocumentTree(context)
    val expectedCover = buildCoverDownloadCandidateUrls(song).isNotEmpty()
    val allowIndexedSidecarLookup = shouldUseIndexedSidecarLookup(
        usesDocumentTree = useSequentialSidecarWrites,
        allowSlowLookup = true
    )
    val references = if (useSequentialSidecarWrites) {
        val lyricReferences = observeSidecarStage(
            stage = DownloadedSidecarStage.LYRICS,
            observer = stageObserver
        ) {
            downloadLyrics(
                context = context,
                song = song,
                songKey = songKey,
                baseName = baseName,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt,
                operationId = operationId
            )
        }
        val cachedCover = observeSidecarStage(
            stage = DownloadedSidecarStage.COVER,
            observer = stageObserver
        ) {
            cacheCover(
                context = context,
                song = song,
                songKey = songKey,
                baseName = baseName,
                storedAudio = storedAudio,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt,
                allowIndexedLookup = allowIndexedSidecarLookup,
                operationId = operationId
            )
        }
        DownloadedSidecarReferences(
            coverReference = cachedCover?.reference,
            createdCover = cachedCover?.created == true,
            expectedCover = expectedCover,
            lyricReference = lyricReferences.lyricReference,
            translatedLyricReference = lyricReferences.translatedLyricReference,
            romanizedLyricReference = lyricReferences.romanizedLyricReference,
            lyricContent = lyricReferences.lyricContent,
            translatedLyricContent = lyricReferences.translatedLyricContent,
            romanizedLyricContent = lyricReferences.romanizedLyricContent,
            expectedLyric = lyricReferences.expectedLyric,
            expectedTranslatedLyric = lyricReferences.expectedTranslatedLyric,
            expectedRomanizedLyric = lyricReferences.expectedRomanizedLyric
        )
    } else {
        coroutineScope {
            val lyricJob = async {
                observeSidecarStage(
                    stage = DownloadedSidecarStage.LYRICS,
                    observer = stageObserver
                ) {
                    downloadLyrics(
                        context = context,
                        song = song,
                        songKey = songKey,
                        baseName = baseName,
                        serializeWrites = false,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        requireActiveAttempt = requireActiveAttempt,
                        operationId = operationId
                    )
                }
            }
            val coverJob = async {
                observeSidecarStage(
                    stage = DownloadedSidecarStage.COVER,
                    observer = stageObserver
                ) {
                    cacheCover(
                        context = context,
                        song = song,
                        songKey = songKey,
                        baseName = baseName,
                        storedAudio = storedAudio,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        requireActiveAttempt = requireActiveAttempt,
                        allowIndexedLookup = allowIndexedSidecarLookup,
                        operationId = operationId
                    )
                }
            }
            val lyricReferences = lyricJob.await()
            val cachedCover = coverJob.await()
            DownloadedSidecarReferences(
                coverReference = cachedCover?.reference,
                createdCover = cachedCover?.created == true,
                expectedCover = expectedCover,
                lyricReference = lyricReferences.lyricReference,
                translatedLyricReference = lyricReferences.translatedLyricReference,
                romanizedLyricReference = lyricReferences.romanizedLyricReference,
                lyricContent = lyricReferences.lyricContent,
                translatedLyricContent = lyricReferences.translatedLyricContent,
                romanizedLyricContent = lyricReferences.romanizedLyricContent,
                expectedLyric = lyricReferences.expectedLyric,
                expectedTranslatedLyric = lyricReferences.expectedTranslatedLyric,
                expectedRomanizedLyric = lyricReferences.expectedRomanizedLyric
            )
        }
    }
    return mergeDownloadedSidecarReferences(
        references,
        completedAudioReferenceRegistry.peekPartialSidecarReferences(songKey)
            ?.retainCreatedOnly()
    )
}

internal suspend fun <T> AudioDownloadManager.observeSidecarStage(
    stage: DownloadedSidecarStage,
    observer: ((DownloadedSidecarStage, Boolean) -> Unit)?,
    block: suspend () -> T
): T {
    runCatching { observer?.invoke(stage, true) }
    return try {
        block()
    } finally {
        runCatching { observer?.invoke(stage, false) }
    }
}

internal suspend fun AudioDownloadManager.verifyDownloadedAudioPayload(
    song: SongItem,
    tempFile: File,
    displayFileName: String,
    payloadSummary: DownloadedPayloadSummary,
    resolvedSource: AudioDownloadManager.ResolvedDownloadSource? = null
): Long? {
    currentCoroutineContext().ensureActive()
    // 文件长度是提交前唯一可信的本地事实，传输计数只用于诊断
    val actualBytes = tempFile.length().coerceAtLeast(0L)
    if (actualBytes <= 0L) {
        throw DownloadIntegrityException("DOWNLOAD_INTEGRITY_EMPTY", "下载文件为空: $displayFileName")
    }
    if (payloadSummary.actualBytes > 0L && payloadSummary.actualBytes != actualBytes) {
        NPLogger.w(
            TAG,
            "下载计数与工作文件长度不同，以文件长度为准: file=$displayFileName, " +
                "reported=${payloadSummary.actualBytes}, fileBytes=$actualBytes"
        )
    }
    if (!isTransferSizeComplete(payloadSummary.expectedBytes, actualBytes)) {
        throw DownloadIntegrityException(
            "DOWNLOAD_INTEGRITY_SIZE_MISMATCH",
            "下载文件不完整: $displayFileName, $actualBytes/${payloadSummary.expectedBytes}"
        )
    }
    // HLS 已解封装，来源响应长度不能当作最终音频长度
    val sourceContentLength = resolvedSource
        ?.takeIf { it.streamType == YouTubePlayableStreamType.DIRECT }?.contentLength
    if (!isTransferSizeComplete(sourceContentLength, actualBytes)) {
        throw DownloadIntegrityException(
            "DOWNLOAD_INTEGRITY_SIZE_MISMATCH",
            "下载文件长度与来源不符: $displayFileName, $actualBytes/$sourceContentLength"
        )
    }
    NPLogger.d(
        TAG,
        "下载传输校验通过: file=$displayFileName, " +
            "reported=${payloadSummary.actualBytes}, file=$actualBytes, " +
            "expected=${payloadSummary.expectedBytes}"
    )

    val sizeBeforeProbe = tempFile.length().coerceAtLeast(0L)
    val expectedMd5 = resolvedSource?.contentMd5
        ?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
    if (expectedMd5 != null) {
        val digest = MessageDigest.getInstance("MD5")
        tempFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualMd5 = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualMd5.equals(expectedMd5, ignoreCase = true)) {
            throw DownloadIntegrityException(
                "DOWNLOAD_INTEGRITY_CHECKSUM_MISMATCH",
                "下载音频摘要与来源不符: $displayFileName"
            )
        }
    }
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(tempFile.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            ?.takeIf(String::isNotBlank)
            ?.let { hasAudio ->
                if (hasAudio == "no") {
                    throw IOException("下载文件不包含音轨: $displayFileName")
                }
            }
        val containerDurationMs = runCatching {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                TagLib.getAudioProperties(descriptor.dup().detachFd())
                    ?.length?.toLong()?.takeIf { it > 0L }
            }
        }.getOrNull()
        val durationMs = containerDurationMs ?: retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val expectedDurationMs = resolvedSource?.durationMs?.takeIf { it > 0L } ?: song.durationMs
        if ((expectedDurationMs > 0L || expectedMd5 != null) && (durationMs == null || durationMs <= 0L)) {
            throw DownloadIntegrityException("DOWNLOAD_INTEGRITY_DURATION_UNAVAILABLE", "无法确认下载音频时长")
        }
        // 来源摘要证明完整字节属于本次音源，歌单时长可能属于其它编码或旧版本
        if (expectedMd5 == null && hasDownloadedAudioDurationMismatch(expectedDurationMs, durationMs)) {
            throw DownloadIntegrityException(
                "DOWNLOAD_INTEGRITY_DURATION_MISMATCH",
                "下载音频时长不匹配: expectedMs=$expectedDurationMs, actualMs=$durationMs"
            )
        }
        val sizeAfterProbe = tempFile.length().coerceAtLeast(0L)
        if (sizeAfterProbe != sizeBeforeProbe) {
            throw IOException(
                "下载文件在完整性校验期间发生变化: " +
                    "$sizeBeforeProbe/$sizeAfterProbe"
            )
        }
        currentCoroutineContext().ensureActive()
        NPLogger.d(TAG, "下载音频校验通过: catalogMs=${song.durationMs}, " +
            "sourceMs=${resolvedSource?.durationMs}, actualMs=$durationMs, sourceChecksumVerified=${expectedMd5 != null}")
        return durationMs?.takeIf { it > 0L }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error is DownloadIntegrityException) throw error
        throw DownloadIntegrityException("DOWNLOAD_INTEGRITY_AUDIO_UNREADABLE", "下载文件校验失败: ${song.name}", error)
    } finally {
        runCatching { retriever.release() }
    }
}

internal suspend fun AudioDownloadManager.waitForRetryOrCancellation(
    context: Context,
    songKey: String,
    delayMs: Long,
    batchSessionId: Long? = null,
    attemptId: Long? = null,
    operationId: String? = null
) {
    val initialDelayMs = delayMs.coerceAtLeast(0L)
    // 下载重试只接受已确认的 INTERNET_CAPABILITY_INTERNET，未知状态不能
    // 被当作在线或蜂窝网络，避免切网窗口误恢复或误暂停
    val startedOffline = !context.hasConfirmedInternetAccess()
    var remainingMs = if (startedOffline) {
        maxOf(initialDelayMs, TRANSIENT_DOWNLOAD_OFFLINE_RECOVERY_WAIT_MS)
    } else {
        initialDelayMs
    }
    var recoveredOnlineAtMs: Long? = null
    var observedWakeSignalVersion = retryWakeSignalVersion.value
    while (remainingMs > 0L) {
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "retry_wait",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId
        )
        val hasConfirmedInternetNow = context.hasConfirmedInternetAccess()
        if (startedOffline && hasConfirmedInternetNow) {
            val nowMs = System.currentTimeMillis()
            val recoveredAtMs = recoveredOnlineAtMs ?: nowMs.also { recoveredOnlineAtMs = it }
            if (nowMs - recoveredAtMs >= TRANSIENT_DOWNLOAD_NETWORK_SETTLE_MS) {
                return
            }
        } else {
            recoveredOnlineAtMs = null
        }
        val nextSliceMs = remainingMs.coerceAtMost(DOWNLOAD_RETRY_POLL_SLICE_MS)
        val wakeSignalResult = withTimeoutOrNull(nextSliceMs) {
            retryWakeSignalVersion.first { version ->
                version != observedWakeSignalVersion
            }
        }
        if (wakeSignalResult != null) {
            observedWakeSignalVersion = wakeSignalResult
            if (
                !startedOffline ||
                    hasConfirmedInternetNow ||
                    context.hasConfirmedInternetAccess()
            ) {
                if (!startedOffline) {
                    return
                }
                val wakeAtMs = System.currentTimeMillis()
                val recoveredAtMs = recoveredOnlineAtMs ?: wakeAtMs.also { recoveredOnlineAtMs = it }
                if (wakeAtMs - recoveredAtMs >= TRANSIENT_DOWNLOAD_NETWORK_SETTLE_MS) {
                    return
                }
                continue
            }
        }
        remainingMs -= nextSliceMs
    }
    ensureSongDownloadNotCancelled(
        songKey = songKey,
        stage = "retry_wait",
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = operationId
    )
}

internal suspend fun AudioDownloadManager.downloadLyrics(
    context: Context,
    song: SongItem,
    songKey: String,
    baseName: String,
    serializeWrites: Boolean = true,
    batchSessionId: Long? = null,
    attemptId: Long? = null,
    requireActiveAttempt: Boolean = true,
    operationId: String? = null
): DownloadedSidecarReferences {
    return AudioDownloadLyricsCoordinator.download(
        context = context,
        song = song,
        songKey = songKey,
        baseName = baseName,
        serializeWrites = serializeWrites,
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        requireActiveAttempt = requireActiveAttempt,
        operationId = operationId,
        ensureNotCancelled = { key, stage, session, attempt, active, operation ->
            ensureSongDownloadNotCancelled(
                songKey = key,
                stage = stage,
                batchSessionId = session,
                attemptId = attempt,
                operationId = operation,
                requireActiveAttempt = active
            )
        },
        writeSidecar = { key, stage, session, attempt, active, operation, block ->
            withNetworkPolicyMutationPermit(
                songKey = key,
                stage = stage,
                batchSessionId = session,
                attemptId = attempt,
                operationId = operation,
                requireActiveAttempt = active,
                block = block
            )
        },
        rememberPartial = { key, partialOperationId, references ->
            rememberPartialSidecarReferences(
                songKey = key,
                sidecarReferences = references,
                operationId = partialOperationId
            )
        }
    )
}

internal fun AudioDownloadManager.ensureDownloadNotCancelled(
    songId: Long,
    songKey: String,
    destFile: File,
    batchSessionId: Long? = null,
    attemptId: Long? = null,
    operationId: String? = null
) {
    val shouldAbort = operationRegistry.withMutationLock {
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
        shouldAbortDownloadWork(
            allDownloadsCancelled = _isCancelled.value,
            batchSessionCurrent = isBatchSessionCurrent(batchSessionId),
            songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
            networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey),
            attemptAllowsWork = GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId),
            operationAllowsWork = operationAllowsWork
        )
    }
    if (shouldAbort) {
        NPLogger.d(TAG, "下载被取消，停止分块下载: songId=$songId")
        val preserveCancellationArtifacts =
            shouldPreserveWorkingArtifactsAfterCancellation(
                cancellation = true,
                allDownloadsCancelled = _isCancelled.value,
                songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey)
            )
        if (!preserveCancellationArtifacts) {
            deleteWorkingFileUnlessNetworkPolicyPaused(songKey, destFile)
        }
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = operationId
        )
        throw java.util.concurrent.CancellationException("Download cancelled")
    }
}
