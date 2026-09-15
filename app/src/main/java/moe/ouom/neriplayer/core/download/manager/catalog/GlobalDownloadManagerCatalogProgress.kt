package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.GlobalDownloadManager.ActiveProgressCheckpointBinding
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PendingProgressCheckpoint
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.CatalogPublishMode
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PendingCatalogPersistRequest
import android.content.Context
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogDelta
import moe.ouom.neriplayer.core.download.catalog.applyDownloadedSongCatalogDelta
import moe.ouom.neriplayer.core.download.catalog.buildDownloadedSongCatalogDelta
import moe.ouom.neriplayer.core.download.catalog.downloadedSongCatalogEntryKey
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey


internal suspend fun GlobalDownloadManager.waitForActiveDownloadJobsToSettle() {
    repeat(20) {
        if (activeBatchDownloadJobs.isEmpty()) {
            return
        }
        delay(100L)
    }
}

internal suspend fun GlobalDownloadManager.waitForQueuedTasksToAttachToBatch() {
    val pollCount = (
        DOWNLOAD_RECOVERY_QUEUE_ATTACH_GRACE_MS /
            DOWNLOAD_RECOVERY_QUEUE_ATTACH_POLL_MS
        ).coerceAtLeast(1)
    repeat(pollCount.toInt()) {
        if (activeBatchDownloadJobs.isNotEmpty()) {
            return
        }
        val currentTasks = taskStore.currentTasks()
        val hasQueuedTask = currentTasks.any { task ->
            task.status == DownloadStatus.QUEUED
        }
        val hasDownloadingTask = currentTasks.any { task ->
            task.status == DownloadStatus.DOWNLOADING
        }
        if (!hasQueuedTask || hasDownloadingTask) {
            return
        }
        delay(DOWNLOAD_RECOVERY_QUEUE_ATTACH_POLL_MS)
    }
}

internal fun GlobalDownloadManager.hasBlockingActiveDownloadOperationsForRecovery(): Boolean {
    return hasRecoveryBlockingDownloadOperations(
        tasks = taskStore.currentTasks(),
        isSingleDownloading = taskStore.isSingleDownloading,
        hasActiveBatchJobs = activeBatchDownloadJobs.isNotEmpty()
    )
}

internal fun GlobalDownloadManager.publishDownloadedSongs(
    context: Context,
    songs: List<DownloadedSong>,
    persistCatalog: Boolean,
    catalogPublishMode: CatalogPublishMode = CatalogPublishMode.FULL
) {
    synchronized(downloadedSongCatalogMutationLock) {
        val visibleSongs = downloadedSongDeleteVisibility.filterVisible(songs)
        val previousSongs = downloadedSongsMutable.value
        val changedSongKeys = changedDownloadedSongKeys(
            previousSongs = previousSongs,
            currentSongs = visibleSongs
        )
        val catalogDelta = if (
            catalogPublishMode == CatalogPublishMode.DELTA &&
                visibleSongs == songs
        ) {
            buildDownloadedSongCatalogDelta(
                previousSongs = previousSongs,
                currentSongs = visibleSongs
            )
        } else {
            null
        }
        // 先发布索引再通知列表, 避免界面首帧看到歌曲时索引仍为空
        downloadedSongCatalogIndex = if (
            catalogDelta != null && downloadedSongCatalogReady
        ) {
            applyDownloadedSongCatalogDelta(
                previousIndex = downloadedSongCatalogIndex,
                previousSongs = previousSongs,
                currentSongs = visibleSongs
            )
        } else {
            buildDownloadedSongCatalogIndex(visibleSongs)
        }
        downloadedSongCatalogReady = true
        downloadedSongsMutable.value = visibleSongs
        downloadedSongCatalogPersistenceRevision.incrementAndGet()
        LocalAssetInvalidationBus.bumpSongs(changedSongKeys)
        downloadPresenceVersionMutable.value += 1
        if (
            persistCatalog && visibleSongs == songs &&
                (catalogDelta == null || !catalogDelta.isEmpty)
        ) {
            scheduleDownloadedSongsCatalogPersist(
                context = context,
                delta = catalogDelta
            )
        }
    }
}

