package moe.ouom.neriplayer.core.player.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 集中管理下载进度和批次可见状态，避免传输协程直接交叉操作多个 Flow */
internal class AudioDownloadProgressStore(
    bufferCapacity: Int
) {
    private companion object {
        // 恢复快照只需有界新鲜度，不能让大批量进度更新反复复制整张 Map
        private const val LATEST_SNAPSHOT_INTERVAL_NS = 450_000_000L
    }

    private val progressPublishLock = Any()
    private val lastPublishedProgressBySongKey = mutableMapOf<
        String,
        AudioDownloadManager.PublishedProgressState
    >()
    private val latestProgressByOperationValues = mutableMapOf<
        String,
        AudioDownloadManager.DownloadProgress
    >()

    private val _progressFlow = MutableStateFlow<AudioDownloadManager.DownloadProgress?>(null)
    val progressFlow: StateFlow<AudioDownloadManager.DownloadProgress?> =
        _progressFlow.asStateFlow()
    private val progressEventStream = DownloadProgressEventStream<
        AudioDownloadManager.DownloadProgress
    >(bufferCapacity)
    val progressEvents: SharedFlow<AudioDownloadManager.DownloadProgress> =
        progressEventStream.events

    private val _batchProgressFlow =
        MutableStateFlow<AudioDownloadManager.BatchDownloadProgress?>(null)
    val batchProgressFlow: StateFlow<AudioDownloadManager.BatchDownloadProgress?> =
        _batchProgressFlow.asStateFlow()

    private val _latestProgressByOperation =
        MutableStateFlow<Map<String, AudioDownloadManager.DownloadProgress>>(emptyMap())
    val latestProgressByOperation: StateFlow<
        Map<String, AudioDownloadManager.DownloadProgress>
    > = _latestProgressByOperation.asStateFlow()
    private val latestProgressEventStream = DownloadProgressEventStream<
        AudioDownloadManager.DownloadProgress
    >(bufferCapacity = (bufferCapacity * 4).coerceAtLeast(bufferCapacity))
    /** 批量和全局投影消费增量，避免每个进度回调扫描全量快照 */
    val latestProgressEvents: SharedFlow<AudioDownloadManager.DownloadProgress> =
        latestProgressEventStream.events
    private var lastLatestSnapshotAtNs = Long.MIN_VALUE

    private var nextBatchSessionId = 0L
    private var visibleBatchSessionId = 0L
    private val activeBatchSessionIds = linkedSetOf<Long>()
    private val batchSessionLock = Any()

    fun publish(
        progress: AudioDownloadManager.DownloadProgress,
        nowNs: Long,
        force: Boolean = false
    ) {
        synchronized(progressPublishLock) {
            val progressKey = progressOperationKey(progress)
            val previousLatest = latestProgressByOperationValues[progressKey]
            val accepted = AudioDownloadProgressPolicy.shouldReplaceLatestProgress(
                previous = previousLatest,
                incoming = progress
            )
            val effectiveProgress = AudioDownloadProgressPolicy.mergeLatestProgress(
                previous = previousLatest,
                incoming = progress
            )
            if (accepted) {
                latestProgressByOperationValues[progressKey] = effectiveProgress
                // 在同一把锁内发出增量，保证并发回调不会按完成时序倒排
                latestProgressEventStream.publish(effectiveProgress)
                if (shouldPublishLatestSnapshot(previousLatest, effectiveProgress, nowNs)) {
                    _latestProgressByOperation.value = latestProgressByOperationValues.toMap()
                    lastLatestSnapshotAtNs = nowNs
                }
            }
            if (
                previousLatest != null &&
                    !accepted &&
                    previousLatest.attemptId != progress.attemptId
            ) {
                return@synchronized false
            }
            val previous = lastPublishedProgressBySongKey[progress.songKey]
            val shouldPublishNow = AudioDownloadProgressPolicy.shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = effectiveProgress,
                nowNs = nowNs,
                force = force
            )
            if (shouldPublishNow) {
                lastPublishedProgressBySongKey[progress.songKey] =
                    AudioDownloadManager.PublishedProgressState(
                        attemptId = effectiveProgress.attemptId,
                        operationId = effectiveProgress.operationId,
                        bytesRead = effectiveProgress.bytesRead,
                        totalBytes = effectiveProgress.totalBytes,
                        percentage = effectiveProgress.percentage,
                        stage = effectiveProgress.stage,
                        emittedAtNs = nowNs
                    )
                // Flow 发布本身是非阻塞的，和判定一起持锁可保持同曲目事件顺序
                _progressFlow.value = effectiveProgress
                progressEventStream.publish(effectiveProgress)
            }
        }
    }

    fun clearPublished(
        songKey: String,
        expectedAttemptId: Long? = null,
        expectedOperationId: String? = null
    ) {
        val normalizedOperationId = expectedOperationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        synchronized(progressPublishLock) {
            val published = lastPublishedProgressBySongKey[songKey]
            if (
                published != null &&
                    progressOwnershipMatches(
                        attemptId = published.attemptId,
                        operationId = published.operationId,
                        expectedAttemptId = expectedAttemptId,
                        expectedOperationId = normalizedOperationId
                    )
            ) {
                lastPublishedProgressBySongKey.remove(songKey)
            }
            val removed = latestProgressByOperationValues.entries.removeIf { (_, progress) ->
                progress.songKey == songKey &&
                    progressOwnershipMatches(
                        attemptId = progress.attemptId,
                        operationId = progress.operationId,
                        expectedAttemptId = expectedAttemptId,
                        expectedOperationId = normalizedOperationId
                    )
            }
            if (removed) {
                _latestProgressByOperation.value = latestProgressByOperationValues.toMap()
                lastLatestSnapshotAtNs = System.nanoTime()
            }
        }
    }

    fun latestProgressForSong(
        songKey: String,
        attemptId: Long? = null,
        operationId: String? = null
    ): AudioDownloadManager.DownloadProgress? = synchronized(progressPublishLock) {
        latestProgressByOperationValues.values
            .asSequence()
            .filter { progress -> progress.songKey == songKey }
            .filter { progress -> operationId == null || progress.operationId == operationId }
            .filter { progress -> attemptId == null || progress.attemptId == attemptId }
            .maxWithOrNull(
                compareBy<AudioDownloadManager.DownloadProgress> {
                    it.attemptId ?: Long.MIN_VALUE
                }.thenBy { it.operationId.orEmpty() }
            )
    }

    fun latestProgressSnapshot(): List<AudioDownloadManager.DownloadProgress> =
        synchronized(progressPublishLock) {
            latestProgressByOperationValues.values.toList()
        }

    fun clearVisibleProgressForSong(
        songKey: String,
        expectedAttemptId: Long? = null,
        expectedOperationId: String? = null
    ) {
        if (
            AudioDownloadProgressPolicy.shouldClearVisibleProgressForOwner(
                visible = _progressFlow.value,
                songKey = songKey,
                expectedAttemptId = expectedAttemptId,
                expectedOperationId = expectedOperationId
            )
        ) {
            _progressFlow.value = null
        }
    }

    fun clearAllPublished() {
        synchronized(progressPublishLock) {
            lastPublishedProgressBySongKey.clear()
            latestProgressByOperationValues.clear()
            _latestProgressByOperation.value = emptyMap()
            lastLatestSnapshotAtNs = System.nanoTime()
        }
    }

    fun currentProgress(): AudioDownloadManager.DownloadProgress? = _progressFlow.value

    fun clearVisibleProgress() {
        _progressFlow.value = null
    }

    fun clearBatchProgress() {
        _batchProgressFlow.value = null
    }

    fun startBatchSession(): Long = synchronized(batchSessionLock) {
        val sessionId = ++nextBatchSessionId
        activeBatchSessionIds += sessionId
        visibleBatchSessionId = sessionId
        sessionId
    }

    fun invalidateBatchSession() {
        synchronized(batchSessionLock) {
            activeBatchSessionIds.clear()
            visibleBatchSessionId = 0L
            nextBatchSessionId++
        }
    }

    fun isBatchSessionCurrent(batchSessionId: Long?): Boolean {
        return batchSessionId == null || synchronized(batchSessionLock) {
            batchSessionId in activeBatchSessionIds
        }
    }

    fun finishBatchSession(batchSessionId: Long) {
        val shouldClearProgress = synchronized(batchSessionLock) {
            val wasVisible = visibleBatchSessionId == batchSessionId
            activeBatchSessionIds.remove(batchSessionId)
            if (wasVisible) {
                visibleBatchSessionId = activeBatchSessionIds.maxOrNull() ?: 0L
            }
            wasVisible
        }
        if (shouldClearProgress) {
            _batchProgressFlow.value = null
        }
    }

    fun updateBatchProgressForSession(
        batchSessionId: Long,
        progress: AudioDownloadManager.BatchDownloadProgress?
    ) {
        val shouldPublish = synchronized(batchSessionLock) {
            batchSessionId in activeBatchSessionIds && visibleBatchSessionId == batchSessionId
        }
        if (shouldPublish) {
            _batchProgressFlow.value = progress
        }
    }

    private fun progressOperationKey(
        progress: AudioDownloadManager.DownloadProgress
    ): String {
        val operationId = progress.operationId?.trim().orEmpty()
        return operationId.ifBlank {
            "${progress.songKey}#${progress.attemptId ?: 0L}"
        }
    }

    private fun shouldPublishLatestSnapshot(
        previous: AudioDownloadManager.DownloadProgress?,
        incoming: AudioDownloadManager.DownloadProgress,
        nowNs: Long
    ): Boolean {
        if (previous == null) {
            // 首个 operation 需要立即建立快照，后续首条进度交给增量流
            return latestProgressByOperationValues.size == 1 ||
                nowNs - lastLatestSnapshotAtNs >= LATEST_SNAPSHOT_INTERVAL_NS
        }
        if (previous.attemptId != incoming.attemptId) return true
        if (
            incoming.stage != AudioDownloadManager.DownloadStage.TRANSFERRING ||
                (incoming.totalBytes > 0L && incoming.bytesRead >= incoming.totalBytes)
        ) {
            return true
        }
        return nowNs - lastLatestSnapshotAtNs >= LATEST_SNAPSHOT_INTERVAL_NS
    }

    private fun progressOwnershipMatches(
        attemptId: Long?,
        operationId: String?,
        expectedAttemptId: Long?,
        expectedOperationId: String?
    ): Boolean {
        return (expectedAttemptId == null || attemptId == expectedAttemptId) &&
            (expectedOperationId == null || operationId == expectedOperationId)
    }
}
