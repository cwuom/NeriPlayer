package moe.ouom.neriplayer.core.player.download

import android.content.Context
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import okio.BufferedSource
import java.security.MessageDigest

internal fun AudioDownloadManager.cancelSongDownloadImpl(songKey: String) {
    val calls = operationRegistry.withMutationLock {
        operationRegistry.removeNetworkPolicyPaused(songKey)
        val operationIds = activeOperationIdsForSongLocked(songKey)
        operationIds.forEach(operationRegistry::clearCoreCommitted)
        operationRegistry.markExecutionHostPaused(operationIds)
        operationRegistry.revokeReference(songKey, operationIds)
        operationRegistry.clearInactiveExecutionHostPausesExcept(operationIds)
        snapshotActiveCalls(songKey)
    }
    calls.forEach { call ->
        call.cancel()
    }
    clearPublishedProgress(songKey)
    clearVisibleProgressForSong(songKey)
}

internal fun AudioDownloadManager.cancelDownloadImpl() {
    val calls = operationRegistry.withMutationLock {
        operationRegistry.clearNetworkPolicyPaused()
        val operationIds = operationRegistry.activeOperationIds()
        operationIds.forEach(operationRegistry::clearCoreCommitted)
        operationRegistry.markExecutionHostPaused(operationIds)
        operationRegistry.revokeAllReferences()
        snapshotActiveCalls()
    }
    _isCancelled.value = true
    invalidateBatchSession()
    calls.forEach { call ->
        call.cancel()
    }
    progressStore.clearVisibleProgress()
    progressStore.clearBatchProgress()
    clearAllPublishedProgress()
}

internal fun AudioDownloadManager.pauseDownloadsForNetworkPolicyImpl(songKeys: Collection<String>) {
    val normalizedKeys = songKeys
        .mapNotNull { it.takeIf(String::isNotBlank) }
        .distinct()
    if (normalizedKeys.isEmpty()) {
        return
    }
    val calls = operationRegistry.withMutationLock {
        operationRegistry.addNetworkPolicyPaused(normalizedKeys)
        normalizedKeys.forEach { songKey ->
            val operationIds = activeOperationIdsForSongLocked(songKey)
            operationRegistry.markExecutionHostPaused(operationIds)
            operationRegistry.revokeReference(songKey, operationIds)
        }
        normalizedKeys.flatMap(::snapshotActiveCalls).distinct()
    }
    calls.forEach { call -> call.cancel() }
    progressStore.currentProgress()?.songKey
        ?.takeIf(normalizedKeys::contains)
        ?.let(::clearVisibleProgressForSong)
    normalizedKeys.forEach(::clearPublishedProgress)
}

internal fun AudioDownloadManager.pauseSongDownloadForExecutionHostImpl(songKey: String) {
    val normalizedKey = songKey.takeIf(String::isNotBlank) ?: return
    val calls = operationRegistry.withMutationLock {
        operationRegistry.addNetworkPolicyPaused(setOf(normalizedKey))
        val operationIds = activeOperationIdsForSongLocked(normalizedKey)
        operationRegistry.markExecutionHostPaused(operationIds)
        operationRegistry.revokeReference(normalizedKey, operationIds)
        snapshotActiveCalls(normalizedKey)
    }
    calls.forEach { call ->
        call.cancel()
    }
    clearPublishedProgress(normalizedKey)
    clearVisibleProgressForSong(normalizedKey)
}

internal fun AudioDownloadManager.onConfiguredDownloadParallelismChangedImpl(
    configuredValue: Int,
    configurationRevision: Long? = null
) {
    transferPermitRegistry.updateConfiguredParallelism(
        requestedParallelism = configuredValue,
        reason = "user_setting",
        configurationRevision = configurationRevision
    )
    GlobalDownloadManager.wakeDownloadExecutionPumpAfterParallelismChanged(
        AppContainer.applicationContext
    )
}

internal fun AudioDownloadManager.resolveBatchDownloadWorkerCountImpl(
    songCount: Int,
    requestedParallelism: Int
): Int {
    if (songCount <= 0) {
        return 0
    }
    return clampBatchDownloadParallelism(requestedParallelism).coerceAtMost(songCount)
}

internal fun AudioDownloadManager.shouldFetchRomanizedLyricForDownloadImpl(
    shouldFetchPrimaryLyric: Boolean,
    shouldFetchTranslatedLyric: Boolean
): Boolean {
    return AudioDownloadLyricsCoordinator.shouldFetchRomanizedLyric(
        shouldFetchPrimaryLyric,
        shouldFetchTranslatedLyric
    )
}

internal fun AudioDownloadManager.getLyricsBundleFastImpl(
    context: Context,
    song: SongItem,
    allowColdSafProbe: Boolean = true
): ManagedDownloadStorage.DownloadedLyricsBundle {
    return ManagedDownloadStorage.readLyricsBundleFast(
        context = context,
        song = song,
        allowColdSafProbe = allowColdSafProbe
    )
}

internal fun AudioDownloadManager.copyHlsSegmentImpl(
    source: BufferedSource,
    sink: okio.BufferedSink,
    trafficAccumulator: TrafficByteAccumulator,
    prefixDigest: MessageDigest? = null,
    expectedRawBytes: Long? = null,
    onNetworkActivity: (() -> Unit)? = null
): Long {
    return AudioHlsSegmentSupport.copySegment(
        source = source,
        sink = sink,
        trafficAccumulator = trafficAccumulator,
        maxSegmentBytes = MAX_HLS_SEGMENT_BYTES,
        readBufferBytes = DOWNLOAD_READ_BUFFER_BYTES.toInt(),
        prefixDigest = prefixDigest,
        expectedRawBytes = expectedRawBytes,
        onNetworkActivity = onNetworkActivity
    )
}