internal fun GlobalDownloadManager.publishScannedDownloadedSongsIfCurrent(
    context: Context,
    songs: List<DownloadedSong>,
    scanRootKey: String?,
    expectedCatalogRevision: Long,
    expectedMetadataRevision: Long
): Boolean {
    synchronized(downloadedSongCatalogMutationLock) {
        if (
            downloadedSongCatalogPersistenceRevision.get() != expectedCatalogRevision ||
                downloadedSongMetadataRevision.get() != expectedMetadataRevision
        ) {
            return false
        }
        publishDownloadedSongs(context, songs, persistCatalog = true)
        downloadedSongCatalogRootKey = scanRootKey
        return true
    }
}

internal fun GlobalDownloadManager.changedDownloadedSongKeys(
    previousSongs: List<DownloadedSong>,
    currentSongs: List<DownloadedSong>
): Set<String> {
    fun keyOf(song: DownloadedSong): String {
        return song.stableKey?.takeIf(String::isNotBlank)
            ?: song.filePath
    }
    val previousByKey = previousSongs.associateBy(::keyOf)
    val currentByKey = currentSongs.associateBy(::keyOf)
    return (previousByKey.keys + currentByKey.keys)
        .filterTo(linkedSetOf()) { key ->
            previousByKey[key] != currentByKey[key]
        }
}

internal fun GlobalDownloadManager.notifyDownloadPresenceChanged() {
    downloadPresenceVersionMutable.value += 1
}

internal fun GlobalDownloadManager.scheduleDownloadedSongsCatalogPersist(
    context: Context,
    delta: DownloadedSongCatalogDelta? = null
) {
    val appContext = context.applicationContext
    val expectedRevision = downloadedSongCatalogPersistenceRevision.get()
    synchronized(catalogPersistenceLock) {
        if (delta == null || delta.requiresFullPersistence) {
            pendingCatalogPersistRequiresFull = true
            pendingCatalogDeltaByKey.clear()
            pendingCatalogDeltaRemovedStableKeys.clear()
        } else if (!pendingCatalogPersistRequiresFull) {
            delta.upserts.forEach { song ->
                val key = downloadedSongCatalogEntryKey(song)
                pendingCatalogDeltaByKey[key] = song
                song.stableKey
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(pendingCatalogDeltaRemovedStableKeys::remove)
            }
            delta.removedStableKeys.forEach { stableKey ->
                pendingCatalogDeltaByKey.remove("stable:$stableKey")
                pendingCatalogDeltaRemovedStableKeys += stableKey
            }
            if (
                pendingCatalogDeltaByKey.size +
                    pendingCatalogDeltaRemovedStableKeys.size >
                DOWNLOAD_CATALOG_DELTA_MAX_ENTRIES
            ) {
                pendingCatalogPersistRequiresFull = true
                pendingCatalogDeltaByKey.clear()
                pendingCatalogDeltaRemovedStableKeys.clear()
            }
        }
        val generation = catalogPersistGeneration.incrementAndGet()
        catalogPersistJob?.cancel()
        catalogPersistJob = scope.launch {
            delay(DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS)
            if (catalogPersistGeneration.get() != generation) return@launch
            val request = synchronized(catalogPersistenceLock) {
                if (catalogPersistGeneration.get() != generation) {
                    null
                } else {
                    val requestDelta = DownloadedSongCatalogDelta(
                        upserts = pendingCatalogDeltaByKey.values.toList(),
                        removedStableKeys = pendingCatalogDeltaRemovedStableKeys.toSet()
                    )
                    val request = PendingCatalogPersistRequest(
                        expectedRevision = expectedRevision,
                        full = pendingCatalogPersistRequiresFull,
                        delta = requestDelta.takeUnless { it.isEmpty }
                    )
                    pendingCatalogPersistRequiresFull = false
                    pendingCatalogDeltaByKey.clear()
                    pendingCatalogDeltaRemovedStableKeys.clear()
                    request
                }
            } ?: return@launch
            catalogPersistenceMutex.withLock {
                val songs = synchronized(downloadedSongCatalogMutationLock) {
                    downloadedSongsMutable.value.takeIf {
                        downloadedSongCatalogPersistenceRevision.get() ==
                            request.expectedRevision
                    }
                } ?: return@withLock
                if (request.full || request.delta == null) {
                    persistDownloadedSongsCatalog(appContext, songs)
                } else {
                    val persisted = downloadedSongCatalogStore.persistDelta(
                        context = appContext,
                        delta = request.delta,
                        snapshot = songs
                    )
                    if (!persisted) {
                        persistDownloadedSongsCatalog(appContext, songs)
                    }
                }
            }
        }
    }
}

