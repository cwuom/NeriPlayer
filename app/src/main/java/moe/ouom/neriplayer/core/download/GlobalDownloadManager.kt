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
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.catalog.downloadedSongNewestFirstComparator
import moe.ouom.neriplayer.core.download.catalog.projectDownloadedSongMetadata
import moe.ouom.neriplayer.core.download.catalog.toMetadataPersistenceSong
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactCoordinator
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.artifact.ownedLeaseIdOrNull
import moe.ouom.neriplayer.core.download.enrichment.AssetEnrichmentCoordinator
import moe.ouom.neriplayer.core.download.metadata.RestorableMetadataClearPolicy
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionSchedule
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.DownloadClearFenceReleaseResult
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.policy.TagPostProcessingAction
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanDecision
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanObservation
import moe.ouom.neriplayer.core.download.reconcile.ManagedLibraryReconciler
import moe.ouom.neriplayer.core.download.reconcile.ScanConfidence
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuilder
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
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
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

internal data class RecoveredDownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long
)

internal fun resolveRecoveredDownloadProgress(
    workingFileBytes: Long,
    checkpointTotalBytes: Long?
): RecoveredDownloadProgress? {
    val durableBytes = workingFileBytes.coerceAtLeast(0L)
    val totalBytes = checkpointTotalBytes?.takeIf { total ->
        durableBytes > 0L && total >= durableBytes
    } ?: return null
    return RecoveredDownloadProgress(
        bytesRead = durableBytes,
        totalBytes = totalBytes
    )
}

internal suspend fun awaitBatchDownloadJobsSettled(
    jobs: Collection<Job>,
    timeoutMs: Long
): Boolean {
    if (jobs.isEmpty()) {
        return true
    }
    return withTimeoutOrNull(timeoutMs) {
        jobs.joinAll()
        true
    } == true
}

internal suspend fun resolveRestorableCoverReference(
    metadata: ManagedDownloadRestorableMetadata,
    baseline: Boolean,
    fingerprintReference: suspend (String) -> ManagedDownloadCoverAssetStore.MaterializedCover?,
    findManagedReferenceByName: suspend (String) -> String?,
    findContentAddressedReference: suspend (String) -> String?
): String? {
    val directReference = if (baseline) {
        metadata.baseline.coverReference
    } else {
        metadata.overrides.coverReference
    }?.trim()?.takeIf(String::isNotBlank)
    val assetHash = if (baseline) {
        metadata.baselineCoverAssetHash
    } else {
        metadata.currentCoverAssetHash
    }?.trim()?.takeIf { hash -> hash.matches(Regex("[0-9a-fA-F]{64}")) }
    val assetFileName = if (baseline) {
        metadata.baselineCoverAssetFileName
    } else {
        metadata.currentCoverAssetFileName
    }?.trim()?.takeIf { name ->
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name
    }
    if (assetHash == null) return directReference

    directReference?.let { reference ->
        val fingerprint = recoverRestorableCoverValue {
            fingerprintReference(reference)
        }
        if (fingerprint?.assetHash.equals(assetHash, ignoreCase = true)) {
            return fingerprint?.reference ?: reference
        }
    }
    assetFileName?.let { fileName ->
        recoverRestorableCoverValue {
            findManagedReferenceByName(fileName)
        }?.let { reference ->
            val fingerprint = recoverRestorableCoverValue {
                fingerprintReference(reference)
            }
            if (fingerprint?.assetHash.equals(assetHash, ignoreCase = true)) {
                return fingerprint?.reference ?: reference
            }
        }
    }
    recoverRestorableCoverValue {
        findContentAddressedReference(assetHash)
    }?.let { reference ->
        val fingerprint = recoverRestorableCoverValue {
            fingerprintReference(reference)
        }
        if (fingerprint?.assetHash.equals(assetHash, ignoreCase = true)) {
            return fingerprint?.reference ?: reference
        }
    }
    return directReference?.takeUnless(::isLocalRestorableCoverReference)
}

