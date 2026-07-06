package moe.ouom.neriplayer.core.download

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.download/GlobalDownloadManager
 * Updated: 2026/3/24
 */

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.currentTrafficNetworkType
import org.json.JSONObject
import kotlin.LazyThreadSafetyMode

/**
 * 全局下载管理器，统一维护下载任务和本地下载列表
 */
object GlobalDownloadManager {
    private const val TAG = "GlobalDownloadManager"
    private const val INITIAL_SCAN_DELAY_MS = 1_500L
    private const val DOWNLOAD_CATALOG_CACHE_FILE_NAME = "downloaded_song_catalog_v3.json"
    private const val DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS = 1_200L
    private const val DOWNLOAD_TASK_COMPLETED_RETENTION_MS = 800L
    private const val DOWNLOAD_CATALOG_RECONCILE_DELAY_MS = 1_200L
    private const val DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS = 5_000L
    private const val METADATA_WRITE_MAX_ATTEMPTS = 3
    private const val METADATA_WRITE_RETRY_DELAY_MS = 200L
    private const val METADATA_POST_PROCESSING_MAX_ATTEMPTS = 3
    private const val METADATA_POST_PROCESSING_RETRY_DELAY_MS = 350L
    private const val DOWNLOAD_TASK_PROGRESS_EMIT_INTERVAL_NS = 450_000_000L
    private const val METADATA_POST_PROCESSING_PARALLELISM = 2
    internal const val PLAYBACK_METADATA_HYDRATION_DELAY_MS = 1_500L
    internal const val LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class TrafficRiskDownloadRequest(
        val id: Long,
        val songs: List<SongItem>,
        val networkType: TrafficNetworkType,
        val isBatch: Boolean
    ) {
        val songCount: Int
            get() = songs.size
    }

    private val _trafficRiskDownloadRequests =
        MutableSharedFlow<TrafficRiskDownloadRequest>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val trafficRiskDownloadRequests: SharedFlow<TrafficRiskDownloadRequest> =
        _trafficRiskDownloadRequests

    data class MobileDataDownloadInterruptionRequest(
        val id: Long,
        val networkType: TrafficNetworkType,
        val taskCount: Int
    )

    private val _mobileDataDownloadInterruptionRequest =
        MutableStateFlow<MobileDataDownloadInterruptionRequest?>(null)
    val mobileDataDownloadInterruptionRequest:
        StateFlow<MobileDataDownloadInterruptionRequest?> =
        _mobileDataDownloadInterruptionRequest.asStateFlow()

    private val taskStore = DownloadTaskStore(
        scope = scope,
        progressEmitIntervalNs = DOWNLOAD_TASK_PROGRESS_EMIT_INTERVAL_NS
    )
    val downloadTasks: StateFlow<List<DownloadTask>> = taskStore.downloadTasks
    val downloadTaskSummary: StateFlow<DownloadTaskSummary> = taskStore.downloadTaskSummary
    val activeDownloadOperationsFlow: StateFlow<Boolean> = taskStore.activeDownloadOperationsFlow

    private val _downloadedSongs = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloadedSongs: StateFlow<List<DownloadedSong>> = _downloadedSongs.asStateFlow()
    private val _downloadPresenceVersion = MutableStateFlow(0)
    val downloadPresenceVersion: StateFlow<Int> = _downloadPresenceVersion.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val cancelledSongKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val catalogPersistenceLock = Any()
    private var refreshJob: Job? = null
    private var catalogPersistJob: Job? = null
    private var catalogReconcileJob: Job? = null
    private val metadataPostProcessingSemaphore = Semaphore(METADATA_POST_PROCESSING_PARALLELISM)

    @Volatile
    private var downloadedSongCatalogIndex = DownloadedSongCatalogIndex.EMPTY

    @Volatile
    private var downloadedSongCatalogReady = false

    @Volatile
    private var pendingRefresh = false

    @Volatile
    private var pendingForceRefresh = false

    private var initialized = false
    private val trafficRiskRequestIdGenerator = AtomicLong(0L)
    private val mobileDataInterruptionRequestIdGenerator = AtomicLong(0L)
    private val songExecutionLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingDownloadRecoveryMutex = Mutex()
    private val activeBatchDownloadJobs = Collections.newSetFromMap(ConcurrentHashMap<Job, Boolean>())
    private val pendingDownloadRecoveryStateLock = Any()

    @Volatile
    private var pendingDownloadRecoveryActive = false

