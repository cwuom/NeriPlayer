package moe.ouom.neriplayer.core.download

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal class DownloadTaskStore(
    scope: CoroutineScope,
    private val progressEmitIntervalNs: Long
) {
    private val mutationLock = Any()
    private val attemptIdGenerator = AtomicLong(0L)
    private val progressPublishStates = ConcurrentHashMap<String, TaskProgressPublishState>()
    private val _isSingleDownloading = MutableStateFlow(false)
    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val _activeBatchDownloadJobCount = MutableStateFlow(0)
    @Volatile private var songKeyIndex = emptyMap<String, Int>()

    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()

    private val rawDownloadTaskSummary: StateFlow<DownloadTaskSummary> = _downloadTasks
        .map(::buildDownloadTaskSummary)
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = DownloadTaskSummary()
        )

    val downloadTaskSummary: StateFlow<DownloadTaskSummary> = combine(
        rawDownloadTaskSummary,
        _isSingleDownloading,
        _activeBatchDownloadJobCount
    ) { taskSummary: DownloadTaskSummary,
        isSingleDownloading: Boolean,
        activeBatchDownloadJobCount: Int ->
        stabilizeDownloadTaskSummary(
            taskSummary = taskSummary,
            isSingleDownloading = isSingleDownloading,
            hasActiveBatchJobs = activeBatchDownloadJobCount > 0
        )
    }.distinctUntilChanged().stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = DownloadTaskSummary()
    )

    val activeDownloadOperationsFlow: StateFlow<Boolean> = combine(
        downloadTaskSummary,
        _isSingleDownloading,
        _activeBatchDownloadJobCount
    ) { taskSummary: DownloadTaskSummary,
        isSingleDownloading: Boolean,
        activeBatchDownloadJobCount: Int ->
        isSingleDownloading ||
            activeBatchDownloadJobCount > 0 ||
            taskSummary.hasActiveOperations
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    var isSingleDownloading: Boolean
        get() = _isSingleDownloading.value
        set(value) {
            _isSingleDownloading.value = value
        }

    private data class TaskProgressPublishState(
        val bytesRead: Long,
        val totalBytes: Long,
        val percentage: Int,
        val speedBytesPerSec: Long,
        val stage: AudioDownloadManager.DownloadStage,
        val emittedAtNs: Long
    )

    fun setActiveBatchDownloadJobCount(count: Int) {
        _activeBatchDownloadJobCount.value = count.coerceAtLeast(0)
    }

    fun currentTasks(): List<DownloadTask> {
        return _downloadTasks.value
    }

    fun findTask(songKey: String): DownloadTask? {
        val tasks = _downloadTasks.value
        val idx = songKeyIndex[songKey]
        if (idx != null && idx < tasks.size) {
            val task = tasks[idx]
            if (task.song.stableKey() == songKey) return task
        }
        return tasks.firstOrNull { it.song.stableKey() == songKey }
    }

    fun hasActiveDownloadOperations(): Boolean {
        return hasActiveDownloadOperations(
            tasks = _downloadTasks.value,
            isSingleDownloading = _isSingleDownloading.value,
            hasActiveBatchJobs = _activeBatchDownloadJobCount.value > 0
        )
    }

    fun updateProgress(progress: AudioDownloadManager.DownloadProgress) {
        if (!shouldPublishProgress(progress)) {
            return
        }
        mutate { tasks ->
            val taskIndex = songKeyIndex[progress.songKey] ?: -1
            if (taskIndex < 0) return@mutate tasks
            val currentTask = tasks[taskIndex]
            if (currentTask.status != DownloadStatus.DOWNLOADING ||
                !shouldApplyTaskMutation(currentTask, progress.attemptId)
            ) {
                return@mutate tasks
            }
            if (currentTask.progress == progress) {
                return@mutate tasks
            }

            tasks.replaceAt(taskIndex, currentTask.copy(progress = progress))
        }
    }

    fun removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys: Set<String>) {
        mutate { tasks ->
            tasks.filterNot { task ->
                task.status == DownloadStatus.WAITING_NETWORK &&
                    task.song.stableKey() !in recoveryCandidateKeys
            }
        }
    }

    fun prepareDownloadTask(
        song: SongItem,
        status: DownloadStatus = DownloadStatus.DOWNLOADING
    ): Long? {
        val songKey = song.stableKey()
        var preparedAttemptId: Long? = null
        mutate { tasks ->
            val existingIndex = songKeyIndex[songKey] ?: -1
            if (existingIndex < 0) {
                val attemptId = nextAttemptId()
                preparedAttemptId = attemptId
                clearProgressPublishState(songKey)
                return@mutate tasks + DownloadTask(
                    song = song,
                    progress = null,
                    status = status,
                    attemptId = attemptId
                )
            }

            val existingTask = tasks[existingIndex]
            when (existingTask.status) {
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING -> return@mutate tasks

                DownloadStatus.COMPLETED,
                DownloadStatus.CANCELLED,
                DownloadStatus.FAILED,
                DownloadStatus.WAITING_NETWORK -> {
                    val attemptId = nextAttemptId()
                    preparedAttemptId = attemptId
                    clearProgressPublishState(songKey)
                    tasks.replaceAt(
                        existingIndex,
                        DownloadTask(
                            song = song,
                            progress = null,
                            status = status,
                            attemptId = attemptId
                        )
                    )
                }
            }
        }
        return preparedAttemptId
    }

    fun prepareDownloadTasks(
        songs: List<SongItem>,
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
        replaceExistingActiveTasks: Boolean = false
    ): Map<String, Long> {
        if (songs.isEmpty()) {
            return emptyMap()
        }
        val distinctSongs = songs.distinctBy { it.stableKey() }
        val preparedAttemptIds = linkedMapOf<String, Long>()
        mutate { tasks ->
            val updatedTasks = tasks.toMutableList()
            val existingIndexesBySongKey = HashMap<String, Int>(songKeyIndex)
            distinctSongs.forEach { song ->
                val songKey = song.stableKey()
                val existingIndex = existingIndexesBySongKey[songKey]
                if (existingIndex == null) {
                    val attemptId = nextAttemptId()
                    preparedAttemptIds[songKey] = attemptId
                    clearProgressPublishState(songKey)
                    existingIndexesBySongKey[songKey] = updatedTasks.size
                    updatedTasks += DownloadTask(
                        song = song,
                        progress = null,
                        status = status,
                        attemptId = attemptId
                    )
                    return@forEach
                }

                val existingTask = updatedTasks[existingIndex]
                when (existingTask.status) {
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING -> {
                        if (!replaceExistingActiveTasks) {
                            return@forEach
                        }
                        val attemptId = nextAttemptId()
                        preparedAttemptIds[songKey] = attemptId
                        clearProgressPublishState(songKey)
                        updatedTasks[existingIndex] = DownloadTask(
                            song = song,
                            progress = null,
                            status = status,
                            attemptId = attemptId
                        )
                    }

                    DownloadStatus.COMPLETED,
                    DownloadStatus.CANCELLED,
                    DownloadStatus.FAILED,
                    DownloadStatus.WAITING_NETWORK -> {
                        val attemptId = nextAttemptId()
                        preparedAttemptIds[songKey] = attemptId
                        clearProgressPublishState(songKey)
                        updatedTasks[existingIndex] = DownloadTask(
                            song = song,
                            progress = null,
                            status = status,
                            attemptId = attemptId
                        )
                    }
                }
            }
            updatedTasks
        }
        return preparedAttemptIds
    }

    fun registerActiveDownloadTask(
        song: SongItem,
        expectedAttemptId: Long
    ) {
        clearProgressPublishState(song.stableKey())
        updateTask(
            songKey = song.stableKey(),
            expectedAttemptId = expectedAttemptId
        ) { task ->
            if (task.status == DownloadStatus.CANCELLED) {
                return@updateTask task
            }
            task.copy(
                song = song,
                progress = null,
                status = DownloadStatus.DOWNLOADING
            )
        }
    }

    fun registerDownloadTask(
        song: SongItem,
        status: DownloadStatus,
        attemptId: Long
    ) {
        val songKey = song.stableKey()
        mutate { tasks ->
            clearProgressPublishState(songKey)
            val existingIndex = songKeyIndex[songKey] ?: -1
            if (existingIndex >= 0) {
                val existingTask = tasks[existingIndex]
                if (
                    existingTask.attemptId == attemptId &&
                    existingTask.status == status &&
                    existingTask.progress == null
                ) {
                    return@mutate tasks
                }
                return@mutate tasks.replaceAt(
                    existingIndex,
                    DownloadTask(
                        song = song,
                        progress = null,
                        status = status,
                        attemptId = attemptId
                    )
                )
            }

            tasks + DownloadTask(
                song = song,
                progress = null,
                status = status,
                attemptId = attemptId
            )
        }
    }

    fun updateTaskStatus(
        songKey: String,
        status: DownloadStatus,
        expectedAttemptId: Long? = null
    ) {
        if (status != DownloadStatus.DOWNLOADING) {
            clearProgressPublishState(songKey)
        }
        updateTask(songKey, expectedAttemptId) { task ->
            if (task.status == status && task.progress == null) {
                return@updateTask task
            }
            task.copy(status = status, progress = null)
        }
    }

    fun removeDownloadTask(songKey: String, expectedAttemptId: Long? = null) {
        mutate { tasks ->
            val taskIndex = songKeyIndex[songKey] ?: -1
            if (taskIndex < 0) {
                return@mutate tasks
            }
            val task = tasks[taskIndex]
            if (!shouldApplyTaskMutation(task, expectedAttemptId)) {
                return@mutate tasks
            }
            clearProgressPublishState(songKey)
            tasks.filterIndexed { index, _ -> index != taskIndex }
        }
    }

    fun removeDownloadTasks(expectedAttemptIdsBySongKey: Map<String, Long>) {
        if (expectedAttemptIdsBySongKey.isEmpty()) {
            return
        }
        val removedSongKeys = mutableSetOf<String>()
        mutate { tasks ->
            tasks.filterNot { task ->
                val songKey = task.song.stableKey()
                val expectedAttemptId = expectedAttemptIdsBySongKey[songKey]
                    ?: return@filterNot false
                val shouldRemove = shouldApplyTaskMutation(task, expectedAttemptId)
                if (shouldRemove) {
                    removedSongKeys += songKey
                }
                shouldRemove
            }
        }
        removedSongKeys.forEach(::clearProgressPublishState)
    }

    fun removeActiveDownloadTasks(activeTasks: Collection<DownloadTask>) {
        if (activeTasks.isEmpty()) {
            return
        }
        val activeTaskKeys = activeTasks.mapTo(mutableSetOf()) { task ->
            task.song.stableKey() to task.attemptId
        }
        activeTasks.forEach { task ->
            clearProgressPublishState(task.song.stableKey())
        }
        mutate { tasks ->
            tasks.filterNot { task ->
                task.status in arrayOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.WAITING_NETWORK
                ) && activeTaskKeys.contains(task.song.stableKey() to task.attemptId)
            }
        }
    }

    fun applyWaitingNetworkStatus(activeTasks: List<DownloadTask>) {
        activeTasks.forEach { task ->
            clearProgressPublishState(task.song.stableKey())
        }
        mutate { tasks -> applyWaitingNetworkStatus(tasks, activeTasks) }
    }

    fun clearAllTasks() {
        mutate { emptyList() }
        progressPublishStates.clear()
    }

    fun clearCompletedTasks() {
        val removedSongKeys = mutableSetOf<String>()
        mutate { tasks ->
            tasks.filterNot { task ->
                val shouldClear = isDownloadTaskClearable(task)
                if (shouldClear) {
                    removedSongKeys += task.song.stableKey()
                }
                shouldClear
            }
        }
        removedSongKeys.forEach(::clearProgressPublishState)
    }

    fun isDownloadAttemptCurrent(songKey: String, attemptId: Long?): Boolean {
        if (attemptId == null) {
            return true
        }
        return shouldApplyTaskMutation(findTask(songKey), attemptId)
    }

    fun isDownloadAttemptActive(
        songKey: String,
        expectedAttemptId: Long? = null
    ): Boolean {
        val task = findTask(songKey) ?: return false
        if (!shouldApplyTaskMutation(task, expectedAttemptId)) return false
        return task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
    }

    private fun shouldPublishProgress(progress: AudioDownloadManager.DownloadProgress): Boolean {
        val nowNs = System.nanoTime()
        val previous = progressPublishStates[progress.songKey]
        val shouldPublish = previous == null || shouldPublishProgress(
            previous = previous,
            progress = progress,
            nowNs = nowNs
        )
        if (!shouldPublish) {
            return false
        }
        progressPublishStates[progress.songKey] = TaskProgressPublishState(
            bytesRead = progress.bytesRead,
            totalBytes = progress.totalBytes,
            percentage = progress.percentage,
            speedBytesPerSec = progress.speedBytesPerSec,
            stage = progress.stage,
            emittedAtNs = nowNs
        )
        return true
    }

    private fun shouldPublishProgress(
        previous: TaskProgressPublishState,
        progress: AudioDownloadManager.DownloadProgress,
        nowNs: Long
    ): Boolean {
        val enoughTimeElapsed = nowNs - previous.emittedAtNs >= progressEmitIntervalNs
        return progress.stage != previous.stage ||
            progress.stage != AudioDownloadManager.DownloadStage.TRANSFERRING ||
            progress.totalBytes != previous.totalBytes ||
            (
                enoughTimeElapsed &&
                    (
                        progress.percentage != previous.percentage ||
                            progress.bytesRead != previous.bytesRead ||
                            progress.speedBytesPerSec != previous.speedBytesPerSec
                        )
                )
    }

    private fun clearProgressPublishState(songKey: String) {
        progressPublishStates.remove(songKey)
    }

    private fun nextAttemptId(): Long {
        return attemptIdGenerator.incrementAndGet()
    }

    private inline fun mutate(
        transform: (List<DownloadTask>) -> List<DownloadTask>
    ): List<DownloadTask> {
        synchronized(mutationLock) {
            val currentTasks = _downloadTasks.value
            val updatedTasks = transform(currentTasks)
            if (updatedTasks != currentTasks) {
                _downloadTasks.value = updatedTasks
                songKeyIndex = buildSongKeyIndex(updatedTasks)
            }
            return updatedTasks
        }
    }

    private fun buildSongKeyIndex(tasks: List<DownloadTask>): Map<String, Int> {
        if (tasks.isEmpty()) return emptyMap()
        val index = HashMap<String, Int>(tasks.size * 2)
        tasks.forEachIndexed { i, task ->
            index.putIfAbsent(task.song.stableKey(), i)
        }
        return index
    }

    private inline fun updateTask(
        songKey: String,
        expectedAttemptId: Long? = null,
        transform: (DownloadTask) -> DownloadTask
    ): Boolean {
        var applied = false
        mutate { tasks ->
            val taskIndex = songKeyIndex[songKey] ?: -1
            if (taskIndex < 0) {
                return@mutate tasks
            }
            val task = tasks[taskIndex]
            if (!shouldApplyTaskMutation(task, expectedAttemptId)) {
                return@mutate tasks
            }
            val updatedTask = transform(task)
            applied = true
            if (updatedTask == task) {
                return@mutate tasks
            }
            tasks.replaceAt(taskIndex, updatedTask)
        }
        return applied
    }

    private fun List<DownloadTask>.replaceAt(
        index: Int,
        task: DownloadTask
    ): List<DownloadTask> {
        val updatedTasks = toMutableList()
        updatedTasks[index] = task
        return updatedTasks
    }
}
