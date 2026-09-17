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

import moe.ouom.neriplayer.core.download.artifact.*
import moe.ouom.neriplayer.core.download.manager.admission.*
import moe.ouom.neriplayer.core.download.manager.batch.*
import moe.ouom.neriplayer.core.download.manager.catalog.*
import moe.ouom.neriplayer.core.download.manager.facade.*
import moe.ouom.neriplayer.core.download.manager.runtime.*
import moe.ouom.neriplayer.core.download.model.*
import moe.ouom.neriplayer.core.download.policy.*
import moe.ouom.neriplayer.core.download.storage.facade.*
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactCoordinator
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogDelta
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogIndex
import moe.ouom.neriplayer.core.download.enrichment.AssetEnrichmentCoordinator
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildToken
import moe.ouom.neriplayer.core.download.metadata.RestorableMetadataClearPolicy
import moe.ouom.neriplayer.core.download.reconcile.ManagedLibraryReconciler
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.DownloadProgressProjectionStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局下载管理器, 统一维护下载任务和本地下载列表
 */
object GlobalDownloadManager {
    internal const val TAG = "GlobalDownloadManager"
    internal const val DOWNLOAD_SOURCE_UNAVAILABLE_ERROR_CODE = "DOWNLOAD_SOURCE_UNAVAILABLE"
    internal const val DOWNLOAD_NETWORK_UNAVAILABLE_ERROR_CODE = "NETWORK_UNAVAILABLE"
    internal const val DOWNLOAD_TRANSIENT_FAILURE_ERROR_CODE = "DOWNLOAD_TRANSIENT_FAILURE"
    internal const val DOWNLOAD_CATALOG_CACHE_FILE_NAME = "downloaded_song_catalog_v4.json"
    internal const val DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS = 1_200L
    internal const val DOWNLOAD_CATALOG_DELTA_MAX_ENTRIES = 2_048
    internal const val DOWNLOAD_TASK_COMPLETED_RETENTION_MS = 800L
    internal const val DOWNLOAD_CATALOG_RECONCILE_DELAY_MS = 1_200L
    /** 批量 operation 持久化使用有界页，避免单个 Room 事务占满大批选择 */
    internal const val BATCH_OPERATION_STAGE_PAGE_SIZE = 64
    /** 首次选择只对有限索引窗口做同步 Present 校验，余量交给共享泵对账 */
    internal const val BATCH_FAST_COMPLETION_PROBE_CHUNK_SIZE = 64
    internal const val DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS = 5_000L
    internal const val DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS = 1_200L
    /** 任务展示应在这个预算内消失，Provider 清理转到后台 */
    internal const val DOWNLOAD_CLEAR_PRESENTATION_BUDGET_MS = 500L
    /** 逻辑清空不能超过任务展示的交互预算 */
    internal const val DOWNLOAD_CLEAR_INTERACTIVE_BUDGET_MS =
        DOWNLOAD_CLEAR_PRESENTATION_BUDGET_MS
    internal const val DOWNLOAD_CLEAR_FAST_DB_WAIT_MS =
        DOWNLOAD_CLEAR_PRESENTATION_BUDGET_MS / 2L
    internal const val DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS = 3
    internal const val DOWNLOAD_CANCEL_JOURNAL_RETRY_DELAY_MS = 150L
    internal const val DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS = 1_000L
    internal const val DOWNLOAD_CANCEL_CONVERGENCE_MAX_ATTEMPTS = 8
    /** 取消入口固定旧 operation 身份的最长等待时间 */
    internal const val DOWNLOAD_CANCEL_OPERATION_SNAPSHOT_TIMEOUT_MS = 2_000L
    internal const val DOWNLOAD_CLEAR_FENCE_WAIT_POLL_MS = 100L
    internal const val DOWNLOAD_CLEAR_FENCE_WAIT_TIMEOUT_MS = 2_000L
    /** 单轮 Provider 清理只等待有限时间，未结束的工作继续持有目录 lease */
    internal const val DOWNLOAD_CLEAR_PROVIDER_CLEANUP_WAIT_TIMEOUT_MS = 1_500L
    internal const val DOWNLOAD_CANCEL_CLEANUP_PARALLELISM = 4
    internal const val DOWNLOAD_CLEAR_PROGRESS_UPDATE_INTERVAL_MS = 100L
    internal const val DOWNLOAD_CLEAR_PROGRESS_UPDATE_BATCH_SIZE = 8
    internal const val DOWNLOAD_CLEAR_PROGRESS_PERSIST_INTERVAL_MS = 500L
    internal const val DOWNLOAD_CLEAR_PROGRESS_PERSIST_BATCH_SIZE = 32
    internal const val DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS = 25L
    /** 删除屏障只允许短暂等待，超时由持久恢复流程重试 */
    internal const val DOWNLOADED_SONG_DELETE_BARRIER_TIMEOUT_MS = 1_500L
    internal const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_GRACE_MS = 300L
    internal const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_POLL_MS = 50L
    internal const val DOWNLOADED_SONG_BUILD_PARALLELISM = 4
    internal const val DOWNLOAD_LIBRARY_SCAN_TARGET_MS = 3_000L
    internal const val STARTUP_RECOVERY_MAX_ATTEMPTS = 3
    internal const val STARTUP_RECOVERY_RETRY_DELAY_MS = 350L
    /** 首个真实传输的条件化启动预算，超时只触发一次有界再唤醒 */
    internal const val STARTUP_FIRST_TRANSFER_DEADLINE_MS = 5_000L
    internal const val STARTUP_WATCHDOG_RECHECK_DELAY_MS = 5_000L
    internal const val STARTUP_INITIAL_SCAN_WAIT_TIMEOUT_MS =
        DOWNLOAD_LIBRARY_SCAN_TARGET_MS
    internal const val STARTUP_ARTIFACT_RECOVERY_HANDOFF_DELAY_MS = 250L
    internal const val STARTUP_ARTIFACT_RECOVERY_YIELD_BATCH_SIZE = 16
    /** core 收尾共享一次短时目录快照，避免并发 operation 重复扫描 SAF */
    internal const val FINALIZATION_RECOVERY_SNAPSHOT_TTL_MS = 750L
    internal const val DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS = 6
    internal const val DOWNLOADED_PLAYBACK_RETRY_BASE_DELAY_MS = 50L
    internal const val DOWNLOADED_PLAYBACK_RETRY_MAX_DELAY_MS = 250L
    internal const val METADATA_WRITE_MAX_ATTEMPTS = 3
    internal const val METADATA_WRITE_RETRY_DELAY_MS = 200L
    internal const val METADATA_POST_PROCESSING_MAX_ATTEMPTS = 3
    internal const val METADATA_POST_PROCESSING_RETRY_DELAY_MS = 350L
    internal const val DOWNLOAD_TASK_PROGRESS_EMIT_INTERVAL_NS = 450_000_000L
    internal const val DOWNLOAD_PROGRESS_CHECKPOINT_COALESCE_MS = 120L
    internal const val METADATA_POST_PROCESSING_PARALLELISM = 2
    internal const val ASSET_ENRICHMENT_PARALLELISM = 4
    internal const val ASSET_ENRICHMENT_MAX_ACTIVE_JOBS = 4
    internal const val POST_CORE_RECOVERY_MAX_WINDOWS = 4
    internal const val POST_CORE_RECOVERY_MAX_OPERATIONS = 32
    internal const val POST_CORE_RECOVERY_WINDOW_WAIT_MS = 125_000L
    internal const val POST_CORE_ENRICHMENT_MAX_AUTO_RETRIES = 6
    internal const val POST_CORE_ENRICHMENT_RETRY_EXHAUSTED_ERROR =
        "POST_CORE_ENRICHMENT_RETRY_EXHAUSTED"
    internal const val WIFI_RECOVERY_PROBE_ATTEMPTS = 6
    internal const val WIFI_RECOVERY_PROBE_DELAY_MS = 300L
    /** 清空后残留的传输 lease 只在确认没有 durable owner 后回收 */
    internal const val ORPHANED_TRANSFER_LEASE_MIN_AGE_MS = 2_000L
    internal const val SONG_EXECUTION_LOCK_STRIPES = 256
    internal const val TERMINAL_TEMPORARY_WRITE_CLEANUP_COALESCE_MS = 750L
    internal const val PLAYBACK_METADATA_HYDRATION_DELAY_MS = 1_500L
    internal const val LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS = 4_000L

