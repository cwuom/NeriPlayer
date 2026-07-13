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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType

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
    private const val DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS = 1_200L
    private const val DOWNLOAD_CANCELLED_ARTIFACT_RECOVERY_DELAY_MS = 800L
    private const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_GRACE_MS = 300L
    private const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_POLL_MS = 50L
    private const val METADATA_WRITE_MAX_ATTEMPTS = 3
    private const val METADATA_WRITE_RETRY_DELAY_MS = 200L
    private const val METADATA_POST_PROCESSING_MAX_ATTEMPTS = 3
    private const val METADATA_POST_PROCESSING_RETRY_DELAY_MS = 350L
    private const val DOWNLOAD_TASK_PROGRESS_EMIT_INTERVAL_NS = 450_000_000L
    private const val METADATA_POST_PROCESSING_PARALLELISM = 2
    private const val SONG_EXECUTION_LOCK_STRIPES = 256
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
    private val downloadedSongCatalogStore = DownloadedSongCatalogStore(
        cacheFileName = DOWNLOAD_CATALOG_CACHE_FILE_NAME,
        snapshotCacheKeyProvider = ManagedDownloadStorage::currentSnapshotCacheKey,
        loggerTag = TAG
    )
    private val downloadedAudioMetadataStore = DownloadedAudioMetadataStore(
        maxWriteAttempts = METADATA_WRITE_MAX_ATTEMPTS,
        writeRetryDelayMs = METADATA_WRITE_RETRY_DELAY_MS,
        loggerTag = TAG
    )
    private val downloadedSongBuilder = DownloadedSongBuilder(
        metadataStore = downloadedAudioMetadataStore,
        loggerTag = TAG
    )
    private val managedDownloadDeletePlanner = ManagedDownloadDeletePlanner()
    private val requestGenerationTracker = DownloadRequestGenerationTracker()
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
    private val cancelledArtifactRecoveryLock = Any()
    private var refreshJob: Job? = null
    private var catalogPersistJob: Job? = null
    private var catalogReconcileJob: Job? = null
    private var pendingCatalogReconcileForceRefresh = false
    private var cancelledArtifactRecoveryJob: Job? = null
    private val pendingCancelledArtifactRecoverySongs = linkedMapOf<String, SongItem>()
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
    private val songExecutionLocks = Array(SONG_EXECUTION_LOCK_STRIPES) { Mutex() }
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
        observeStorageStartupRecovery(appContext)
        scope.launch {
            val startupRecovery = ManagedDownloadStorage.consumeStartupRecoveryResult()
            val restoredCatalog = restorePersistedDownloadedSongs(appContext)
            recoverPendingDownloadsForStartup(appContext)
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

    private fun observeStorageStartupRecovery(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            ManagedDownloadStorage.startupRecoveryResults.collect { result ->
                if (!result.hasRecoveredEntries) {
                    return@collect
                }
                NPLogger.d(
                    TAG,
                    "后台下载启动清理完成，安排目录对账: cleaned=${result.cleanedCount}, failed=${result.failedCount}"
                )
                scheduleCatalogReconcile(appContext, forceRefresh = true)
            }
        }
    }

    private suspend fun recoverPendingDownloadsForStartup(context: Context) {
        val appContext = context.applicationContext
        if (!tryBeginPendingDownloadRecovery()) {
            NPLogger.d(TAG, "跳过启动下载恢复: 已有恢复任务执行中")
            return
        }
        try {
            if (!hasPendingRecoveryCandidates(appContext)) {
                return
            }
            waitForActiveDownloadJobsToSettle()
            waitForQueuedTasksToAttachToBatch()
            if (hasBlockingActiveDownloadOperationsForRecovery()) {
                NPLogger.d(TAG, "延后启动下载恢复: 当前已有活动下载")
                return
            }
            if (deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(appContext, reason = "startup")) {
                return
            }
            recoverPendingResumableDownloads(appContext, reason = "startup")
            delay(1_500L)
        } finally {
            finishPendingDownloadRecovery()
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
            val recoveryPlan = resolvePendingDownloadRecoveryPlan(context)
            removeObsoleteWaitingNetworkTasks(recoveryPlan.recoveryCandidateKeys)
            if (recoveryPlan.recoveryCandidates.isEmpty()) {
                if (recoveryPlan.pendingQueuedDownloads.isEmpty() && recoveryPlan.pendingDownloads.isEmpty()) {
                    ManagedDownloadStorage.clearCancelledDownloadKeys(context)
                }
                return
            }

            forgetPendingDownloadQueueEntries(context, recoveryPlan.settledSongKeys)
            ManagedDownloadStorage.removeCancelledDownloadKeys(context, recoveryPlan.settledSongKeys)

            if (recoveryPlan.resumableSongs.isEmpty()) {
                return
            }

            NPLogger.d(
                TAG,
                "检测到未完成下载，准备自动恢复: reason=$reason, count=${recoveryPlan.resumableSongs.size}, queued=${recoveryPlan.pendingQueuedDownloads.size}, partial=${recoveryPlan.pendingDownloads.size}"
            )
            startBatchDownload(
                context = context,
                songs = recoveryPlan.resumableSongs,
                skipTrafficRiskPrompt = true,
                cleanupBeforeStart = false,
                replaceExistingActiveTasks = true,
                deferForNetworkPolicy = true
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "自动恢复未完成下载失败: ${error.message}", error)
        }
    }

    private data class PendingDownloadRecoveryPlan(
        val pendingQueuedDownloads: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
        val pendingDownloads: List<ManagedDownloadStorage.PendingResumableDownload>,
        val recoveryCandidates: List<PendingDownloadRecoveryCandidate>,
        val recoveryCandidateKeys: Set<String>,
        val resumableSongs: List<SongItem>,
        val settledSongKeys: Set<String>
    )

    private fun resolvePendingDownloadRecoveryPlan(context: Context): PendingDownloadRecoveryPlan {
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
                findFastCachedDownloadedSong(context, song) != null -> {
                    candidate.workingFile?.let(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)
                    settledSongKeys += songKey
                }
                else -> {
                    resumableSongs += song
                }
            }
        }
        return PendingDownloadRecoveryPlan(
            pendingQueuedDownloads = pendingQueuedDownloads,
            pendingDownloads = pendingDownloads,
            recoveryCandidates = recoveryCandidates,
            recoveryCandidateKeys = recoveryCandidateKeys,
            resumableSongs = resumableSongs,
            settledSongKeys = settledSongKeys
        )
    }

    private suspend fun deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(
        context: Context,
        reason: String
    ): Boolean {
        val networkType = context.currentTrafficNetworkType()
        if (!shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = networkType,
                mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
            )
        ) {
            return false
        }

        val recoveryPlan = resolvePendingDownloadRecoveryPlan(context)
        val waitingTaskSongs = currentWaitingNetworkTaskSongs()
        val recoverableWaitingKeys = recoveryPlan.recoveryCandidateKeys +
            waitingTaskSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
        removeObsoleteWaitingNetworkTasks(recoverableWaitingKeys)
        if (recoveryPlan.recoveryCandidates.isEmpty() && waitingTaskSongs.isEmpty()) {
            if (recoveryPlan.pendingQueuedDownloads.isEmpty() && recoveryPlan.pendingDownloads.isEmpty()) {
                ManagedDownloadStorage.clearCancelledDownloadKeys(context)
            }
            return true
        }

        forgetPendingDownloadQueueEntries(context, recoveryPlan.settledSongKeys)
        ManagedDownloadStorage.removeCancelledDownloadKeys(context, recoveryPlan.settledSongKeys)

        val waitingSongs = recoveryPlan.resumableSongs.ifEmpty { waitingTaskSongs }
        if (waitingSongs.isEmpty()) {
            return true
        }

        val waitingSongKeys = waitingSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
        AudioDownloadManager.pauseDownloadsForNetworkPolicy(waitingSongKeys)
        taskStore.prepareDownloadTasks(
            songs = waitingSongs,
            status = DownloadStatus.WAITING_NETWORK,
            replaceExistingActiveTasks = true
        )
        mobileDataDownloadOverrideAllowed = false
        NPLogger.w(
            TAG,
            "启动下载恢复遇到非 WIFI 网络，已等待用户选择: reason=$reason, networkType=$networkType, count=${waitingSongs.size}"
        )
        publishMobileDataDownloadInterruptionRequestIfNeeded(
            networkType = networkType,
            taskCount = waitingSongs.size,
            reason = reason
        )
        return true
    }

    private fun currentWaitingNetworkTaskSongs(): List<SongItem> {
        return taskStore.currentTasks()
            .asSequence()
            .filter { task -> task.status == DownloadStatus.WAITING_NETWORK }
            .map(DownloadTask::song)
            .distinctBy { song -> song.stableKey() }
            .toList()
    }

    private fun currentActiveNetworkPolicyTasks(): List<DownloadTask> {
        return taskStore.currentTasks().filter { task ->
            task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
        }
    }

    private suspend fun pauseActiveDownloadsForNetworkPolicyIfNeeded(
        context: Context,
        networkType: TrafficNetworkType,
        reason: String
    ): Boolean {
        if (networkType == TrafficNetworkType.WIFI) {
            return false
        }
        val activeTasks = currentActiveNetworkPolicyTasks()
        if (activeTasks.isEmpty() && activeBatchDownloadJobs.isEmpty() && !taskStore.isSingleDownloading) {
            return false
        }
        mobileDataDownloadOverrideAllowed = false
        val persistedQueuedCount = ManagedDownloadStorage.listPendingQueuedDownloads(context).size
        val taskCount = maxOf(activeTasks.size, persistedQueuedCount)
        NPLogger.w(
            TAG,
            "非 WIFI 网络下检测到活动下载，先暂停并等待用户选择: reason=$reason, networkType=$networkType, activeTasks=${activeTasks.size}, persistedQueued=$persistedQueuedCount"
        )
        publishMobileDataDownloadInterruptionRequestIfNeeded(
            networkType = networkType,
            taskCount = taskCount.coerceAtLeast(1),
            reason = reason
        )
        pauseDownloadTasksForNetworkPolicy(context, activeTasks)
        return true
    }

    private suspend fun deferPreparedDownloadStartForNetworkPolicyIfNeeded(
        context: Context,
        songs: List<SongItem>,
        attemptIdsBySongKey: Map<String, Long>,
        requestGeneration: Long,
        reason: String,
        deferForNetworkPolicy: Boolean
    ): Boolean {
        val networkType = context.currentTrafficNetworkType()
        if (
            !shouldDeferPreparedDownloadStartForNetwork(
                networkType = networkType,
                mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed,
                deferForNetworkPolicy = deferForNetworkPolicy
            )
        ) {
            return false
        }

        val waitingSongs = songs
            .distinctBy { song -> song.stableKey() }
            .filter { song ->
                val songKey = song.stableKey()
                attemptIdsBySongKey.containsKey(songKey) &&
                    isDownloadRequestGenerationCurrent(songKey, requestGeneration)
            }
        if (waitingSongs.isEmpty()) {
            return false
        }

        val waitingKeys = waitingSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
        AudioDownloadManager.pauseDownloadsForNetworkPolicy(waitingKeys)
        waitingSongs.forEach { song ->
            val songKey = song.stableKey()
            updateTaskStatus(
                songKey = songKey,
                status = DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = attemptIdsBySongKey[songKey]
            )
        }
        mobileDataDownloadOverrideAllowed = false
        NPLogger.w(
            TAG,
            "非 WIFI 网络下阻止恢复下载启动，等待用户选择: reason=$reason, networkType=$networkType, count=${waitingSongs.size}"
        )
        publishMobileDataDownloadInterruptionRequestIfNeeded(
            networkType = networkType,
            taskCount = waitingSongs.size,
            reason = reason
        )
        return true
    }

    private suspend fun publishMobileDataDownloadInterruptionRequestIfNeeded(
        networkType: TrafficNetworkType,
        taskCount: Int,
        reason: String
    ) {
        _mobileDataDownloadInterruptionRequest.value?.let { existingRequest ->
            NPLogger.d(
                TAG,
                "移动网络下载确认请求已存在，跳过重复发布: reason=$reason, requestId=${existingRequest.id}, taskCount=${existingRequest.taskCount}"
            )
            return
        }
        if (!AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow.first()) {
            NPLogger.d(
                TAG,
                "移动网络下载提示已关闭，任务保持等待 WIFI: reason=$reason, networkType=$networkType, taskCount=$taskCount"
            )
            return
        }
        val request = MobileDataDownloadInterruptionRequest(
            id = mobileDataInterruptionRequestIdGenerator.incrementAndGet(),
            networkType = networkType,
            taskCount = taskCount.coerceAtLeast(1)
        )
        _mobileDataDownloadInterruptionRequest.value = request
        NPLogger.w(
            TAG,
            "已发出移动网络下载确认请求: reason=$reason, networkType=$networkType, taskCount=${request.taskCount}, requestId=${request.id}"
        )
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
                waitForQueuedTasksToAttachToBatch()
                if (hasBlockingActiveDownloadOperationsForRecovery()) {
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

    fun requestPendingDownloadRecoveryDecisionIfNeeded(
        context: Context,
        reason: String
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val networkType = appContext.currentTrafficNetworkType()
            val pendingQueuedCount = ManagedDownloadStorage.listPendingQueuedDownloads(appContext).size
            val pendingResumableCount = ManagedDownloadStorage.listPendingResumableDownloads(appContext).size
            val currentTasks = taskStore.currentTasks()
            val waitingTaskCount = currentTasks.count { task ->
                task.status == DownloadStatus.WAITING_NETWORK
            }
            val activeTaskCount = currentTasks.count { task ->
                task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
            }
            NPLogger.d(
                TAG,
                "复查移动网络下载恢复: reason=$reason, networkType=$networkType, queued=$pendingQueuedCount, partial=$pendingResumableCount, waiting=$waitingTaskCount, active=$activeTaskCount, batchJobs=${activeBatchDownloadJobs.size}, single=${taskStore.isSingleDownloading}, pendingDialog=${_mobileDataDownloadInterruptionRequest.value != null}"
            )
            if (networkType == TrafficNetworkType.WIFI) {
                NPLogger.d(TAG, "跳过移动网络下载恢复复查: 当前是 WIFI, reason=$reason")
                return@launch
            }
            if (
                pauseActiveDownloadsForNetworkPolicyIfNeeded(
                    context = appContext,
                    networkType = networkType,
                    reason = reason
                )
            ) {
                return@launch
            }
            if (!hasPendingRecoveryCandidates(appContext)) {
                NPLogger.d(TAG, "跳过移动网络下载恢复复查: 没有恢复候选, reason=$reason")
                return@launch
            }
            if (!tryBeginPendingDownloadRecovery()) {
                NPLogger.d(TAG, "跳过移动网络下载恢复复查: 恢复锁忙, reason=$reason")
                return@launch
            }
            try {
                waitForActiveDownloadJobsToSettle()
                waitForQueuedTasksToAttachToBatch()
                if (hasBlockingActiveDownloadOperationsForRecovery()) {
                    NPLogger.d(TAG, "跳过移动网络下载恢复复查: 仍有活动下载, reason=$reason")
                    return@launch
                }
                deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(appContext, reason = reason)
            } finally {
                finishPendingDownloadRecovery()
            }
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

    private suspend fun waitForQueuedTasksToAttachToBatch() {
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

    private fun hasBlockingActiveDownloadOperationsForRecovery(): Boolean {
        return hasRecoveryBlockingDownloadOperations(
            tasks = taskStore.currentTasks(),
            isSingleDownloading = taskStore.isSingleDownloading,
            hasActiveBatchJobs = activeBatchDownloadJobs.isNotEmpty()
        )
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
        expectedAttemptId: Long? = null,
        storedAudioHint: ManagedDownloadStorage.StoredEntry? = null
    ) {
        val songKey = song.stableKey()
        val completedAudio = AudioDownloadManager.consumeCompletedAudioReference(songKey)
        val sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
        val currentTask = taskStore.findTask(songKey)
        if (!shouldApplyTaskMutation(currentTask, expectedAttemptId)) {
            NPLogger.d(
                TAG,
                "忽略过期下载完成回调: song=${song.name}, expectedAttemptId=$expectedAttemptId, currentAttemptId=${currentTask?.attemptId}"
            )
            rollbackStaleCompletedDownload(
                context = context,
                song = song,
                storedAudio = storedAudioHint ?: completedAudio,
                sidecarReferences = sidecarReferences
            )
            return
        }
        val storedAudio = storedAudioHint
            ?: completedAudio
            ?: resolveStoredAudio(context, song)
            ?: ManagedDownloadStorage.findDownloadedAudio(context, song, forceRefresh = true)
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
                NPLogger.w(TAG, "下载完成但未找到已下载文件，按失败处理: ${song.name}")
                cleanupOrphanedCompletedSidecars(
                    context = context,
                    song = song,
                    sidecarReferences = sidecarReferences
                )
                updateTaskStatus(
                    songKey,
                    DownloadStatus.FAILED,
                    expectedAttemptId = expectedAttemptId
                )
                forgetPendingDownloadQueueEntries(context, setOf(songKey))
                scheduleCatalogReconcile(context, forceRefresh = true)
                return
            }
            CompletedDownloadFinalizationAction.COMPLETE -> Unit
        }
        val resolvedStoredAudio = storedAudio ?: return

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
        val finalization = finalizeCompletedDownloadMetadata(
            context = context,
            song = song,
            storedAudio = resolvedStoredAudio,
            initialSidecarReferences = sidecarReferences,
        )
        if (!finalization.finalized) {
            if (
                handleCancelledCompletedDownload(
                    context = context,
                    song = song,
                    songKey = songKey,
                    storedAudio = resolvedStoredAudio,
                    sidecarReferences = finalization.sidecarReferences,
                    expectedAttemptId = expectedAttemptId
                )
            ) {
                return
            }
            rollbackFailedCompletedDownloadFinalization(
                context = context,
                song = song,
                storedAudio = resolvedStoredAudio,
                sidecarReferences = finalization.sidecarReferences
            )
            updateTaskStatus(
                songKey,
                DownloadStatus.FAILED,
                expectedAttemptId = expectedAttemptId
            )
            forgetPendingDownloadQueueEntries(context, setOf(songKey))
            scheduleCatalogReconcile(context, forceRefresh = true)
            return
        }

        publishCompletedDownloadOptimistically(
            context = context,
            song = song,
            storedAudio = resolvedStoredAudio,
            sidecarReferences = finalization.sidecarReferences
        )
        updateTaskStatus(
            songKey,
            DownloadStatus.COMPLETED,
            expectedAttemptId = expectedAttemptId
        )
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        scheduleCompletedTaskRemoval(songKey, expectedAttemptId = expectedAttemptId)
        if (refreshCatalog) {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
    }

    private data class CompletedDownloadMetadataFinalizationResult(
        val finalized: Boolean,
        val sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences =
            AudioDownloadManager.DownloadedSidecarReferences()
    )

    private suspend fun finalizeCompletedDownloadMetadata(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        initialSidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ): CompletedDownloadMetadataFinalizationResult {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        var sidecarReferences = initialSidecarReferences ?: AudioDownloadManager.DownloadedSidecarReferences()
        return try {
            if (!ManagedDownloadStorage.exists(appContext, storedAudio.reference)) {
                NPLogger.w(TAG, "下载收尾发现音频已不可读: song=${song.name}, reference=${storedAudio.reference}")
                return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
            }
            sidecarReferences = AudioDownloadManager.mergeDownloadedSidecarReferences(
                sidecarReferences,
                AudioDownloadManager.downloadSidecarsForCompletedAudio(
                    context = appContext,
                    song = song,
                    storedAudio = storedAudio
                )
            )
            if (isSongCancelled(songKey)) {
                cleanupOrphanedCompletedSidecars(
                    context = appContext,
                    song = song,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
                return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
            }

            val postProcessingEnabled = isDownloadMetadataPostProcessingEnabled(appContext)
            val metadataSeedWritten = persistDownloadedMetadata(
                context = appContext,
                audio = storedAudio,
                song = song,
                sidecarReferences = sidecarReferences,
                downloadFinalized = !postProcessingEnabled,
                resolveExistingSidecars = false
            )
            if (!metadataSeedWritten) {
                NPLogger.w(TAG, "下载 metadata 写入失败，保持未完成状态: ${song.name}")
                return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
            }

            if (postProcessingEnabled) {
                val tagWritten = runDownloadedAudioMetadataPostProcessing(
                    context = appContext,
                    audio = storedAudio,
                    song = song,
                    sidecarReferences = sidecarReferences
                )
                if (!tagWritten) {
                    NPLogger.w(TAG, "音频标签后处理失败，保持未完成状态: ${song.name}")
                    return CompletedDownloadMetadataFinalizationResult(
                        finalized = false,
                        sidecarReferences = sidecarReferences.retainCreatedOnly()
                    )
                }
                val finalizedMetadataWritten = persistDownloadedMetadata(
                    context = appContext,
                    audio = storedAudio,
                    song = song,
                    sidecarReferences = sidecarReferences,
                    downloadFinalized = true,
                    resolveExistingSidecars = false
                )
                if (!finalizedMetadataWritten) {
                    NPLogger.w(TAG, "下载 finalized metadata 写入失败，保持未完成状态: ${song.name}")
                    return CompletedDownloadMetadataFinalizationResult(
                        finalized = false,
                        sidecarReferences = sidecarReferences.retainCreatedOnly()
                    )
                }
            }

            CompletedDownloadMetadataFinalizationResult(
                finalized = !isSongCancelled(songKey) &&
                    ManagedDownloadStorage.exists(appContext, storedAudio.reference),
                sidecarReferences = sidecarReferences
            )
        } catch (error: CancellationException) {
            cleanupOrphanedCompletedSidecars(
                context = appContext,
                song = song,
                sidecarReferences = sidecarReferences.retainCreatedOnly()
            )
            throw error
        } catch (error: Throwable) {
            NPLogger.w(TAG, "下载元信息收尾失败，保持未完成状态: ${song.name}, ${error.message}")
            CompletedDownloadMetadataFinalizationResult(
                finalized = false,
                sidecarReferences = sidecarReferences.retainCreatedOnly()
            )
        }
    }

    private suspend fun rollbackFailedCompletedDownloadFinalization(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ) {
        val appContext = context.applicationContext
        val explicitReferences = listOfNotNull(
            ManagedDownloadStorage.metadataReferenceForAudio(storedAudio),
            sidecarReferences?.coverReference,
            sidecarReferences?.lyricReference,
            sidecarReferences?.translatedLyricReference
        )
        runCatching {
            removeManagedDownloadArtifacts(
                context = appContext,
                songName = song.name,
                storedAudio = storedAudio,
                songId = song.id,
                candidateBaseNames = candidateManagedDownloadBaseNames(storedAudio.nameWithoutExtension),
                explicitReferences = explicitReferences,
                useCachedSnapshotOnly = false
            )
            val updatedSongs = _downloadedSongs.value.filterNot { downloaded ->
                downloaded.filePath == storedAudio.reference || matchesDownloadedSong(song, downloaded)
            }
            if (updatedSongs != _downloadedSongs.value) {
                publishDownloadedSongs(appContext, updatedSongs, persistCatalog = true)
            } else {
                notifyDownloadPresenceChanged()
            }
        }.onFailure { error ->
            NPLogger.e(TAG, "下载元信息收尾失败后回滚半成品失败: ${song.name}, ${error.message}", error)
        }
    }

    private suspend fun cleanupOrphanedCompletedSidecars(
        context: Context,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ) {
        val references = listOfNotNull(
            sidecarReferences?.coverReference,
            sidecarReferences?.lyricReference,
            sidecarReferences?.translatedLyricReference
        )
        if (references.isEmpty()) {
            return
        }
        runCatching {
            ManagedDownloadStorage.deleteReferences(context.applicationContext, references)
        }.onFailure { error ->
            NPLogger.e(TAG, "清理孤立下载关联文件失败: ${song.name}, ${error.message}", error)
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
        ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, setOf(songKey))
        cleanupUnfinalizedDownloadForRetry(appContext, song)
    }

    private suspend fun cleanupCancelledDownloadArtifacts(
        context: Context,
        song: SongItem
    ) {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
        ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, setOf(songKey))
        scheduleCancelledArtifactRecovery(appContext, listOf(song))
    }

    private suspend fun recoverUnfinalizedDownloadArtifact(
        context: Context,
        song: SongItem,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null
    ): Boolean {
        val songKey = song.stableKey()
        if (
            shouldSkipCancelledArtifactRecovery(
                downloadActive = AudioDownloadManager.isSongDownloadActive(songKey),
                taskStatus = taskStore.findTask(songKey)?.status
            )
        ) {
            NPLogger.d(TAG, "跳过正在重新下载的取消恢复: song=${song.name}, songKey=$songKey")
            return false
        }
        val audio = snapshot?.let { ManagedDownloadStorage.findDownloadedAudio(it, song) }
            ?: ManagedDownloadStorage.findDownloadedAudio(
                context = context,
                song = song,
                forceRefresh = true
            )
            ?: return false
        val metadata = readDownloadedMetadata(context, audio)
        if (!isUnfinalizedDownloadedMetadata(metadata)) {
            return false
        }
        NPLogger.w(TAG, "发现未最终确认下载半成品，回滚等待重试: song=${song.name}, file=${audio.name}")
        rollbackCancelledDownload(
            context = context,
            song = song,
            storedAudio = audio
        )
        return true
    }

    private fun scheduleCancelledArtifactRecovery(
        context: Context,
        songs: Collection<SongItem>
    ) {
        val pendingSongs = songs
            .distinctBy { it.stableKey() }
            .filterNot { song ->
                shouldSkipCancelledArtifactRecovery(
                    downloadActive = AudioDownloadManager.isSongDownloadActive(song.stableKey()),
                    taskStatus = taskStore.findTask(song.stableKey())?.status
                )
            }
        if (pendingSongs.isEmpty()) {
            return
        }
        val appContext = context.applicationContext
        synchronized(cancelledArtifactRecoveryLock) {
            pendingSongs.forEach { song ->
                pendingCancelledArtifactRecoverySongs[song.stableKey()] = song
            }
            if (cancelledArtifactRecoveryJob?.isActive == true) {
                return
            }
            cancelledArtifactRecoveryJob = scope.launch {
                delay(DOWNLOAD_CANCELLED_ARTIFACT_RECOVERY_DELAY_MS)
                val recoverySongs = synchronized(cancelledArtifactRecoveryLock) {
                    val snapshot = pendingCancelledArtifactRecoverySongs.values.toList()
                    pendingCancelledArtifactRecoverySongs.clear()
                    cancelledArtifactRecoveryJob = null
                    snapshot
                }
                recoverCancelledArtifacts(appContext, recoverySongs)
            }
        }
    }

    private suspend fun recoverCancelledArtifacts(
        context: Context,
        songs: List<SongItem>
    ) {
        val recoverySongs = songs.filterNot { song ->
            shouldSkipCancelledArtifactRecovery(
                downloadActive = AudioDownloadManager.isSongDownloadActive(song.stableKey()),
                taskStatus = taskStore.findTask(song.stableKey())?.status
            )
        }
        if (recoverySongs.isEmpty()) {
            return
        }
        val snapshot = runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        }.onFailure { error ->
            NPLogger.w(TAG, "取消恢复构建下载索引失败，降级为后台对账: ${error.message}")
            scheduleCatalogReconcile(context, forceRefresh = true)
        }.getOrNull() ?: return
        var recoveredCount = 0
        recoverySongs.forEach { song ->
            if (recoverUnfinalizedDownloadArtifact(context, song, snapshot)) {
                recoveredCount++
            }
        }
        if (recoveredCount > 0) {
            NPLogger.d(TAG, "取消恢复完成: recovered=$recoveredCount, requested=${recoverySongs.size}")
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
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

    fun refreshDownloadedSongsForManager(context: Context) {
        val appContext = context.applicationContext
        scanLocalFiles(appContext, forceRefresh = false)
        scheduleCatalogReconcile(appContext, forceRefresh = true)
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
                    recoverUnfinalizedDownloadedAudio(
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

    private suspend fun recoverUnfinalizedDownloadedAudio(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        val metadata = snapshot.metadataByAudioName[storedAudio.name]
        if (isUnfinalizedDownloadStillActive(metadata)) {
            NPLogger.d(TAG, "跳过活跃下载的未最终确认文件: file=${storedAudio.name}")
            return
        }
        NPLogger.w(TAG, "扫描发现未最终确认下载半成品，隐藏并等待重试: file=${storedAudio.name}")
        if (storedAudio.sizeBytes > 0L) {
            return
        }
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
    ): DownloadedSong = downloadedSongBuilder.build(
        context = context,
        storedAudio = storedAudio,
        snapshot = snapshot,
        existingDownloadTime = existingDownloadTime,
        loadLyricContents = loadLyricContents,
        resolveLyricFallbacks = resolveLyricFallbacks,
        allowSlowLocalInspection = allowSlowLocalInspection
    )

    private suspend fun persistDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
        downloadFinalized: Boolean = true,
        resolveExistingSidecars: Boolean = true
    ): Boolean = downloadedAudioMetadataStore.persist(
        context = context,
        audio = audio,
        song = song,
        sidecarReferences = sidecarReferences,
        downloadFinalized = downloadFinalized,
        resolveExistingSidecars = resolveExistingSidecars
    )

    private suspend fun readDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        metadataEntry: ManagedDownloadStorage.StoredEntry? = null
    ): ManagedDownloadStorage.DownloadedAudioMetadata? = downloadedAudioMetadataStore.read(
        context = context,
        audio = audio,
        metadataEntry = metadataEntry
    )

    private suspend fun removeManagedDownloadArtifacts(
        context: Context,
        songName: String,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        songId: Long,
        candidateBaseNames: List<String>,
        explicitReferences: List<String> = emptyList(),
        useCachedSnapshotOnly: Boolean = false
    ): ManagedDownloadArtifactRemovalResult {
        return managedDownloadDeletePlanner.removeArtifacts(
            context = context,
            songName = songName,
            storedAudio = storedAudio,
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            explicitReferences = explicitReferences,
            useCachedSnapshotOnly = useCachedSnapshotOnly,
            logger = { message -> NPLogger.d(TAG, message) }
        )
    }

    internal fun trustedManagedMetadataReference(
        reference: String?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return ManagedDownloadArtifactPlanner.trustedMetadataReference(reference, snapshot)
    }

    private suspend fun buildManagedDownloadDeletePlans(
        context: Context,
        songs: List<DownloadedSong>
    ): List<ManagedDownloadSongDeletePlan> {
        return managedDownloadDeletePlanner.buildDeletePlans(context, songs)
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
            resolvedStoredAudio?.let(ManagedDownloadStorage::metadataReferenceForAudio),
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
            explicitReferences = explicitReferences,
            useCachedSnapshotOnly = true
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
            val startedAtMs = System.currentTimeMillis()
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
                val requiredReferences = mergeManagedRequestedReferences(
                    deletePlans.map(ManagedDownloadSongDeletePlan::requiredReferences)
                )
                NPLogger.d(
                    TAG,
                    "批量删除下载开始: songs=${targetSongs.size}, references=${requestedReferences.size}, optimisticRemoved=${previousSongs.size - optimisticSongs.size}"
                )
                val deletedReferences = if (requestedReferences.isNotEmpty()) {
                    ManagedDownloadStorage.deleteReferences(appContext, requestedReferences)
                } else {
                    emptySet()
                }
                val remainingReferences = requestedReferences - deletedReferences
                val remainingRequiredReferences = requiredReferences - deletedReferences
                val remainingRequiredReferencesBySong = groupRemainingManagedReferencesByIdentity(
                    requestedReferencesByIdentity = deletePlans.associate { plan ->
                        plan.song.deletionIdentity() to plan.requiredReferences
                    },
                    remainingReferences = remainingRequiredReferences
                )
                val hasUnconfirmedDeletes = remainingReferences.isNotEmpty()
                var deletionFailed = remainingRequiredReferences.isNotEmpty()
                deletePlans.forEach { deletePlan ->
                    val remainingForSong = remainingRequiredReferencesBySong[deletePlan.song.deletionIdentity()].orEmpty()
                    if (remainingForSong.isNotEmpty()) {
                        deletionFailed = true
                        NPLogger.w(
                            TAG,
                            "删除下载音频不完整: ${deletePlan.song.name}, remaining=$remainingForSong"
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
                NPLogger.d(
                    TAG,
                    "批量删除下载结束: songs=${targetSongs.size}, requested=${requestedReferences.size}, deleted=${deletedReferences.size}, failed=$deletionFailed, costMs=${System.currentTimeMillis() - startedAtMs}"
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
                if (playbackReference.isNullOrBlank()) {
                    NPLogger.w(TAG, "下载文件不存在: ${song.name}, reference=$playbackReference")
                    removeMissingDownloadedSongEntry(appContext, song)
                    return@launch
                }

                val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                    context = appContext,
                    restoreFromDisk = false
                )
                val storedAudio = snapshot?.audioEntriesByLookupKey?.get(playbackReference)
                val playbackUri = storedAudio?.playbackUri
                    ?: ManagedDownloadStorage.toPlayableUri(playbackReference)
                    ?: playbackReference
                val quickSong = song.toPlaybackSongItem(
                    playbackUri = playbackUri,
                    storedAudio = storedAudio,
                    resolvedDurationMs = song.durationMs
                )
                withContext<Unit>(Dispatchers.Main.immediate) {
                    PlayerManager.playPlaylist(listOf(quickSong), 0)
                }

                scheduleDownloadedPlaybackReferenceValidation(appContext, song, playbackReference)
                val hydratedStoredAudio = storedAudio
                    ?: resolveStoredAudioFromCache(appContext, playbackReference)
                val refreshedSong = hydratedStoredAudio?.let {
                    val hydrationSnapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(appContext)
                        ?: ManagedDownloadStorage.emptyDownloadLibrarySnapshot()
                    buildDownloadedSong(
                        context = appContext,
                        storedAudio = it,
                        snapshot = hydrationSnapshot,
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
                    storedAudio = hydratedStoredAudio,
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

    private fun scheduleDownloadedPlaybackReferenceValidation(
        context: Context,
        song: DownloadedSong,
        playbackReference: String
    ) {
        scope.launch {
            if (ManagedDownloadStorage.exists(context, playbackReference)) {
                return@launch
            }
            NPLogger.w(TAG, "下载文件后台校验不可读: ${song.name}, reference=$playbackReference")
            scheduleCatalogReconcile(context, forceRefresh = true)
        }
    }

    private fun removeMissingDownloadedSongEntry(
        context: Context,
        song: DownloadedSong
    ) {
        val updatedSongs = _downloadedSongs.value.filterNot { candidate ->
            matchesDownloadedSongCatalogEntry(candidate, song)
        }
        if (updatedSongs != _downloadedSongs.value) {
            publishDownloadedSongs(context, updatedSongs, persistCatalog = true)
        }
        scheduleCatalogReconcile(context, forceRefresh = false)
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
            localFilePath = storedAudio?.localFilePath ?: filePath.takeIf { it.startsWith("/") },
            sourceStableKey = stableKey
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

    fun findFastCachedDownloadedSongPlaybackUri(context: Context, song: SongItem): String? {
        val downloadedSong = findFastCachedDownloadedSong(context, song) ?: return null
        val reference = resolveDownloadedSongPlaybackReference(downloadedSong) ?: return null
        return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
    }

    private fun restorePersistedDownloadedSongs(context: Context): Boolean {
        val restoredSongs = downloadedSongCatalogStore.restore(context) ?: return false
        publishDownloadedSongs(context, restoredSongs, persistCatalog = false)
        return true
    }

    private fun persistDownloadedSongsCatalog(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        downloadedSongCatalogStore.persist(context, songs)
    }

    fun startDownload(context: Context, song: SongItem) {
        startDownload(context, song, skipTrafficRiskPrompt = false)
    }

    private fun startDownload(
        context: Context,
        song: SongItem,
        skipTrafficRiskPrompt: Boolean,
        cleanupBeforeStart: Boolean = true,
        deferForNetworkPolicy: Boolean = false
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val songKey = song.stableKey()
            clearSongCancellationForFreshStart(appContext, setOf(songKey))
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
            val requestGeneration = beginDownloadRequestGeneration(listOf(song))
            rememberPendingDownloadQueue(appContext, listOf(song))
            startDownloadConfirmed(
                context = appContext,
                song = song,
                cleanupBeforeStart = cleanupBeforeStart,
                requestGeneration = requestGeneration,
                deferForNetworkPolicy = deferForNetworkPolicy
            )
        }
    }

    private fun startDownloadConfirmed(
        context: Context,
        song: SongItem,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        deferForNetworkPolicy: Boolean
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val songKey = song.stableKey()
            if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                NPLogger.d(TAG, "忽略过期单曲下载启动: song=${song.name}, generation=$requestGeneration")
                return@launch
            }
            AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
            val attemptId = taskStore.prepareDownloadTask(song) ?: return@launch
            try {
                withSongExecutionLock(songKey) {
                    awaitSongCancellationSettled(
                        songKey = songKey,
                        timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS
                    )
                    if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                        NPLogger.d(TAG, "单曲下载等待取消收敛后已过期: song=${song.name}, generation=$requestGeneration")
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        return@withSongExecutionLock
                    }
                    if (cleanupBeforeStart) {
                        cleanupDownloadArtifactsBeforeFreshStart(appContext, song)
                    }
                    if (shouldSkipDownload(appContext, song)) {
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                        return@withSongExecutionLock
                    }

                    if (findFastCachedDownloadedSong(appContext, song) != null) {
                        NPLogger.d(TAG, "单曲下载命中下载目录缓存并直接完成: song=${song.name}, songKey=$songKey")
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                        return@withSongExecutionLock
                    }

                    val existingAudio = findExistingDownloadedAudio(
                        context = appContext,
                        song = song,
                        snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(appContext),
                        allowStorageLookup = false
                    )
                    val existingAudioAction = resolvePreExistingDownloadedAudioAction(
                        hasExistingAudio = existingAudio != null
                    )
                    if (existingAudio != null) {
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "单曲下载命中已存在文件时已过期: song=${song.name}, generation=$requestGeneration")
                            removeDownloadTask(songKey, expectedAttemptId = attemptId)
                            return@withSongExecutionLock
                        }
                        if (existingAudioAction == PreExistingDownloadedAudioAction.DIRECT_SETTLE) {
                            publishOptimisticDownloadedSongs(
                                appContext,
                                listOf(buildOptimisticDownloadedSong(song, existingAudio))
                            )
                            removeDownloadTask(songKey, expectedAttemptId = attemptId)
                            forgetPendingDownloadQueueEntriesIfCurrent(
                                appContext,
                                setOf(songKey),
                                requestGeneration
                            )
                            scheduleCatalogReconcile(appContext, forceRefresh = false)
                            NPLogger.d(
                                TAG,
                                "单曲下载命中已存在音频并直接完成: song=${song.name}, songKey=$songKey, file=${existingAudio.name}"
                            )
                            return@withSongExecutionLock
                        }
                    }

                    if (
                        deferPreparedDownloadStartForNetworkPolicyIfNeeded(
                            context = appContext,
                            songs = listOf(song),
                            attemptIdsBySongKey = mapOf(songKey to attemptId),
                            requestGeneration = requestGeneration,
                            reason = "single_start",
                            deferForNetworkPolicy = deferForNetworkPolicy
                        )
                    ) {
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
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "单曲下载完成后已过期，转入过期结果回滚: song=${song.name}, generation=$requestGeneration")
                            finalizeCompletedDownload(
                                context = appContext,
                                song = song,
                                refreshCatalog = false,
                                expectedAttemptId = attemptId
                            )
                            return@withSongExecutionLock
                        }
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
                if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                    return@launch
                }
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
                    forgetPendingDownloadQueueEntriesIfCurrent(
                        appContext,
                        setOf(songKey),
                        requestGeneration
                    )
                }
                taskStore.isSingleDownloading = false
            } catch (error: Exception) {
                if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                    return@launch
                }
                NPLogger.e(TAG, "下载失败: ${song.name} - ${error.message}", error)
                updateTaskStatus(
                    songKey,
                    DownloadStatus.FAILED,
                    expectedAttemptId = attemptId
                )
                forgetPendingDownloadQueueEntriesIfCurrent(
                    appContext,
                    setOf(songKey),
                    requestGeneration
                )
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
        cleanupBeforeStart: Boolean = true,
        replaceExistingActiveTasks: Boolean = false,
        deferForNetworkPolicy: Boolean = false
    ) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        scope.launch {
            val requestedSongs = songs.distinctBy { it.stableKey() }
            if (requestedSongs.isEmpty()) {
                return@launch
            }
            val requestedSongKeys = requestedSongs.mapTo(linkedSetOf()) { it.stableKey() }
            clearSongCancellationForFreshStart(appContext, requestedSongKeys)
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
            val requestGeneration = beginDownloadRequestGeneration(requestedSongs)
            rememberPendingDownloadQueue(appContext, requestedSongs)
            startBatchDownloadConfirmed(
                context = appContext,
                songs = requestedSongs,
                cleanupBeforeStart = cleanupBeforeStart,
                requestGeneration = requestGeneration,
                replaceExistingActiveTasks = replaceExistingActiveTasks,
                deferForNetworkPolicy = deferForNetworkPolicy
            )
        }
    }

    private fun startBatchDownloadConfirmed(
        context: Context,
        songs: List<SongItem>,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        replaceExistingActiveTasks: Boolean,
        deferForNetworkPolicy: Boolean
    ) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        val batchJob = scope.launch {
            val requestedSongs = songs.distinctBy { it.stableKey() }
                .filter { song ->
                    isDownloadRequestGenerationCurrent(song.stableKey(), requestGeneration)
                }
            if (requestedSongs.isEmpty()) {
                NPLogger.d(TAG, "忽略过期批量下载启动: generation=$requestGeneration")
                return@launch
            }
            val pendingSongs = mutableListOf<PreparedDownloadTaskRequest>()
            var skippedLocalSongs = 0
            var preparedQueuedSongs = 0
            try {
                NPLogger.d(
                    TAG,
                    "批量下载启动: requested=${songs.size}, deduped=${requestedSongs.size}, cleanupBeforeStart=$cleanupBeforeStart, replaceExistingActiveTasks=$replaceExistingActiveTasks, persistedQueued=${ManagedDownloadStorage.listPendingQueuedDownloads(appContext).size}, persistedCancelled=${ManagedDownloadStorage.listCancelledDownloadKeys(appContext).size}"
                )
                val settledSongKeys = mutableSetOf<String>()
                val currentRequestedSongs = requestedSongs.filter { song ->
                    isDownloadRequestGenerationCurrent(song.stableKey(), requestGeneration)
                }
                if (currentRequestedSongs.isEmpty()) {
                    NPLogger.d(TAG, "批量下载准备前请求已过期: generation=$requestGeneration")
                    return@launch
                }
                val preparedAttemptIds = taskStore.prepareDownloadTasks(
                    songs = currentRequestedSongs,
                    status = DownloadStatus.QUEUED,
                    replaceExistingActiveTasks = replaceExistingActiveTasks
                )
                val downloadLibrarySnapshot = buildBatchDownloadLibrarySnapshot(appContext)
                val settledAttemptIds = linkedMapOf<String, Long>()
                val optimisticDownloadedSongs = mutableListOf<DownloadedSong>()
                currentRequestedSongs.forEach { song ->
                    val songKey = song.stableKey()
                    if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                        settledSongKeys += songKey
                        return@forEach
                    }
                    AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
                    val attemptId = preparedAttemptIds[songKey]
                        ?: return@forEach
                    if (shouldSkipDownload(appContext, song)) {
                        skippedLocalSongs++
                        settledSongKeys += songKey
                        settledAttemptIds[songKey] = attemptId
                        NPLogger.d(TAG, "批量下载跳过本地歌曲: song=${song.name}, songKey=$songKey")
                        return@forEach
                    }
                    if (findFastCachedDownloadedSong(appContext, song) != null) {
                        settledSongKeys += songKey
                        settledAttemptIds[songKey] = attemptId
                        NPLogger.d(
                            TAG,
                            "批量下载命中下载目录缓存并直接完成: song=${song.name}, songKey=$songKey"
                        )
                        return@forEach
                    }
                    val existingAudio = findExistingDownloadedAudio(
                        context = appContext,
                        song = song,
                        snapshot = downloadLibrarySnapshot,
                        allowStorageLookup = false
                    )
                    val existingAudioAction = resolvePreExistingDownloadedAudioAction(
                        hasExistingAudio = existingAudio != null
                    )
                    if (existingAudio != null) {
                        if (existingAudioAction == PreExistingDownloadedAudioAction.DIRECT_SETTLE) {
                            settledSongKeys += songKey
                            settledAttemptIds[songKey] = attemptId
                            optimisticDownloadedSongs += buildOptimisticDownloadedSong(
                                song = song,
                                storedAudio = existingAudio
                            )
                            NPLogger.d(
                                TAG,
                                "批量下载命中已存在音频并直接完成: song=${song.name}, songKey=$songKey, file=${existingAudio.name}"
                            )
                            return@forEach
                        }
                    }
                    preparedQueuedSongs++
                    pendingSongs += PreparedDownloadTaskRequest(song = song, attemptId = attemptId)
                }
                removeDownloadTasks(settledAttemptIds)
                publishOptimisticDownloadedSongs(appContext, optimisticDownloadedSongs)

                forgetPendingDownloadQueueEntriesIfCurrent(
                    appContext,
                    settledSongKeys,
                    requestGeneration
                )

                if (pendingSongs.isEmpty()) {
                    NPLogger.d(
                        TAG,
                        "没有新的批量下载任务: requested=${requestedSongs.size}, skippedLocalSongs=$skippedLocalSongs, settledSongKeys=${settledSongKeys.size}, persistedQueued=${ManagedDownloadStorage.listPendingQueuedDownloads(appContext).size}, persistedCancelled=${ManagedDownloadStorage.listCancelledDownloadKeys(appContext).size}"
                    )
                    return@launch
                }
                val pendingAttemptIds = pendingSongs.associate { request ->
                    request.song.stableKey() to request.attemptId
                }
                if (
                    deferPreparedDownloadStartForNetworkPolicyIfNeeded(
                        context = appContext,
                        songs = pendingSongs.map(PreparedDownloadTaskRequest::song),
                        attemptIdsBySongKey = pendingAttemptIds,
                        requestGeneration = requestGeneration,
                        reason = "batch_start",
                        deferForNetworkPolicy = deferForNetworkPolicy
                    )
                ) {
                    return@launch
                }
                NPLogger.d(
                    TAG,
                    "批量下载正式开始: pendingSongs=${pendingSongs.size}, preparedQueuedSongs=$preparedQueuedSongs, settledSongKeys=${settledSongKeys.size}"
                )

                AudioDownloadManager.resetCancelFlag()
                val downloadParallelism =
                    AudioDownloadManager.resolveConfiguredDownloadParallelism(appContext)
                AudioDownloadManager.downloadPlaylist(
                    context = appContext,
                    songs = pendingSongs.map(PreparedDownloadTaskRequest::song),
                    maxConcurrentDownloads = downloadParallelism,
                    songAttemptIds = pendingAttemptIds,
                    onSongStarted = { startedSong ->
                        val songKey = startedSong.stableKey()
                        val attemptId = pendingAttemptIds[songKey] ?: return@downloadPlaylist
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "忽略旧批次开始回调: song=${startedSong.name}, generation=$requestGeneration")
                            return@downloadPlaylist
                        }
                        if (cleanupBeforeStart) {
                            cleanupDownloadArtifactsBeforeFreshStart(appContext, startedSong)
                        }
                        taskStore.registerActiveDownloadTask(startedSong, expectedAttemptId = attemptId)
                    },
                    onSongCompleted = { completedSong ->
                        val songKey = completedSong.stableKey()
                        val attemptId = pendingAttemptIds[songKey] ?: return@downloadPlaylist
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "旧批次完成回调已过期，转入过期结果回滚: song=${completedSong.name}, generation=$requestGeneration")
                            finalizeCompletedDownload(
                                context = appContext,
                                song = completedSong,
                                refreshCatalog = false,
                                expectedAttemptId = attemptId
                            )
                            return@downloadPlaylist
                        }
                        finalizeCompletedDownload(
                            context = appContext,
                            song = completedSong,
                            refreshCatalog = false,
                            expectedAttemptId = attemptId
                        )
                    },
                    onSongFailed = { failedSong, _ ->
                        val songKey = failedSong.stableKey()
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "忽略旧批次失败回调: song=${failedSong.name}, generation=$requestGeneration")
                            return@downloadPlaylist
                        }
                        val attemptId = pendingAttemptIds[songKey] ?: return@downloadPlaylist
                        updateTaskStatus(
                            songKey,
                            DownloadStatus.FAILED,
                            expectedAttemptId = attemptId
                        )
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                    },
                    onSongCancelled = { cancelledSong ->
                        val songKey = cancelledSong.stableKey()
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "忽略旧批次取消回调: song=${cancelledSong.name}, generation=$requestGeneration")
                            return@downloadPlaylist
                        }
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
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                    }
                )
            } catch (_: CancellationException) {
                val cancelledSongKeys = mutableSetOf<String>()
                pendingSongs.forEach { request ->
                    val songKey = request.song.stableKey()
                    if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                        return@forEach
                    }
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
                forgetPendingDownloadQueueEntriesIfCurrent(
                    appContext,
                    cancelledSongKeys,
                    requestGeneration
                )
            } catch (error: Exception) {
                NPLogger.e(TAG, "批量下载失败: ${error.message}", error)
                pendingSongs.forEach { request ->
                    if (!isDownloadRequestGenerationCurrent(request.song.stableKey(), requestGeneration)) {
                        return@forEach
                    }
                    updateTaskStatus(
                        request.song.stableKey(),
                        DownloadStatus.FAILED,
                        expectedAttemptId = request.attemptId
                    )
                }
                forgetPendingDownloadQueueEntriesIfCurrent(
                    appContext,
                    pendingSongs.map { it.song.stableKey() }.toSet(),
                    requestGeneration
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
        return findExistingDownloadedAudio(
            context = context,
            song = song,
            snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context),
            allowStorageLookup = false
        )
    }

    private suspend fun buildBatchDownloadLibrarySnapshot(
        context: Context
    ): ManagedDownloadStorage.DownloadLibrarySnapshot? {
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
        if (snapshot == null) {
            NPLogger.d(TAG, "批量下载跳过同步 SAF 索引，后台对账下载目录")
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
        return snapshot
    }

    private suspend fun findExistingDownloadedAudio(
        context: Context,
        song: SongItem,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        allowStorageLookup: Boolean = true
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
            ?: snapshot?.let { ManagedDownloadStorage.findDownloadedAudio(it, song) }
            ?: if (allowStorageLookup) {
                ManagedDownloadStorage.findDownloadedAudio(context, song)
            } else {
                null
            }
            ?: return null
        NPLogger.d(
            TAG,
            "命中已下载候选文件: song=${song.name}, file=${existingAudio.name}, size=${existingAudio.sizeBytes}"
        )
        return validateExistingDownloadedAudio(
            context = context,
            song = song,
            audio = existingAudio,
            snapshotMetadata = snapshot?.metadataByAudioName?.get(existingAudio.name)
        )
    }

    private fun findFastCachedDownloadedSong(
        context: Context,
        song: SongItem
    ): DownloadedSong? {
        val downloadedSong = downloadedSongCatalogIndex.find(song) ?: return null
        val reference = resolveDownloadedSongPlaybackReference(downloadedSong) ?: return null
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restoreFromDisk = false
        )
        if (!shouldTrustFastDownloadedSongCatalogHit(reference, snapshot?.knownReferences)) {
            NPLogger.w(
                TAG,
                "下载目录缓存与索引不一致，后台强制刷新: song=${song.name}, reference=$reference"
            )
            scheduleCatalogReconcile(context, forceRefresh = true)
            return null
        }
        if (snapshot == null) {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
        return downloadedSong
    }

    private suspend fun validateExistingDownloadedAudio(
        context: Context,
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry,
        snapshotMetadata: ManagedDownloadStorage.DownloadedAudioMetadata? = null
    ): ManagedDownloadStorage.StoredEntry? {
        val metadata = snapshotMetadata ?: run {
            val metadataEntry = ManagedDownloadStorage.findMetadataForAudio(context, audio)
            metadataEntry?.let {
                readDownloadedMetadata(
                    context = context,
                    audio = audio,
                    metadataEntry = it
                )
            }
        }
        if (isUnfinalizedDownloadedMetadata(metadata)) {
            val unfinalizedMetadata = metadata ?: return null
            if (!isMetadataOwnedBySong(unfinalizedMetadata, song)) {
                NPLogger.w(TAG, "未最终确认文件不属于当前歌曲，跳过回滚: song=${song.name}, file=${audio.name}")
                return null
            }
            NPLogger.w(TAG, "发现未最终确认下载文件，等待重新下载收尾: song=${song.name}, file=${audio.name}")
            rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
            return null
        }

        if (metadata != null && isMetadataOwnedBySong(metadata, song) && audio.sizeBytes > 0L) {
            NPLogger.d(
                TAG,
                "已下载文件 metadata 快速校验通过: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
            )
            return audio
        }

        val localDetails = inspectDownloadedAudioDetails(context, audio)
        if (metadata != null && localDetails == null) {
            if (audio.sizeBytes > 0L) {
                NPLogger.w(
                    TAG,
                    "已下载文件存在 metadata 但音频标签不可读，保留并复用: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
                )
                return audio
            }
            NPLogger.w(
                TAG,
                "已下载文件存在 metadata 但文件为空，回滚后重新下载: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
            )
            if (isMetadataOwnedBySong(metadata, song)) {
                rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
            }
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
            NPLogger.w(TAG, "发现无法验证的候选文件，未确认归属前不回滚: song=${song.name}, file=${audio.name}")
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
        if (isDownloadedAudioLikelyOwnedBySong(metadata, song, audio)) {
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = audio
            )
        }
        return null
    }

    private fun isDownloadedAudioLikelyOwnedBySong(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        return metadata?.let { isMetadataOwnedBySong(it, song) } == true ||
            matchesExpectedDownloadFileName(song, audio)
    }

    private suspend fun cleanupUnfinalizedDownloadForRetry(
        context: Context,
        song: SongItem
    ) {
        val audio = ManagedDownloadStorage.findDownloadedAudio(
            context = context,
            song = song,
            forceRefresh = true
        ) ?: return
        val metadata = readDownloadedMetadata(context, audio)
        if (!isUnfinalizedDownloadedMetadata(metadata)) {
            return
        }
        NPLogger.w(TAG, "重试前清理未最终确认下载文件: song=${song.name}, file=${audio.name}")
        rollbackCancelledDownload(
            context = context,
            song = song,
            storedAudio = audio
        )
    }

    private fun isMetadataOwnedBySong(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata,
        song: SongItem
    ): Boolean {
        val identity = song.identity()
        val stableKey = identity.stableKey()
        if (metadata.stableKey == stableKey) {
            return true
        }
        if (metadata.songId != null && metadata.songId > 0L && metadata.songId == song.id) {
            return true
        }
        val remoteTrackKey = buildDownloadRemoteTrackKey(
            channelId = metadata.channelId,
            audioId = metadata.audioId,
            subAudioId = metadata.subAudioId
        )
        val songRemoteTrackKey = buildDownloadRemoteTrackKey(
            channelId = song.channelId,
            audioId = song.audioId,
            subAudioId = song.subAudioId
        )
        if (remoteTrackKey != null && remoteTrackKey == songRemoteTrackKey) {
            return true
        }
        return metadata.mediaUri?.takeIf(String::isNotBlank) == identity.mediaUri
    }

    private fun buildDownloadRemoteTrackKey(
        channelId: String?,
        audioId: String?,
        subAudioId: String?
    ): String? {
        val normalizedAudioId = audioId?.takeIf(String::isNotBlank) ?: return null
        return listOfNotNull(
            channelId?.takeIf(String::isNotBlank),
            normalizedAudioId,
            subAudioId?.takeIf(String::isNotBlank)
        ).joinToString("|")
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

    private fun removeDownloadTasks(expectedAttemptIdsBySongKey: Map<String, Long>) {
        taskStore.removeDownloadTasks(expectedAttemptIdsBySongKey)
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
            pendingCatalogReconcileForceRefresh = pendingCatalogReconcileForceRefresh || forceRefresh
            if (catalogReconcileJob?.isActive == true) {
                return
            }
            catalogReconcileJob = scope.launch {
                delay(DOWNLOAD_CATALOG_RECONCILE_DELAY_MS)
                val shouldForceRefresh = synchronized(catalogPersistenceLock) {
                    val requestedForceRefresh = pendingCatalogReconcileForceRefresh
                    pendingCatalogReconcileForceRefresh = false
                    catalogReconcileJob = null
                    requestedForceRefresh
                }
                scanLocalFiles(appContext, forceRefresh = shouldForceRefresh)
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

    private fun beginDownloadRequestGeneration(songs: Collection<SongItem>): Long {
        val snapshot = requestGenerationTracker.begin(songs)
        NPLogger.d(TAG, "登记下载请求代际: generation=${snapshot.generation}, songs=${snapshot.songCount}")
        return snapshot.generation
    }

    private fun invalidateDownloadRequestGenerations(songKeys: Collection<String>) {
        val invalidatedCount = requestGenerationTracker.invalidate(songKeys)
        if (invalidatedCount > 0) {
            NPLogger.d(TAG, "失效下载请求代际: songs=$invalidatedCount")
        }
    }

    private fun isDownloadRequestGenerationCurrent(
        songKey: String,
        generation: Long
    ): Boolean {
        return requestGenerationTracker.isCurrent(songKey, generation)
    }

    private fun isCancellationCleanupStillCurrent(
        songKey: String,
        cancellationGeneration: Long?
    ): Boolean {
        return requestGenerationTracker.shouldKeepCancellationCleanup(
            songKey = songKey,
            cancellationGeneration = cancellationGeneration,
            cancelled = isSongCancelled(songKey)
        )
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

    private fun forgetPendingDownloadQueueEntriesIfCurrent(
        context: Context,
        songKeys: Collection<String>,
        generation: Long
    ) {
        val currentKeys = songKeys
            .filter(String::isNotBlank)
            .filter { songKey -> isDownloadRequestGenerationCurrent(songKey, generation) }
        if (currentKeys.isEmpty()) {
            val requestedCount = songKeys.count { it.isNotBlank() }
            if (requestedCount > 0) {
                NPLogger.d(TAG, "跳过过期队列移除: generation=$generation, requested=$requestedCount")
            }
            return
        }
        forgetPendingDownloadQueueEntries(context, currentKeys)
    }

    private fun clearPendingDownloadQueue(context: Context) {
        ManagedDownloadStorage.clearPendingDownloadQueue(context)
    }

    private fun clearCancelledDownloadKeys(context: Context) {
        ManagedDownloadStorage.clearCancelledDownloadKeys(context)
    }

    private fun clearSongCancellationForFreshStart(
        context: Context,
        songKeys: Collection<String>
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        cancelledSongKeys.removeAll(keys)
        ManagedDownloadStorage.removeCancelledDownloadKeys(context, keys)
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
        persistCancelledDownloadKeys(setOf(songKey))
        removeDownloadTask(songKey, expectedAttemptId = task.attemptId)
        forgetPendingDownloadQueueEntries(AppContainer.applicationContext, setOf(songKey))
        val cancellationGeneration = requestGenerationTracker.cancellationGeneration(songKey)
        scope.launch {
            cancelDownloadTaskInBackground(task, cancellationGeneration)
        }
    }

    fun clearAllDownloadTasks() {
        cancelAllDownloadTasks()
    }

    fun cancelAllDownloadTasks() {
        clearCompletedTasks()
        val appContext = AppContainer.applicationContext
        val batchJobs = activeBatchDownloadJobs.toList()
        val activeTasks = taskStore.currentTasks().filter { task ->
            task.status == DownloadStatus.QUEUED ||
                task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.WAITING_NETWORK
        }
        val persistedQueuedKeysBefore = ManagedDownloadStorage.listPendingQueuedDownloads(appContext)
            .mapTo(linkedSetOf()) { it.stableKey }
        val persistedQueuedCountBefore = persistedQueuedKeysBefore.size
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

        val activeSongKeys = activeTasks.mapTo(persistedQueuedKeysBefore) { it.song.stableKey() }
        activeSongKeys.forEach(::markSongCancelled)
        persistCancelledDownloadKeys(activeSongKeys)
        val cancellationGenerations = requestGenerationTracker.cancellationGenerations(activeSongKeys)
        invalidateDownloadRequestGenerations(activeSongKeys)
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
            cancelDownloadTasksInBackground(
                context = appContext,
                tasks = activeTasks,
                cancelledSongKeysSnapshot = activeSongKeys,
                cancellationGenerations = cancellationGenerations
            )
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
            publishMobileDataDownloadInterruptionRequestIfNeeded(
                networkType = networkType,
                taskCount = activeTasks.size,
                reason = "wifi_disconnected"
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
                waitForQueuedTasksToAttachToBatch()
                if (hasBlockingActiveDownloadOperationsForRecovery()) {
                    return@launch
                }
                recoverPendingResumableDownloads(appContext, reason = reason)
                delay(1_500L)
            } finally {
                finishPendingDownloadRecovery()
            }
        }
    }

    private suspend fun cancelDownloadTaskInBackground(
        task: DownloadTask,
        cancellationGeneration: Long?
    ) {
        val appContext = AppContainer.applicationContext
        val songKey = task.song.stableKey()
        if (isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            persistCancelledDownloadKeys(setOf(songKey))
        }
        AudioDownloadManager.cancelSongDownload(songKey)
        awaitSongCancellationSettled(
            songKey = songKey,
            timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS,
            clearCancellationWhenSettled = false
        )
        withSongExecutionLock(songKey) {
            if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
                NPLogger.d(TAG, "跳过过期单曲取消清理: song=${task.song.name}, songKey=$songKey")
                return@withSongExecutionLock
            }
            cleanupCancelledDownloadArtifacts(appContext, task.song)
            clearSongCancelled(songKey)
        }
    }

    private suspend fun cancelDownloadTasksInBackground(
        context: Context,
        tasks: Collection<DownloadTask>,
        additionalSongKeys: Collection<String> = emptySet(),
        cancelledSongKeysSnapshot: Collection<String> = emptySet(),
        cancellationGenerations: Map<String, Long?> = emptyMap()
    ) {
        val appContext = context.applicationContext
        val persistedKeys = cancelledSongKeysSnapshot
            .filter(String::isNotBlank)
            .toMutableSet()
        val activeKeys = tasks.mapTo(persistedKeys) { it.song.stableKey() }
        additionalSongKeys
            .filter(String::isNotBlank)
            .forEach(activeKeys::add)
        val currentCancellationKeys = activeKeys
            .filter { songKey ->
                isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])
            }
            .toSet()
        currentCancellationKeys.forEach(::markSongCancelled)
        persistCancelledDownloadKeys(currentCancellationKeys)
        val activeDownloadTaskKeys = tasks.mapNotNullTo(linkedSetOf()) { task ->
            val songKey = task.song.stableKey()
            when {
                songKey !in activeKeys -> null
                task.status == DownloadStatus.DOWNLOADING -> songKey
                AudioDownloadManager.isSongDownloadActive(songKey) -> songKey
                else -> null
            }
        }
        awaitDownloadCancellationsSettled(activeDownloadTaskKeys)
        val focusedCleanupKeys = mutableSetOf<String>()
        tasks.forEach { task ->
            val songKey = task.song.stableKey()
            if (songKey !in activeDownloadTaskKeys) return@forEach
            withSongExecutionLock(songKey) {
                if (!isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])) {
                    NPLogger.d(TAG, "跳过过期批量取消清理: song=${task.song.name}, songKey=$songKey")
                    return@withSongExecutionLock
                }
                cleanupCancelledDownloadArtifacts(appContext, task.song)
                focusedCleanupKeys += songKey
            }
        }
        val batchCleanupKeys = activeKeys
            .minus(focusedCleanupKeys)
            .filter { songKey ->
                isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])
            }
            .toSet()
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
            appContext,
            batchCleanupKeys
        )
        val releasableCancelledKeys = activeKeys
            .filter { songKey ->
                isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])
            }
            .toSet()
        ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, releasableCancelledKeys)
        releasableCancelledKeys.forEach(::clearSongCancelled)
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
        return mutex.withLock {
            block()
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
            cleanupBeforeStart = task.status != DownloadStatus.WAITING_NETWORK,
            deferForNetworkPolicy = false
        )
    }

    fun clearCompletedTasks() {
        val appContext = AppContainer.applicationContext
        val clearableKeys = taskStore.currentTasks()
            .filter(::isDownloadTaskClearable)
            .mapTo(linkedSetOf()) { task -> task.song.stableKey() }
        if (clearableKeys.isEmpty()) {
            return
        }
        taskStore.clearCompletedTasks()
        forgetPendingDownloadQueueEntries(appContext, clearableKeys)
        ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, clearableKeys)
        cancelledSongKeys.removeAll(clearableKeys)
        NPLogger.d(TAG, "清除终态下载任务: count=${clearableKeys.size}")
    }

    private suspend fun awaitSongCancellationSettled(
        songKey: String,
        timeoutMs: Long = DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS,
        clearCancellationWhenSettled: Boolean = true
    ): Boolean {
        if (!isSongCancelled(songKey) && !AudioDownloadManager.isSongDownloadActive(songKey)) {
            return true
        }
        NPLogger.d(
            TAG,
            "等待歌曲取消状态收敛: songKey=$songKey, cancelled=${isSongCancelled(songKey)}, active=${AudioDownloadManager.isSongDownloadActive(songKey)}"
        )

        val deadlineAt = System.currentTimeMillis() + timeoutMs
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
        if (clearCancellationWhenSettled) {
            clearSongCancelled(songKey)
        }
        return true
    }

    private fun songExecutionMutex(songKey: String): Mutex {
        val index = (songKey.hashCode() and Int.MAX_VALUE) % songExecutionLocks.size
        return songExecutionLocks[index]
    }

    private suspend fun resolveStoredAudio(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? {
        resolveStoredAudio(context, resolveSongLocation(song))?.let { return it }
        return ManagedDownloadStorage.findDownloadedAudio(context, song)
    }

    private fun resolveStoredAudioFromCache(
        context: Context,
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val normalized = reference?.takeIf(String::isNotBlank) ?: return null
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
            ?: return null
        return snapshot.audioEntriesByLookupKey[normalized]
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
    ) = downloadedSongBuilder.inspectAudioDetails(context, storedAudio)

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

}
