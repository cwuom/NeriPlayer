package moe.ouom.neriplayer.core.player.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 保存全局任务投影需要的最新进度，限制大批量任务下整图快照的复制频率 */
internal class DownloadProgressProjectionStore(
    private val snapshotIntervalNs: Long = DEFAULT_SNAPSHOT_INTERVAL_NS
) {
    init {
        require(snapshotIntervalNs >= 0L) { "snapshotIntervalNs must not be negative" }
    }

    private val mutationLock = Any()
    private val values = linkedMapOf<String, AudioDownloadManager.DownloadProgress>()
    private val _snapshot = MutableStateFlow<
        Map<String, AudioDownloadManager.DownloadProgress>
    >(emptyMap())
    val snapshot: StateFlow<Map<String, AudioDownloadManager.DownloadProgress>> =
        _snapshot.asStateFlow()
    private var lastSnapshotAtNs = Long.MIN_VALUE

    fun record(
        progress: AudioDownloadManager.DownloadProgress,
        nowNs: Long = System.nanoTime()
    ): AudioDownloadManager.DownloadProgress {
        synchronized(mutationLock) {
            val key = operationKey(progress)
            val previous = values[key]
            val effective = AudioDownloadProgressPolicy.mergeLatestProgress(previous, progress)
            if (effective == previous) {
                return effective
            }
            values[key] = effective
            if (shouldPublishSnapshot(previous, effective, nowNs)) {
                publishSnapshotLocked(nowNs)
            }
            return effective
        }
    }

    fun remove(operationId: String?) {
        val normalizedId = operationId?.trim().orEmpty()
        if (normalizedId.isBlank()) return
        synchronized(mutationLock) {
            if (values.remove(normalizedId) != null) {
                publishSnapshotLocked(System.nanoTime())
            }
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            if (values.isEmpty()) return
            values.clear()
            publishSnapshotLocked(System.nanoTime())
        }
    }

    fun latest(operationId: String?): AudioDownloadManager.DownloadProgress? {
        val normalizedId = operationId?.trim().orEmpty()
        if (normalizedId.isBlank()) return null
        return synchronized(mutationLock) { values[normalizedId] }
    }

    fun snapshotValues(): List<AudioDownloadManager.DownloadProgress> =
        synchronized(mutationLock) { values.values.toList() }

    private fun shouldPublishSnapshot(
        previous: AudioDownloadManager.DownloadProgress?,
        incoming: AudioDownloadManager.DownloadProgress,
        nowNs: Long
    ): Boolean {
        if (previous == null) {
            // 首个 operation 需要立即建立快照，后续首条进度交给增量流
            return values.size == 1 || nowNs - lastSnapshotAtNs >= snapshotIntervalNs
        }
        if (previous.attemptId != incoming.attemptId) return true
        if (
            incoming.stage != AudioDownloadManager.DownloadStage.TRANSFERRING ||
                (incoming.totalBytes > 0L && incoming.bytesRead >= incoming.totalBytes)
        ) {
            return true
        }
        return nowNs - lastSnapshotAtNs >= snapshotIntervalNs
    }

    private fun publishSnapshotLocked(nowNs: Long) {
        _snapshot.value = values.toMap()
        lastSnapshotAtNs = nowNs
    }

    private fun operationKey(
        progress: AudioDownloadManager.DownloadProgress
    ): String {
        val operationId = progress.operationId?.trim().orEmpty()
        return operationId.ifBlank {
            "${progress.songKey}#${progress.attemptId ?: 0L}"
        }
    }

    private companion object {
        private const val DEFAULT_SNAPSHOT_INTERVAL_NS = 450_000_000L
    }
}
