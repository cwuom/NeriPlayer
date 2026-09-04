package moe.ouom.neriplayer.core.download.task

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
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.DownloadTaskSummary
import moe.ouom.neriplayer.core.download.applyWaitingNetworkStatus
import moe.ouom.neriplayer.core.download.buildDownloadTaskSummary
import moe.ouom.neriplayer.core.download.hasActiveDownloadOperations
import moe.ouom.neriplayer.core.download.mergeDownloadProgress
import moe.ouom.neriplayer.core.download.shouldApplyTaskMutation
import moe.ouom.neriplayer.core.download.shouldApplyTaskProgressMutation
import moe.ouom.neriplayer.core.download.stabilizeDownloadTaskSummary
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem

internal class DownloadTaskStore(
    scope: CoroutineScope,
    private val progressEmitIntervalNs: Long
) {
    internal data class ClearPresentationToken(
        val generation: Long,
        val visibleTasks: List<DownloadTask>,
        internal var blockedStableKeys: Set<String> = emptySet()
    )

    private val mutationLock = Any()
    private val attemptIdGenerator = AtomicLong(0L)
    private val progressPublishStates = ConcurrentHashMap<String, TaskProgressPublishState>()
    private val _isSingleDownloading = MutableStateFlow(false)
    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val _isClearPresentationActive = MutableStateFlow(false)
    private val _activeBatchDownloadJobCount = MutableStateFlow(0)
    private var activeDownloadTransferCount = 0
    private var clearPresentationGeneration = 0L
    private var activeClearPresentation: ClearPresentationToken? = null
    @Volatile private var songKeyIndex = emptyMap<String, Int>()

    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()
    val isClearPresentationActive: StateFlow<Boolean> =
        _isClearPresentationActive.asStateFlow()

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

    val isSingleDownloading: Boolean
        get() = _isSingleDownloading.value

    fun beginDownloadTransfer() {
        synchronized(mutationLock) {
            activeDownloadTransferCount++
            _isSingleDownloading.value = true
        }
    }

    fun endDownloadTransfer() {
        synchronized(mutationLock) {
            activeDownloadTransferCount = (activeDownloadTransferCount - 1).coerceAtLeast(0)
            _isSingleDownloading.value = activeDownloadTransferCount > 0
        }
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

    /** 清空开始时摘取并隐藏归属任务，不阻塞其他 stableKey 的新 generation */
    fun beginClearPresentation(
        cleanupStableKeys: Collection<String>? = null
    ): ClearPresentationToken {
        return synchronized(mutationLock) {
            activeClearPresentation?.let { token ->
                cleanupStableKeys?.let { stableKeys ->
                    addClearOwnershipLocked(token, stableKeys)
                }
                return@synchronized token
            }
            val allTasks = _downloadTasks.value
            val normalizedCleanupKeys = cleanupStableKeys
                ?.mapNotNull(::normalizeSongKey)
                ?.toSet()
            val blockedKeys = normalizedCleanupKeys ?: allTasks.mapTo(linkedSetOf()) {
                it.song.stableKey()
            }
            val visibleTasks = allTasks.filter { task ->
                task.song.stableKey() in blockedKeys
            }
            val token = ClearPresentationToken(
                generation = ++clearPresentationGeneration,
                visibleTasks = visibleTasks,
                blockedStableKeys = blockedKeys
            )
            activeClearPresentation = token
            // 先发布隐藏标记，让界面不会在任务快照清空和清空横幅之间闪回旧数据
            _isClearPresentationActive.value = true
            removeTasksForClearOwnershipLocked(blockedKeys)
            progressPublishStates.clear()
            token
        }
    }

    /** 清空后台发现持久 operation 后补齐 owner，不能影响清空期间的新任务 */
    fun addClearPresentationOwnership(
        token: ClearPresentationToken,
        stableKeys: Collection<String>
    ): Boolean {
        return synchronized(mutationLock) {
            if (activeClearPresentation?.generation != token.generation) {
                return@synchronized false
            }
            addClearOwnershipLocked(token, stableKeys)
            true
        }
    }

    /** 持久清空栅栏确认释放后才允许任务展示恢复 */
    fun finishClearPresentation(
        token: ClearPresentationToken,
        canRelease: () -> Boolean = { true }
    ): Boolean {
        return synchronized(mutationLock) {
            if (activeClearPresentation?.generation != token.generation) {
                return@synchronized false
            }
            if (!canRelease()) {
                return@synchronized false
            }
            activeClearPresentation = null
            _isClearPresentationActive.value = false
            true
        }
    }

    fun currentClearPresentationToken(): ClearPresentationToken? {
        return synchronized(mutationLock) { activeClearPresentation }
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

    fun updateProgress(progress: AudioDownloadManager.DownloadProgress): Boolean {
        return synchronized(mutationLock) {
            if (isClearKeyBlockedLocked(progress.songKey)) return@synchronized false
            val tasks = _downloadTasks.value
            val taskIndex = songKeyIndex[progress.songKey] ?: -1
            if (taskIndex < 0) return@synchronized false
            val currentTask = tasks[taskIndex]
            if (currentTask.status != DownloadStatus.QUEUED &&
                currentTask.status != DownloadStatus.DOWNLOADING &&
                currentTask.status != DownloadStatus.WAITING_NETWORK ||
                !shouldApplyTaskProgressMutation(currentTask, progress.attemptId)
            ) {
                return@synchronized false
            }
            val effectiveProgress = mergeDownloadProgress(
                current = currentTask.progress,
                incoming = progress
            )
            if (currentTask.progress == effectiveProgress) {
                return@synchronized false
            }
            if (!shouldPublishProgress(effectiveProgress)) {
                return@synchronized false
            }
            val updatedTasks = tasks.replaceAt(
                taskIndex,
                currentTask.copy(progress = effectiveProgress)
            )
            _downloadTasks.value = updatedTasks
            songKeyIndex = buildSongKeyIndex(updatedTasks)
            true
        }
    }

    /**
     * 从持久化检查点回填任务卡片，不受传输节流影响
     *
     * 重启后任务尚未进入 DOWNLOADING，普通 updateProgress 会拒绝这类进度，
     * 进而让页面一直显示 0。只接受当前 attempt，并保留单调递增的字节数。
     */
    fun restoreProgress(progress: AudioDownloadManager.DownloadProgress): Boolean {
        return synchronized(mutationLock) {
            if (isClearKeyBlockedLocked(progress.songKey)) return@synchronized false
            val taskIndex = songKeyIndex[progress.songKey] ?: -1
            if (taskIndex < 0) return@synchronized false
            val currentTask = _downloadTasks.value[taskIndex]
            if (
                currentTask.status !in RESTORABLE_PROGRESS_STATUSES ||
                !shouldApplyTaskProgressMutation(currentTask, progress.attemptId)
            ) {
                return@synchronized false
            }
            val effectiveProgress = mergeDownloadProgress(
                current = currentTask.progress,
                incoming = progress
            )
            if (currentTask.progress == effectiveProgress) {
                return@synchronized true
            }
            clearProgressPublishState(progress.songKey)
            val updatedTasks = _downloadTasks.value.replaceAt(
                taskIndex,
                currentTask.copy(progress = effectiveProgress)
            )
            _downloadTasks.value = updatedTasks
            songKeyIndex = buildSongKeyIndex(updatedTasks)
            true
        }
    }

    /**
     * 批量回填持久化进度，只复制和重建一次任务索引
     *
     * 启动恢复可能同时包含数百个任务，逐项调用 restoreProgress 会重复复制
     * 整个列表并重复重建索引，导致页面首帧和恢复变慢
     */
    fun restoreProgressBatch(
        progresses: Collection<AudioDownloadManager.DownloadProgress>
    ): Int {
        if (progresses.isEmpty()) return 0
        return synchronized(mutationLock) {
            val currentTasks = _downloadTasks.value
            val updatedTasks = currentTasks.toMutableList()
            var acceptedCount = 0
            var changed = false
            progresses.forEach { progress ->
                val taskIndex = songKeyIndex[progress.songKey] ?: return@forEach
                if (isClearKeyBlockedLocked(progress.songKey)) return@forEach
                val currentTask = updatedTasks[taskIndex]
                if (
                    currentTask.status !in RESTORABLE_PROGRESS_STATUSES ||
                    !shouldApplyTaskProgressMutation(currentTask, progress.attemptId)
                ) {
                    return@forEach
                }
                acceptedCount++
                val effectiveProgress = mergeDownloadProgress(
                    current = currentTask.progress,
                    incoming = progress
                )
                if (currentTask.progress == effectiveProgress) {
                    return@forEach
                }
                clearProgressPublishState(progress.songKey)
                updatedTasks[taskIndex] = currentTask.copy(progress = effectiveProgress)
                changed = true
            }
            if (changed) {
                _downloadTasks.value = updatedTasks
                songKeyIndex = buildSongKeyIndex(updatedTasks)
            }
            acceptedCount
        }
    }

    fun removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys: Set<String>) {
        mutate(allowDuringClear = true) { tasks ->
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
        if (isClearKeyBlocked(songKey)) return null
        var preparedAttemptId: Long? = null
        mutate(allowDuringClear = true, songKey = songKey) { tasks ->
            // 外部检查和列表变更之间可能刚好开始清空，锁内必须再次确认 owner
            if (isClearKeyBlockedLocked(songKey)) return@mutate tasks
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
        val distinctSongs = songs
            .distinctBy { it.stableKey() }
            .filterNot { song -> isClearKeyBlocked(song.stableKey()) }
        val preparedAttemptIds = linkedMapOf<String, Long>()
        mutate(allowDuringClear = true) { tasks ->
            val updatedTasks = tasks.toMutableList()
            val existingIndexesBySongKey = HashMap<String, Int>(songKeyIndex)
            distinctSongs.forEach { song ->
                val songKey = song.stableKey()
                if (isClearKeyBlockedLocked(songKey)) return@forEach
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

    fun ensureDownloadTasks(
        songs: List<SongItem>,
        status: DownloadStatus = DownloadStatus.QUEUED,
        durableAttemptIds: Map<String, Long> = emptyMap()
    ): Map<String, Long> {
        if (songs.isEmpty()) {
            return emptyMap()
        }
        val attemptIds = linkedMapOf<String, Long>()
        mutate(allowDuringClear = true) { tasks ->
            val updatedTasks = tasks.toMutableList()
            val existingIndexesBySongKey = HashMap<String, Int>(songKeyIndex)
            songs.distinctBy { it.stableKey() }.forEach { song ->
                val songKey = song.stableKey()
                if (isClearKeyBlockedLocked(songKey)) return@forEach
                val existingIndex = existingIndexesBySongKey[songKey]
                val existingTask = existingIndex?.let(updatedTasks::get)
                if (existingTask != null && (
                        existingTask.status == DownloadStatus.QUEUED ||
                            existingTask.status == DownloadStatus.DOWNLOADING
                        )
                ) {
                    attemptIds[songKey] = existingTask.attemptId
                    return@forEach
                }
                val durableAttemptId = durableAttemptIds[songKey]
                    ?.takeIf { attemptId -> attemptId > 0L }
                if (
                    existingTask?.status == DownloadStatus.WAITING_NETWORK &&
                        durableAttemptId == existingTask.attemptId
                ) {
                    // 同一 durable attempt 从等待网络回到队列时不能清掉已恢复的进度
                    attemptIds[songKey] = existingTask.attemptId
                    updatedTasks[requireNotNull(existingIndex)] = existingTask.copy(
                        song = song,
                        status = status
                    )
                    return@forEach
                }
                val attemptId = adoptDurableAttemptId(durableAttemptId)
                attemptIds[songKey] = attemptId
                clearProgressPublishState(songKey)
                val task = DownloadTask(
                    song = song,
                    progress = null,
                    status = status,
                    attemptId = attemptId
                )
                if (existingIndex == null) {
                    existingIndexesBySongKey[songKey] = updatedTasks.size
                    updatedTasks += task
                } else {
                    updatedTasks[existingIndex] = task
                }
            }
            updatedTasks
        }
        return attemptIds
    }

    fun registerActiveDownloadTask(
        song: SongItem,
        expectedAttemptId: Long
    ) {
        updateTask(
            songKey = song.stableKey(),
            expectedAttemptId = expectedAttemptId
        ) { task ->
            if (task.status == DownloadStatus.CANCELLED) {
                return@updateTask task
            }
            task.copy(
                song = song,
                status = DownloadStatus.DOWNLOADING
            )
        }
    }

    fun updateTaskStatus(
        songKey: String,
        status: DownloadStatus,
        expectedAttemptId: Long? = null
    ): Boolean {
        val retainedProgress = status == DownloadStatus.WAITING_NETWORK
        if (!retainedProgress) {
            clearProgressPublishState(songKey)
        }
        return updateTask(songKey, expectedAttemptId) { task ->
            val nextProgress = if (retainedProgress) task.progress else null
            if (task.status == status && task.progress == nextProgress) {
                return@updateTask task
            }
            task.copy(status = status, progress = nextProgress)
        }
    }

    fun removeDownloadTask(songKey: String, expectedAttemptId: Long? = null) {
        mutate(allowDuringClear = true) { tasks ->
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
        mutate(allowDuringClear = true) { tasks ->
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

    fun applyWaitingNetworkStatus(activeTasks: List<DownloadTask>) {
        activeTasks.forEach { task ->
            clearProgressPublishState(task.song.stableKey())
        }
        mutate(allowDuringClear = true) { tasks ->
            applyWaitingNetworkStatus(
                tasks,
                activeTasks.filterNot { task ->
                    isClearKeyBlockedLocked(task.song.stableKey())
                }
            )
        }
    }

    fun clearAllTasks() {
        mutate(allowDuringClear = true) { tasks ->
            val token = activeClearPresentation
            if (token == null) {
                emptyList()
            } else {
                tasks.filterNot { task ->
                    task.song.stableKey() in token.blockedStableKeys
                }
            }
        }
        progressPublishStates.clear()
    }

    fun isDownloadAttemptCurrent(songKey: String, attemptId: Long?): Boolean {
        if (isClearKeyBlocked(songKey)) return false
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

    private fun isClearKeyBlocked(songKey: String): Boolean {
        return synchronized(mutationLock) {
            isClearKeyBlockedLocked(songKey)
        }
    }

    private fun isClearKeyBlockedLocked(songKey: String): Boolean {
        val normalizedKey = normalizeSongKey(songKey) ?: return false
        return activeClearPresentation?.blockedStableKeys?.contains(normalizedKey) == true
    }

    private fun addClearOwnershipLocked(
        token: ClearPresentationToken,
        stableKeys: Collection<String>
    ) {
        val normalizedKeys = stableKeys.mapNotNull(::normalizeSongKey).toSet()
        if (normalizedKeys.isEmpty()) return
        token.blockedStableKeys = token.blockedStableKeys + normalizedKeys
        removeTasksForClearOwnershipLocked(normalizedKeys)
    }

    private fun removeTasksForClearOwnershipLocked(stableKeys: Set<String>) {
        if (stableKeys.isEmpty()) return
        val currentTasks = _downloadTasks.value
        val updatedTasks = currentTasks.filterNot { task ->
            task.song.stableKey() in stableKeys
        }
        if (updatedTasks != currentTasks) {
            _downloadTasks.value = updatedTasks
            songKeyIndex = buildSongKeyIndex(updatedTasks)
        }
        stableKeys.forEach(::clearProgressPublishState)
    }

    private fun normalizeSongKey(songKey: String): String? {
        return songKey.trim().takeIf(String::isNotBlank)
    }

    private fun nextAttemptId(): Long {
        return attemptIdGenerator.incrementAndGet()
    }

    private fun adoptDurableAttemptId(attemptId: Long?): Long {
        val durableAttemptId = attemptId?.takeIf { it > 0L } ?: return nextAttemptId()
        attemptIdGenerator.updateAndGet { current -> maxOf(current, durableAttemptId) }
        return durableAttemptId
    }

    private inline fun mutate(
        allowDuringClear: Boolean = false,
        songKey: String? = null,
        transform: (List<DownloadTask>) -> List<DownloadTask>
    ): List<DownloadTask> {
        synchronized(mutationLock) {
            val currentTasks = _downloadTasks.value
            if (
                !allowDuringClear &&
                    activeClearPresentation != null &&
                    (songKey == null || isClearKeyBlockedLocked(songKey))
            ) {
                return currentTasks
            }
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

    private companion object {
        val RESTORABLE_PROGRESS_STATUSES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.WAITING_NETWORK
        )
    }

    private inline fun updateTask(
        songKey: String,
        expectedAttemptId: Long? = null,
        allowDuringClear: Boolean = false,
        transform: (DownloadTask) -> DownloadTask
    ): Boolean {
        var applied = false
        mutate(
            allowDuringClear = allowDuringClear,
            songKey = songKey
        ) { tasks ->
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

    private fun isClearPresentationActive(): Boolean {
        return synchronized(mutationLock) { activeClearPresentation != null }
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