internal fun GlobalDownloadManager.cancelScheduledDownloadedSongsCatalogPersist() {
    synchronized(catalogPersistenceLock) {
        catalogPersistGeneration.incrementAndGet()
        catalogPersistJob?.cancel()
        catalogPersistJob = null
        pendingCatalogPersistRequiresFull = false
        pendingCatalogDeltaByKey.clear()
        pendingCatalogDeltaRemovedStableKeys.clear()
    }
}

internal fun GlobalDownloadManager.observeDownloadProgress() {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        // 启动时只回放一次快照，运行中消费增量，避免每次更新扫描全量 operation
        AudioDownloadManager.latestProgressSnapshot().forEach { progress ->
            updateDownloadProgress(progress)
        }
        AudioDownloadManager.latestProgressEvents.collect { progress ->
            updateDownloadProgress(progress)
        }
    }
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        // 增量事件可能在高峰期丢弃，低频快照负责有界补偿而不是忙等
        AudioDownloadManager.latestProgressByOperation.collect {
            latestProgress ->
            latestProgress.values.forEach { progress ->
                updateDownloadProgress(progress)
            }
        }
    }
}

internal fun GlobalDownloadManager.overlayLatestProgress(
    tasks: List<DownloadTask>,
    latestProgress: Map<String, AudioDownloadManager.DownloadProgress>
): List<DownloadTask> {
    if (tasks.isEmpty() || latestProgress.isEmpty()) {
        return tasks
    }
    return tasks.map { task ->
        val matchingProgress = latestProgressForTask(task, latestProgress)
            ?: return@map task
        val mergedProgress = AudioDownloadManager.mergeLatestProgress(
            task.progress,
            matchingProgress
        )
        if (mergedProgress == task.progress) task else task.copy(progress = mergedProgress)
    }
}

internal fun GlobalDownloadManager.latestProgressForTask(
    task: DownloadTask,
    latestProgress: Map<String, AudioDownloadManager.DownloadProgress>
): AudioDownloadManager.DownloadProgress? {
    val songKey = task.song.stableKey()
    val candidates = latestProgress.values
        .asSequence()
        .filter { progress -> progress.songKey == songKey }
        .filter { progress ->
            progress.attemptId == null || progress.attemptId == task.attemptId
        }
        .toList()
    if (candidates.isEmpty()) return null

    val binding = activeProgressCheckpointBindings[songKey]
    binding?.let { owner ->
        candidates.firstOrNull { progress ->
            progress.operationId == owner.operationId &&
                progress.attemptId == owner.attemptId
        }?.let { progress -> return progress }
    }
    task.progress?.operationId?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { operationId ->
            candidates.firstOrNull { progress ->
                progress.operationId == operationId &&
                    progress.attemptId == task.attemptId
            }?.let { progress -> return progress }
        }
    val sameAttempt = candidates.filter { progress ->
        progress.attemptId == task.attemptId
    }
    return when {
        sameAttempt.size == 1 -> sameAttempt.single()
        sameAttempt.isEmpty() && candidates.size == 1 -> candidates.single()
        else -> null
    }
}

internal fun GlobalDownloadManager.recordLatestDownloadProgress(
    progress: AudioDownloadManager.DownloadProgress
): AudioDownloadManager.DownloadProgress {
    return latestProgressProjectionStore.record(progress)
}

