package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.download.model.mergeDownloadProgress
import java.util.concurrent.atomic.AtomicLong

private val downloadProgressSequence = AtomicLong()

internal fun AudioDownloadManager.DownloadProgress.forPublication(): AudioDownloadManager.DownloadProgress =
    copy(publicationSequence = downloadProgressSequence.incrementAndGet())

/**
 * 下载进度合并和节流策略
 *
 * 策略本身不持有 Flow 或任务状态，迟到快照按发布序号、attempt 和 generation 判定
 */
internal object AudioDownloadProgressPolicy {
    private const val PROGRESS_EMIT_INTERVAL_NS = 180_000_000L
    private const val PROGRESS_EMIT_MIN_BYTES_DELTA = 256L * 1024L

    internal fun shouldPublishAudioDownloadProgress(
        previous: AudioDownloadManager.PublishedProgressState?,
        progress: AudioDownloadManager.DownloadProgress,
        nowNs: Long,
        force: Boolean = false
    ): Boolean {
        if (force || previous == null) {
            return true
        }
        if (previous.attemptId != progress.attemptId) {
            return true
        }
        val enoughTimeElapsed = nowNs - previous.emittedAtNs >= PROGRESS_EMIT_INTERVAL_NS
        val completedTransfer = progress.stage != AudioDownloadManager.DownloadStage.TRANSFERRING ||
            (progress.totalBytes > 0L && progress.bytesRead >= progress.totalBytes)
        if (progress.stage != previous.stage || completedTransfer) {
            return true
        }
        if (!enoughTimeElapsed) {
            return false
        }
        if (progress.totalBytes != previous.totalBytes) {
            return true
        }
        if (progress.totalBytes <= 0L) {
            return progress.bytesRead > previous.bytesRead
        }
        val bytesDelta = progress.bytesRead - previous.bytesRead
        val absoluteBytesDelta = if (bytesDelta >= 0L) bytesDelta else -bytesDelta
        return progress.percentage != previous.percentage ||
            absoluteBytesDelta >= PROGRESS_EMIT_MIN_BYTES_DELTA
    }

    internal fun shouldReplaceLatestProgress(
        previous: AudioDownloadManager.DownloadProgress?,
        incoming: AudioDownloadManager.DownloadProgress
    ): Boolean {
        return previous != mergeLatestProgress(previous, incoming)
    }

    internal fun mergeLatestProgress(
        previous: AudioDownloadManager.DownloadProgress?,
        incoming: AudioDownloadManager.DownloadProgress
    ): AudioDownloadManager.DownloadProgress {
        if (previous == null) {
            return incoming
        }
        val previousAttemptId = previous.attemptId
        val incomingAttemptId = incoming.attemptId
        if (previousAttemptId != null && incomingAttemptId != null) {
            return if (incomingAttemptId > previousAttemptId) {
                incoming
            } else if (incomingAttemptId < previousAttemptId) {
                previous
            } else {
                val previousGeneration = previous.transferGeneration
                val incomingGeneration = incoming.transferGeneration
                if (
                    previousGeneration != null &&
                        incomingGeneration != null &&
                        incomingGeneration < previousGeneration
                ) {
                    previous
                } else {
                    mergeDownloadProgress(previous, incoming)
                }
            }
        }
        if (previousAttemptId != null) {
            return previous
        }
        if (incomingAttemptId != null) {
            return incoming
        }
        return mergeDownloadProgress(previous, incoming)
    }

    /**
     * 只有可见进度仍属于目标 attempt 或 operation 时才允许清空
     *
     * 取消旧请求和替代请求可能短暂重叠，按歌曲键直接清空会让旧回调抹掉新任务的进度
     */
    internal fun shouldClearVisibleProgressForOwner(
        visible: AudioDownloadManager.DownloadProgress?,
        songKey: String,
        expectedAttemptId: Long? = null,
        expectedOperationId: String? = null
    ): Boolean {
        val current = visible ?: return false
        if (current.songKey != songKey) {
            return false
        }
        val normalizedOperationId = expectedOperationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return (expectedAttemptId == null || current.attemptId == expectedAttemptId) &&
            (normalizedOperationId == null || current.operationId == normalizedOperationId)
    }
}
