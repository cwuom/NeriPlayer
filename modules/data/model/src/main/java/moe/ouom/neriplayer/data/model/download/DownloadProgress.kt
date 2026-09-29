package moe.ouom.neriplayer.data.model.download

enum class DownloadStage {
    WAITING_HOST,
    WAITING_DELETE_CLEANUP,
    RESOLVING_SOURCE,
    PREPARING_STORAGE,
    TRANSFERRING,
    VERIFYING_AUDIO,
    COMMITTING_CORE,
    ASSETS_ENRICHING,
    WAITING_RETRY,
    FINALIZING
}

data class DownloadProgress(
    val songKey: String,
    val songId: Long,
    val fileName: String,
    val bytesRead: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val stage: DownloadStage = DownloadStage.TRANSFERRING,
    val attemptId: Long? = null,
    val operationId: String? = null,
    /** 当前传输 permit 的代次，拒绝旧 attempt 的迟到进度回调 */
    val transferGeneration: Long? = null,
    /** 已完成 flush 和 fsync 的前缀，只有这部分可以写入恢复检查点 */
    val durableBytesRead: Long? = null,
    /** 同一进程内的发布顺序，防止补偿快照覆盖较新的增量事件 */
    val publicationSequence: Long = 0L
) {
    val percentage: Int
        get() = when {
            stage == DownloadStage.FINALIZING -> 100
            totalBytes <= 0L -> -1
            bytesRead >= totalBytes -> 100
            else -> ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 99)
        }
}

data class BatchDownloadProgress(
    val totalSongs: Int,
    val completedSongs: Int,
    val currentSong: String,
    val currentProgress: DownloadProgress?,
    val currentSongIndex: Int = 0,
    val aggregateProgressFraction: Float? = null
) {
    val percentage: Int get() = if (totalSongs > 0) {
        aggregateProgressFraction?.let { progressFraction ->
            if (completedSongs >= totalSongs) {
                100
            } else {
                (progressFraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 99)
            }
        } ?: run {
            val baseProgress = (completedSongs * 100.0 / totalSongs)
            val currentSongProgress = currentProgress?.let { progress ->
                if (progress.totalBytes > 0) {
                    (progress.bytesRead.toDouble() / progress.totalBytes) / totalSongs
                } else 0.0
            } ?: 0.0
            if (completedSongs >= totalSongs) {
                100
            } else {
                (baseProgress + currentSongProgress * 100).toInt().coerceIn(0, 99)
            }
        }
    } else 0
}