    /** 清空时仍需保留 lease 身份，等待后台完成提交边界收敛 */
    internal val CLEAR_LEASE_RELEASE_OPERATION_STATES = setOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    internal val NETWORK_POLICY_OPERATION_STATES = (
        DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
            DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
            WAITING_STORAGE_MUTATION_OPERATION_STATE
        ).distinct()

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val startupWatchdogLock = Any()
    internal var startupWatchdogJob: Job? = null
    internal val downloadPresentationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )
    internal val pendingWorkingProgressSnapshotLock = Any()
    internal val pendingWorkingProgressSnapshotLoaded = AtomicBoolean(false)
    @Volatile
    internal var pendingWorkingProgressSnapshot = PendingWorkingProgressSnapshot.Empty
    internal val downloadedSongBuildDispatcher =
        Dispatchers.IO.limitedParallelism(DOWNLOADED_SONG_BUILD_PARALLELISM)

    internal data class FastIndexPersistenceRequest(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        val rebuildToken: ManagedLibraryFastIndexRebuildToken
    )

    internal val fastIndexPersistenceLock = Any()
    internal var pendingFastIndexPersistence: FastIndexPersistenceRequest? = null
    internal var fastIndexPersistenceJob: Job? = null

    internal data class DownloadClearSettlement(
        val activeSongKeys: Set<String>,
        val activeOperationIds: Set<String>,
        val batchJobsSettled: Boolean,
        val residualWorkingSongKeys: Set<String> = emptySet(),
        val residualPendingArtifactSongKeys: Set<String> = emptySet(),
        val residualPendingArtifactCount: Int = 0,
        val providerCleanupInFlight: Boolean = false
    ) {
        val isSettled: Boolean
            get() = activeSongKeys.isEmpty() &&
                activeOperationIds.isEmpty() &&
                batchJobsSettled &&
                residualWorkingSongKeys.isEmpty() &&
                residualPendingArtifactCount <= 0 &&
                !providerCleanupInFlight
    }

    internal data class CancelledPendingCleanupOutcome(
        val residualSongKeys: Set<String> = emptySet(),
        val failedCount: Int = 0
    )

    internal data class DownloadTaskCancellationEntry(
        val songKey: String,
        val task: DownloadTask?,
        val cancellationGeneration: Long?,
        val operationIds: Set<String> = emptySet()
    )

    internal data class DownloadClearOwnershipCapture(
        val operationIdentities: List<DownloadExecutionRoomStore.OperationIdentity>,
        val pendingWorkingDownloads: List<ManagedDownloadStorage.PendingResumableDownload>
    ) {
        val operationIds: Set<String>
            get() = operationIdentities.mapTo(linkedSetOf()) { it.operationId }

        val stableKeys: Set<String>
            get() = buildSet {
                operationIdentities.forEach { identity ->
                    add(identity.stableKey)
                }
                pendingWorkingDownloads.forEach { entry ->
                    add(entry.song.stableKey())
                }
            }
    }

    internal data class ActiveProgressCheckpointBinding(
        val operationId: String,
        val attemptId: Long,
        val admissionTicket: Long? = null
    )

    internal data class PendingProgressCheckpoint(
        val context: Context,
        val progress: AudioDownloadManager.DownloadProgress,
        val binding: ActiveProgressCheckpointBinding
    )

    internal data class FinalizedManagedAudioSnapshot(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        val audio: ManagedDownloadStorage.StoredEntry,
        val metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    )

    internal data class CoreRecoveryAudioCandidate(
        val audio: ManagedDownloadStorage.StoredEntry,
        val metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        val operationMatches: Boolean,
        val identityMatches: Boolean
    )

    internal data class FinalizationRecoverySnapshotCache(
        val cacheKey: String,
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        val loadedAtElapsedMs: Long,
        val forceRefreshed: Boolean
    )

    internal data class PlayableManagedAudioSnapshot(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        val audio: ManagedDownloadStorage.StoredEntry,
        val metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ) {
        val reference: String?
            get() = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(
                entry = audio,
                allowPending = audio.isPendingAudioWrite
            )
    }

    internal data class DownloadedPlaybackResolution(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        val audio: ManagedDownloadStorage.StoredEntry?,
        val reference: String
    )

    internal data class DownloadedSongReferenceProbe(
        val reference: String? = null,
        val sawMissing: Boolean = false,
        val sawUncertain: Boolean = false
    )

    internal data class DownloadedSongDeleteSession(
        val deleteId: Long,
        val targetSongs: List<DownloadedSong>,
        val previousSongs: List<DownloadedSong>,
        val deletionKeys: Set<String>,
        val visibilityToken: DownloadedSongDeleteVisibility.Token,
        val clearJob: Job?,
        val fullLibraryDelete: Boolean,
        val deleteIntentDurable: Boolean
    )

    internal enum class DownloadedSongMetadataSyncOutcome {
        SUCCESS,
        NOT_DOWNLOADED,
        FAILED
    }

    internal enum class MetadataPostProcessingResult {
        EMBEDDED_VERIFIED,
        UNSUPPORTED_CONTAINER,
        RETRYABLE_FAILURE
    }

    internal enum class CatalogPublishMode {
        FULL,
        DELTA
    }

    internal data class PendingCatalogPersistRequest(
        val expectedRevision: Long,
        val full: Boolean,
        val delta: DownloadedSongCatalogDelta?
    )

    data class TrafficRiskDownloadRequest(
        val id: Long,
        val songs: List<SongItem>,
        val networkType: TrafficNetworkType,
        val isBatch: Boolean
    ) {
        val songCount: Int
            get() = songs.size
    }

    internal val trafficRiskDownloadRequestsMutable =
        MutableSharedFlow<TrafficRiskDownloadRequest>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val trafficRiskDownloadRequests: SharedFlow<TrafficRiskDownloadRequest> =
        trafficRiskDownloadRequestsMutable

    data class MobileDataDownloadBatchIdentity(
        val batchId: String,
        val generation: Long
    )

    internal data class WifiBoundNetworkPolicySnapshot(
        val policyBoundTasks: List<DownloadTask>,
        val displayWaitingTasks: List<DownloadTask>,
        val waitingSongs: List<SongItem>,
        val durableSongKeys: Set<String>,
        val policyBoundSongKeys: Set<String>,
        val affectedSongKeys: Set<String>,
        val batchIdentities: List<MobileDataDownloadBatchIdentity>
    ) {
        val taskCount: Int
            get() = affectedSongKeys.size
    }

    data class MobileDataDownloadInterruptionRequest(
        val id: Long,
        val networkType: TrafficNetworkType,
        val taskCount: Int,
        /** 用户确认只对捕获时仍在等待的批次身份生效 */
        val batchIdentities: List<MobileDataDownloadBatchIdentity> = emptyList(),
        /** 网络切换后旧确认不得复用到新的移动网络代际 */
        val networkGeneration: Long = 0L
    )

    internal val mobileDataDownloadInterruptionRequestMutable =
        MutableStateFlow<MobileDataDownloadInterruptionRequest?>(null)
    val mobileDataDownloadInterruptionRequest:
        StateFlow<MobileDataDownloadInterruptionRequest?> =
        mobileDataDownloadInterruptionRequestMutable.asStateFlow()

    internal val latestProgressProjectionStore =
        DownloadProgressProjectionStore()
    internal val latestProgressByOperation: StateFlow<
        Map<String, AudioDownloadManager.DownloadProgress>
        > = latestProgressProjectionStore.snapshot
    internal val activeProgressCheckpointBindings =
        ConcurrentHashMap<String, ActiveProgressCheckpointBinding>()

    internal val taskStore = DownloadTaskStore(
        scope = scope,
        progressEmitIntervalNs = DOWNLOAD_TASK_PROGRESS_EMIT_INTERVAL_NS
    )
    internal val downloadedSongCatalogStore = DownloadedSongCatalogStore(
        cacheFileName = DOWNLOAD_CATALOG_CACHE_FILE_NAME,
        snapshotCacheKeyProvider = ManagedDownloadStorage::currentSnapshotCacheKey,
        loggerTag = TAG
    )
    internal val downloadedAudioMetadataStore = DownloadedAudioMetadataStore(
        maxWriteAttempts = METADATA_WRITE_MAX_ATTEMPTS,
        writeRetryDelayMs = METADATA_WRITE_RETRY_DELAY_MS,
        loggerTag = TAG
    )
    internal val downloadedSongBuilder = DownloadedSongBuilder(
        metadataStore = downloadedAudioMetadataStore,
        loggerTag = TAG
    )
    internal val managedDownloadDeletePlanner = ManagedDownloadDeletePlanner()
    internal val managedDownloadArtifactCoordinator = ManagedDownloadArtifactCoordinator()
    internal val assetEnrichmentCoordinator = AssetEnrichmentCoordinator(
        scope = scope,
        parallelism = ASSET_ENRICHMENT_PARALLELISM,
        maxActiveJobs = ASSET_ENRICHMENT_MAX_ACTIVE_JOBS,
        timeoutMs = 120_000L
    )
    internal val managedLibraryReconciler = ManagedLibraryReconciler()
    internal val corePublicationCoordinator = DownloadCorePublicationCoordinator()
    internal val requestGenerationTracker = DownloadRequestGenerationTracker()
    internal val batchDownloadPresentationIdGenerator = AtomicLong(0L)
    /** 进程重启后用固定 id 回填持久 operation，避免重复创建恢复横幅 */
    internal const val RECOVERED_BATCH_DOWNLOAD_PRESENTATION_ID = Long.MIN_VALUE
    internal const val RECOVERED_DURABLE_BATCH_PRESENTATION_ID_START = Long.MIN_VALUE + 1L
    /** UI 临时 id 只用于渲染；用户批次身份始终以 Room 的 id 和 generation 为准 */
    internal val durableBatchIdentityByPresentationId =
        ConcurrentHashMap<Long, DownloadExecutionRoomStore.DownloadBatchIdentity>()
    internal val batchDownloadPresentationsMutable =
        MutableStateFlow<Map<Long, BatchDownloadPresentationState>>(emptyMap())
    val downloadTasks: StateFlow<List<DownloadTask>> = combine(
        taskStore.downloadTasks,
        latestProgressByOperation
    ) { tasks, latestProgress ->
        overlayLatestProgress(tasks, latestProgress)
    }.stateIn(
        scope = downloadPresentationScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )
    val downloadTaskSummary: StateFlow<DownloadTaskSummary> = taskStore.downloadTaskSummary
    val activeDownloadOperationsFlow: StateFlow<Boolean> = combine(
        taskStore.activeDownloadOperationsFlow,
        assetEnrichmentCoordinator.hasActiveJobs
    ) { taskOperationsActive, enrichmentActive ->
        taskOperationsActive || enrichmentActive
    }.stateIn(
        scope = downloadPresentationScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )
    internal val batchDownloadProgressFlow: StateFlow<BatchDownloadOverallProgress?> = combine(
        batchDownloadPresentationsMutable,
        downloadTasks
    ) { presentations: Map<Long, BatchDownloadPresentationState>, tasks: List<DownloadTask> ->
        aggregateBatchDownloadProgress(presentations.values, tasks)
    }.stateIn(
        scope = downloadPresentationScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )
    internal val downloadClearVisibility = DownloadClearVisibility()
    internal val deferredTaskClearRecoveryScheduled = AtomicBoolean(false)
    internal val taskClearHardDeadlineScheduled = AtomicBoolean(false)
    internal val deferredFullDeleteRecoveryScheduled = AtomicBoolean(false)
    internal val deferredFullDeleteProviderCleanupRecoveryLock = Any()
    internal var deferredFullDeleteProviderCleanup: Deferred<DownloadClearSettlement>? = null
    internal val deferredFullDeleteProviderCleanupRecoveryPending = AtomicBoolean(false)
    internal val downloadClearProviderCleanupCoordinator =
        DownloadClearProviderCleanupCoordinator<Long, DownloadClearSettlement>(scope)
    val isClearingDownloadTasks: StateFlow<Boolean> =
        downloadClearVisibility.isTaskProgressClearing
    val isDownloadTaskClearPresentationActive: StateFlow<Boolean> =
        combine(
            taskStore.isClearPresentationActive,
            downloadClearVisibility.isTaskProgressClearing
        ) { presentationActive, taskProgressClearing ->
            presentationActive && taskProgressClearing
        }.stateIn(
            scope = downloadPresentationScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )
    val isDownloadTaskClearPresentationCleared: StateFlow<Boolean> =
        combine(
            downloadClearVisibility.isTaskPresentationCleared,
            downloadClearVisibility.isTaskProgressClearing
        ) { presentationCleared, taskProgressClearing ->
            presentationCleared && taskProgressClearing
        }.stateIn(
            scope = downloadPresentationScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )
    internal val downloadClearProgress: StateFlow<DownloadClearVisibility.ClearProgress?> =
        downloadClearVisibility.progress

    internal val downloadedSongsMutable = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloadedSongs: StateFlow<List<DownloadedSong>> = downloadedSongsMutable.asStateFlow()
    internal val downloadPresenceVersionMutable = MutableStateFlow(0)
    val downloadPresenceVersion: StateFlow<Int> = downloadPresenceVersionMutable.asStateFlow()

    internal val isRefreshingMutable = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = isRefreshingMutable.asStateFlow()

    internal val cancelledSongKeys = Collections.synchronizedSet(mutableSetOf<String>())
    /** 取消收敛只允许触碰取消请求创建时已经存在的 operation */
    internal val cancellationOperationIdsBySongKey = ConcurrentHashMap<String, Set<String>>()
    /** 取消请求与新请求可以交错，但旧 operation 的清理不能被新代次抹掉 */
    internal val cancellationCleanupActiveSongKeys = ConcurrentHashMap.newKeySet<String>()
    /** 取消入口先固定 operation 身份，后续新请求只能使用新的 operation */
    internal val cancellationOperationSnapshotJobs =
        ConcurrentHashMap<String, Deferred<Set<String>>>()
    /** 记录取消入口的时间边界，延迟查询也不能把替代 operation 纳入旧清理 */
    internal val cancellationOperationSnapshotCutoffs = ConcurrentHashMap<String, Long>()
    /** 只有查询成功返回后才允许把空快照当作没有旧 operation */
    internal val cancellationOperationSnapshotResolvedKeys =
        ConcurrentHashMap.newKeySet<String>()
    /** 快照超时也不能丢掉用户新请求，首次替代入队时强制换用新 operation */
    internal val cancellationForceNewSongKeys = ConcurrentHashMap.newKeySet<String>()
    internal val catalogPersistenceLock = Any()
    internal val catalogPersistenceMutex = Mutex()
    internal val downloadedSongCatalogMutationLock = Any()
    internal val downloadedSongMetadataSyncMutex = Mutex()
    internal val downloadedSongDeleteMutex = Mutex()
    internal val terminalTemporaryWriteCleanupMutex = Mutex()
    internal val terminalTemporaryWriteCleanupBatch = TerminalTemporaryWriteCleanupBatch()
    internal var terminalTemporaryWriteCleanupWakeRequested = false
    internal val downloadedSongDeletionCounts = ConcurrentHashMap<String, AtomicInteger>()
    internal val downloadedSongDeleteVisibility = DownloadedSongDeleteVisibility()
    internal val downloadedSongDeleteIdGenerator = AtomicLong(0L)
    internal val downloadedSongDeleteProgressMutable =
        MutableStateFlow<DownloadedSongDeleteProgress?>(null)
    val downloadedSongDeleteProgress: StateFlow<DownloadedSongDeleteProgress?> =
        downloadedSongDeleteProgressMutable.asStateFlow()
    internal val downloadedSongCatalogPersistenceRevision = AtomicLong(0L)
    internal val downloadedSongMetadataRevision = AtomicLong(0L)
    internal val emptyScanSequence = AtomicLong(0L)
    internal var refreshJob: Job? = null
    internal val refreshWaiters =
        mutableSetOf<CompletableDeferred<ManagedLibraryRefreshOutcome>>()
    internal var activeRefreshForceRefresh = false
    internal var catalogPersistJob: Job? = null
    internal val catalogPersistGeneration = AtomicLong(0L)
    internal val pendingCatalogDeltaByKey = linkedMapOf<String, DownloadedSong>()
    internal val pendingCatalogDeltaRemovedStableKeys = linkedSetOf<String>()
    internal var pendingCatalogPersistRequiresFull = false
    internal var catalogReconcileJob: Job? = null
    internal var terminalTemporaryWriteCleanupJob: Job? = null
    internal var pendingCatalogReconcileForceRefresh = false
    internal val metadataPostProcessingSemaphore = Semaphore(METADATA_POST_PROCESSING_PARALLELISM)
    internal val downloadedPlaybackRequestGeneration = AtomicLong(0L)
    internal val downloadedPlaybackJobLock = Any()
    internal var downloadedPlaybackJob: Job? = null

    @Volatile
    internal var downloadedSongCatalogIndex = DownloadedSongCatalogIndex.EMPTY

    @Volatile
    internal var downloadedSongCatalogReady = false

    // 当前内存 catalog 所属的存储 root 标识 (restore/扫描发布时更新)
    // 用于把"切换/重置目录后扫描新目录得到的真空"与"同目录 SAF 瞬时空列举失败"区分开:
    // 前者 scanRootKey != catalogRootKey, 应放行清空; 后者相等, 才启用 #D4 可疑空保护
    @Volatile
    internal var downloadedSongCatalogRootKey: String? = null

    @Volatile
    internal var pendingRefresh = false

    @Volatile
    internal var pendingForceRefresh = false

    /** Application 可能从多个入口同时初始化，初始化流程只能注册一次观察者和泵 */
    internal val initializationStarted = AtomicBoolean(false)
    internal val startupProgressRestoreReady = CompletableDeferred<Unit>()
    internal val trafficRiskRequestIdGenerator = AtomicLong(0L)
    internal val mobileDataInterruptionRequestIdGenerator = AtomicLong(0L)
    internal val mobileDataDownloadInterruptionEpoch = AtomicLong(0L)
    internal val wifiBoundNetworkPolicyEpoch = AtomicLong(0L)
    internal val wifiBoundNetworkPolicyMutationLock = Any()
    internal val mobileDataDownloadInterruptionRequestMutex = Mutex()
    internal val songExecutionLocks = Array(SONG_EXECUTION_LOCK_STRIPES) { Mutex() }
    internal val pendingDownloadRecoveryMutex = Mutex()
    internal val wifiRecoveryProbeLock = Any()
    internal var wifiRecoveryProbeJob: Job? = null
    internal val startupRecoveryMutex = Mutex()
    internal val finalizationRecoverySnapshotMutex = Mutex()
    @Volatile
    internal var finalizationRecoverySnapshotCache: FinalizationRecoverySnapshotCache? = null
    internal val startupArtifactRecoveryActive = AtomicBoolean(false)
    internal val finalizedCoverRepairActive = AtomicBoolean(false)
    internal val activeBatchDownloadJobs = Collections.newSetFromMap(ConcurrentHashMap<Job, Boolean>())
    /** 磁盘确实耗尽时只启动一轮全局取消，避免多个并发 operation 重复建清空栅栏 */
    internal val storageExhaustionCancellationScheduled = AtomicBoolean(false)
    internal val managedDownloadArtifactLeases = ConcurrentHashMap<String, String>()
    internal val immediatePumpRunning = AtomicBoolean(false)
    internal data class CancellationConvergenceEntry(
        val generation: Long?,
        val operationIds: Set<String>,
        val job: Job
    )
    internal val cancellationConvergenceJobs =
        ConcurrentHashMap<String, CancellationConvergenceEntry>()
    internal val progressCheckpointLock = Any()
    internal val pendingProgressCheckpoints = linkedMapOf<String, PendingProgressCheckpoint>()
    internal var progressCheckpointWriterJob: Job? = null
    internal val downloadAdmissionGate = DownloadAdmissionGate()
    /** 启动和网络回调可能同时触发恢复，挂起槽位会排队后续请求而不是丢弃 */
    internal val pendingDownloadRecoverySlot = Mutex()

    @Volatile
    internal var mobileDataDownloadOverrideAllowed = false

    internal fun onWifiBoundDownloadNetworkRestored(
        context: Context,
        reason: String,
        networkGeneration: Long = AudioDownloadManager.currentDownloadNetworkGeneration()
    ): Boolean {
        return this.onWifiBoundDownloadNetworkRestoredImpl(context, reason, networkGeneration)
    }

    /** 队列持久化完成前不让新 operation 进入运行态 */
    internal data class StagedPendingDownloadQueue(
        val operationIds: List<String>,
        val skippedSongKeys: Set<String>,
        val operationIdsBySongKey: Map<String, String> = emptyMap(),
        val operationRequestsBySongKey: Map<String, DownloadExecutionRequest> = emptyMap()
    )

    /** 本批次刚写入的 request 尚未重新从 Room 解码时，仍可安全参与提升 */
    internal fun resolveBatchWaitingOperationStableKey(
        directRequest: DownloadExecutionRequest?,
        metadataStableKey: String?,
        identityStableKey: String?
    ): String? {
        return this.resolveBatchWaitingOperationStableKeyImpl(directRequest, metadataStableKey, identityStableKey)
    }

    internal fun isBatchWaitingOperationReadable(
        directRequest: DownloadExecutionRequest?,
        metadataAvailable: Boolean,
        stableKey: String?,
        identityStableKey: String?
    ): Boolean {
        return this.isBatchWaitingOperationReadableImpl(directRequest, metadataAvailable, stableKey, identityStableKey)
    }

    /** 存储空间恢复 worker 使用同一套栅栏提升等待意图，避免绕过迁移和清空保护 */
    internal suspend fun promoteWaitingStorageMutationsForDownloadPump(context: Context): Int {
        return promoteWaitingStorageMutationsForRecovery(context.applicationContext)
    }

    internal fun recoverPendingDownloadsAfterStorageMutation(context: Context) {
        return this.recoverPendingDownloadsAfterStorageMutationImpl(context)
    }

    /** 串行处理所有持久恢复触发请求，调用方会挂起直到当前恢复释放槽位 */
    internal suspend inline fun <T> withPendingDownloadRecoverySlot(
        reason: String,
        crossinline block: suspend () -> T
    ): T {
        return pendingDownloadRecoverySlot.withLock {
            NPLogger.d(TAG, "下载恢复进入串行槽位: reason=$reason")
            block()
        }
    }

    /** legacy 队列导入完成后立即触发泵，避免等下一次网络回调才恢复 */
    internal fun wakeDownloadExecutionPumpAfterLegacyQueueBootstrap(context: Context) {
        wakeDownloadExecutionPump(
            context = context,
            reason = "legacy_queue_bootstrap"
        )
    }

    /** Core 音频已 durable 后立即补位，资产增强仍由独立队列异步处理 */
    internal fun wakeDownloadExecutionPumpAfterCoreCommit(
        context: Context,
        operationId: String? = null,
        attemptId: Long? = null,
        transferOwnerToken: Long? = null
    ): Boolean {
        return this.wakeDownloadExecutionPumpAfterCoreCommitImpl(context, operationId, attemptId, transferOwnerToken)
    }

    /** transfer owner 争抢失败后立即让共享泵重新扫描，而不是等待退避窗口 */
    internal fun wakeDownloadExecutionPumpAfterTransferAdmissionDeferred(context: Context) {
        return this.wakeDownloadExecutionPumpAfterTransferAdmissionDeferredImpl(context)
    }

    /** 并行数提高后立即让共享水泵补足空出的 transfer lane */
    internal fun wakeDownloadExecutionPumpAfterParallelismChanged(context: Context) {
        return this.wakeDownloadExecutionPumpAfterParallelismChangedImpl(context)
    }

    fun initialize(context: Context) {
        return this.initializeImpl(context)
    }

    internal suspend fun recoverPostCoreDownloadsForWorker(
        context: Context
    ): PostCoreDownloadRecoveryResult {
        return this.recoverPostCoreDownloadsForWorkerImpl(context)
    }

    internal suspend fun reconcileMaterializedLegacyDownloads(context: Context) {
        return this.reconcileMaterializedLegacyDownloadsImpl(context)
    }

    /**
     * 迁移前发现 pending 时只收敛下载提交凭据，避免 durable 迁移请求把通用恢复挡住
     *
     * 恢复函数自行取得独占目录租约，无法读取或无法证明归属的文件继续保留
     */
    internal suspend fun reconcilePendingDownloadsAfterMigrationBlocked(
        context: Context,
        sourceDirectoryUri: String? = null
    ) {
        return this.reconcilePendingDownloadsAfterMigrationBlockedImpl(context, sourceDirectoryUri)
    }

    /**
     * 在迁移状态占位前收敛 pending 音频，避免恢复路径被自己的状态机挡住
     *
     * WaitingForRetry 仍然保留，迁移 Worker 随后可以复用同一个 operation
     */
    internal suspend fun reconcilePendingDownloadsBeforeMigrationDetailed(
        context: Context,
        sourceDirectoryUri: String? = null,
        directoryMutationLeaseOwned: Boolean = false
    ): PendingDownloadRecoverySummary {
        return this.reconcilePendingDownloadsBeforeMigrationDetailedImpl(context, sourceDirectoryUri, directoryMutationLeaseOwned)
    }

    internal const val TERMINAL_OPERATION_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    internal const val TERMINAL_OPERATION_PRUNE_LIMIT = 64

    internal data class PendingDownloadRecoveryPlan(
        val pendingQueuedDownloads: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
        val pendingDownloads: List<ManagedDownloadStorage.PendingResumableDownload>,
        val recoveryCandidates: List<PendingDownloadRecoveryCandidate>,
        val recoveryCandidateKeys: Set<String>,
        val resumableSongs: List<SongItem>,
        val directSettlements: List<PendingDownloadRecoveryDirectSettlement>,
        val settledSongKeys: Set<String>,
        val settledOperationIds: Set<String>,
        val workingFilesToDelete: List<File>,
        val explicitResumeKeys: Set<String>,
        val durableInFlightRequests: List<DownloadExecutionRequest>
    )

    internal data class PendingDownloadRecoveryDirectSettlement(
        val song: SongItem,
        val operationId: String,
        val attemptId: Long?,
        val workingFile: File?
    )

    internal data class RecoveryDirectSettlementResult(
        val settledSongKeys: Set<String>,
        val settledOperationIds: Set<String>,
        val failedSongKeys: Set<String>
    )

    internal fun MobileDataDownloadBatchIdentity.toRoomBatchIdentity():
        DownloadExecutionRoomStore.DownloadBatchIdentity {
        return DownloadExecutionRoomStore.DownloadBatchIdentity(
            batchId = batchId,
            generation = generation
        )
    }

    fun recoverPendingDownloadsForNetworkRestored(context: Context, reason: String) {
        return this.recoverPendingDownloadsForNetworkRestoredImpl(context, reason)
    }

    /**
     * WIFI 刚恢复时 provider 和 Connectivity callback 可能仍在收敛，
     * 用短窗口串行探测，避免一次过早回调把恢复机会丢掉。
     */
    internal fun scheduleWifiRecoveryProbe(context: Context, reason: String) {
        return this.scheduleWifiRecoveryProbeImpl(context, reason)
    }

    fun hasPendingRecoveryCandidates(context: Context): Boolean {
        return this.hasPendingRecoveryCandidatesImpl(context)
    }

    fun requestPendingDownloadRecoveryDecisionIfNeeded(
        context: Context,
        reason: String
    ) {
        return this.requestPendingDownloadRecoveryDecisionIfNeededImpl(context, reason)
    }

    fun hasActiveDownloadOperations(): Boolean {
        return taskStore.hasActiveDownloadOperations() ||
            assetEnrichmentCoordinator.hasActiveJobs.value
    }

    internal fun buildDownloadedSongCatalogIndex(
        songs: List<DownloadedSong>
    ): DownloadedSongCatalogIndex {
        return moe.ouom.neriplayer.core.download.buildDownloadedSongCatalogIndex(songs)
    }

    internal data class FinalizedDownloadedAudioProbe(
        val readable: Boolean,
        val durationMs: Long?
    )

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
    ): ManagedLibraryRefreshOutcome {
        return this.scanLocalFilesAwaitImpl(context, forceRefresh)
    }

    internal fun shouldCompleteProcessingAfterCatalogPublish(
        state: ManagedLibraryProcessingState
    ): Boolean {
        return this.shouldCompleteProcessingAfterCatalogPublishImpl(state)
    }

    fun refreshDownloadedSongsForManager(
        context: Context,
        forceRefresh: Boolean = false
    ) {
        val appContext = context.applicationContext
        scanLocalFiles(appContext, forceRefresh = forceRefresh)
    }

    internal fun consumePendingRefreshRequest(): Boolean? = synchronized(this) {
        val shouldRefreshAgain = pendingRefresh
        val shouldForceRefresh = pendingForceRefresh
        pendingRefresh = false
        pendingForceRefresh = false
        if (!shouldRefreshAgain) {
            activeRefreshForceRefresh = false
            return null
        }
        activeRefreshForceRefresh = shouldForceRefresh
        shouldForceRefresh
    }

    /** 当前快照的音频引用优先于旧 metadata，避免迁移后恢复 app-private URI */
    internal fun resolveDurableMetadataPlaybackReference(
        audio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): String? {
        return this.resolveDurableMetadataPlaybackReferenceImpl(audio, metadata)
    }

    internal fun syncDownloadedSongMetadata(
        song: SongItem,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy()
    ) {
        return this.syncDownloadedSongMetadataImpl(song, clearRestorableOverrides)
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
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return@withContext null
        resolveFinalizedManagedAudioSnapshot(snapshot, storedAudio)
            ?.metadata
            ?.restorableMetadata
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
    ): DownloadedSongMetadataSyncOutcome = this.syncDownloadedSongMetadataNowImpl(
        song = song,
        clearRestorableOverrides = clearRestorableOverrides
    )

    internal suspend fun buildDownloadedSong(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null,
        existingDownloadTime: Long? = null,
        loadLyricContents: Boolean = false,
        resolveLyricFallbacks: Boolean = false,
        allowSlowLocalInspection: Boolean = true,
        verifySnapshotReferences: Boolean = true
    ): DownloadedSong = downloadedSongBuilder.build(
        context = context,
        storedAudio = storedAudio,
        snapshot = snapshot,
        existingDownloadTime = existingDownloadTime,
        loadLyricContents = loadLyricContents,
        resolveLyricFallbacks = resolveLyricFallbacks,
        allowSlowLocalInspection = allowSlowLocalInspection,
        verifySnapshotReferences = verifySnapshotReferences
    )

    internal suspend fun persistDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null,
        downloadFinalized: Boolean = true,
        metadataEmbeddingState: DownloadedAudioEmbeddingState? = null,
        resolveExistingSidecars: Boolean = true,
        artifactStateOverride: String? = null,
        operationId: String? = null,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy(),
        existingMetadataHint: ManagedDownloadStorage.DownloadedAudioMetadata? = null
    ): Boolean = downloadedAudioMetadataStore.persist(
        context = context,
        audio = audio,
        song = song,
        sidecarReferences = sidecarReferences,
        downloadFinalized = downloadFinalized,
        metadataEmbeddingState = metadataEmbeddingState,
        resolveExistingSidecars = resolveExistingSidecars,
        artifactStateOverride = artifactStateOverride,
        operationId = operationId,
        clearRestorableOverrides = clearRestorableOverrides,
        existingMetadataHint = existingMetadataHint
    )

    internal suspend fun readDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        metadataEntry: ManagedDownloadStorage.StoredEntry? = null
    ): ManagedDownloadStorage.DownloadedAudioMetadata? = downloadedAudioMetadataStore.read(
        context = context,
        audio = audio,
        metadataEntry = metadataEntry
    )

    internal fun trustedManagedMetadataReference(
        reference: String?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return ManagedDownloadArtifactPlanner.trustedMetadataReference(reference, snapshot)
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

        val currentSongs = downloadedSongsMutable.value
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

    suspend fun deleteDownloadedSongsWithResult(
        context: Context,
        songs: List<DownloadedSong>,
        deleteEntireLibrary: Boolean = false
    ): DownloadedSongDeleteResult {
        return this.deleteDownloadedSongsWithResultImpl(context, songs, deleteEntireLibrary)
    }

    fun playDownloadedSong(context: Context, song: DownloadedSong) {
        return this.playDownloadedSongImpl(context, song)
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
        return this.findAccessibleDownloadedSongPlaybackUriImpl(context, song)
    }

    fun findFastCachedDownloadedSongPlaybackUri(context: Context, song: SongItem): String? {
        return this.findFastCachedDownloadedSongPlaybackUriImpl(context, song)
    }

    fun startDownload(context: Context, song: SongItem) {
        scheduleUserDownload(context, song, skipTrafficRiskPrompt = false)
    }

    suspend fun startDownload(
        context: Context,
        song: SongItem,
        operationId: String,
        preserveStaging: Boolean = false,
        preparedAttemptId: Long? = null
    ): DownloadExecutionResult {
        return this.startDownloadImpl(context, song, operationId, preserveStaging, preparedAttemptId)
    }

    internal suspend fun executeDownloadOperation(
        context: Context,
        song: SongItem,
        operationId: String,
        preserveStaging: Boolean = false,
        preparedAttemptId: Long? = null,
        admissionTicket: Long? = null
    ): DownloadExecutionResult {
        return this.executeDownloadOperationImpl(context, song, operationId, preserveStaging, preparedAttemptId, admissionTicket)
    }

    internal fun shouldRestartPostCoreOperationForFreshTransfer(
        operationState: String?,
        artifactClaim: ManagedDownloadArtifactClaim
    ): Boolean {
        return this.shouldRestartPostCoreOperationForFreshTransferImpl(operationState, artifactClaim)
    }

    internal suspend fun isMetadataEmbeddingActionRequired(
        context: Context,
        operationId: String,
        songKey: String
    ): Boolean = runCatching {
        val request = DownloadExecutionRoomStore.read(context, operationId)
            ?.takeIf { it.song.stableKey() == songKey }
            ?: return@runCatching false
        val storedAudio = findPendingAudioForFinalization(
            context = context,
            song = request.song,
            operationId = operationId,
            preferredAudioName = null
        ) ?: resolveStoredAudio(context, request.song)
            ?: ManagedDownloadStorage.findDownloadedAudio(context, request.song, forceRefresh = true)
            ?: return@runCatching false
        readDownloadedMetadata(context, storedAudio)?.metadataEmbeddingState ==
            DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
    }.getOrElse { error ->
        NPLogger.w(
            TAG,
            "读取元信息嵌入待处理状态失败，保留可重试 operation: " +
                "operationId=$operationId, error=${error.message}"
        )
        false
    }

    internal fun stopDownloadOperation(
        context: Context,
        songKey: String,
        expectedAttemptId: Long?,
        rememberForRetry: Boolean,
        operationId: String? = null,
        knownOperationState: String? = null
    ) {
        return this.stopDownloadOperationImpl(context, songKey, expectedAttemptId, rememberForRetry, operationId, knownOperationState)
    }

    internal fun cancelDownloadOperationFromHost(
        songKey: String,
        operationId: String? = null
    ) {
        return this.cancelDownloadOperationFromHostImpl(songKey, operationId)
    }

    internal data class PreparedConfirmedDownload(
        val artifactClaim: ManagedDownloadArtifactClaim?,
        val requiresFinalizationRecovery: Boolean,
        val acquiredLeaseId: String?,
        val attemptId: Long,
        val userInitiated: Boolean,
        val isBatchOperation: Boolean
    )

    fun startBatchDownload(context: Context, songs: List<SongItem>) {
        startBatchDownload(context, songs, skipTrafficRiskPrompt = false)
    }

    internal class BatchDownloadSession(
        val context: Context,
        val requestedSongs: List<SongItem>,
        val sourceSongCount: Int,
        val cleanupBeforeStart: Boolean,
        val requestGeneration: Long,
        val admissionTicket: Long,
        val deferForNetworkPolicy: Boolean,
        val userInitiated: Boolean,
        val operationIdsBySongKey: Map<String, String>,
        val operationRequestsBySongKey: Map<String, DownloadExecutionRequest>,
        val batchPresentationId: Long,
        val durableBatchIdentity: DownloadExecutionRoomStore.DownloadBatchIdentity? = null,
        val initialDownloadLibrarySnapshot:
            ManagedDownloadStorage.DownloadLibrarySnapshot? = null
    ) {
        val pendingSongs = mutableListOf<QueuedDownloadRequest>()
        val handedOffSongKeys = mutableSetOf<String>()
        val scheduledSongKeys = mutableSetOf<String>()
        val operationStatesBySongKey = linkedMapOf<String, String>()
        var shouldYieldToSharedPump = false
        val artifactClaims = linkedMapOf<String, ManagedDownloadArtifactClaim?>()
        val artifactLeaseIdsBySongKey = linkedMapOf<String, String>()
        val scheduleMetadataBySongKey = linkedMapOf<String, BatchOperationScheduleMetadata>()
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

    internal data class PreparedBatchArtifact(
        val operationId: String,
        val artifactClaim: ManagedDownloadArtifactClaim?,
        val requiresFinalizationRecovery: Boolean,
        val acquiredLeaseId: String?,
        val attemptId: Long?
    )

    internal data class BatchOperationScheduleMetadata(
        val operationId: String,
        val preserveStaging: Boolean,
        val requiresWifiNetwork: Boolean,
        val attemptId: Long?,
        val artifactLeaseId: String,
        val userInitiated: Boolean,
        val downloadAudioQuality: DownloadAudioQualitySelection?
    )

    fun confirmTrafficRiskDownload(
        context: Context,
        request: TrafficRiskDownloadRequest
    ) {
        return this.confirmTrafficRiskDownloadImpl(context, request)
    }

    fun updateTaskStatus(
        songKey: String,
        status: DownloadStatus,
        expectedAttemptId: Long? = null,
        settleBatchPresentation: Boolean = true,
        operationId: String? = null
    ) {
        return this.updateTaskStatusImpl(songKey, status, expectedAttemptId, settleBatchPresentation, operationId)
    }

    fun removeDownloadTask(songKey: String, expectedAttemptId: Long? = null) {
        taskStore.removeDownloadTask(
            songKey = songKey,
            expectedAttemptId = expectedAttemptId
        )
    }

    internal fun clearBatchDownloadPresentation(batchId: Long? = null) {
        return this.clearBatchDownloadPresentationImpl(batchId)
    }

    internal fun invalidateDownloadRequestGenerations(songKeys: Collection<String>) =
        requestGenerationTracker.snapshotAndInvalidate(songKeys).also { snapshot ->
            if (snapshot.invalidatedCount > 0) {
                NPLogger.d(TAG, "失效下载请求代际: songs=${snapshot.invalidatedCount}")
            }
        }

    fun clearSongCancelled(songKey: String) {
        cancelledSongKeys.remove(songKey)
    }

    fun cancelDownloadTask(songKey: String) {
        requestDownloadTaskCancellation(setOf(songKey))
    }

    fun clearAllDownloadTasks() {
        cancelAllDownloadTasks()
    }

    fun cancelAllDownloadTasks() {
        requestAllDownloadTaskCancellation()
    }

    fun interruptDownloadsForWifiDisconnected(
        callbackNetworkType: TrafficNetworkType?,
        networkGeneration: Long? = null
    ) {
        return this.interruptDownloadsForWifiDisconnectedImpl(callbackNetworkType, networkGeneration)
    }

    fun continueDownloadsOnMobileData(
        context: Context,
        request: MobileDataDownloadInterruptionRequest
    ) {
        return this.continueDownloadsOnMobileDataImpl(context, request)
    }

    fun waitDownloadsForWifi(request: MobileDataDownloadInterruptionRequest) {
        return this.waitDownloadsForWifiImpl(request)
    }

    fun cancelAllDownloadsForMobileData(request: MobileDataDownloadInterruptionRequest) {
        if (mobileDataDownloadInterruptionRequestMutable.value?.id != request.id) {
            return
        }
        dismissMobileDataDownloadInterruptionRequest()
        cancelAllDownloadTasks()
    }

    internal suspend fun recoverPendingDownloadsFromWifiWake(
        context: Context,
        admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
    ): Boolean {
        return this.recoverPendingDownloadsFromWifiWakeImpl(context, admissionTicket)
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

    /**
     * 允许长网络阶段暂时让出歌曲锁
     *
     * 同一首歌的状态检查仍在锁内进行，但 DNS、socket 读取和旧任务取消收敛不能
     * 占住这把锁，否则取消后的新代次会一直等到旧网络超时才有机会开始
     */
    internal class ReleasableSongExecutionLockScope(
        private val mutex: Mutex
    ) {
        private var held = true

        suspend fun <T> withoutSongExecutionLock(block: suspend () -> T): T {
            check(held) { "song execution lock is already released" }
            mutex.unlock()
            held = false
            return try {
                block()
            } finally {
                // 取消也必须先重新取得锁，保证外层 finally 不会把新代次留在无序状态
                withContext(NonCancellable) {
                    mutex.lock()
                    held = true
                }
            }
        }

        fun close() {
            if (held) {
                held = false
                mutex.unlock()
            }
        }
    }

    internal fun isDownloadAttemptActive(
        songKey: String,
        expectedAttemptId: Long? = null
    ): Boolean {
        return this.isDownloadAttemptActiveImpl(songKey, expectedAttemptId)
    }

    fun resumeDownloadTask(context: Context, songKey: String) {
        return this.resumeDownloadTaskImpl(context, songKey)
    }

    internal fun buildOptimisticDownloadedSong(
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
    ): DownloadedSong {
        return this.buildOptimisticDownloadedSongImpl(song, storedAudio, sidecarReferences)
    }

    internal fun resolveSongLocation(song: SongItem): String? {
        return this.resolveSongLocationImpl(song)
    }

    internal fun inspectDownloadedAudioDetails(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) = downloadedSongBuilder.inspectAudioDetails(context, storedAudio)

    internal fun matchesExpectedDownloadFileName(
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        return this.matchesExpectedDownloadFileNameImpl(song, audio)
    }

}
