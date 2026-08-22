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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.catalog.downloadedSongNewestFirstComparator
import moe.ouom.neriplayer.core.download.catalog.projectDownloadedSongMetadata
import moe.ouom.neriplayer.core.download.catalog.toMetadataPersistenceSong
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactCoordinator
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.enrichment.AssetEnrichmentCoordinator
import moe.ouom.neriplayer.core.download.policy.TagPostProcessingAction
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanDecision
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanObservation
import moe.ouom.neriplayer.core.download.reconcile.ManagedLibraryReconciler
import moe.ouom.neriplayer.core.download.reconcile.ScanConfidence
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuilder
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.policy.shouldPreserveCompletedAudioAfterFinalizationFailure
import moe.ouom.neriplayer.core.download.policy.tagPostProcessingAction
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

internal fun shouldRebuildDownloadedLibrarySnapshot(recoveredArtifactCount: Int): Boolean {
    return recoveredArtifactCount > 0
}

internal fun shouldFinalizeDownloadedSidecars(
    hasNetworkCoverCandidate: Boolean,
    coverReference: String?,
    coverAccessible: Boolean
): Boolean {
    if (!hasNetworkCoverCandidate) {
        return true
    }
    return !coverReference.isNullOrBlank() && coverAccessible
}

internal fun shouldApplyDownloadedPlaybackHydration(
    currentSong: SongItem?,
    quickSong: SongItem
): Boolean = currentSong?.sameIdentityAs(quickSong) == true

/**
 * 全局下载管理器, 统一维护下载任务和本地下载列表
 */
object GlobalDownloadManager {
    private const val TAG = "GlobalDownloadManager"
    private const val INITIAL_SCAN_DELAY_MS = 1_500L
    private const val DOWNLOAD_CATALOG_CACHE_FILE_NAME = "downloaded_song_catalog_v4.json"
    private const val DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS = 1_200L
    private const val DOWNLOAD_TASK_COMPLETED_RETENTION_MS = 800L
    private const val DOWNLOAD_CATALOG_RECONCILE_DELAY_MS = 1_200L
    private const val DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS = 5_000L
    private const val DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS = 1_200L
    private const val DOWNLOAD_CANCELLED_ARTIFACT_RECOVERY_DELAY_MS = 800L
    private const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_GRACE_MS = 300L
    private const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_POLL_MS = 50L
    private const val DOWNLOADED_SONG_BUILD_PARALLELISM = 4
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
    private val downloadedSongBuildDispatcher =
        Dispatchers.IO.limitedParallelism(DOWNLOADED_SONG_BUILD_PARALLELISM)

    internal enum class DownloadedSongMetadataSyncOutcome {
        SUCCESS,
        NOT_DOWNLOADED,
        FAILED
    }

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
    private val managedDownloadArtifactCoordinator = ManagedDownloadArtifactCoordinator()
    private val assetEnrichmentCoordinator = AssetEnrichmentCoordinator(
        scope = scope,
        parallelism = METADATA_POST_PROCESSING_PARALLELISM,
        timeoutMs = 120_000L
    )
    private val managedLibraryReconciler = ManagedLibraryReconciler()
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
    private val downloadedSongMetadataSyncMutex = Mutex()
    private val downloadedSongDeleteMutex = Mutex()
    private val downloadedSongMetadataRevision = AtomicLong(0L)
    private val emptyScanSequence = AtomicLong(0L)
    private val cancelledArtifactRecoveryLock = Any()
    private var refreshJob: Job? = null
    private val refreshWaiters = mutableSetOf<CompletableDeferred<Unit>>()
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