private suspend fun <T> recoverRestorableCoverValue(block: suspend () -> T?): T? {
    return try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

private fun isLocalRestorableCoverReference(reference: String): Boolean {
    return reference.startsWith("/") ||
        reference.startsWith("file:", ignoreCase = true) ||
        reference.startsWith("content:", ignoreCase = true)
}

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
    private const val DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS = 3
    private const val DOWNLOAD_CANCEL_JOURNAL_RETRY_DELAY_MS = 150L
    private const val DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS = 1_000L
    private const val DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS = 25L
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
    private const val BATCH_DOWNLOAD_EARLY_HANDOFF_LIMIT = 6
    internal const val PLAYBACK_METADATA_HYDRATION_DELAY_MS = 1_500L
    internal const val LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadedSongBuildDispatcher =
        Dispatchers.IO.limitedParallelism(DOWNLOADED_SONG_BUILD_PARALLELISM)

    private data class DownloadClearSettlement(
        val activeSongKeys: Set<String>,
        val activeOperationIds: Set<String>,
        val batchJobsSettled: Boolean,
        val residualWorkingSongKeys: Set<String> = emptySet()
    ) {
        val isSettled: Boolean
            get() = activeSongKeys.isEmpty() &&
                activeOperationIds.isEmpty() &&
                batchJobsSettled &&
                residualWorkingSongKeys.isEmpty()
    }

    private data class ActiveProgressCheckpointBinding(
        val operationId: String,
        val attemptId: Long
    )

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
    private val batchDownloadPresentationIdGenerator = AtomicLong(0L)
    private val _batchDownloadPresentation =
        MutableStateFlow<BatchDownloadPresentationState?>(null)
    val downloadTasks: StateFlow<List<DownloadTask>> = taskStore.downloadTasks
    val downloadTaskSummary: StateFlow<DownloadTaskSummary> = taskStore.downloadTaskSummary
    val activeDownloadOperationsFlow: StateFlow<Boolean> = taskStore.activeDownloadOperationsFlow
    internal val batchDownloadProgressFlow: StateFlow<BatchDownloadOverallProgress?> = combine(
        _batchDownloadPresentation,
        downloadTasks
    ) { presentation: BatchDownloadPresentationState?, tasks: List<DownloadTask> ->
        presentation?.let { state -> aggregateBatchDownloadProgress(state, tasks) }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )
    private val downloadClearVisibility = DownloadClearVisibility()
    val isClearingDownloadTasks: StateFlow<Boolean> = downloadClearVisibility.isClearing

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
    private val downloadedSongDeletionCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val downloadedSongMetadataRevision = AtomicLong(0L)
    private val emptyScanSequence = AtomicLong(0L)
    private var refreshJob: Job? = null
    private val refreshWaiters = mutableSetOf<CompletableDeferred<Unit>>()
    private var catalogPersistJob: Job? = null
    private var catalogReconcileJob: Job? = null
    private var pendingCatalogReconcileForceRefresh = false
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
    private val activeProgressCheckpointBindings =
        ConcurrentHashMap<String, ActiveProgressCheckpointBinding>()
    private val downloadAdmissionGate = DownloadAdmissionGate()
    private val pendingDownloadRecoveryStateLock = Any()

    @Volatile
    private var pendingDownloadRecoveryActive = false

    @Volatile
    private var mobileDataDownloadOverrideAllowed = false

    private fun isDownloadClearFenceActive(context: Context): Boolean {
        return PersistentDownloadClearFenceStore.isActive(context.applicationContext)
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        observeDownloadProgress()
        observeStorageStartupRecovery(appContext)
        scope.launch {
            if (PersistentDownloadClearFenceStore.isActive(appContext)) {
                NPLogger.w(TAG, "检测到未完成的下载清空，跳过启动恢复并继续收敛")
                DownloadExecutionHosts.cancelAllOwned(appContext)
                requestAllDownloadTaskCancellation()
                return@launch
            }
            val startupRecovery = ManagedDownloadStorage.consumeStartupRecoveryResult()
            val restoredCatalog = restorePersistedDownloadedSongs(appContext)
            DownloadExecutionOperationStore().pruneTerminalOperations(
                context = appContext,
                cutoffMs = System.currentTimeMillis() -
                    TERMINAL_OPERATION_RETENTION_MS,
                limit = TERMINAL_OPERATION_PRUNE_LIMIT
            )
            val processStoppedKeys = DownloadExecutionHosts.default
                .markUserRequestedProcessExitOperations(appContext)
            if (processStoppedKeys.isNotEmpty()) {
                NPLogger.i(
                    TAG,
                    "检测到系统用户停止进程，暂停未完成 UIDT 下载: " +
                        "count=${processStoppedKeys.size}"
                )
            }
            recoverPendingAudioWritesFromRoot(appContext)
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

    private const val TERMINAL_OPERATION_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val TERMINAL_OPERATION_PRUNE_LIMIT = 64

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
                val song = buildSongFromDurableMetadata(pendingAudio, metadata)
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
                    operationId = metadata.operationId,
                    expectedArtifactLeaseId = metadata.operationId
                        ?.let { operationId ->
                            DownloadExecutionRoomStore.read(context, operationId)?.artifactLeaseId
                        }
                        ?: managedDownloadArtifactCoordinator.currentLeaseId(context, song),
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
        if (isDownloadClearFenceActive(appContext)) {
            NPLogger.d(TAG, "跳过启动下载恢复: 清空栅栏仍在生效")
            return
        }
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
        if (isDownloadClearFenceActive(context)) {
            NPLogger.d(TAG, "跳过未完成下载恢复: 清空栅栏仍在生效, reason=$reason")
            return
        }
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
                    DownloadExecutionRoomStore.purgeAllCancelled(context)
                }
                return
            }

            forgetPendingDownloadQueueEntries(context, recoveryPlan.settledSongKeys)
            DownloadExecutionRoomStore.purgeCancelled(context, recoveryPlan.settledSongKeys)

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
                DownloadExecutionRoomStore.purgeCancelled(context, finalizedRecoveryKeys)
            }

            NPLogger.d(
                TAG,
                "检测到未完成下载，准备自动恢复: reason=$reason, " +
                    "count=${antiJoinedResumableSongs.size}, " +
                    "antiJoinedFinalized=${finalizedRecoveryKeys.size}, " +
                    "explicitResume=${recoveryPlan.explicitResumeKeys.size}, " +
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
                deferForNetworkPolicy = true,
                userInitiated = false
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
        val settledSongKeys: Set<String>,
        val explicitResumeKeys: Set<String>
    )

    private suspend fun resolvePendingDownloadRecoveryPlan(
        context: Context
    ): PendingDownloadRecoveryPlan {
        val pendingQueuedDownloads = ManagedDownloadStorage.listPendingQueuedDownloads(context)
        val pendingDownloads = ManagedDownloadStorage.listPendingResumableDownloads(context)
        val cancelledDownloadKeys = DownloadExecutionRoomStore.listByStates(
            context = context,
            states = listOf("CANCEL_REQUESTED", "CANCELLED")
        ).mapTo(linkedSetOf()) { entry -> entry.request.song.stableKey() }
        val externallyStoppedSongKeys = DownloadExecutionHosts.default
            .externallyStoppedSongKeys(context)
        val recoveryCandidates = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = pendingQueuedDownloads,
            resumableDownloads = pendingDownloads,
            cancelledKeys = cancelledDownloadKeys
        )
        val recoveryCandidateKeys = recoveryCandidates
            .mapTo(mutableSetOf()) { candidate -> candidate.song.stableKey() }
            .apply { addAll(externallyStoppedSongKeys) }
        val resumableSongs = mutableListOf<SongItem>()
        val settledSongKeys = mutableSetOf<String>()
        val explicitResumeKeys = mutableSetOf<String>()
        recoveryCandidates.forEach { candidate ->
            val song = candidate.song
            val songKey = song.stableKey()
            val operationId = candidate.operationId
                ?: DownloadExecutionHosts.default.operationIdForSong(context, songKey)
            val requiresExplicitResume = DownloadExecutionHosts.default
                .requiresExplicitResume(context, operationId)
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
                songKey in externallyStoppedSongKeys || requiresExplicitResume -> {
                    explicitResumeKeys += songKey
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
            settledSongKeys = settledSongKeys,
            explicitResumeKeys = explicitResumeKeys
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
                DownloadExecutionRoomStore.purgeAllCancelled(context)
            }
            return true
        }

        forgetPendingDownloadQueueEntries(context, recoveryPlan.settledSongKeys)
        DownloadExecutionRoomStore.purgeCancelled(context, recoveryPlan.settledSongKeys)

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

    private suspend fun deferQueuedDownloadStartForNetworkPolicyIfNeeded(
        context: Context,
        songs: List<SongItem>,
        attemptIdsBySongKey: Map<String, Long>,
        requestGeneration: Long,
        reason: String,
        deferForNetworkPolicy: Boolean
    ): Boolean {
        val networkType = context.currentTrafficNetworkType()
        if (
            !shouldDeferQueuedDownloadStartForNetwork(
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
            if (isDownloadClearFenceActive(appContext)) {
                return@launch
            }
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
        if (isDownloadClearFenceActive(appContext)) {
            return false
        }
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
            if (isDownloadClearFenceActive(appContext)) {
                return@launch
            }
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            AudioDownloadManager.progressEvents.collect { progress ->
                updateDownloadProgress(progress)
            }
        }
    }

    private suspend fun updateDownloadProgress(progress: AudioDownloadManager.DownloadProgress) {
        if (!taskStore.updateProgress(progress)) {
            return
        }
        val binding = activeProgressCheckpointBindings[progress.songKey] ?: return
        if (binding.attemptId != progress.attemptId) {
            return
        }
        runCatching {
            DownloadExecutionRoomStore.checkpointProgress(
                context = AppContainer.applicationContext,
                operationId = binding.operationId,
                stableKey = progress.songKey,
                attemptId = binding.attemptId,
                bytesWritten = progress.bytesRead,
                totalBytes = progress.totalBytes
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "写入下载进度检查点失败: operationId=${binding.operationId}, " +
                    "songKey=${progress.songKey}, error=${error.message}"
            )
        }
    }

    private suspend fun restoreTaskProgressCheckpoint(
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
        val pendingDownload = ManagedDownloadStorage.listPendingResumableDownloads(context)
            .firstOrNull { pending ->
                pending.song.stableKey() == songKey &&
                    pending.operationId == binding.operationId
            } ?: return
        val durableBytes = pendingDownload.workingFile
            .takeIf(File::isFile)
            ?.length()
            ?.coerceAtLeast(0L)
            ?: return
        val restoredProgress = resolveRecoveredDownloadProgress(
            workingFileBytes = durableBytes,
            checkpointTotalBytes = checkpoint.totalBytes
        ) ?: return
        taskStore.updateProgress(
            AudioDownloadManager.DownloadProgress(
                songKey = songKey,
                songId = song.id,
                fileName = song.name,
                bytesRead = restoredProgress.bytesRead,
                totalBytes = restoredProgress.totalBytes,
                speedBytesPerSec = 0L,
                attemptId = binding.attemptId
            )
        )
    }

    private suspend fun finalizeCompletedDownload(
        context: Context,
        song: SongItem,
        refreshCatalog: Boolean,
        expectedAttemptId: Long? = null,
        operationId: String? = null,
        expectedArtifactLeaseId: String? = null,
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
                sidecarReferences = sidecarReferences,
                operationId = operationId
            )
            return
        }
        val artifactLeaseId = expectedArtifactLeaseId
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
                    expectedAttemptId = expectedAttemptId,
                    operationId = operationId,
                    expectedArtifactLeaseId = artifactLeaseId
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
                expectedAttemptId = expectedAttemptId,
                operationId = operationId,
                expectedArtifactLeaseId = artifactLeaseId
            )
        ) {
            return
        }

        val existingMetadata = readDownloadedMetadata(
            context = context.applicationContext,
            audio = resolvedStoredAudio
        )
        // v15 metadata is read as input only; every completion now enters the
        // single core commit and enrichment pipeline
        completeCoreDownloadAndEnqueueEnrichment(
            context = context.applicationContext,
            song = song,
            storedAudio = resolvedStoredAudio,
            existingMetadata = existingMetadata,
            artifactLeaseId = artifactLeaseId,
            expectedAttemptId = expectedAttemptId,
            refreshCatalog = refreshCatalog,
            operationId = operationId
        )
    }

    private suspend fun completeCoreDownloadAndEnqueueEnrichment(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        existingMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        artifactLeaseId: String?,
        expectedAttemptId: Long?,
        refreshCatalog: Boolean,
        operationId: String?
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

        val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
        val operationState = normalizedOperationId?.let { id ->
            DownloadExecutionRoomStore.state(context, id)
        }
        if (operationState == "CANCEL_REQUESTED" || operationState == "CANCELLED") {
            NPLogger.d(
                TAG,
                "忽略已请求取消的 core commit，并只清理 operation 所有的半成品: " +
                    "song=${song.name}, operationId=$operationId"
            )
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                operationId = operationId
            )
            return
        }
        if (
            normalizedOperationId != null &&
                operationState != null &&
                operationState != "COMMITTING" &&
                !isDurableCoreOperationState(operationState) &&
                !DownloadExecutionRoomStore.markCommitting(context, normalizedOperationId)
        ) {
            NPLogger.w(
                TAG,
                "operation journal 未确认进入 COMMITTING，保留音频等待恢复: " +
                    "song=${song.name}, operationId=$normalizedOperationId"
            )
            return
        }

        val coreCommitResult = withContext(NonCancellable) {
            val coreMetadataReady = existingMetadata?.artifactState ==
                ManagedDownloadArtifactState.CORE_COMMITTED.name
            val coreMetadataWritten = if (!coreMetadataReady) {
                persistDownloadedMetadata(
                    context = context,
                    audio = storedAudio,
                    song = song,
                    sidecarReferences = null,
                    downloadFinalized = false,
                    resolveExistingSidecars = false,
                    artifactStateOverride = ManagedDownloadArtifactState.CORE_COMMITTED.name,
                    operationId = operationId
                ).also { written ->
                    if (!written) {
                        NPLogger.w(
                            TAG,
                            "core metadata 写入失败，保留音频等待恢复: ${song.name}"
                        )
                    }
                }
            } else {
                true
            }
            if (!shouldPublishCoreCommit(coreMetadataReady, coreMetadataWritten)) {
                return@withContext false
            }
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
            val journalCommitted = normalizedOperationId?.let { id ->
                DownloadExecutionRoomStore.markCoreCommitted(context, id)
            } ?: true
            if (!journalCommitted) {
                NPLogger.w(
                    TAG,
                    "core metadata 已写入但 operation journal 未确认 CORE_COMMITTED: " +
                        "song=${song.name}, operationId=$operationId"
                )
            }
            true
        }
        if (!coreCommitResult) {
            updateTaskStatus(songKey, DownloadStatus.FAILED, expectedAttemptId = expectedAttemptId)
            markDownloadArtifactRepairRequired(
                context = context,
                song = song,
                leaseId = artifactLeaseId,
                errorCode = "CORE_METADATA_WRITE_FAILED"
            )
            return
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
            sidecarReferences = null,
            state = "CORE_COMMITTED"
        )
        updateTaskStatus(songKey, DownloadStatus.COMPLETED, expectedAttemptId = expectedAttemptId)
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        scheduleCompletedTaskRemoval(songKey, expectedAttemptId = expectedAttemptId)
        if (refreshCatalog) {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }

        val enrichmentOperationId = operationId
            ?: existingMetadata?.operationId
            ?: UUID.randomUUID().toString()
        try {
            assetEnrichmentCoordinator.enqueueAndAwait(
                operationId = enrichmentOperationId,
                block = {
                    enrichCoreCommittedDownload(
                        context = context,
                        song = song,
                        storedAudio = storedAudio,
                        operationId = enrichmentOperationId,
                        artifactLeaseId = artifactLeaseId
                    )
                },
                onTimeout = { error ->
                    withContext(NonCancellable) {
                        NPLogger.w(
                            TAG,
                            "下载资产补齐超时，保留 core audio: song=${song.name}, " +
                                "operationId=$enrichmentOperationId, " +
                                "timeout=${error.javaClass.simpleName}"
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
                                expectedLeaseId = artifactLeaseId,
                                errorCode = "ASSET_ENRICHMENT_TIMEOUT"
                            )
                        }
                    }
                }
            )
        } finally {
            artifactLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases.remove(songKey, leaseId)
            }
        }
    }

    private suspend fun enrichCoreCommittedDownload(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        operationId: String,
        artifactLeaseId: String?
    ) {
        var sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences()
        try {
            managedDownloadArtifactCoordinator.markAssetsEnriching(
                context = context,
                song = song,
                expectedLeaseId = artifactLeaseId
            )
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
                expectedLeaseId = artifactLeaseId
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
                    expectedLeaseId = artifactLeaseId,
                    errorCode = "ASSET_ENRICHMENT_FAILED"
                )
            }
        } finally {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
    }

    private fun isDurableCoreOperationState(state: String?): Boolean {
        return state == "CORE_COMMITTED" ||
            state == "ASSETS_ENRICHING" ||
            state == "FINALIZED" ||
            state == "DEGRADED_COMPLETE" ||
            state == "COMPLETED"
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
        expectedAttemptId: Long? = null,
        operationId: String? = null,
        expectedArtifactLeaseId: String? = null
    ): Boolean {
        if (!isSongCancelled(songKey)) {
            return false
        }

        val currentState: String? = operationId?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { id -> DownloadExecutionRoomStore.state(context.applicationContext, id) }
        val durableCoreCommitted = isDurableCoreArtifactState(currentState) ||
            currentState == "COMMITTING"
        val storedMetadata = storedAudio?.let { audio ->
            readDownloadedMetadata(context.applicationContext, audio)
        }
        val preserveCommittedAudio = storedAudio != null &&
            (durableCoreCommitted || shouldPreserveAudioForCancellationRollback(
                    audioIsPending = storedAudio.isPendingAudioWrite,
                    metadataReadable = storedMetadata != null,
                    downloadFinalized = storedMetadata?.downloadFinalized,
                    artifactState = storedMetadata?.artifactState,
                    metadataOperationId = storedMetadata?.operationId,
                    operationId = operationId
                ))
        if (preserveCommittedAudio) {
            NPLogger.d(
                TAG,
                "core commit 后收到迟到取消，保留完整音频: " +
                    "song=${song.name}, operationState=$currentState, " +
                    "metadataState=${storedMetadata?.artifactState}"
            )
        } else {
            NPLogger.d(TAG, "下载最终入库阶段检测到取消，开始回滚: ${song.name}")
            runCatching {
                rollbackCancelledDownload(
                    context = context,
                    song = song,
                    storedAudio = storedAudio,
                    sidecarReferences = sidecarReferences,
                    operationId = operationId
                )
            }.onFailure { error ->
                NPLogger.e(TAG, "下载最终入库回滚失败: ${song.name}, ${error.message}", error)
            }
        }
        clearSongCancelled(songKey)
        if (preserveCommittedAudio) {
            runCatching {
                managedDownloadArtifactCoordinator.markDegradedComplete(
                    context = context,
                    song = song,
                    expectedLeaseId = expectedArtifactLeaseId,
                    errorCode = "CANCEL_AFTER_CORE_COMMIT"
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "写入 core audio 保留状态失败: ${error.message}")
            }
        } else {
            runCatching {
                managedDownloadArtifactCoordinator.markCancelled(
                    context = context,
                    song = song,
                    expectedLeaseId = expectedArtifactLeaseId
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "写入下载 artifact 取消状态失败: ${error.message}")
            }
        }
        expectedArtifactLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
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
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        operationId: String? = null
    ) {
        if (storedAudio == null && (sidecarReferences?.isEmpty != false)) {
            return
        }
        runCatching {
            rollbackCancelledDownload(
                context = context,
                song = song,
                storedAudio = storedAudio,
                sidecarReferences = sidecarReferences,
                operationId = operationId
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
        cleanupUnfinalizedDownloadForRetry(appContext, song)
    }

    private suspend fun cleanupCancelledDownloadArtifacts(
        context: Context,
        song: SongItem,
        keepCancellationOperation: Boolean = false
    ) {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
        if (!keepCancellationOperation) {
            DownloadExecutionRoomStore.purgeCancelled(appContext, setOf(songKey))
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
            runCatching {
                ManagedDownloadStorage.persistFastIndex(context, snapshot)
            }.onFailure { error ->
                NPLogger.w(TAG, "写入 Managed SAF fast index 失败: ${error.message}")
            }
            val rebuildPlan = ManagedLibraryRebuilder.plan(snapshot)
            val songs = coroutineScope {
                rebuildPlan
                    .asSequence()
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

    private fun buildSongFromDurableMetadata(
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

    internal fun syncDownloadedSongMetadata(
        song: SongItem,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy()
    ) {
        scope.launch {
            syncDownloadedSongMetadataNow(song, clearRestorableOverrides)
        }
    }

    /**
     * 从 Managed root 读取恢复基线，避免把当前 Room 或 filesDir 状态当作权威
     */
    internal suspend fun readManagedRestorableMetadata(
        context: Context,
        song: SongItem
    ): ManagedDownloadRestorableMetadata? = withContext(Dispatchers.IO) {
        val storedAudio = resolveStoredAudio(context, song)
            ?: ManagedDownloadStorage.findDownloadedAudio(context, song)
            ?: return@withContext null
        readDownloadedMetadata(context, storedAudio)?.restorableMetadata
    }

    internal suspend fun resolveManagedRestorableCoverReference(
        context: Context,
        metadata: ManagedDownloadRestorableMetadata,
        baseline: Boolean
    ): String? = withContext(Dispatchers.IO) {
        resolveRestorableCoverReference(
            metadata = metadata,
            baseline = baseline,
            fingerprintReference = { reference ->
                ManagedDownloadCoverAssetStore.inspect(
                    context = context,
                    reference = reference
                )
            },
            findManagedReferenceByName = { fileName ->
                ManagedDownloadStorage.findCoverReferenceByFileName(context, fileName)
            },
            findContentAddressedReference = { hash ->
                ManagedDownloadStorage.findCoverReferenceByAssetHash(context, hash)
            }
        )
    }

    internal suspend fun syncDownloadedSongMetadataNow(
        song: SongItem,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy()
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

            if (!persistDownloadedMetadata(
                    context = context,
                    audio = storedAudio,
                    song = metadataSong,
                    clearRestorableOverrides = clearRestorableOverrides
                )
            ) {
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
        artifactStateOverride: String? = null,
        operationId: String? = null,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy()
    ): Boolean = downloadedAudioMetadataStore.persist(
        context = context,
        audio = audio,
        song = song,
        sidecarReferences = sidecarReferences,
        downloadFinalized = downloadFinalized,
        resolveExistingSidecars = resolveExistingSidecars,
        artifactStateOverride = artifactStateOverride,
        operationId = operationId,
        clearRestorableOverrides = clearRestorableOverrides
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
        song: SongItem,
        expectedLeaseId: String
    ) {
        val songKey = song.stableKey()
        managedDownloadArtifactLeases.remove(songKey, expectedLeaseId)
        runCatching {
            managedDownloadArtifactCoordinator.markCancelled(
                context = context,
                song = song,
                expectedLeaseId = expectedLeaseId
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "释放下载 artifact lease 失败: ${error.message}")
        }
    }

    private suspend fun releaseDownloadArtifactAfterExecutionOwnershipLoss(
        context: Context,
        song: SongItem,
        operationId: String,
        expectedLeaseId: String
    ) {
        val operationState = DownloadExecutionRoomStore.state(context, operationId)
        val preserveForResume = operationState == "RETRYABLE" ||
            DownloadExecutionRoomStore.isStopped(context, operationId)
        if (preserveForResume) {
            markDownloadArtifactRetryable(
                context = context,
                song = song,
                leaseId = expectedLeaseId,
                errorCode = "EXECUTION_OWNERSHIP_LOST"
            )
            managedDownloadArtifactLeases.remove(song.stableKey(), expectedLeaseId)
        } else {
            releaseDownloadArtifactClaim(context, song, expectedLeaseId)
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
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
        operationId: String? = null
    ) = runNonCancellableDownloadRollback {
        val appContext = context.applicationContext
        val resolvedStoredAudio = storedAudio ?: resolveStoredAudio(appContext, song)
        val resolvedMetadata = resolvedStoredAudio?.let { audio ->
            readDownloadedMetadata(appContext, audio)
        }
        if (resolvedStoredAudio != null && shouldPreserveAudioForCancellationRollback(
                audioIsPending = resolvedStoredAudio.isPendingAudioWrite,
                metadataReadable = resolvedMetadata != null,
                downloadFinalized = resolvedMetadata?.downloadFinalized,
                artifactState = resolvedMetadata?.artifactState,
                metadataOperationId = resolvedMetadata?.operationId,
                operationId = operationId
            )
        ) {
            NPLogger.d(
                TAG,
                "跳过无法证明所有权的音频取消回滚: " +
                    "song=${song.name}, state=${resolvedMetadata?.artifactState}, " +
                    "metadataReadable=${resolvedMetadata != null}, operationId=$operationId"
            )
            return@runNonCancellableDownloadRollback
        }
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
        val deletionKeys = downloadedSongDeletionKeys(targetSongs)
        beginDownloadedSongDeletion(deletionKeys)
        scope.launch {
            try {
                deleteDownloadedSongsWithResult(appContext, targetSongs)
            } finally {
                endDownloadedSongDeletion(deletionKeys)
            }
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

        val deletionKeys = downloadedSongDeletionKeys(targetSongs)
        beginDownloadedSongDeletion(deletionKeys)
        return try {
            withContext(Dispatchers.IO) {
                downloadedSongDeleteMutex.withLock {
                    deleteDownloadedSongsOnIo(appContext, targetSongs)
                }
            }
        } finally {
            endDownloadedSongDeletion(deletionKeys)
        }
    }

    private fun downloadedSongDeletionKeys(songs: Collection<DownloadedSong>): Set<String> {
        return songs.mapNotNullTo(linkedSetOf()) { song ->
            song.remoteSourceStableKeyOrNull()
                ?: song.stableKey?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private fun beginDownloadedSongDeletion(songKeys: Collection<String>) {
        songKeys.forEach { songKey ->
            downloadedSongDeletionCounts.compute(songKey) { _, current ->
                (current ?: AtomicInteger()).apply { incrementAndGet() }
            }
        }
    }

    private fun endDownloadedSongDeletion(songKeys: Collection<String>) {
        songKeys.forEach { songKey ->
            downloadedSongDeletionCounts.computeIfPresent(songKey) { _, count ->
                count.takeIf { it.decrementAndGet() > 0 }
            }
        }
    }

    private suspend fun awaitDownloadedSongDeletion(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        while (keys.any(downloadedSongDeletionCounts::containsKey)) {
            delay(DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS)
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
        } else {
            val deletionSongKeys = downloadedSongDeletionKeys(targetSongs)
            deletionSongKeys.forEach(::cancelDownloadTask)
            awaitDownloadCancellationsSettled(deletionSongKeys)
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
                runCatching {
                    val artifactDeleted = managedDownloadArtifactCoordinator.deleteByStableKey(
                        context = appContext,
                        stableKey = song.stableKey
                    )
                    if (!artifactDeleted) {
                        NPLogger.w(
                            TAG,
                            "下载文件已删除但 artifact 已被并发请求更新，保留记录等待对账: " +
                                "song=${song.name}"
                        )
                    }
                }.onFailure { error ->
                    NPLogger.w(TAG, "删除 managed_library_item/artifact 预览失败: ${error.message}")
                }
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
            when (val evidence = ManagedDownloadReferenceLookup.inspect(context, playbackReference)) {
                ManagedDownloadReferenceLookup.Result.Present -> return@launch
                ManagedDownloadReferenceLookup.Result.Missing -> {
                    NPLogger.w(
                        TAG,
                        "下载文件后台校验确认缺失: " +
                            "${song.name}, reference=$playbackReference"
                    )
                    scheduleCatalogReconcile(context, forceRefresh = true)
                }
                is ManagedDownloadReferenceLookup.Result.PermissionLost,
                is ManagedDownloadReferenceLookup.Result.ProviderFailure,
                ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                    NPLogger.w(
                        TAG,
                        "下载文件后台校验未取得 Missing 证据，保留目录: " +
                            "${song.name}, reference=$playbackReference, evidence=$evidence"
                    )
                }
            }
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
        val evidence = ManagedDownloadReferenceLookup.inspect(context, reference)
        if (evidence !is ManagedDownloadReferenceLookup.Result.Present) {
            NPLogger.w(
                TAG,
                "下载目录缓存命中未确认可读引用，忽略本地回退: " +
                    "song=${song.name}, reference=$reference, evidence=$evidence"
            )
            return null
        }
        return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
    }

    fun findFastCachedDownloadedSongPlaybackUri(context: Context, song: SongItem): String? {
        val downloadedSong = findFastCachedDownloadedSong(context, song) ?: return null
        val reference = resolveDownloadedSongPlaybackReference(downloadedSong) ?: return null
        when (val evidence = ManagedDownloadReferenceLookup.inspect(context, reference)) {
            ManagedDownloadReferenceLookup.Result.Present -> Unit
            ManagedDownloadReferenceLookup.Result.Missing -> {
                scheduleCatalogReconcile(context, forceRefresh = true)
                return null
            }
            is ManagedDownloadReferenceLookup.Result.PermissionLost,
            is ManagedDownloadReferenceLookup.Result.ProviderFailure,
            ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                NPLogger.w(
                    TAG,
                    "下载快索引引用暂不可确认，跳过破坏性对账: " +
                        "song=${song.name}, reference=$reference, evidence=$evidence"
                )
                return null
            }
        }
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
        if (isDownloadClearFenceActive(context)) {
            NPLogger.d(TAG, "忽略下载请求: 清空栅栏仍在生效, song=${song.name}")
            return
        }
        scheduleUserDownload(context, song, skipTrafficRiskPrompt = false)
    }

    suspend fun startDownload(
        context: Context,
        song: SongItem,
        operationId: String,
        preserveStaging: Boolean = false,
        preparedAttemptId: Long? = null
    ): DownloadExecutionResult {
        if (isDownloadClearFenceActive(context)) {
            return DownloadExecutionResult.Cancelled
        }
        return executeDownloadOperation(
            context = context,
            song = song,
            operationId = operationId,
            preserveStaging = preserveStaging,
            preparedAttemptId = preparedAttemptId
        )
    }

    private fun scheduleUserDownload(
        context: Context,
        song: SongItem,
        skipTrafficRiskPrompt: Boolean,
        preserveStaging: Boolean = false
    ) {
        val appContext = context.applicationContext
        if (isDownloadClearFenceActive(appContext)) {
            NPLogger.d(TAG, "忽略下载调度: 清空栅栏仍在生效, song=${song.name}")
            return
        }
        val admissionTicket = downloadAdmissionGate.ticket()
        scope.launch {
            awaitDownloadedSongDeletion(setOf(song.stableKey()))
            var inFlightRequestToRecover: DownloadExecutionRequest? = null
            val admitted = downloadAdmissionGate.admit(admissionTicket) admission@{
                clearSongCancellationForFreshStart(appContext, setOf(song.stableKey()))
                if (
                    maybeRequestTrafficRiskDownloadConfirmation(
                        context = appContext,
                        songs = listOf(song),
                        isBatch = false,
                        skipTrafficRiskPrompt = skipTrafficRiskPrompt
                    )
                ) {
                    return@admission
                }
                val operationId = rememberPendingDownloadQueue(
                    context = appContext,
                    songs = listOf(song),
                    userInitiated = true
                ).singleOrNull()
                if (operationId == null) {
                    NPLogger.w(TAG, "持久化下载 operation 失败: song=${song.name}")
                    return@admission
                }
                val operationState = DownloadExecutionRoomStore.state(appContext, operationId)
                val persistedRequest = DownloadExecutionRoomStore.read(appContext, operationId)
                if (persistedRequest?.song?.stableKey() != song.stableKey()) {
                    NPLogger.w(
                        TAG,
                        "持久化下载 operation 未确认，保留队列等待恢复: song=${song.name}"
                    )
                    return@admission
                }
                if (operationState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES) {
                    inFlightRequestToRecover = persistedRequest
                    NPLogger.d(
                        TAG,
                        "单曲下载复用并检查执行中的 operation: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@admission
                }
                if (operationState !in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES) {
                    NPLogger.w(
                        TAG,
                        "单曲下载 operation 不可调度: song=${song.name}, state=$operationState"
                    )
                    return@admission
                }
                val request = persistedRequest.copy(
                    preserveStaging = persistedRequest.preserveStaging || preserveStaging,
                    userInitiated = true
                )
                DownloadExecutionRoomStore.upsert(
                    context = appContext,
                    request = request,
                    state = "QUEUED"
                )
                val schedule = DownloadExecutionHosts.default.schedule(
                    context = appContext,
                    request = request
                )
                if (schedule is DownloadExecutionSchedule.Deferred) {
                    NPLogger.d(
                        TAG,
                        "单曲下载已登记等待全局宿主槽位: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                } else if (schedule is DownloadExecutionSchedule.Rejected) {
                    val retryMarked = runCatching {
                        DownloadExecutionRoomStore.markScheduleRejectedRetryable(
                            context = appContext,
                            operationId = operationId,
                            stableKey = song.stableKey(),
                            errorCode = "OS_HOST_REJECTED"
                        )
                    }.onFailure { error ->
                        NPLogger.w(TAG, "写入单曲 operation 重试状态失败: ${error.message}")
                    }.getOrDefault(false)
                    if (!retryMarked) {
                        val latestState = runCatching {
                            DownloadExecutionRoomStore.state(appContext, operationId)
                        }.getOrNull()
                        val latestRequest = runCatching {
                            DownloadExecutionRoomStore.read(appContext, operationId)
                        }.getOrNull()
                        if (
                            resolveBatchOperationScheduleAction(
                                operationState = latestState,
                                requestMatchesSong =
                                    latestRequest?.song?.stableKey() == song.stableKey()
                            ) == BatchOperationScheduleAction.HANDED_OFF
                        ) {
                            NPLogger.d(
                                TAG,
                                "单曲下载调度竞态中 operation 已被接管: " +
                                    "song=${song.name}, operationId=$operationId, " +
                                    "state=$latestState"
                            )
                            return@admission
                        }
                    }
                    NPLogger.w(
                        TAG,
                        "OS 下载宿主调度失败: song=${song.name}, reason=${schedule.reason}"
                    )
                }
            }
            if (admitted) {
                inFlightRequestToRecover?.let { request ->
                    recoverInFlightDownloadOperations(
                        context = appContext,
                        requests = listOf(request),
                        admissionTicket = admissionTicket
                    )
                }
            }
            if (!admitted) {
                NPLogger.d(TAG, "清空任务已使单曲下载请求过期: song=${song.name}")
            }
        }
    }

    internal suspend fun executeDownloadOperation(
        context: Context,
        song: SongItem,
        operationId: String,
        preserveStaging: Boolean = false,
        preparedAttemptId: Long? = null
    ): DownloadExecutionResult {
        val appContext = context.applicationContext
        if (isDownloadClearFenceActive(appContext)) {
            return DownloadExecutionResult.Cancelled
        }
        val songKey = song.stableKey()
        val admissionTicket = downloadAdmissionGate.ticket()
        downloadAdmissionGate.awaitOpen()
        awaitDownloadedSongDeletion(setOf(songKey))
        var admissionResult: DownloadExecutionResult? = null
        var admittedRequest: DownloadExecutionRequest? = null
        var admittedAttemptId: Long? = null
        var admittedPreserveStaging = false
        var admittedRequestGeneration: Long? = null
        val admitted = downloadAdmissionGate.admit(admissionTicket) admission@{
            val persistedRequest = DownloadExecutionRoomStore.read(appContext, operationId)
                ?.takeIf { request -> request.song.stableKey() == songKey }
                ?: run {
                    admissionResult = DownloadExecutionResult.MissingOperation
                    return@admission
                }
            if (!DownloadExecutionRoomStore.isExecutionOwned(appContext, operationId, songKey)) {
                admissionResult = executionResultForOperation(
                    context = appContext,
                    operationId = operationId,
                    songKey = songKey,
                    expectedAttemptId = preparedAttemptId
                )
                return@admission
            }
            val durableAttemptIds = (preparedAttemptId ?: persistedRequest.attemptId)
                ?.takeIf { it > 0L }
                ?.let { attemptId -> mapOf(songKey to attemptId) }
                ?: emptyMap()
            val effectiveAttemptId = taskStore.ensureDownloadTasks(
                songs = listOf(song),
                status = DownloadStatus.QUEUED,
                durableAttemptIds = durableAttemptIds
            )[songKey] ?: run {
                admissionResult = DownloadExecutionResult.Retry
                return@admission
            }
            DownloadExecutionRoomStore.upsert(
                context = appContext,
                request = persistedRequest.copy(attemptId = effectiveAttemptId),
                state = "RUNNING"
            )
            runCatching {
                DownloadExecutionRoomStore.updateState(
                    context = appContext,
                    operationId = operationId,
                    state = "RUNNING"
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "更新下载 operation 状态失败: ${error.message}")
            }
            if (!DownloadExecutionRoomStore.isExecutionOwned(appContext, operationId, songKey)) {
                taskStore.removeDownloadTask(
                    songKey = songKey,
                    expectedAttemptId = effectiveAttemptId
                )
                admissionResult = executionResultForOperation(
                    context = appContext,
                    operationId = operationId,
                    songKey = songKey,
                    expectedAttemptId = effectiveAttemptId
                )
                return@admission
            }
            admittedRequest = persistedRequest
            admittedAttemptId = effectiveAttemptId
            admittedPreserveStaging = resolveDownloadPreserveStaging(
                persistedPreserveStaging = persistedRequest.preserveStaging,
                preserveRequested = preserveStaging
            )
            admittedRequestGeneration = beginDownloadRequestGeneration(listOf(song))
        }
        if (!admitted) {
            return DownloadExecutionResult.Cancelled
        }
        admissionResult?.let { return it }
        val persistedRequest = admittedRequest ?: return DownloadExecutionResult.Retry
        val effectiveAttemptId = admittedAttemptId ?: return DownloadExecutionResult.Retry
        val requestGeneration = admittedRequestGeneration ?: return DownloadExecutionResult.Retry
        startDownloadConfirmed(
            context = appContext,
            song = song,
            cleanupBeforeStart = !admittedPreserveStaging,
            requestGeneration = requestGeneration,
            deferForNetworkPolicy = false,
            operationId = operationId,
            preparedAttemptId = effectiveAttemptId,
            artifactLeaseOwnerId = persistedRequest.artifactLeaseId
        )
        return executionResultForOperation(
            context = appContext,
            operationId = operationId,
            songKey = songKey,
            expectedAttemptId = effectiveAttemptId
        )
    }

    private suspend fun executionResultForOperation(
        context: Context,
        operationId: String,
        songKey: String,
        expectedAttemptId: Long?
    ): DownloadExecutionResult {
        if (DownloadExecutionRoomStore.isStopped(context, operationId)) {
            return DownloadExecutionResult.UserStopped
        }
        when (DownloadExecutionRoomStore.state(context, operationId)) {
            "COMPLETED",
            "CORE_COMMITTED" -> return DownloadExecutionResult.Accepted
            "CANCEL_REQUESTED",
            "CANCELLED" -> return DownloadExecutionResult.Cancelled
            "STOPPED" -> return DownloadExecutionResult.UserStopped
            "RETRYABLE" -> return DownloadExecutionResult.Retry
            null -> return DownloadExecutionResult.MissingOperation
        }
        val task = taskStore.findTask(songKey)
        if (
            expectedAttemptId != null &&
            task != null &&
            task.attemptId != expectedAttemptId
        ) {
            return DownloadExecutionResult.Retry
        }
        return when (task?.status) {
            DownloadStatus.CANCELLED -> DownloadExecutionResult.Cancelled
            DownloadStatus.FAILED,
            DownloadStatus.WAITING_NETWORK -> DownloadExecutionResult.Retry
            DownloadStatus.COMPLETED -> DownloadExecutionResult.Accepted
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING -> DownloadExecutionResult.Retry
            null -> DownloadExecutionResult.Retry
        }
    }

    internal fun stopDownloadOperation(
        context: Context,
        songKey: String,
        rememberForRetry: Boolean
    ) {
        val task = taskStore.findTask(songKey) ?: return
        if (
            task.status != DownloadStatus.QUEUED &&
            task.status != DownloadStatus.DOWNLOADING &&
            task.status != DownloadStatus.WAITING_NETWORK
        ) {
            return
        }
        AudioDownloadManager.pauseSongDownloadForExecutionHost(songKey)
        updateTaskStatus(
            songKey = songKey,
            status = DownloadStatus.WAITING_NETWORK,
            expectedAttemptId = task.attemptId
        )
        if (rememberForRetry) {
            rememberPendingDownloadQueue(context.applicationContext, listOf(task.song))
        }
    }

    internal fun cancelDownloadOperationFromHost(songKey: String) {
        AudioDownloadManager.cancelSongDownload(songKey)
    }

    internal fun removeDownloadOperationRecordFromHost(
        context: Context,
        operationId: String
    ) {
        scope.launch {
            runCatching {
                DownloadExecutionRoomStore.delete(context.applicationContext, operationId)
            }.onFailure { error ->
                NPLogger.w(TAG, "删除已取消 operation 记录失败: ${error.message}")
            }
        }
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
        if (isDownloadClearFenceActive(appContext)) {
            NPLogger.d(TAG, "忽略内部下载启动: 清空栅栏仍在生效, song=${song.name}")
            return
        }
        val admissionTicket = downloadAdmissionGate.ticket()
        scope.launch {
            val songKey = song.stableKey()
            awaitDownloadedSongDeletion(setOf(songKey))
            val admitted = downloadAdmissionGate.admit(admissionTicket) admission@{
                clearSongCancellationForFreshStart(appContext, setOf(songKey))
                if (
                    maybeRequestTrafficRiskDownloadConfirmation(
                        context = appContext,
                        songs = listOf(song),
                        isBatch = false,
                        skipTrafficRiskPrompt = skipTrafficRiskPrompt
                    )
                ) {
                    return@admission
                }
                val requestGeneration = beginDownloadRequestGeneration(listOf(song))
                rememberPendingDownloadQueue(
                    context = appContext,
                    songs = listOf(song),
                    userInitiated = true
                )
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
            if (!admitted) {
                NPLogger.d(TAG, "清空任务已使内部下载请求过期: song=${song.name}")
            }
        }
    }

    private suspend fun startDownloadConfirmed(
        context: Context,
        song: SongItem,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        deferForNetworkPolicy: Boolean,
        preparedAttemptId: Long? = null,
        operationId: String? = null,
        artifactLeaseOwnerId: String? = null
    ) {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        if (
            operationId != null &&
                !DownloadExecutionRoomStore.isExecutionOwned(appContext, operationId, songKey)
        ) {
            return
        }
        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
            NPLogger.d(TAG, "忽略过期单曲下载启动: song=${song.name}, generation=$requestGeneration")
            return
        }
            if (preparedAttemptId != null &&
                !taskStore.isDownloadAttemptCurrent(songKey, preparedAttemptId)
            ) {
                NPLogger.d(TAG, "忽略已被替换的预创建下载任务: song=${song.name}, attempt=$preparedAttemptId")
                return
            }
            val durableArtifactLeaseOwnerId = artifactLeaseOwnerId
                ?: operationId?.let { id ->
                    DownloadExecutionRoomStore.read(appContext, id)?.artifactLeaseId
                }
                ?: ManagedDownloadStorage.findQueuedOperationIdForSong(appContext, songKey)
                    ?.let { id ->
                        DownloadExecutionRoomStore.read(appContext, id)?.artifactLeaseId
                    }
            val artifactClaim = try {
                managedDownloadArtifactCoordinator.claim(
                    context = appContext,
                    song = song,
                    reconcileStorage = false,
                    leaseOwnerId = durableArtifactLeaseOwnerId
                )
            } catch (cancellation: CancellationException) {
                durableArtifactLeaseOwnerId?.let { leaseId ->
                    releaseDownloadArtifactClaim(appContext, song, leaseId)
                }
                throw cancellation
            } catch (error: Exception) {
                NPLogger.w(TAG, "下载 artifact claim 失败，继续现有恢复路径: ${error.message}")
                null
            }
            val requiresFinalizationRecovery = (
                artifactClaim as? ManagedDownloadArtifactClaim.AlreadyDownloaded
                )?.artifact?.state?.let(::requiresDownloadFinalizationRecovery) == true
            val acquiredLeaseId = artifactClaim.ownedLeaseIdOrNull()
                ?: (artifactClaim as? ManagedDownloadArtifactClaim.AlreadyDownloaded)
                    ?.takeIf { claim ->
                        requiresFinalizationRecovery &&
                            claim.artifact.leaseId == durableArtifactLeaseOwnerId
                    }
                    ?.artifact
                    ?.leaseId
            acquiredLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases[songKey] = leaseId
            }
            if (
                operationId != null &&
                    !DownloadExecutionRoomStore.isExecutionOwned(
                        context = appContext,
                        operationId = operationId,
                        stableKey = songKey
                    )
            ) {
                acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactAfterExecutionOwnershipLoss(
                        context = appContext,
                        song = song,
                        operationId = operationId,
                        expectedLeaseId = leaseId
                    )
                }
                return
            }
            when (artifactClaim) {
                is ManagedDownloadArtifactClaim.AlreadyDownloaded -> {
                    if (!requiresFinalizationRecovery) {
                        updateTaskStatus(
                            songKey,
                            DownloadStatus.COMPLETED,
                            expectedAttemptId = preparedAttemptId
                        )
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                        NPLogger.d(
                            TAG,
                            "跳过已完成下载 artifact: song=${song.name}, songKey=$songKey"
                        )
                        return
                    }
                    NPLogger.d(
                        TAG,
                        "恢复未完成下载资产收尾: song=${song.name}, " +
                            "artifactState=${artifactClaim.artifact.state}"
                    )
                }

                is ManagedDownloadArtifactClaim.InFlight -> {
                    NPLogger.d(TAG, "跳过重复下载请求: song=${song.name}, songKey=$songKey")
                    return
                }

                is ManagedDownloadArtifactClaim.Acquired -> {
                    artifactClaim.artifact.leaseId?.let { leaseId ->
                        managedDownloadArtifactLeases[songKey] = leaseId
                    }
                }

                is ManagedDownloadArtifactClaim.RepairRequired,
                null -> Unit
            }
            val attemptId = preparedAttemptId ?: taskStore.prepareDownloadTask(song) ?: run {
                if (artifactClaim is ManagedDownloadArtifactClaim.Acquired) {
                    markDownloadArtifactRetryable(
                        context = appContext,
                        song = song,
                        leaseId = artifactClaim.artifact.leaseId,
                        errorCode = "TASK_ALREADY_ACTIVE"
                    )
                    artifactClaim.artifact.leaseId?.let { leaseId ->
                        managedDownloadArtifactLeases.remove(songKey, leaseId)
                    }
                }
                return
            }
            try {
                withSongExecutionLock(songKey) {
                    awaitSongCancellationSettled(
                        songKey = songKey,
                        timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS
                    )
                    AudioDownloadManager.clearNetworkPolicyPause(setOf(songKey))
                    if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                        NPLogger.d(TAG, "单曲下载等待取消收敛后已过期: song=${song.name}, generation=$requestGeneration")
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        return@withSongExecutionLock
                    }
                    if (
                        operationId != null &&
                            !DownloadExecutionRoomStore.isExecutionOwned(
                                context = appContext,
                                operationId = operationId,
                                stableKey = songKey
                            )
                    ) {
                        acquiredLeaseId?.let { leaseId ->
                            releaseDownloadArtifactAfterExecutionOwnershipLoss(
                                context = appContext,
                                song = song,
                                operationId = operationId,
                                expectedLeaseId = leaseId
                            )
                        }
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        return@withSongExecutionLock
                    }
                    val preserveArtifactForRepair =
                        artifactClaim is ManagedDownloadArtifactClaim.RepairRequired
                    if (cleanupBeforeStart && !preserveArtifactForRepair) {
                        cleanupDownloadArtifactsBeforeFreshStart(appContext, song)
                    }
                    if (
                        cleanupBeforeStart &&
                            operationId != null &&
                            !DownloadExecutionRoomStore.markStagingPrepared(
                                context = appContext,
                                operationId = operationId,
                                stableKey = songKey
                            )
                    ) {
                        error("failed to persist prepared download staging")
                    }
                    if (shouldSkipDownload(appContext, song)) {
                        acquiredLeaseId?.let { leaseId ->
                            releaseDownloadArtifactClaim(appContext, song, leaseId)
                        }
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        forgetPendingDownloadQueueEntriesIfCurrent(
                            appContext,
                            setOf(songKey),
                            requestGeneration
                        )
                        return@withSongExecutionLock
                    }

                    val fastCachedSong = findFastCachedDownloadedSong(appContext, song)
                        ?.takeUnless { requiresFinalizationRecovery }
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
                                leaseId = acquiredLeaseId
                            )
                            acquiredLeaseId?.let { leaseId ->
                                managedDownloadArtifactLeases.remove(songKey, leaseId)
                            }
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
                    val needsFinalization = requiresFinalizationRecovery ||
                        existingAudio?.let { audio ->
                            isUnfinalizedDownloadedMetadata(
                                readDownloadedMetadata(appContext, audio)
                            )
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
                                storedAudioHint = existingAudio,
                                operationId = operationId,
                                expectedArtifactLeaseId = acquiredLeaseId
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
                                leaseId = acquiredLeaseId
                            )
                            acquiredLeaseId?.let { leaseId ->
                                managedDownloadArtifactLeases.remove(songKey, leaseId)
                            }
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
                        deferQueuedDownloadStartForNetworkPolicyIfNeeded(
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

                    if (
                        operationId != null &&
                            !DownloadExecutionRoomStore.isExecutionOwned(
                                context = appContext,
                                operationId = operationId,
                                stableKey = songKey
                            )
                    ) {
                        acquiredLeaseId?.let { leaseId ->
                            releaseDownloadArtifactAfterExecutionOwnershipLoss(
                                context = appContext,
                                song = song,
                                operationId = operationId,
                                expectedLeaseId = leaseId
                            )
                        }
                        removeDownloadTask(songKey, expectedAttemptId = attemptId)
                        return@withSongExecutionLock
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

                    taskStore.beginDownloadTransfer()
                    try {
                        taskStore.registerActiveDownloadTask(
                            song = song,
                            expectedAttemptId = attemptId
                        )
                        val progressCheckpointBinding = operationId?.let { id ->
                            ActiveProgressCheckpointBinding(
                                operationId = id,
                                attemptId = attemptId
                            )
                        }
                        progressCheckpointBinding?.let { binding ->
                            activeProgressCheckpointBindings[songKey] = binding
                            restoreTaskProgressCheckpoint(
                                context = appContext,
                                song = song,
                                binding = binding
                            )
                        }
                        AudioDownloadManager.resetCancelFlag()
                        try {
                            AudioDownloadManager.downloadSong(
                                context = appContext,
                                song = song,
                                attemptId = attemptId,
                                operationId = operationId
                            )
                        } finally {
                            progressCheckpointBinding?.let { binding ->
                                activeProgressCheckpointBindings.remove(songKey, binding)
                            }
                        }
                        if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                            NPLogger.d(TAG, "单曲下载完成后已过期，转入过期结果回滚: song=${song.name}, generation=$requestGeneration")
                            finalizeCompletedDownload(
                                context = appContext,
                                song = song,
                                refreshCatalog = false,
                                expectedAttemptId = attemptId,
                                operationId = operationId,
                                expectedArtifactLeaseId = acquiredLeaseId
                            )
                            return@withSongExecutionLock
                        }
                        finalizeCompletedDownload(
                            context = appContext,
                            song = song,
                            refreshCatalog = true,
                            expectedAttemptId = attemptId,
                            operationId = operationId,
                            expectedArtifactLeaseId = acquiredLeaseId
                        )
                    } finally {
                        taskStore.endDownloadTransfer()
                    }
                }
            } catch (_: CancellationException) {
                val pausedForNetworkPolicy =
                    AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)
                if (!pausedForNetworkPolicy) {
                    acquiredLeaseId?.let { leaseId ->
                        releaseDownloadArtifactClaim(appContext, song, leaseId)
                    }
                }
                if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                    return
                }
                if (pausedForNetworkPolicy) {
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
            } catch (error: Exception) {
                if (!isDownloadRequestGenerationCurrent(songKey, requestGeneration)) {
                    markDownloadArtifactRetryable(
                        context = appContext,
                        song = song,
                        leaseId = acquiredLeaseId,
                        errorCode = "STALE_DOWNLOAD_FAILED"
                    )
                    acquiredLeaseId?.let { leaseId ->
                        managedDownloadArtifactLeases.remove(songKey, leaseId)
                    }
                    return
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
                    leaseId = acquiredLeaseId,
                    errorCode = "DOWNLOAD_FAILED"
                )
                acquiredLeaseId?.let { leaseId ->
                    managedDownloadArtifactLeases.remove(songKey, leaseId)
                }
                forgetPendingDownloadQueueEntriesIfCurrent(
                    appContext,
                    setOf(songKey),
                    requestGeneration
                )
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
        deferForNetworkPolicy: Boolean = false,
        userInitiated: Boolean = true
    ) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        if (isDownloadClearFenceActive(appContext)) {
            NPLogger.d(TAG, "忽略批量下载请求: 清空栅栏仍在生效, count=${songs.size}")
            return
        }
        val requestedSongs = songs.distinctBy(SongItem::stableKey)
        if (requestedSongs.isEmpty()) {
            return
        }
        val batchPresentationId = beginBatchDownloadPresentation(requestedSongs)
        val admissionTicket = downloadAdmissionGate.ticket()
        scope.launch {
            awaitDownloadedSongDeletion(requestedSongs.map(SongItem::stableKey))
            var inFlightRequestsToRecover = emptyList<DownloadExecutionRequest>()
            val admitted = downloadAdmissionGate.admit(admissionTicket) admission@{
                inFlightRequestsToRecover = requestedSongs.mapNotNull { song ->
                    val songKey = song.stableKey()
                    val operationId = DownloadExecutionRoomStore.findReadableOperationIdForSong(
                        context = appContext,
                        songKey = songKey,
                        states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                        excludeUserCancelledStops = true
                    ) ?: return@mapNotNull null
                    DownloadExecutionRoomStore.read(appContext, operationId)
                        ?.takeIf { request -> request.song.stableKey() == songKey }
                }
                val alreadyExecutingSongKeys = inFlightRequestsToRecover
                    .mapTo(linkedSetOf()) { request -> request.song.stableKey() }
                bindBatchDownloadPresentationAttempts(
                    batchId = batchPresentationId,
                    attemptIdsBySongKey = taskStore.currentTasks()
                        .asSequence()
                        .filter { task -> task.song.stableKey() in alreadyExecutingSongKeys }
                        .associate { task -> task.song.stableKey() to task.attemptId }
                )
                val candidateSongs = selectBatchDownloadCandidates(
                    songs = requestedSongs,
                    inFlightSongKeys = alreadyExecutingSongKeys
                )
                if (candidateSongs.isEmpty()) {
                    NPLogger.d(
                        TAG,
                        "批量下载请求均已有执行中的 operation: " +
                            "requested=${requestedSongs.size}"
                    )
                    if (alreadyExecutingSongKeys.isEmpty()) {
                        clearBatchDownloadPresentation(batchPresentationId)
                    }
                    return@admission
                }
                val requestedSongKeys = candidateSongs.mapTo(linkedSetOf()) { it.stableKey() }
                clearSongCancellationForFreshStart(appContext, requestedSongKeys)
                if (
                    maybeRequestTrafficRiskDownloadConfirmation(
                        context = appContext,
                        songs = candidateSongs,
                        isBatch = true,
                        skipTrafficRiskPrompt = skipTrafficRiskPrompt
                    )
                ) {
                    clearBatchDownloadPresentation(batchPresentationId)
                    return@admission
                }
                val operationIds = rememberPendingDownloadQueue(
                    context = appContext,
                    songs = candidateSongs,
                    userInitiated = userInitiated
                )
                val operationIdsBySongKey = linkedMapOf<String, String>()
                operationIds.forEach { operationId ->
                    val request = DownloadExecutionRoomStore.read(appContext, operationId)
                    if (request != null) {
                        operationIdsBySongKey[request.song.stableKey()] = operationId
                    }
                }
                val schedulableSongs = candidateSongs.filter { song ->
                    val operationId = operationIdsBySongKey[song.stableKey()]
                    operationId != null &&
                        DownloadExecutionRoomStore.state(appContext, operationId) in
                        DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
                }
                if (schedulableSongs.isEmpty()) {
                    NPLogger.d(
                        TAG,
                        "批量下载 operation 已被其他执行接管: " +
                            "requested=${candidateSongs.size}"
                    )
                    if (alreadyExecutingSongKeys.isEmpty()) {
                        clearBatchDownloadPresentation(batchPresentationId)
                    }
                    return@admission
                }
                val requestGeneration = beginDownloadRequestGeneration(schedulableSongs)
                startBatchDownloadConfirmed(
                    context = appContext,
                    songs = schedulableSongs,
                    cleanupBeforeStart = cleanupBeforeStart,
                    requestGeneration = requestGeneration,
                    admissionTicket = admissionTicket,
                    deferForNetworkPolicy = deferForNetworkPolicy,
                    userInitiated = userInitiated,
                    operationIdsBySongKey = operationIdsBySongKey,
                    batchPresentationId = batchPresentationId
                )
            }
            if (admitted) {
                recoverInFlightDownloadOperations(
                    context = appContext,
                    requests = inFlightRequestsToRecover,
                    admissionTicket = admissionTicket
                )
            }
            if (!admitted) {
                NPLogger.d(
                    TAG,
                    "清空任务已使批量下载请求过期: requested=${requestedSongs.size}"
                )
                clearBatchDownloadPresentation(batchPresentationId)
            }
        }
    }

    private class BatchDownloadSession(
        val context: Context,
        val requestedSongs: List<SongItem>,
        val sourceSongCount: Int,
        val cleanupBeforeStart: Boolean,
        val requestGeneration: Long,
        val admissionTicket: Long,
        val deferForNetworkPolicy: Boolean,
        val userInitiated: Boolean,
        val operationIdsBySongKey: Map<String, String>,
        val batchPresentationId: Long
    ) {
        val pendingSongs = mutableListOf<QueuedDownloadRequest>()
        val handedOffSongKeys = mutableSetOf<String>()
        val scheduledSongKeys = mutableSetOf<String>()
        val artifactClaims = linkedMapOf<String, ManagedDownloadArtifactClaim?>()
        val preparedAttemptIds = linkedMapOf<String, Long>()
        val settledSongKeys = mutableSetOf<String>()
        val settledAttemptIds = linkedMapOf<String, Long>()
        val optimisticDownloadedSongs = mutableListOf<DownloadedSong>()
        var skippedLocalSongs = 0
        var preparedQueuedSongs = 0

        fun enqueue(song: SongItem, attemptId: Long, operationId: String) {
            preparedQueuedSongs++
            pendingSongs += QueuedDownloadRequest(
                song = song,
                attemptId = attemptId,
                operationId = operationId
            )
        }
    }

    private data class PreparedBatchArtifact(
        val operationId: String,
        val artifactClaim: ManagedDownloadArtifactClaim?,
        val requiresFinalizationRecovery: Boolean,
        val acquiredLeaseId: String?,
        val attemptId: Long?
    )

    private fun startBatchDownloadConfirmed(
        context: Context,
        songs: List<SongItem>,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        admissionTicket: Long,
        deferForNetworkPolicy: Boolean,
        operationIdsBySongKey: Map<String, String>,
        batchPresentationId: Long,
        userInitiated: Boolean = true
    ) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        val batchJob = scope.launch {
            runBatchDownloadSession(
                context = appContext,
                songs = songs,
                cleanupBeforeStart = cleanupBeforeStart,
                requestGeneration = requestGeneration,
                admissionTicket = admissionTicket,
                deferForNetworkPolicy = deferForNetworkPolicy,
                operationIdsBySongKey = operationIdsBySongKey,
                batchPresentationId = batchPresentationId,
                userInitiated = userInitiated
            )
        }
        registerActiveBatchDownloadJob(batchJob)
    }

    private suspend fun runBatchDownloadSession(
        context: Context,
        songs: List<SongItem>,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        admissionTicket: Long,
        deferForNetworkPolicy: Boolean,
        operationIdsBySongKey: Map<String, String>,
        batchPresentationId: Long,
        userInitiated: Boolean
    ) {
        val requestedSongs = songs.distinctBy(SongItem::stableKey)
            .filter { song ->
                isDownloadRequestGenerationCurrent(song.stableKey(), requestGeneration)
            }
        if (requestedSongs.isEmpty()) {
            NPLogger.d(TAG, "忽略过期批量下载启动: generation=$requestGeneration")
            clearBatchDownloadPresentation(batchPresentationId)
            return
        }
        val session = BatchDownloadSession(
            context = context,
            requestedSongs = requestedSongs,
            sourceSongCount = songs.size,
            cleanupBeforeStart = cleanupBeforeStart,
            requestGeneration = requestGeneration,
            admissionTicket = admissionTicket,
            deferForNetworkPolicy = deferForNetworkPolicy,
            userInitiated = userInitiated,
            operationIdsBySongKey = operationIdsBySongKey,
            batchPresentationId = batchPresentationId
        )
        try {
            prepareAndScheduleBatchDownloadSession(session)
        } catch (_: CancellationException) {
            cancelPreparedBatchDownloadSession(session)
        } catch (error: Exception) {
            failPreparedBatchDownloadSession(session, error)
        }
    }

    private suspend fun prepareAndScheduleBatchDownloadSession(session: BatchDownloadSession) {
        NPLogger.d(
            TAG,
            "批量下载启动: requested=${session.sourceSongCount}, " +
                "deduped=${session.requestedSongs.size}, " +
                "cleanupBeforeStart=${session.cleanupBeforeStart}, " +
                "persistedQueued=${ManagedDownloadStorage.listPendingQueuedDownloads(session.context).size}"
        )
        val claimableSongs = findClaimableBatchDownloadSongs(session)
        if (claimableSongs.isEmpty()) {
            return
        }
        if (!prepareBatchDownloadTasks(session, claimableSongs)) {
            return
        }
        val deferEarlyHandoffForNetwork = shouldDeferQueuedDownloadStartForNetwork(
            networkType = session.context.currentTrafficNetworkType(),
            mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed,
            deferForNetworkPolicy = session.deferForNetworkPolicy
        )
        val downloadLibrarySnapshot = buildBatchDownloadLibrarySnapshot(session.context)
        var earlyHandoffLogged = false
        for (song in claimableSongs) {
            prepareBatchDownloadSong(session, song, downloadLibrarySnapshot)
            if (!deferEarlyHandoffForNetwork) {
                val earlyRequests = selectBatchRequestsForEarlyHandoff(
                    pendingRequests = session.pendingSongs,
                    scheduledSongKeys = session.scheduledSongKeys,
                    maximumHandoffs = BATCH_DOWNLOAD_EARLY_HANDOFF_LIMIT
                )
                if (earlyRequests.isNotEmpty() && !earlyHandoffLogged) {
                    earlyHandoffLogged = true
                    NPLogger.d(
                        TAG,
                        "批量下载提前开始交接: earlyLimit=$BATCH_DOWNLOAD_EARLY_HANDOFF_LIMIT"
                    )
                }
                earlyRequests.forEach { request ->
                    schedulePendingBatchDownload(
                        session = session,
                        request = request,
                        pendingAttemptIds = mapOf(request.song.stableKey() to request.attemptId)
                    )
                }
            }
        }
        removeDownloadTasks(session.settledAttemptIds)
        publishOptimisticDownloadedSongs(session.context, session.optimisticDownloadedSongs)
        forgetPendingDownloadQueueEntriesIfCurrent(
            context = session.context,
            songKeys = session.settledSongKeys,
            generation = session.requestGeneration
        )
        if (session.pendingSongs.isEmpty()) {
            NPLogger.d(
                TAG,
                "没有新的批量下载任务: requested=${session.requestedSongs.size}, " +
                    "skippedLocalSongs=${session.skippedLocalSongs}, " +
                    "settledSongKeys=${session.settledSongKeys.size}, " +
                    "persistedQueued=${ManagedDownloadStorage.listPendingQueuedDownloads(session.context).size}"
            )
            return
        }
        val pendingAttemptIds = session.pendingSongs.associate { request ->
            request.song.stableKey() to request.attemptId
        }
        if (
            deferQueuedDownloadStartForNetworkPolicyIfNeeded(
                context = session.context,
                songs = session.pendingSongs.map(QueuedDownloadRequest::song),
                attemptIdsBySongKey = pendingAttemptIds,
                requestGeneration = session.requestGeneration,
                reason = "batch_start",
                deferForNetworkPolicy = session.deferForNetworkPolicy
            )
        ) {
            return
        }
        NPLogger.d(
            TAG,
            "批量下载正式开始: pendingSongs=${session.pendingSongs.size}, " +
                "preparedQueuedSongs=${session.preparedQueuedSongs}, " +
                "settledSongKeys=${session.settledSongKeys.size}"
        )
        schedulePendingBatchDownloads(session, pendingAttemptIds)
    }

    private suspend fun prepareBatchDownloadTasks(
        session: BatchDownloadSession,
        songs: List<SongItem>
    ): Boolean {
        var preparedAttemptIds = emptyMap<String, Long>()
        val admitted = downloadAdmissionGate.admit(session.admissionTicket) batchTaskAdmission@{
            val reusableRequests = DownloadExecutionRoomStore.listByStates(
                context = session.context,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
            ).associateBy { entry -> entry.request.operationId }
            val durableAttemptIds = linkedMapOf<String, Long>()
            val taskSongs = songs.filter songFilter@{ song ->
                val songKey = song.stableKey()
                val operationId = session.operationIdsBySongKey[songKey]
                    ?: return@songFilter false
                val entry = reusableRequests[operationId]
                    ?: return@songFilter false
                if (
                    !isDownloadRequestGenerationCurrent(songKey, session.requestGeneration) ||
                        entry.request.song.stableKey() != songKey
                ) {
                    return@songFilter false
                }
                entry.request.attemptId?.takeIf { attemptId -> attemptId > 0L }
                    ?.let { attemptId -> durableAttemptIds[songKey] = attemptId }
                true
            }
            preparedAttemptIds = taskStore.ensureDownloadTasks(
                songs = taskSongs,
                status = DownloadStatus.QUEUED,
                durableAttemptIds = durableAttemptIds
            )
        }
        if (!admitted) {
            NPLogger.d(
                TAG,
                "清空任务已使批量任务预创建过期: requested=${songs.size}"
            )
            return false
        }
        session.preparedAttemptIds.putAll(preparedAttemptIds)
        bindBatchDownloadPresentationAttempts(
            batchId = session.batchPresentationId,
            attemptIdsBySongKey = preparedAttemptIds
        )
        return preparedAttemptIds.isNotEmpty()
    }

    private suspend fun findClaimableBatchDownloadSongs(
        session: BatchDownloadSession
    ): List<SongItem> {
        val currentRequestedSongs = session.requestedSongs.filter { song ->
            isDownloadRequestGenerationCurrent(song.stableKey(), session.requestGeneration)
        }
        if (currentRequestedSongs.isEmpty()) {
            NPLogger.d(
                TAG,
                "批量下载准备前请求已过期: generation=${session.requestGeneration}"
            )
            return emptyList()
        }
        val claimableSongs = currentRequestedSongs.filter { song ->
            val operationId = session.operationIdsBySongKey[song.stableKey()]
            operationId != null &&
                DownloadExecutionRoomStore.state(session.context, operationId) in
                DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
        }
        if (claimableSongs.isEmpty()) {
            NPLogger.d(TAG, "批量下载 operation 已在 claim 前被其他执行接管")
        }
        return claimableSongs
    }

    private suspend fun prepareBatchDownloadSong(
        session: BatchDownloadSession,
        song: SongItem,
        downloadLibrarySnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?
    ) {
        val songKey = song.stableKey()
        try {
            if (!isDownloadRequestGenerationCurrent(songKey, session.requestGeneration)) {
                session.settledSongKeys += songKey
                session.preparedAttemptIds[songKey]?.let { attemptId ->
                    session.settledAttemptIds[songKey] = attemptId
                    markBatchDownloadPresentationTerminal(
                        songKey = songKey,
                        attemptId = attemptId,
                        terminalState = BatchDownloadTerminalState.CANCELLED,
                        batchId = session.batchPresentationId
                    )
                }
                return
            }
            val preparedArtifact = claimAndPrepareBatchArtifact(session, song)
            when (val artifactClaim = preparedArtifact.artifactClaim) {
                is ManagedDownloadArtifactClaim.AlreadyDownloaded -> {
                    if (!preparedArtifact.requiresFinalizationRecovery) {
                        session.settledSongKeys += songKey
                        preparedArtifact.attemptId?.let { attemptId ->
                            session.settledAttemptIds[songKey] = attemptId
                            markBatchDownloadPresentationTerminal(
                                songKey = songKey,
                                attemptId = attemptId,
                                terminalState = BatchDownloadTerminalState.COMPLETED,
                                batchId = session.batchPresentationId
                            )
                        }
                        NPLogger.d(TAG, "批量下载跳过已完成 artifact: song=${song.name}")
                        return
                    }
                    NPLogger.d(
                        TAG,
                        "批量下载恢复未完成资产收尾: song=${song.name}, " +
                            "artifactState=${artifactClaim.artifact.state}"
                    )
                }

                is ManagedDownloadArtifactClaim.InFlight -> {
                    val attemptId = requireNotNull(preparedArtifact.attemptId) {
                        "in-flight artifact is missing a durable retry request"
                    }
                    session.enqueue(song, attemptId, preparedArtifact.operationId)
                    NPLogger.d(
                        TAG,
                        "批量下载 artifact 已由其他 operation 持有，保留宿主重试: " +
                            "song=${song.name}, operationId=${preparedArtifact.operationId}"
                    )
                    return
                }

                is ManagedDownloadArtifactClaim.Acquired,
                is ManagedDownloadArtifactClaim.RepairRequired,
                null -> Unit
            }
            val attemptId = preparedArtifact.attemptId
            if (attemptId == null) {
                preparedArtifact.acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactClaim(session.context, song, leaseId)
                }
                return
            }
            if (preparedArtifact.requiresFinalizationRecovery) {
                session.enqueue(song, attemptId, preparedArtifact.operationId)
                return
            }
            if (shouldSkipDownload(session.context, song)) {
                session.skippedLocalSongs++
                preparedArtifact.acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactClaim(session.context, song, leaseId)
                }
                session.settledSongKeys += songKey
                session.settledAttemptIds[songKey] = attemptId
                markBatchDownloadPresentationTerminal(
                    songKey = songKey,
                    attemptId = attemptId,
                    terminalState = BatchDownloadTerminalState.COMPLETED,
                    batchId = session.batchPresentationId
                )
                NPLogger.d(TAG, "批量下载跳过本地歌曲: song=${song.name}, songKey=$songKey")
                return
            }
            val fastCachedSong = findFastCachedDownloadedSong(session.context, song)
            if (fastCachedSong != null) {
                settleFastCachedBatchDownload(
                    session = session,
                    song = song,
                    attemptId = attemptId,
                    acquiredLeaseId = preparedArtifact.acquiredLeaseId,
                    downloadedSong = fastCachedSong
                )
                return
            }
            val existingAudio = findExistingDownloadedAudio(
                context = session.context,
                song = song,
                snapshot = downloadLibrarySnapshot,
                allowStorageLookup = false
            )
            val needsFinalization = existingAudio?.let { audio ->
                isUnfinalizedDownloadedMetadata(readDownloadedMetadata(session.context, audio))
            } == true
            when (
                resolvePreExistingDownloadedAudioAction(
                    hasExistingAudio = existingAudio != null,
                    needsFinalization = needsFinalization
                )
            ) {
                PreExistingDownloadedAudioAction.FINALIZE_EXISTING -> {
                    session.enqueue(song, attemptId, preparedArtifact.operationId)
                    return
                }

                PreExistingDownloadedAudioAction.DIRECT_SETTLE -> {
                    val audio = requireNotNull(existingAudio)
                    settleExistingBatchDownload(
                        session = session,
                        song = song,
                        attemptId = attemptId,
                        acquiredLeaseId = preparedArtifact.acquiredLeaseId,
                        storedAudio = audio
                    )
                    return
                }

                PreExistingDownloadedAudioAction.CONTINUE_DOWNLOAD -> Unit
            }
            val operationId = session.operationIdsBySongKey[songKey]
            if (operationId == null) {
                preparedArtifact.acquiredLeaseId?.let { leaseId ->
                    releaseDownloadArtifactClaim(session.context, song, leaseId)
                }
                session.settledSongKeys += songKey
                session.settledAttemptIds[songKey] = attemptId
                markBatchDownloadPresentationTerminal(
                    songKey = songKey,
                    attemptId = attemptId,
                    terminalState = BatchDownloadTerminalState.FAILED,
                    batchId = session.batchPresentationId
                )
                NPLogger.w(TAG, "批量下载缺少持久化 operation: song=${song.name}")
                return
            }
            session.enqueue(song, attemptId, operationId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            handleBatchDownloadPreparationFailure(session, song)
            NPLogger.e(
                TAG,
                "批量下载单项准备失败: song=${song.name}, error=${error.message}",
                error
            )
        }
    }

    private suspend fun claimAndPrepareBatchArtifact(
        session: BatchDownloadSession,
        song: SongItem
    ): PreparedBatchArtifact {
        val songKey = song.stableKey()
        val operationId = session.operationIdsBySongKey[songKey]
            ?: throw IllegalStateException("batch item has no durable operation")
        val operationRequest = DownloadExecutionRoomStore.read(
            context = session.context,
            operationId = operationId
        )?.takeIf { request -> request.song.stableKey() == songKey }
            ?: throw IllegalStateException("batch item has no readable durable operation")
        val artifactClaim = try {
            managedDownloadArtifactCoordinator.claim(
                context = session.context,
                song = song,
                reconcileStorage = false,
                leaseOwnerId = operationRequest.artifactLeaseId
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "批量下载 artifact claim 失败，继续现有恢复路径: ${error.message}"
            )
            null
        }
        session.artifactClaims[songKey] = artifactClaim
        val requiresFinalizationRecovery = (
            artifactClaim as? ManagedDownloadArtifactClaim.AlreadyDownloaded
        )?.artifact?.state?.let(::requiresDownloadFinalizationRecovery) == true
        if (artifactClaim is ManagedDownloadArtifactClaim.Acquired) {
            artifactClaim.artifact.leaseId?.let { leaseId ->
                managedDownloadArtifactLeases[songKey] = leaseId
            }
        }
        val acquiredLeaseId = (artifactClaim as? ManagedDownloadArtifactClaim.Acquired)
            ?.artifact?.leaseId ?: (
            artifactClaim as? ManagedDownloadArtifactClaim.AlreadyDownloaded
            )?.takeIf { claim ->
            requiresFinalizationRecovery &&
                claim.artifact.leaseId == operationRequest.artifactLeaseId
        }?.artifact?.leaseId
        val attemptId = session.preparedAttemptIds[songKey]
        val requiresTask = artifactClaim !is ManagedDownloadArtifactClaim.AlreadyDownloaded ||
            requiresFinalizationRecovery
        val operationStillReusable = DownloadExecutionRoomStore.state(
            context = session.context,
            operationId = operationId
        ) in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
        if (
            requiresTask &&
                (
                    attemptId == null ||
                        !isDownloadRequestGenerationCurrent(songKey, session.requestGeneration) ||
                        !operationStillReusable
                    )
        ) {
            acquiredLeaseId?.let { leaseId ->
                releaseDownloadArtifactAfterExecutionOwnershipLoss(
                    context = session.context,
                    song = song,
                    operationId = operationId,
                    expectedLeaseId = leaseId
                )
            }
            session.artifactClaims.remove(songKey)
            attemptId?.let { preparedAttemptId ->
                session.settledAttemptIds[songKey] = preparedAttemptId
            }
            NPLogger.d(
                TAG,
                "跳过已过期批量任务: song=${song.name}, " +
                    "generation=${session.requestGeneration}, reusable=$operationStillReusable"
            )
            return PreparedBatchArtifact(
                operationId = operationId,
                artifactClaim = null,
                requiresFinalizationRecovery = false,
                acquiredLeaseId = null,
                attemptId = null
            )
        }
        return PreparedBatchArtifact(
            operationId = operationId,
            artifactClaim = artifactClaim,
            requiresFinalizationRecovery = requiresFinalizationRecovery,
            acquiredLeaseId = acquiredLeaseId,
            attemptId = attemptId
        )
    }

    private suspend fun settleFastCachedBatchDownload(
        session: BatchDownloadSession,
        song: SongItem,
        attemptId: Long,
        acquiredLeaseId: String?,
        downloadedSong: DownloadedSong
    ) {
        val songKey = song.stableKey()
        val repairedSong = repairDownloadedCoverIfMissing(
            context = session.context,
            song = song,
            downloadedSong = downloadedSong
        )
        session.settledSongKeys += songKey
        session.settledAttemptIds[songKey] = attemptId
        markBatchDownloadPresentationTerminal(
            songKey = songKey,
            attemptId = attemptId,
            terminalState = BatchDownloadTerminalState.COMPLETED,
            batchId = session.batchPresentationId
        )
        if (repairedSong != downloadedSong) {
            session.optimisticDownloadedSongs += repairedSong
        }
        resolveStoredAudio(session.context, song)?.let { storedAudio ->
            markDownloadArtifactFinalized(
                context = session.context,
                song = song,
                storedAudio = storedAudio,
                leaseId = acquiredLeaseId
            )
            acquiredLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases.remove(songKey, leaseId)
            }
        }
        NPLogger.d(
            TAG,
            "批量下载命中下载目录缓存并直接完成: song=${song.name}, songKey=$songKey"
        )
    }

    private suspend fun settleExistingBatchDownload(
        session: BatchDownloadSession,
        song: SongItem,
        attemptId: Long,
        acquiredLeaseId: String?,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) {
        val songKey = song.stableKey()
        session.settledSongKeys += songKey
        session.settledAttemptIds[songKey] = attemptId
        markBatchDownloadPresentationTerminal(
            songKey = songKey,
            attemptId = attemptId,
            terminalState = BatchDownloadTerminalState.COMPLETED,
            batchId = session.batchPresentationId
        )
        session.optimisticDownloadedSongs += repairDownloadedCoverIfMissing(
            context = session.context,
            song = song,
            downloadedSong = buildOptimisticDownloadedSong(
                song = song,
                storedAudio = storedAudio
            )
        )
        markDownloadArtifactFinalized(
            context = session.context,
            song = song,
            storedAudio = storedAudio,
            leaseId = acquiredLeaseId
        )
        acquiredLeaseId?.let { leaseId ->
            managedDownloadArtifactLeases.remove(songKey, leaseId)
        }
        NPLogger.d(
            TAG,
            "批量下载命中已存在音频并直接完成: song=${song.name}, " +
                "songKey=$songKey, file=${storedAudio.name}"
        )
    }

    private suspend fun handleBatchDownloadPreparationFailure(
        session: BatchDownloadSession,
        song: SongItem
    ) {
        val songKey = song.stableKey()
        val attemptId = session.preparedAttemptIds[songKey]
        val operationId = session.operationIdsBySongKey[songKey]
        if (attemptId != null && operationId != null) {
            handleBatchDownloadScheduleFailure(
                context = session.context,
                request = QueuedDownloadRequest(
                    song = song,
                    attemptId = attemptId,
                    operationId = operationId
                ),
                errorCode = "BATCH_ITEM_PREPARE_FAILED",
                expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
            )
            return
        }
        batchArtifactLeaseId(session, songKey)?.let { leaseId ->
            releaseDownloadArtifactClaim(session.context, song, leaseId)
        }
    }

    private suspend fun cancelPreparedBatchDownloadSession(session: BatchDownloadSession) {
        val cancelledSongKeys = mutableSetOf<String>()
        session.requestedSongs.forEach { song ->
            val songKey = song.stableKey()
            if (!isDownloadRequestGenerationCurrent(songKey, session.requestGeneration)) {
                return@forEach
            }
            selectBatchArtifactLeaseForCancellation(
                handedOff = songKey in session.handedOffSongKeys,
                capturedLeaseId = batchArtifactLeaseId(session, songKey)
            )?.let { leaseId ->
                releaseDownloadArtifactClaim(session.context, song, leaseId)
            }
            if (songKey in session.handedOffSongKeys) {
                return@forEach
            }
            if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                session.preparedAttemptIds[songKey]?.let { attemptId ->
                    updateTaskStatus(
                        songKey,
                        DownloadStatus.WAITING_NETWORK,
                        expectedAttemptId = attemptId
                    )
                }
                return@forEach
            }
            clearSongCancelled(songKey)
            session.preparedAttemptIds[songKey]?.let { attemptId ->
                markBatchDownloadPresentationTerminal(
                    songKey = songKey,
                    attemptId = attemptId,
                    terminalState = BatchDownloadTerminalState.CANCELLED,
                    batchId = session.batchPresentationId
                )
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
            }
            cancelledSongKeys += songKey
        }
        forgetPendingDownloadQueueEntriesIfCurrent(
            context = session.context,
            songKeys = cancelledSongKeys,
            generation = session.requestGeneration
        )
    }

    private suspend fun failPreparedBatchDownloadSession(
        session: BatchDownloadSession,
        error: Exception
    ) {
        NPLogger.e(TAG, "批量下载失败: ${error.message}", error)
        session.pendingSongs.forEach { request ->
            val songKey = request.song.stableKey()
            if (
                songKey in session.handedOffSongKeys ||
                    !isDownloadRequestGenerationCurrent(songKey, session.requestGeneration)
            ) {
                return@forEach
            }
            handleBatchDownloadScheduleFailure(
                context = session.context,
                request = request,
                errorCode = "BATCH_DOWNLOAD_FAILED",
                expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
            )
        }
    }

    private fun batchArtifactLeaseId(
        session: BatchDownloadSession,
        songKey: String
    ): String? {
        return (session.artifactClaims[songKey] as? ManagedDownloadArtifactClaim.Acquired)
            ?.artifact?.leaseId
    }

    private suspend fun schedulePendingBatchDownloads(
        session: BatchDownloadSession,
        pendingAttemptIds: Map<String, Long>
    ) {
        for (request in session.pendingSongs) {
            schedulePendingBatchDownload(
                session = session,
                request = request,
                pendingAttemptIds = pendingAttemptIds
            )
        }
    }

    private suspend fun schedulePendingBatchDownload(
        session: BatchDownloadSession,
        request: QueuedDownloadRequest,
        pendingAttemptIds: Map<String, Long>
    ) {
        val song = request.song
        val songKey = song.stableKey()
        if (!session.scheduledSongKeys.add(songKey)) {
            return
        }
        try {
            val admitted = downloadAdmissionGate.admit(session.admissionTicket) scheduleAdmission@{
                val operationId = request.operationId
                val persistedExecutionRequest = DownloadExecutionRoomStore.read(
                    context = session.context,
                    operationId = operationId
                )
                val operationState = DownloadExecutionRoomStore.state(
                    context = session.context,
                    operationId = operationId
                )
                when (resolveBatchOperationScheduleAction(
                    operationState = operationState,
                    requestMatchesSong = persistedExecutionRequest?.song?.stableKey() == songKey
                )) {
                    BatchOperationScheduleAction.HANDED_OFF -> {
                        session.handedOffSongKeys += songKey
                        NPLogger.d(
                            TAG,
                            "批量下载 operation 已由 OS 宿主接管: " +
                                "song=${song.name}, operationId=$operationId, state=$operationState"
                        )
                        return@scheduleAdmission
                    }

                    BatchOperationScheduleAction.INVALID -> {
                        handleBatchDownloadScheduleFailure(
                            context = session.context,
                            request = request,
                            errorCode = "OPERATION_NOT_SCHEDULABLE",
                            expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
                        )
                        NPLogger.w(
                            TAG,
                            "批量下载 operation 在调度前失效: " +
                                "song=${song.name}, operationId=$operationId"
                        )
                        return@scheduleAdmission
                    }

                    BatchOperationScheduleAction.RELEASE -> {
                        batchArtifactLeaseId(session, songKey)?.let { leaseId ->
                            releaseDownloadArtifactClaim(session.context, song, leaseId)
                        }
                        NPLogger.d(
                            TAG,
                            "批量下载 operation 已取消或停止，释放 artifact 租约: " +
                                "song=${song.name}, operationId=$operationId, state=$operationState"
                        )
                        return@scheduleAdmission
                    }

                    BatchOperationScheduleAction.SETTLED -> {
                        batchArtifactLeaseId(session, songKey)?.let { leaseId ->
                            managedDownloadArtifactLeases.remove(songKey, leaseId)
                        }
                        NPLogger.d(
                            TAG,
                            "批量下载 operation 状态已收敛，跳过重复调度: " +
                                "song=${song.name}, operationId=$operationId, state=$operationState"
                        )
                        return@scheduleAdmission
                    }

                    BatchOperationScheduleAction.SCHEDULE -> Unit
                }
                val executionRequest = requireNotNull(persistedExecutionRequest).copy(
                    preserveStaging = resolveDownloadPreserveStaging(
                        persistedPreserveStaging = persistedExecutionRequest.preserveStaging,
                        preserveRequested = !session.cleanupBeforeStart
                    ),
                    attemptId = pendingAttemptIds[songKey],
                    userInitiated = session.userInitiated
                )
                val schedule = DownloadExecutionHosts.default.schedule(
                    context = session.context,
                    request = executionRequest
                )
                if (schedule is DownloadExecutionSchedule.Deferred) {
                    session.handedOffSongKeys += songKey
                    NPLogger.d(
                        TAG,
                        "批量下载已登记等待全局宿主槽位: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                } else if (schedule is DownloadExecutionSchedule.Rejected) {
                    val wasHandedOff = handleBatchDownloadScheduleFailure(
                        context = session.context,
                        request = request,
                        errorCode = "OS_HOST_REJECTED",
                        expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
                    )
                    if (wasHandedOff) {
                        session.handedOffSongKeys += songKey
                        NPLogger.d(
                            TAG,
                            "批量下载调度竞态中 operation 已被接管: " +
                                "song=${song.name}, operationId=$operationId"
                        )
                    } else {
                        NPLogger.w(
                            TAG,
                            "批量下载宿主调度失败: " +
                                "song=${song.name}, reason=${schedule.reason}"
                        )
                    }
                } else {
                    session.handedOffSongKeys += songKey
                    NPLogger.d(
                        TAG,
                        "批量下载已交给 OS 宿主: song=${song.name}, operationId=$operationId"
                    )
                }
            }
            if (!admitted) {
                batchArtifactLeaseId(session, songKey)?.let { leaseId ->
                    releaseDownloadArtifactAfterExecutionOwnershipLoss(
                        context = session.context,
                        song = song,
                        operationId = request.operationId,
                        expectedLeaseId = leaseId
                    )
                }
                session.artifactClaims.remove(songKey)
                NPLogger.d(
                    TAG,
                    "跳过过期批量宿主调度: song=${song.name}, " +
                        "operationId=${request.operationId}"
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val latestState = runCatching {
                DownloadExecutionRoomStore.state(session.context, request.operationId)
            }.getOrNull()
            val latestRequest = runCatching {
                DownloadExecutionRoomStore.read(session.context, request.operationId)
            }.getOrNull()
            if (
                latestState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES &&
                    latestRequest?.song?.stableKey() == songKey
            ) {
                session.handedOffSongKeys += songKey
                NPLogger.d(
                    TAG,
                    "批量下载单项异常时 operation 已被接管: " +
                        "song=${song.name}, operationId=${request.operationId}"
                )
                return
            }
            handleBatchDownloadScheduleFailure(
                context = session.context,
                request = request,
                errorCode = "BATCH_ITEM_SCHEDULE_FAILED",
                expectedArtifactLeaseId = batchArtifactLeaseId(session, songKey)
            )
            NPLogger.e(
                TAG,
                "批量下载单项调度失败: song=${song.name}, error=${error.message}",
                error
            )
        }
    }

    private fun recoverInFlightDownloadOperations(
        context: Context,
        requests: Collection<DownloadExecutionRequest>,
        admissionTicket: Long
    ) {
        val distinctRequests = requests.distinctBy(DownloadExecutionRequest::operationId)
        if (distinctRequests.isEmpty()) return
        val appContext = context.applicationContext
        val recoveryJob = scope.launch {
            for (candidate in distinctRequests) {
                var schedule: DownloadExecutionSchedule? = null
                val admitted = downloadAdmissionGate.admit(admissionTicket) recoveryAdmission@{
                    val latest = DownloadExecutionRoomStore.read(
                        context = appContext,
                        operationId = candidate.operationId
                    ) ?: return@recoveryAdmission
                    val state = DownloadExecutionRoomStore.state(
                        context = appContext,
                        operationId = candidate.operationId
                    )
                    if (
                        latest.song.stableKey() != candidate.song.stableKey() ||
                            !canScheduleRecoveredDownloadOperation(state)
                    ) {
                        return@recoveryAdmission
                    }
                    schedule = DownloadExecutionHosts.default.schedule(
                        context = appContext,
                        request = latest
                    )
                }
                if (!admitted) {
                    NPLogger.d(
                        TAG,
                        "清空任务已使遗留 operation 恢复请求过期: " +
                            "operationId=${candidate.operationId}"
                    )
                    return@launch
                }
                when (val result = schedule) {
                    is DownloadExecutionSchedule.Scheduled -> {
                        NPLogger.d(
                            TAG,
                            "已重新交给 OS 宿主的遗留下载 operation: " +
                                "song=${candidate.song.name}, " +
                                "operationId=${candidate.operationId}, backend=${result.backend}"
                        )
                    }

                    is DownloadExecutionSchedule.Rejected -> {
                        NPLogger.w(
                            TAG,
                            "遗留下载 operation 恢复调度被拒绝，保留状态等待下次恢复: " +
                                "song=${candidate.song.name}, " +
                                "operationId=${candidate.operationId}, reason=${result.reason}"
                        )
                    }

                    is DownloadExecutionSchedule.Deferred -> {
                        NPLogger.d(
                            TAG,
                            "遗留下载已登记等待全局宿主槽位: " +
                                "song=${candidate.song.name}, " +
                                "operationId=${candidate.operationId}"
                        )
                    }

                    null -> Unit
                }
            }
        }
        registerActiveBatchDownloadJob(recoveryJob)
    }

    private fun registerActiveBatchDownloadJob(job: Job) {
        activeBatchDownloadJobs += job
        taskStore.setActiveBatchDownloadJobCount(activeBatchDownloadJobs.size)
        job.invokeOnCompletion {
            activeBatchDownloadJobs.remove(job)
            taskStore.setActiveBatchDownloadJobCount(activeBatchDownloadJobs.size)
        }
    }

    private suspend fun handleBatchDownloadScheduleFailure(
        context: Context,
        request: QueuedDownloadRequest,
        errorCode: String,
        expectedArtifactLeaseId: String?
    ): Boolean {
        val songKey = request.song.stableKey()
        val retryMarked = runCatching {
            DownloadExecutionRoomStore.markScheduleRejectedRetryable(
                context = context,
                operationId = request.operationId,
                stableKey = songKey,
                errorCode = errorCode
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入批量 operation 重试状态失败: ${error.message}")
        }.getOrDefault(false)
        if (!retryMarked) {
            val latestState = runCatching {
                DownloadExecutionRoomStore.state(context, request.operationId)
            }.getOrNull()
            val latestRequest = runCatching {
                DownloadExecutionRoomStore.read(context, request.operationId)
            }.getOrNull()
            if (
                latestState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES &&
                    latestRequest?.song?.stableKey() == songKey
            ) {
                return true
            }
        }
        updateTaskStatus(
            songKey,
            DownloadStatus.FAILED,
            expectedAttemptId = request.attemptId
        )
        runCatching {
            expectedArtifactLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases.remove(songKey, leaseId)
            }
            markDownloadArtifactRetryable(
                context = context,
                song = request.song,
                leaseId = expectedArtifactLeaseId,
                errorCode = errorCode
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "释放批量 artifact 租约失败: ${error.message}")
        }
        return false
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
            scheduleUserDownload(context, song, skipTrafficRiskPrompt = true)
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
        val coverEvidence = downloadedSong.coverPath?.let { reference ->
            ManagedDownloadReferenceLookup.inspect(context, reference)
        }
        if (
            coverEvidence is ManagedDownloadReferenceLookup.Result.PermissionLost ||
            coverEvidence is ManagedDownloadReferenceLookup.Result.ProviderFailure
        ) {
            NPLogger.w(
                TAG,
                "封面引用暂不可确认，跳过替换: song=${song.name}, evidence=$coverEvidence"
            )
            return downloadedSong
        }
        val existingCoverAccessible =
            coverEvidence is ManagedDownloadReferenceLookup.Result.Present
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
                ManagedDownloadReferenceLookup.inspect(context, reference) is
                    ManagedDownloadReferenceLookup.Result.Present
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
            if (isDurableCoreArtifactState(unfinalizedMetadata.artifactState)) {
                NPLogger.w(
                    TAG,
                    "core committed 音频暂时不可读，禁止验证路径删除: " +
                        "song=${song.name}, file=${audio.name}"
                )
                return audio
            }
            val evidence = ManagedDownloadReferenceLookup.inspect(
                context = context,
                reference = audio.reference
            )
            if (!ManagedDownloadReferenceLookup.canMarkMissing(evidence)) {
                NPLogger.w(
                    TAG,
                    "未最终确认音频不可读但缺少 Missing 证据，保留: " +
                        "song=${song.name}, file=${audio.name}, evidence=$evidence"
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
        if (isDurableCoreArtifactState(metadata?.artifactState)) {
            NPLogger.w(
                TAG,
                "core committed 音频暂时不可读，重试清理保留: song=${song.name}, file=${audio.name}"
            )
            return
        }
        val evidence = ManagedDownloadReferenceLookup.inspect(
            context = context,
            reference = audio.reference
        )
        if (!ManagedDownloadReferenceLookup.canMarkMissing(evidence)) {
            NPLogger.w(
                TAG,
                "重试清理缺少 Missing 证据，保留音频: song=${song.name}, evidence=$evidence"
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
        val updated = taskStore.updateTaskStatus(
            songKey = songKey,
            status = status,
            expectedAttemptId = expectedAttemptId
        )
        if (!updated) {
            return
        }
        when (status) {
            DownloadStatus.COMPLETED -> markBatchDownloadPresentationTerminal(
                songKey = songKey,
                attemptId = expectedAttemptId,
                terminalState = BatchDownloadTerminalState.COMPLETED
            )

            DownloadStatus.FAILED -> markBatchDownloadPresentationTerminal(
                songKey = songKey,
                attemptId = expectedAttemptId,
                terminalState = BatchDownloadTerminalState.FAILED
            )

            DownloadStatus.CANCELLED -> markBatchDownloadPresentationTerminal(
                songKey = songKey,
                attemptId = expectedAttemptId,
                terminalState = BatchDownloadTerminalState.CANCELLED
            )

            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.WAITING_NETWORK -> Unit
        }
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
        songs: List<SongItem>,
        userInitiated: Boolean = false
    ): List<String> {
        if (songs.isEmpty()) {
            return emptyList()
        }
        return ManagedDownloadStorage.upsertPendingDownloadQueue(
            context = context,
            songs = songs,
            userInitiated = userInitiated
        )
    }

    private fun beginDownloadRequestGeneration(songs: Collection<SongItem>): Long {
        val snapshot = requestGenerationTracker.begin(songs)
        NPLogger.d(TAG, "登记下载请求代际: generation=${snapshot.generation}, songs=${snapshot.songCount}")
        return snapshot.generation
    }

    private fun beginBatchDownloadPresentation(songs: Collection<SongItem>): Long {
        val memberAttemptIds = songs
            .asSequence()
            .map(SongItem::stableKey)
            .filter(String::isNotBlank)
            .distinct()
            .associateWith { null }
        val batchId = batchDownloadPresentationIdGenerator.incrementAndGet()
        _batchDownloadPresentation.value = BatchDownloadPresentationState(
            id = batchId,
            memberAttemptIds = memberAttemptIds
        )
        return batchId
    }

    private fun bindBatchDownloadPresentationAttempts(
        batchId: Long,
        attemptIdsBySongKey: Map<String, Long>
    ) {
        if (attemptIdsBySongKey.isEmpty()) {
            return
        }
        _batchDownloadPresentation.update { presentation ->
            if (presentation?.id != batchId) {
                return@update presentation
            }
            val updatedMemberAttemptIds = presentation.memberAttemptIds.mapValues { (songKey, attemptId) ->
                attemptIdsBySongKey[songKey]?.takeIf { candidate -> candidate > 0L } ?: attemptId
            }
            presentation.copy(memberAttemptIds = updatedMemberAttemptIds)
        }
    }

    private fun markBatchDownloadPresentationTerminal(
        songKey: String,
        attemptId: Long?,
        terminalState: BatchDownloadTerminalState,
        batchId: Long? = null
    ) {
        if (attemptId == null || attemptId <= 0L) {
            return
        }
        var completedBatchId: Long? = null
        _batchDownloadPresentation.update { presentation ->
            if (presentation == null || (batchId != null && presentation.id != batchId)) {
                return@update presentation
            }
            val memberAttemptId = presentation.memberAttemptIds[songKey]
            if (
                songKey !in presentation.memberAttemptIds ||
                    (memberAttemptId != null && memberAttemptId != attemptId)
            ) {
                return@update presentation
            }
            val updatedMemberAttemptIds = if (memberAttemptId == null) {
                presentation.memberAttemptIds + (songKey to attemptId)
            } else {
                presentation.memberAttemptIds
            }
            val updatedTerminalStates = presentation.terminalStates + (songKey to terminalState)
            val updatedPresentation = presentation.copy(
                memberAttemptIds = updatedMemberAttemptIds,
                terminalStates = updatedTerminalStates
            )
            if (updatedTerminalStates.size == updatedPresentation.memberAttemptIds.size) {
                completedBatchId = updatedPresentation.id
            }
            updatedPresentation
        }
        completedBatchId?.let(::scheduleCompletedBatchDownloadPresentationRemoval)
    }

    private fun scheduleCompletedBatchDownloadPresentationRemoval(batchId: Long) {
        scope.launch {
            delay(DOWNLOAD_TASK_COMPLETED_RETENTION_MS)
            _batchDownloadPresentation.update { presentation ->
                if (
                    presentation?.id == batchId &&
                        presentation.terminalStates.size == presentation.memberAttemptIds.size
                ) {
                    null
                } else {
                    presentation
                }
            }
        }
    }

    private fun clearBatchDownloadPresentation(batchId: Long? = null) {
        _batchDownloadPresentation.update { presentation ->
            if (batchId == null || presentation?.id == batchId) {
                null
            } else {
                presentation
            }
        }
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

    private suspend fun clearSongCancellationForFreshStart(
        context: Context,
        songKeys: Collection<String>
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        cancelledSongKeys.removeAll(keys)
        // clear the old terminal operation before writing the replacement queue entry
        DownloadExecutionRoomStore.purgeCancelled(context.applicationContext, keys)
        runCatching {
            DownloadExecutionRoomStore.prepareExplicitResumesForStableKeys(
                context = context.applicationContext,
                stableKeys = keys
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "恢复用户停止的 operation 失败: ${error.message}")
        }
    }

    private fun markSongCancelled(songKey: String) {
        cancelledSongKeys.add(songKey)
    }

    private fun requestOperationCancellation(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        runCatching {
            val context = AppContainer.applicationContext
            val operationStore = DownloadExecutionOperationStore()
            keys.forEach { songKey ->
                operationStore.findOperationIdsForSong(context, songKey)
                    .forEach { operationId ->
                        operationStore.requestCancel(context, operationId)
                    }
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "记录下载 operation 取消状态失败: count=${keys.size}, ${error.message}")
        }
    }

    fun clearSongCancelled(songKey: String) {
        cancelledSongKeys.remove(songKey)
    }

    fun cancelDownloadTask(songKey: String) {
        val appContext = AppContainer.applicationContext
        val task = taskStore.findTask(songKey)
        val operationId = DownloadExecutionHosts.default.operationIdForSong(appContext, songKey)
        val operationRequest = operationId
            ?.let { id -> DownloadExecutionOperationStore().read(appContext, id) }
            ?.takeIf { request -> request.song.stableKey() == songKey }
        if (task == null) {
            markSongCancelled(songKey)
            if (operationId != null) {
                DownloadExecutionHosts.default.cancel(appContext, operationId)
            } else {
                DownloadExecutionHosts.default.cancelForSong(appContext, songKey)
            }
            requestOperationCancellation(setOf(songKey))
            val cancellationGeneration = requestGenerationTracker.cancellationGeneration(songKey)
            invalidateDownloadRequestGenerations(setOf(songKey))
            scope.launch {
                awaitSongCancellationSettled(
                    songKey = songKey,
                    timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS,
                    clearCancellationWhenSettled = false
                )
                if (isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
                    if (operationId != null && operationRequest != null) {
                        releaseDownloadArtifactAfterExecutionOwnershipLoss(
                            context = appContext,
                            song = operationRequest.song,
                            operationId = operationId,
                            expectedLeaseId = operationRequest.artifactLeaseId
                        )
                    }
                    ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
                        appContext,
                        setOf(songKey)
                    )
                    DownloadExecutionRoomStore.purgeCancelled(appContext, setOf(songKey))
                    clearSongCancelled(songKey)
                }
            }
            return
        }
        if (!isDownloadTaskCancellationCandidate(task)) {
            return
        }

        if (operationId != null) {
            DownloadExecutionHosts.default.cancel(appContext, operationId)
        } else {
            DownloadExecutionHosts.default.cancelForSong(appContext, songKey)
        }
        markSongCancelled(songKey)
        requestOperationCancellation(setOf(songKey))
        markBatchDownloadPresentationTerminal(
            songKey = songKey,
            attemptId = task.attemptId,
            terminalState = BatchDownloadTerminalState.CANCELLED
        )
        removeDownloadTask(songKey, expectedAttemptId = task.attemptId)
        forgetPendingDownloadQueueEntries(appContext, setOf(songKey))
        val cancellationGeneration = requestGenerationTracker.cancellationGeneration(songKey)
        invalidateDownloadRequestGenerations(setOf(songKey))
        scope.launch {
            cancelDownloadTaskInBackground(
                task = task,
                cancellationGeneration = cancellationGeneration,
                operationRequest = operationRequest
            )
        }
    }

    fun clearAllDownloadTasks() {
        cancelAllDownloadTasks()
    }

    fun cancelAllDownloadTasks() {
        requestAllDownloadTaskCancellation()
    }

    private fun requestAllDownloadTaskCancellation(): Job {
        val appContext = AppContainer.applicationContext
        clearBatchDownloadPresentation()
        val clearToken = downloadAdmissionGate.beginClear()
        val clearFenceEpoch = if (clearToken.ownsClear) {
            PersistentDownloadClearFenceStore.beginClear()
        } else {
            null
        }
        downloadClearVisibility.begin(clearToken)
        _mobileDataDownloadInterruptionRequest.value = null
        if (!clearToken.ownsClear) {
            return scope.launch {
                downloadAdmissionGate.awaitClear(clearToken)
            }
        }
        return scope.launch {
            try {
                activateDownloadClearFence(appContext)
                stopDownloadExecutionImmediately(
                    context = appContext,
                    reason = "download clear fence activated"
                )
                val initiallyVisibleTasks = taskStore.currentTasks()
                val initiallyCancellationTasks = initiallyVisibleTasks
                    .filter(::isDownloadTaskCancellationCandidate)
                taskStore.clearAllTasks()
                downloadAdmissionGate.runClear(clearToken) {
                    val cancellationTasksBySongKey = linkedMapOf<String, DownloadTask>()
                    val clearOperationIds = linkedSetOf<String>()
                    val clearOperationRequests = linkedMapOf<String, DownloadExecutionRequest>()
                    val clearSongKeys = linkedSetOf<String>()
                    val clearWorkingFilesBySongKey = linkedMapOf<String, MutableSet<File>>()
                    val clearBatchJobs = linkedSetOf<Job>()
                    val cancellationGenerations = linkedMapOf<String, Long?>()
                    var initialHostCancellationSnapshotCaptured = false
                    while (true) {
                        try {
                            val lateVisibleTasks = taskStore.currentTasks()
                            val lateCancellationTasks = lateVisibleTasks
                                .filter(::isDownloadTaskCancellationCandidate)
                            (initiallyCancellationTasks + lateCancellationTasks).forEach { task ->
                                cancellationTasksBySongKey[task.song.stableKey()] = task
                            }
                            clearBatchJobs += activeBatchDownloadJobs
                            val cancellationTasks = cancellationTasksBySongKey.values.toList()
                            taskStore.clearAllTasks()
                            val visibleSongKeys = (initiallyVisibleTasks + lateVisibleTasks)
                                .mapTo(linkedSetOf()) { task -> task.song.stableKey() }
                            val allOperationIdentities = DownloadExecutionRoomStore
                                .listAllOperationIdentities(appContext)
                            val newlyDiscoveredOperationIds = linkedSetOf<String>()
                            allOperationIdentities.forEach { identity ->
                                if (clearOperationIds.add(identity.operationId)) {
                                    newlyDiscoveredOperationIds += identity.operationId
                                }
                                clearSongKeys += identity.stableKey
                            }
                            val pendingWorkingDownloads =
                                ManagedDownloadStorage.listPendingResumableDownloads(appContext)
                            pendingWorkingDownloads.forEach { pendingDownload ->
                                val songKey = pendingDownload.song.stableKey()
                                clearSongKeys += songKey
                                clearWorkingFilesBySongKey
                                    .getOrPut(songKey) { linkedSetOf() }
                                    .add(pendingDownload.workingFile)
                            }
                            val cancellationSnapshot = requestAllDownloadOperationCancellation(appContext)
                            val cancellationCandidates = cancellationSnapshot.entries
                            cancellationSnapshot.operationIds.forEach { operationId ->
                                if (clearOperationIds.add(operationId)) {
                                    newlyDiscoveredOperationIds += operationId
                                }
                            }
                            cancellationCandidates.forEach { entry ->
                                clearOperationRequests[entry.request.operationId] = entry.request
                            }
                            clearSongKeys += visibleSongKeys
                            clearSongKeys += cancellationSnapshot.stableKeys
                            val newlyDiscoveredSongKeys = clearSongKeys.filterTo(linkedSetOf()) { songKey ->
                                songKey !in cancellationGenerations
                            }
                            newlyDiscoveredSongKeys.forEach(::markSongCancelled)
                            val newlyDiscoveredCancellationGenerations =
                                requestGenerationTracker.cancellationGenerations(
                                    newlyDiscoveredSongKeys
                                )
                            invalidateDownloadRequestGenerations(newlyDiscoveredSongKeys)
                            newlyDiscoveredCancellationGenerations.forEach { (songKey, generation) ->
                                cancellationGenerations.putIfAbsent(songKey, generation)
                            }
                            clearPendingDownloadQueue(appContext)
                            if (
                                initialHostCancellationSnapshotCaptured &&
                                    newlyDiscoveredOperationIds.isNotEmpty()
                            ) {
                                DownloadExecutionHosts.default.cancelAll(
                                    context = appContext,
                                    operationIds = newlyDiscoveredOperationIds
                                )
                            }
                            initialHostCancellationSnapshotCaptured = true
                            val finalizedCancellationCount =
                                DownloadExecutionRoomStore.finalizeRequestedCancellations(
                                    context = appContext,
                                    operationIds = clearOperationIds
                                )
                            clearBatchJobs.forEach { job ->
                                job.cancel(CancellationException("cancel all download tasks"))
                            }
                            AudioDownloadManager.cancelDownload()
                            taskStore.clearAllTasks()
                            NPLogger.d(
                                TAG,
                                "取消全部下载任务: memory=${cancellationTasks.size}, " +
                                    "operations=${cancellationCandidates.size}, " +
                                    "finalized=$finalizedCancellationCount, " +
                                    "batchJobs=${clearBatchJobs.size}"
                            )
                            val settlement = cancelDownloadTasksInBackground(
                                context = appContext,
                                tasks = cancellationTasks,
                                batchJobs = clearBatchJobs,
                                additionalSongKeys = clearSongKeys,
                                cancelledSongKeysSnapshot = clearSongKeys,
                                cancellationGenerations = cancellationGenerations,
                                operationRequests = clearOperationRequests.values,
                                executionOperationIds = clearOperationIds,
                                workingFilesBySongKey = clearWorkingFilesBySongKey
                            )
                            if (!settlement.isSettled) {
                                NPLogger.w(
                                    TAG,
                                    "下载清空仍在等待执行收敛: activeSongs=" +
                                        "${settlement.activeSongKeys.size}, activeOperations=" +
                                        "${settlement.activeOperationIds.size}, " +
                                        "batchJobsSettled=${settlement.batchJobsSettled}, " +
                                        "residualWorking=${settlement.residualWorkingSongKeys.size}"
                                )
                                taskStore.clearAllTasks()
                                stopDownloadExecutionImmediately(
                                    context = appContext,
                                    reason = "waiting for download clear convergence"
                                )
                                delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                                continue
                            }
                            val deletedOperationCount =
                                DownloadExecutionRoomStore.purgeFullyClearedOperations(
                                    context = appContext,
                                    operationIds = clearOperationIds
                                )
                            clearSongKeys.forEach(::clearSongCancelled)
                            NPLogger.d(
                                TAG,
                                "下载清空已物理移除持久任务: operations=$deletedOperationCount, " +
                                    "songs=${clearSongKeys.size}"
                            )
                            taskStore.clearAllTasks()
                            return@runClear
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            NPLogger.e(
                                TAG,
                                "下载清空流程失败，保持栅栏并重试: ${error.message}",
                                error
                            )
                            taskStore.clearAllTasks()
                            stopDownloadExecutionImmediately(
                                context = appContext,
                                reason = "retrying failed download clear"
                            )
                            delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                        }
                    }
                }
                clearDownloadClearFence(
                    context = appContext,
                    expectedEpoch = requireNotNull(clearFenceEpoch)
                )
            } finally {
                downloadClearVisibility.finish(clearToken)
            }
        }
    }

    private suspend fun activateDownloadClearFence(context: Context) {
        var retryRound = 0
        while (!PersistentDownloadClearFenceStore.activate(context)) {
            retryRound += 1
            NPLogger.w(
                TAG,
                "下载清空栅栏落盘失败，保持清空状态并重试: retryRound=$retryRound"
            )
            stopDownloadExecutionImmediately(
                context = context,
                reason = "waiting for durable download clear fence"
            )
            delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
        }
    }

    private fun stopDownloadExecutionImmediately(context: Context, reason: String) {
        runDownloadClearStopAction("取消全部下载宿主") {
            DownloadExecutionHosts.cancelAllOwned(context)
        }
        activeBatchDownloadJobs.toList().forEach { job ->
            runDownloadClearStopAction("取消批量下载协程") {
                job.cancel(CancellationException(reason))
            }
        }
        runDownloadClearStopAction("取消音频下载") {
            AudioDownloadManager.cancelDownload()
        }
    }

    private fun runDownloadClearStopAction(
        action: String,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(TAG, "$action 失败: ${error.message}", error)
        }
    }

    private suspend fun clearDownloadClearFence(
        context: Context,
        expectedEpoch: Long
    ) {
        var retryRound = 0
        while (true) {
            when (
                PersistentDownloadClearFenceStore.clearIfCurrent(
                    context = context,
                    expectedEpoch = expectedEpoch
                )
            ) {
                DownloadClearFenceReleaseResult.RELEASED,
                DownloadClearFenceReleaseResult.SUPERSEDED -> return

                DownloadClearFenceReleaseResult.FAILED -> {
                    retryRound += 1
                    NPLogger.w(
                        TAG,
                        "下载清空栅栏移除失败，继续阻止新下载并重试: retryRound=$retryRound"
                    )
                    stopDownloadExecutionImmediately(
                        context = context,
                        reason = "download clear fence still active"
                    )
                    delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun cancelAllDownloadTasksAndWait() {
        requestAllDownloadTaskCancellation().join()
    }

    private suspend fun requestAllDownloadOperationCancellation(
        context: Context
    ): DownloadExecutionRoomStore.CancellationSnapshot {
        var retryRound = 0
        while (true) {
            repeat(DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS) { attempt ->
                try {
                    return DownloadExecutionRoomStore.requestCancelAll(context)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "批量标记持久下载取消失败: attempt=${attempt + 1}/" +
                            "$DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS, error=${error.message}",
                        error
                    )
                    if (attempt + 1 < DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS) {
                        delay(DOWNLOAD_CANCEL_JOURNAL_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
            }
            retryRound += 1
            NPLogger.w(
                TAG,
                "批量取消等待持久化存储恢复: retryRound=$retryRound"
            )
            stopDownloadExecutionImmediately(
                context = context,
                reason = "waiting for durable download cancellation"
            )
            delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
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
            if (isDownloadClearFenceActive(appContext)) {
                return@launch
            }
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
        cancellationGeneration: Long?,
        operationRequest: DownloadExecutionRequest?
    ) {
        val appContext = AppContainer.applicationContext
        val songKey = task.song.stableKey()
        if (isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            requestOperationCancellation(setOf(songKey))
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
            operationRequest?.let { request ->
                releaseDownloadArtifactAfterExecutionOwnershipLoss(
                    context = appContext,
                    song = request.song,
                    operationId = request.operationId,
                    expectedLeaseId = request.artifactLeaseId
                )
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
        cancellationGenerations: Map<String, Long?> = emptyMap(),
        operationRequests: Collection<DownloadExecutionRequest> = emptyList(),
        executionOperationIds: Collection<String> = operationRequests.map(
            DownloadExecutionRequest::operationId
        ),
        workingFilesBySongKey: Map<String, Collection<File>> = emptyMap()
    ): DownloadClearSettlement {
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
        val activeDownloadTaskKeys = tasks.mapNotNullTo(linkedSetOf()) { task ->
            val songKey = task.song.stableKey()
            when {
                songKey !in activeKeys -> null
                task.status == DownloadStatus.DOWNLOADING -> songKey
                AudioDownloadManager.isSongDownloadActive(songKey) -> songKey
                else -> null
            }
        }
        currentCancellationKeys
            .filter(AudioDownloadManager::isSongDownloadActive)
            .forEach(activeDownloadTaskKeys::add)
        val activeSongKeys = awaitDownloadCancellationsSettled(activeDownloadTaskKeys)
        val batchJobsSettled = awaitBatchDownloadJobsAfterCancellation(
            batchJobs = batchJobs,
            phase = "clear_all_background_cleanup"
        )
        val activeOperationIds = executionOperationIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(DownloadExecutionHosts.default::isExecuting)
            .toSet()
        if (activeSongKeys.isNotEmpty() || activeOperationIds.isNotEmpty() || !batchJobsSettled) {
            return DownloadClearSettlement(
                activeSongKeys = activeSongKeys,
                activeOperationIds = activeOperationIds,
                batchJobsSettled = batchJobsSettled
            )
        }
        operationRequests.distinctBy(DownloadExecutionRequest::operationId).forEach { request ->
            val songKey = request.song.stableKey()
            if (!isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])) {
                NPLogger.d(
                    TAG,
                    "跳过过期 operation 租约取消: " +
                        "song=${request.song.name}, operationId=${request.operationId}"
                )
                return@forEach
            }
            releaseDownloadArtifactAfterExecutionOwnershipLoss(
                context = appContext,
                song = request.song,
                operationId = request.operationId,
                expectedLeaseId = request.artifactLeaseId
            )
        }
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
                    keepCancellationOperation = true
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
        workingFilesBySongKey.forEach { (songKey, workingFiles) ->
            if (songKey !in batchCleanupKeys) return@forEach
            workingFiles.forEach(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)
        }
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
            appContext,
            batchCleanupKeys
        )
        val pendingWorkingSongKeys = ManagedDownloadStorage
            .listPendingResumableDownloads(appContext)
            .mapTo(linkedSetOf()) { pendingDownload ->
                pendingDownload.song.stableKey()
            }
        val residualWorkingSongKeys = activeKeys
            .filter { songKey ->
                isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])
            }
            .filterTo(linkedSetOf()) { songKey ->
                val rememberedFiles = workingFilesBySongKey[songKey].orEmpty()
                rememberedFiles.any(::hasWorkingDownloadArtifact) ||
                    songKey in pendingWorkingSongKeys
            }
        return DownloadClearSettlement(
            activeSongKeys = emptySet(),
            activeOperationIds = emptySet(),
            batchJobsSettled = true,
            residualWorkingSongKeys = residualWorkingSongKeys
        )
    }

    private fun hasWorkingDownloadArtifact(workingFile: File): Boolean {
        return workingFile.exists() ||
            ManagedDownloadStorage.buildWorkingResumeMetadataFile(workingFile).exists() ||
            ManagedDownloadStorage.buildWorkingHlsCheckpointFile(workingFile).exists()
    }

    private suspend fun awaitDownloadCancellationsSettled(songKeys: Set<String>): Set<String> {
        if (songKeys.isEmpty()) {
            return emptySet()
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
        return stuckKeys.toSet()
    }

    private suspend fun awaitBatchDownloadJobsAfterCancellation(
        batchJobs: Collection<Job>,
        phase: String
    ): Boolean {
        val settled = awaitBatchDownloadJobsSettled(
            jobs = batchJobs,
            timeoutMs = DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS
        )
        if (!settled) {
            NPLogger.w(
                TAG,
                "等待批量下载协程取消收敛超时: phase=$phase, " +
                    "jobs=${batchJobs.size}, timeoutMs=$DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS"
            )
        }
        return settled
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
        activeKeys.forEach { songKey ->
            DownloadExecutionHosts.default.stopForSong(
                context = context.applicationContext,
                songKey = songKey,
                preventReschedule = false
            )
        }
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

        DownloadExecutionHosts.default.cancelForSong(
            context = context.applicationContext,
            songKey = songKey
        )
        clearSongCancelled(songKey)
        removeDownloadTask(songKey, expectedAttemptId = task.attemptId)
        scheduleUserDownload(
            context = context,
            song = task.song,
            skipTrafficRiskPrompt = false,
            preserveStaging = task.status == DownloadStatus.WAITING_NETWORK
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
        scope.launch {
            DownloadExecutionRoomStore.purgeCancelled(appContext, clearableKeys)
        }
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
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(songKey.toByteArray(Charsets.UTF_8))
        val stripeValue = ((digest[0].toLong() and 0xffL) shl 24) or
            ((digest[1].toLong() and 0xffL) shl 16) or
            ((digest[2].toLong() and 0xffL) shl 8) or
            (digest[3].toLong() and 0xffL)
        val index = (stripeValue % songExecutionLocks.size).toInt()
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
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
        state: String = "FINALIZED"
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
        scope.launch {
            runCatching {
                ManagedLibraryItemRoomStore.upsert(
                    context = context.applicationContext,
                    song = song,
                    audio = storedAudio,
                    state = state
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "更新 managed_library_item 预览失败: ${error.message}")
            }
        }
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
