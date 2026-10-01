package moe.ouom.neriplayer.data.model.download

data class BatchDownloadPresentationState(
    val id: Long,
    val memberAttemptIds: Map<String, Long?>,
    val memberOperationIds: Map<String, String> = emptyMap(),
    val terminalStates: Map<String, BatchDownloadTerminalState> = emptyMap(),
    val maximumObservedFractions: Map<String, Float> = emptyMap(),
    /** 当前目录已经确认完成的成员, 等待真实传输时再清除 */
    val initiallyCompletedSongKeys: Set<String> = emptySet(),
    val batchId: String? = null,
    val batchGeneration: Long? = null
)

enum class BatchDownloadTerminalState {
    COMPLETED,
    FAILED,
    CANCELLED
}

data class BatchDownloadOverallProgress(
    val totalSongs: Int,
    val completedSongs: Int,
    val percentage: Int,
    val fraction: Float,
    val activeSongCount: Int,
    val hasPendingSongs: Boolean
)

data class DownloadTaskSummary(
    val pendingTaskCount: Int = 0,
    val failedTaskCount: Int = 0,
    val queuedTaskCount: Int = 0,
    val hasActiveTasks: Boolean = false,
    val hasActiveOperations: Boolean = false
) {
    val hasPendingTasks: Boolean
        get() = pendingTaskCount > 0

    val hasFailedTasks: Boolean
        get() = failedTaskCount > 0

    /** admission, recovery and failed-task retry must remain reachable from the manager entry */
    val hasDownloadManagerEntry: Boolean
        get() = hasPendingTasks || hasActiveOperations || hasFailedTasks
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    WAITING_NETWORK,
    COMPLETED,
    FAILED,
    CANCELLED
}