    @Volatile
    private var mobileDataDownloadOverrideAllowed = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        observeDownloadProgress()
        scope.launch {
            val startupRecovery = ManagedDownloadStorage.consumeStartupRecoveryResult()
            val restoredCatalog = restorePersistedDownloadedSongs(appContext)
            recoverPendingResumableDownloads(appContext, reason = "startup")
            if (
                !shouldRunInitialDownloadScan(
                    catalogReady = restoredCatalog,
                    hasRecoveredEntries = startupRecovery.hasRecoveredEntries
                )
            ) {
                return@launch
            }
            delay(INITIAL_SCAN_DELAY_MS)
            scanLocalFiles(
                appContext,
                forceRefresh = startupRecovery.hasRecoveredEntries
            )
        }
    }

    private suspend fun recoverPendingResumableDownloads(
        context: Context,
        reason: String
    ) {
        pendingDownloadRecoveryMutex.withLock {
            recoverPendingResumableDownloadsLocked(context, reason)
        }
    }

    private suspend fun recoverPendingResumableDownloadsLocked(
        context: Context,
        reason: String
    ) {
        runCatching {
            val pendingQueuedDownloads = ManagedDownloadStorage.listPendingQueuedDownloads(context)
            val pendingDownloads = ManagedDownloadStorage.listPendingResumableDownloads(context)
            val cancelledDownloadKeys = ManagedDownloadStorage.listCancelledDownloadKeys(context)
            val recoveryCandidates = mergePendingDownloadRecoveryCandidates(
                queuedDownloads = pendingQueuedDownloads,
                resumableDownloads = pendingDownloads,
                cancelledKeys = cancelledDownloadKeys
            )
            val recoveryCandidateKeys = recoveryCandidates
                .mapTo(mutableSetOf()) { candidate -> candidate.song.stableKey() }
            removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys)
            if (recoveryCandidates.isEmpty()) {
                if (pendingQueuedDownloads.isEmpty() && pendingDownloads.isEmpty()) {
                    ManagedDownloadStorage.clearCancelledDownloadKeys(context)
                }
                return
            }

            val resumableSongs = mutableListOf<SongItem>()
            val settledSongKeys = mutableSetOf<String>()
            recoveryCandidates.forEach { candidate ->
                val song = candidate.song
                val songKey = song.stableKey()
                when {
                    candidate.cancelled -> {
                        candidate.workingFile?.let(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)
                        settledSongKeys += songKey
                    }
                    shouldSkipDownload(context, song) -> {
                        candidate.workingFile?.let(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)
                        settledSongKeys += songKey
                    }
                    findExistingDownloadedAudio(context, song) != null -> {
                        candidate.workingFile?.let(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)
                        settledSongKeys += songKey
                    }
                    else -> {
                        resumableSongs += song
                    }
                }
            }

            forgetPendingDownloadQueueEntries(context, settledSongKeys)
            ManagedDownloadStorage.removeCancelledDownloadKeys(context, settledSongKeys)

            if (resumableSongs.isEmpty()) {
                return
            }

            NPLogger.d(
                TAG,
                "检测到未完成下载，准备自动恢复: reason=$reason, count=${resumableSongs.size}, queued=${pendingQueuedDownloads.size}, partial=${pendingDownloads.size}"
            )
            startBatchDownload(
                context = context,
                songs = resumableSongs,
                skipTrafficRiskPrompt = true,
                cleanupBeforeStart = false
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "自动恢复未完成下载失败: ${error.message}", error)
        }
    }

    private fun removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys: Set<String>) {
        taskStore.removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys)
    }

    fun recoverPendingDownloadsForNetworkRestored(context: Context, reason: String) {
        val appContext = context.applicationContext
        scope.launch {
            if (appContext.currentTrafficNetworkType() != TrafficNetworkType.WIFI) {
                return@launch
            }
            if (!hasPendingRecoveryCandidates(appContext)) {
                return@launch
            }
            mobileDataDownloadOverrideAllowed = false
            _mobileDataDownloadInterruptionRequest.value = null
            if (!tryBeginPendingDownloadRecovery()) {
                return@launch
            }
            try {
                waitForActiveDownloadJobsToSettle()
                if (hasActiveDownloadOperations()) {
                    return@launch
                }
                recoverPendingResumableDownloads(appContext, reason = reason)
                delay(1_500L)
            } finally {
                finishPendingDownloadRecovery()
            }
        }
    }

    private fun tryBeginPendingDownloadRecovery(): Boolean {
        synchronized(pendingDownloadRecoveryStateLock) {
            if (pendingDownloadRecoveryActive) {
                return false
            }
            pendingDownloadRecoveryActive = true
            return true
        }
    }

    fun hasPendingRecoveryCandidates(context: Context): Boolean {
        val appContext = context.applicationContext
        if (ManagedDownloadStorage.listPendingQueuedDownloads(appContext).isNotEmpty()) {
            return true
        }
        if (ManagedDownloadStorage.listPendingResumableDownloads(appContext).isNotEmpty()) {
            return true
        }
        return downloadTasks.value.any { task ->
            task.status == DownloadStatus.WAITING_NETWORK
        }
    }

    private fun finishPendingDownloadRecovery() {
        synchronized(pendingDownloadRecoveryStateLock) {
            pendingDownloadRecoveryActive = false
        }
    }

    private suspend fun waitForActiveDownloadJobsToSettle() {
        repeat(20) {
            if (activeBatchDownloadJobs.isEmpty()) {
                return
            }
            delay(100L)
        }
    }

    fun hasActiveDownloadOperations(): Boolean {
        return taskStore.hasActiveDownloadOperations()
    }

    private fun publishDownloadedSongs(
        context: Context,
        songs: List<DownloadedSong>,
        persistCatalog: Boolean
    ) {
        _downloadedSongs.value = songs
        downloadedSongCatalogIndex = buildDownloadedSongCatalogIndex(songs)
        downloadedSongCatalogReady = true
        _downloadPresenceVersion.value = _downloadPresenceVersion.value + 1
        if (persistCatalog) {
            scheduleDownloadedSongsCatalogPersist(context, songs)
        }
    }

    private fun notifyDownloadPresenceChanged() {
        _downloadPresenceVersion.value = _downloadPresenceVersion.value + 1
    }

    private fun scheduleDownloadedSongsCatalogPersist(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        val appContext = context.applicationContext
        synchronized(catalogPersistenceLock) {
            catalogPersistJob?.cancel()
            catalogPersistJob = scope.launch {
                delay(DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS)
                persistDownloadedSongsCatalog(appContext, songs)
            }
        }
    }

    internal fun buildDownloadedSongCatalogIndex(
        songs: List<DownloadedSong>
    ): DownloadedSongCatalogIndex {
        return moe.ouom.neriplayer.core.download.buildDownloadedSongCatalogIndex(songs)
    }

    private fun observeDownloadProgress() {
        scope.launch {
            AudioDownloadManager.progressFlow.collect { progress ->
                progress?.let(::updateDownloadProgress)
            }
        }
    }

    private fun updateDownloadProgress(progress: AudioDownloadManager.DownloadProgress) {
        taskStore.updateProgress(progress)
    }

    private suspend fun finalizeCompletedDownload(
        context: Context,
        song: SongItem,
        refreshCatalog: Boolean,
        expectedAttemptId: Long? = null
    ) {
        val songKey = song.stableKey()
        val sidecarReferences = AudioDownloadManager.consumeCompletedSidecarReferences(songKey)
        val storedAudio = resolveStoredAudio(context, song)
        val currentTask = taskStore.findTask(songKey)
        if (!shouldApplyTaskMutation(currentTask, expectedAttemptId)) {
            NPLogger.d(
                TAG,
                "忽略过期下载完成回调: song=${song.name}, expectedAttemptId=$expectedAttemptId, currentAttemptId=${currentTask?.attemptId}"
            )
            rollbackStaleCompletedDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences
            )
            return
        }
        when (
            resolveCompletedDownloadFinalizationAction(
                hasStoredAudio = storedAudio != null,
                cancelled = isSongCancelled(songKey)
            )
        ) {
            CompletedDownloadFinalizationAction.ROLLBACK_CANCELLED -> {
                handleCancelledCompletedDownload(
                    context = context,
                    song = song,
                    songKey = songKey,
                    storedAudio = storedAudio,
                    sidecarReferences = sidecarReferences,
                    expectedAttemptId = expectedAttemptId
                )
                return
            }
            CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO -> {
                NPLogger.w(TAG, "下载完成但未找到已下载文件: ${song.name}")
                updateTaskStatus(
                    songKey,
                    DownloadStatus.COMPLETED,
                    expectedAttemptId = expectedAttemptId
                )
                forgetPendingDownloadQueueEntries(context, setOf(songKey))
                scheduleCompletedTaskRemoval(songKey, expectedAttemptId = expectedAttemptId)
                scheduleCatalogReconcile(context, forceRefresh = true)
                return
            }
            CompletedDownloadFinalizationAction.COMPLETE -> Unit
        }
        val resolvedStoredAudio = storedAudio ?: return

        val postProcessingEnabled = isDownloadMetadataPostProcessingEnabled(context)
        if (!persistDownloadedMetadata(
            context = context,
            audio = resolvedStoredAudio,
            song = song,
            sidecarReferences = sidecarReferences,
            downloadFinalized = !postProcessingEnabled
        )) {
            rollbackFailedFinalization(
                context = context,
                song = song,
                songKey = songKey,
                storedAudio = resolvedStoredAudio,
                sidecarReferences = sidecarReferences,
                expectedAttemptId = expectedAttemptId,
                reason = "metadata_write"
            )
            return
        }
        if (
            handleCancelledCompletedDownload(
                context = context,
                song = song,
                songKey = songKey,
                storedAudio = resolvedStoredAudio,
                sidecarReferences = sidecarReferences,
                expectedAttemptId = expectedAttemptId
            )
        ) {
            return
        }

        if (postProcessingEnabled) {
            if (
                !runDownloadedAudioMetadataPostProcessing(
                    context = context,
                    audio = resolvedStoredAudio,
                    song = song,
                    sidecarReferences = sidecarReferences
                )
            ) {
                rollbackFailedFinalization(
                    context = context,
                    song = song,
                    songKey = songKey,
                    storedAudio = resolvedStoredAudio,
                    sidecarReferences = sidecarReferences,
                    expectedAttemptId = expectedAttemptId,
                    reason = "tag_post_processing"
                )
                return
            }
            if (
                handleCancelledCompletedDownload(
                    context = context,
                    song = song,
                    songKey = songKey,
                    storedAudio = resolvedStoredAudio,
                    sidecarReferences = sidecarReferences,
                    expectedAttemptId = expectedAttemptId
                )
            ) {
                return
            }
            if (!persistDownloadedMetadata(
                context = context,
                audio = resolvedStoredAudio,
                song = song,
                sidecarReferences = sidecarReferences,
                downloadFinalized = true
            )) {
                rollbackFailedFinalization(
                    context = context,
                    song = song,
                    songKey = songKey,
                    storedAudio = resolvedStoredAudio,
                    sidecarReferences = sidecarReferences,
                    expectedAttemptId = expectedAttemptId,
                    reason = "final_metadata_write"
                )
                return
            }
        }

        publishCompletedDownloadOptimistically(
            context = context,
            song = song,
            storedAudio = resolvedStoredAudio,
            sidecarReferences = sidecarReferences
        )
        if (
            handleCancelledCompletedDownload(
                context = context,
                song = song,
                songKey = songKey,
                storedAudio = resolvedStoredAudio,
                sidecarReferences = sidecarReferences,
                expectedAttemptId = expectedAttemptId
            )
        ) {
            return
        }
        updateTaskStatus(
            songKey,
            DownloadStatus.COMPLETED,
            expectedAttemptId = expectedAttemptId
        )
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        scheduleCompletedTaskRemoval(songKey, expectedAttemptId = expectedAttemptId)
        if (refreshCatalog) {
            // 正常完成时前面已经做过 optimistic publish 和 snapshot 增量更新
            // 这里继续走轻量 reconcile，避免非私有目录每首歌都强制全量重扫
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
    }

    private suspend fun runDownloadedAudioMetadataPostProcessing(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ): Boolean {
        val songKey = song.stableKey()
        var lastError: Throwable? = null
        repeat(METADATA_POST_PROCESSING_MAX_ATTEMPTS) { attempt ->
            if (isSongCancelled(songKey)) {
                return true
            }
            val writeResult = runCatching {
                metadataPostProcessingSemaphore.withPermit {
                    val standardizedLyricEmbeddingEnabled =
                        isStandardizedLyricEmbeddingEnabled(context)
                    DownloadedAudioTagWriter.write(
                        context = context,
                        audio = audio,
                        song = song,
                        sidecarReferences = sidecarReferences,
                        standardizedLyricEmbeddingEnabled = standardizedLyricEmbeddingEnabled
                    )
                }
            }
            if (writeResult.getOrDefault(false)) {
                return true
            }
            lastError = writeResult.exceptionOrNull()
                ?: IllegalStateException("TagLib 未确认标签写入成功")
            if (isSongCancelled(songKey)) {
                return true
            }
            if (attempt < METADATA_POST_PROCESSING_MAX_ATTEMPTS - 1) {
                NPLogger.w(
                    TAG,
                    "元信息后处理失败，准备重试(第${attempt + 1}次): ${audio.name} - ${lastError?.message}"
                )
                delay(METADATA_POST_PROCESSING_RETRY_DELAY_MS * (attempt + 1))
            }
        }

        NPLogger.e(
            TAG,
            "元信息后处理最终失败: ${audio.name} - ${lastError?.message}",
            lastError
        )
        return false
    }

    private suspend fun isDownloadMetadataPostProcessingEnabled(context: Context): Boolean {
        val setting = AutoSettingsSchema.download.downloadMetadataPostProcessingEnabled
        return runCatching {
            context.applicationContext.autoSettingFlow(setting).first()
        }.getOrElse { error ->
            NPLogger.w(TAG, "读取元信息后处理设置失败，按默认值处理: ${error.message}")
            setting.defaultValue
        }
    }

    private suspend fun isStandardizedLyricEmbeddingEnabled(context: Context): Boolean {
        val setting = AutoSettingsSchema.download.standardizedLyricEmbeddingEnabled
        return runCatching {
            context.applicationContext.autoSettingFlow(setting).first()
        }.getOrElse { error ->
            NPLogger.w(TAG, "读取标准化歌词嵌入设置失败，按默认值处理: ${error.message}")
            setting.defaultValue
        }
    }

    private suspend fun handleCancelledCompletedDownload(
        context: Context,
        song: SongItem,
        songKey: String,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        expectedAttemptId: Long? = null
    ): Boolean {
        if (!isSongCancelled(songKey)) {
            return false
        }

        NPLogger.d(TAG, "下载最终入库阶段检测到取消，开始回滚: ${song.name}")
        runCatching {
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "下载最终入库回滚失败: ${song.name}, ${error.message}", error)
        }
        clearSongCancelled(songKey)
        removeDownloadTask(
            songKey,
            expectedAttemptId = expectedAttemptId
        )
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        return true
    }

    private suspend fun rollbackFailedFinalization(
        context: Context,
        song: SongItem,
        songKey: String,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        expectedAttemptId: Long?,
        reason: String
    ) {
        NPLogger.w(TAG, "下载最终入库失败，开始回滚半成品: song=${song.name}, reason=$reason")
        runCatching {
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "下载失败回滚半成品失败: ${song.name}, ${error.message}", error)
        }
        updateTaskStatus(
            songKey,
            DownloadStatus.FAILED,
            expectedAttemptId = expectedAttemptId
        )
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
    }

    private suspend fun rollbackStaleCompletedDownload(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ) {
        if (storedAudio == null && (sidecarReferences?.isEmpty != false)) {
            return
        }
        runCatching {
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "过期下载结果回滚失败: ${song.name}, ${error.message}", error)
        }
    }

    private suspend fun cleanupDownloadArtifactsBeforeFreshStart(
        context: Context,
        song: SongItem
    ) {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
        rollbackUnfinalizedDownloadArtifact(appContext, song)
        ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, setOf(songKey))
    }

    private suspend fun cleanupCancelledDownloadArtifacts(
        context: Context,
        song: SongItem
    ) {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
        rollbackUnfinalizedDownloadArtifact(appContext, song)
        ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, setOf(songKey))
    }

    private suspend fun rollbackUnfinalizedDownloadArtifact(
        context: Context,
        song: SongItem
    ): Boolean {
        val audio = ManagedDownloadStorage.findDownloadedAudio(
            context = context,
            song = song,
            forceRefresh = true
        ) ?: return false
        val metadata = readDownloadedMetadata(context, audio)
        if (!isUnfinalizedDownloadedMetadata(metadata)) {
            return false
        }
        NPLogger.w(TAG, "发现未最终确认的下载半成品，回滚: song=${song.name}, file=${audio.name}")
        rollbackCancelledDownload(
            context = context,
            song = song,
            storedAudio = audio
        )
        return true
    }

    fun scanLocalFiles(context: Context, forceRefresh: Boolean = false) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (refreshJob?.isActive == true) {
                pendingRefresh = true
                pendingForceRefresh = pendingForceRefresh || forceRefresh
                return
            }

            refreshJob = scope.launch {
                var nextForceRefresh = forceRefresh
                while (true) {
                    reloadDownloadedSongs(appContext, forceRefresh = nextForceRefresh)
                    nextForceRefresh = consumePendingRefreshRequest() ?: break
                }
            }
        }
    }

    private fun consumePendingRefreshRequest(): Boolean? = synchronized(this) {
        val shouldRefreshAgain = pendingRefresh
        val shouldForceRefresh = pendingForceRefresh
        pendingRefresh = false
        pendingForceRefresh = false
        if (!shouldRefreshAgain) {
            refreshJob = null
            return null
        }
        shouldForceRefresh
    }

    private suspend fun reloadDownloadedSongs(context: Context, forceRefresh: Boolean = false) {
        _isRefreshing.value = true
        try {
            var snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = forceRefresh
            )
            val unfinalizedAudios = snapshot.audioEntries.filter { storedAudio ->
                isUnfinalizedDownloadedMetadata(snapshot.metadataByAudioName[storedAudio.name])
            }
            if (unfinalizedAudios.isNotEmpty()) {
                unfinalizedAudios.forEach { storedAudio ->
                    rollbackUnfinalizedDownloadedAudio(
                        context = context,
                        storedAudio = storedAudio,
                        snapshot = snapshot
                    )
                }
                snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                    context = context,
                    forceRefresh = true
                )
            }
            val songs = snapshot.audioEntries
                .filterNot { storedAudio ->
                    isUnfinalizedDownloadedMetadata(snapshot.metadataByAudioName[storedAudio.name])
                }
                .mapNotNull { storedAudio ->
                    runCatching {
                        buildDownloadedSong(
                            context = context,
                            storedAudio = storedAudio,
                            snapshot = snapshot,
                            allowSlowLocalInspection = false
                        )
                    }.onFailure { error ->
                        NPLogger.w(TAG, "解析下载文件失败: ${storedAudio.name} - ${error.message}")
                    }.getOrNull()
                }
                .sortedByDescending { it.downloadTime }
            if (_downloadedSongs.value != songs) {
                publishDownloadedSongs(context, songs, persistCatalog = true)
            } else if (!downloadedSongCatalogReady) {
                downloadedSongCatalogIndex = buildDownloadedSongCatalogIndex(songs)
                downloadedSongCatalogReady = true
            }
        } catch (error: Exception) {
            NPLogger.e(TAG, "扫描已下载文件失败: ${error.message}", error)
        } finally {
            _isRefreshing.value = false
        }
    }

    private suspend fun rollbackUnfinalizedDownloadedAudio(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        val metadata = snapshot.metadataByAudioName[storedAudio.name]
        if (isUnfinalizedDownloadStillActive(metadata)) {
            NPLogger.d(TAG, "跳过活跃下载的未最终确认文件: file=${storedAudio.name}")
            return
        }
        NPLogger.w(TAG, "扫描发现未最终确认的下载半成品，回滚: file=${storedAudio.name}")
        runCatching {
            removeManagedDownloadArtifacts(
                context = context,
                songName = storedAudio.nameWithoutExtension,
                storedAudio = storedAudio,
                songId = metadata?.songId ?: 0L,
                candidateBaseNames = candidateManagedDownloadBaseNames(storedAudio.nameWithoutExtension)
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "扫描回滚未完成下载半成品失败: ${storedAudio.name}, ${error.message}", error)
        }
    }

    private fun isUnfinalizedDownloadStillActive(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): Boolean {
        val stableKey = metadata?.stableKey?.takeIf(String::isNotBlank) ?: return false
        val hasActiveTask = taskStore.currentTasks().any { task ->
            task.song.stableKey() == stableKey &&
                (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING)
        }
        return hasActiveTask || AudioDownloadManager.isSongDownloadActive(stableKey)
    }

    fun syncDownloadedSongMetadata(song: SongItem) {
        scope.launch {
            val context = AppContainer.applicationContext
            val storedAudio = resolveStoredAudio(context, song) ?: return@launch

            persistDownloadedMetadata(context, storedAudio, song)

            val currentSongs = _downloadedSongs.value
            var updated = false
            var shouldPublishCatalog = false
            val refreshedSongs = currentSongs.map { downloaded ->
                if (downloaded.filePath == storedAudio.reference) {
                    updated = true
                    buildDownloadedSong(
                        context = context,
                        storedAudio = storedAudio,
                        existingDownloadTime = downloaded.downloadTime,
                        allowSlowLocalInspection = false
                    ).also { refreshed ->
                        shouldPublishCatalog = shouldPublishDownloadedSongCatalogUpdate(
                            currentSong = downloaded,
                            updatedSong = refreshed
                        )
                    }
                } else {
                    downloaded
                }
            }

            if (updated) {
                scheduleDownloadedSongsCatalogPersist(context, refreshedSongs)
                if (shouldPublishCatalog) {
                    publishDownloadedSongs(context, refreshedSongs, persistCatalog = false)
                }
            } else {
                reloadDownloadedSongs(context)
            }
        }
    }

    private suspend fun buildDownloadedSong(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null,
        existingDownloadTime: Long? = null,
        loadLyricContents: Boolean = false,
        resolveLyricFallbacks: Boolean = false,
        allowSlowLocalInspection: Boolean = true
    ): DownloadedSong {
        val effectiveSnapshot = snapshot ?: ManagedDownloadStorage.buildDownloadLibrarySnapshot(context)
        val metadataEntry = effectiveSnapshot.metadataEntriesByAudioName[storedAudio.name]
        val snapshotMetadata = effectiveSnapshot.metadataByAudioName[storedAudio.name]
        val metadata = if (loadLyricContents || resolveLyricFallbacks) {
            readDownloadedMetadata(
                context = context,
                audio = storedAudio,
                metadataEntry = metadataEntry
            ) ?: snapshotMetadata
        } else {
            snapshotMetadata ?: readDownloadedMetadata(
                context = context,
                audio = storedAudio,
                metadataEntry = metadataEntry
            )
        }
        val (parsedArtist, parsedTitle) = parseDownloadedFileName(storedAudio.name)
        val cachedCoverReference = resolveAccessibleManagedReference(
            context = context,
            snapshot = effectiveSnapshot,
            metadata?.coverPath,
            metadata?.coverUrl,
            metadata?.originalCoverUrl
        )
            ?: findIndexedCoverReference(storedAudio, effectiveSnapshot)
        val indexedLyricReference = findIndexedLyricReference(
            audio = storedAudio,
            songId = metadata?.songId,
            translated = false,
            snapshot = effectiveSnapshot
        )
        val lyricReference = if (loadLyricContents) {
            resolveAccessibleManagedReference(
                context = context,
                snapshot = effectiveSnapshot,
                metadata?.lyricPath,
                indexedLyricReference
            )
        } else {
            null
        }
        val fileLyric = if (loadLyricContents) {
            lyricReference?.let { ManagedDownloadStorage.readText(context, it) }
        } else {
            null
        }
        val indexedLyric = if (
            loadLyricContents &&
            resolveLyricFallbacks &&
            fileLyric.isNullOrBlank() &&
            lyricReference != indexedLyricReference
        ) {
            findIndexedLyricText(
                context = context,
                audio = storedAudio,
                songId = metadata?.songId,
                translated = false,
                snapshot = effectiveSnapshot
            )
        } else {
            null
        }
        val indexedTranslatedLyricReference = findIndexedLyricReference(
            audio = storedAudio,
            songId = metadata?.songId,
            translated = true,
            snapshot = effectiveSnapshot
        )
        val translatedLyricReference = if (loadLyricContents) {
            resolveAccessibleManagedReference(
                context = context,
                snapshot = effectiveSnapshot,
                metadata?.translatedLyricPath,
                indexedTranslatedLyricReference
            )
        } else {
            null
        }
        val fileTranslatedLyric = if (loadLyricContents) {
            translatedLyricReference?.let { ManagedDownloadStorage.readText(context, it) }
        } else {
            null
        }
        val indexedTranslatedLyric = if (
            loadLyricContents &&
            resolveLyricFallbacks &&
            fileTranslatedLyric.isNullOrBlank() &&
            translatedLyricReference != indexedTranslatedLyricReference
        ) {
            findIndexedLyricText(
                context = context,
                audio = storedAudio,
                songId = metadata?.songId,
                translated = true,
                snapshot = effectiveSnapshot
            )
        } else {
            null
        }
        val needsLocalLyricFallback = loadLyricContents &&
            fileLyric.isNullOrBlank() &&
            metadata?.matchedLyric == null &&
            metadata?.originalLyric == null &&
            indexedLyric.isNullOrBlank()
        val localDetails by lazy(LazyThreadSafetyMode.NONE) {
            if (
                shouldInspectDownloadedAudioDetails(
                    allowSlowLocalInspection = allowSlowLocalInspection,
                    metadata = metadata,
                    coverReference = cachedCoverReference,
                    needsLocalLyricFallback = needsLocalLyricFallback
                )
            ) {
                inspectDownloadedAudioDetails(context, storedAudio)
            } else {
                null
            }
        }
        val coverReference = cachedCoverReference ?: localDetails?.coverUri
        val matchedLyric = if (loadLyricContents) {
            resolveDownloadedLyricOverride(
                fileLyric = fileLyric,
                embeddedMatchedLyric = metadata?.matchedLyric,
                embeddedOriginalLyric = metadata?.originalLyric,
                localLyricContent = localDetails?.lyricContent,
                indexedLyricContent = indexedLyric
            )
        } else {
            null
        }
        val matchedTranslatedLyric = if (loadLyricContents) {
            resolveDownloadedLyricOverride(
                fileLyric = fileTranslatedLyric,
                embeddedMatchedLyric = metadata?.matchedTranslatedLyric,
                embeddedOriginalLyric = metadata?.originalTranslatedLyric,
                localLyricContent = null,
                indexedLyricContent = if (
                    fileTranslatedLyric.isNullOrBlank() &&
                    metadata?.matchedTranslatedLyric == null &&
                    metadata?.originalTranslatedLyric == null
                ) {
                    indexedTranslatedLyric
                } else {
                    null
                }
            )
        } else {
            null
        }
        val originalLyric = metadata?.originalLyric
        val originalTranslatedLyric = metadata?.originalTranslatedLyric

        return DownloadedSong(
            id = metadata?.songId ?: storedAudio.reference.hashCode().toLong(),
            name = metadata?.name?.takeIf(String::isNotBlank) ?: localDetails?.title?.takeIf(String::isNotBlank) ?: parsedTitle,
            artist = metadata?.artist?.takeIf(String::isNotBlank) ?: localDetails?.artist?.takeIf(String::isNotBlank) ?: parsedArtist,
            album = (if (metadata == null) localDetails?.album?.takeIf(String::isNotBlank) else null)
                ?: context.getString(R.string.local_files),
            filePath = storedAudio.reference,
            fileSize = storedAudio.sizeBytes,
            downloadTime = existingDownloadTime ?: storedAudio.lastModifiedMs,
            coverPath = coverReference,
            coverUrl = metadata?.coverUrl,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = metadata?.matchedLyricSource,
            matchedSongId = metadata?.matchedSongId,
            userLyricOffsetMs = metadata?.userLyricOffsetMs ?: 0L,
            customCoverUrl = metadata?.customCoverUrl,
            customName = metadata?.customName,
            customArtist = metadata?.customArtist,
            originalName = metadata?.originalName ?: localDetails?.originalTitle,
            originalArtist = metadata?.originalArtist ?: localDetails?.originalArtist,
            originalCoverUrl = metadata?.originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            mediaUri = storedAudio.playbackUri,
            durationMs = metadata?.durationMs?.takeIf { it > 0L } ?: localDetails?.durationMs ?: 0L,
            stableKey = metadata?.stableKey
        )
    }

    private fun parseDownloadedFileName(fileName: String): Pair<String, String> {
        val nameWithoutExt = fileName.substringBeforeLast('.', fileName)
        candidateManagedDownloadFileNameTemplates(ManagedDownloadStorage.currentDownloadFileNameTemplate())
            .asSequence()
            .mapNotNull { template -> parseManagedDownloadBaseName(nameWithoutExt, template) }
            .firstOrNull { parsed ->
                !parsed.title.isNullOrBlank() || !parsed.artist.isNullOrBlank()
            }
            ?.let { parsed ->
                return parsed.artist.orEmpty() to (parsed.title ?: nameWithoutExt)
            }
        val parts = nameWithoutExt.split(" - ", limit = 2)
        return if (parts.size >= 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            "" to nameWithoutExt
        }
    }

    private suspend fun persistDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
        downloadFinalized: Boolean = true
    ): Boolean {
        val identity = song.identity()
        val candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension)
        val coverReference = sidecarReferences?.coverReference
            ?: ManagedDownloadStorage.findCoverReference(context, audio)
            ?: ManagedDownloadStorage.findReusableCoverReference(
                context = context,
                song = song,
                excludedAudioName = audio.name
            )
        val lyricPath = sidecarReferences?.lyricReference
            ?: ManagedDownloadStorage.findLyricLocation(
                context = context,
                songId = song.id,
                candidateBaseNames = candidateBaseNames,
                translated = false
            )
        val translatedLyricPath = sidecarReferences?.translatedLyricReference
            ?: ManagedDownloadStorage.findLyricLocation(
                context = context,
                songId = song.id,
                candidateBaseNames = candidateBaseNames,
                translated = true
            )
        val payload = JSONObject().apply {
            put("stableKey", identity.stableKey())
            put("songId", song.id)
            put("identityAlbum", identity.album)
            put("name", song.name)
            put("artist", song.artist)
            put("coverUrl", song.coverUrl)
            put("matchedLyric", song.matchedLyric)
            put("matchedTranslatedLyric", song.matchedTranslatedLyric)
            put("matchedLyricSource", song.matchedLyricSource?.name)
            put("matchedSongId", song.matchedSongId)
            put("userLyricOffsetMs", song.userLyricOffsetMs)
            put("customCoverUrl", song.customCoverUrl)
            put("customName", song.customName)
            put("customArtist", song.customArtist)
            put("originalName", song.originalName)
            put("originalArtist", song.originalArtist)
            put("originalCoverUrl", song.originalCoverUrl)
            put("originalLyric", song.originalLyric)
            put("originalTranslatedLyric", song.originalTranslatedLyric)
            put("mediaUri", identity.mediaUri ?: song.mediaUri)
            put("channelId", song.channelId)
            put("audioId", song.audioId)
            put("subAudioId", song.subAudioId)
            put("playlistContextId", song.playlistContextId)
            put("coverPath", coverReference)
            put("lyricPath", lyricPath)
            put("translatedLyricPath", translatedLyricPath)
            put("durationMs", song.durationMs)
            put("downloadFinalized", downloadFinalized)
        }

        var lastError: Throwable? = null
        repeat(METADATA_WRITE_MAX_ATTEMPTS) { attempt ->
            val result = runCatching {
                ManagedDownloadStorage.saveMetadata(context, audio, payload.toString())
            }
            if (result.isSuccess) {
                NPLogger.d(
                    TAG,
                    "保存下载 metadata: file=${audio.name}, stableKey=${identity.stableKey()}, finalized=$downloadFinalized, lyricPath=$lyricPath, translatedLyricPath=$translatedLyricPath, coverPath=$coverReference"
                )
                return true
            }
            lastError = result.exceptionOrNull()
            if (attempt < METADATA_WRITE_MAX_ATTEMPTS - 1) {
                NPLogger.w(TAG, "写入下载元数据失败(第${attempt + 1}次): ${audio.name} - ${lastError?.message}")
                kotlinx.coroutines.delay(METADATA_WRITE_RETRY_DELAY_MS)
            }
        }
        NPLogger.e(TAG, "写入下载元数据最终失败: ${audio.name} - ${lastError?.message}", lastError)
        return false
    }

    private suspend fun readDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        metadataEntry: ManagedDownloadStorage.StoredEntry? = null
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        val resolvedMetadataEntry = metadataEntry
            ?: ManagedDownloadStorage.findMetadataForAudio(context, audio)
            ?: return null
        val raw = ManagedDownloadStorage.readText(context, resolvedMetadataEntry.reference) ?: return null
        return ManagedDownloadStorage.parseDownloadedAudioMetadataJson(raw)
    }

    private fun findIndexedLyricReference(
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val candidates = ManagedDownloadStorage.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            translated = translated
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            snapshot.lyricEntriesByName[candidate]?.reference
        }
    }

    private suspend fun findIndexedLyricText(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val reference = findIndexedLyricReference(
            audio = audio,
            songId = songId,
            translated = translated,
            snapshot = snapshot
        ) ?: return null
        return ManagedDownloadStorage.readText(context, reference)
    }

    private fun findIndexedCoverReference(
        audio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return findIndexedCoverReference(
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            snapshot = snapshot
        )
    }

    private fun findIndexedCoverReference(
        candidateBaseNames: List<String>,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return candidateBaseNames
            .firstNotNullOfOrNull { baseName ->
                sequenceOf("jpg", "jpeg", "png", "webp").firstNotNullOfOrNull { extension ->
                    snapshot.coverEntriesByName["$baseName.$extension"]?.reference
                }
            }
    }

    private fun findAllIndexedCoverReferences(
        candidateBaseNames: List<String>,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): List<String> {
        return candidateBaseNames
            .flatMap { baseName ->
                sequenceOf("jpg", "jpeg", "png", "webp")
                    .mapNotNull { extension ->
                        snapshot.coverEntriesByName["$baseName.$extension"]?.reference
                    }
            }
            .distinct()
    }

    private fun findIndexedLyricReference(
        candidateBaseNames: List<String>,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val candidates = ManagedDownloadStorage.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            translated = translated
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            snapshot.lyricEntriesByName[candidate]?.reference
        }
    }

    private fun findAllIndexedLyricReferences(
        candidateBaseNames: List<String>,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): List<String> {
        val candidates = ManagedDownloadStorage.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            translated = translated
        )
        return candidates
            .mapNotNull { candidate -> snapshot.lyricEntriesByName[candidate]?.reference }
            .distinct()
    }

    private suspend fun removeManagedDownloadArtifacts(
        context: Context,
        songName: String,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        songId: Long,
        candidateBaseNames: List<String>,
        explicitReferences: List<String> = emptyList()
    ): ManagedDownloadArtifactRemovalResult {
        var snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
            context = context,
            forceRefresh = false
        )
        if (storedAudio != null && snapshot.audioEntriesByLookupKey[storedAudio.reference] == null) {
            snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }
        val referencesToDelete = collectManagedDownloadArtifactReferences(
            snapshot = snapshot,
            storedAudio = storedAudio,
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            explicitReferences = explicitReferences
        )
        referencesToDelete.forEach { reference ->
            if (storedAudio?.reference == reference) {
                NPLogger.d(TAG, "删除下载音频: song=$songName, reference=$reference")
            } else {
                NPLogger.d(TAG, "删除下载关联文件: song=$songName, reference=$reference")
            }
        }
        val deletedReferences = if (referencesToDelete.isNotEmpty()) {
            ManagedDownloadStorage.deleteReferences(context, referencesToDelete)
        } else {
            emptySet()
        }
        if (referencesToDelete.isNotEmpty()) {
            return ManagedDownloadArtifactRemovalResult(
                requestedReferences = referencesToDelete,
                deletedReferences = deletedReferences
            )
        }
        return ManagedDownloadArtifactRemovalResult()
    }

    private fun collectManagedDownloadArtifactReferences(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        songId: Long,
        candidateBaseNames: List<String>,
        explicitReferences: List<String> = emptyList(),
        deletingAudioNames: Set<String> = emptySet()
    ): Set<String> {
        val metadataReference = storedAudio?.let { snapshot.metadataEntriesByAudioName[it.name]?.reference }
        val metadata = storedAudio?.let { snapshot.metadataByAudioName[it.name] }
        val resolvedSongId = metadata?.songId ?: songId.takeIf { it > 0L }
        val currentAudioName = storedAudio?.name
        val lyricReferences = buildList {
            metadata?.lyricPath?.let(::add)
            metadata?.translatedLyricPath?.let(::add)
            addAll(
                findAllIndexedLyricReferences(
                    candidateBaseNames = candidateBaseNames,
                    songId = resolvedSongId,
                    translated = false,
                    snapshot = snapshot
                )
            )
            addAll(
                findAllIndexedLyricReferences(
                    candidateBaseNames = candidateBaseNames,
                    songId = resolvedSongId,
                    translated = true,
                    snapshot = snapshot
                )
            )
        }
        val coverReferences = buildList {
            metadata?.coverPath?.let(::add)
            val indexedCoverBaseNames = storedAudio
                ?.let { candidateManagedDownloadBaseNames(it.nameWithoutExtension) }
                ?: candidateBaseNames
            addAll(findAllIndexedCoverReferences(indexedCoverBaseNames, snapshot))
        }

        return linkedSetOf<String>().apply {
            storedAudio?.reference?.let(::add)
            explicitReferences
                .plus(listOfNotNull(metadataReference))
                .plus(coverReferences)
                .plus(lyricReferences)
                .distinct()
                .forEach { reference ->
                    if (
                        metadataReference == null ||
                        reference == metadataReference ||
                        !isReferenceOwnedByOtherDownload(
                            snapshot = snapshot,
                            currentAudioName = currentAudioName,
                            reference = reference,
                            deletingAudioNames = deletingAudioNames
                        )
                    ) {
                        add(reference)
                    }
                }
        }
    }

    private suspend fun buildManagedDownloadDeletePlans(
        context: Context,
        songs: List<DownloadedSong>
    ): List<ManagedDownloadSongDeletePlan> {
        if (songs.isEmpty()) {
            return emptyList()
        }
        var snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
            context = context,
            forceRefresh = false
        )
        var deleteContexts = songs.map { song ->
            buildManagedDownloadSongDeleteContext(
                context = context,
                song = song,
                snapshot = snapshot,
                allowFallbackLookup = true
            )
        }
        if (deleteContexts.any(ManagedDownloadSongDeleteContext::requiresSnapshotRefresh)) {
            snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
            deleteContexts = songs.map { song ->
                buildManagedDownloadSongDeleteContext(
                    context = context,
                    song = song,
                    snapshot = snapshot,
                    allowFallbackLookup = false
                )
            }
        }
        val deletingAudioNames = deleteContexts.mapNotNullTo(mutableSetOf()) { it.storedAudio?.name }
        return deleteContexts.map { deleteContext ->
            ManagedDownloadSongDeletePlan(
                song = deleteContext.song,
                requestedReferences = collectManagedDownloadArtifactReferences(
                    snapshot = snapshot,
                    storedAudio = deleteContext.storedAudio,
                    songId = deleteContext.song.id,
                    candidateBaseNames = deleteContext.candidateBaseNames,
                    explicitReferences = deleteContext.explicitReferences,
                    deletingAudioNames = deletingAudioNames
                )
            )
        }
    }

    private suspend fun buildManagedDownloadSongDeleteContext(
        context: Context,
        song: DownloadedSong,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        allowFallbackLookup: Boolean
    ): ManagedDownloadSongDeleteContext {
        val locationReference = resolveDownloadedSongPlaybackReference(song)
        val snapshotStoredAudio = locationReference?.let(snapshot.audioEntriesByLookupKey::get)
        val fallbackStoredAudio = if (snapshotStoredAudio == null && allowFallbackLookup) {
            resolveStoredAudio(context, locationReference)
        } else {
            null
        }
        val storedAudio = snapshotStoredAudio ?: fallbackStoredAudio
        val requiresSnapshotRefresh = fallbackStoredAudio?.reference?.let { reference ->
            snapshot.audioEntriesByLookupKey[reference] == null
        } == true
        return ManagedDownloadSongDeleteContext(
            song = song,
            storedAudio = storedAudio,
            candidateBaseNames = candidateBaseNames(song, storedAudio?.nameWithoutExtension),
            explicitReferences = listOfNotNull(
                song.coverPath,
                locationReference.takeIf { storedAudio == null }
            ),
            requiresSnapshotRefresh = requiresSnapshotRefresh
        )
    }

    internal suspend fun rollbackCancelledDownload(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
    ) = runNonCancellableDownloadRollback {
        val appContext = context.applicationContext
        val resolvedStoredAudio = storedAudio ?: resolveStoredAudio(appContext, song)
        val candidateBaseNames = buildList {
            resolvedStoredAudio?.nameWithoutExtension
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            addAll(ManagedDownloadStorage.buildCandidateBaseNames(song))
        }.distinct()
        val explicitReferences = listOfNotNull(
            sidecarReferences?.coverReference,
            sidecarReferences?.lyricReference,
            sidecarReferences?.translatedLyricReference
        )

        NPLogger.d(
            TAG,
            "回滚已取消下载: song=${song.name}, audio=${resolvedStoredAudio?.reference}, baseNames=$candidateBaseNames, sidecars=$explicitReferences"
        )

        removeManagedDownloadArtifacts(
            context = appContext,
            songName = song.name,
            storedAudio = resolvedStoredAudio,
            songId = song.id,
            candidateBaseNames = candidateBaseNames,
            explicitReferences = explicitReferences
        )

        val currentSongs = _downloadedSongs.value
        val updatedSongs = currentSongs.filterNot { downloaded ->
            (resolvedStoredAudio != null && downloaded.filePath == resolvedStoredAudio.reference) ||
                matchesDownloadedSong(song, downloaded)
        }
        if (updatedSongs != currentSongs) {
            publishDownloadedSongs(appContext, updatedSongs, persistCatalog = true)
        } else {
            notifyDownloadPresenceChanged()
        }
        scheduleCatalogReconcile(appContext, forceRefresh = false)
        NPLogger.d(TAG, "回滚已取消下载完成: ${song.name}")
    }

    fun deleteDownloadedSong(context: Context, song: DownloadedSong) {
        deleteDownloadedSongs(context, listOf(song))
    }

    fun deleteDownloadedSongs(context: Context, songs: List<DownloadedSong>) {
        val appContext = context.applicationContext
        val targetSongs = songs.distinctBy(DownloadedSong::deletionIdentity)
        if (targetSongs.isEmpty()) {
            return
        }
        scope.launch {
            val previousSongs = _downloadedSongs.value
            val optimisticKeys = targetSongs.mapTo(mutableSetOf()) { it.deletionIdentity() }
            val optimisticSongs = previousSongs.filterNot { candidate ->
                optimisticKeys.contains(candidate.deletionIdentity())
            }
            if (optimisticSongs != previousSongs) {
                publishDownloadedSongs(appContext, optimisticSongs, persistCatalog = false)
            }
            try {
                val deletePlans = buildManagedDownloadDeletePlans(
                    context = appContext,
                    songs = targetSongs
                )
                val requestedReferences = mergeManagedRequestedReferences(
                    deletePlans.map(ManagedDownloadSongDeletePlan::requestedReferences)
                )
                val deletedReferences = if (requestedReferences.isNotEmpty()) {
                    ManagedDownloadStorage.deleteReferences(appContext, requestedReferences)
                } else {
                    emptySet()
                }
                val remainingReferences = resolveUndeletedManagedReferences(
                    requestedReferences = requestedReferences,
                    deletedReferences = deletedReferences
                ) { reference ->
                    ManagedDownloadStorage.exists(appContext, reference)
                }
                val remainingReferencesBySong = groupRemainingManagedReferencesByIdentity(
                    requestedReferencesByIdentity = deletePlans.associate { plan ->
                        plan.song.deletionIdentity() to plan.requestedReferences
                    },
                    remainingReferences = remainingReferences
                )
                val hasUnconfirmedDeletes = requestedReferences.size != deletedReferences.size
                var deletionFailed = remainingReferences.isNotEmpty()
                deletePlans.forEach { deletePlan ->
                    val remainingForSong = remainingReferencesBySong[deletePlan.song.deletionIdentity()].orEmpty()
                    if (remainingForSong.isNotEmpty()) {
                        deletionFailed = true
                        NPLogger.w(
                            TAG,
                            "删除下载文件不完整: ${deletePlan.song.name}, remaining=$remainingForSong"
                        )
                    } else {
                        NPLogger.d(TAG, "删除下载文件完成: ${deletePlan.song.name}")
                    }
                }
                if (!deletionFailed) {
                    scheduleDownloadedSongsCatalogPersist(appContext, optimisticSongs)
                }
                scheduleCatalogReconcile(
                    appContext,
                    forceRefresh = deletionFailed || hasUnconfirmedDeletes
                )
            } catch (error: Exception) {
                if (previousSongs != _downloadedSongs.value) {
                    publishDownloadedSongs(appContext, previousSongs, persistCatalog = false)
                }
                NPLogger.e(TAG, "删除下载文件失败: ${error.message}", error)
            }
        }
    }

    fun playDownloadedSong(context: Context, song: DownloadedSong) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val playbackReference = resolveDownloadedSongPlaybackReference(song)
                if (
                    playbackReference.isNullOrBlank() ||
                    !ManagedDownloadStorage.exists(appContext, playbackReference)
                ) {
                    NPLogger.w(TAG, "下载文件不存在: ${song.name}, reference=$playbackReference")
                    val updatedSongs = _downloadedSongs.value.filterNot { candidate ->
                        matchesDownloadedSongCatalogEntry(candidate, song)
                    }
                    if (updatedSongs != _downloadedSongs.value) {
                        publishDownloadedSongs(appContext, updatedSongs, persistCatalog = true)
                    }
                    scheduleCatalogReconcile(appContext, forceRefresh = false)
                    return@launch
                }

                val storedAudio = resolveStoredAudio(appContext, playbackReference)
                val playbackUri = storedAudio?.playbackUri
                    ?: ManagedDownloadStorage.toPlayableUri(playbackReference)
                    ?: playbackReference
                val quickSong = song.toPlaybackSongItem(
                    playbackUri = playbackUri,
                    storedAudio = storedAudio,
                    resolvedDurationMs = song.durationMs
                )
                withContext(Dispatchers.Main.immediate) {
                    PlayerManager.playPlaylist(listOf(quickSong), 0)
                }

                val refreshedSong = storedAudio?.let {
                    val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(appContext)
                    buildDownloadedSong(
                        context = appContext,
                        storedAudio = it,
                        snapshot = snapshot,
                        existingDownloadTime = song.downloadTime,
                        loadLyricContents = true,
                        resolveLyricFallbacks = true,
                        allowSlowLocalInspection = false
                    )
                } ?: song
                val hydratedDurationMs = refreshedSong.durationMs
                    .takeIf { it > 0L }
                    ?: quickSong.durationMs.takeIf { it > 0L }
                    ?: resolveAudioDuration(appContext, playbackUri)
                val hydratedSong = refreshedSong.toPlaybackSongItem(
                    playbackUri = playbackUri,
                    storedAudio = storedAudio,
                    resolvedDurationMs = hydratedDurationMs
                )
                if (hydratedSong != quickSong) {
                    delay(resolveDownloadedPlaybackHydrationDelayMs(quickSong, hydratedSong))
                    if (PlayerManager.currentSongFlow.value?.stableKey() != quickSong.stableKey()) {
                        return@launch
                    }
                    PlayerManager.hydrateSongMetadata(
                        originalSong = quickSong,
                        updatedSong = hydratedSong
                    )
                }
            } catch (error: Exception) {
                NPLogger.e(TAG, "播放下载文件失败: ${error.message}", error)
            }
        }
    }

    private fun DownloadedSong.toPlaybackSongItem(
        playbackUri: String,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        resolvedDurationMs: Long
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = resolvedDurationMs.coerceAtLeast(0L),
            coverUrl = coverPath ?: coverUrl,
            mediaUri = playbackUri,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = matchedLyricSource?.let {
                runCatching { MusicPlatform.valueOf(it) }.getOrNull()
            },
            matchedSongId = matchedSongId,
            userLyricOffsetMs = userLyricOffsetMs,
            customCoverUrl = customCoverUrl,
            customName = customName,
            customArtist = customArtist,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            localFileName = storedAudio?.name ?: filePath.substringAfterLast('/').takeIf(String::isNotBlank),
            localFilePath = storedAudio?.localFilePath ?: filePath.takeIf { it.startsWith("/") }
        )
    }

    private fun resolveAudioDuration(context: Context, location: String): Long {
        val uri = when {
            location.startsWith("/") -> Uri.fromFile(File(location))
            else -> location.toUri()
        }
        val quickDuration = runCatching {
            LocalMediaSupport.inspectQuick(context, uri).durationMs
        }.getOrNull()
        if (quickDuration != null && quickDuration > 0L) {
            return quickDuration
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrElse { error ->
            NPLogger.w(TAG, "读取下载音频时长失败: ${error.message}")
            0L
        }
    }

    fun hasDownloadedSongCached(song: SongItem): Boolean {
        return downloadedSongCatalogIndex.contains(song)
    }

    fun isDownloadedSongCatalogReady(): Boolean {
        return downloadedSongCatalogReady
    }

    fun findAccessibleDownloadedSongPlaybackUri(context: Context, song: SongItem): String? {
        val downloadedSong = downloadedSongCatalogIndex.find(song) ?: return null
        val reference = resolveDownloadedSongPlaybackReference(downloadedSong) ?: return null
        if (!ManagedDownloadStorage.isReferenceAccessible(context, reference)) {
            NPLogger.w(
                TAG,
                "下载目录缓存命中不可读引用，忽略本地回退: song=${song.name}, reference=$reference"
            )
            return null
        }
        return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
    }

    private fun downloadedSongsCacheFile(context: Context): File {
        return File(context.filesDir, DOWNLOAD_CATALOG_CACHE_FILE_NAME)
    }

    private fun restorePersistedDownloadedSongs(context: Context): Boolean {
        val rawPayload = runCatching {
            downloadedSongsCacheFile(context).takeIf(File::exists)?.readText(Charsets.UTF_8)
        }.onFailure {
            NPLogger.w(TAG, "读取下载歌曲目录缓存失败: ${it.message}")
        }.getOrNull() ?: return false

        val restoredSongs = runCatching {
            deserializeDownloadedSongsCatalog(
                raw = rawPayload,
                expectedCacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            )
        }.onFailure {
            NPLogger.w(TAG, "解析下载歌曲目录缓存失败: ${it.message}")
        }.getOrNull() ?: return false

        publishDownloadedSongs(context, restoredSongs, persistCatalog = false)
        return true
    }

    private fun persistDownloadedSongsCatalog(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        runCatching {
            downloadedSongsCacheFile(context).writeText(
                serializeDownloadedSongsCatalog(
                    cacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(context),
                    songs = songs
                ),
                Charsets.UTF_8
            )
        }.onFailure {
            NPLogger.w(TAG, "写入下载歌曲目录缓存失败: ${it.message}")
        }
    }

    fun startDownload(context: Context, song: SongItem) {
        startDownload(context, song, skipTrafficRiskPrompt = false)
    }

    private fun startDownload(
        context: Context,
        song: SongItem,
        skipTrafficRiskPrompt: Boolean,
        cleanupBeforeStart: Boolean = true
    ) {
        val appContext = context.applicationContext
        scope.launch {
            if (
                maybeRequestTrafficRiskDownloadConfirmation(
                    context = appContext,
                    songs = listOf(song),
                    isBatch = false,
                    skipTrafficRiskPrompt = skipTrafficRiskPrompt
                )
            ) {
                return@launch
            }
            rememberPendingDownloadQueue(appContext, listOf(song))
            startDownloadConfirmed(
                context = appContext,
                song = song,
                cleanupBeforeStart = cleanupBeforeStart
            )
        }
    }

    private fun startDownloadConfirmed(
        context: Context,
        song: SongItem,
        cleanupBeforeStart: Boolean
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val songKey = song.stableKey()
            if (!awaitSongCancellationSettled(songKey)) {
                return@launch
            }
            AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
            val attemptId = taskStore.prepareDownloadTask(song) ?: return@launch
            try {
                withSongExecutionLock(songKey) {
                    if (cleanupBeforeStart) {
                        cleanupDownloadArtifactsBeforeFreshStart(appContext, song)
                    }
                    if (shouldSkipDownload(appContext, song)) {
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        forgetPendingDownloadQueueEntries(appContext, setOf(songKey))
                        return@withSongExecutionLock
                    }

                    val existingAudio = findExistingDownloadedAudio(appContext, song)
                    if (existingAudio != null) {
                        finalizeCompletedDownload(
                            context = appContext,
                            song = song,
                            refreshCatalog = true,
                            expectedAttemptId = attemptId
                        )
                        return@withSongExecutionLock
                    }

                    while (taskStore.isSingleDownloading) {
                        if (isSongCancelled(songKey)) {
                            throw CancellationException("Download cancelled before start")
                        }
                        delay(100)
                    }

                    if (isSongCancelled(songKey)) {
                        throw CancellationException("Download cancelled before start")
                    }

                    if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                        updateTaskStatus(
                            songKey,
                            DownloadStatus.WAITING_NETWORK,
                            expectedAttemptId = attemptId
                        )
                        return@withSongExecutionLock
                    }

                    taskStore.isSingleDownloading = true
                    try {
                        AudioDownloadManager.resetCancelFlag()
                        AudioDownloadManager.downloadSong(
                            context = appContext,
                            song = song,
                            attemptId = attemptId
                        )
                        finalizeCompletedDownload(
                            context = appContext,
                            song = song,
                            refreshCatalog = true,
                            expectedAttemptId = attemptId
                        )
                    } finally {
                        taskStore.isSingleDownloading = false
                    }
                }
            } catch (_: CancellationException) {
                if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                    updateTaskStatus(
                        songKey,
                        DownloadStatus.WAITING_NETWORK,
                        expectedAttemptId = attemptId
                    )
                } else {
                    clearSongCancelled(songKey)
                    updateTaskStatus(
                        songKey,
                        DownloadStatus.CANCELLED,
                        expectedAttemptId = attemptId
                    )
                    forgetPendingDownloadQueueEntries(appContext, setOf(songKey))
                }
                taskStore.isSingleDownloading = false
            } catch (error: Exception) {
                NPLogger.e(TAG, "下载失败: ${song.name} - ${error.message}", error)
                updateTaskStatus(
                    songKey,
                    DownloadStatus.FAILED,
                    expectedAttemptId = attemptId
                )
                forgetPendingDownloadQueueEntries(appContext, setOf(songKey))
                taskStore.isSingleDownloading = false
            }
        }
    }

    fun startBatchDownload(context: Context, songs: List<SongItem>) {
        startBatchDownload(context, songs, skipTrafficRiskPrompt = false)
    }

    private fun startBatchDownload(
        context: Context,
        songs: List<SongItem>,
        skipTrafficRiskPrompt: Boolean,
        cleanupBeforeStart: Boolean = true
    ) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        scope.launch {
            val requestedSongs = songs.distinctBy { it.stableKey() }
            if (requestedSongs.isEmpty()) {
                return@launch
            }
            if (
                maybeRequestTrafficRiskDownloadConfirmation(
                    context = appContext,
                    songs = requestedSongs,
                    isBatch = true,
                    skipTrafficRiskPrompt = skipTrafficRiskPrompt
                )
            ) {
                return@launch
            }
            rememberPendingDownloadQueue(appContext, requestedSongs)
            startBatchDownloadConfirmed(
                context = appContext,
                songs = requestedSongs,
                cleanupBeforeStart = cleanupBeforeStart
            )
        }
    }

    private fun startBatchDownloadConfirmed(
        context: Context,
        songs: List<SongItem>,
        cleanupBeforeStart: Boolean
    ) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        val batchJob = scope.launch {
            val requestedSongs = songs.distinctBy { it.stableKey() }
            val pendingSongs = mutableListOf<PreparedDownloadTaskRequest>()
            var skippedByCancellationSettle = 0
            var skippedLocalSongs = 0
            var reusedExistingAudio = 0
            var preparedQueuedSongs = 0
            try {
                NPLogger.d(
                    TAG,
                    "批量下载启动: requested=${songs.size}, deduped=${requestedSongs.size}, cleanupBeforeStart=$cleanupBeforeStart, persistedQueued=${ManagedDownloadStorage.listPendingQueuedDownloads(appContext).size}, persistedCancelled=${ManagedDownloadStorage.listCancelledDownloadKeys(appContext).size}"
                )
                val optimisticCompletedSongs = mutableListOf<DownloadedSong>()
                val settledSongKeys = mutableSetOf<String>()
                val settledSongs = coroutineScope {
                    requestedSongs.map { song ->
                        async {
                            val songKey = song.stableKey()
                            if (!awaitSongCancellationSettled(songKey)) {
                                skippedByCancellationSettle++
                                NPLogger.d(TAG, "批量下载跳过歌曲: 取消状态未收敛, song=${song.name}, songKey=$songKey")
                                null
                            } else {
                                AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
                                song
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()
                NPLogger.d(
                    TAG,
                    "批量下载预处理完成: settled=${settledSongs.size}, skippedByCancellationSettle=$skippedByCancellationSettle"
                )
                settledSongs.forEach { song ->
                    withSongExecutionLock(song.stableKey()) {
                        if (cleanupBeforeStart) {
                            cleanupDownloadArtifactsBeforeFreshStart(appContext, song)
                            NPLogger.d(TAG, "批量下载清理旧痕迹完成: song=${song.name}, songKey=${song.stableKey()}")
                        }
                        if (shouldSkipDownload(appContext, song)) {
                            skippedLocalSongs++
                            settledSongKeys += song.stableKey()
                            NPLogger.d(TAG, "批量下载跳过本地歌曲: song=${song.name}, songKey=${song.stableKey()}")
                            return@withSongExecutionLock
                        }

                        val existingAudio = findExistingDownloadedAudio(appContext, song)
                        if (existingAudio != null) {
                            reusedExistingAudio++
                            if (isDownloadMetadataPostProcessingEnabled(appContext)) {
                                val attemptId = taskStore.prepareDownloadTask(song) ?: return@withSongExecutionLock
                                NPLogger.d(
                                    TAG,
                                    "批量下载命中已存在音频并走完成态收尾: song=${song.name}, songKey=${song.stableKey()}, attemptId=$attemptId, file=${existingAudio.name}"
                                )
                                finalizeCompletedDownload(
                                    context = appContext,
                                    song = song,
                                    refreshCatalog = false,
                                    expectedAttemptId = attemptId
                                )
                            } else {
                                optimisticCompletedSongs += buildOptimisticDownloadedSong(song, existingAudio)
                                settledSongKeys += song.stableKey()
                                NPLogger.d(
                                    TAG,
                                    "批量下载命中已存在音频并直接复用: song=${song.name}, songKey=${song.stableKey()}, file=${existingAudio.name}"
                                )
                            }
                            return@withSongExecutionLock
                        }

                        val attemptId = taskStore.prepareDownloadTask(
                            song,
                            status = DownloadStatus.QUEUED
                        ) ?: return@withSongExecutionLock
                        preparedQueuedSongs++
                        NPLogger.d(
                            TAG,
                            "批量下载加入队列: song=${song.name}, songKey=${song.stableKey()}, attemptId=$attemptId"
                        )
                        pendingSongs += PreparedDownloadTaskRequest(song = song, attemptId = attemptId)
                    }
                }

                publishOptimisticDownloadedSongs(appContext, optimisticCompletedSongs)
                forgetPendingDownloadQueueEntries(appContext, settledSongKeys)
                if (optimisticCompletedSongs.isNotEmpty()) {
                    scheduleCatalogReconcile(appContext, forceRefresh = false)
                }

                if (pendingSongs.isEmpty()) {
                    NPLogger.d(
                        TAG,
                        "没有新的批量下载任务: requested=${requestedSongs.size}, settled=${settledSongs.size}, skippedByCancellationSettle=$skippedByCancellationSettle, skippedLocalSongs=$skippedLocalSongs, reusedExistingAudio=$reusedExistingAudio, settledSongKeys=${settledSongKeys.size}, persistedQueued=${ManagedDownloadStorage.listPendingQueuedDownloads(appContext).size}, persistedCancelled=${ManagedDownloadStorage.listCancelledDownloadKeys(appContext).size}"
                    )
                    return@launch
                }
                NPLogger.d(
                    TAG,
                    "批量下载正式开始: pendingSongs=${pendingSongs.size}, preparedQueuedSongs=$preparedQueuedSongs, optimisticCompleted=${optimisticCompletedSongs.size}, settledSongKeys=${settledSongKeys.size}"
                )

                val pendingAttemptIds = pendingSongs.associate { request ->
                    request.song.stableKey() to request.attemptId
                }
                AudioDownloadManager.resetCancelFlag()
                val downloadParallelism =
                    AudioDownloadManager.resolveConfiguredDownloadParallelism(appContext)
                AudioDownloadManager.downloadPlaylist(
                    context = appContext,
                    songs = pendingSongs.map(PreparedDownloadTaskRequest::song),
                    maxConcurrentDownloads = downloadParallelism,
                    songAttemptIds = pendingAttemptIds,
                    onSongStarted = { startedSong ->
                        val attemptId = pendingAttemptIds[startedSong.stableKey()] ?: return@downloadPlaylist
                        taskStore.registerActiveDownloadTask(startedSong, expectedAttemptId = attemptId)
                    },
                    onSongCompleted = { completedSong ->
                        val attemptId = pendingAttemptIds[completedSong.stableKey()] ?: return@downloadPlaylist
                        finalizeCompletedDownload(
                            context = appContext,
                            song = completedSong,
                            refreshCatalog = false,
                            expectedAttemptId = attemptId
                        )
                    },
                    onSongFailed = { failedSong, _ ->
                        val attemptId = pendingAttemptIds[failedSong.stableKey()] ?: return@downloadPlaylist
                        updateTaskStatus(
                            failedSong.stableKey(),
                            DownloadStatus.FAILED,
                            expectedAttemptId = attemptId
                        )
                        forgetPendingDownloadQueueEntries(appContext, setOf(failedSong.stableKey()))
                    },
                    onSongCancelled = { cancelledSong ->
                        val songKey = cancelledSong.stableKey()
                        val attemptId = pendingAttemptIds[songKey] ?: return@downloadPlaylist
                        if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                            updateTaskStatus(
                                songKey,
                                DownloadStatus.WAITING_NETWORK,
                                expectedAttemptId = attemptId
                            )
                            return@downloadPlaylist
                        }
                        clearSongCancelled(songKey)
                        updateTaskStatus(
                            songKey,
                            DownloadStatus.CANCELLED,
                            expectedAttemptId = attemptId
                        )
                        forgetPendingDownloadQueueEntries(appContext, setOf(songKey))
                    }
                )
            } catch (_: CancellationException) {
                val cancelledSongKeys = mutableSetOf<String>()
                pendingSongs.forEach { request ->
                    val songKey = request.song.stableKey()
                    if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                        updateTaskStatus(
                            songKey,
                            DownloadStatus.WAITING_NETWORK,
                            expectedAttemptId = request.attemptId
                        )
                        return@forEach
                    }
                    clearSongCancelled(songKey)
                    removeDownloadTask(
                        songKey,
                        expectedAttemptId = request.attemptId
                    )
                    cancelledSongKeys += songKey
                }
                forgetPendingDownloadQueueEntries(appContext, cancelledSongKeys)
            } catch (error: Exception) {
                NPLogger.e(TAG, "批量下载失败: ${error.message}", error)
                pendingSongs.forEach { request ->
                    updateTaskStatus(
                        request.song.stableKey(),
                        DownloadStatus.FAILED,
                        expectedAttemptId = request.attemptId
                    )
                }
                forgetPendingDownloadQueueEntries(
                    appContext,
                    pendingSongs.map { it.song.stableKey() }.toSet()
                )
            }
        }
        activeBatchDownloadJobs += batchJob
        taskStore.setActiveBatchDownloadJobCount(activeBatchDownloadJobs.size)
        batchJob.invokeOnCompletion {
            activeBatchDownloadJobs.remove(batchJob)
            taskStore.setActiveBatchDownloadJobCount(activeBatchDownloadJobs.size)
        }
    }

    fun confirmTrafficRiskDownload(
        context: Context,
        request: TrafficRiskDownloadRequest
    ) {
        if (request.isBatch) {
            startBatchDownload(context, request.songs, skipTrafficRiskPrompt = true)
            return
        }
        request.songs.firstOrNull()?.let { song ->
            startDownload(context, song, skipTrafficRiskPrompt = true)
        }
    }

    private suspend fun maybeRequestTrafficRiskDownloadConfirmation(
        context: Context,
        songs: List<SongItem>,
        isBatch: Boolean,
        skipTrafficRiskPrompt: Boolean
    ): Boolean {
        if (skipTrafficRiskPrompt) {
            return false
        }
        val distinctSongs = songs.distinctBy { it.stableKey() }
        if (distinctSongs.isEmpty()) {
            return false
        }
        val networkType = context.currentTrafficNetworkType()
        if (networkType == TrafficNetworkType.WIFI) {
            return false
        }
        if (!AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow.first()) {
            return false
        }

        _trafficRiskDownloadRequests.emit(
            TrafficRiskDownloadRequest(
                id = trafficRiskRequestIdGenerator.incrementAndGet(),
                songs = distinctSongs,
                networkType = networkType,
                isBatch = isBatch
            )
        )
        return true
    }

    private suspend fun findExistingDownloadedAudio(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? {
        val songKey = song.stableKey()
        if (isSongCancelled(songKey) || AudioDownloadManager.isSongDownloadActive(songKey)) {
            NPLogger.d(
                TAG,
                "跳过已下载检查: song=${song.name}, cancelled=${isSongCancelled(songKey)}, active=${AudioDownloadManager.isSongDownloadActive(songKey)}"
            )
            return null
        }
        val existingAudio = ManagedDownloadStorage.peekDownloadedAudio(song)
            ?: ManagedDownloadStorage.findDownloadedAudio(context, song)
            ?: return null
        NPLogger.d(
            TAG,
            "命中已下载候选文件: song=${song.name}, file=${existingAudio.name}, size=${existingAudio.sizeBytes}"
        )
        return validateExistingDownloadedAudio(
            context = context,
            song = song,
            audio = existingAudio
        )
    }

    private suspend fun validateExistingDownloadedAudio(
        context: Context,
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.StoredEntry? {
        val metadataEntry = ManagedDownloadStorage.findMetadataForAudio(context, audio)
        val metadata = metadataEntry?.let {
            readDownloadedMetadata(
                context = context,
                audio = audio,
                metadataEntry = it
            )
        }
        if (isUnfinalizedDownloadedMetadata(metadata)) {
            NPLogger.w(TAG, "发现未最终确认的下载文件，回滚后重新下载: song=${song.name}, file=${audio.name}")
            rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
            return null
        }

        val localDetails = inspectDownloadedAudioDetails(context, audio)
        if (metadata != null && localDetails == null) {
            if (audio.sizeBytes > 0L && matchesExpectedDownloadFileName(song, audio)) {
                NPLogger.w(
                    TAG,
                    "已下载文件存在 metadata 但音频不可验证，保守回滚后重新下载: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
                )
            } else {
                NPLogger.w(
                    TAG,
                    "已下载文件存在 metadata 但疑似损坏，回滚后重新下载: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
                )
            }
            rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
            return null
        }
        if (metadata != null && localDetails != null) {
            NPLogger.d(
                TAG,
                "已下载文件校验通过: song=${song.name}, file=${audio.name}, durationMs=${localDetails.durationMs}, size=${audio.sizeBytes}"
            )
            return audio
        }
        if (localDetails == null) {
            // 无法读取音频标签（常见于 SAF content:// URI），
            // 通过文件名和文件大小判断是否为有效下载
            if (audio.sizeBytes > 0L && matchesExpectedDownloadFileName(song, audio)) {
                NPLogger.d(TAG, "无法读取音频标签但文件名匹配，补写元数据: ${audio.name}")
                persistDownloadedMetadata(context, audio, song)
                return audio
            }
            NPLogger.w(TAG, "发现无法验证的残缺下载文件，回滚: song=${song.name}, file=${audio.name}")
            rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
            return null
        }

        val shouldRepair = shouldRepairMetadataLessManagedDownload(
            expectedTitles = buildExpectedDownloadTitles(song),
            expectedArtists = buildExpectedDownloadArtists(song),
            expectedDurationMs = song.durationMs.coerceAtLeast(0L),
            actualTitle = localDetails.originalTitle ?: localDetails.title,
            actualArtist = localDetails.originalArtist ?: localDetails.artist,
            actualDurationMs = localDetails.durationMs
        )
        if (!shouldRepair) {
            persistDownloadedMetadata(context, audio, song)
            return audio
        }

        NPLogger.w(
            TAG,
            "发现残缺下载文件，回滚后重新下载: song=${song.name}, file=${audio.name}"
        )
        rollbackCancelledDownload(
            context = context,
            song = song,
            storedAudio = audio
        )
        return null
    }

    private fun shouldSkipDownload(context: Context, song: SongItem): Boolean {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return false
        }
        NPLogger.d(TAG, "跳过本地歌曲下载: ${song.name}")
        return true
    }

    fun updateTaskStatus(
        songKey: String,
        status: DownloadStatus,
        expectedAttemptId: Long? = null
    ) {
        taskStore.updateTaskStatus(
            songKey = songKey,
            status = status,
            expectedAttemptId = expectedAttemptId
        )
    }

    fun removeDownloadTask(songKey: String, expectedAttemptId: Long? = null) {
        taskStore.removeDownloadTask(
            songKey = songKey,
            expectedAttemptId = expectedAttemptId
        )
    }

    private fun scheduleCompletedTaskRemoval(
        songKey: String,
        expectedAttemptId: Long? = null
    ) {
        scope.launch {
            delay(DOWNLOAD_TASK_COMPLETED_RETENTION_MS)
            val task = taskStore.findTask(songKey) ?: return@launch
            if (
                shouldApplyTaskMutation(task, expectedAttemptId) &&
                task.status == DownloadStatus.COMPLETED
            ) {
                removeDownloadTask(songKey, expectedAttemptId = expectedAttemptId)
            }
        }
    }

    private fun scheduleCatalogReconcile(context: Context, forceRefresh: Boolean) {
        val appContext = context.applicationContext
        synchronized(catalogPersistenceLock) {
            catalogReconcileJob?.cancel()
            catalogReconcileJob = scope.launch {
                delay(DOWNLOAD_CATALOG_RECONCILE_DELAY_MS)
                scanLocalFiles(appContext, forceRefresh = forceRefresh)
            }
        }
    }

    private fun rememberPendingDownloadQueue(
        context: Context,
        songs: List<SongItem>
    ) {
        if (songs.isEmpty()) {
            return
        }
        ManagedDownloadStorage.upsertPendingDownloadQueue(context, songs)
    }

    private fun forgetPendingDownloadQueueEntries(
        context: Context,
        songKeys: Collection<String>
    ) {
        val keys = songKeys.filter(String::isNotBlank)
        if (keys.isEmpty()) {
            return
        }
        ManagedDownloadStorage.removePendingDownloadQueueEntries(context, keys)
    }

    private fun clearPendingDownloadQueue(context: Context) {
        ManagedDownloadStorage.clearPendingDownloadQueue(context)
    }

    private fun clearCancelledDownloadKeys(context: Context) {
        ManagedDownloadStorage.clearCancelledDownloadKeys(context)
    }

    private fun markSongCancelled(songKey: String) {
        cancelledSongKeys.add(songKey)
    }

    private fun persistCancelledDownloadKeys(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        runCatching {
            ManagedDownloadStorage.markCancelledDownloadKeys(
                AppContainer.applicationContext,
                keys
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "记录下载取消标记失败: count=${keys.size}, ${error.message}")
        }
    }

    fun clearSongCancelled(songKey: String) {
        cancelledSongKeys.remove(songKey)
    }

    fun cancelDownloadTask(songKey: String) {
        val task = taskStore.findTask(songKey) ?: return
        if (
            task.status != DownloadStatus.QUEUED &&
            task.status != DownloadStatus.DOWNLOADING &&
            task.status != DownloadStatus.WAITING_NETWORK
        ) {
            return
        }

        markSongCancelled(songKey)
        removeDownloadTask(songKey, expectedAttemptId = task.attemptId)
        scope.launch {
            cancelDownloadTaskInBackground(task)
        }
    }

    fun cancelAllDownloadTasks() {
        val appContext = AppContainer.applicationContext
        val batchJobs = activeBatchDownloadJobs.toList()
        val activeTasks = taskStore.currentTasks().filter { task ->
            task.status == DownloadStatus.QUEUED ||
                task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.WAITING_NETWORK
        }
        val persistedQueuedCountBefore = ManagedDownloadStorage.listPendingQueuedDownloads(appContext).size
        val persistedCancelledCountBefore = ManagedDownloadStorage.listCancelledDownloadKeys(appContext).size
        NPLogger.d(
            TAG,
            "取消全部下载任务: activeTasks=${activeTasks.size}, batchJobs=${batchJobs.size}, persistedQueued=$persistedQueuedCountBefore, persistedCancelled=$persistedCancelledCountBefore"
        )
        if (activeTasks.isEmpty() && batchJobs.isEmpty()) {
            clearPendingDownloadQueue(appContext)
            clearCancelledDownloadKeys(appContext)
            NPLogger.d(TAG, "取消全部下载任务: 无活动任务，已直接清空持久化队列与取消标记")
            return
        }

        val activeSongKeys = activeTasks.mapTo(linkedSetOf()) { it.song.stableKey() }
        activeSongKeys.forEach(::markSongCancelled)
        persistCancelledDownloadKeys(activeSongKeys)
        clearPendingDownloadQueue(appContext)
        NPLogger.d(
            TAG,
            "取消全部下载任务: activeSongKeys=${activeSongKeys.size}, persistedQueuedAfterClear=${ManagedDownloadStorage.listPendingQueuedDownloads(appContext).size}, persistedCancelledAfterMark=${ManagedDownloadStorage.listCancelledDownloadKeys(appContext).size}"
        )
        taskStore.removeActiveDownloadTasks(activeTasks)
        _mobileDataDownloadInterruptionRequest.value = null
        batchJobs.forEach { job ->
            job.cancel(CancellationException("cancel all download tasks"))
        }
        AudioDownloadManager.cancelDownload()
        scope.launch {
            cancelDownloadTasksInBackground(appContext, activeTasks)
        }
    }

    private fun cleanupPendingWorkingDownloadArtifactsAfterCancellation(
        context: Context,
        songKeys: Set<String>
    ) {
        if (songKeys.isEmpty()) {
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            songKeys.forEach { songKey ->
                awaitSongCancellationSettled(songKey)
            }
            ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, songKeys)
        }
    }

    fun interruptDownloadsForWifiDisconnected(networkType: TrafficNetworkType) {
        scope.launch {
            if (networkType == TrafficNetworkType.WIFI) {
                return@launch
            }
            mobileDataDownloadOverrideAllowed = false
            val activeTasks = taskStore.currentTasks().filter { task ->
                task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
            }
            if (activeTasks.isEmpty() && activeBatchDownloadJobs.isEmpty()) {
                return@launch
            }
            NPLogger.w(
                TAG,
                "WIFI 已断开，等待用户确认下载策略: networkType=$networkType, activeTasks=${activeTasks.size}"
            )
            if (!AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow.first()) {
                pauseDownloadTasksForNetworkPolicy(AppContainer.applicationContext, activeTasks)
                return@launch
            }
            _mobileDataDownloadInterruptionRequest.value = MobileDataDownloadInterruptionRequest(
                id = mobileDataInterruptionRequestIdGenerator.incrementAndGet(),
                networkType = networkType,
                taskCount = activeTasks.size
            )
            pauseDownloadTasksForNetworkPolicy(AppContainer.applicationContext, activeTasks)
        }
    }

    fun continueDownloadsOnMobileData(
        context: Context,
        request: MobileDataDownloadInterruptionRequest
    ) {
        if (_mobileDataDownloadInterruptionRequest.value?.id != request.id) {
            return
        }
        mobileDataDownloadOverrideAllowed = true
        _mobileDataDownloadInterruptionRequest.value = null
        recoverPendingDownloadsOnCurrentNetwork(context, reason = "mobile_data_user_confirmed")
    }

    fun waitDownloadsForWifi(request: MobileDataDownloadInterruptionRequest) {
        if (_mobileDataDownloadInterruptionRequest.value?.id != request.id) {
            return
        }
        _mobileDataDownloadInterruptionRequest.value = null
        scope.launch {
            val activeTasks = taskStore.currentTasks().filter { task ->
                task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
            }
            pauseDownloadTasksForNetworkPolicy(AppContainer.applicationContext, activeTasks)
        }
    }

    fun cancelAllDownloadsForMobileData(request: MobileDataDownloadInterruptionRequest) {
        if (_mobileDataDownloadInterruptionRequest.value?.id != request.id) {
            return
        }
        _mobileDataDownloadInterruptionRequest.value = null
        cancelAllDownloadTasks()
    }

    private fun recoverPendingDownloadsOnCurrentNetwork(context: Context, reason: String) {
        val appContext = context.applicationContext
        scope.launch {
            val networkType = appContext.currentTrafficNetworkType()
            if (networkType != TrafficNetworkType.WIFI && !mobileDataDownloadOverrideAllowed) {
                return@launch
            }
            if (!tryBeginPendingDownloadRecovery()) {
                return@launch
            }
            try {
                waitForActiveDownloadJobsToSettle()
                if (hasActiveDownloadOperations()) {
                    return@launch
                }
                recoverPendingResumableDownloads(appContext, reason = reason)
                delay(1_500L)
            } finally {
                finishPendingDownloadRecovery()
            }
        }
    }

    private suspend fun cancelDownloadTaskInBackground(task: DownloadTask) {
        val appContext = AppContainer.applicationContext
        val songKey = task.song.stableKey()
        persistCancelledDownloadKeys(setOf(songKey))
        AudioDownloadManager.cancelSongDownload(songKey)
        forgetPendingDownloadQueueEntries(appContext, setOf(songKey))
        awaitSongCancellationSettled(songKey)
        withSongExecutionLock(songKey) {
            cleanupCancelledDownloadArtifacts(appContext, task.song)
        }
    }

    private suspend fun cancelDownloadTasksInBackground(
        context: Context,
        tasks: Collection<DownloadTask>,
        additionalSongKeys: Collection<String> = emptySet()
    ) {
        val appContext = context.applicationContext
        val persistedKeys = ManagedDownloadStorage.listPendingQueuedDownloads(appContext)
            .mapTo(mutableSetOf()) { it.stableKey }
        val activeKeys = tasks.mapTo(persistedKeys) { it.song.stableKey() }
        additionalSongKeys
            .filter(String::isNotBlank)
            .forEach(activeKeys::add)
        activeKeys.forEach(::markSongCancelled)
        persistCancelledDownloadKeys(activeKeys)
        clearPendingDownloadQueue(appContext)
        awaitDownloadCancellationsSettled(activeKeys)
        val taskSongKeys = tasks.mapTo(mutableSetOf()) { task -> task.song.stableKey() }
        tasks.forEach { task ->
            val songKey = task.song.stableKey()
            if (songKey !in activeKeys) {
                return@forEach
            }
            withSongExecutionLock(songKey) {
                cleanupCancelledDownloadArtifacts(appContext, task.song)
            }
        }
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
            appContext,
            activeKeys - taskSongKeys
        )
        ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, activeKeys)
        activeKeys.forEach(::clearSongCancelled)
    }

    private suspend fun awaitDownloadCancellationsSettled(songKeys: Set<String>) {
        if (songKeys.isEmpty()) {
            return
        }
        val deadlineAt = System.currentTimeMillis() + DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadlineAt) {
            if (songKeys.none(AudioDownloadManager::isSongDownloadActive)) {
                break
            }
            delay(50L)
        }
        val stuckKeys = songKeys.filter(AudioDownloadManager::isSongDownloadActive)
        if (stuckKeys.isNotEmpty()) {
            NPLogger.w(TAG, "等待批量取消清理超时: count=${stuckKeys.size}")
        }
    }

    private fun pauseDownloadTasksForNetworkPolicy(
        context: Context,
        activeTasks: List<DownloadTask>
    ) {
        val activeKeys = ManagedDownloadStorage.listPendingQueuedDownloads(context)
            .mapTo(mutableSetOf()) { entry -> entry.stableKey }
        activeTasks.mapTo(activeKeys) { task -> task.song.stableKey() }
        if (activeKeys.isEmpty()) {
            activeBatchDownloadJobs.toList().forEach { job ->
                job.cancel(CancellationException("pause downloads for network policy"))
            }
            return
        }
        AudioDownloadManager.pauseDownloadsForNetworkPolicy(activeKeys)
        taskStore.applyWaitingNetworkStatus(activeTasks)
        activeBatchDownloadJobs.toList().forEach { job ->
            job.cancel(CancellationException("pause downloads for network policy"))
        }
    }

    fun isSongCancelled(songKey: String): Boolean {
        return cancelledSongKeys.contains(songKey)
    }

    internal fun isDownloadAttemptCurrent(songKey: String, attemptId: Long?): Boolean {
        return taskStore.isDownloadAttemptCurrent(songKey, attemptId)
    }

    internal suspend fun <T> withSongExecutionLock(
        songKey: String,
        block: suspend () -> T
    ): T {
        val mutex = songExecutionMutex(songKey)
        return try {
            mutex.withLock {
                block()
            }
        } finally {
            if (!mutex.isLocked) {
                songExecutionLocks.remove(songKey, mutex)
            }
        }
    }

    internal fun isDownloadAttemptActive(
        songKey: String,
        expectedAttemptId: Long? = null
    ): Boolean {
        return taskStore.isDownloadAttemptActive(
            songKey = songKey,
            expectedAttemptId = expectedAttemptId
        )
    }

    fun resumeDownloadTask(context: Context, songKey: String) {
        val task = taskStore.findTask(songKey) ?: return
        if (
            task.status != DownloadStatus.CANCELLED &&
            task.status != DownloadStatus.FAILED &&
            task.status != DownloadStatus.WAITING_NETWORK
        ) {
            return
        }

        clearSongCancelled(songKey)
        removeDownloadTask(songKey, expectedAttemptId = task.attemptId)
        startDownload(
            context = context,
            song = task.song,
            skipTrafficRiskPrompt = false,
            cleanupBeforeStart = task.status != DownloadStatus.WAITING_NETWORK
        )
    }

    fun clearCompletedTasks() {
        val appContext = AppContainer.applicationContext
        val batchJobs = activeBatchDownloadJobs.toList()
        val persistedQueuedKeys = ManagedDownloadStorage.listPendingQueuedDownloads(appContext)
            .mapTo(mutableSetOf()) { entry -> entry.stableKey }
        val downloadingTasks = taskStore.currentTasks().filter { task ->
            task.status == DownloadStatus.QUEUED ||
                task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.WAITING_NETWORK
        }
        val activeKeys = downloadingTasks.mapTo(persistedQueuedKeys) { task ->
            task.song.stableKey()
        }
        if (activeKeys.isNotEmpty()) {
            activeKeys.forEach(::markSongCancelled)
            persistCancelledDownloadKeys(activeKeys)
        }
        if (downloadingTasks.isNotEmpty() || batchJobs.isNotEmpty()) {
            AudioDownloadManager.cancelDownload()
            batchJobs.forEach { job ->
                job.cancel(CancellationException("clear download queue"))
            }
        }

        taskStore.clearAllTasks()
        _mobileDataDownloadInterruptionRequest.value = null
        clearPendingDownloadQueue(appContext)
        if (activeKeys.isEmpty()) {
            cancelledSongKeys.clear()
            clearCancelledDownloadKeys(appContext)
            return
        }
        scope.launch {
            cancelDownloadTasksInBackground(
                context = appContext,
                tasks = downloadingTasks,
                additionalSongKeys = activeKeys
            )
        }
    }

    private suspend fun awaitSongCancellationSettled(songKey: String): Boolean {
        if (!isSongCancelled(songKey) && !AudioDownloadManager.isSongDownloadActive(songKey)) {
            return true
        }
        NPLogger.d(
            TAG,
            "等待歌曲取消状态收敛: songKey=$songKey, cancelled=${isSongCancelled(songKey)}, active=${AudioDownloadManager.isSongDownloadActive(songKey)}"
        )

        val deadlineAt = System.currentTimeMillis() + DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS
        while (AudioDownloadManager.isSongDownloadActive(songKey) && System.currentTimeMillis() < deadlineAt) {
            delay(50)
        }
        if (AudioDownloadManager.isSongDownloadActive(songKey)) {
            NPLogger.w(TAG, "等待取消中的下载清理超时: songKey=$songKey")
            return false
        }
        NPLogger.d(
            TAG,
            "歌曲取消状态已收敛: songKey=$songKey, cancelledBeforeClear=${isSongCancelled(songKey)}"
        )
        clearSongCancelled(songKey)
        return true
    }

    private fun songExecutionMutex(songKey: String): Mutex {
        return songExecutionLocks.computeIfAbsent(songKey) { Mutex() }
    }

    private fun candidateBaseNames(
        song: DownloadedSong,
        actualAudioBaseName: String? = null
    ): List<String> {
        val baseNames = linkedSetOf<String>()
        actualAudioBaseName?.takeIf { it.isNotBlank() }?.let(baseNames::add)
        baseNames += sanitizeManagedDownloadFileName("${song.displayArtist()} - ${song.displayName()}")
        baseNames += sanitizeManagedDownloadFileName("${song.artist} - ${song.name}")

        val originalName = song.originalName?.takeIf { it.isNotBlank() } ?: song.name
        val originalArtist = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
        baseNames += sanitizeManagedDownloadFileName("$originalArtist - $originalName")
        return baseNames.toList()
    }

    private suspend fun resolveStoredAudio(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? {
        resolveStoredAudio(context, resolveSongLocation(song))?.let { return it }
        return ManagedDownloadStorage.findDownloadedAudio(context, song)
    }

    private suspend fun resolveStoredAudio(
        context: Context,
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val normalized = reference?.takeIf { it.isNotBlank() } ?: return null
        return ManagedDownloadStorage.queryStoredEntry(context, normalized)
    }

    private fun publishCompletedDownloadOptimistically(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
    ) {
        publishOptimisticDownloadedSongs(
            context = context,
            songs = listOf(
                buildOptimisticDownloadedSong(
                    song = song,
                    storedAudio = storedAudio,
                    sidecarReferences = sidecarReferences
                )
            )
        )
    }

    private fun publishOptimisticDownloadedSongs(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        if (songs.isEmpty()) {
            return
        }

        var mergedSongs = _downloadedSongs.value
        songs.forEach { song ->
            mergedSongs = upsertDownloadedSongCatalog(mergedSongs, song)
        }
        if (mergedSongs != _downloadedSongs.value) {
            publishDownloadedSongs(context, mergedSongs, persistCatalog = true)
        }
    }

    private fun buildOptimisticDownloadedSong(
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
    ): DownloadedSong {
        val previousSong = _downloadedSongs.value.firstOrNull { downloadedSong ->
            downloadedSong.filePath == storedAudio.reference || matchesDownloadedSong(song, downloadedSong)
        }
        val resolvedDownloadTime = previousSong?.downloadTime
            ?: storedAudio.lastModifiedMs.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        return DownloadedSong(
            id = song.id,
            name = song.name,
            artist = song.artist,
            album = song.album,
            filePath = storedAudio.reference,
            fileSize = storedAudio.sizeBytes.coerceAtLeast(0L),
            downloadTime = resolvedDownloadTime,
            coverPath = sidecarReferences?.coverReference ?: previousSong?.coverPath,
            coverUrl = song.coverUrl,
            matchedLyric = song.matchedLyric,
            matchedTranslatedLyric = song.matchedTranslatedLyric,
            matchedLyricSource = song.matchedLyricSource?.name,
            matchedSongId = song.matchedSongId,
            userLyricOffsetMs = song.userLyricOffsetMs,
            customCoverUrl = song.customCoverUrl,
            customName = song.customName,
            customArtist = song.customArtist,
            originalName = song.originalName,
            originalArtist = song.originalArtist,
            originalCoverUrl = song.originalCoverUrl,
            originalLyric = song.originalLyric,
            originalTranslatedLyric = song.originalTranslatedLyric,
            mediaUri = storedAudio.mediaUri,
            durationMs = song.durationMs.coerceAtLeast(0L),
            stableKey = song.stableKey()
        )
    }

    private suspend fun resolveAccessibleManagedReference(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        vararg references: String?
    ): String? {
        return references.firstNotNullOfOrNull { reference ->
            val candidate = reference?.takeIf(::isResolvableLocalReference) ?: return@firstNotNullOfOrNull null
            candidate.takeIf {
                candidate in snapshot.knownReferences || ManagedDownloadStorage.exists(context, candidate)
            }
        }
    }

    private fun resolveSongLocation(song: SongItem): String? {
        song.localFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val mediaUri = song.mediaUri?.takeIf { it.isNotBlank() } ?: return null
        return when {
            mediaUri.startsWith("/") -> mediaUri
            mediaUri.startsWith("file://") -> mediaUri
            mediaUri.startsWith("content://") -> mediaUri
            else -> null
        }
    }

    private fun inspectDownloadedAudioDetails(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) = runCatching {
        LocalMediaSupport.inspect(context, storedAudio.playbackUri.toUri())
    }.onFailure { error ->
        NPLogger.w(TAG, "读取已下载音频标签失败: ${storedAudio.name} - ${error.message}")
    }.getOrNull()

    private fun matchesExpectedDownloadFileName(
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        val baseNames = ManagedDownloadStorage.buildCandidateBaseNames(song)
        val audioBaseName = audio.nameWithoutExtension
        val normalizedAudioBaseName = audioBaseName.replace(Regex(" \\(\\d+\\)$"), "")
        return baseNames.any { candidate ->
            candidate == audioBaseName || candidate == normalizedAudioBaseName
        }
    }

    private fun isReferenceOwnedByOtherDownload(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        currentAudioName: String?,
        reference: String,
        deletingAudioNames: Set<String> = emptySet()
    ): Boolean {
        return snapshot.metadataByAudioName.any { (audioName, metadata) ->
            audioName != currentAudioName &&
                audioName !in deletingAudioNames &&
                listOfNotNull(
                    metadata.coverPath,
                    metadata.lyricPath,
                    metadata.translatedLyricPath
                ).contains(reference)
        }
    }
}