    // 当前内存 catalog 所属的存储 root 标识 (restore/扫描发布时更新)
    // 用于把"切换/重置目录后扫描新目录得到的真空"与"同目录 SAF 瞬时空列举失败"区分开:
    // 前者 scanRootKey != catalogRootKey, 应放行清空; 后者相等, 才启用 #D4 可疑空保护
    @Volatile
    private var downloadedSongCatalogRootKey: String? = null

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
    private val managedDownloadArtifactLeases = ConcurrentHashMap<String, String>()
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
            recoverPendingAudioWritesFromRoot(appContext)
            runCatching { ManagedDownloadStorage.listCancelledDownloadKeys(appContext) }
                .onFailure { error ->
                    NPLogger.w(TAG, "预热已取消下载标记失败: ${error.message}")
                }
            recoverPendingDownloadsForStartup(appContext)
            LegacyJsonCleanupScheduler.schedule(appContext, "download-startup")
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
                forceRefresh = true
            )
        }
    }

    private suspend fun recoverPendingAudioWritesFromRoot(context: Context) {
        runCatching {
            ManagedDownloadStorage.listPendingAudioWrites(
                context = context,
                forceRefresh = true
            ).forEach { pendingAudio ->
                val metadataEntry = ManagedDownloadStorage.findMetadataForAudio(
                    context = context,
                    audio = pendingAudio
                ) ?: return@forEach
                val rawMetadata = ManagedDownloadStorage.readText(
                    context = context,
                    reference = metadataEntry.reference
                ) ?: return@forEach
                val metadata = ManagedDownloadStorage.parseDownloadedAudioMetadataJson(rawMetadata)
                    ?: return@forEach
                val song = buildSongForUnfinalizedRecovery(pendingAudio, metadata)
                    ?: return@forEach
                val coreMetadata = org.json.JSONObject(rawMetadata).apply {
                    put("downloadFinalized", false)
                    put("artifactState", "CORE_COMMITTED")
                    put("audioFileName", pendingAudio.logicalName)
                }.toString()
                val promotedAudio = ManagedDownloadStorage.promoteCoreAudioMetadata(
                    context = context,
                    audio = pendingAudio,
                    json = coreMetadata
                ) ?: return@forEach
                NPLogger.d(
                    TAG,
                    "从 pending 音频恢复 core commit: " +
                        "song=${song.name}, file=${promotedAudio.name}"
                )
                finalizeCompletedDownload(
                    context = context,
                    song = song,
                    refreshCatalog = false,
                    storedAudioHint = promotedAudio,
                    allowMissingTask = true
                )
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "恢复 pending 音频失败，保留文件等待下次启动: ${error.message}")
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
            reconcilePendingDownloadArtifacts(appContext)
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

    private suspend fun reconcilePendingDownloadArtifacts(context: Context) {
        val songs = buildList {
            addAll(
                ManagedDownloadStorage.listPendingQueuedDownloads(context)
                    .map(ManagedDownloadStorage.PendingDownloadQueueEntry::song)
            )
            addAll(
                ManagedDownloadStorage.listPendingResumableDownloads(context)
                    .map(ManagedDownloadStorage.PendingResumableDownload::song)
            )
            addAll(currentWaitingNetworkTaskSongs())
        }.distinctBy(SongItem::stableKey)
        if (songs.isEmpty()) return
        runCatching {
            managedDownloadArtifactCoordinator.reconcilePendingStorage(
                context = context,
                songs = songs
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "启动恢复前对账 pending artifact 失败: ${error.message}")
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

            val antiJoinedResumableSongs =
                managedDownloadArtifactCoordinator.filterNotFinalized(
                    context = context,
                    songs = recoveryPlan.resumableSongs
                )
            val finalizedRecoveryKeys = recoveryPlan.resumableSongs
                .asSequence()
                .map(SongItem::stableKey)
                .filterNot { key -> antiJoinedResumableSongs.any { it.stableKey() == key } }
                .toSet()
            if (finalizedRecoveryKeys.isNotEmpty()) {
                forgetPendingDownloadQueueEntries(context, finalizedRecoveryKeys)
                ManagedDownloadStorage.removeCancelledDownloadKeys(context, finalizedRecoveryKeys)
            }

            NPLogger.d(
                TAG,
                "检测到未完成下载，准备自动恢复: reason=$reason, " +
                    "count=${antiJoinedResumableSongs.size}, " +
                    "antiJoinedFinalized=${finalizedRecoveryKeys.size}, " +
                    "queued=${recoveryPlan.pendingQueuedDownloads.size}, " +
                    "partial=${recoveryPlan.pendingDownloads.size}"
            )
            if (antiJoinedResumableSongs.isEmpty()) {
                return
            }
            startBatchDownload(
                context = context,
                songs = antiJoinedResumableSongs,
                skipTrafficRiskPrompt = true,
                cleanupBeforeStart = false,
                replaceExistingActiveTasks = true,
                deferForNetworkPolicy = true,
                materializeQueuedTasks = true
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
        val previousSongs = _downloadedSongs.value
        val changedSongKeys = changedDownloadedSongKeys(
            previousSongs = previousSongs,
            currentSongs = songs
        )
        // 先发布索引再通知列表, 避免界面首帧看到歌曲时索引仍为空
        downloadedSongCatalogIndex = buildDownloadedSongCatalogIndex(songs)
        downloadedSongCatalogReady = true
        _downloadedSongs.value = songs
        LocalAssetInvalidationBus.bumpSongs(changedSongKeys)
        _downloadPresenceVersion.value += 1
        if (persistCatalog) {
            scheduleDownloadedSongsCatalogPersist(context, songs)
        }
    }

    private fun changedDownloadedSongKeys(
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

    private fun notifyDownloadPresenceChanged() {
        _downloadPresenceVersion.value += 1
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
        storedAudioHint: ManagedDownloadStorage.StoredEntry? = null,
        allowMissingTask: Boolean = false
    ) {
        val songKey = song.stableKey()
        val completedAudio = AudioDownloadManager.consumeCompletedAudioReference(songKey)
        val sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
        val currentTask = taskStore.findTask(songKey)
        if (
            (currentTask == null && !allowMissingTask) ||
            (currentTask != null && !shouldApplyTaskMutation(currentTask, expectedAttemptId))
        ) {
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
        val artifactLeaseId = managedDownloadArtifactLeases[songKey]
        if (artifactLeaseId != null) {
            runCatching {
                managedDownloadArtifactCoordinator.markCommitting(
                    context = context,
                    song = song,
                    expectedLeaseId = artifactLeaseId
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "更新下载 artifact 提交状态失败: ${error.message}")
            }
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
                if (artifactLeaseId == null) {
                    markDownloadArtifactMissingConfirmed(
                        context = context,
                        song = song,
                        errorCode = "AUDIO_REFERENCE_MISSING"
                    )
                } else {
                    markDownloadArtifactRetryable(
                        context = context,
                        song = song,
                        leaseId = artifactLeaseId,
                        errorCode = "AUDIO_REFERENCE_MISSING"
                    )
                }
                forgetPendingDownloadQueueEntries(context, setOf(songKey))
                scheduleCatalogReconcile(context, forceRefresh = true)
                return
            }
            CompletedDownloadFinalizationAction.COMPLETE -> Unit
        }
        val resolvedStoredAudio = storedAudio ?: run {
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

        val existingMetadata = readDownloadedMetadata(
            context = context.applicationContext,
            audio = resolvedStoredAudio
        )
        val existingArtifactState = existingMetadata?.artifactState
        val usesCoreCommitPath = existingMetadata?.downloadFinalized != true ||
            existingArtifactState in setOf(
                ManagedDownloadArtifactState.COMMITTING.name,
                ManagedDownloadArtifactState.CORE_COMMITTED.name,
                ManagedDownloadArtifactState.ASSETS_ENRICHING.name
            )
        if (usesCoreCommitPath) {
            completeCoreDownloadAndEnqueueEnrichment(
                context = context.applicationContext,
                song = song,
                storedAudio = resolvedStoredAudio,
                existingMetadata = existingMetadata,
                artifactLeaseId = artifactLeaseId,
                expectedAttemptId = expectedAttemptId,
                refreshCatalog = refreshCatalog
            )
            return
        }

        val finalization = finalizeCompletedDownloadMetadata(
            context = context,
            song = song,
            storedAudio = resolvedStoredAudio,
            initialSidecarReferences = sidecarReferences
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
            val audioStillExists = ManagedDownloadStorage.exists(
                context.applicationContext,
                resolvedStoredAudio.reference
            )
            val preserveAudio = shouldPreserveCompletedAudioAfterFinalizationFailure(
                audioStillExists,
                cancelled = false
            )
            if (preserveAudio) {
                NPLogger.w(
                    TAG,
                    "下载元信息收尾失败但音频已完整落盘，保留文件等待后续重试: ${song.name}"
                )
            } else {
                rollbackFailedCompletedDownloadFinalization(
                    context = context,
                    song = song,
                    storedAudio = resolvedStoredAudio,
                    sidecarReferences = finalization.sidecarReferences
                )
            }
            updateTaskStatus(
                songKey,
                DownloadStatus.FAILED,
                expectedAttemptId = expectedAttemptId
            )
            if (preserveAudio) {
                markDownloadArtifactRepairRequired(
                    context = context,
                    song = song,
                    leaseId = artifactLeaseId,
                    errorCode = "METADATA_FINALIZATION_FAILED"
                )
            } else {
                markDownloadArtifactRetryable(
                    context = context,
                    song = song,
                    leaseId = artifactLeaseId,
                    errorCode = "METADATA_FINALIZATION_FAILED"
                )
            }
            managedDownloadArtifactLeases.remove(songKey, artifactLeaseId)
            forgetPendingDownloadQueueEntries(context, setOf(songKey))
            scheduleCatalogReconcile(context, forceRefresh = true)
            return
        }

        publishCompletedDownloadOptimistically(
            context = context,
            song = song,
            storedAudio = finalization.storedAudio ?: resolvedStoredAudio,
            sidecarReferences = finalization.sidecarReferences
        )
        updateTaskStatus(
            songKey,
            DownloadStatus.COMPLETED,
            expectedAttemptId = expectedAttemptId
        )
        markDownloadArtifactFinalized(
            context = context,
            song = song,
            storedAudio = finalization.storedAudio ?: resolvedStoredAudio,
            leaseId = artifactLeaseId
        )
        managedDownloadArtifactLeases.remove(songKey, artifactLeaseId)
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        scheduleCompletedTaskRemoval(songKey, expectedAttemptId = expectedAttemptId)
        if (refreshCatalog) {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
    }

    private suspend fun completeCoreDownloadAndEnqueueEnrichment(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        existingMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        artifactLeaseId: String?,
        expectedAttemptId: Long?,
        refreshCatalog: Boolean
    ) {
        val songKey = song.stableKey()
        if (!ManagedDownloadStorage.hasReadableContent(context, storedAudio)) {
            updateTaskStatus(songKey, DownloadStatus.FAILED, expectedAttemptId = expectedAttemptId)
            markDownloadArtifactRetryable(
                context = context,
                song = song,
                leaseId = artifactLeaseId,
                errorCode = "AUDIO_CORE_NOT_READABLE"
            )
            return
        }

        val coreMetadataReady = existingMetadata?.artifactState ==
            ManagedDownloadArtifactState.CORE_COMMITTED.name
        if (!coreMetadataReady) {
            val written = persistDownloadedMetadata(
                context = context,
                audio = storedAudio,
                song = song,
                sidecarReferences = null,
                downloadFinalized = false,
                resolveExistingSidecars = false,
                artifactStateOverride = ManagedDownloadArtifactState.CORE_COMMITTED.name
            )
            if (!written) {
                NPLogger.w(TAG, "core metadata 写入失败，保留音频等待恢复: ${song.name}")
            } else {
                runCatching {
                    ManagedDownloadStorage.deletePendingAudioMetadata(
                        context = context,
                        audioName = storedAudio.name
                    )
                }.onFailure { error ->
                    NPLogger.w(
                        TAG,
                        "core metadata 已写入但 pending 清理失败，保留可恢复残留: " +
                            "audio=${storedAudio.name}, error=${error.message}"
                    )
                }
            }
        }

        runCatching {
            managedDownloadArtifactCoordinator.markCoreCommitted(
                context = context,
                song = song,
                storedAudio = storedAudio,
                expectedLeaseId = artifactLeaseId
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入 core committed artifact 状态失败: ${error.message}")
        }
        publishCompletedDownloadOptimistically(
            context = context,
            song = song,
            storedAudio = storedAudio,
            sidecarReferences = null
        )
        updateTaskStatus(songKey, DownloadStatus.COMPLETED, expectedAttemptId = expectedAttemptId)
        managedDownloadArtifactLeases.remove(songKey, artifactLeaseId)
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        scheduleCompletedTaskRemoval(songKey, expectedAttemptId = expectedAttemptId)
        if (refreshCatalog) {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }

        val operationId = existingMetadata?.operationId ?: UUID.randomUUID().toString()
        assetEnrichmentCoordinator.enqueue(
            operationId = operationId,
            block = {
            enrichCoreCommittedDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                operationId = operationId
            )
            },
            onTimeout = { error ->
                withContext(NonCancellable) {
                    NPLogger.w(
                        TAG,
                        "下载资产补齐超时，保留 core audio: song=${song.name}, " +
                            "operationId=$operationId, timeout=${error.javaClass.simpleName}"
                    )
                    runCatching {
                        persistDownloadedMetadata(
                            context = context,
                            audio = storedAudio,
                            song = song,
                            sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences(),
                            downloadFinalized = false,
                            resolveExistingSidecars = false
                        )
                    }
                    runCatching {
                        managedDownloadArtifactCoordinator.markDegradedComplete(
                            context = context,
                            song = song,
                            errorCode = "ASSET_ENRICHMENT_TIMEOUT"
                        )
                    }
                }
            }
        )
    }

    private suspend fun enrichCoreCommittedDownload(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        operationId: String
    ) {
        var sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences()
        try {
            managedDownloadArtifactCoordinator.markAssetsEnriching(context, song)
            sidecarReferences = AudioDownloadManager.downloadSidecarsForCompletedAudio(
                context = context,
                song = song,
                storedAudio = storedAudio
            )
            if (isDownloadMetadataPostProcessingEnabled(context)) {
                check(
                    runDownloadedAudioMetadataPostProcessing(
                        context = context,
                        audio = storedAudio,
                        song = song,
                        sidecarReferences = sidecarReferences
                    )
                ) { "embedded metadata post-processing failed" }
            }
            check(
                persistDownloadedMetadata(
                    context = context,
                    audio = storedAudio,
                    song = song,
                    sidecarReferences = sidecarReferences,
                    downloadFinalized = true,
                    resolveExistingSidecars = false
                )
            ) { "final metadata persist failed" }
            managedDownloadArtifactCoordinator.markFinalized(
                context = context,
                song = song,
                storedAudio = storedAudio,
                expectedLeaseId = null
            )
            publishCompletedDownloadOptimistically(
                context = context,
                song = song,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences
            )
            NPLogger.d(TAG, "下载资产补齐完成: song=${song.name}, operationId=$operationId")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "下载资产补齐失败，保留 core audio: song=${song.name}, " +
                    "operationId=$operationId, error=${error.message}"
            )
            runCatching {
                persistDownloadedMetadata(
                    context = context,
                    audio = storedAudio,
                    song = song,
                    sidecarReferences = sidecarReferences.retainCreatedOnly(),
                    downloadFinalized = false,
                    resolveExistingSidecars = false
                )
            }
            runCatching {
                managedDownloadArtifactCoordinator.markDegradedComplete(
                    context = context,
                    song = song,
                    errorCode = "ASSET_ENRICHMENT_FAILED"
                )
            }
        } finally {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
    }

    private data class CompletedDownloadMetadataFinalizationResult(
        val finalized: Boolean,
        val storedAudio: ManagedDownloadStorage.StoredEntry? = null,
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
        var finalizedAudio = storedAudio
        return try {
            finalizedAudio = ManagedDownloadStorage.refreshStoredEntry(
                context = appContext,
                reference = storedAudio.reference
            ) ?: return CompletedDownloadMetadataFinalizationResult(
                finalized = false,
                sidecarReferences = sidecarReferences.retainCreatedOnly()
            )
            if (!ManagedDownloadStorage.hasReadableContent(appContext, finalizedAudio)) {
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
                    storedAudio = finalizedAudio
                )
            )
            val coverAccessible = sidecarReferences.coverReference?.let { reference ->
                ManagedDownloadStorage.isReferenceAccessible(appContext, reference)
            } == true
            if (!shouldFinalizeDownloadedSidecars(
                    hasNetworkCoverCandidate = AudioDownloadManager
                        .buildCoverDownloadCandidateUrls(song)
                        .isNotEmpty(),
                    coverReference = sidecarReferences.coverReference,
                    coverAccessible = coverAccessible
                )
            ) {
                NPLogger.w(TAG, "下载封面侧载未生成，保持未完成状态: ${song.name}")
                return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
            }
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
            if (postProcessingEnabled) {
                val tagWritten = runDownloadedAudioMetadataPostProcessing(
                    context = appContext,
                    audio = finalizedAudio,
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
                finalizedAudio = ManagedDownloadStorage.refreshStoredEntry(
                    context = appContext,
                    reference = finalizedAudio.reference
                ) ?: return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
                if (!ManagedDownloadStorage.hasReadableContent(appContext, finalizedAudio)) {
                    NPLogger.w(TAG, "标签写入后音频校验失败，保持未完成状态: ${song.name}")
                    return CompletedDownloadMetadataFinalizationResult(
                        finalized = false,
                        sidecarReferences = sidecarReferences.retainCreatedOnly()
                    )
            }

            persistDownloadedMetadata(
                context = appContext,
                audio = finalizedAudio,
                song = song,
                sidecarReferences = sidecarReferences,
                downloadFinalized = false,
                resolveExistingSidecars = false
            )
                .also { metadataWritten ->
                    if (!metadataWritten) {
                        NPLogger.w(TAG, "下载 metadata 写入失败，保持未完成状态: ${song.name}")
                    }
                }
                .takeIf { it }
                ?: return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
            val artifactsVerified = !isSongCancelled(songKey) && verifyFinalizedDownloadedAudio(
                context = appContext,
                audio = finalizedAudio,
                song = song,
                sidecarReferences = sidecarReferences
            )
            if (!artifactsVerified) {
                return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    storedAudio = finalizedAudio,
                    sidecarReferences = sidecarReferences
                )
            }
            val finalizedMetadataWritten = persistDownloadedMetadata(
                context = appContext,
                audio = finalizedAudio,
                song = song,
                sidecarReferences = sidecarReferences,
                downloadFinalized = true,
                resolveExistingSidecars = false
            )
            if (!finalizedMetadataWritten) {
                NPLogger.w(TAG, "下载 finalized metadata 写入失败，保持未完成状态: ${song.name}")
                return CompletedDownloadMetadataFinalizationResult(
                    finalized = false,
                    storedAudio = finalizedAudio,
                    sidecarReferences = sidecarReferences
                )
            }
            CompletedDownloadMetadataFinalizationResult(
                finalized = true,
                storedAudio = finalizedAudio,
                sidecarReferences = sidecarReferences
            )
        } catch (error: CancellationException) {
            if (isSongCancelled(songKey)) {
                runCatching {
                    rollbackCancelledDownload(
                        context = appContext,
                        song = song,
                        storedAudio = finalizedAudio,
                        sidecarReferences = sidecarReferences.retainCreatedOnly()
                    )
                }.onFailure { rollbackError ->
                    NPLogger.e(
                        TAG,
                        "下载收尾取消后的回滚失败: ${song.name}, ${rollbackError.message}",
                        rollbackError
                    )
                }
            } else {
                cleanupOrphanedCompletedSidecars(
                    context = appContext,
                    song = song,
                    sidecarReferences = sidecarReferences.retainCreatedOnly()
                )
            }
            throw error
        } catch (error: Throwable) {
            NPLogger.w(TAG, "下载元信息收尾失败，保持未完成状态: ${song.name}, ${error.message}")
            CompletedDownloadMetadataFinalizationResult(
                finalized = false,
                sidecarReferences = sidecarReferences.retainCreatedOnly()
            )
        }
    }

    private suspend fun verifyFinalizedDownloadedAudio(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ): Boolean {
        val metadata = readDownloadedMetadata(context, audio) ?: return false
        val references = coroutineScope {
            val cover = async {
                ManagedDownloadStorage.hasReadableReference(context, metadata.coverPath)
            }
            val originalLyric = async {
                hasReadableDownloadedLyric(context, metadata.lyricPath)
            }
            val translatedLyric = async {
                hasReadableDownloadedLyric(context, metadata.translatedLyricPath)
            }
            val romanizedLyric = async {
                hasReadableDownloadedLyric(context, metadata.romanizedLyricPath)
            }
            DownloadedArtifactReferenceState(
                audioReadable = ManagedDownloadStorage.hasReadableContent(context, audio),
                coverReadable = cover.await(),
                originalLyricReadable = originalLyric.await(),
                translatedLyricReadable = translatedLyric.await(),
                romanizedLyricReadable = romanizedLyric.await()
            )
        }
        val prepared = sidecarReferences?.prepared == true
        val expectCover = if (prepared) {
            !metadata.coverPath.isNullOrBlank() ||
                !sidecarReferences.coverReference.isNullOrBlank()
        } else {
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song).isNotEmpty() ||
                !metadata.coverPath.isNullOrBlank() ||
                !sidecarReferences?.coverReference.isNullOrBlank() ||
                !metadata.coverUrl.isNullOrBlank() ||
                !metadata.customCoverUrl.isNullOrBlank() ||
                !metadata.originalCoverUrl.isNullOrBlank()
        }
        val expectOriginalLyric = if (prepared) {
            !metadata.lyricPath.isNullOrBlank() ||
                !sidecarReferences.lyricReference.isNullOrBlank()
        } else {
            sidecarReferences?.expectedLyric == true ||
                !metadata.lyricPath.isNullOrBlank() ||
                !sidecarReferences?.lyricReference.isNullOrBlank() ||
                !metadata.matchedLyric.isNullOrBlank() ||
                !metadata.originalLyric.isNullOrBlank()
        }
        val expectTranslatedLyric = if (prepared) {
            !metadata.translatedLyricPath.isNullOrBlank() ||
                !sidecarReferences.translatedLyricReference.isNullOrBlank()
        } else {
            sidecarReferences?.expectedTranslatedLyric == true ||
                !metadata.translatedLyricPath.isNullOrBlank() ||
                !sidecarReferences?.translatedLyricReference.isNullOrBlank() ||
                !metadata.matchedTranslatedLyric.isNullOrBlank() ||
                !metadata.originalTranslatedLyric.isNullOrBlank()
        }
        val expectRomanizedLyric = if (prepared) {
            !metadata.romanizedLyricPath.isNullOrBlank() ||
                !sidecarReferences.romanizedLyricReference.isNullOrBlank()
        } else {
            sidecarReferences?.expectedRomanizedLyric == true ||
                !metadata.romanizedLyricPath.isNullOrBlank() ||
                !sidecarReferences?.romanizedLyricReference.isNullOrBlank() ||
                !metadata.matchedRomanizedLyric.isNullOrBlank() ||
                !metadata.originalRomanizedLyric.isNullOrBlank()
        }
        val result = verifyDownloadedArtifactIntegrity(
            song = song,
            metadata = metadata,
            references = references,
            expectCover = expectCover,
            expectOriginalLyric = expectOriginalLyric,
            expectTranslatedLyric = expectTranslatedLyric,
            expectRomanizedLyric = expectRomanizedLyric,
            // final 标记要在产物校验通过后写入，这一步只校验实际文件和引用
            requireFinalizedMetadata = false
        )
        if (!result.isValid) {
            NPLogger.w(
                TAG,
                "下载产物完整性校验失败: song=${song.name}, issues=${result.issues}"
            )
        }
        return result.isValid
    }

    private suspend fun hasReadableDownloadedLyric(
        context: Context,
        reference: String?
    ): Boolean {
        if (!ManagedDownloadStorage.hasReadableReference(context, reference)) {
            return false
        }
        return ManagedDownloadStorage.readText(context, reference!!)
            ?.isNotBlank() == true
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
            sidecarReferences?.translatedLyricReference,
            sidecarReferences?.romanizedLyricReference
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
        runNonCancellableDownloadRollback {
            val references = listOfNotNull(
                sidecarReferences?.coverReference,
                sidecarReferences?.lyricReference,
                sidecarReferences?.translatedLyricReference,
                sidecarReferences?.romanizedLyricReference
            )
            if (references.isEmpty()) {
                return@runNonCancellableDownloadRollback
            }
            runCatching {
                ManagedDownloadStorage.deleteReferences(context.applicationContext, references)
            }.onFailure { error ->
                NPLogger.e(TAG, "清理孤立下载关联文件失败: ${song.name}, ${error.message}", error)
            }
        }
    }

    private suspend fun runDownloadedAudioMetadataPostProcessing(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?
    ): Boolean {
        val songKey = song.stableKey()
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
            val hasRemainingAttempts =
                attempt < METADATA_POST_PROCESSING_MAX_ATTEMPTS - 1 && !isSongCancelled(songKey)
            when (tagPostProcessingAction(writeResult.getOrNull(), hasRemainingAttempts)) {
                TagPostProcessingAction.FINALIZE_TAGGED -> return true
                TagPostProcessingAction.FINALIZE_UNTAGGED -> {
                    val reason = writeResult.exceptionOrNull()?.message
                        ?: writeResult.getOrNull()?.name
                    NPLogger.w(TAG, "容器不支持标签写入，按无内嵌标签完成: ${audio.name} - $reason")
                    return true
                }
                TagPostProcessingAction.RETRY -> {
                    val lastError = writeResult.exceptionOrNull()
                        ?: IllegalStateException("TagLib 未确认标签写入成功")
                    NPLogger.w(
                        TAG,
                        "元信息后处理失败，准备重试(第${attempt + 1}次): ${audio.name} - ${lastError.message}"
                    )
                    delay(METADATA_POST_PROCESSING_RETRY_DELAY_MS * (attempt + 1))
                }
                TagPostProcessingAction.PRESERVE_UNFINALIZED -> {
                    val reason = writeResult.exceptionOrNull()?.message
                        ?: writeResult.getOrNull()?.name
                    NPLogger.w(TAG, "标签写入持续失败，保留音频等待收尾重试: ${audio.name} - $reason")
                    return false
                }
            }
        }

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
        runCatching {
            managedDownloadArtifactCoordinator.markCancelled(
                context = context,
                song = song,
                expectedLeaseId = managedDownloadArtifactLeases[songKey]
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载 artifact 取消状态失败: ${error.message}")
        }
        managedDownloadArtifactLeases.remove(songKey)
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
        song: SongItem,
        keepCancellationMarker: Boolean = false,
        scheduleRecovery: Boolean = true
    ) {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
        if (!keepCancellationMarker) {
            ManagedDownloadStorage.removeCancelledDownloadKeys(appContext, setOf(songKey))
        }
        if (scheduleRecovery) {
            scheduleCancelledArtifactRecovery(appContext, listOf(song))
        }
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
            ?: ManagedDownloadStorage.findDownloadedAudioByCandidateBaseNames(
                context = context,
                candidateBaseNames = ManagedDownloadStorage.buildCandidateBaseNames(song)
            )
            ?: return false
        val metadata = readDownloadedMetadata(context, audio)
        if (metadata == null) {
            NPLogger.w(TAG, "发现取消下载留下无 metadata 音频，按精确文件名回滚: song=${song.name}, file=${audio.name}")
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = audio
            )
            return true
        }
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
            requestLocalScanLocked(appContext, forceRefresh)
        }
    }

    /**
     * 请求一次最终扫描并等待列表发布完成，迁移等需要强一致结果的流程使用此接口
     */
    suspend fun scanLocalFilesAwait(
        context: Context,
        forceRefresh: Boolean = false
    ) {
        val waiter = CompletableDeferred<Unit>()
        val appContext = context.applicationContext
        synchronized(this) {
            refreshWaiters += waiter
            requestLocalScanLocked(appContext, forceRefresh)
        }
        try {
            waiter.await()
        } finally {
            synchronized(this) {
                refreshWaiters.remove(waiter)
            }
        }
    }

    private fun requestLocalScanLocked(
        context: Context,
        forceRefresh: Boolean
    ) {
        if (refreshJob?.isActive == true) {
            pendingRefresh = true
            pendingForceRefresh = pendingForceRefresh || forceRefresh
            return
        }

        refreshJob = scope.launch {
            try {
                var nextForceRefresh = forceRefresh
                while (true) {
                    reloadDownloadedSongs(context, forceRefresh = nextForceRefresh)
                    nextForceRefresh = consumePendingRefreshRequest() ?: break
                }
            } finally {
                val waiters = synchronized(this@GlobalDownloadManager) {
                    refreshJob = null
                    refreshWaiters.toList()
                }
                waiters.forEach { waiter -> waiter.complete(Unit) }
            }
        }
    }

    fun refreshDownloadedSongsForManager(
        context: Context,
        forceRefresh: Boolean = false
    ) {
        val appContext = context.applicationContext
        scanLocalFiles(appContext, forceRefresh = forceRefresh)
        scheduleCatalogReconcile(appContext, forceRefresh = forceRefresh)
    }

    private fun consumePendingRefreshRequest(): Boolean? = synchronized(this) {
        val shouldRefreshAgain = pendingRefresh
        val shouldForceRefresh = pendingForceRefresh
        pendingRefresh = false
        pendingForceRefresh = false
        if (!shouldRefreshAgain) {
            return null
        }
        shouldForceRefresh
    }

    private suspend fun reloadDownloadedSongs(context: Context, forceRefresh: Boolean = false) {
        _isRefreshing.value = true
        try {
            val metadataRevisionAtScanStart = downloadedSongMetadataRevision.get()
            var snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = forceRefresh
            )
            snapshot = ManagedDownloadStorage.refreshDownloadSidecarSnapshot(
                context = context,
                snapshot = snapshot,
                forceRefresh = forceRefresh
            )
            val unfinalizedAudios = snapshot.audioEntries.filter { storedAudio ->
                isUnfinalizedDownloadedMetadata(snapshot.metadataByAudioName[storedAudio.name])
            }
            if (unfinalizedAudios.isNotEmpty()) {
                val finalizedArtifactCount = unfinalizedAudios.count { storedAudio ->
                    finalizeUnfinalizedDownloadedAudio(
                        context = context,
                        storedAudio = storedAudio,
                        snapshot = snapshot
                    )
                }
                if (shouldRebuildDownloadedLibrarySnapshot(finalizedArtifactCount)) {
                    snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                        context = context,
                        forceRefresh = true
                    )
                }
            }
            runCatching {
                ManagedDownloadStorage.persistFastIndex(context, snapshot)
            }.onFailure { error ->
                NPLogger.w(TAG, "写入 Managed SAF fast index 失败: ${error.message}")
            }
            val rebuildPlan = ManagedLibraryRebuilder.plan(snapshot)
            val songs = coroutineScope {
                rebuildPlan
                    .asSequence()
                    .filterNot { storedAudio ->
                        isUnfinalizedDownloadedMetadata(storedAudio.metadata)
                    }
                    .map { rebuildItem ->
                        async(downloadedSongBuildDispatcher) {
                            runCatching {
                                buildDownloadedSong(
                                    context = context,
                                    storedAudio = rebuildItem.audio,
                                    snapshot = snapshot,
                                    existingDownloadTime = rebuildItem.logicalTimeMs,
                                    loadLyricContents = false,
                                    resolveLyricFallbacks = false,
                                    allowSlowLocalInspection = false
                                )
                            }.onFailure { error ->
                                NPLogger.w(
                                    TAG,
                                    "解析下载文件失败: ${rebuildItem.audio.name} - ${error.message}"
                                )
                            }.getOrNull()
                        }
                    }
                    .toList()
                    .awaitAll()
                    .filterNotNull()
                    .sortedWith(downloadedSongNewestFirstComparator)
            }
            if (!snapshot.rootEntriesComplete) {
                NPLogger.w(
                    TAG,
                    "下载目录根项扫描不完整，保留既有 catalog 并等待重扫: " +
                        "observed=${songs.size}, forceRefresh=$forceRefresh"
                )
                if (!forceRefresh) {
                    scheduleCatalogReconcile(context, forceRefresh = true)
                }
                return
            }
            downloadedSongMetadataSyncMutex.withLock {
                if (metadataRevisionAtScanStart != downloadedSongMetadataRevision.get()) {
                    NPLogger.d(TAG, "跳过过期下载目录扫描结果: metadata changed during scan")
                    return@withLock
                }

                val existingSongs = _downloadedSongs.value
                // 本次扫描所针对的存储 root 标识, 用于与既有 catalog 所属 root 比对
                val scanRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
                // 可疑空结果保护 (#168 疑似根因) : SAF DocumentsProvider 可能瞬时返回空/失败游标
                // ManagedDownloadTreeChildQuery 列举失败时静默兜底为空列表, 导致一次瞬时失败被当成
                // 权威的"空目录"; 若直接持久化空目录, 下次启动会 restore 出空目录并把 catalogReady
                // 标为就绪, 从而跳过启动重扫, 使已下载歌曲彻底"看不见" (文件本身仍在磁盘)
                //
                // 判定条件 (isSuspiciousEmptyDownloadScan, 四者同时成立才视为可疑, 避免误伤正常清空) :
                // 1) 本次扫描结果为空; 2) 既有内存目录非空; 3) 存储 root 仍可解析
                // 4) 本次扫描 root 与既有 catalog 所属 root 一致 (scanMatchesCatalogRoot)
                // 反面: 应用内逐首删除走增量发布路径, 删空后既有目录已为空, 不触发本保护
                // 切换/重置下载目录后扫描的是新 root, 与旧 catalog root 不一致, genuine-empty 放行清空
                // 权衡: 同目录下外部文件管理器批量删空且 SAF 树仍可解析的极端场景下, 会保留旧条目直到下一次
                // 能成功列举或目录发生变化; 相比"瞬时失败即清空导致数据不可见", 保守保留是更安全的失败方向
                if (songs.isEmpty() && existingSongs.isNotEmpty()) {
                    val storageRootResolvable = ManagedDownloadStorage.isStorageRootResolvable(context)
                    val scanMatchesCatalogRoot = downloadedSongCatalogRootKey == scanRootKey
                    val confidence = if (snapshot.rootEntriesComplete) {
                        if (storageRootResolvable) {
                            ScanConfidence.COMPLETE
                        } else {
                            ScanConfidence.ROOT_UNAVAILABLE
                        }
                    } else {
                        ScanConfidence.PARTIAL
                    }
                    val knownReferences = existingSongs.mapNotNull { song ->
                        song.filePath.takeIf(String::isNotBlank)
                            ?: song.mediaUri?.takeIf(String::isNotBlank)
                    }.distinct()
                    val missingReferences = knownReferences.count { reference ->
                        ManagedDownloadReferenceLookup.canMarkMissing(
                            ManagedDownloadReferenceLookup.inspect(
                                context = context,
                                reference = reference
                            )
                        )
                    }
                    val emptyDecision = if (scanMatchesCatalogRoot) {
                        managedLibraryReconciler.observeEmpty(
                            observation = EmptyScanObservation(
                                rootKey = scanRootKey,
                                confidence = confidence,
                                isUncached = forceRefresh,
                                knownReferenceCount = knownReferences.size,
                                missingReferenceCount = missingReferences,
                                scanId = emptyScanSequence.incrementAndGet()
                            ),
                            existingCount = existingSongs.size
                        )
                    } else {
                        managedLibraryReconciler.reset()
                        EmptyScanDecision.CLEAR_CONFIRMED
                    }
                    if (
                        scanMatchesCatalogRoot &&
                            emptyDecision != EmptyScanDecision.CLEAR_CONFIRMED
                    ) {
                        NPLogger.w(
                            TAG,
                            "扫描结果为空但既有目录非空、存储根可解析且同 root，判定为可疑空结果，保留既有目录: " +
                                "existing=${existingSongs.size}, forceRefresh=$forceRefresh"
                        )
                        // 仅在本次使用了缓存 (非强制刷新) 时安排一次强制重扫尝试恢复
                        // 若本次已是强制刷新仍为空, 则不再重排, 避免瞬时失败演化成无限重扫循环
                        if (!forceRefresh) {
                            scheduleCatalogReconcile(context, forceRefresh = true)
                        }
                        return@withLock
                    }
                    if (emptyDecision == EmptyScanDecision.CLEAR_CONFIRMED) {
                        runCatching {
                            managedDownloadArtifactCoordinator.reconcileEmptyConfirmed(
                                context = context,
                                rootKey = scanRootKey
                            )
                        }.onFailure { error ->
                            NPLogger.w(TAG, "确认下载目录为空后清理 artifact 失败: ${error.message}")
                        }
                    }
                } else if (songs.isNotEmpty()) {
                    managedLibraryReconciler.reset()
                }
                runCatching {
                    managedDownloadArtifactCoordinator.reconcileCatalog(context, songs)
                }.onFailure { error ->
                    NPLogger.w(TAG, "扫描后回填下载 artifact 索引失败: ${error.message}")
                }
                if (existingSongs != songs) {
                    publishDownloadedSongs(context, songs, persistCatalog = true)
                    downloadedSongCatalogRootKey = scanRootKey
                } else if (!downloadedSongCatalogReady) {
                    downloadedSongCatalogIndex = buildDownloadedSongCatalogIndex(songs)
                    downloadedSongCatalogReady = true
                    downloadedSongCatalogRootKey = scanRootKey
                }
            }
        } catch (error: Exception) {
            NPLogger.e(TAG, "扫描已下载文件失败: ${error.message}", error)
        } finally {
            _isRefreshing.value = false
        }
    }

    private suspend fun finalizeUnfinalizedDownloadedAudio(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): Boolean {
        val metadata = snapshot.metadataByAudioName[storedAudio.name]
        if (isUnfinalizedDownloadStillActive(metadata)) {
            NPLogger.d(TAG, "跳过活跃下载的未最终确认文件: file=${storedAudio.name}")
            return false
        }
        if (!ManagedDownloadStorage.hasReadableContent(context, storedAudio)) {
            NPLogger.w(TAG, "扫描发现未最终确认且音频不可读，回滚后等待重试: file=${storedAudio.name}")
            val removal = runCatching {
                removeManagedDownloadArtifacts(
                    context = context,
                    songName = storedAudio.nameWithoutExtension,
                    storedAudio = storedAudio,
                    songId = metadata?.songId ?: 0L,
                    candidateBaseNames = candidateManagedDownloadBaseNames(storedAudio.nameWithoutExtension)
                )
            }.onFailure { error ->
                NPLogger.e(TAG, "扫描回滚未完成下载半成品失败: ${storedAudio.name}, ${error.message}", error)
            }.getOrNull()
            return removal?.deletedReferences?.isNotEmpty() == true
        }
        val song = buildSongForUnfinalizedRecovery(storedAudio, metadata) ?: run {
            NPLogger.w(TAG, "未最终确认音频缺少可恢复歌曲身份，跳过收尾: file=${storedAudio.name}")
            return false
        }
        NPLogger.d(TAG, "扫描发现完整但未最终确认音频，执行收尾重试: file=${storedAudio.name}")
        finalizeCompletedDownload(
            context = context,
            song = song,
            refreshCatalog = false,
            storedAudioHint = storedAudio,
            allowMissingTask = true
        )
        return true
    }

    private fun buildSongForUnfinalizedRecovery(
        audio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): SongItem? {
        val stableKey = metadata?.stableKey?.takeIf(String::isNotBlank) ?: return null
        return SongItem(
            id = metadata.songId ?: 0L,
            name = metadata.name ?: audio.nameWithoutExtension,
            artist = metadata.artist ?: "",
            album = metadata.identityAlbum ?: metadata.album ?: "local",
            albumId = 0L,
            durationMs = metadata.durationMs,
            coverUrl = metadata.coverUrl,
            mediaUri = metadata.mediaUri ?: audio.mediaUri,
            matchedLyric = metadata.matchedLyric,
            matchedTranslatedLyric = metadata.matchedTranslatedLyric,
            matchedRomanizedLyric = metadata.matchedRomanizedLyric,
            matchedSongId = metadata.matchedSongId,
            customCoverUrl = metadata.customCoverUrl,
            customName = metadata.customName,
            customArtist = metadata.customArtist,
            originalName = metadata.originalName,
            originalArtist = metadata.originalArtist,
            originalCoverUrl = metadata.originalCoverUrl,
            originalLyric = metadata.originalLyric,
            originalTranslatedLyric = metadata.originalTranslatedLyric,
            originalRomanizedLyric = metadata.originalRomanizedLyric,
            channelId = metadata.channelId,
            audioId = metadata.audioId,
            subAudioId = metadata.subAudioId,
            playlistContextId = metadata.playlistContextId,
            sourceStableKey = stableKey,
            localFileName = audio.name,
            localFilePath = audio.localFilePath,
            addedAt = metadata.createdAtMs ?: metadata.downloadTimeMs ?: audio.lastModifiedMs
        )
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
            syncDownloadedSongMetadataNow(song)
        }
    }

    internal suspend fun syncDownloadedSongMetadataNow(
        song: SongItem
    ): DownloadedSongMetadataSyncOutcome = withContext(Dispatchers.IO) {
        downloadedSongMetadataSyncMutex.withLock {
            val context = AppContainer.applicationContext
            val currentSongs = _downloadedSongs.value
            val catalogSong = findDownloadedSongCatalogMatch(song, currentSongs)
            val metadataSong = catalogSong
                ?.let { existing -> projectDownloadedSongMetadata(existing, song) }
                ?.toMetadataPersistenceSong(song)
                ?: song
            val storedAudio = resolveStoredAudio(context, song)
                ?: catalogSong?.let { downloaded ->
                    resolveStoredAudio(context, downloaded.filePath)
                        ?: resolveStoredAudio(context, downloaded.mediaUri)
                }
            if (storedAudio == null) {
                if (publishDownloadedSongMetadataFallback(
                        context = context,
                        currentSongs = currentSongs,
                        catalogSong = catalogSong,
                        storedAudio = null,
                        song = metadataSong,
                        reason = "storage entry unavailable"
                    )
                ) {
                    return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
                }
                NPLogger.w(
                    TAG,
                    "同步下载歌曲元数据失败: file unavailable, song=${song.name}"
                )
                return@withLock DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
            }

            if (!persistDownloadedMetadata(context, storedAudio, metadataSong)) {
                NPLogger.e(TAG, "同步下载歌曲元数据失败: metadata persist failed, file=${storedAudio.name}")
                if (publishDownloadedSongMetadataFallback(
                        context = context,
                        currentSongs = currentSongs,
                        catalogSong = catalogSong,
                        storedAudio = storedAudio,
                        song = metadataSong,
                        reason = "metadata sidecar persist deferred"
                    )
                ) {
                    return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
                }
                return@withLock DownloadedSongMetadataSyncOutcome.FAILED
            }

            downloadedSongMetadataRevision.incrementAndGet()
            val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                context = context,
                restorePersisted = false
            )?.takeIf { cachedSnapshot ->
                cachedSnapshot.audioEntriesByLookupKey.containsKey(storedAudio.reference) ||
                    cachedSnapshot.audioEntriesByLookupKey.containsKey(storedAudio.mediaUri)
            } ?: runCatching {
                ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                    context = context,
                    forceRefresh = true
                )
            }.getOrElse { error ->
                NPLogger.e(TAG, "同步下载歌曲元数据失败: refresh failed, file=${storedAudio.name}", error)
                if (publishDownloadedSongMetadataFallback(
                        context = context,
                        currentSongs = currentSongs,
                        catalogSong = catalogSong,
                        storedAudio = storedAudio,
                        song = metadataSong,
                        reason = "snapshot refresh failed"
                    )
                ) {
                    scheduleCatalogReconcile(context, forceRefresh = true)
                    return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
                }
                return@withLock DownloadedSongMetadataSyncOutcome.FAILED
            }
            val refreshedStoredAudio = snapshot.audioEntriesByLookupKey[storedAudio.reference]
                ?: snapshot.audioEntriesByLookupKey[storedAudio.mediaUri]
                ?: storedAudio
            val refreshedSong = runCatching {
                buildDownloadedSong(
                    context = context,
                    storedAudio = refreshedStoredAudio,
                    snapshot = snapshot,
                    existingDownloadTime = catalogSong?.downloadTime,
                    allowSlowLocalInspection = false
                )
            }.getOrElse { error ->
                NPLogger.e(TAG, "同步下载歌曲元数据失败: rebuild failed, file=${storedAudio.name}", error)
                if (publishDownloadedSongMetadataFallback(
                        context = context,
                        currentSongs = currentSongs,
                        catalogSong = catalogSong,
                        storedAudio = storedAudio,
                        song = metadataSong,
                        reason = "catalog rebuild failed"
                    )
                ) {
                    scheduleCatalogReconcile(context, forceRefresh = true)
                    return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
                }
                return@withLock DownloadedSongMetadataSyncOutcome.FAILED
            }
            if (!hasExpectedDownloadedSongMetadata(refreshedSong, metadataSong)) {
                NPLogger.w(TAG, "下载目录快照未反映最新标签，使用已验证结果: file=${storedAudio.name}")
                publishDownloadedSongMetadataFallback(
                    context = context,
                    currentSongs = currentSongs,
                    catalogSong = catalogSong,
                    storedAudio = storedAudio,
                    song = metadataSong,
                    reason = "catalog metadata stale"
                )
                scheduleCatalogReconcile(context, forceRefresh = true)
                return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
            }
            val refreshedSongs = upsertDownloadedSongCatalog(currentSongs, refreshedSong)
            publishDownloadedSongs(context, refreshedSongs, persistCatalog = true)
            downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
            NPLogger.d(
                TAG,
                "已同步下载歌曲元数据并发布目录: file=${storedAudio.name}, " +
                    "customCover=${refreshedSong.customCoverUrl != null}"
            )
            DownloadedSongMetadataSyncOutcome.SUCCESS
        }
    }

    private suspend fun publishDownloadedSongMetadataFallback(
        context: Context,
        currentSongs: List<DownloadedSong>,
        catalogSong: DownloadedSong?,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        song: SongItem,
        reason: String
    ): Boolean {
        val projectedSong = catalogSong
            ?.let { existing -> projectDownloadedSongMetadata(existing, song) }
            ?: storedAudio?.let { audio -> buildOptimisticDownloadedSong(song, audio) }
            ?: return false
        val refreshedSongs = upsertDownloadedSongCatalog(currentSongs, projectedSong)
        publishDownloadedSongs(context, refreshedSongs, persistCatalog = true)
        downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
        NPLogger.w(
            TAG,
            "下载元数据目录使用已验证标签更新: file=${projectedSong.filePath}, reason=$reason"
        )
        return true
    }

    private fun hasExpectedDownloadedSongMetadata(
        downloadedSong: DownloadedSong,
        song: SongItem
    ): Boolean {
        return downloadedSong.name == song.name &&
            downloadedSong.artist == song.artist &&
            downloadedSong.album == song.album &&
            downloadedSong.coverUrl == song.coverUrl &&
            downloadedSong.customCoverUrl == song.customCoverUrl &&
            downloadedSong.customName == song.customName &&
            downloadedSong.customArtist == song.customArtist &&
            downloadedSong.originalCoverUrl == song.originalCoverUrl &&
            (
                song.sourceStableKey.isNullOrBlank() ||
                    downloadedSong.remoteSourceStableKeyOrNull() == song.sourceStableKey
            )
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
        resolveExistingSidecars: Boolean = true,
        artifactStateOverride: String? = null
    ): Boolean = downloadedAudioMetadataStore.persist(
        context = context,
        audio = audio,
        song = song,
        sidecarReferences = sidecarReferences,
        downloadFinalized = downloadFinalized,
        resolveExistingSidecars = resolveExistingSidecars,
        artifactStateOverride = artifactStateOverride
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

    private suspend fun markDownloadArtifactFinalized(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        leaseId: String?
    ) {
        runCatching {
            managedDownloadArtifactCoordinator.markFinalized(
                context = context,
                song = song,
                storedAudio = storedAudio,
                expectedLeaseId = leaseId
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载 artifact 完成状态失败: ${error.message}")
        }
    }

    private suspend fun markDownloadArtifactRetryable(
        context: Context,
        song: SongItem,
        leaseId: String?,
        errorCode: String
    ) {
        runCatching {
            managedDownloadArtifactCoordinator.markRetryable(
                context = context,
                song = song,
                expectedLeaseId = leaseId,
                errorCode = errorCode
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载 artifact 重试状态失败: ${error.message}")
        }
    }

    private suspend fun markDownloadArtifactRepairRequired(
        context: Context,
        song: SongItem,
        leaseId: String?,
        errorCode: String
    ) {
        runCatching {
            managedDownloadArtifactCoordinator.markRepairRequired(
                context = context,
                song = song,
                expectedLeaseId = leaseId,
                errorCode = errorCode
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载 artifact 修复状态失败: ${error.message}")
        }
    }

    private suspend fun markDownloadArtifactMissingConfirmed(
        context: Context,
        song: SongItem,
        errorCode: String?
    ) {
        runCatching {
            managedDownloadArtifactCoordinator.markMissingConfirmed(
                context = context,
                song = song,
                errorCode = errorCode
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载 artifact 缺失确认状态失败: ${error.message}")
        }
    }

    private suspend fun releaseDownloadArtifactClaim(
        context: Context,
        song: SongItem
    ) {
        val songKey = song.stableKey()
        val leaseId = managedDownloadArtifactLeases.remove(songKey) ?: return
        runCatching {
            managedDownloadArtifactCoordinator.markCancelled(
                context = context,
                song = song,
                expectedLeaseId = leaseId
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "释放下载 artifact lease 失败: ${error.message}")
        }
    }

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
        val directOrphanAudio = if (resolvedStoredAudio == null) {
            ManagedDownloadStorage.findDownloadedAudioByCandidateBaseNames(
                context = appContext,
                candidateBaseNames = candidateBaseNames
            )?.takeUnless { audio ->
                readDownloadedMetadata(appContext, audio)?.downloadFinalized == true
            }
        } else {
            null
        }
        val audioForRemoval = resolvedStoredAudio ?: directOrphanAudio
        val explicitReferences = listOfNotNull(
            audioForRemoval?.let(ManagedDownloadStorage::metadataReferenceForAudio),
            sidecarReferences?.coverReference,
            sidecarReferences?.lyricReference,
            sidecarReferences?.translatedLyricReference,
            sidecarReferences?.romanizedLyricReference
        )

        NPLogger.d(
            TAG,
            "回滚已取消下载: song=${song.name}, audio=${audioForRemoval?.reference}, baseNames=$candidateBaseNames, sidecars=$explicitReferences"
        )

        removeManagedDownloadArtifacts(
            context = appContext,
            songName = song.name,
            storedAudio = audioForRemoval,
            songId = song.id,
            candidateBaseNames = candidateBaseNames,
            explicitReferences = explicitReferences,
            useCachedSnapshotOnly = false
        )

        val currentSongs = _downloadedSongs.value
        val updatedSongs = currentSongs.filterNot { downloaded ->
            (audioForRemoval != null && downloaded.filePath == audioForRemoval.reference) ||
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
            deleteDownloadedSongsWithResult(appContext, targetSongs)
        }
    }

    suspend fun deleteDownloadedSongsWithResult(
        context: Context,
        songs: List<DownloadedSong>
    ): DownloadedSongDeleteResult {
        val appContext = context.applicationContext
        val targetSongs = songs.distinctBy(DownloadedSong::deletionIdentity)
        if (targetSongs.isEmpty()) {
            return DownloadedSongDeleteResult.empty()
        }

        return withContext(Dispatchers.IO) {
            downloadedSongDeleteMutex.withLock {
                deleteDownloadedSongsOnIo(appContext, targetSongs)
            }
        }
    }

    private suspend fun deleteDownloadedSongsOnIo(
        appContext: Context,
        targetSongs: List<DownloadedSong>
    ): DownloadedSongDeleteResult {
        val startedAtMs = System.currentTimeMillis()
        val previousSongs = _downloadedSongs.value
        val deletesEntireCatalog = isCompleteDownloadedSongSelection(
            selectedSongs = targetSongs,
            availableSongs = previousSongs
        )
        if (deletesEntireCatalog) {
            NPLogger.d(
                TAG,
                "全选删除下载目录，先取消活动下载并保留清理标记直到收敛: songs=${targetSongs.size}"
            )
            cancelAllDownloadTasksAndWait()
        }
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
            NPLogger.d(
                TAG,
                "批量删除下载开始: songs=${targetSongs.size}, references=${requestedReferences.size}, optimisticRemoved=${previousSongs.size - optimisticSongs.size}"
            )
            val deletedReferences = if (requestedReferences.isNotEmpty()) {
                ManagedDownloadStorage.deleteReferences(appContext, requestedReferences)
            } else {
                emptySet()
            }
            val deletionResult = resolveDownloadedSongDeleteResult(
                deletePlans = deletePlans,
                deletedReferences = deletedReferences
            )
            val currentSongs = _downloadedSongs.value
            val finalSongs = mergeDownloadedSongsAfterDelete(
                currentSongs = currentSongs,
                previousSongs = previousSongs,
                deletedSongs = deletionResult.deletedSongs,
                restoredSongs = deletionResult.failedSongs
            )
            if (finalSongs != currentSongs) {
                publishDownloadedSongs(appContext, finalSongs, persistCatalog = false)
            }
            if (finalSongs != previousSongs) {
                scheduleDownloadedSongsCatalogPersist(appContext, finalSongs)
            }

            val remainingReferences = requestedReferences - deletedReferences
            deletionResult.failedSongs.forEach { song ->
                NPLogger.w(TAG, "删除下载音频不完整: ${song.name}")
            }
            deletionResult.deletedSongs.forEach { song ->
                NPLogger.d(TAG, "删除下载文件完成: ${song.name}")
            }
            scheduleCatalogReconcile(
                appContext,
                forceRefresh = deletionResult.failedSongs.isNotEmpty() || remainingReferences.isNotEmpty()
            )
            NPLogger.d(
                TAG,
                "批量删除下载结束: songs=${targetSongs.size}, requested=${requestedReferences.size}, deleted=${deletedReferences.size}, failed=${deletionResult.failedSongs.size}, costMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return deletionResult
        } catch (error: CancellationException) {
            val currentSongs = _downloadedSongs.value
            val restoredSongs = mergeDownloadedSongsAfterDelete(
                currentSongs = currentSongs,
                previousSongs = previousSongs,
                deletedSongs = emptyList(),
                restoredSongs = targetSongs
            )
            if (restoredSongs != currentSongs) {
                publishDownloadedSongs(appContext, restoredSongs, persistCatalog = false)
            }
            throw error
        } catch (error: Exception) {
            val currentSongs = _downloadedSongs.value
            val restoredSongs = mergeDownloadedSongsAfterDelete(
                currentSongs = currentSongs,
                previousSongs = previousSongs,
                deletedSongs = emptyList(),
                restoredSongs = targetSongs
            )
            if (restoredSongs != currentSongs) {
                publishDownloadedSongs(appContext, restoredSongs, persistCatalog = false)
            }
            scheduleCatalogReconcile(appContext, forceRefresh = true)
            NPLogger.e(TAG, "删除下载文件失败: ${error.message}", error)
            return DownloadedSongDeleteResult(
                deletedSongs = emptyList(),
                failedSongs = targetSongs
            )
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

                val cachedSnapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                    context = appContext,
                    restorePersisted = false
                )
                val storedAudio = cachedSnapshot?.audioEntriesByLookupKey?.get(playbackReference)
                val playbackUri = storedAudio?.playbackUri
                    ?: ManagedDownloadStorage.toPlayableUri(playbackReference)
                    ?: playbackReference
                val quickDownloadedSong = if (cachedSnapshot != null && storedAudio != null) {
                    runCatching {
                        buildDownloadedSong(
                            context = appContext,
                            storedAudio = storedAudio,
                            snapshot = cachedSnapshot,
                            existingDownloadTime = song.downloadTime,
                            loadLyricContents = false,
                            resolveLyricFallbacks = false,
                            allowSlowLocalInspection = false
                        )
                    }.getOrNull()
                } else {
                    null
                }
                val quickSongBase = (quickDownloadedSong ?: song).toPlaybackSongItem(
                    playbackUri = playbackUri,
                    localFileName = storedAudio?.name
                        ?: ManagedDownloadStorage.normalizeManagedAudioFileName(song.filePath),
                    localFilePath = storedAudio?.localFilePath
                        ?: song.filePath.takeIf { it.startsWith("/") },
                    resolvedDurationMs = song.durationMs
                )
                val quickSong = hydrateDownloadedSidecarLyricsFast(
                    context = appContext,
                    song = quickSongBase
                )
                withContext<Unit>(Dispatchers.Main.immediate) {
                    PlayerManager.playPlaylist(listOf(quickSong), 0)
                }

                scheduleDownloadedPlaybackReferenceValidation(appContext, song, playbackReference)
                val hydratedStoredAudio = storedAudio
                    ?: resolveStoredAudioFromCache(appContext, playbackReference)
                    ?: resolveStoredAudio(appContext, playbackReference)
                val refreshedSong = hydratedStoredAudio?.let {
                    val hydrationSnapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(appContext)
                        ?.let { snapshot ->
                            ManagedDownloadStorage.refreshDownloadSidecarSnapshot(
                                context = appContext,
                                snapshot = snapshot
                            )
                        }
                        ?: ManagedDownloadStorage.buildDownloadLibrarySnapshot(appContext)
                    buildDownloadedSong(
                        context = appContext,
                        storedAudio = it,
                        snapshot = hydrationSnapshot,
                        existingDownloadTime = song.downloadTime,
                        loadLyricContents = false,
                        resolveLyricFallbacks = false,
                        allowSlowLocalInspection = false
                    )
                } ?: song
                val hydratedDurationMs = refreshedSong.durationMs
                    .takeIf { it > 0L }
                    ?: quickSong.durationMs.takeIf { it > 0L }
                    ?: resolveAudioDuration(appContext, playbackUri)
                val hydratedSong = hydrateDownloadedSidecarLyricsFast(
                    context = appContext,
                    song = refreshedSong.toPlaybackSongItem(
                        playbackUri = playbackUri,
                        localFileName = hydratedStoredAudio?.name
                            ?: ManagedDownloadStorage.normalizeManagedAudioFileName(
                                refreshedSong.filePath
                            ),
                        localFilePath = hydratedStoredAudio?.localFilePath
                            ?: refreshedSong.filePath.takeIf { it.startsWith("/") },
                        resolvedDurationMs = hydratedDurationMs
                    ),
                    refreshIfMissing = true
                )
                if (hydratedSong != quickSong) {
                    delay(resolveDownloadedPlaybackHydrationDelayMs(quickSong, hydratedSong))
                    if (!shouldApplyDownloadedPlaybackHydration(
                            currentSong = PlayerManager.currentSongFlow.value,
                            quickSong = quickSong
                        )
                    ) {
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

    private fun hydrateDownloadedSidecarLyricsFast(
        context: Context,
        song: SongItem,
        refreshIfMissing: Boolean = false
    ): SongItem {
        val lyrics = runCatching {
            if (refreshIfMissing) {
                AudioDownloadManager.getLyricsBundle(context, song)
            } else {
                AudioDownloadManager.getLyricsBundleFast(
                    context = context,
                    song = song,
                    allowColdSafProbe = false
                )
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "下载播放首屏歌词读取失败: ${error.message}")
        }.getOrNull() ?: return song
        return song.copy(
            matchedLyric = if (lyrics.hasOriginalSidecar) {
                lyrics.lyric
            } else {
                song.matchedLyric
            },
            matchedTranslatedLyric = if (lyrics.hasTranslatedSidecar) {
                lyrics.translatedLyric
            } else {
                song.matchedTranslatedLyric
            },
            matchedRomanizedLyric = if (lyrics.hasRomanizedSidecar) {
                lyrics.romanizedLyric
            } else {
                song.matchedRomanizedLyric
            }
        )
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
        return findDownloadedSongCached(song) != null
    }

    fun findDownloadedSongCached(song: SongItem): DownloadedSong? {
        return downloadedSongCatalogIndex.find(song)
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

    private suspend fun restorePersistedDownloadedSongs(context: Context): Boolean {
        val restoredSongs = downloadedSongCatalogStore.restore(context)
        if (restoredSongs == null) {
            restoreFastIndexPreview(context)
            return false
        }
        publishDownloadedSongs(context, restoredSongs, persistCatalog = false)
        runCatching {
            managedDownloadArtifactCoordinator.reconcileCatalog(context, restoredSongs)
        }.onFailure { error ->
            NPLogger.w(TAG, "回填下载 artifact 索引失败: ${error.message}")
        }
        // 记录 restore 出的 catalog 所属 root (当前配置目录) , 使随后同目录的启动瞬时空扫描仍受 #D4 保护
        // 若目录在离线期间被切换, 则新 root 与随后扫描一致而与真实来源不符 -- 属既有边界, 不在本次修复范围
        downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
        return true
    }

    private suspend fun restoreFastIndexPreview(context: Context): Boolean {
        val snapshot = ManagedDownloadStorage.restoreFastIndexPreview(context) ?: return false
        val rebuildPlan = ManagedLibraryRebuilder.plan(snapshot)
        val songs = coroutineScope {
            rebuildPlan.map { rebuildItem ->
                async(downloadedSongBuildDispatcher) {
                    runCatching {
                        buildDownloadedSong(
                            context = context,
                            storedAudio = rebuildItem.audio,
                            snapshot = snapshot,
                            existingDownloadTime = rebuildItem.logicalTimeMs,
                            allowSlowLocalInspection = false
                        )
                    }.onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "解析 Managed fast index 预览失败: " +
                                "${rebuildItem.audio.name} - ${error.message}"
                        )
                    }.getOrNull()
                }
            }.awaitAll()
                .filterNotNull()
                .sortedWith(downloadedSongNewestFirstComparator)
        }
        if (songs.isEmpty()) return false
        publishDownloadedSongs(context, songs, persistCatalog = false)
        downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
        NPLogger.d(TAG, "从 Managed SAF fast index 恢复预览: songs=${songs.size}")
        return true
    }

    private fun persistDownloadedSongsCatalog(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        val persisted = downloadedSongCatalogStore.persist(context, songs)
        if (!persisted) {
            scheduleCatalogReconcile(context, forceRefresh = true)
        }
    }

    fun startDownload(context: Context, song: SongItem) {
        startDownload(context, song, skipTrafficRiskPrompt = false)
    }

    fun startDownload(
        context: Context,
        song: SongItem,
        operationId: String
    ) {
        startDownload(
            context = context,
            song = song,
            skipTrafficRiskPrompt = false,
            operationId = operationId
        )
    }

    private fun startDownload(
        context: Context,
        song: SongItem,
        skipTrafficRiskPrompt: Boolean,
        cleanupBeforeStart: Boolean = true,
        deferForNetworkPolicy: Boolean = false,
        preparedAttemptId: Long? = null,
        operationId: String? = null
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
                deferForNetworkPolicy = deferForNetworkPolicy,
                preparedAttemptId = preparedAttemptId,
                operationId = operationId
            )
        }
    }

    private fun startDownloadConfirmed(
        context: Context,
        song: SongItem,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        deferForNetworkPolicy: Boolean,
        preparedAttemptId: Long? = null,
        operationId: String? = null
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val songKey = song.stableKey()
            if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                NPLogger.d(TAG, "忽略过期单曲下载启动: song=${song.name}, generation=$requestGeneration")
                return@launch
            }
            AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
            val artifactClaim = runCatching {
                managedDownloadArtifactCoordinator.claim(
                    context = appContext,
                    song = song,
                    reconcileStorage = false
                )
            }.getOrElse { error ->
                NPLogger.w(TAG, "下载 artifact claim 失败，继续现有恢复路径: ${error.message}")
                null
            }
            when (artifactClaim) {
                is ManagedDownloadArtifactClaim.AlreadyDownloaded -> {
                    forgetPendingDownloadQueueEntriesIfCurrent(
                        appContext,
                        setOf(songKey),
                        requestGeneration
                    )
                    NPLogger.d(TAG, "跳过已完成下载 artifact: song=${song.name}, songKey=$songKey")
                    return@launch
                }

                is ManagedDownloadArtifactClaim.InFlight -> {
                    NPLogger.d(TAG, "跳过重复下载请求: song=${song.name}, songKey=$songKey")
                    return@launch
                }

                is ManagedDownloadArtifactClaim.Acquired -> {
                    artifactClaim.artifact.leaseId?.let { leaseId ->
                        managedDownloadArtifactLeases[songKey] = leaseId
                    }
                }

                is ManagedDownloadArtifactClaim.RepairRequired,
                null -> Unit
            }
            if (preparedAttemptId != null &&
                !taskStore.isDownloadAttemptCurrent(songKey, preparedAttemptId)
            ) {
                NPLogger.d(TAG, "忽略已被替换的预创建下载任务: song=${song.name}, attempt=$preparedAttemptId")
                return@launch
            }
            val attemptId = preparedAttemptId ?: taskStore.prepareDownloadTask(song) ?: run {
                if (artifactClaim is ManagedDownloadArtifactClaim.Acquired) {
                    markDownloadArtifactRetryable(
                        context = appContext,
                        song = song,
                        leaseId = artifactClaim.artifact.leaseId,
                        errorCode = "TASK_ALREADY_ACTIVE"
                    )
                    managedDownloadArtifactLeases.remove(songKey)
                }
                return@launch
            }
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
                    val preserveArtifactForRepair =
                        artifactClaim is ManagedDownloadArtifactClaim.RepairRequired
                    if (cleanupBeforeStart && !preserveArtifactForRepair) {
                        cleanupDownloadArtifactsBeforeFreshStart(appContext, song)
                    }
                    if (shouldSkipDownload(appContext, song)) {
                        releaseDownloadArtifactClaim(appContext, song)
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                        return@withSongExecutionLock
                    }

                    val fastCachedSong = findFastCachedDownloadedSong(appContext, song)
                    if (fastCachedSong != null) {
                        val repairedSong = repairDownloadedCoverIfMissing(
                            context = appContext,
                            song = song,
                            downloadedSong = fastCachedSong
                        )
                        if (repairedSong != fastCachedSong) {
                            publishOptimisticDownloadedSongs(appContext, listOf(repairedSong))
                        }
                        resolveStoredAudio(appContext, song)?.let { storedAudio ->
                            markDownloadArtifactFinalized(
                                context = appContext,
                                song = song,
                                storedAudio = storedAudio,
                                leaseId = managedDownloadArtifactLeases.remove(songKey)
                            )
                        }
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
                    val needsFinalization = existingAudio?.let { audio ->
                        isUnfinalizedDownloadedMetadata(readDownloadedMetadata(appContext, audio))
                    } == true
                    val existingAudioAction = resolvePreExistingDownloadedAudioAction(
                        hasExistingAudio = existingAudio != null,
                        needsFinalization = needsFinalization
                    )
                    if (existingAudio != null) {
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "单曲下载命中已存在文件时已过期: song=${song.name}, generation=$requestGeneration")
                            removeDownloadTask(songKey, expectedAttemptId = attemptId)
                            return@withSongExecutionLock
                        }
                        if (existingAudioAction == PreExistingDownloadedAudioAction.FINALIZE_EXISTING) {
                            finalizeCompletedDownload(
                                context = appContext,
                                song = song,
                                refreshCatalog = true,
                                expectedAttemptId = attemptId,
                                storedAudioHint = existingAudio
                            )
                            return@withSongExecutionLock
                        }
                        if (existingAudioAction == PreExistingDownloadedAudioAction.DIRECT_SETTLE) {
                            val optimisticSong = repairDownloadedCoverIfMissing(
                                context = appContext,
                                song = song,
                                downloadedSong = buildOptimisticDownloadedSong(
                                    song = song,
                                    storedAudio = existingAudio
                                )
                            )
                            publishOptimisticDownloadedSongs(
                                appContext,
                                listOf(optimisticSong)
                            )
                            markDownloadArtifactFinalized(
                                context = appContext,
                                song = song,
                                storedAudio = existingAudio,
                                leaseId = managedDownloadArtifactLeases.remove(songKey)
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
                            attemptId = attemptId,
                            operationId = operationId
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
                    runCatching {
                        managedDownloadArtifactCoordinator.markCancelled(
                            context = appContext,
                            song = song,
                            expectedLeaseId = managedDownloadArtifactLeases[songKey]
                        )
                    }.onFailure { error ->
                        NPLogger.w(TAG, "写入单曲 artifact 取消状态失败: ${error.message}")
                    }
                    managedDownloadArtifactLeases.remove(songKey)
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
                markDownloadArtifactRetryable(
                    context = appContext,
                    song = song,
                    leaseId = managedDownloadArtifactLeases[songKey],
                    errorCode = "DOWNLOAD_FAILED"
                )
                managedDownloadArtifactLeases.remove(songKey)
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
        deferForNetworkPolicy: Boolean = false,
        materializeQueuedTasks: Boolean = false
    ) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        val preMaterializedAttemptIds = if (materializeQueuedTasks) {
            taskStore.prepareDownloadTasks(
                songs = songs.distinctBy { it.stableKey() },
                status = DownloadStatus.QUEUED,
                replaceExistingActiveTasks = replaceExistingActiveTasks
            )
        } else {
            emptyMap()
        }
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
                deferForNetworkPolicy = deferForNetworkPolicy,
                preMaterializedAttemptIds = preMaterializedAttemptIds
            )
        }
    }

    private fun startBatchDownloadConfirmed(
        context: Context,
        songs: List<SongItem>,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        replaceExistingActiveTasks: Boolean,
        deferForNetworkPolicy: Boolean,
        preMaterializedAttemptIds: Map<String, Long> = emptyMap()
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
                val artifactClaims = currentRequestedSongs.associate { song ->
                    song.stableKey() to runCatching {
                        managedDownloadArtifactCoordinator.claim(
                            context = appContext,
                            song = song,
                            reconcileStorage = false
                        )
                    }.getOrElse { error ->
                        NPLogger.w(
                            TAG,
                            "批量下载 artifact claim 失败，继续现有恢复路径: ${error.message}"
                        )
                        null
                    }
                }
                val songsRequiringTask = currentRequestedSongs.filter { song ->
                    when (artifactClaims[song.stableKey()]) {
                        is ManagedDownloadArtifactClaim.AlreadyDownloaded,
                        is ManagedDownloadArtifactClaim.InFlight -> false

                        else -> true
                    }
                }
                val prePreparedForSongs = preMaterializedAttemptIds
                    .filterKeys { songKey -> songsRequiringTask.any { it.stableKey() == songKey } }
                val preparedAttemptIds = linkedMapOf<String, Long>().apply {
                    putAll(prePreparedForSongs)
                    putAll(
                        taskStore.prepareDownloadTasks(
                            songs = songsRequiringTask.filterNot { song ->
                                song.stableKey() in prePreparedForSongs
                            },
                            status = DownloadStatus.QUEUED,
                            replaceExistingActiveTasks = replaceExistingActiveTasks
                        )
                    )
                }
                val downloadLibrarySnapshot = buildBatchDownloadLibrarySnapshot(appContext)
                val settledAttemptIds = linkedMapOf<String, Long>()
                val optimisticDownloadedSongs = mutableListOf<DownloadedSong>()
                for (song in currentRequestedSongs) {
                    val songKey = song.stableKey()
                    if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                        settledSongKeys += songKey
                        continue
                    }
                    AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
                    when (val artifactClaim = artifactClaims[songKey]) {
                        is ManagedDownloadArtifactClaim.AlreadyDownloaded -> {
                            settledSongKeys += songKey
                            NPLogger.d(TAG, "批量下载跳过已完成 artifact: song=${song.name}")
                            continue
                        }

                        is ManagedDownloadArtifactClaim.InFlight -> {
                            NPLogger.d(TAG, "批量下载跳过进行中 artifact: song=${song.name}")
                            continue
                        }

                        is ManagedDownloadArtifactClaim.Acquired -> {
                            artifactClaim.artifact.leaseId?.let { leaseId ->
                                managedDownloadArtifactLeases[songKey] = leaseId
                            }
                        }

                        is ManagedDownloadArtifactClaim.RepairRequired,
                        null -> Unit
                    }
                    val attemptId = preparedAttemptIds[songKey]
                    if (attemptId == null) {
                        releaseDownloadArtifactClaim(appContext, song)
                        continue
                    }
                    if (shouldSkipDownload(appContext, song)) {
                        skippedLocalSongs++
                        releaseDownloadArtifactClaim(appContext, song)
                        settledSongKeys += songKey
                        settledAttemptIds[songKey] = attemptId
                        NPLogger.d(TAG, "批量下载跳过本地歌曲: song=${song.name}, songKey=$songKey")
                        continue
                    }
                    val fastCachedSong = findFastCachedDownloadedSong(appContext, song)
                    if (fastCachedSong != null) {
                        val repairedSong = repairDownloadedCoverIfMissing(
                            context = appContext,
                            song = song,
                            downloadedSong = fastCachedSong
                        )
                        settledSongKeys += songKey
                        settledAttemptIds[songKey] = attemptId
                        if (repairedSong != fastCachedSong) {
                            optimisticDownloadedSongs += repairedSong
                        }
                        resolveStoredAudio(appContext, song)?.let { storedAudio ->
                            markDownloadArtifactFinalized(
                                context = appContext,
                                song = song,
                                storedAudio = storedAudio,
                                leaseId = managedDownloadArtifactLeases.remove(songKey)
                            )
                        }
                        NPLogger.d(
                            TAG,
                            "批量下载命中下载目录缓存并直接完成: song=${song.name}, songKey=$songKey"
                        )
                        continue
                    }
                    val existingAudio = findExistingDownloadedAudio(
                        context = appContext,
                        song = song,
                        snapshot = downloadLibrarySnapshot,
                        allowStorageLookup = false
                    )
                    val needsFinalization = existingAudio?.let { audio ->
                        isUnfinalizedDownloadedMetadata(readDownloadedMetadata(appContext, audio))
                    } == true
                    val existingAudioAction = resolvePreExistingDownloadedAudioAction(
                        hasExistingAudio = existingAudio != null,
                        needsFinalization = needsFinalization
                    )
                    if (existingAudio != null) {
                        if (existingAudioAction == PreExistingDownloadedAudioAction.FINALIZE_EXISTING) {
                            finalizeCompletedDownload(
                                context = appContext,
                                song = song,
                                refreshCatalog = false,
                                expectedAttemptId = attemptId,
                                storedAudioHint = existingAudio
                            )
                            settledSongKeys += songKey
                            settledAttemptIds[songKey] = attemptId
                            continue
                        }
                        if (existingAudioAction == PreExistingDownloadedAudioAction.DIRECT_SETTLE) {
                            settledSongKeys += songKey
                            settledAttemptIds[songKey] = attemptId
                            optimisticDownloadedSongs += repairDownloadedCoverIfMissing(
                                context = appContext,
                                song = song,
                                downloadedSong = buildOptimisticDownloadedSong(
                                    song = song,
                                    storedAudio = existingAudio
                                )
                            )
                            markDownloadArtifactFinalized(
                                context = appContext,
                                song = song,
                                storedAudio = existingAudio,
                                leaseId = managedDownloadArtifactLeases.remove(songKey)
                            )
                            NPLogger.d(
                                TAG,
                                "批量下载命中已存在音频并直接完成: song=${song.name}, songKey=$songKey, file=${existingAudio.name}"
                            )
                            continue
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
                        if (
                            cleanupBeforeStart &&
                                artifactClaims[songKey] !is ManagedDownloadArtifactClaim.RepairRequired
                        ) {
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
                        markDownloadArtifactRetryable(
                            context = appContext,
                            song = failedSong,
                            leaseId = managedDownloadArtifactLeases.remove(songKey),
                            errorCode = "DOWNLOAD_FAILED"
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
                        runCatching {
                            managedDownloadArtifactCoordinator.markCancelled(
                                context = appContext,
                                song = cancelledSong,
                                expectedLeaseId = managedDownloadArtifactLeases.remove(songKey)
                            )
                        }.onFailure { error ->
                            NPLogger.w(TAG, "写入批量 artifact 取消状态失败: ${error.message}")
                        }
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
                    runCatching {
                        managedDownloadArtifactCoordinator.markCancelled(
                            context = appContext,
                            song = request.song,
                            expectedLeaseId = managedDownloadArtifactLeases.remove(songKey)
                        )
                    }.onFailure { error ->
                        NPLogger.w(TAG, "写入批量取消 artifact 状态失败: ${error.message}")
                    }
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
                    markDownloadArtifactRetryable(
                        context = appContext,
                        song = request.song,
                        leaseId = managedDownloadArtifactLeases.remove(request.song.stableKey()),
                        errorCode = "BATCH_DOWNLOAD_FAILED"
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
            restorePersisted = false
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

    private suspend fun repairDownloadedCoverIfMissing(
        context: Context,
        song: SongItem,
        downloadedSong: DownloadedSong
    ): DownloadedSong {
        val existingCoverAccessible = downloadedSong.coverPath
            ?.takeIf { reference ->
                ManagedDownloadStorage.isReferenceAccessible(context, reference)
            } != null
        val hasNetworkCoverCandidate = AudioDownloadManager
            .buildCoverDownloadCandidateUrls(song)
            .isNotEmpty()
        if (!shouldRepairDownloadedCover(existingCoverAccessible, hasNetworkCoverCandidate)) {
            return downloadedSong
        }

        val storedAudio = resolveStoredAudio(context, song)
            ?: resolveStoredAudio(context, downloadedSong.filePath)
        if (storedAudio == null) {
            NPLogger.w(TAG, "缺少封面但无法定位已下载音频，跳过侧载修复: ${song.name}")
            return downloadedSong
        }

        val sidecarReferences = try {
            AudioDownloadManager.repairCoverForCompletedAudio(
                context = context,
                song = song,
                storedAudio = storedAudio
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "已下载歌曲封面侧载修复失败: ${song.name} - ${error.message}")
            return downloadedSong
        }
        val repairedCover = sidecarReferences.coverReference
            ?.takeIf { reference ->
                ManagedDownloadStorage.isReferenceAccessible(context, reference)
            }
        if (repairedCover == null) {
            NPLogger.w(TAG, "封面侧载修复未生成可访问文件: ${song.name}")
            return downloadedSong
        }

        val metadataPatched = downloadedAudioMetadataStore.persistCoverReference(
            context = context,
            audio = storedAudio,
            coverReference = repairedCover
        )
        val existingMetadata = readDownloadedMetadata(context, storedAudio)
        val metadataWritten = metadataPatched || persistDownloadedMetadata(
            context = context,
            audio = storedAudio,
            song = song,
            sidecarReferences = sidecarReferences,
            downloadFinalized = existingMetadata?.downloadFinalized == true,
            resolveExistingSidecars = true
        )
        if (!metadataWritten) {
            NPLogger.w(TAG, "封面侧载已生成但元数据回写失败: ${song.name}")
            scheduleCatalogReconcile(context, forceRefresh = true)
        }
        return downloadedSong.copy(coverPath = repairedCover)
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
            if (ManagedDownloadStorage.hasReadableContent(context, audio)) {
                NPLogger.w(
                    TAG,
                    "发现未最终确认但音频已完整，保留文件并进入收尾重试: " +
                        "song=${song.name}, file=${audio.name}"
                )
                return audio
            }
            NPLogger.w(TAG, "发现未最终确认且音频不可读，回滚后重新下载: song=${song.name}, file=${audio.name}")
            rollbackCancelledDownload(context = context, song = song, storedAudio = audio)
            return null
        }

        val hasReadableAudio = audio.sizeBytes > 0L ||
            ManagedDownloadStorage.hasReadableContent(context, audio)
        if (metadata != null && isMetadataOwnedBySong(metadata, song) && hasReadableAudio) {
            NPLogger.d(
                TAG,
                "已下载文件 metadata 快速校验通过: song=${song.name}, file=${audio.name}, size=${audio.sizeBytes}"
            )
            return audio
        }

        val localDetails = inspectDownloadedAudioDetails(context, audio)
        if (metadata != null && localDetails == null) {
            if (hasReadableAudio) {
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
            // 无法读取音频标签 (常见于 SAF content:// URI)
            // 通过文件名和文件大小判断是否为有效下载
            if (hasReadableAudio && matchesExpectedDownloadFileName(song, audio)) {
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
        if (ManagedDownloadStorage.hasReadableContent(context, audio)) {
            NPLogger.d(
                TAG,
                "重试前保留已完整但未最终确认音频，后续只执行收尾: " +
                    "song=${song.name}, file=${audio.name}"
            )
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
        requestAllDownloadTaskCancellation()
    }

    private fun requestAllDownloadTaskCancellation(): Job? {
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
            return null
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
        return scope.launch {
            cancelDownloadTasksInBackground(
                context = appContext,
                tasks = activeTasks,
                batchJobs = batchJobs,
                cancelledSongKeysSnapshot = activeSongKeys,
                cancellationGenerations = cancellationGenerations
            )
        }
    }

    private suspend fun cancelAllDownloadTasksAndWait() {
        requestAllDownloadTaskCancellation()?.join()
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
        batchJobs: Collection<Job>,
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
        batchJobs.joinAll()
        val focusedCleanupKeys = mutableSetOf<String>()
        tasks.forEach { task ->
            val songKey = task.song.stableKey()
            if (songKey !in activeDownloadTaskKeys) return@forEach
            withSongExecutionLock(songKey) {
                if (!isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])) {
                    NPLogger.d(TAG, "跳过过期批量取消清理: song=${task.song.name}, songKey=$songKey")
                    return@withSongExecutionLock
                }
                cleanupCancelledDownloadArtifacts(
                    context = appContext,
                    song = task.song,
                    keepCancellationMarker = true,
                    scheduleRecovery = false
                )
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
        val recoverySongs = tasks
            .filter { task ->
                isCancellationCleanupStillCurrent(
                    songKey = task.song.stableKey(),
                    cancellationGeneration = cancellationGenerations[task.song.stableKey()]
                )
            }
            .map { task -> task.song }
            .distinctBy { song -> song.stableKey() }
        if (recoverySongs.isNotEmpty()) {
            recoverCancelledArtifacts(appContext, recoverySongs)
        }
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
        LocalMediaSupport.invalidateSongAssetCaches(song)
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
        val remoteSource = song.remoteSourceIdentityOrNull()
            ?: song.takeUnless { LocalSongSupport.isLocalSong(it, null) }?.identity()
        val rawSourceChannel = song.channelId
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
        val sourceChannel = rawSourceChannel ?: remoteSource?.album
        val sourceAudioId = song.audioId
            ?.trim()
            ?.takeIf { rawSourceChannel != null && it.isNotBlank() }
            ?: remoteSource
                ?.takeIf { sourceChannel.equals("netease", ignoreCase = true) }
                ?.id
                ?.toString()
        val sourceSubAudioId = song.subAudioId
            ?.trim()
            ?.takeIf { rawSourceChannel != null && it.isNotBlank() }
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
            matchedRomanizedLyric = song.matchedRomanizedLyric,
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
            originalRomanizedLyric = song.originalRomanizedLyric,
            mediaUri = storedAudio.mediaUri,
            durationMs = song.durationMs.coerceAtLeast(0L),
            stableKey = remoteSource?.stableKey() ?: song.stableKey(),
            sourceIdentityAlbum = remoteSource?.album,
            sourceMediaUri = remoteSource?.mediaUri,
            sourceChannelId = sourceChannel,
            sourceAudioId = sourceAudioId,
            sourceSubAudioId = sourceSubAudioId,
            sourcePlaylistContextId = song.playlistContextId?.takeIf { remoteSource != null }
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