internal fun GlobalDownloadManager.clearLatestProgressForOperation(operationId: String?) {
    latestProgressProjectionStore.remove(operationId)
}

internal fun GlobalDownloadManager.clearAllLatestProgress() {
    latestProgressProjectionStore.clear()
}

internal fun GlobalDownloadManager.enqueueProgressCheckpoint(
    context: Context,
    progress: AudioDownloadManager.DownloadProgress,
    binding: ActiveProgressCheckpointBinding
) {
    val key = "${binding.operationId}:${binding.attemptId}:${progress.songKey}"
    synchronized(progressCheckpointLock) {
        pendingProgressCheckpoints[key] = PendingProgressCheckpoint(
            context = context.applicationContext,
            progress = progress,
            binding = binding
        )
        if (progressCheckpointWriterJob?.isActive != true) {
            progressCheckpointWriterJob = scope.launch {
                delay(DOWNLOAD_PROGRESS_CHECKPOINT_COALESCE_MS)
                flushProgressCheckpoints()
            }
        }
    }
}

internal suspend fun GlobalDownloadManager.flushProgressCheckpoints() {
    while (true) {
        val pending = synchronized(progressCheckpointLock) {
            if (pendingProgressCheckpoints.isEmpty()) {
                progressCheckpointWriterJob = null
                return
            }
            pendingProgressCheckpoints.values.toList().also {
                pendingProgressCheckpoints.clear()
            }
        }
        pending.forEach { checkpoint ->
            val currentBinding = activeProgressCheckpointBindings[
                checkpoint.progress.songKey
            ]
            if (currentBinding != checkpoint.binding) {
                return@forEach
            }
            val ticket = checkpoint.binding.admissionTicket
            if (
                ticket != null &&
                !isDownloadAdmissionTicketCurrent(
                    context = checkpoint.context,
                    admissionTicket = ticket,
                    stableKey = checkpoint.progress.songKey,
                    operationId = checkpoint.binding.operationId
                )
            ) {
                return@forEach
            }
            val bytesToPersist = when {
                checkpoint.progress.stage != AudioDownloadManager.DownloadStage.TRANSFERRING ->
                    checkpoint.progress.bytesRead.coerceAtLeast(0L)
                else -> checkpoint.progress.durableBytesRead
                    ?.coerceAtLeast(0L)
                    ?: return@forEach
            }
            runCatching {
                DownloadExecutionRoomStore.checkpointProgress(
                    context = checkpoint.context,
                    operationId = checkpoint.binding.operationId,
                    stableKey = checkpoint.progress.songKey,
                    attemptId = checkpoint.binding.attemptId,
                    bytesWritten = bytesToPersist,
                    totalBytes = checkpoint.progress.totalBytes
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "写入下载进度检查点失败: operationId=" +
                        "${checkpoint.binding.operationId}, " +
                        "songKey=${checkpoint.progress.songKey}, " +
                        "error=${error.message}"
                )
            }
        }
        val hasMore = synchronized(progressCheckpointLock) {
            pendingProgressCheckpoints.isNotEmpty()
        }
        if (!hasMore) {
            synchronized(progressCheckpointLock) {
                progressCheckpointWriterJob = null
            }
            return
        }
        delay(DOWNLOAD_PROGRESS_CHECKPOINT_COALESCE_MS)
    }
}

internal suspend fun GlobalDownloadManager.persistLatestProgressCheckpointNow(
    context: Context,
    songKey: String,
    binding: ActiveProgressCheckpointBinding
) {
    val currentBinding = activeProgressCheckpointBindings[songKey]
    if (currentBinding != binding) return
    val progress = AudioDownloadManager.latestProgressForSong(
        songKey = songKey,
        attemptId = binding.attemptId,
        operationId = binding.operationId
    ) ?: latestProgressProjectionStore.latest(binding.operationId) ?: return
    val bytesToPersist = when {
        progress.stage != AudioDownloadManager.DownloadStage.TRANSFERRING ->
            progress.bytesRead.coerceAtLeast(0L)
        else -> progress.durableBytesRead?.coerceAtLeast(0L) ?: return
    }
    runCatching {
        DownloadExecutionRoomStore.checkpointProgress(
            context = context.applicationContext,
            operationId = binding.operationId,
            stableKey = songKey,
            attemptId = binding.attemptId,
            bytesWritten = bytesToPersist,
            totalBytes = progress.totalBytes
        )
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "退出前写入下载进度检查点失败: operationId=${binding.operationId}, " +
                "songKey=$songKey, error=${error.message}"
        )
    }
}

