package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

data class DownloadTask(
    val song: SongItem,
    val progress: AudioDownloadManager.DownloadProgress?,
    val status: DownloadStatus,
    val attemptId: Long = 0L
)

data class DownloadTaskSummary(
    val pendingTaskCount: Int = 0,
    val queuedTaskCount: Int = 0,
    val hasActiveTasks: Boolean = false,
    val hasActiveOperations: Boolean = false
) {
    val hasPendingTasks: Boolean
        get() = pendingTaskCount > 0
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    WAITING_NETWORK,
    COMPLETED,
    FAILED,
    CANCELLED
}

internal data class PreparedDownloadTaskRequest(
    val song: SongItem,
    val attemptId: Long
)

internal fun isDownloadTaskFinalizing(task: DownloadTask?): Boolean {
    return task?.status == DownloadStatus.DOWNLOADING &&
        task.progress?.stage == AudioDownloadManager.DownloadStage.FINALIZING
}

internal fun isDownloadTaskCancellable(task: DownloadTask?): Boolean {
    return task?.status == DownloadStatus.QUEUED ||
        task?.status == DownloadStatus.DOWNLOADING ||
        task?.status == DownloadStatus.WAITING_NETWORK
}

internal fun shouldHideRemoteDownloadAction(
    hasLocalDownload: Boolean,
    task: DownloadTask?
): Boolean {
    if (!hasLocalDownload) {
        return false
    }
    return task == null || task.status == DownloadStatus.COMPLETED
}

fun buildDownloadTaskSummary(tasks: List<DownloadTask>): DownloadTaskSummary {
    var pendingTaskCount = 0
    var queuedTaskCount = 0
    var hasActiveTasks = false
    var hasActiveOperations = false

    tasks.forEach { task ->
        when (task.status) {
            DownloadStatus.QUEUED -> {
                pendingTaskCount++
                queuedTaskCount++
                hasActiveOperations = true
            }

            DownloadStatus.DOWNLOADING -> {
                pendingTaskCount++
                hasActiveTasks = true
                hasActiveOperations = true
            }

            DownloadStatus.WAITING_NETWORK -> pendingTaskCount++
            DownloadStatus.FAILED -> pendingTaskCount++
            DownloadStatus.COMPLETED,
            DownloadStatus.CANCELLED -> Unit
        }
    }

    return DownloadTaskSummary(
        pendingTaskCount = pendingTaskCount,
        queuedTaskCount = queuedTaskCount,
        hasActiveTasks = hasActiveTasks,
        hasActiveOperations = hasActiveOperations
    )
}

internal fun stabilizeDownloadTaskSummary(
    taskSummary: DownloadTaskSummary,
    isSingleDownloading: Boolean,
    hasActiveBatchJobs: Boolean
): DownloadTaskSummary {
    if (!isSingleDownloading && !hasActiveBatchJobs) {
        return taskSummary
    }
    if (taskSummary.hasPendingTasks) {
        return taskSummary.copy(
            hasActiveTasks = taskSummary.hasActiveTasks || isSingleDownloading,
            hasActiveOperations = true
        )
    }
    return taskSummary.copy(
        pendingTaskCount = 1,
        queuedTaskCount = if (isSingleDownloading) 0 else 1,
        hasActiveTasks = isSingleDownloading,
        hasActiveOperations = true
    )
}

fun countPendingDownloadTasks(tasks: List<DownloadTask>): Int {
    return tasks.count { task ->
        task.status == DownloadStatus.QUEUED ||
            task.status == DownloadStatus.DOWNLOADING ||
            task.status == DownloadStatus.WAITING_NETWORK ||
            task.status == DownloadStatus.FAILED
    }
}

internal fun shouldApplyTaskMutation(
    task: DownloadTask?,
    expectedAttemptId: Long?
): Boolean {
    if (task == null) {
        return false
    }
    return expectedAttemptId == null || task.attemptId == expectedAttemptId
}

internal fun isActiveDownloadAttempt(
    tasks: List<DownloadTask>,
    songKey: String,
    expectedAttemptId: Long?
): Boolean {
    val task = tasks.firstOrNull { it.song.stableKey() == songKey } ?: return false
    if (!shouldApplyTaskMutation(task, expectedAttemptId)) {
        return false
    }
    return task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
}

internal fun applyWaitingNetworkStatus(
    tasks: List<DownloadTask>,
    waitingTasks: Collection<DownloadTask>
): List<DownloadTask> {
    if (tasks.isEmpty() || waitingTasks.isEmpty()) {
        return tasks
    }
    val waitingTaskKeys = waitingTasks
        .mapTo(mutableSetOf()) { task -> task.song.stableKey() to task.attemptId }
    var changed = false
    val updatedTasks = tasks.map { task ->
        val shouldWait =
            task.status in arrayOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) &&
                waitingTaskKeys.contains(task.song.stableKey() to task.attemptId)
        if (!shouldWait) {
            return@map task
        }
        changed = true
        task.copy(status = DownloadStatus.WAITING_NETWORK, progress = null)
    }
    return if (changed) updatedTasks else tasks
}

internal fun applyCancelledStatus(
    tasks: List<DownloadTask>,
    cancelledTasks: Collection<DownloadTask>
): List<DownloadTask> {
    if (tasks.isEmpty() || cancelledTasks.isEmpty()) {
        return tasks
    }
    val cancelledTaskKeys = cancelledTasks
        .mapTo(mutableSetOf()) { task -> task.song.stableKey() to task.attemptId }
    var changed = false
    val updatedTasks = tasks.map { task ->
        val shouldCancel =
            task.status in arrayOf(
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.WAITING_NETWORK
            ) &&
                cancelledTaskKeys.contains(task.song.stableKey() to task.attemptId)
        if (!shouldCancel) {
            return@map task
        }
        changed = true
        task.copy(status = DownloadStatus.CANCELLED, progress = null)
    }
    return if (changed) updatedTasks else tasks
}

fun hasPendingDownloadTasks(tasks: List<DownloadTask>): Boolean {
    return countPendingDownloadTasks(tasks) > 0
}

fun countQueuedDownloadTasks(tasks: List<DownloadTask>): Int {
    return tasks.count { it.status == DownloadStatus.QUEUED }
}

fun hasActiveDownloadTasks(tasks: List<DownloadTask>): Boolean {
    return tasks.any { it.status == DownloadStatus.DOWNLOADING }
}

internal fun hasActiveDownloadOperations(
    tasks: List<DownloadTask>,
    isSingleDownloading: Boolean,
    hasActiveBatchJobs: Boolean
): Boolean {
    if (isSingleDownloading || hasActiveBatchJobs) {
        return true
    }
    return tasks.any { task ->
        task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
    }
}
