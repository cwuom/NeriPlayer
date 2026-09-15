package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadStage
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadProgress
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.HlsResumeState
import android.content.Context
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.isPostCoreDownloadOperationState
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection

internal fun AudioDownloadManager.notifyRecoveryOpportunityImpl(reason: String) {
    val appContext = AppContainer.applicationContext
    val nowMs = System.currentTimeMillis()
    synchronized(networkRecoveryMonitorLock) {
        if (nowMs - lastRecoveryOpportunityAtMs < RECOVERY_OPPORTUNITY_COOLDOWN_MS) {
            NPLogger.d(TAG, "跳过重复下载恢复机会: reason=$reason")
            return
        }
        lastRecoveryOpportunityAtMs = nowMs
    }
    // Connectivity 回调线程不能同步查询 Room/SAF；恢复入口本身会在 IO
    // 协程中做候选检查，没有候选时立即返回
    evictDownloadConnections()
    retryWakeSignalVersion.value = advanceRetryWakeSignalVersion(retryWakeSignalVersion.value)
    GlobalDownloadManager.recoverPendingDownloadsForNetworkRestored(
        context = appContext,
        reason = reason
    )
    NPLogger.d(TAG, "下载恢复机会已触发: reason=$reason")
}

internal fun AudioDownloadManager.isHlsResumeStateCompatibleImpl(
    state: HlsResumeState,
    actualFileLength: Long,
    actualPrefixSha256: String,
    segmentCount: Int
): Boolean {
    return hlsResumeStore.isCompatible(
        state = state,
        actualFileLength = actualFileLength,
        actualPrefixSha256 = actualPrefixSha256,
        segmentCount = segmentCount
    )
}

internal fun AudioDownloadManager.cancelOperationDownloadImpl(
    songKey: String,
    operationIds: Collection<String>
): Int {
    val normalizedIds = operationIds
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    if (normalizedIds.isEmpty()) return 0
    val calls = operationRegistry.withMutationLock {
        // 先封存 operation，再取消当前调用，防止旧协程在取消窗口内新建请求
        normalizedIds.forEach(operationRegistry::clearCoreCommitted)
        operationRegistry.markExecutionHostPaused(normalizedIds)
        operationRegistry.revokeReference(songKey, normalizedIds)
        snapshotActiveCalls(normalizedIds)
    }
    calls.forEach(okhttp3.Call::cancel)
    normalizedIds.forEach { operationId ->
        clearPublishedProgress(
            songKey = songKey,
            expectedOperationId = operationId
        )
    }
    val visibleOperationId = progressStore.currentProgress()
        ?.takeIf { progress -> progress.songKey == songKey }
        ?.operationId
    if (visibleOperationId in normalizedIds) {
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedOperationId = visibleOperationId
        )
    }
    normalizedIds.forEach { operationId ->
        operationRegistry.clearExecutionHostPausedIfInactive(setOf(operationId))
    }
    return calls.size
}

internal fun AudioDownloadManager.pauseOperationDownloadForExecutionHostImpl(
    operationId: String,
    durableState: String? = null
): Boolean {
    val normalizedId = operationId.trim().takeIf(String::isNotBlank) ?: return false
    if (isPostCoreDownloadOperationState(durableState)) {
        operationRegistry.clearExecutionHostPaused(normalizedId)
        NPLogger.d(
            TAG,
            "宿主停止跳过已提交 core operation: operationId=$normalizedId, " +
                "state=$durableState"
        )
        return false
    }
    var skippedCoreCommitted = false
    var songKey: String? = null
    val calls = operationRegistry.withMutationLock {
        if (operationRegistry.isCoreCommitted(normalizedId)) {
            operationRegistry.clearExecutionHostPaused(normalizedId)
            skippedCoreCommitted = true
            emptyList()
        } else {
            songKey = operationRegistry.songKeyForOperation(normalizedId)
            val currentSongKey = songKey
            if (currentSongKey == null) {
                emptyList()
            } else {
                operationRegistry.markExecutionHostPaused(normalizedId)
                operationRegistry.revokeReference(currentSongKey, setOf(normalizedId))
                snapshotActiveCalls(listOf(normalizedId))
            }
        }
    }
    if (skippedCoreCommitted) {
        NPLogger.d(
            TAG,
            "宿主停止跳过已提交 core operation: operationId=$normalizedId"
        )
        return false
    }
    val resolvedSongKey = songKey ?: return false
    calls.forEach(okhttp3.Call::cancel)
    clearPublishedProgress(
        songKey = resolvedSongKey,
        expectedOperationId = normalizedId
    )
    val visibleProgress = progressStore.currentProgress()
    if (
        visibleProgress?.songKey == resolvedSongKey &&
            visibleProgress.operationId == normalizedId
    ) {
        clearVisibleProgressForSong(
            songKey = resolvedSongKey,
            expectedOperationId = normalizedId
        )
    }
    return true
}

internal fun AudioDownloadManager.releaseCompletedAudioReferenceImpl(
    songKey: String,
    expectedAudio: ManagedDownloadStorage.StoredEntry? = null,
    retainForPlayback: Boolean = false
) {
    completedAudioReferenceRegistry.releaseCompletedAudioReference(
        songKey = songKey,
        expectedAudio = expectedAudio,
        retainForPlayback = retainForPlayback
    )
}

internal fun AudioDownloadManager.publishStageProgressImpl(
    songId: Long,
    songKey: String,
    fileName: String,
    stage: DownloadStage,
    attemptId: Long? = null,
    operationId: String? = null,
    bytesRead: Long = 0L,
    totalBytes: Long = 0L
) {
    publishProgress(
        DownloadProgress(
            songKey = songKey,
            songId = songId,
            fileName = fileName,
            bytesRead = bytesRead.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            speedBytesPerSec = 0L,
            stage = stage,
            attemptId = attemptId,
            operationId = operationId
        ),
        force = true
    )
}

internal suspend fun AudioDownloadManager.downloadSongImpl(
    context: Context,
    song: SongItem,
    batchSessionId: Long? = null,
    attemptId: Long? = null,
    operationId: String? = null,
    downloadAudioQuality: DownloadAudioQualitySelection? = null,
    forceFreshTransfer: Boolean = false
) {
    downloadSongOnIo(
        context = context,
        song = song,
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = operationId,
        downloadAudioQuality = downloadAudioQuality,
        forceFreshTransfer = forceFreshTransfer
    )
}