internal fun GlobalDownloadManager.updateDownloadProgress(progress: AudioDownloadManager.DownloadProgress) {
    val appContext = AppContainer.applicationContext
    val binding = activeProgressCheckpointBindings[progress.songKey]
    if (binding != null) {
        if (
            progress.attemptId != binding.attemptId ||
                progress.operationId != binding.operationId ||
                (binding.admissionTicket != null &&
                    !isDownloadAdmissionTicketCurrent(
                        context = appContext,
                        admissionTicket = binding.admissionTicket,
                        stableKey = progress.songKey,
                        operationId = binding.operationId
                    ))
        ) {
            return
        }
    }
    val latestProgress = recordLatestDownloadProgress(progress)
    val taskProgressAccepted = taskStore.updateProgress(latestProgress)
    val effectiveProgress = taskStore.findTask(progress.songKey)
        ?.progress
        ?.takeIf { stored ->
            stored.attemptId == latestProgress.attemptId
        }
        ?: latestProgress
    if (taskProgressAccepted) {
        updateBatchDownloadPresentationProgress(effectiveProgress)
        persistBatchMemberProgress(appContext, effectiveProgress)
    }
    val currentBinding = activeProgressCheckpointBindings[progress.songKey]
        ?: return
    if (
        currentBinding != binding ||
        currentBinding.attemptId != effectiveProgress.attemptId ||
        currentBinding.operationId != effectiveProgress.operationId ||
        (currentBinding.admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = appContext,
                admissionTicket = currentBinding.admissionTicket,
                stableKey = effectiveProgress.songKey,
                operationId = currentBinding.operationId
            ))
    ) {
        return
    }
    enqueueProgressCheckpoint(appContext, effectiveProgress, currentBinding)
}

internal suspend fun GlobalDownloadManager.restoreTaskProgressCheckpoint(
    context: Context,
    song: SongItem,
    binding: ActiveProgressCheckpointBinding
) {
    val songKey = song.stableKey()
    val checkpoint = DownloadExecutionRoomStore.readProgressCheckpoint(
        context = context,
        operationId = binding.operationId,
        stableKey = songKey,
        attemptId = binding.attemptId
    ) ?: return
    // 启动阶段已经一次性建立工作文件快照，按 operation 优先回填，避免
    // 每首歌重复枚举 staging 目录并让大量任务退回到 0
    val durableBytes = pendingWorkingProgressSnapshot
        .workingFileBytes(
            operationId = binding.operationId,
            stableKey = songKey
        )
    val restoredProgress = resolveRecoveredDownloadProgress(
        workingFileBytes = durableBytes,
        checkpointTotalBytes = checkpoint.totalBytes,
        checkpointBytesWritten = checkpoint.bytesWritten
    ) ?: return
    val restoredDownloadProgress = AudioDownloadManager.DownloadProgress(
            songKey = songKey,
            songId = song.id,
            fileName = song.name,
            bytesRead = restoredProgress.bytesRead,
            totalBytes = restoredProgress.totalBytes,
            speedBytesPerSec = 0L,
            attemptId = binding.attemptId,
            operationId = binding.operationId
        )
    recordLatestDownloadProgress(restoredDownloadProgress)
    if (!taskStore.restoreProgress(restoredDownloadProgress)) {
        return
    }
    taskStore.findTask(songKey)
        ?.progress
        ?.takeIf { progress -> progress.attemptId == binding.attemptId }
        ?.let(::updateBatchDownloadPresentationProgress)
}
