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
import kotlinx.coroutines.yield
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
import moe.ouom.neriplayer.core.download.execution.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearProgressStore
import moe.ouom.neriplayer.core.download.execution.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.WifiBoundDownloadWakeWorker
import moe.ouom.neriplayer.core.download.execution.DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.METADATA_ACTION_REQUIRED_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildToken
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import moe.ouom.neriplayer.core.download.policy.TagPostProcessingAction
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanDecision
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanObservation
import moe.ouom.neriplayer.core.download.reconcile.ManagedLibraryReconciler
import moe.ouom.neriplayer.core.download.reconcile.ScanConfidence
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuilder
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuildItem
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.policy.tagPostProcessingAction
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.isFormalManagedAudioReference
import moe.ouom.neriplayer.core.player.download.isReadableManagedAudioPlaybackAllowed
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.core.startup.AppStartupWorkGate
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.settings.resolveDownloadAudioQualitySelection
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

internal fun shouldRebuildDownloadedLibrarySnapshot(recoveredArtifactCount: Int): Boolean {
    return recoveredArtifactCount > 0
}

/** 只有目录清理已经确认完成时，才允许删除取消 operation 的持久凭据 */
internal fun shouldPurgeCancelledDownloadOperation(
    keepCancellationOperation: Boolean,
    cleanupSucceeded: Boolean
): Boolean {
    return !keepCancellationOperation && cleanupSucceeded
}

internal fun shouldContinueWifiRecoveryProbe(
    networkType: TrafficNetworkType?,
    hasPendingCandidates: Boolean,
    attempt: Int,
    maxAttempts: Int
): Boolean {
    return networkType == TrafficNetworkType.WIFI &&
        hasPendingCandidates &&
        attempt >= 0 &&
        attempt + 1 < maxAttempts.coerceAtLeast(1)
}

internal fun durableOperationRecoveryPriority(state: String): Int {
    return when (state) {
        "DEGRADED_COMPLETE" -> 8
        "ASSETS_ENRICHING" -> 7
        "CORE_COMMITTED" -> 6
        "COMMITTING" -> 5
        "RUNNING" -> 4
        "RETRYABLE" -> 3
        "QUEUED" -> 2
        "PENDING_QUEUE" -> 1
        else -> 0
    }
}

internal fun <T> partitionForBoundedParallelism(
    items: List<T>,
    maxParallelism: Int
): List<List<T>> {
    return items.chunked(maxParallelism.coerceAtLeast(1))
}

internal data class DownloadedSongReferenceCoverage(
    val knownReferenceCount: Int,
    val missingReferenceCount: Int
)

/**
 * 用本次完整快照判断旧 catalog 的音频是否仍被观察到，避免空扫描时逐首触发 SAF stat
 */
internal fun observeDownloadedSongReferencesFromSnapshot(
    existingSongs: Collection<DownloadedSong>,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
): DownloadedSongReferenceCoverage {
    val observedReferences = buildSet {
        (snapshot.audioEntries + snapshot.pendingAudioEntries).forEach { entry ->
            add(entry.reference)
            add(entry.mediaUri)
            entry.localFilePath?.let(::add)
        }
    }
    var knownReferenceCount = 0
    var missingReferenceCount = 0
    existingSongs.forEach { song ->
        val references = listOf(song.filePath, song.mediaUri)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
        if (references.isEmpty()) return@forEach
        knownReferenceCount++
        if (references.none(observedReferences::contains)) {
            missingReferenceCount++
        }
    }
    return DownloadedSongReferenceCoverage(
        knownReferenceCount = knownReferenceCount,
        missingReferenceCount = missingReferenceCount
    )
}

/**
 * 已写入下载目录的 core 条目可以在快照重建前继续播放
 * Room/目录 catalog 是 core commit 之后才发布的持久凭据
 */
internal fun shouldAllowPendingCatalogPlayback(
    referenceIsPending: Boolean,
    catalogEntryAvailable: Boolean,
    snapshotAvailable: Boolean,
    durableCoreCommitAvailable: Boolean = false
): Boolean {
    if (!referenceIsPending) {
        return true
    }
    // pending 文件名只代表写入器已经暴露了一个候选引用，不能单独作为
    // 播放凭据。目录条目、当前快照和 durable core 状态必须同时存在
    return catalogEntryAvailable && snapshotAvailable && durableCoreCommitAvailable
}

/**
 * 快照尚未完成时只信任已经 Present 的正式音频
 * pending 条目仍必须有独立的 core 凭据才能进入播放链
 */
internal fun shouldTrustDirectPresentDownloadedSongReference(
    reference: String?,
    evidence: ManagedDownloadReferenceLookup.Result,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
    cachedAudio: ManagedDownloadStorage.StoredEntry?
): Boolean {
    if (
        !isFormalManagedAudioReference(reference) ||
        evidence != ManagedDownloadReferenceLookup.Result.Present ||
        cachedAudio?.isPendingAudioWrite == true
    ) {
        return false
    }
    if (cachedAudio == null) {
        return true
    }
    return isReadableManagedAudioPlaybackAllowed(
        audioIsPending = false,
        downloadActive = false,
        downloadCancelled = false,
        metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, cachedAudio),
        allowLegacyPublishedAudio = true
    )
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

/**
 * 把后台元数据旁路的异常收敛在任务边界，避免影响核心音频生命周期
 */
internal suspend fun runDownloadedSongMetadataSyncSafely(
    block: suspend () -> GlobalDownloadManager.DownloadedSongMetadataSyncOutcome,
    onFailure: (Throwable) -> Unit
): GlobalDownloadManager.DownloadedSongMetadataSyncOutcome? {
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
        null
    }
}

internal suspend fun runDownloadStartupRecoverySafely(
    block: suspend () -> Unit,
    onFailure: (Throwable) -> Unit
) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
    }
}

internal fun shouldApplyDownloadedPlaybackHydration(
    currentSong: SongItem?,
    quickSong: SongItem
): Boolean = currentSong?.sameIdentityAs(quickSong) == true

/**
 * 下载列表的播放解析是异步的，只有最后一次点击仍然有效时才能提交结果
 */
internal fun shouldApplyDownloadedPlaybackRequest(
    requestGeneration: Long,
    latestGeneration: Long
): Boolean = requestGeneration == latestGeneration

internal data class RecoveredDownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long
)

internal fun resolveRecoveredDownloadProgress(
    workingFileBytes: Long,
    checkpointTotalBytes: Long?,
    checkpointBytesWritten: Long? = null
): RecoveredDownloadProgress? {
    // 文件可能暂时不可见（例如进程刚被系统杀死或 SAF 游标尚未恢复），
    // 但 Room 检查点仍然是可用的进度凭据。两者取较大值，避免重启后回到 0。
    val durableBytes = maxOf(
        workingFileBytes.coerceAtLeast(0L),
        checkpointBytesWritten?.coerceAtLeast(0L) ?: 0L
    )
    val totalBytes = when {
        checkpointTotalBytes == null && durableBytes > 0L -> 0L
        checkpointTotalBytes != null &&
            checkpointTotalBytes > 0L &&
            checkpointTotalBytes >= durableBytes -> checkpointTotalBytes

        else -> return null
    }
    return RecoveredDownloadProgress(
        bytesRead = durableBytes,
        totalBytes = totalBytes
    )
}

internal fun finalizedTemporaryWriteTargetNames(
    audioName: String,
    pendingAudioName: String? = null
): List<String> {
    val normalizedAudioName = audioName.trim().takeIf(String::isNotBlank) ?: return emptyList()
    val normalizedPendingAudioName = pendingAudioName
        ?.trim()
        ?.takeIf { candidate ->
            candidate.substringBefore(PENDING_AUDIO_WRITE_MARKER, candidate) == normalizedAudioName
        }
    return listOfNotNull(
        normalizedPendingAudioName,
        normalizedAudioName,
        "$normalizedAudioName$METADATA_SUFFIX",
        "$normalizedAudioName$PENDING_METADATA_SUFFIX"
    ).distinct()
}

internal class TerminalTemporaryWriteCleanupBatch {
    private val targetNames = linkedSetOf<String>()

    fun addAll(candidates: Collection<String>) {
        candidates.forEach { candidate ->
            candidate.trim().takeIf(String::isNotBlank)?.let(targetNames::add)
        }
    }

    fun takeAll(): List<String> = targetNames.toList().also { targetNames.clear() }

    fun isEmpty(): Boolean = targetNames.isEmpty()
}

internal object TerminalTemporaryWriteCleanupRetryPolicy {
    const val MAX_FAILED_ATTEMPTS = 4

    fun delayMsForFailedAttempt(failedAttempt: Int): Long? {
        if (failedAttempt !in 1..MAX_FAILED_ATTEMPTS) {
            return null
        }
        return TERMINAL_TEMPORARY_WRITE_CLEANUP_RETRY_INITIAL_DELAY_MS *
            (1L shl (failedAttempt - 1))
    }

    private const val TERMINAL_TEMPORARY_WRITE_CLEANUP_RETRY_INITIAL_DELAY_MS = 1_000L
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

internal suspend fun <T> runBoundedDownloadCancellationCleanup(
    items: Collection<T>,
    parallelism: Int,
    action: suspend (T) -> Unit
) {
    require(parallelism > 0) { "parallelism must be positive" }
    val workItems = items.toList()
    if (workItems.isEmpty()) return
    val nextIndex = AtomicInteger(0)
    coroutineScope {
        List(minOf(parallelism, workItems.size)) {
            launch {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= workItems.size) break
                    action(workItems[index])
                }
            }
        }.joinAll()
    }
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
    private const val DOWNLOAD_CANCEL_CLEANUP_PARALLELISM = 4
    private const val DOWNLOAD_CLEAR_PROGRESS_UPDATE_INTERVAL_MS = 100L
    private const val DOWNLOAD_CLEAR_PROGRESS_UPDATE_BATCH_SIZE = 8
    private const val DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS = 25L
    private const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_GRACE_MS = 300L
    private const val DOWNLOAD_RECOVERY_QUEUE_ATTACH_POLL_MS = 50L
    private const val DOWNLOADED_SONG_BUILD_PARALLELISM = 4
    internal const val DOWNLOAD_LIBRARY_SCAN_TARGET_MS = 3_000L
    private const val STARTUP_PROGRESS_RESTORE_WAIT_TIMEOUT_MS = 5_000L
    private const val STARTUP_ARTIFACT_RECOVERY_HANDOFF_DELAY_MS = 250L
    private const val STARTUP_ARTIFACT_RECOVERY_YIELD_BATCH_SIZE = 16
    private const val DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS = 6
    private const val DOWNLOADED_PLAYBACK_RETRY_BASE_DELAY_MS = 50L
    private const val DOWNLOADED_PLAYBACK_RETRY_MAX_DELAY_MS = 250L
    private const val METADATA_WRITE_MAX_ATTEMPTS = 3
    private const val METADATA_WRITE_RETRY_DELAY_MS = 200L
    private const val METADATA_POST_PROCESSING_MAX_ATTEMPTS = 3
    private const val METADATA_POST_PROCESSING_RETRY_DELAY_MS = 350L
    private const val DOWNLOAD_TASK_PROGRESS_EMIT_INTERVAL_NS = 450_000_000L
    private const val METADATA_POST_PROCESSING_PARALLELISM = 2
    private const val WIFI_RECOVERY_PROBE_ATTEMPTS = 6
    private const val WIFI_RECOVERY_PROBE_DELAY_MS = 300L
    private const val SONG_EXECUTION_LOCK_STRIPES = 256
    private const val BATCH_DOWNLOAD_EARLY_HANDOFF_LIMIT = 6
    private const val TERMINAL_TEMPORARY_WRITE_CLEANUP_COALESCE_MS = 750L
    internal const val PLAYBACK_METADATA_HYDRATION_DELAY_MS = 1_500L
    internal const val LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadPresentationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )
    private val downloadedSongBuildDispatcher =
        Dispatchers.IO.limitedParallelism(DOWNLOADED_SONG_BUILD_PARALLELISM)

    private data class FastIndexPersistenceRequest(
        val context: Context,
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        val rebuildToken: ManagedLibraryFastIndexRebuildToken
    )

    private val fastIndexPersistenceLock = Any()
    private var pendingFastIndexPersistence: FastIndexPersistenceRequest? = null
    private var fastIndexPersistenceJob: Job? = null

    private data class DownloadClearSettlement(
        val activeSongKeys: Set<String>,
        val activeOperationIds: Set<String>,
        val batchJobsSettled: Boolean,
        val residualWorkingSongKeys: Set<String> = emptySet(),
        val residualPendingArtifactSongKeys: Set<String> = emptySet()
    ) {
        val isSettled: Boolean
            get() = activeSongKeys.isEmpty() &&
                activeOperationIds.isEmpty() &&
                batchJobsSettled &&
                residualWorkingSongKeys.isEmpty() &&
                residualPendingArtifactSongKeys.isEmpty()
    }

    private data class DownloadTaskCancellationEntry(
        val songKey: String,
        val task: DownloadTask?,
        val cancellationGeneration: Long?
    )

    private data class ActiveProgressCheckpointBinding(
        val operationId: String,
        val attemptId: Long
    )

    private data class FinalizedManagedAudioSnapshot(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        val audio: ManagedDownloadStorage.StoredEntry,
        val metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    )

    private data class PlayableManagedAudioSnapshot(
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

    private data class DownloadedPlaybackResolution(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        val audio: ManagedDownloadStorage.StoredEntry?,
        val reference: String
    )

    private data class DownloadedSongReferenceProbe(
        val reference: String? = null,
        val sawMissing: Boolean = false,
        val sawUncertain: Boolean = false
    )

    private data class DownloadedSongDeleteSession(
        val targetSongs: List<DownloadedSong>,
        val previousSongs: List<DownloadedSong>,
        val deletionKeys: Set<String>,
        val visibilityToken: DownloadedSongDeleteVisibility.Token,
        val clearJob: Job?
    )

    internal enum class DownloadedSongMetadataSyncOutcome {
        SUCCESS,
        NOT_DOWNLOADED,
        FAILED
    }

    private enum class MetadataPostProcessingResult {
        EMBEDDED_VERIFIED,
        UNSUPPORTED_CONTAINER,
        RETRYABLE_FAILURE
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
    private val _batchDownloadPresentations =
        MutableStateFlow<Map<Long, BatchDownloadPresentationState>>(emptyMap())
    val downloadTasks: StateFlow<List<DownloadTask>> = taskStore.downloadTasks
    val downloadTaskSummary: StateFlow<DownloadTaskSummary> = taskStore.downloadTaskSummary
    val activeDownloadOperationsFlow: StateFlow<Boolean> = taskStore.activeDownloadOperationsFlow
    internal val batchDownloadProgressFlow: StateFlow<BatchDownloadOverallProgress?> = combine(
        _batchDownloadPresentations,
        downloadTasks
    ) { presentations: Map<Long, BatchDownloadPresentationState>, tasks: List<DownloadTask> ->
        aggregateBatchDownloadProgress(presentations.values, tasks)
    }.stateIn(
        scope = downloadPresentationScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )
    private val downloadClearVisibility = DownloadClearVisibility()
    val isClearingDownloadTasks: StateFlow<Boolean> = downloadClearVisibility.isClearing
    val isDownloadTaskClearPresentationCleared: StateFlow<Boolean> =
        downloadClearVisibility.isTaskPresentationCleared
    internal val downloadClearProgress: StateFlow<DownloadClearVisibility.ClearProgress?> =
        downloadClearVisibility.progress

    private val _downloadedSongs = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloadedSongs: StateFlow<List<DownloadedSong>> = _downloadedSongs.asStateFlow()
    private val _downloadPresenceVersion = MutableStateFlow(0)
    val downloadPresenceVersion: StateFlow<Int> = _downloadPresenceVersion.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val cancelledSongKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val catalogPersistenceLock = Any()
    private val catalogPersistenceMutex = Mutex()
    private val downloadedSongCatalogMutationLock = Any()
    private val downloadedSongMetadataSyncMutex = Mutex()
    private val downloadedSongDeleteMutex = Mutex()
    private val terminalTemporaryWriteCleanupMutex = Mutex()
    private val terminalTemporaryWriteCleanupBatch = TerminalTemporaryWriteCleanupBatch()
    private var terminalTemporaryWriteCleanupWakeRequested = false
    private val downloadedSongDeletionCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val downloadedSongDeleteVisibility = DownloadedSongDeleteVisibility()
    private val downloadedSongCatalogPersistenceRevision = AtomicLong(0L)
    private val downloadedSongMetadataRevision = AtomicLong(0L)
    private val emptyScanSequence = AtomicLong(0L)
    private var refreshJob: Job? = null
    private val refreshWaiters =
        mutableSetOf<CompletableDeferred<ManagedLibraryRefreshOutcome>>()
    private var catalogPersistJob: Job? = null
    private var catalogReconcileJob: Job? = null
    private var terminalTemporaryWriteCleanupJob: Job? = null
    private var pendingCatalogReconcileForceRefresh = false
    private val metadataPostProcessingSemaphore = Semaphore(METADATA_POST_PROCESSING_PARALLELISM)
    private val downloadedPlaybackRequestGeneration = AtomicLong(0L)
    private val downloadedPlaybackJobLock = Any()
    private var downloadedPlaybackJob: Job? = null

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
    private val startupProgressRestoreReady = CompletableDeferred<Unit>()
    private val trafficRiskRequestIdGenerator = AtomicLong(0L)
    private val mobileDataInterruptionRequestIdGenerator = AtomicLong(0L)
    private val mobileDataDownloadInterruptionEpoch = AtomicLong(0L)
    private val wifiBoundNetworkPolicyEpoch = AtomicLong(0L)
    private val wifiBoundNetworkPolicyMutationLock = Any()
    private val mobileDataDownloadInterruptionRequestMutex = Mutex()
    private val songExecutionLocks = Array(SONG_EXECUTION_LOCK_STRIPES) { Mutex() }
    private val pendingDownloadRecoveryMutex = Mutex()
    private val wifiRecoveryProbeLock = Any()
    private var wifiRecoveryProbeJob: Job? = null
    private val startupRecoveryMutex = Mutex()
    private val startupArtifactRecoveryActive = AtomicBoolean(false)
    private val finalizedCoverRepairActive = AtomicBoolean(false)
    private val activeBatchDownloadJobs = Collections.newSetFromMap(ConcurrentHashMap<Job, Boolean>())
    private val managedDownloadArtifactLeases = ConcurrentHashMap<String, String>()
    private val activeProgressCheckpointBindings =
        ConcurrentHashMap<String, ActiveProgressCheckpointBinding>()
    private val downloadAdmissionGate = DownloadAdmissionGate()
    /**
     * recovery entry points can be triggered by startup and network callbacks at
     * the same time. A suspending slot keeps the later trigger queued instead of
     * dropping it when another recovery is still reconciling durable state
     */
    private val pendingDownloadRecoverySlot = Mutex()

    @Volatile
    private var mobileDataDownloadOverrideAllowed = false

    private fun isDownloadClearFenceActive(context: Context): Boolean {
        return PersistentDownloadClearFenceStore.isActive(context.applicationContext)
    }

    /**
     * 初始目录扫描发布后再收尾 core 音频, 避免 SAF 元数据 I/O 阻塞首屏
     */
    private fun scheduleStartupArtifactRecovery(context: Context) {
        val appContext = context.applicationContext
        if (!startupArtifactRecoveryActive.compareAndSet(false, true)) {
            NPLogger.d(TAG, "跳过重复的启动 artifact 收尾调度")
            return
        }
        scope.launch {
            try {
                delay(STARTUP_ARTIFACT_RECOVERY_HANDOFF_DELAY_MS)
                startupRecoveryMutex.withLock {
                    if (isDownloadClearFenceActive(appContext)) {
                        NPLogger.d(TAG, "下载清空栅栏仍生效，保留启动 artifact 收尾凭据")
                    } else {
                        recoverPendingAudioWritesFromRoot(appContext)
                        yield()
                        recoverUnfinalizedPublishedAudioFromRoot(appContext)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "启动 artifact 收尾失败，保留持久凭据等待后续重试: ${error.message}",
                    error
                )
            } finally {
                startupArtifactRecoveryActive.set(false)
            }
        }
    }

    private fun dismissMobileDataDownloadInterruptionRequest() {
        mobileDataDownloadInterruptionEpoch.incrementAndGet()
        _mobileDataDownloadInterruptionRequest.value = null
    }

    internal fun onWifiBoundDownloadNetworkRestored(
        context: Context,
        reason: String
    ): Boolean {
        val appContext = context.applicationContext
        synchronized(wifiBoundNetworkPolicyMutationLock) {
            if (appContext.currentDownloadNetworkTypeOrNull() != TrafficNetworkType.WIFI) {
                return false
            }
            wifiBoundNetworkPolicyEpoch.incrementAndGet()
            mobileDataDownloadOverrideAllowed = false
            dismissMobileDataDownloadInterruptionRequest()
        }
        NPLogger.d(TAG, "WIFI 下载网络已恢复，已撤销移动网络确认提示: reason=$reason")
        return true
    }

    private fun isWifiBoundNetworkPolicyStillRequired(
        context: Context,
        snapshotEpoch: Long
    ): Boolean {
        return !isDownloadClearFenceActive(context) &&
            isWifiBoundNetworkPolicyObservationCurrent(
                snapshotEpoch = snapshotEpoch,
                currentEpoch = wifiBoundNetworkPolicyEpoch.get(),
                currentNetworkType = context.applicationContext.currentDownloadNetworkTypeOrNull()
            )
    }

    private inline fun mutateWifiBoundNetworkPolicyIfStillRequired(
        context: Context,
        snapshotEpoch: Long,
        mutation: () -> Unit
    ): Boolean {
        return synchronized(wifiBoundNetworkPolicyMutationLock) {
            if (!isWifiBoundNetworkPolicyStillRequired(context, snapshotEpoch)) {
                false
            } else {
                mutation()
                true
            }
        }
    }

    private fun recoverWifiBoundDownloadsIfNetworkPolicyExpired(
        context: Context,
        snapshotEpoch: Long,
        reason: String
    ) {
        if (
            !isWifiBoundNetworkPolicyStillRequired(context, snapshotEpoch) &&
                context.applicationContext.currentDownloadNetworkTypeOrNull() == TrafficNetworkType.WIFI
        ) {
            recoverPendingDownloadsForNetworkRestored(context, reason)
        }
    }

    /**
     * captures an admission generation only after a durable clear has finished
     */
    private suspend fun awaitDownloadAdmissionTicket(context: Context): Long {
        val appContext = context.applicationContext
        while (true) {
            val openTicket = downloadAdmissionGate.openTicketOrNull()
            if (openTicket != null && !isDownloadClearFenceActive(appContext)) {
                return openTicket
            }
            if (openTicket == null) {
                downloadAdmissionGate.awaitOpen()
            } else {
                delay(DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS)
            }
        }
    }

    /**
     * keeps a request bound to the generation that existed when the user made it
     */
    private suspend fun admitDownloadMutation(
        context: Context,
        admissionTicket: Long,
        block: suspend () -> Unit
    ): Boolean {
        val appContext = context.applicationContext
        if (
            isDownloadClearFenceActive(appContext) ||
                downloadAdmissionGate.openTicketOrNull() != admissionTicket
        ) {
            return false
        }
        var ranBlock = false
        val admitted = downloadAdmissionGate.admit(admissionTicket) admission@{
            if (isDownloadClearFenceActive(appContext)) {
                return@admission
            }
            ranBlock = true
            block()
        }
        return admitted && ranBlock
    }

    /**
     * keeps a new operation non-runnable until queue persistence can be committed atomically
     */
    private data class StagedPendingDownloadQueue(
        val operationIds: List<String>,
        val skippedSongKeys: Set<String>
    )

    private suspend fun stageAndPromotePendingDownloadQueue(
        context: Context,
        songs: List<SongItem>,
        userInitiated: Boolean
    ): StagedPendingDownloadQueue? {
        val distinctSongs = songs.distinctBy(SongItem::stableKey)
        if (distinctSongs.isEmpty()) {
            return StagedPendingDownloadQueue(
                operationIds = emptyList(),
                skippedSongKeys = emptySet()
            )
        }
        val currentNetworkType = context.currentDownloadNetworkTypeOrNull()
        val requiresWifiNetwork = !userInitiated ||
            currentNetworkType == null || currentNetworkType == TrafficNetworkType.WIFI
        val downloadAudioQuality = resolveDownloadAudioQualitySelection(context)
        val recoveryStore = DownloadRecoveryRoomStore(context.applicationContext)
        val existingReusableOperationIds = DownloadExecutionRoomStore
            .findReadableOperationsBySongKeys(
                context = context.applicationContext,
                songKeys = distinctSongs.map(SongItem::stableKey),
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                excludeUserStoppedOperations = true
            )
            .values
            .map(DownloadExecutionRequest::operationId)
            .distinct()
        val waitingOperationIds = recoveryStore.upsertWaitingStorageMutation(
            songs = distinctSongs,
            userInitiated = userInitiated,
            requiresWifiNetwork = requiresWifiNetwork,
            downloadAudioQuality = downloadAudioQuality
        )
        if (ManagedDownloadDirectoryMutationFence.isActive(context)) {
            existingReusableOperationIds.forEach { operationId ->
                DownloadExecutionRoomStore.markWaitingForStorageMutation(
                    context = context.applicationContext,
                    operationId = operationId,
                    errorCode = DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
                )
            }
            val allWaitingOperationIds = (
                waitingOperationIds + existingReusableOperationIds
                ).distinct().filter { operationId ->
                    DownloadExecutionRoomStore.state(context, operationId) ==
                        WAITING_STORAGE_MUTATION_OPERATION_STATE
                }
            NPLogger.d(
                TAG,
                "下载目录迁移期间保留持久等待意图: waiting=${allWaitingOperationIds.size}"
            )
            return StagedPendingDownloadQueue(
                operationIds = allWaitingOperationIds,
                skippedSongKeys = emptySet()
            )
        }
        val waitingOperationSnapshots = DownloadExecutionRoomStore.readOperationSnapshots(
            context = context,
            operationIds = waitingOperationIds
        )
        val skippedSongKeys = linkedSetOf<String>()
        for (operationId in waitingOperationIds) {
            val request = waitingOperationSnapshots[operationId]?.request
            if (request == null) {
                NPLogger.w(
                    TAG,
                    "下载意图在提升前丢失，停止当前请求以避免错误重建: " +
                        "operationId=$operationId"
                )
                return null
            }
            val promoted = recoveryStore.promoteWaitingStorageMutation(
                operationId = operationId,
                stableKey = request.song.stableKey()
            )
            if (!promoted) {
                val latestState = DownloadExecutionRoomStore.state(context, operationId)
                if (
                    latestState in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES ||
                        latestState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES
                ) {
                    NPLogger.d(
                        TAG,
                        "下载意图已被并行恢复接管，复用现有 operation: " +
                            "operationId=$operationId, state=$latestState"
                    )
                    continue
                }
                skippedSongKeys += request.song.stableKey()
                NPLogger.w(
                    TAG,
                    "下载意图在存储变更期间不可提升，仅跳过该歌曲: " +
                        "operationId=$operationId, state=$latestState"
                )
            }
        }
        val queueableSongs = distinctSongs.filterNot { song ->
            song.stableKey() in skippedSongKeys
        }
        return StagedPendingDownloadQueue(
            operationIds = rememberPendingDownloadQueue(
                context = context,
                songs = queueableSongs,
                userInitiated = userInitiated,
                requiresWifiNetwork = requiresWifiNetwork,
                downloadAudioQuality = downloadAudioQuality
            ),
            skippedSongKeys = skippedSongKeys
        )
    }

    private suspend fun promoteWaitingStorageMutationsForRecovery(context: Context): Int {
        val appContext = context.applicationContext
        if (ManagedDownloadDirectoryMutationFence.isActive(appContext)) {
            return 0
        }
        try {
            DownloadExecutionRoomStore.rehomeActiveOperationsToCurrentLibrary(appContext)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "恢复目录迁移等待意图前重绑定失败，保留 operation 待下次重试: " +
                    error.message,
                error
            )
        }
        val recoveryStore = DownloadRecoveryRoomStore(appContext)
        val waitingEntries = recoveryStore.listWaitingStorageMutations()
        if (waitingEntries.isEmpty()) {
            return 0
        }
        val admissionTicket = awaitDownloadAdmissionTicket(appContext)
        var promotedCount = 0
        val admitted = admitDownloadMutation(appContext, admissionTicket) {
            waitingEntries.forEach { entry ->
                if (
                    recoveryStore.promoteWaitingStorageMutation(
                        operationId = entry.request.operationId,
                        stableKey = entry.request.song.stableKey()
                    )
                ) {
                    promotedCount += 1
                }
            }
        }
        if (admitted && promotedCount > 0) {
            NPLogger.d(
                TAG,
                "恢复存储变更等待下载意图: promoted=$promotedCount, " +
                    "waiting=${waitingEntries.size}"
            )
        }
        return promotedCount
    }

    internal fun recoverPendingDownloadsAfterStorageMutation(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            withPendingDownloadRecoverySlot("directory_mutation_complete") {
                ManagedDownloadDirectoryMutationFence.awaitOpen()
                if (ManagedDownloadDirectoryMutationFence.isActive(appContext)) {
                    return@withPendingDownloadRecoverySlot
                }
                val promotedCount = promoteWaitingStorageMutationsForRecovery(appContext)
                if (promotedCount > 0) {
                    recoverPendingResumableDownloads(
                        context = appContext,
                        reason = "directory_mutation_complete"
                    )
                }
            }
        }
    }

    /**
     * serializes every durable recovery trigger while allowing the caller to
     * suspend until the current recovery has released the slot
     */
    private suspend inline fun <T> withPendingDownloadRecoverySlot(
        reason: String,
        crossinline block: suspend () -> T
    ): T {
        return pendingDownloadRecoverySlot.withLock {
            NPLogger.d(TAG, "下载恢复进入串行槽位: reason=$reason")
            block()
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        observeDownloadProgress()
        observeStorageStartupRecovery(appContext)
        // restore the persisted banner state before the startup coroutine is
        // scheduled, so a restarted migration is visible on the first frame
        ManagedLibraryProcessingCoordinator.restoreImmediately(appContext)
        scope.launch {
            runDownloadStartupRecoverySafely(
                block = {
            val restoredProcessingState =
                ManagedLibraryProcessingCoordinator.restore(appContext)
            val restoredDirectoryOperationId = restoredProcessingState
                .takeIf {
                    it.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE
                }
                ?.operationId
            startupRecoveryMutex.withLock recovery@{
                if (PersistentDownloadClearFenceStore.isActive(appContext)) {
                    startupProgressRestoreReady.complete(Unit)
                    val clearPurpose = PersistentDownloadClearFenceStore.activePurpose(appContext)
                    if (clearPurpose == DownloadClearPurpose.TASK_PROGRESS) {
                        // 清空的是任务展示，不是已经提交的音频。先恢复 catalog，
                        // 防止进程死亡后的瞬时空扫描把可播放歌曲变成白色条目。
                        restorePersistedDownloadedSongs(appContext)
                    }
                    NPLogger.w(
                        TAG,
                        "检测到未完成的下载清空，跳过启动恢复并继续收敛: purpose=$clearPurpose"
                    )
                    DownloadExecutionHosts.cancelAllOwned(appContext)
                    requestAllDownloadTaskCancellation(
                        purpose = PersistentDownloadClearFenceStore.activePurpose(appContext)
                    )
                    restoredDirectoryOperationId?.let { operationId ->
                        ManagedLibraryProcessingCoordinator.waitingForRetry(
                            appContext,
                            operationId
                        )
                    }
                    return@recovery
                }
                // 先记录系统用户主动结束进程，再恢复任务卡片；否则旧的 RUNNING
                // 行会在卡片恢复后才被标记，下一轮刷新可能永久保留旧进度状态
                val processStoppedKeys = DownloadExecutionHosts.default
                    .markUserRequestedProcessExitOperations(appContext)
                if (processStoppedKeys.isNotEmpty()) {
                    NPLogger.i(
                        TAG,
                        "检测到系统用户停止进程，暂停未完成 UIDT 下载: " +
                            "count=${processStoppedKeys.size}"
                    )
                }
                // Room progress is restored before the interactive gate so a
                // restarted worker cannot run with an empty task presentation
                restorePersistedDownloadProgress(appContext)
                // 迁移凭据必须先于任何旧库回填或目录 I/O 恢复。迁移 worker
                // 会独占目录栅栏并在最终扫描发布后再放行后续启动流程
                val migrationRecoveryPresent = try {
                    ManagedDownloadMigrationWorker.hasPersistedMigrationRecovery(appContext)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "迁移恢复凭据检查失败，保守跳过启动目录操作: ${error.message}",
                        error
                    )
                    restoredDirectoryOperationId?.let { operationId ->
                        ManagedLibraryProcessingCoordinator.waitingForRetry(
                            appContext,
                            operationId
                        )
                    }
                    return@recovery
                }
                val resumedMigrationWorkId = try {
                    ManagedDownloadMigrationWorker.resumePersistedRequestIfNeeded(appContext)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NPLogger.w(
                        TAG,
                        "迁移恢复检查暂时失败，保留请求并跳过启动目录操作: ${error.message}",
                        error
                    )
                    restoredDirectoryOperationId?.let { operationId ->
                        ManagedLibraryProcessingCoordinator.waitingForRetry(
                            appContext,
                            operationId
                        )
                    }
                    return@recovery
                }
                if (resumedMigrationWorkId != null || migrationRecoveryPresent) {
                    NPLogger.i(
                        TAG,
                        if (resumedMigrationWorkId != null) {
                            "启动恢复迁移任务，跳过并行目录扫描: workId=$resumedMigrationWorkId"
                        } else {
                            "检测到待处理迁移凭据，跳过并行目录扫描，等待显式恢复"
                        }
                    )
                    return@recovery
                }
                // migration recovery is dispatched before the interactive gate so
                // a restarted transfer can resume its durable progress immediately
                AppStartupWorkGate.awaitInteractiveContentOrTimeout()
                try {
                    LegacyJsonCleanupScheduler.runDownloadUpgradeOnce(appContext)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NPLogger.w(TAG, "旧下载数据回填待重试: ${error.message}")
                }
                val startupRecovery = ManagedDownloadStorage.consumeStartupRecoveryResult()
                if (startupRecovery.failedCount > 0) {
                    NPLogger.w(
                        TAG,
                        "启动存储恢复存在未确认项，安排持久临时写入清理复查: " +
                            "failed=${startupRecovery.failedCount}"
                    )
                    schedulePersistedTerminalTemporaryWriteCleanup(appContext)
                }
                val restoredCatalog = restorePersistedDownloadedSongs(appContext)
                DownloadExecutionOperationStore().pruneTerminalOperations(
                    context = appContext,
                    cutoffMs = System.currentTimeMillis() -
                        TERMINAL_OPERATION_RETENTION_MS,
                    limit = TERMINAL_OPERATION_PRUNE_LIMIT
                )
                recoverPendingDownloadsForStartup(appContext)
                repairFinalizedDownloadedCoversFromRoot(appContext)
                LegacyJsonCleanupScheduler.schedule(appContext, "download-startup")
                if (
                    !shouldRunInitialDownloadScan(
                        catalogReady = restoredCatalog,
                        hasRecoveredEntries = startupRecovery.hasRecoveredEntries
                    ) && restoredDirectoryOperationId == null
                ) {
                    return@recovery
                }
                delay(INITIAL_SCAN_DELAY_MS)
                val refreshOutcome = scanLocalFilesAwait(
                    appContext,
                    forceRefresh = true
                )
                if (refreshOutcome is ManagedLibraryRefreshOutcome.Published) {
                    scheduleStartupArtifactRecovery(appContext)
                } else {
                    NPLogger.w(
                        TAG,
                        "初始下载目录扫描未发布，保留 artifact 收尾凭据等待后续重试: " +
                            "outcome=${refreshOutcome::class.simpleName}"
                    )
                }
                restoredDirectoryOperationId?.let { operationId ->
                    if (refreshOutcome is ManagedLibraryRefreshOutcome.Published) {
                        ManagedLibraryProcessingCoordinator.complete(
                            appContext,
                            operationId
                        )
                    } else {
                        ManagedLibraryProcessingCoordinator.waitingForRetry(
                            appContext,
                            operationId
                        )
                    }
                }
            }
                },
                onFailure = { error ->
                    startupProgressRestoreReady.complete(Unit)
                    NPLogger.e(
                        TAG,
                        "下载启动恢复任务失败，保留持久化状态等待下次重试: " +
                            "error=${error.message}",
                        error
                    )
                }
            )
        }
    }

    internal suspend fun reconcileMaterializedLegacyDownloads(context: Context) {
        val appContext = context.applicationContext
        startupRecoveryMutex.withLock {
            if (PersistentDownloadClearFenceStore.isActive(appContext)) return
            recoverPendingAudioWritesFromRoot(appContext)
            recoverUnfinalizedPublishedAudioFromRoot(appContext)
            recoverPendingDownloadsForStartup(appContext)
            repairFinalizedDownloadedCoversFromRoot(appContext)
            scanLocalFilesAwait(appContext, forceRefresh = true)
        }
    }

    private const val TERMINAL_OPERATION_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val TERMINAL_OPERATION_PRUNE_LIMIT = 64

    private suspend fun recoverPendingAudioWritesFromRoot(context: Context) {
        val metadataPostProcessingEnabled = isDownloadMetadataPostProcessingEnabled(context)
        val pendingAudioWrites = runCatching {
            ManagedDownloadStorage.listPendingAudioWrites(
                context = context,
                forceRefresh = true
            )
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            NPLogger.w(TAG, "读取 pending 音频失败，保留文件等待下次启动: ${error.message}")
            return
        }
        for ((index, pendingAudio) in pendingAudioWrites.withIndex()) {
            if (
                index > 0 &&
                    index % STARTUP_ARTIFACT_RECOVERY_YIELD_BATCH_SIZE == 0
            ) {
                yield()
            }
            runCatching {
                val metadata = readDownloadedMetadata(context, pendingAudio)
                    ?: return@runCatching
                val song = buildSongFromDurableMetadata(pendingAudio, metadata)
                    ?: return@runCatching
                withSongExecutionLock(song.stableKey()) {
                    val currentMetadata = readDownloadedMetadata(context, pendingAudio)
                        ?: return@withSongExecutionLock
                    if (
                        metadataPostProcessingEnabled &&
                            currentMetadata.metadataEmbeddingState ==
                                DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
                    ) {
                        NPLogger.d(
                            TAG,
                            "跳过不支持内嵌标签的 pending 音频自动恢复: " +
                                "song=${song.name}, file=${pendingAudio.name}"
                        )
                        return@withSongExecutionLock
                    }
                    val artifactLeaseId = currentMetadata.operationId
                        ?.let { operationId ->
                            DownloadExecutionRoomStore.read(context, operationId)?.artifactLeaseId
                        }
                        ?: managedDownloadArtifactCoordinator.currentLeaseId(context, song)
                    if (isFinalizedDownloadedMetadata(currentMetadata)) {
                        val published = publishFinalizedDownload(
                            context = context,
                            song = song,
                            storedAudio = pendingAudio,
                            sidecarReferences = null,
                            expectedAttemptId = null,
                            operationId = currentMetadata.operationId,
                            expectedArtifactLeaseId = artifactLeaseId,
                            refreshCatalog = false,
                            allowMissingTask = true
                        )
                        if (!published) {
                            NPLogger.w(
                                TAG,
                                "pending 音频已有完成凭据但提升未确认，保留等待恢复: " +
                                    "song=${song.name}, file=${pendingAudio.name}"
                            )
                        }
                        return@withSongExecutionLock
                    }
                    NPLogger.d(
                        TAG,
                        "从 pending 音频恢复元信息收尾: " +
                            "song=${song.name}, file=${pendingAudio.name}"
                    )
                    finalizeCompletedDownload(
                        context = context,
                        song = song,
                        refreshCatalog = false,
                        operationId = currentMetadata.operationId,
                        expectedArtifactLeaseId = artifactLeaseId,
                        storedAudioHint = pendingAudio,
                        allowMissingTask = true
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                NPLogger.w(
                    TAG,
                    "恢复 pending 音频失败，保留等待重试: " +
                        "file=${pendingAudio.name}, error=${error.message}"
                )
            }
        }
    }

    private suspend fun recoverUnfinalizedPublishedAudioFromRoot(context: Context) {
        val metadataPostProcessingEnabled = isDownloadMetadataPostProcessingEnabled(context)
        val snapshot = runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            NPLogger.w(TAG, "读取待收尾下载音频失败，等待下次恢复: ${error.message}")
            return
        }
        for ((index, audio) in snapshot.audioEntries.withIndex()) {
            if (
                index > 0 &&
                    index % STARTUP_ARTIFACT_RECOVERY_YIELD_BATCH_SIZE == 0
            ) {
                yield()
            }
            val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
                ?: readDownloadedMetadata(context, audio)
                ?: continue
            val song = buildSongFromDurableMetadata(audio, metadata) ?: continue
            runCatching {
                withSongExecutionLock(song.stableKey()) {
                    val currentMetadata = readDownloadedMetadata(context, audio)
                        ?: return@withSongExecutionLock
                    if (
                        metadataPostProcessingEnabled &&
                            currentMetadata.metadataEmbeddingState ==
                                DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
                    ) {
                        NPLogger.d(
                            TAG,
                            "跳过不支持内嵌标签的已发布音频自动恢复: " +
                                "song=${song.name}, file=${audio.name}"
                        )
                        return@withSongExecutionLock
                    }
                    val artifactLeaseId = currentMetadata.operationId
                        ?.let { operationId ->
                            DownloadExecutionRoomStore.read(context, operationId)?.artifactLeaseId
                        }
                        ?: managedDownloadArtifactCoordinator.currentLeaseId(context, song)
                    val operationState = currentMetadata.operationId
                        ?.let { operationId -> DownloadExecutionRoomStore.state(context, operationId) }
                    val artifactState = managedDownloadArtifactCoordinator.currentState(context, song)
                        ?.name
                        ?: currentMetadata.artifactState
                    if (
                        isFinalizedDownloadedMetadata(currentMetadata) &&
                            requiresFinalizedPublicationRecovery(
                                metadataFinalized = true,
                                operationState = operationState,
                                artifactState = artifactState
                            )
                    ) {
                        val published = publishFinalizedDownload(
                            context = context,
                            song = song,
                            storedAudio = audio,
                            sidecarReferences = null,
                            expectedAttemptId = null,
                            operationId = currentMetadata.operationId,
                            expectedArtifactLeaseId = artifactLeaseId,
                            refreshCatalog = false,
                            allowMissingTask = true
                        )
                        if (!published) {
                            NPLogger.w(
                                TAG,
                                "恢复完成凭据后的发布失败，保留等待重试: " +
                                    "song=${song.name}, file=${audio.name}"
                            )
                        }
                        return@withSongExecutionLock
                    }
                    if (!isUnfinalizedDownloadedMetadata(currentMetadata)) {
                        return@withSongExecutionLock
                    }
                    finalizeCompletedDownload(
                        context = context,
                        song = song,
                        refreshCatalog = false,
                        operationId = currentMetadata.operationId,
                        expectedArtifactLeaseId = artifactLeaseId,
                        storedAudioHint = audio,
                        allowMissingTask = true
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                NPLogger.w(
                    TAG,
                    "恢复未最终化已发布音频失败，保留等待重试: " +
                        "song=${song.name}, file=${audio.name}, error=${error.message}"
                )
            }
        }
    }

    private fun repairFinalizedDownloadedCoversFromRoot(context: Context) {
        val appContext = context.applicationContext
        if (!finalizedCoverRepairActive.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                if (appContext.currentDownloadNetworkTypeOrNull() != TrafficNetworkType.WIFI) {
                    return@launch
                }
                val snapshot = runCatching {
                    ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                        context = appContext,
                        forceRefresh = true
                    )
                }.getOrElse { error ->
                    NPLogger.w(TAG, "读取历史缺失封面失败，等待下次恢复: ${error.message}")
                    return@launch
                }
                val candidates = snapshot.audioEntries.mapNotNull { audio ->
                    val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
                        ?: readDownloadedMetadata(appContext, audio)
                        ?: return@mapNotNull null
                    if (!isFinalizedDownloadedMetadata(metadata)) {
                        return@mapNotNull null
                    }
                    val song = buildSongFromDurableMetadata(audio, metadata)
                        ?: return@mapNotNull null
                    if (AudioDownloadManager.buildCoverDownloadCandidateUrls(song).isEmpty()) {
                        return@mapNotNull null
                    }
                    song to audio
                }
                if (candidates.isEmpty()) {
                    return@launch
                }
                val nextCandidateIndex = AtomicInteger(0)
                coroutineScope {
                    List(
                        size = minOf(METADATA_POST_PROCESSING_PARALLELISM, candidates.size)
                    ) {
                        async {
                            while (true) {
                                val candidate = candidates.getOrNull(nextCandidateIndex.getAndIncrement())
                                    ?: return@async
                                val (song, audio) = candidate
                                withSongExecutionLock(song.stableKey()) {
                                    val currentMetadata = readDownloadedMetadata(appContext, audio)
                                        ?: return@withSongExecutionLock
                                    if (!isFinalizedDownloadedMetadata(currentMetadata)) {
                                        return@withSongExecutionLock
                                    }
                                    val beforeRepair = buildOptimisticDownloadedSong(song, audio)
                                    val repaired = repairDownloadedCoverIfMissing(
                                        context = appContext,
                                        song = song,
                                        downloadedSong = beforeRepair
                                    )
                                    if (repaired.coverPath != beforeRepair.coverPath) {
                                        publishOptimisticDownloadedSongs(appContext, listOf(repaired))
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
                scheduleCatalogReconcile(appContext, forceRefresh = true)
            } finally {
                finalizedCoverRepairActive.set(false)
            }
        }
    }

    private fun observeStorageStartupRecovery(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            ManagedDownloadStorage.startupRecoveryResults.collect { result ->
                if (!result.hasRecoveredEntries) {
                    return@collect
                }
                if (result.failedCount > 0) {
                    NPLogger.w(
                        TAG,
                        "后台下载启动清理存在未确认项，安排持久临时写入清理复查: " +
                            "failed=${result.failedCount}"
                    )
                    schedulePersistedTerminalTemporaryWriteCleanup(appContext)
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
        withPendingDownloadRecoverySlot("startup") {
            if (isDownloadClearFenceActive(appContext)) {
                NPLogger.d(TAG, "跳过启动下载恢复: 清空栅栏仍在生效")
                return@withPendingDownloadRecoverySlot
            }
            promoteWaitingStorageMutationsForRecovery(appContext)
            if (!hasPendingRecoveryCandidates(appContext)) {
                return@withPendingDownloadRecoverySlot
            }
            reconcilePendingDownloadArtifacts(appContext)
            waitForActiveDownloadJobsToSettle()
            waitForQueuedTasksToAttachToBatch()
            if (hasBlockingActiveDownloadOperationsForRecovery()) {
                NPLogger.d(TAG, "延后启动下载恢复: 当前已有活动下载")
                return@withPendingDownloadRecoverySlot
            }
            val deferredSongKeys = deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(
                context = appContext,
                reason = "startup"
            )
            recoverPendingResumableDownloads(
                context = appContext,
                reason = "startup",
                excludedSongKeys = deferredSongKeys
            )
            delay(1_500L)
        }
    }

    /**
     * 启动时先从 Room 恢复任务卡片，再决定是否交给下载宿主继续执行
     *
     * 这一步不枚举 SAF 目录，文件不可见时仍可用 durable checkpoint 显示真实进度。
     */
    private suspend fun restorePersistedDownloadProgress(context: Context) {
        try {
            val entries = runCatching {
                DownloadExecutionRoomStore.listProgressEntriesAnyLibrary(context)
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "启动恢复下载进度失败，保留 Room 状态等待后续恢复: ${error.message}",
                    error
                )
                return
            }
            if (entries.isEmpty()) return
            val latestBySongKey = entries
                .asSequence()
                .filter { entry -> entry.request.song.stableKey().isNotBlank() }
                .groupBy { entry -> entry.request.song.stableKey() }
                .mapValues { (_, candidates) ->
                    candidates.maxWithOrNull(
                        compareBy<DownloadExecutionRoomStore.ProgressEntry> {
                            it.updatedAtMs
                        }.thenBy { it.request.operationId }
                    )
                }
                .values
                .filterNotNull()
                .toList()
            if (latestBySongKey.isEmpty()) return
            val stoppedEntries = latestBySongKey.filter { entry ->
                entry.stopRequestedByUser || entry.state == "STOPPED"
            }
            val queuedEntries = latestBySongKey - stoppedEntries.toSet()
            val stoppedAttemptIds = taskStore.ensureDownloadTasks(
                songs = stoppedEntries.map { entry -> entry.request.song },
                status = DownloadStatus.WAITING_NETWORK,
                durableAttemptIds = stoppedEntries.mapNotNull { entry ->
                    entry.request.attemptId?.takeIf { it > 0L }?.let { attemptId ->
                        entry.request.song.stableKey() to attemptId
                    }
                }.toMap()
            )
            val queuedAttemptIds = taskStore.ensureDownloadTasks(
                songs = queuedEntries.map { entry -> entry.request.song },
                status = DownloadStatus.QUEUED,
                durableAttemptIds = queuedEntries.mapNotNull { entry ->
                    entry.request.attemptId?.takeIf { it > 0L }?.let { attemptId ->
                        entry.request.song.stableKey() to attemptId
                    }
                }.toMap()
            )
            val effectiveAttemptIds = stoppedAttemptIds + queuedAttemptIds
            latestBySongKey.forEach { entry ->
                if (entry.request.attemptId == null) {
                    val attemptId = effectiveAttemptIds[entry.request.song.stableKey()]
                    if (attemptId != null) {
                        runCatching {
                            DownloadExecutionRoomStore.ensureAttemptId(
                                context = context,
                                operationId = entry.request.operationId,
                                stableKey = entry.request.song.stableKey(),
                                attemptId = attemptId
                            )
                        }.onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "补写旧下载 operation attemptId 失败，保留本次卡片恢复: " +
                                    "operationId=${entry.request.operationId}, " +
                                    "error=${error.message}"
                            )
                        }
                    }
                }
            }
            val restoredProgresses = latestBySongKey.mapNotNull { entry ->
                val request = entry.request
                val songKey = request.song.stableKey()
                val attemptId = request.attemptId?.takeIf { it > 0L }
                    ?: effectiveAttemptIds[songKey]
                    ?: return@mapNotNull null
                val stopped = entry.stopRequestedByUser || entry.state == "STOPPED"
                val restoredProgress = resolveRecoveredDownloadProgress(
                    workingFileBytes = 0L,
                    checkpointTotalBytes = entry.totalBytes,
                    checkpointBytesWritten = entry.bytesWritten
                ) ?: return@mapNotNull null
                AudioDownloadManager.DownloadProgress(
                    songKey = songKey,
                    songId = request.song.id,
                    fileName = request.song.name,
                    bytesRead = restoredProgress.bytesRead,
                    totalBytes = restoredProgress.totalBytes,
                    speedBytesPerSec = 0L,
                    stage = if (stopped) {
                        AudioDownloadManager.DownloadStage.WAITING_RETRY
                    } else {
                        AudioDownloadManager.DownloadStage.TRANSFERRING
                    },
                    attemptId = attemptId
                )
            }
            val restoredCount = taskStore.restoreProgressBatch(restoredProgresses)
            NPLogger.d(
                TAG,
                "启动恢复下载进度任务卡片: operations=${latestBySongKey.size}, " +
                    "restored=$restoredCount, withBytes=${latestBySongKey.count { it.bytesWritten > 0L }}"
            )
        } finally {
            startupProgressRestoreReady.complete(Unit)
        }
    }

    private suspend fun reconcilePendingDownloadArtifacts(context: Context) {
        val durableSongs = try {
            DownloadExecutionRoomStore.listByStatesAnyLibrary(
                context = context,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                    DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                    listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
                excludeUserStoppedOperations = true
            ).map { entry -> entry.request.song }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "读取持久下载 artifact 对账候选失败: ${error.message}",
                error
            )
            emptyList()
        }
        val songs = buildList {
            addAll(
                ManagedDownloadStorage.listPendingQueuedDownloads(context)
                    .map(ManagedDownloadStorage.PendingDownloadQueueEntry::song)
            )
            addAll(
                ManagedDownloadStorage.listPendingResumableDownloads(context)
                    .map(ManagedDownloadStorage.PendingResumableDownload::song)
            )
            addAll(durableSongs)
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
        reason: String,
        excludedSongKeys: Set<String> = emptySet()
    ): Boolean {
        if (isDownloadClearFenceActive(context)) {
            NPLogger.d(TAG, "跳过未完成下载恢复: 清空栅栏仍在生效, reason=$reason")
            return false
        }
        if (ManagedDownloadDirectoryMutationFence.isActive(context)) {
            NPLogger.d(TAG, "跳过未完成下载恢复: 目录迁移栅栏仍在生效, reason=$reason")
            return false
        }
        return pendingDownloadRecoveryMutex.withLock {
            recoverPendingResumableDownloadsLocked(
                context = context,
                reason = reason,
                excludedSongKeys = excludedSongKeys
            )
        }
    }

    private suspend fun recoverPendingResumableDownloadsLocked(
        context: Context,
        reason: String,
        excludedSongKeys: Set<String>
    ): Boolean {
        return try {
            val recoveryPlan = resolvePendingDownloadRecoveryPlan(context)
            removeObsoleteWaitingNetworkTasks(recoveryPlan.recoveryCandidateKeys)
            if (recoveryPlan.recoveryCandidates.isEmpty()) {
                if (recoveryPlan.pendingQueuedDownloads.isEmpty() && recoveryPlan.pendingDownloads.isEmpty()) {
                    DownloadExecutionRoomStore.purgeAllCancelled(context)
                }
                return false
            }

            forgetPendingDownloadQueueEntries(context, recoveryPlan.settledSongKeys)
            DownloadExecutionRoomStore.purgeCancelled(context, recoveryPlan.settledSongKeys)

            val resumableSongs = recoveryPlan.resumableSongs.filterNot { song ->
                song.stableKey() in excludedSongKeys
            }
            if (resumableSongs.isEmpty()) {
                return false
            }

            val antiJoinedResumableSongs =
                managedDownloadArtifactCoordinator.filterNotFinalized(
                    context = context,
                    songs = resumableSongs
                )
            val finalizedRecoveryKeys = resumableSongs
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
                    "deferred=${excludedSongKeys.size}, " +
                    "antiJoinedFinalized=${finalizedRecoveryKeys.size}, " +
                    "explicitResume=${recoveryPlan.explicitResumeKeys.size}, " +
                    "queued=${recoveryPlan.pendingQueuedDownloads.size}, " +
                    "partial=${recoveryPlan.pendingDownloads.size}"
            )
            if (antiJoinedResumableSongs.isEmpty()) {
                return false
            }
            startBatchDownload(
                context = context,
                songs = antiJoinedResumableSongs,
                skipTrafficRiskPrompt = true,
                cleanupBeforeStart = false,
                deferForNetworkPolicy = true,
                userInitiated = false
            ) != null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.e(TAG, "自动恢复未完成下载失败: ${error.message}", error)
            false
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
        val durableOperationEntries = if (
            ManagedDownloadDirectoryMutationFence.isActive(context)
        ) {
            emptyList()
        } else try {
            // 迁移栅栏打开后才允许重绑定。若事务失败，暂不把旧 root
            // 行伪装成当前队列，避免 startBatchDownload 创建重复 operation
            DownloadExecutionRoomStore.rehomeActiveOperationsToCurrentLibrary(context)
            DownloadExecutionRoomStore.listByStates(
                context = context,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                    DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                    listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
                excludeUserStoppedOperations = true
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "读取持久下载 operation 失败，保留文件队列等待重试: ${error.message}",
                error
            )
            emptyList()
        }
        val durableOperationBySongKey = durableOperationEntries
            .groupBy { entry -> entry.request.song.stableKey() }
            .mapValues { (_, entries) ->
                entries.maxWithOrNull(
                    compareBy<DownloadExecutionRoomStore.StateEntry> { it.updatedAtMs }
                        .thenBy { durableOperationRecoveryPriority(it.state) }
                        .thenBy { it.request.operationId }
                )?.request
            }
            .filterValues { it != null }
            .mapValues { (_, request) -> requireNotNull(request) }
        val durableQueueDownloads = durableOperationBySongKey.values
            .sortedWith(compareBy(DownloadExecutionRequest::operationId))
            .mapIndexed { index, request ->
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = request.song.stableKey(),
                    song = request.song,
                    order = pendingQueuedDownloads.size + index,
                    queuedAtMs = 0L,
                    operationId = request.operationId,
                    requiresWifiNetwork = request.requiresWifiNetwork
                )
            }
        val cancelledDownloadKeys = DownloadExecutionRoomStore.listByStatesAnyLibrary(
            context = context,
            states = listOf("CANCEL_REQUESTED", "CANCELLED")
        ).mapTo(linkedSetOf()) { entry -> entry.request.song.stableKey() }
        val externallyStoppedSongKeys = DownloadExecutionHosts.default
            .externallyStoppedSongKeys(context)
        val durableNetworkPoliciesBySongKey = durableOperationEntries
            .sortedWith(
                compareBy<DownloadExecutionRoomStore.StateEntry> { it.updatedAtMs }
                    .thenBy { durableOperationRecoveryPriority(it.state) }
                    .thenBy { it.request.operationId }
            )
            .associate { entry ->
                entry.request.song.stableKey() to entry.request.requiresWifiNetwork
            }
        val recoveryCandidates = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = pendingQueuedDownloads + durableQueueDownloads,
            resumableDownloads = pendingDownloads,
            cancelledKeys = cancelledDownloadKeys
        ).map { candidate ->
            val durableRequest = durableOperationBySongKey[candidate.song.stableKey()]
            candidate.copy(
                operationId = durableRequest?.operationId ?: candidate.operationId,
                requiresWifiNetwork = durableNetworkPoliciesBySongKey[
                    candidate.song.stableKey()
                ] ?: candidate.requiresWifiNetwork
            )
        }
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
    ): Set<String> {
        if (ManagedDownloadDirectoryMutationFence.isActive(context)) {
            NPLogger.d(
                TAG,
                "目录迁移期间不改写网络等待状态，迁移完成后再恢复: reason=$reason"
            )
            return emptySet()
        }
        val networkType = context.currentDownloadNetworkTypeOrNull()
        if (!shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = networkType,
                mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
            )
        ) {
            return emptySet()
        }
        val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()

        val recoveryPlan = resolvePendingDownloadRecoveryPlan(context)
        val waitingTaskSongs = currentWaitingNetworkTaskSongs()
        val recoverableWaitingKeys = recoveryPlan.recoveryCandidateKeys +
            waitingTaskSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
        removeObsoleteWaitingNetworkTasks(recoverableWaitingKeys)
        if (recoveryPlan.recoveryCandidates.isEmpty() && waitingTaskSongs.isEmpty()) {
            if (recoveryPlan.pendingQueuedDownloads.isEmpty() && recoveryPlan.pendingDownloads.isEmpty()) {
                DownloadExecutionRoomStore.purgeAllCancelled(context)
            }
            return emptySet()
        }

        forgetPendingDownloadQueueEntries(context, recoveryPlan.settledSongKeys)
        DownloadExecutionRoomStore.purgeCancelled(context, recoveryPlan.settledSongKeys)

        val resumableSongKeys = recoveryPlan.resumableSongs
            .mapTo(linkedSetOf()) { song -> song.stableKey() }
        val wifiBoundResumableSongs = recoveryPlan.recoveryCandidates
            .asSequence()
            .filter { candidate ->
                candidate.song.stableKey() in resumableSongKeys &&
                    shouldPauseDownloadForWifiDisconnect(candidate.requiresWifiNetwork)
            }
            .map(PendingDownloadRecoveryCandidate::song)
            .toList()
        val wifiBoundWaitingSongs = wifiBoundSongsForNetworkPolicy(
            context = context,
            songs = waitingTaskSongs
        )
        val waitingSongs = (wifiBoundResumableSongs + wifiBoundWaitingSongs)
            .distinctBy(SongItem::stableKey)
        if (waitingSongs.isEmpty()) {
            return emptySet()
        }

        val waitingSongKeys = waitingSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
        val waitingStateCommitted = mutateWifiBoundNetworkPolicyIfStillRequired(
            context = context,
            snapshotEpoch = networkPolicyEpoch
        ) {
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(waitingSongKeys)
            taskStore.prepareDownloadTasks(
                songs = waitingSongs,
                status = DownloadStatus.WAITING_NETWORK,
                replaceExistingActiveTasks = true
            )
            mobileDataDownloadOverrideAllowed = false
        }
        if (!waitingStateCommitted) {
            NPLogger.d(TAG, "启动恢复网络策略已过期，保留 WIFI 恢复路径: reason=$reason")
            return emptySet()
        }
        scheduleWifiBoundDownloadWakeups(context, waitingSongKeys)
        NPLogger.w(
            TAG,
            "启动下载恢复网络尚未确认，已保守等待: reason=$reason, " +
                "networkType=${networkType ?: "UNKNOWN"}, count=${waitingSongs.size}"
        )
        networkType?.let { confirmedNetworkType ->
            publishMobileDataDownloadInterruptionRequestIfNeeded(
                context = context,
                networkType = confirmedNetworkType,
                fallbackTaskCount = waitingSongs.size,
                reason = reason,
                forceAuthoritativeRecount = true
            )
        }
        recoverWifiBoundDownloadsIfNetworkPolicyExpired(
            context = context,
            snapshotEpoch = networkPolicyEpoch,
            reason = "startup_network_policy_stale_$reason"
        )
        return waitingSongKeys
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

    private suspend fun wifiBoundTasksForNetworkPolicy(
        context: Context,
        tasks: List<DownloadTask>
    ): List<DownloadTask> {
        if (tasks.isEmpty()) {
            return emptyList()
        }
        val requiresWifiBySongKey = durableWifiRequirementBySongKey(
            context = context,
            songKeys = tasks.map { task -> task.song.stableKey() }
        )
        return tasks.filter { task ->
            shouldPauseDownloadForWifiDisconnect(
                requiresWifiNetwork = requiresWifiBySongKey[task.song.stableKey()] ?: true
            )
        }
    }

    private suspend fun wifiBoundSongsForNetworkPolicy(
        context: Context,
        songs: List<SongItem>
    ): List<SongItem> {
        val distinctSongs = songs.distinctBy(SongItem::stableKey)
        if (distinctSongs.isEmpty()) {
            return emptyList()
        }
        val requiresWifiBySongKey = durableWifiRequirementBySongKey(
            context = context,
            songKeys = distinctSongs.map(SongItem::stableKey)
        )
        return distinctSongs.filter { song ->
            shouldPauseDownloadForWifiDisconnect(
                requiresWifiNetwork = requiresWifiBySongKey[song.stableKey()] ?: true
            )
        }
    }

    private suspend fun persistedWifiBoundSongKeys(context: Context): Set<String> {
        val fallbackRequirements = linkedMapOf<String, Boolean>()
        val fallbackSongKeysWithOperationId = mutableSetOf<String>()
        ManagedDownloadStorage.listPendingQueuedDownloads(context).forEach { entry ->
            fallbackRequirements[entry.stableKey] = entry.requiresWifiNetwork
            if (!entry.operationId.isNullOrBlank()) {
                fallbackSongKeysWithOperationId += entry.stableKey
            }
        }
        ManagedDownloadStorage.listPendingResumableDownloads(context).forEach { entry ->
            fallbackRequirements.putIfAbsent(entry.song.stableKey(), true)
            if (!entry.operationId.isNullOrBlank()) {
                fallbackSongKeysWithOperationId += entry.song.stableKey()
            }
        }
        val durableRequirements = try {
            DownloadExecutionRoomStore.listByStatesAnyLibrary(
                context = context.applicationContext,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                    DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                    WAITING_STORAGE_MUTATION_OPERATION_STATE,
                excludeUserStoppedOperations = true
            )
                .asSequence()
                .sortedBy(DownloadExecutionRoomStore.StateEntry::createdAtMs)
                .associate { entry ->
                    entry.request.song.stableKey() to entry.request.requiresWifiNetwork
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "读取持久下载网络策略失败，保留兼容队列计数: error=${error.message}",
                error
            )
            null
        }
        durableRequirements?.forEach { (songKey, requiresWifi) ->
            fallbackRequirements.putIfAbsent(songKey, requiresWifi)
            fallbackSongKeysWithOperationId += songKey
        }
        if (fallbackRequirements.isEmpty()) {
            return emptySet()
        }
        return fallbackRequirements
            .filter { (songKey, fallbackRequiresWifi) ->
                val requiresWifi = resolvePersistedWifiBoundRequirement(
                    fallbackRequiresWifi = fallbackRequiresWifi,
                    hasKnownOperation = songKey in fallbackSongKeysWithOperationId,
                    durableRequiresWifi = durableRequirements?.get(songKey)
                ) ?: return@filter false
                shouldPauseDownloadForWifiDisconnect(
                    requiresWifiNetwork = requiresWifi
                )
            }
            .keys
            .toSet()
    }

    private suspend fun durableWifiRequirementBySongKey(
        context: Context,
        songKeys: Collection<String>
    ): Map<String, Boolean> {
        val requestedSongKeys = songKeys.map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (requestedSongKeys.isEmpty()) {
            return emptyMap()
        }
        return try {
            DownloadExecutionRoomStore.listByStatesAnyLibrary(
                context = context.applicationContext,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                    DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                    listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
            )
                .asSequence()
                .filter { entry -> entry.request.song.stableKey() in requestedSongKeys }
                .sortedBy(DownloadExecutionRoomStore.StateEntry::createdAtMs)
                .associate { entry ->
                    entry.request.song.stableKey() to entry.request.requiresWifiNetwork
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "读取下载网络策略失败，按仅 WIFI 保守处理: error=${error.message}",
                error
            )
            emptyMap()
        }
    }

    private suspend fun scheduleWifiBoundDownloadWakeups(
        context: Context,
        songKeys: Set<String>
    ) {
        if (songKeys.isEmpty()) {
            return
        }
        val scheduled = WifiBoundDownloadWakeWorker.scheduleAll(
            context = context.applicationContext
        )
        if (!scheduled) {
            NPLogger.w(
                TAG,
                "登记批量 WIFI 恢复唤醒失败: songs=${songKeys.size}"
            )
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
        val interruptionSnapshotEpoch = mobileDataDownloadInterruptionEpoch.get()
        val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()
        val activeTasks = wifiBoundTasksForNetworkPolicy(
            context = context,
            tasks = currentActiveNetworkPolicyTasks()
        )
        val waitingSongs = wifiBoundSongsForNetworkPolicy(
            context = context,
            songs = currentWaitingNetworkTaskSongs()
        )
        val persistedSongKeys = persistedWifiBoundSongKeys(context)
        if (!hasWifiBoundNetworkPolicyDownloads(
                activeTaskCount = activeTasks.size,
                persistedQueuedCount = persistedSongKeys.size + waitingSongs.size
            )
        ) {
            return false
        }
        if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
            NPLogger.d(TAG, "活动下载网络策略已过期，保留 WIFI 恢复路径: reason=$reason")
            return false
        }
        val taskCount = wifiBoundDownloadTaskCount(
            activeSongKeys = activeTasks.map { task -> task.song.stableKey() } +
                waitingSongs.map(SongItem::stableKey),
            persistedSongKeys = persistedSongKeys
        )
        NPLogger.w(
            TAG,
            "非 WIFI 网络下检测到仅 WIFI 下载，先暂停并等待用户选择: reason=$reason, networkType=$networkType, activeTasks=${activeTasks.size}, persisted=${persistedSongKeys.size}, waiting=${waitingSongs.size}"
        )
        publishMobileDataDownloadInterruptionRequestIfNeeded(
            context = context,
            networkType = networkType,
            fallbackTaskCount = taskCount.coerceAtLeast(1),
            reason = reason,
            authoritativeTaskCount = taskCount,
            interruptionSnapshotEpoch = interruptionSnapshotEpoch
        )
        val paused = pauseDownloadTasksForNetworkPolicy(
            context = context,
            activeTasks = activeTasks,
            networkPolicyEpoch = networkPolicyEpoch
        )
        if (!paused) {
            recoverWifiBoundDownloadsIfNetworkPolicyExpired(
                context = context,
                snapshotEpoch = networkPolicyEpoch,
                reason = "active_network_policy_stale_$reason"
            )
        }
        return paused
    }

    private suspend fun pauseActiveDownloadsForUnknownNetwork(
        context: Context,
        reason: String
    ): Boolean {
        val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()
        if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
            return false
        }
        val paused = pauseDownloadTasksForNetworkPolicy(
            context = context,
            activeTasks = currentActiveNetworkPolicyTasks(),
            networkPolicyEpoch = networkPolicyEpoch
        )
        if (paused) {
            NPLogger.w(
                TAG,
                "下载网络状态未知，已保守暂停仅 WIFI 下载: reason=$reason"
            )
        }
        return paused
    }

    private suspend fun deferQueuedDownloadStartForNetworkPolicyIfNeeded(
        context: Context,
        songs: List<SongItem>,
        attemptIdsBySongKey: Map<String, Long>,
        requestGeneration: Long,
        reason: String,
        deferForNetworkPolicy: Boolean
    ): Set<String> {
        val networkType = context.currentDownloadNetworkTypeOrNull()
        if (
            !shouldDeferQueuedDownloadStartForNetwork(
                networkType = networkType,
                mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed,
                deferForNetworkPolicy = deferForNetworkPolicy
            )
        ) {
            return emptySet()
        }
        val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()

        val eligibleSongs = songs
            .distinctBy { song -> song.stableKey() }
            .filter { song ->
                val songKey = song.stableKey()
                attemptIdsBySongKey.containsKey(songKey) &&
                    isDownloadRequestGenerationCurrent(songKey, requestGeneration)
            }
        val waitingSongs = wifiBoundSongsForNetworkPolicy(
            context = context,
            songs = eligibleSongs
        )
        if (waitingSongs.isEmpty()) {
            return emptySet()
        }

        val waitingKeys = waitingSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
        val waitingStateCommitted = mutateWifiBoundNetworkPolicyIfStillRequired(
            context = context,
            snapshotEpoch = networkPolicyEpoch
        ) {
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
        }
        if (!waitingStateCommitted) {
            NPLogger.d(TAG, "排队启动网络策略已过期，保留 WIFI 恢复路径: reason=$reason")
            return emptySet()
        }
        NPLogger.w(
            TAG,
            "下载启动网络尚未确认，已保守等待: reason=$reason, " +
                "networkType=${networkType ?: "UNKNOWN"}, count=${waitingSongs.size}"
        )
        networkType?.let { confirmedNetworkType ->
            publishMobileDataDownloadInterruptionRequestIfNeeded(
                context = context,
                networkType = confirmedNetworkType,
                fallbackTaskCount = waitingSongs.size,
                reason = reason,
                forceAuthoritativeRecount = true
            )
        }
        scheduleWifiBoundDownloadWakeups(context, waitingKeys)
        recoverWifiBoundDownloadsIfNetworkPolicyExpired(
            context = context,
            snapshotEpoch = networkPolicyEpoch,
            reason = "queued_network_policy_stale_$reason"
        )
        return waitingKeys
    }

    private suspend fun publishMobileDataDownloadInterruptionRequestIfNeeded(
        context: Context,
        networkType: TrafficNetworkType,
        fallbackTaskCount: Int,
        reason: String,
        authoritativeTaskCount: Int? = null,
        forceAuthoritativeRecount: Boolean = false,
        interruptionSnapshotEpoch: Long? = null
    ) {
        mobileDataDownloadInterruptionRequestMutex.withLock {
            if (onWifiBoundDownloadNetworkRestored(context, "dialog_$reason")) {
                return@withLock
            }
            val publicationEpoch = interruptionSnapshotEpoch
                ?: mobileDataDownloadInterruptionEpoch.get()
            if (!isMobileDataDownloadInterruptionSnapshotCurrent(
                    snapshotEpoch = publicationEpoch,
                    currentEpoch = mobileDataDownloadInterruptionEpoch.get()
                )
            ) {
                NPLogger.d(TAG, "移动网络下载提示统计已过期: reason=$reason")
                return@withLock
            }
            if (isDownloadClearFenceActive(context.applicationContext)) {
                return@withLock
            }
            val existingRequest = _mobileDataDownloadInterruptionRequest.value
            if (
                existingRequest == null &&
                    !AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow.first()
            ) {
                NPLogger.d(
                    TAG,
                    "移动网络下载提示已关闭，任务保持等待 WIFI: reason=$reason, " +
                        "networkType=$networkType, taskCount=${fallbackTaskCount.coerceAtLeast(1)}"
                )
                return@withLock
            }
            val observedTaskCount = authoritativeTaskCount ?: when {
                forceAuthoritativeRecount ||
                    existingRequest == null ||
                    fallbackTaskCount > existingRequest.taskCount -> {
                    observeWifiBoundMobileDataTaskCount(context)
                }

                else -> null
            }
            if (onWifiBoundDownloadNetworkRestored(context, "dialog_recheck_$reason")) {
                return@withLock
            }
            if (
                !isMobileDataDownloadInterruptionSnapshotCurrent(
                    snapshotEpoch = publicationEpoch,
                    currentEpoch = mobileDataDownloadInterruptionEpoch.get()
                ) ||
                    isDownloadClearFenceActive(context.applicationContext)
            ) {
                NPLogger.d(TAG, "移动网络下载提示发布已过期: reason=$reason")
                return@withLock
            }
            val normalizedTaskCount = resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = existingRequest?.taskCount,
                observedTaskCount = observedTaskCount,
                fallbackTaskCount = fallbackTaskCount
            )
            if (normalizedTaskCount == 0) {
                if (existingRequest != null) {
                    dismissMobileDataDownloadInterruptionRequest()
                }
                NPLogger.d(
                    TAG,
                    "移动网络下载提示权威重算为空，撤销提示: reason=$reason"
                )
                return@withLock
            }
            if (existingRequest != null) {
                if (existingRequest.taskCount != normalizedTaskCount) {
                    val updatedRequest = existingRequest.copy(
                        taskCount = normalizedTaskCount
                    )
                    _mobileDataDownloadInterruptionRequest.value = updatedRequest
                    if (
                        !isMobileDataDownloadInterruptionSnapshotCurrent(
                            snapshotEpoch = publicationEpoch,
                            currentEpoch = mobileDataDownloadInterruptionEpoch.get()
                        ) ||
                            isDownloadClearFenceActive(context.applicationContext)
                    ) {
                        if (_mobileDataDownloadInterruptionRequest.value?.id == updatedRequest.id) {
                            _mobileDataDownloadInterruptionRequest.value = null
                        }
                        return@withLock
                    }
                }
                NPLogger.d(
                    TAG,
                    "移动网络下载确认请求已存在，更新等待数量: reason=$reason, " +
                        "requestId=${existingRequest.id}, taskCount=$normalizedTaskCount"
                )
                return@withLock
            }
            val request = MobileDataDownloadInterruptionRequest(
                id = mobileDataInterruptionRequestIdGenerator.incrementAndGet(),
                networkType = networkType,
                taskCount = normalizedTaskCount
            )
            _mobileDataDownloadInterruptionRequest.value = request
            if (
                !isMobileDataDownloadInterruptionSnapshotCurrent(
                    snapshotEpoch = publicationEpoch,
                    currentEpoch = mobileDataDownloadInterruptionEpoch.get()
                ) ||
                isDownloadClearFenceActive(context.applicationContext)
            ) {
                if (_mobileDataDownloadInterruptionRequest.value?.id == request.id) {
                    _mobileDataDownloadInterruptionRequest.value = null
                }
                return@withLock
            }
            NPLogger.w(
                TAG,
                "已发出移动网络下载确认请求: reason=$reason, networkType=$networkType, " +
                    "taskCount=${request.taskCount}, requestId=${request.id}"
            )
        }
    }

    private suspend fun observeWifiBoundMobileDataTaskCount(context: Context): Int? {
        return runCatching {
            val activeTasks = wifiBoundTasksForNetworkPolicy(
                context = context,
                tasks = currentActiveNetworkPolicyTasks()
            )
            val waitingSongs = wifiBoundSongsForNetworkPolicy(
                context = context,
                songs = currentWaitingNetworkTaskSongs()
            )
            val persistedSongKeys = persistedWifiBoundSongKeys(context)
            wifiBoundDownloadTaskCount(
                activeSongKeys = activeTasks.map { task -> task.song.stableKey() } +
                    waitingSongs.map(SongItem::stableKey),
                persistedSongKeys = persistedSongKeys
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "重算移动网络下载提示数量失败，保留当前入口计数: " +
                    "reason=${error.message}",
                error
            )
        }.getOrNull()
    }

    private fun removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys: Set<String>) {
        taskStore.removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys)
    }

    fun recoverPendingDownloadsForNetworkRestored(context: Context, reason: String) {
        val appContext = context.applicationContext
        scope.launch {
            withPendingDownloadRecoverySlot("network:$reason") {
                if (isDownloadClearFenceActive(appContext)) {
                    return@withPendingDownloadRecoverySlot
                }
                if (appContext.currentDownloadNetworkTypeOrNull() != TrafficNetworkType.WIFI) {
                    return@withPendingDownloadRecoverySlot
                }
                if (!onWifiBoundDownloadNetworkRestored(appContext, "recovery_$reason")) {
                    return@withPendingDownloadRecoverySlot
                }
                promoteWaitingStorageMutationsForRecovery(appContext)
                // a core file can survive process death without an in-memory task or a
                // resumable transfer record, so repair finalization before testing the
                // ordinary queue-based recovery candidates
                recoverPendingAudioWritesFromRoot(appContext)
                recoverUnfinalizedPublishedAudioFromRoot(appContext)
                repairFinalizedDownloadedCoversFromRoot(appContext)
                if (!hasPendingRecoveryCandidates(appContext)) {
                    return@withPendingDownloadRecoverySlot
                }
                waitForActiveDownloadJobsToSettle()
                waitForQueuedTasksToAttachToBatch()
                if (hasBlockingActiveDownloadOperationsForRecovery()) {
                    return@withPendingDownloadRecoverySlot
                }
                recoverPendingResumableDownloads(appContext, reason = reason)
                delay(1_500L)
            }
        }
    }

    /**
     * WIFI 刚恢复时 provider 和 Connectivity callback 可能仍在收敛，
     * 用短窗口串行探测，避免一次过早回调把恢复机会丢掉。
     */
    internal fun scheduleWifiRecoveryProbe(context: Context, reason: String) {
        val appContext = context.applicationContext
        synchronized(wifiRecoveryProbeLock) {
            if (wifiRecoveryProbeJob?.isActive == true) {
                return
            }
            wifiRecoveryProbeJob = scope.launch {
                try {
                    repeat(WIFI_RECOVERY_PROBE_ATTEMPTS) { attempt ->
                        if (attempt > 0) {
                            delay(WIFI_RECOVERY_PROBE_DELAY_MS)
                        }
                        if (isDownloadClearFenceActive(appContext)) {
                            return@launch
                        }
                        val networkType = runCatching {
                            appContext.currentDownloadNetworkTypeOrNull()
                        }.getOrNull()
                        val hasPendingCandidates = runCatching {
                            hasPendingRecoveryCandidates(appContext)
                        }.getOrElse { error ->
                            NPLogger.d(
                                TAG,
                                "WIFI 恢复探测无法读取候选，保留下一次探测: " +
                                    "reason=$reason, error=${error.message}"
                            )
                            true
                        }
                        if (!shouldContinueWifiRecoveryProbe(
                                networkType = networkType,
                                hasPendingCandidates = hasPendingCandidates,
                                attempt = attempt,
                                maxAttempts = WIFI_RECOVERY_PROBE_ATTEMPTS
                            ) &&
                            !(networkType == TrafficNetworkType.WIFI && hasPendingCandidates)
                        ) {
                            return@launch
                        }
                        val settled = recoverPendingDownloadsFromWifiWake(appContext)
                        if (settled) {
                            return@launch
                        }
                    }
                } finally {
                    synchronized(wifiRecoveryProbeLock) {
                        wifiRecoveryProbeJob = null
                    }
                }
            }
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
        val durableCandidates = runCatching {
            DownloadExecutionRoomStore.hasAnyByStatesAnyLibrary(
                context = appContext,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                    DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
                    listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
            )
        }.getOrElse { error ->
            NPLogger.d(
                TAG,
                "检查持久下载候选失败，保守保留恢复机会: ${error.message}"
            )
            true
        }
        if (durableCandidates) {
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
            withPendingDownloadRecoverySlot("decision:$reason") {
                if (isDownloadClearFenceActive(appContext)) {
                    return@withPendingDownloadRecoverySlot
                }
                val networkType = appContext.currentDownloadNetworkTypeOrNull()
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
                    onWifiBoundDownloadNetworkRestored(appContext, "decision_$reason")
                    NPLogger.d(TAG, "跳过移动网络下载恢复复查: 当前是 WIFI, reason=$reason")
                    return@withPendingDownloadRecoverySlot
                }
                if (networkType == null) {
                    pauseActiveDownloadsForUnknownNetwork(appContext, reason)
                    deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(appContext, reason)
                    return@withPendingDownloadRecoverySlot
                }
                if (
                    pauseActiveDownloadsForNetworkPolicyIfNeeded(
                        context = appContext,
                        networkType = networkType,
                        reason = reason
                    )
                ) {
                    return@withPendingDownloadRecoverySlot
                }
                if (!hasPendingRecoveryCandidates(appContext)) {
                    NPLogger.d(TAG, "跳过移动网络下载恢复复查: 没有恢复候选, reason=$reason")
                    return@withPendingDownloadRecoverySlot
                }
                waitForActiveDownloadJobsToSettle()
                waitForQueuedTasksToAttachToBatch()
                if (hasBlockingActiveDownloadOperationsForRecovery()) {
                    NPLogger.d(TAG, "跳过移动网络下载恢复复查: 仍有活动下载, reason=$reason")
                    return@withPendingDownloadRecoverySlot
                }
                val deferredSongKeys = deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(
                    context = appContext,
                    reason = reason
                )
                recoverPendingResumableDownloads(
                    context = appContext,
                    reason = reason,
                    excludedSongKeys = deferredSongKeys
                )
            }
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
        synchronized(downloadedSongCatalogMutationLock) {
            val visibleSongs = downloadedSongDeleteVisibility.filterVisible(songs)
            val previousSongs = _downloadedSongs.value
            val changedSongKeys = changedDownloadedSongKeys(
                previousSongs = previousSongs,
                currentSongs = visibleSongs
            )
            // 先发布索引再通知列表, 避免界面首帧看到歌曲时索引仍为空
            downloadedSongCatalogIndex = buildDownloadedSongCatalogIndex(visibleSongs)
            downloadedSongCatalogReady = true
            _downloadedSongs.value = visibleSongs
            downloadedSongCatalogPersistenceRevision.incrementAndGet()
            LocalAssetInvalidationBus.bumpSongs(changedSongKeys)
            _downloadPresenceVersion.value += 1
            if (persistCatalog && visibleSongs == songs) {
                scheduleDownloadedSongsCatalogPersist(context)
            }
        }
    }

    /** 扫描结果只有在扫描期间没有新的目录发布时才能替换 catalog */
    private fun publishScannedDownloadedSongsIfCurrent(
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

    private fun scheduleDownloadedSongsCatalogPersist(context: Context) {
        val appContext = context.applicationContext
        val expectedRevision = downloadedSongCatalogPersistenceRevision.get()
        synchronized(catalogPersistenceLock) {
            catalogPersistJob?.cancel()
            catalogPersistJob = scope.launch {
                delay(DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS)
                catalogPersistenceMutex.withLock {
                    val songs = synchronized(downloadedSongCatalogMutationLock) {
                        _downloadedSongs.value.takeIf {
                            downloadedSongCatalogPersistenceRevision.get() == expectedRevision
                        }
                    } ?: return@withLock
                    persistDownloadedSongsCatalog(appContext, songs)
                }
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
        val effectiveProgress = taskStore.findTask(progress.songKey)
            ?.progress
            ?.takeIf { stored -> stored.attemptId == progress.attemptId }
            ?: return
        updateBatchDownloadPresentationProgress(effectiveProgress)
        val binding = activeProgressCheckpointBindings[progress.songKey] ?: return
        if (binding.attemptId != effectiveProgress.attemptId) {
            return
        }
        runCatching {
            DownloadExecutionRoomStore.checkpointProgress(
                context = AppContainer.applicationContext,
                operationId = binding.operationId,
                stableKey = progress.songKey,
                attemptId = binding.attemptId,
                bytesWritten = effectiveProgress.bytesRead,
                totalBytes = effectiveProgress.totalBytes
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
        // Room 检查点是按 operation 持久化的唯一稳定进度来源。这里不能为每首歌
        // 再枚举一次 staging 目录，否则重启 1000 首歌曲会退化为 O(N^2) 文件扫描；
        // 传输协程启动后仍会以 working file 长度校正并发布更大的进度
        val durableBytes = 0L
        val restoredProgress = resolveRecoveredDownloadProgress(
            workingFileBytes = durableBytes,
            checkpointTotalBytes = checkpoint.totalBytes,
            checkpointBytesWritten = checkpoint.bytesWritten
        ) ?: return
        if (!taskStore.restoreProgress(
            AudioDownloadManager.DownloadProgress(
                songKey = songKey,
                songId = song.id,
                fileName = song.name,
                bytesRead = restoredProgress.bytesRead,
                totalBytes = restoredProgress.totalBytes,
                speedBytesPerSec = 0L,
                attemptId = binding.attemptId
            )
        )) {
            return
        }
        taskStore.findTask(songKey)
            ?.progress
            ?.takeIf { progress -> progress.attemptId == binding.attemptId }
            ?.let(::updateBatchDownloadPresentationProgress)
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
                // 过期回调不能认领当前内存桥, 它可能已经属于更新后的下载
                storedAudio = storedAudioHint,
                sidecarReferences = sidecarReferences,
                operationId = operationId
            )
            storedAudioHint?.let { staleAudio ->
                AudioDownloadManager.releaseCompletedAudioReference(
                    songKey = songKey,
                    expectedAudio = staleAudio
                )
            }
            return
        }
        // core 音频已通过完整性校验，完成回调开始后的收尾阶段仍需保留引用供首播复用
        val completedAudio = AudioDownloadManager.peekCompletedAudioReference(song)
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
                completedAudio?.let { staleAudio ->
                    AudioDownloadManager.releaseCompletedAudioReference(
                        songKey = songKey,
                        expectedAudio = staleAudio
                    )
                }
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
            AudioDownloadManager.releaseCompletedAudioReference(
                songKey = songKey,
                expectedAudio = completedAudio ?: resolvedStoredAudio
            )
            return
        }

        val directoryCommitOperationId = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "untracked-finalization:$songKey"
        val directoryCommitLease =
            ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
                context = context,
                operationId = directoryCommitOperationId
            ) ?: throw DownloadStorageMutationDeferredException(directoryCommitOperationId)
        try {
        val existingMetadata = readDownloadedMetadata(
            context = context.applicationContext,
            audio = resolvedStoredAudio
        )
        val audioForFinalization = if (
            !resolvedStoredAudio.isPendingAudioWrite &&
                shouldDemotePublishedAudioForFinalization(existingMetadata)
        ) {
            ManagedDownloadStorage.demotePublishedAudioForFinalization(
                context = context.applicationContext,
                audio = resolvedStoredAudio,
                expectedMetadataFinalized = existingMetadata?.downloadFinalized
            ) ?: run {
                NPLogger.w(
                    TAG,
                    "未最终化音频无法安全回退为 pending，保留等待重试: " +
                        "song=${song.name}, file=${resolvedStoredAudio.name}"
                )
                return
            }
        } else {
            resolvedStoredAudio
        }
        val finalizationMetadata = if (audioForFinalization === resolvedStoredAudio) {
            existingMetadata
        } else {
            readDownloadedMetadata(
                context = context.applicationContext,
                audio = audioForFinalization
            )
        }
        // v15 metadata is read as input only; every completion now enters the
        // single core commit and enrichment pipeline
        completeCoreDownloadAndEnqueueEnrichment(
            context = context.applicationContext,
            song = song,
            storedAudio = audioForFinalization,
            existingMetadata = finalizationMetadata,
            artifactLeaseId = artifactLeaseId,
            expectedAttemptId = expectedAttemptId,
            refreshCatalog = refreshCatalog,
            operationId = operationId,
            allowMissingTask = allowMissingTask
        )
        } finally {
            directoryCommitLease.close()
        }
    }

    private suspend fun completeCoreDownloadAndEnqueueEnrichment(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        existingMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        artifactLeaseId: String?,
        expectedAttemptId: Long?,
        refreshCatalog: Boolean,
        operationId: String?,
        allowMissingTask: Boolean
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
                    operationId = operationId,
                    existingMetadataHint = existingMetadata
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
            val pendingMetadataDeleted = runCatching {
                ManagedDownloadStorage.deletePendingAudioMetadata(
                    context = context,
                    audioName = storedAudio.logicalName
                )
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "core metadata 已写入但 pending 清理失败，保留可恢复残留: " +
                        "audio=${storedAudio.logicalName}, error=${error.message}",
                    error
                )
                false
            }
            if (!pendingMetadataDeleted) {
                NPLogger.w(
                    TAG,
                    "core metadata 已写入但 pending 清理未确认，最终发布后将重试: " +
                        "audio=${storedAudio.logicalName}"
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
                return@withContext false
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

        // 进程恢复或延迟完成回调可能没有旧的内存桥。core 已完成完整性校验后，
        // 先登记当前引用，再发布 Room/catalog，保证这段窗口内也能立即播放
        AudioDownloadManager.rememberCompletedAudioReference(
            song = song,
            storedAudio = storedAudio
        )

        // artifact 行可能尚未有可展示标题，补一条同事务可恢复的目录预览
        // 进程在资产收尾前退出时，启动恢复仍能重建这首 core 音频
        runCatching {
            ManagedLibraryItemRoomStore.upsert(
                context = context,
                song = song,
                audio = storedAudio,
                state = ManagedDownloadArtifactState.CORE_COMMITTED.name
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "写入 core 音频恢复预览失败，保留 artifact 恢复入口: ${error.message}"
            )
        }

        // core 音频已经逐项校验并写入操作日志后，先发布可播放目录条目
        // 歌词、封面和内嵌标签在独立收尾队列中处理，不能阻塞刚完成的播放请求
        runCatching {
            publishOptimisticDownloadedSongs(
                context = context,
                songs = listOf(buildOptimisticDownloadedSong(song, storedAudio))
            )
        }.onFailure { error ->
            // 目录发布失败不影响已提交音频，启动时仍会从 core metadata 恢复
            NPLogger.w(
                TAG,
                "core 音频已提交但快速目录发布失败，保留收尾恢复入口: " +
                    "song=${song.name}, error=${error.message}"
            )
            scheduleCatalogReconcile(context, forceRefresh = false)
        }

        val enrichmentOperationId = operationId
            ?: existingMetadata?.operationId
            ?: UUID.randomUUID().toString()
        try {
            assetEnrichmentCoordinator.enqueue(
                operationId = enrichmentOperationId,
                block = {
                    enrichCoreCommittedDownload(
                        context = context,
                        song = song,
                        storedAudio = storedAudio,
                        existingMetadataHint = existingMetadata,
                        operationId = enrichmentOperationId,
                        artifactLeaseId = artifactLeaseId,
                        expectedAttemptId = expectedAttemptId,
                        refreshCatalog = refreshCatalog,
                        allowMissingTask = allowMissingTask
                    )
                },
                onTimeout = { error ->
                    val timeoutCommitLease =
                        ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
                            context = context,
                            operationId = enrichmentOperationId
                        ) ?: throw DownloadStorageMutationDeferredException(
                            enrichmentOperationId
                        )
                    try {
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
                                existingMetadataHint = existingMetadata,
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
                        operationId?.let { id ->
                            DownloadExecutionRoomStore.updateState(
                                context = context,
                                operationId = id,
                                state = "DEGRADED_COMPLETE",
                                errorCode = "ASSET_ENRICHMENT_TIMEOUT"
                            )
                        }
                        updateTaskStatus(
                            songKey,
                            DownloadStatus.FAILED,
                            expectedAttemptId = expectedAttemptId
                        )
                        scheduleCatalogReconcile(context, forceRefresh = true)
                    }
                    } finally {
                        timeoutCommitLease.close()
                    }
                },
                onCompletion = {
                    artifactLeaseId?.let { leaseId ->
                        managedDownloadArtifactLeases.remove(songKey, leaseId)
                    }
                }
            )
        } catch (error: Throwable) {
            artifactLeaseId?.let { leaseId ->
                managedDownloadArtifactLeases.remove(songKey, leaseId)
            }
            throw error
        }
    }

    private suspend fun enrichCoreCommittedDownload(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        existingMetadataHint: ManagedDownloadStorage.DownloadedAudioMetadata?,
        operationId: String,
        artifactLeaseId: String?,
        expectedAttemptId: Long?,
        refreshCatalog: Boolean,
        allowMissingTask: Boolean
    ) {
        val directoryCommitLease =
            ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
                context = context,
                operationId = operationId
            ) ?: throw DownloadStorageMutationDeferredException(operationId)
        var sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences()
        try {
            managedDownloadArtifactCoordinator.markAssetsEnriching(
                context = context,
                song = song,
                expectedLeaseId = artifactLeaseId
            )
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = "ASSETS_ENRICHING"
            )
            sidecarReferences = AudioDownloadManager.downloadSidecarsForCompletedAudio(
                context = context,
                song = song,
                storedAudio = storedAudio
            )
            val coverReference = sidecarReferences.coverReference
            val coverAccessible = coverReference?.let { reference ->
                ManagedDownloadReferenceLookup.inspect(context, reference) is
                    ManagedDownloadReferenceLookup.Result.Present
            } == true
            check(
                shouldFinalizeDownloadedSidecars(
                    hasNetworkCoverCandidate = sidecarReferences.expectedCover,
                    coverReference = coverReference,
                    coverAccessible = coverAccessible
                )
            ) { "COVER_SIDECAR_MISSING" }
            val metadataEmbeddingState = if (isDownloadMetadataPostProcessingEnabled(context)) {
                when (
                    runDownloadedAudioMetadataPostProcessing(
                        context = context,
                        audio = storedAudio,
                        song = song,
                        sidecarReferences = sidecarReferences
                    )
                ) {
                    MetadataPostProcessingResult.EMBEDDED_VERIFIED ->
                        DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED

                    MetadataPostProcessingResult.UNSUPPORTED_CONTAINER -> {
                        preserveUnsupportedMetadataEmbedding(
                            context = context,
                            song = song,
                            storedAudio = storedAudio,
                            sidecarReferences = sidecarReferences,
                            operationId = operationId,
                            artifactLeaseId = artifactLeaseId,
                            expectedAttemptId = expectedAttemptId
                        )
                        return
                    }

                    MetadataPostProcessingResult.RETRYABLE_FAILURE ->
                        error("embedded metadata post-processing failed")
                }
            } else {
                DownloadedAudioEmbeddingState.USER_DISABLED
            }
            check(
                persistDownloadedMetadata(
                    context = context,
                    audio = storedAudio,
                    song = song,
                    existingMetadataHint = existingMetadataHint,
                    sidecarReferences = sidecarReferences,
                    downloadFinalized = true,
                    metadataEmbeddingState = metadataEmbeddingState,
                    resolveExistingSidecars = false
                )
            ) { "final metadata persist failed" }
            check(
                publishFinalizedDownload(
                    context = context,
                    song = song,
                    storedAudio = storedAudio,
                    sidecarReferences = sidecarReferences,
                    expectedAttemptId = expectedAttemptId,
                    operationId = operationId,
                    expectedArtifactLeaseId = artifactLeaseId,
                    refreshCatalog = refreshCatalog,
                    allowMissingTask = allowMissingTask
                )
            ) { "pending audio promotion failed" }
            NPLogger.d(
                TAG,
                "下载资产补齐完成: song=${song.name}, operationId=$operationId"
            )
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
            runCatching {
                DownloadExecutionRoomStore.updateState(
                    context = context,
                    operationId = operationId,
                    state = "DEGRADED_COMPLETE",
                    errorCode = "ASSET_ENRICHMENT_FAILED"
                )
            }
            updateTaskStatus(
                song.stableKey(),
                DownloadStatus.FAILED,
                expectedAttemptId = expectedAttemptId
            )
        } finally {
            directoryCommitLease.close()
            scheduleCatalogReconcile(context, forceRefresh = true)
        }
    }

    private suspend fun preserveUnsupportedMetadataEmbedding(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences,
        operationId: String,
        artifactLeaseId: String?,
        expectedAttemptId: Long?
    ) {
        NPLogger.w(
            TAG,
            "下载容器不支持内嵌元信息，保留待处理文件: " +
                "song=${song.name}, file=${storedAudio.name}"
        )
        val metadataPersisted = runCatching {
            persistDownloadedMetadata(
                context = context,
                audio = storedAudio,
                song = song,
                sidecarReferences = sidecarReferences,
                downloadFinalized = false,
                metadataEmbeddingState = DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
                resolveExistingSidecars = false
            )
        }.getOrElse { error ->
            NPLogger.w(
                TAG,
                "记录不支持内嵌元信息状态失败，保留可重试文件: " +
                    "file=${storedAudio.name}, error=${error.message}"
            )
            false
        }
        if (!metadataPersisted) {
            markDownloadArtifactRepairRequired(
                context = context,
                song = song,
                leaseId = artifactLeaseId,
                errorCode = "METADATA_EMBEDDING_STATE_WRITE_FAILED"
            )
            updateTaskStatus(
                song.stableKey(),
                DownloadStatus.FAILED,
                expectedAttemptId = expectedAttemptId
            )
            return
        }
        runCatching {
            managedDownloadArtifactCoordinator.markDegradedComplete(
                context = context,
                song = song,
                expectedLeaseId = artifactLeaseId,
                errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "记录不支持内嵌元信息 artifact 状态失败: ${error.message}")
        }
        runCatching {
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = "DEGRADED_COMPLETE",
                errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "记录不支持内嵌元信息 operation 状态失败: ${error.message}")
        }
        updateTaskStatus(
            song.stableKey(),
            DownloadStatus.FAILED,
            expectedAttemptId = expectedAttemptId
        )
    }

    private suspend fun publishFinalizedDownload(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences?,
        expectedAttemptId: Long?,
        operationId: String?,
        expectedArtifactLeaseId: String?,
        refreshCatalog: Boolean,
        allowMissingTask: Boolean
    ): Boolean {
        val songKey = song.stableKey()
        val currentTask = taskStore.findTask(songKey)
        if (
            (currentTask == null && !allowMissingTask) ||
                (currentTask != null && !shouldApplyTaskMutation(currentTask, expectedAttemptId))
        ) {
            NPLogger.d(
                TAG,
                "跳过过期下载最终发布: song=${song.name}, " +
                    "expectedAttemptId=$expectedAttemptId"
            )
            return false
        }
        val terminalTemporaryWriteTargets =
            finalizedTemporaryWriteTargetNames(
                audioName = storedAudio.logicalName,
                pendingAudioName = storedAudio.name
            )
        val promotion = ManagedDownloadStorage.promoteFinalizedPendingAudio(
            context = context,
            audio = storedAudio
        ) ?: return false
        val finalizedAudio = promotion.audio
        // promotion 会让旧 pending 引用立即失效。先把内存桥接切到正式引用，
        // 再发布 artifact 和 catalog，避免首播线程在这个窗口内拿到失效 URI
        AudioDownloadManager.rememberCompletedAudioReference(
            song = song,
            storedAudio = finalizedAudio
        )
        managedDownloadArtifactCoordinator.markFinalized(
            context = context,
            song = song,
            storedAudio = finalizedAudio,
            expectedLeaseId = expectedArtifactLeaseId
        )
        publishCompletedDownloadOptimistically(
            context = context,
            song = song,
            storedAudio = finalizedAudio,
            sidecarReferences = sidecarReferences
        )
        // catalog 已经在内存中可见后再清理桥接引用。清理失败时保留短期引用，
        // 由过期回收机制处理，不能为了释放内存重新暴露 pending URI
        AudioDownloadManager.releaseCompletedAudioReference(
            songKey = songKey,
            expectedAudio = finalizedAudio,
            // provider 在 promotion 后可能短暂返回 Missing, 保留有界内存桥
            // 让刚完成的歌曲继续使用已校验引用
            retainForPlayback = true
        )
        updateTaskStatus(
            songKey,
            DownloadStatus.COMPLETED,
            expectedAttemptId = expectedAttemptId
        )
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        scheduleCompletedTaskRemoval(songKey, expectedAttemptId = expectedAttemptId)
        operationId?.let { id ->
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = id,
                state = "FINALIZED"
            )
        }
        cleanupFinalizedPendingArtifacts(
            context = context,
            pendingAudio = storedAudio,
            finalizedAudio = finalizedAudio,
            operationId = operationId,
            terminalTemporaryWriteTargets = terminalTemporaryWriteTargets,
            terminalTemporaryWriteCleanupRecorded =
                promotion.terminalTemporaryWriteCleanupRecorded
        )
        if (refreshCatalog) {
            scheduleCatalogReconcile(context, forceRefresh = false)
        }
        return true
    }

    private suspend fun cleanupFinalizedPendingArtifacts(
        context: Context,
        pendingAudio: ManagedDownloadStorage.StoredEntry,
        finalizedAudio: ManagedDownloadStorage.StoredEntry,
        operationId: String?,
        terminalTemporaryWriteTargets: Collection<String>,
        terminalTemporaryWriteCleanupRecorded: Boolean
    ) {
        try {
            val pendingMetadataDeleted = ManagedDownloadStorage.deletePendingAudioMetadata(
                context = context,
                audioName = finalizedAudio.logicalName
            )
            if (!pendingMetadataDeleted) {
                NPLogger.w(
                    TAG,
                    "最终发布后 pending metadata 清理未确认，保留下次恢复重试: " +
                        "audio=${finalizedAudio.logicalName}"
                )
            }
            val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
            val residualPendingAudio = if (
                normalizedOperationId != null &&
                    pendingAudio.isPendingAudioWrite &&
                    pendingAudio.reference != finalizedAudio.reference
            ) {
                ManagedDownloadStorage.queryStoredEntry(
                    context = context,
                    reference = pendingAudio.reference
                )?.takeIf { entry ->
                    entry.isPendingAudioWrite && entry.logicalName == finalizedAudio.logicalName
                }
            } else {
                null
            }
            residualPendingAudio?.let { residualAudio ->
                val residualMetadata = readDownloadedMetadata(context, residualAudio)
                if (
                    residualMetadata?.operationId == normalizedOperationId &&
                        isFinalizedDownloadedMetadata(residualMetadata)
                ) {
                    val deletedReferences = ManagedDownloadStorage.deleteReferences(
                        context = context,
                        references = listOf(residualAudio.reference)
                    )
                    if (residualAudio.reference !in deletedReferences) {
                        NPLogger.w(
                            TAG,
                            "最终发布后 pending 音频清理未确认，保留下次恢复重试: " +
                                "audio=${residualAudio.name}"
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "最终发布后 pending 半成品清理失败，保留下次恢复重试: " +
                    "audio=${finalizedAudio.logicalName}, error=${error.message}",
                error
            )
        }
        if (!terminalTemporaryWriteCleanupRecorded) {
            NPLogger.w(
                TAG,
                "最终发布后临时写入清理记录仍处于准备态，立即安排恢复重试: " +
                    "targets=${terminalTemporaryWriteTargets.size}"
            )
        }
        scheduleFinalizedTemporaryWriteCleanup(
            context = context,
            targetNames = terminalTemporaryWriteTargets
        )
    }

    private fun scheduleFinalizedTemporaryWriteCleanup(
        context: Context,
        targetNames: Collection<String>
    ) {
        if (targetNames.isEmpty()) return

        schedulePersistedTerminalTemporaryWriteCleanup(
            context = context,
            targetNames = targetNames
        )
    }

    private fun schedulePersistedTerminalTemporaryWriteCleanup(
        context: Context,
        targetNames: Collection<String> = emptyList()
    ) {
        val appContext = context.applicationContext
        scope.launch {
            terminalTemporaryWriteCleanupMutex.withLock {
                terminalTemporaryWriteCleanupBatch.addAll(targetNames)
                terminalTemporaryWriteCleanupWakeRequested = true
                if (terminalTemporaryWriteCleanupJob?.isActive == true) {
                    return@withLock
                }
                terminalTemporaryWriteCleanupJob = scope.launch cleanupLoop@{
                    delay(TERMINAL_TEMPORARY_WRITE_CLEANUP_COALESCE_MS)
                    var failedAttempt = 0
                    var retryPending = false
                    while (true) {
                        val (targets, cleanupRequested) =
                            terminalTemporaryWriteCleanupMutex.withLock {
                                val targets = terminalTemporaryWriteCleanupBatch.takeAll()
                                val requested = terminalTemporaryWriteCleanupWakeRequested
                                terminalTemporaryWriteCleanupWakeRequested = false
                                targets to requested
                            }
                        if (cleanupRequested || retryPending) {
                            val failedCount = try {
                                ManagedDownloadStorage
                                    .cleanupPersistedTerminalTemporaryWriteArtifacts(appContext)
                                    .failedCount
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                NPLogger.w(
                                    TAG,
                                    "最终发布后临时写入清理执行失败，保留下次恢复重试: " +
                                        "targets=${targets.size}, error=${error.message}",
                                    error
                                )
                                targets.size.coerceAtLeast(1)
                            }
                            if (failedCount > 0) {
                                failedAttempt += 1
                                val retryDelayMs =
                                    TerminalTemporaryWriteCleanupRetryPolicy
                                        .delayMsForFailedAttempt(failedAttempt)
                                if (retryDelayMs != null) {
                                    NPLogger.w(
                                        TAG,
                                        "最终发布后临时写入清理未完全确认，延后重试: " +
                                            "targets=${targets.size}, failed=$failedCount, " +
                                            "attempt=$failedAttempt/" +
                                            "${TerminalTemporaryWriteCleanupRetryPolicy.MAX_FAILED_ATTEMPTS}, " +
                                            "delayMs=$retryDelayMs"
                                    )
                                    retryPending = true
                                    delay(retryDelayMs)
                                    continue
                                }
                                NPLogger.w(
                                    TAG,
                                    "最终发布后临时写入清理重试次数已用尽，保留下次恢复: " +
                                        "targets=${targets.size}, failed=$failedCount, " +
                                        "attempts=$failedAttempt"
                                )
                                failedAttempt = 0
                            } else {
                                failedAttempt = 0
                            }
                            retryPending = false
                        }
                        val hasMoreCleanupRequests =
                            terminalTemporaryWriteCleanupMutex.withLock {
                                if (
                                    terminalTemporaryWriteCleanupBatch.isEmpty() &&
                                        !terminalTemporaryWriteCleanupWakeRequested
                                ) {
                                    terminalTemporaryWriteCleanupJob = null
                                    false
                                } else {
                                    true
                                }
                            }
                        if (!hasMoreCleanupRequests) return@cleanupLoop
                    }
                }
            }
        }
    }

    private fun isDurableCoreOperationState(state: String?): Boolean {
        return state == "CORE_COMMITTED" ||
            state == "ASSETS_ENRICHING" ||
            state == "FINALIZED" ||
            state == "DEGRADED_COMPLETE" ||
            state == METADATA_ACTION_REQUIRED_OPERATION_STATE ||
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
    ): MetadataPostProcessingResult {
        val songKey = song.stableKey()
        repeat(METADATA_POST_PROCESSING_MAX_ATTEMPTS) { attempt ->
            if (isSongCancelled(songKey)) {
                return MetadataPostProcessingResult.RETRYABLE_FAILURE
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
                TagPostProcessingAction.FINALIZE_TAGGED -> {
                    return MetadataPostProcessingResult.EMBEDDED_VERIFIED
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
                    return if (
                        writeResult.getOrNull() ==
                            DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER
                    ) {
                        MetadataPostProcessingResult.UNSUPPORTED_CONTAINER
                    } else {
                        MetadataPostProcessingResult.RETRYABLE_FAILURE
                    }
                }
            }
        }

        return MetadataPostProcessingResult.RETRYABLE_FAILURE
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
        operationId: String? = null,
        keepCancellationOperation: Boolean = false,
        cleanupRootPendingArtifacts: Boolean = true
    ): Boolean {
        val appContext = context.applicationContext
        val songKey = song.stableKey()
        ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(appContext, setOf(songKey))
        val rootCleanupSucceeded = if (cleanupRootPendingArtifacts) {
            cleanupCancelledPendingDownloadArtifacts(
                context = appContext,
                song = song,
                operationId = operationId
            )
        } else {
            true
        }
        if (!rootCleanupSucceeded) {
            NPLogger.w(
                TAG,
                "取消下载目录清理未确认，保留 Room 取消凭据等待恢复: " +
                    "song=${song.name}, operationId=${operationId ?: "unknown"}"
            )
        }
        if (shouldPurgeCancelledDownloadOperation(
                keepCancellationOperation = keepCancellationOperation,
                cleanupSucceeded = rootCleanupSucceeded
            )
        ) {
            DownloadExecutionRoomStore.purgeCancelled(appContext, setOf(songKey))
        }
        return rootCleanupSucceeded
    }

    private suspend fun cleanupCancelledPendingDownloadArtifacts(
        context: Context,
        song: SongItem,
        operationId: String?
    ): Boolean {
        val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank) ?: return true
        val operationState = DownloadExecutionRoomStore.state(context, normalizedOperationId)
        val stopRequestedByUser = DownloadExecutionRoomStore.isStopped(
            context,
            normalizedOperationId
        )
        if (!shouldCleanupCancelledPendingArtifacts(operationState, stopRequestedByUser)) {
            NPLogger.d(
                TAG,
                "跳过非取消终态的 pending 清理: song=${song.name}, " +
                    "operationId=$normalizedOperationId, state=$operationState, " +
                    "stopped=$stopRequestedByUser"
            )
            return true
        }
        val result = ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts(
            context = context,
            stableKey = song.stableKey(),
            operationId = normalizedOperationId
        )
        if (result.failedCount > 0) {
            NPLogger.w(
                TAG,
                "取消下载 pending 半成品未完全清理，保留下次恢复处理: " +
                    "song=${song.name}, operationId=$normalizedOperationId, " +
                    "failed=${result.failedCount}"
            )
            schedulePersistedTerminalTemporaryWriteCleanup(context)
        }
        return result.failedCount == 0
    }

    private suspend fun cleanupCancelledPendingDownloadArtifacts(
        context: Context,
        operationRequests: Collection<DownloadExecutionRequest>,
        cancellationGenerations: Map<String, Long?>,
        onProgress: ((completedItems: Int, totalItems: Int) -> Unit)? = null
    ): Set<String> {
        val operations = operationRequests
            .distinctBy(DownloadExecutionRequest::operationId)
            .mapNotNull { request ->
                val songKey = request.song.stableKey()
                if (!isCancellationCleanupStillCurrent(songKey, cancellationGenerations[songKey])) {
                    return@mapNotNull null
                }
                val state = DownloadExecutionRoomStore.state(context, request.operationId)
                val stopRequestedByUser = DownloadExecutionRoomStore.isStopped(
                    context,
                    request.operationId
                )
                if (!shouldCleanupCancelledPendingArtifacts(state, stopRequestedByUser)) {
                    return@mapNotNull null
                }
                ManagedDownloadStorage.CancelledPendingDownloadOperation(
                    stableKey = songKey,
                    operationId = request.operationId
                )
            }
        if (operations.isEmpty()) {
            return emptySet()
        }
        val result = ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts(
            context = context,
            operations = operations,
            onProgress = onProgress ?: { _, _ -> }
        )
        if (result.failedCount == 0) {
            return emptySet()
        }
        val affectedSongKeys = operations.mapTo(linkedSetOf()) { operation ->
            operation.stableKey
        }
        NPLogger.w(
            TAG,
            "批量取消 pending 半成品未完全清理，保持清空栅栏并重试: " +
                "operations=${operations.size}, failed=${result.failedCount}"
        )
        schedulePersistedTerminalTemporaryWriteCleanup(context)
        return affectedSongKeys
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
    ): ManagedLibraryRefreshOutcome {
        val waiter = CompletableDeferred<ManagedLibraryRefreshOutcome>()
        val appContext = context.applicationContext
        synchronized(this) {
            refreshWaiters += waiter
            requestLocalScanLocked(appContext, forceRefresh)
        }
        try {
            return waiter.await()
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
            var latestOutcome: ManagedLibraryRefreshOutcome =
                ManagedLibraryRefreshOutcome.Failed("download directory scan did not run")
            try {
                var nextForceRefresh = forceRefresh
                while (true) {
                    latestOutcome = reloadDownloadedSongs(
                        context,
                        forceRefresh = nextForceRefresh
                    )
                    nextForceRefresh = consumePendingRefreshRequest() ?: break
                }
                if (latestOutcome is ManagedLibraryRefreshOutcome.Published) {
                    LegacyJsonCleanupScheduler.scheduleQuarantineRecovery(context)
                }
                val retryState = ManagedLibraryProcessingCoordinator.state.value
                if (shouldCompleteProcessingAfterCatalogPublish(retryState)) {
                    retryState.operationId?.let { operationId ->
                        if (latestOutcome is ManagedLibraryRefreshOutcome.Published) {
                            ManagedLibraryProcessingCoordinator.complete(context, operationId)
                        } else {
                            ManagedLibraryProcessingCoordinator.waitingForRetry(
                                context,
                                operationId
                            )
                        }
                    }
                }
            } finally {
                val waiters = synchronized(this@GlobalDownloadManager) {
                    refreshJob = null
                    refreshWaiters.toList()
                }
                waiters.forEach { waiter -> waiter.complete(latestOutcome) }
            }
        }
    }

    internal fun shouldCompleteProcessingAfterCatalogPublish(
        state: ManagedLibraryProcessingState
    ): Boolean {
        return when (state) {
            is ManagedLibraryProcessingState.Running ->
                state.phase == ManagedLibraryProcessingPhase.REBUILDING_INDEX &&
                    (
                        state.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE ||
                            state.reason == ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE
                        )
            is ManagedLibraryProcessingState.WaitingForRetry ->
                state.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE
            ManagedLibraryProcessingState.Idle -> false
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

    private suspend fun reloadDownloadedSongs(
        context: Context,
        forceRefresh: Boolean = false
    ): ManagedLibraryRefreshOutcome {
        if (isDownloadClearFenceActive(context)) {
            NPLogger.d(TAG, "下载清空栅栏生效，保留现有 catalog 并延后目录扫描")
            return ManagedLibraryRefreshOutcome.Preserved(
                ManagedLibraryRefreshPreserveReason.DOWNLOAD_CLEAR_IN_PROGRESS
            )
        }
        if (isDownloadedSongDeletionActive()) {
            NPLogger.d(TAG, "下载删除进行中，延后目录扫描")
            awaitAllDownloadedSongDeletions()
            return reloadDownloadedSongs(context, forceRefresh = true)
        }
        _isRefreshing.value = true
        try {
            val scanStartedAtNs = System.nanoTime()
            val metadataRevisionAtScanStart = downloadedSongMetadataRevision.get()
            val catalogRevisionAtScanStart = downloadedSongCatalogPersistenceRevision.get()
            val fastIndexRebuildToken = runCatching {
                ManagedDownloadStorage.captureFastIndexRebuildToken(context)
            }.onFailure { error ->
                NPLogger.w(TAG, "无法建立 Managed SAF fast index 扫描令牌: ${error.message}")
            }.getOrNull()
            var snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = forceRefresh
            )
            snapshot = ManagedDownloadStorage.refreshDownloadSidecarSnapshot(
                context = context,
                snapshot = snapshot,
                forceRefresh = forceRefresh
            )
            if (isDownloadedSongDeletionActive()) {
                NPLogger.d(TAG, "下载删除进行中，丢弃已完成的目录扫描")
                awaitAllDownloadedSongDeletions()
                return reloadDownloadedSongs(context, forceRefresh = true)
            }
            val rebuildPlan = ManagedLibraryRebuilder.plan(snapshot)
            val songs = rebuildDownloadedSongs(
                context = context,
                snapshot = snapshot,
                rebuildPlan = rebuildPlan,
                failureLogPrefix = "解析下载文件失败",
                verifySnapshotReferences = false
            )
            val scanElapsedMs = (System.nanoTime() - scanStartedAtNs) / 1_000_000L
            val scanMessage =
                "Managed SAF 刷新扫描完成: elapsedMs=$scanElapsedMs, " +
                    "root=${snapshot.audioEntries.size}, metadata=${snapshot.metadataByAudioName.size}, " +
                    "songs=${songs.size}, sidecarComplete=${snapshot.sidecarEntriesComplete}, " +
                    "forceRefresh=$forceRefresh"
            if (scanElapsedMs > DOWNLOAD_LIBRARY_SCAN_TARGET_MS) {
                NPLogger.w(TAG, "$scanMessage, targetMs=$DOWNLOAD_LIBRARY_SCAN_TARGET_MS")
            } else {
                NPLogger.d(TAG, "$scanMessage, targetMs=$DOWNLOAD_LIBRARY_SCAN_TARGET_MS")
            }
            if (fastIndexRebuildToken != null) {
                scheduleFastIndexPersistence(
                    context = context,
                    snapshot = snapshot,
                    rebuildToken = fastIndexRebuildToken
                )
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
                return ManagedLibraryRefreshOutcome.Preserved(
                    ManagedLibraryRefreshPreserveReason.INCOMPLETE_ROOT_ENUMERATION
                )
            }
            var retryAfterDeletion = false
            var refreshOutcome: ManagedLibraryRefreshOutcome =
                ManagedLibraryRefreshOutcome.Preserved(
                    ManagedLibraryRefreshPreserveReason.SUPERSEDED_BY_METADATA_CHANGE
                )
            downloadedSongMetadataSyncMutex.withLock {
                if (isDownloadedSongDeletionActive()) {
                    NPLogger.d(TAG, "下载删除进行中，拒绝发布过期目录扫描")
                    retryAfterDeletion = true
                    return@withLock
                }
                if (metadataRevisionAtScanStart != downloadedSongMetadataRevision.get()) {
                    NPLogger.d(TAG, "跳过过期下载目录扫描结果: metadata changed during scan")
                    scheduleCatalogReconcile(context, forceRefresh = true)
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
                    val confidence = if (storageRootResolvable) {
                        ScanConfidence.COMPLETE
                    } else {
                        ScanConfidence.ROOT_UNAVAILABLE
                    }
                    // 根目录已经完成一次批量枚举，直接复用快照中的音频引用。
                    // 空结果路径不能再对每首歌执行 SAF inspect，否则 1000 首歌曲会
                    // 退化为 1000 次 Binder 往返，并且 Provider 瞬时抖动会放大为误判
                    val referenceCoverage = observeDownloadedSongReferencesFromSnapshot(
                        existingSongs = existingSongs,
                        snapshot = snapshot
                    )
                    val emptyDecision = if (scanMatchesCatalogRoot) {
                        managedLibraryReconciler.observeEmpty(
                            observation = EmptyScanObservation(
                                rootKey = scanRootKey,
                                confidence = confidence,
                                isUncached = forceRefresh,
                                knownReferenceCount = referenceCoverage.knownReferenceCount,
                                missingReferenceCount = referenceCoverage.missingReferenceCount,
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
                        refreshOutcome = ManagedLibraryRefreshOutcome.Preserved(
                            ManagedLibraryRefreshPreserveReason.SUSPICIOUS_EMPTY_RESULT
                        )
                        return@withLock
                    }
                    runCatching {
                        managedDownloadArtifactCoordinator.reconcileEmptyConfirmed(
                            context = context,
                            rootKey = scanRootKey
                        )
                    }.onFailure { error ->
                        NPLogger.w(TAG, "确认下载目录为空后清理 artifact 失败: ${error.message}")
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
                    if (
                        !publishScannedDownloadedSongsIfCurrent(
                            context = context,
                            songs = songs,
                            scanRootKey = scanRootKey,
                            expectedCatalogRevision = catalogRevisionAtScanStart,
                            expectedMetadataRevision = metadataRevisionAtScanStart
                        )
                    ) {
                        NPLogger.d(
                            TAG,
                            "跳过与 core/catalog 增量发布冲突的下载目录扫描: " +
                                "scanRoot=$scanRootKey"
                        )
                        scheduleCatalogReconcile(context, forceRefresh = true)
                        refreshOutcome = ManagedLibraryRefreshOutcome.Preserved(
                            ManagedLibraryRefreshPreserveReason.SUPERSEDED_BY_METADATA_CHANGE
                        )
                        return@withLock
                    }
                } else if (!downloadedSongCatalogReady) {
                    synchronized(downloadedSongCatalogMutationLock) {
                        if (
                            catalogRevisionAtScanStart !=
                                downloadedSongCatalogPersistenceRevision.get() ||
                                metadataRevisionAtScanStart != downloadedSongMetadataRevision.get()
                        ) {
                            NPLogger.d(
                                TAG,
                                "跳过与 core/catalog 增量发布冲突的下载目录就绪标记: " +
                                    "scanRoot=$scanRootKey"
                            )
                            scheduleCatalogReconcile(context, forceRefresh = true)
                            refreshOutcome = ManagedLibraryRefreshOutcome.Preserved(
                                ManagedLibraryRefreshPreserveReason.SUPERSEDED_BY_METADATA_CHANGE
                            )
                            return@withLock
                        }
                        downloadedSongCatalogIndex = buildDownloadedSongCatalogIndex(songs)
                        downloadedSongCatalogReady = true
                        downloadedSongCatalogRootKey = scanRootKey
                    }
                }
                refreshOutcome = ManagedLibraryRefreshOutcome.Published(
                    rootKey = scanRootKey,
                    songCount = songs.size
                )
            }
            if (retryAfterDeletion) {
                awaitAllDownloadedSongDeletions()
                return reloadDownloadedSongs(context, forceRefresh = true)
            }
            return refreshOutcome
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e(TAG, "扫描已下载文件失败: ${error.message}", error)
            return ManagedLibraryRefreshOutcome.Failed(
                error.message?.takeIf(String::isNotBlank)
                    ?: error::class.java.simpleName
            )
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
            mediaUri = resolveDurableMetadataPlaybackReference(audio, metadata),
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

    /** 当前快照的音频引用优先于旧 metadata，避免迁移后恢复 app-private URI */
    internal fun resolveDurableMetadataPlaybackReference(
        audio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): String? {
        return ManagedDownloadStorage.resolveStoredEntryPlaybackUri(audio)
            ?: metadata?.mediaUri
                ?.trim()
                ?.takeIf { reference ->
                    reference.startsWith("/") ||
                        reference.startsWith("content:", ignoreCase = true) ||
                        reference.startsWith("file:", ignoreCase = true)
                }
    }

    internal fun syncDownloadedSongMetadata(
        song: SongItem,
        clearRestorableOverrides: RestorableMetadataClearPolicy =
            RestorableMetadataClearPolicy()
    ) {
        scope.launch {
            runDownloadedSongMetadataSyncSafely(
                block = {
                    syncDownloadedSongMetadataNow(song, clearRestorableOverrides)
                },
                onFailure = { error ->
                    NPLogger.e(
                        TAG,
                        "同步下载歌曲元数据后台任务失败: song=${song.name}, " +
                            "error=${error.message}",
                        error
                    )
                }
            )
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
    ): DownloadedSongMetadataSyncOutcome = withContext(Dispatchers.IO) {
        downloadedSongMetadataSyncMutex.withLock {
            val context = AppContainer.applicationContext
            val currentSongs = _downloadedSongs.value
            val catalogSong = findDownloadedSongCatalogMatch(song, currentSongs)
            val metadataSong = catalogSong
                ?.let { existing -> projectDownloadedSongMetadata(existing, song) }
                ?.toMetadataPersistenceSong(song)
                ?: song
            val resolvedStoredAudio = resolveStoredAudio(context, song)
                ?: catalogSong?.let { downloaded ->
                    resolveStoredAudio(context, downloaded.filePath)
                        ?: resolveStoredAudio(context, downloaded.mediaUri)
                }
            if (resolvedStoredAudio == null) {
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

            val preflightSnapshot = runCatching {
                ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                    context = context,
                    forceRefresh = true
                )
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "同步下载歌曲元数据前无法确认目录快照，拒绝发布: " +
                        "file=${resolvedStoredAudio.name}, error=${error.message}"
                )
                scheduleCatalogReconcile(context, forceRefresh = true)
                return@withLock DownloadedSongMetadataSyncOutcome.FAILED
            }
            val finalizedStoredAudio = resolveFinalizedManagedAudioSnapshot(
                snapshot = preflightSnapshot,
                candidate = resolvedStoredAudio
            ) ?: run {
                NPLogger.w(
                    TAG,
                    "同步下载歌曲元数据命中未完成或未确认音频，拒绝写回完成状态: " +
                        "file=${resolvedStoredAudio.name}"
                )
                scheduleCatalogReconcile(context, forceRefresh = true)
                return@withLock DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
            }
            val storedAudio = finalizedStoredAudio.audio

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
            val refreshedStoredAudio = resolveFinalizedManagedAudioSnapshot(
                snapshot = snapshot,
                candidate = storedAudio
            ) ?: run {
                NPLogger.w(
                    TAG,
                    "同步下载歌曲元数据后目录快照未提供严格完成凭据，拒绝发布: " +
                        "file=${storedAudio.name}"
                )
                scheduleCatalogReconcile(context, forceRefresh = true)
                return@withLock DownloadedSongMetadataSyncOutcome.FAILED
            }
            val refreshedSong = runCatching {
                buildDownloadedSong(
                    context = context,
                    storedAudio = refreshedStoredAudio.audio,
                    snapshot = refreshedStoredAudio.snapshot,
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
            updateFastIndexAfterMetadataEdit(
                context = context,
                song = metadataSong,
                storedAudio = refreshedStoredAudio.audio,
                state = "FINALIZED",
                coverPath = refreshedSong.coverPath
            )
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

    private fun scheduleFastIndexPersistence(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        rebuildToken: ManagedLibraryFastIndexRebuildToken
    ) {
        val request = FastIndexPersistenceRequest(
            context = context.applicationContext,
            snapshot = snapshot,
            rebuildToken = rebuildToken
        )
        synchronized(fastIndexPersistenceLock) {
            pendingFastIndexPersistence = request
            if (fastIndexPersistenceJob?.isActive == true) {
                return
            }
            val job = scope.launch {
                while (true) {
                    val nextRequest = synchronized(fastIndexPersistenceLock) {
                        pendingFastIndexPersistence.also {
                            pendingFastIndexPersistence = null
                            if (it == null) {
                                fastIndexPersistenceJob = null
                            }
                        }
                    } ?: break
                    try {
                        if (isDownloadClearFenceActive(nextRequest.context)) {
                            continue
                        }
                        val persisted = ManagedDownloadStorage.persistFastIndex(
                            context = nextRequest.context,
                            snapshot = nextRequest.snapshot,
                            rebuildToken = nextRequest.rebuildToken
                        )
                        if (!persisted) {
                            NPLogger.d(
                                TAG,
                                "扫描期间 fast index 已有增量变更，跳过过期全量写入"
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        NPLogger.w(TAG, "写入 Managed SAF fast index 失败: ${error.message}")
                    }
                }
            }
            fastIndexPersistenceJob = job
            job.invokeOnCompletion {
                synchronized(fastIndexPersistenceLock) {
                    if (fastIndexPersistenceJob === job) {
                        fastIndexPersistenceJob = null
                    }
                }
            }
        }
    }

    private suspend fun rebuildDownloadedSongs(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        rebuildPlan: List<ManagedLibraryRebuildItem>,
        failureLogPrefix: String,
        verifySnapshotReferences: Boolean
    ): List<DownloadedSong> {
        val songs = ArrayList<DownloadedSong>(rebuildPlan.size)
        partitionForBoundedParallelism(
            items = rebuildPlan,
            maxParallelism = DOWNLOADED_SONG_BUILD_PARALLELISM
        ).forEach { batch ->
            val builtBatch = coroutineScope {
                batch.map { rebuildItem ->
                    async(downloadedSongBuildDispatcher) {
                        try {
                            buildDownloadedSong(
                                context = context,
                                storedAudio = rebuildItem.audio,
                                snapshot = snapshot,
                                existingDownloadTime = rebuildItem.logicalTimeMs,
                                loadLyricContents = false,
                                resolveLyricFallbacks = false,
                                allowSlowLocalInspection = false,
                                verifySnapshotReferences = verifySnapshotReferences
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            NPLogger.w(
                                TAG,
                                "$failureLogPrefix: ${rebuildItem.audio.name} - ${error.message}"
                            )
                            null
                        }
                    }
                }.awaitAll()
            }
            builtBatch.filterNotNullTo(songs)
        }
        return songs.sortedWith(downloadedSongNewestFirstComparator)
    }

    private suspend fun buildDownloadedSong(
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

    private suspend fun persistDownloadedMetadata(
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
        val session = beginDownloadedSongDeleteSession(appContext, targetSongs)
        scope.launch {
            var deleteLease: AutoCloseable? = null
            try {
                deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(
                    appContext
                )
                if (deleteLease == null) {
                    restoreDeferredDownloadedSongDeleteSession(appContext, session)
                    NPLogger.w(
                        TAG,
                        "目录迁移进行中，删除下载稍后重试: songs=${targetSongs.size}"
                    )
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    downloadedSongDeleteMutex.withLock {
                        deleteDownloadedSongsOnIo(appContext, session)
                    }
                }
            } finally {
                deleteLease?.close()
                endDownloadedSongDeletion(session.deletionKeys)
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

        val session = beginDownloadedSongDeleteSession(appContext, targetSongs)
        return try {
            withContext(Dispatchers.IO) {
                val deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(
                    appContext
                ) ?: return@withContext restoreDeferredDownloadedSongDeleteSession(
                    appContext,
                    session
                ).also {
                    NPLogger.w(
                        TAG,
                        "目录迁移进行中，删除下载稍后重试: songs=${targetSongs.size}"
                    )
                }
                try {
                    downloadedSongDeleteMutex.withLock {
                        deleteDownloadedSongsOnIo(appContext, session)
                    }
                } finally {
                    deleteLease.close()
                }
            }
        } finally {
            endDownloadedSongDeletion(session.deletionKeys)
        }
    }

    private fun restoreDeferredDownloadedSongDeleteSession(
        context: Context,
        session: DownloadedSongDeleteSession
    ): DownloadedSongDeleteResult {
        settleDownloadedSongDeleteSession(
            context = context,
            session = session,
            deletedSongs = emptyList(),
            restoredSongs = session.targetSongs
        )
        scheduleCatalogReconcile(context, forceRefresh = true)
        return DownloadedSongDeleteResult(
            deletedSongs = emptyList(),
            failedSongs = session.targetSongs
        )
    }

    private fun beginDownloadedSongDeleteSession(
        context: Context,
        targetSongs: List<DownloadedSong>
    ): DownloadedSongDeleteSession {
        val deletionKeys = downloadedSongDeletionKeys(targetSongs)
        beginDownloadedSongDeletion(deletionKeys)
        var visibilityToken: DownloadedSongDeleteVisibility.Token? = null
        return try {
            synchronized(downloadedSongCatalogMutationLock) {
                val previousSongs = _downloadedSongs.value
                val currentVisibilityToken = downloadedSongDeleteVisibility.begin(targetSongs)
                visibilityToken = currentVisibilityToken
                val visibleSongs = downloadedSongDeleteVisibility.filterVisible(previousSongs)
                if (visibleSongs != previousSongs) {
                    publishDownloadedSongs(context, visibleSongs, persistCatalog = false)
                }
                val clearJob = if (
                    isCompleteDownloadedSongSelection(
                        selectedSongs = targetSongs,
                        availableSongs = previousSongs
                    )
                ) {
                    NPLogger.d(
                        TAG,
                        "全选删除已被接受，立即建立下载清空栅栏: songs=${targetSongs.size}"
                    )
                    requestAllDownloadTaskCancellation(
                        purpose = DownloadClearPurpose.FULL_LIBRARY_DELETE
                    )
                } else {
                    null
                }
                DownloadedSongDeleteSession(
                    targetSongs = targetSongs,
                    previousSongs = previousSongs,
                    deletionKeys = deletionKeys,
                    visibilityToken = currentVisibilityToken,
                    clearJob = clearJob
                )
            }
        } catch (error: Throwable) {
            visibilityToken?.let(downloadedSongDeleteVisibility::finish)
            endDownloadedSongDeletion(deletionKeys)
            throw error
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

    private suspend fun awaitAllDownloadedSongDeletions() {
        while (isDownloadedSongDeletionActive()) {
            delay(DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS)
        }
    }

    private fun isDownloadedSongDeletionActive(): Boolean {
        return downloadedSongDeletionCounts.isNotEmpty() ||
            downloadedSongDeleteVisibility.hasActiveDeletions()
    }

    private fun settleDownloadedSongDeleteSession(
        context: Context,
        session: DownloadedSongDeleteSession,
        deletedSongs: List<DownloadedSong>,
        restoredSongs: List<DownloadedSong>
    ): List<DownloadedSong> {
        var shouldPersistCatalog = false
        val settledSongs = synchronized(downloadedSongCatalogMutationLock) {
            downloadedSongDeleteVisibility.recordDeleted(
                token = session.visibilityToken,
                songs = deletedSongs
            )
            val ownedDeletedSongs = deletedSongs.filter { song ->
                downloadedSongDeleteVisibility.owns(session.visibilityToken, song)
            }
            val ownedRestoredSongs = restoredSongs.filter { song ->
                downloadedSongDeleteVisibility.owns(session.visibilityToken, song) &&
                    !downloadedSongDeleteVisibility.wasPhysicallyDeleted(
                        session.visibilityToken,
                        song
                    )
            }
            val currentSongs = _downloadedSongs.value
            val restorationSourceSongs = (
                session.previousSongs +
                    session.visibilityToken.baselineSongsByIdentity.values
                ).distinctBy { song -> song.deletionIdentity().trim() }
            val mergedSongs = mergeDownloadedSongsAfterDelete(
                currentSongs = currentSongs,
                previousSongs = restorationSourceSongs,
                deletedSongs = ownedDeletedSongs,
                restoredSongs = ownedRestoredSongs
            )
            shouldPersistCatalog = ownedDeletedSongs.isNotEmpty() ||
                ownedRestoredSongs.isNotEmpty() ||
                deletedSongs.any { song ->
                    downloadedSongDeleteVisibility.wasPhysicallyDeleted(
                        session.visibilityToken,
                        song
                    )
                }
            downloadedSongDeleteVisibility.finish(session.visibilityToken)
            if (mergedSongs != currentSongs) {
                publishDownloadedSongs(context, mergedSongs, persistCatalog = false)
            }
            mergedSongs
        }
        if (shouldPersistCatalog) {
            scheduleDownloadedSongsCatalogPersist(context)
        }
        return settledSongs
    }

    private suspend fun deleteDownloadedSongsOnIo(
        appContext: Context,
        session: DownloadedSongDeleteSession
    ): DownloadedSongDeleteResult {
        val startedAtMs = System.currentTimeMillis()
        val targetSongs = session.targetSongs
        val previousSongs = session.previousSongs
        val deletesEntireCatalog = isCompleteDownloadedSongSelection(
            selectedSongs = targetSongs,
            availableSongs = previousSongs
        )
        try {
            if (deletesEntireCatalog) {
                NPLogger.d(
                    TAG,
                    "全选删除下载目录，先取消活动下载并保留清理标记直到收敛: songs=${targetSongs.size}"
                )
                session.clearJob?.join() ?: cancelAllDownloadTasksAndWait()
            } else {
                requestDownloadTaskCancellation(session.deletionKeys)?.join()
                val activeCancellationKeys =
                    awaitDownloadCancellationsSettled(session.deletionKeys)
                if (activeCancellationKeys.isNotEmpty()) {
                    settleDownloadedSongDeleteSession(
                        context = appContext,
                        session = session,
                        deletedSongs = emptyList(),
                        restoredSongs = targetSongs
                    )
                    scheduleCatalogReconcile(appContext, forceRefresh = true)
                    return DownloadedSongDeleteResult(
                        deletedSongs = emptyList(),
                        failedSongs = targetSongs
                    )
                }
            }
            val deletePlans = buildManagedDownloadDeletePlans(
                context = appContext,
                songs = targetSongs
            )
            val requestedReferences = mergeManagedRequestedReferences(
                deletePlans.map(ManagedDownloadSongDeletePlan::requestedReferences)
            )
            NPLogger.d(
                TAG,
                "批量删除下载开始: songs=${targetSongs.size}, references=${requestedReferences.size}, " +
                    "visible=${_downloadedSongs.value.size}"
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
            settleDownloadedSongDeleteSession(
                context = appContext,
                session = session,
                deletedSongs = deletionResult.deletedSongs,
                restoredSongs = deletionResult.failedSongs
            )

            val remainingReferences = requestedReferences - deletedReferences
            if (deletionResult.failedSongs.isNotEmpty()) {
                NPLogger.w(
                    TAG,
                    "批量删除下载音频不完整: failed=${deletionResult.failedSongs.size}"
                )
            }
            var staleArtifactCount = 0
            var artifactFailureCount = 0
            var fastIndexFailureCount = 0
            deletionResult.deletedSongs.forEach { song ->
                runCatching {
                    val artifactDeleted = managedDownloadArtifactCoordinator.deleteByStableKey(
                        context = appContext,
                        stableKey = song.stableKey
                    )
                    if (!artifactDeleted) {
                        staleArtifactCount++
                    }
                }.onFailure {
                    artifactFailureCount++
                }
                val stableKey = song.remoteSourceStableKeyOrNull()
                    ?: song.stableKey?.trim()?.takeIf(String::isNotBlank)
                if (stableKey != null) {
                    val indexResult = runCatching {
                        ManagedDownloadStorage.removeFastIndexEntry(
                            context = appContext,
                            stableKey = stableKey
                        )
                    }.getOrElse { error ->
                        ManagedLibraryFastIndexMutationResult.Failed(
                            shard = "",
                            error = error
                        )
                    }
                    if (indexResult is ManagedLibraryFastIndexMutationResult.Failed) {
                        fastIndexFailureCount++
                    }
                }
            }
            if (
                staleArtifactCount > 0 ||
                    artifactFailureCount > 0 ||
                    fastIndexFailureCount > 0
            ) {
                NPLogger.w(
                    TAG,
                    "批量删除 artifact 对账待复查: stale=$staleArtifactCount, " +
                        "failed=$artifactFailureCount, fastIndexFailed=$fastIndexFailureCount"
                )
            }
            scheduleCatalogReconcile(
                appContext,
                forceRefresh = deletionResult.failedSongs.isNotEmpty() ||
                    remainingReferences.isNotEmpty() ||
                    fastIndexFailureCount > 0
            )
            NPLogger.d(
                TAG,
                "批量删除下载结束: songs=${targetSongs.size}, requested=${requestedReferences.size}, deleted=${deletedReferences.size}, failed=${deletionResult.failedSongs.size}, costMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return deletionResult
        } catch (error: CancellationException) {
            settleDownloadedSongDeleteSession(
                context = appContext,
                session = session,
                deletedSongs = emptyList(),
                restoredSongs = targetSongs
            )
            scheduleCatalogReconcile(appContext, forceRefresh = true)
            throw error
        } catch (error: Exception) {
            settleDownloadedSongDeleteSession(
                context = appContext,
                session = session,
                deletedSongs = emptyList(),
                restoredSongs = targetSongs
            )
            scheduleCatalogReconcile(appContext, forceRefresh = true)
            NPLogger.e(TAG, "删除下载文件失败: ${error.message}", error)
            return DownloadedSongDeleteResult(
                deletedSongs = emptyList(),
                failedSongs = targetSongs
            )
        }
    }

    /**
     * 旧目录路径可能已经失效，不能让它遮蔽迁移后仍可用的 mediaUri
     * 每个候选都要独立确认，且不确定结果不能触发破坏性清理
     */
    private fun probeDownloadedSongReferences(
        context: Context,
        song: DownloadedSong,
        catalogEntryAvailable: Boolean = true
    ): DownloadedSongReferenceProbe {
        var sawMissing = false
        var sawUncertain = false
        for (reference in downloadedSongPlaybackReferenceCandidates(song)) {
            if (reference.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)) {
                sawUncertain = true
                continue
            }
            if (!isOptimisticPlaybackCatalogEntryAllowed(
                    context = context,
                    reference = reference,
                    catalogEntryAvailable = catalogEntryAvailable
                )
            ) {
                sawUncertain = true
                continue
            }
            when (val evidence = ManagedDownloadReferenceLookup.inspect(context, reference)) {
                ManagedDownloadReferenceLookup.Result.Present -> {
                    return DownloadedSongReferenceProbe(
                        reference = ManagedDownloadStorage.toPlayableUri(reference) ?: reference,
                        sawMissing = sawMissing,
                        sawUncertain = sawUncertain
                    )
                }
                ManagedDownloadReferenceLookup.Result.Missing -> sawMissing = true
                is ManagedDownloadReferenceLookup.Result.PermissionLost,
                is ManagedDownloadReferenceLookup.Result.ProviderFailure,
                ManagedDownloadReferenceLookup.Result.OutOfScope -> sawUncertain = true
            }
        }
        return DownloadedSongReferenceProbe(
            sawMissing = sawMissing,
            sawUncertain = sawUncertain
        )
    }

    private fun scheduleDownloadedSongReferenceReconcile(
        context: Context,
        probe: DownloadedSongReferenceProbe
    ) {
        if (!probe.sawMissing) return
        // 只有所有候选都给出 Missing 证据时才允许强制对账
        scheduleCatalogReconcile(
            context = context,
            forceRefresh = !probe.sawUncertain
        )
    }

    private suspend fun resolveDownloadedPlaybackWithRetry(
        context: Context,
        downloadedSong: DownloadedSong,
        sourceSong: SongItem,
        playbackReference: String
    ): DownloadedPlaybackResolution? {
        repeat(DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS) { attemptIndex ->
            val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                context = context,
                restorePersisted = false
            )
            val cachedAudio = snapshot?.let { currentSnapshot ->
                currentSnapshot.audioEntriesByLookupKey[playbackReference]
                    ?: currentSnapshot.pendingAudioEntries.firstOrNull { entry ->
                        entry.reference == playbackReference ||
                            entry.mediaUri == playbackReference ||
                            entry.localFilePath == playbackReference
                    }
                    ?: ManagedDownloadStorage.findDownloadedAudio(currentSnapshot, sourceSong)
                    ?: ManagedDownloadStorage.findPendingDownloadedAudio(currentSnapshot, sourceSong)
            }
            val verifiedAudio = snapshot?.let { currentSnapshot ->
                cachedAudio?.let { audio ->
                    resolvePlayableManagedAudioSnapshot(
                        context = context,
                        snapshot = currentSnapshot,
                        candidate = audio,
                        song = sourceSong
                    )
                }
            }
            val bridgedAudio = AudioDownloadManager.peekCompletedAudioReference(sourceSong)
            val storedAudio = verifiedAudio?.audio ?: cachedAudio ?: bridgedAudio
            val localReference = verifiedAudio?.reference
                ?: AudioDownloadManager.getLocalPlaybackUri(
                    context = context,
                    song = sourceSong
                )
            if (!localReference.isNullOrBlank()) {
                return DownloadedPlaybackResolution(
                    snapshot = verifiedAudio?.snapshot ?: snapshot,
                    audio = storedAudio,
                    reference = localReference
                )
            }

            val directReferenceProbe = probeDownloadedSongReferences(
                context = context,
                song = downloadedSong,
                catalogEntryAvailable = true
            )
            directReferenceProbe.reference?.let { directReference ->
                return DownloadedPlaybackResolution(
                    snapshot = snapshot,
                    audio = storedAudio,
                    reference = directReference
                )
            }
            if (attemptIndex == 0) {
                scheduleDownloadedSongReferenceReconcile(context, directReferenceProbe)
            }

            if (attemptIndex + 1 < DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS) {
                val retryNumber = attemptIndex + 1
                val delayMs = (DOWNLOADED_PLAYBACK_RETRY_BASE_DELAY_MS * retryNumber)
                    .coerceAtMost(DOWNLOADED_PLAYBACK_RETRY_MAX_DELAY_MS)
                NPLogger.d(
                    TAG,
                    "刚提交音频的本地引用尚未稳定，等待重试: " +
                        "song=${sourceSong.name}, retry=$retryNumber/" +
                        "$DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS, delayMs=$delayMs"
                )
                if (attemptIndex == 0) {
                    scheduleCatalogReconcile(context, forceRefresh = false)
                }
                delay(delayMs)
                yield()
            }
        }
        return null
    }

    fun playDownloadedSong(context: Context, song: DownloadedSong) {
        val appContext = context.applicationContext
        val requestGeneration = downloadedPlaybackRequestGeneration.incrementAndGet()
        val job = synchronized(downloadedPlaybackJobLock) {
            downloadedPlaybackJob?.cancel()
            scope.launch {
            try {
                if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                    return@launch
                }
                val playbackReference = resolveDownloadedSongPlaybackReference(song)
                if (playbackReference.isNullOrBlank()) {
                    NPLogger.w(TAG, "下载文件不存在: ${song.name}, reference=$playbackReference")
                    removeMissingDownloadedSongEntry(appContext, song)
                    return@launch
                }

                val sourceSong = song.toPlaybackSongItem()
                val playbackResolution = resolveDownloadedPlaybackWithRetry(
                    context = appContext,
                    downloadedSong = song,
                    sourceSong = sourceSong,
                    playbackReference = playbackReference
                )
                if (playbackResolution == null) {
                    NPLogger.w(
                        TAG,
                        "播放下载歌曲暂未取得可读本地引用，保留等待收尾: " +
                            "song=${song.name}, reference=$playbackReference"
                    )
                    scheduleCatalogReconcile(appContext, forceRefresh = false)
                    return@launch
                }
                if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                    return@launch
                }
                val storedAudio = playbackResolution.audio
                val playbackUri = playbackResolution.reference
                val playbackSnapshot = playbackResolution.snapshot
                val quickDownloadedSong = storedAudio?.let { audio ->
                    runCatching {
                        buildDownloadedSong(
                            context = appContext,
                            storedAudio = audio,
                            snapshot = playbackSnapshot,
                            existingDownloadTime = song.downloadTime,
                            loadLyricContents = false,
                            resolveLyricFallbacks = false,
                            allowSlowLocalInspection = false
                        )
                    }.getOrNull()
                }
                val quickSongBase = (quickDownloadedSong ?: song).toPlaybackSongItem(
                    playbackUri = playbackUri,
                    localFileName = storedAudio?.name
                        ?: ManagedDownloadStorage.normalizeManagedAudioFileName(playbackUri),
                    localFilePath = storedAudio?.localFilePath
                        ?: playbackUri.takeIf { it.startsWith("/") },
                    resolvedDurationMs = song.durationMs
                )
                val quickSong = hydrateDownloadedSidecarLyricsFast(
                    context = appContext,
                    song = quickSongBase
                )
                withContext<Unit>(Dispatchers.Main.immediate) {
                    if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                        return@withContext
                    }
                    PlayerManager.playPlaylist(listOf(quickSong), 0)
                }

                if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                    return@launch
                }
                scheduleDownloadedPlaybackReferenceValidation(appContext, song, playbackReference)
                val hydratedStoredAudio = storedAudio
                val refreshedSong = runCatching {
                    val hydrationSnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot =
                        ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                        context = appContext,
                        restorePersisted = false
                    )?.let { snapshot ->
                        ManagedDownloadStorage.refreshDownloadSidecarSnapshot(
                            context = appContext,
                            snapshot = snapshot
                        )
                    } ?: playbackSnapshot ?: return@runCatching null
                    val hydratedAudio = hydratedStoredAudio?.let { audio ->
                        resolvePlayableManagedAudioSnapshot(
                            context = appContext,
                            snapshot = hydrationSnapshot,
                            candidate = audio,
                            song = sourceSong
                        )
                    } ?: return@runCatching null
                    buildDownloadedSong(
                        context = appContext,
                        storedAudio = hydratedAudio.audio,
                        snapshot = hydratedAudio.snapshot,
                        existingDownloadTime = song.downloadTime,
                        loadLyricContents = false,
                        resolveLyricFallbacks = false,
                        allowSlowLocalInspection = false
                    )
                }.getOrNull() ?: quickDownloadedSong ?: song
                val hydratedDurationMs = refreshedSong.durationMs
                    .takeIf { it > 0L }
                    ?: quickSong.durationMs.takeIf { it > 0L }
                    ?: resolveAudioDuration(appContext, playbackUri)
                val hydratedSong = hydrateDownloadedSidecarLyricsFast(
                    context = appContext,
                    song = refreshedSong.toPlaybackSongItem(
                        playbackUri = playbackUri,
                        localFileName = hydratedStoredAudio?.name
                            ?: ManagedDownloadStorage.normalizeManagedAudioFileName(playbackUri),
                        localFilePath = hydratedStoredAudio?.localFilePath
                            ?: playbackUri.takeIf { it.startsWith("/") },
                        resolvedDurationMs = hydratedDurationMs
                    ),
                    refreshIfMissing = true
                )
                if (hydratedSong != quickSong) {
                    delay(resolveDownloadedPlaybackHydrationDelayMs(quickSong, hydratedSong))
                    if (!isLatestDownloadedPlaybackRequest(requestGeneration) ||
                        !shouldApplyDownloadedPlaybackHydration(
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
            } catch (error: CancellationException) {
                // 新的播放请求会取消旧请求，取消不应被记录成播放失败
                return@launch
            } catch (error: Exception) {
                NPLogger.e(TAG, "播放下载文件失败: ${error.message}", error)
            }
            }
            .also { launchedJob ->
                downloadedPlaybackJob = launchedJob
            }
        }
        job.invokeOnCompletion {
            synchronized(downloadedPlaybackJobLock) {
                if (downloadedPlaybackJob === job) {
                    downloadedPlaybackJob = null
                }
            }
        }
    }

    private fun isLatestDownloadedPlaybackRequest(requestGeneration: Long): Boolean {
        return shouldApplyDownloadedPlaybackRequest(
            requestGeneration = requestGeneration,
            latestGeneration = downloadedPlaybackRequestGeneration.get()
        )
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
        if (isSongCancelled(song.stableKey())) {
            return null
        }
        // core 提交后资产收尾可能尚未刷新 SAF 快照，此时优先使用已发布的
        // 内存目录条目，避免每次播放都触发一次完整 SAF 扫描
        val cachedSong = findDownloadedSongCached(song)
        if (cachedSong != null) {
            val probe = probeDownloadedSongReferences(
                context = context,
                song = cachedSong,
                catalogEntryAvailable = true
            )
            probe.reference?.let { return it }
            scheduleDownloadedSongReferenceReconcile(context, probe)
            if (probe.sawUncertain) {
                NPLogger.w(
                    TAG,
                    "下载目录缓存候选引用暂不可确认，保留并等待对账: " +
                        "song=${song.name}"
                )
            }
        }

        // 缓存引用失效时才尝试严格快照路径。该路径仍会校验 root 完整性和
        // core metadata，避免把旧目录或临时文件当作可播放音频
        val downloadedSong = findFastCachedDownloadedSong(context, song) ?: return null
        val probe = probeDownloadedSongReferences(
            context = context,
            song = downloadedSong,
            catalogEntryAvailable = true
        )
        probe.reference?.let { return it }
        scheduleDownloadedSongReferenceReconcile(context, probe)
        if (probe.sawUncertain) {
            NPLogger.w(
                TAG,
                "下载快索引候选引用暂不可确认，保留并等待对账: song=${song.name}"
            )
        }
        return null
    }

    private fun isOptimisticPlaybackCatalogEntryAllowed(
        context: Context,
        reference: String,
        catalogEntryAvailable: Boolean = false
    ): Boolean {
        if (!reference.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true)) {
            return true
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return shouldAllowPendingCatalogPlayback(
            referenceIsPending = true,
            catalogEntryAvailable = catalogEntryAvailable,
            snapshotAvailable = false
        )
        val audio = snapshot.audioEntriesByLookupKey[reference]
            ?: snapshot.pendingAudioEntries.firstOrNull { entry ->
                entry.reference == reference ||
                    entry.mediaUri == reference ||
                    entry.localFilePath == reference
            }
            ?: snapshot.audioEntries.firstOrNull { entry ->
                entry.reference == reference || entry.mediaUri == reference
            }
            ?: return shouldAllowPendingCatalogPlayback(
                referenceIsPending = true,
                catalogEntryAvailable = catalogEntryAvailable,
                snapshotAvailable = false
            )
        val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
        val durableCoreCommitAvailable = isReadableManagedAudioPlaybackAllowed(
            audioIsPending = true,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadata
        )
        return shouldAllowPendingCatalogPlayback(
            referenceIsPending = true,
            catalogEntryAvailable = catalogEntryAvailable,
            snapshotAvailable = true,
            durableCoreCommitAvailable = durableCoreCommitAvailable
        ) && isReadableManagedAudioPlaybackAllowed(
            audioIsPending = audio.isPendingAudioWrite,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadata,
            allowLegacyPublishedAudio = !audio.isPendingAudioWrite
        )
    }

    fun findFastCachedDownloadedSongPlaybackUri(context: Context, song: SongItem): String? {
        val downloadedSong = findFastCachedDownloadedSong(context, song) ?: return null
        val probe = probeDownloadedSongReferences(
            context = context,
            song = downloadedSong,
            catalogEntryAvailable = true
        )
        probe.reference?.let { return it }
        scheduleDownloadedSongReferenceReconcile(context, probe)
        if (probe.sawUncertain) {
            NPLogger.w(
                TAG,
                "下载快索引候选引用暂不可确认，跳过破坏性对账: song=${song.name}"
            )
        }
        return null
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
        // Room restore already rejects catalogs from another root, so publish it before SAF I/O
        downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
        return true
    }

    private suspend fun restoreFastIndexPreview(context: Context): Boolean {
        val snapshot = ManagedDownloadStorage.restoreFastIndexPreview(context) ?: return false
        val rebuildPlan = ManagedLibraryRebuilder.plan(
            snapshot = snapshot,
            allowIncompleteRootPreview = true
        )
        val songs = rebuildDownloadedSongs(
            context = context,
            snapshot = snapshot,
            rebuildPlan = rebuildPlan,
            failureLogPrefix = "解析 Managed fast index 预览失败",
            verifySnapshotReferences = false
        )
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

    private suspend fun awaitStartupProgressRestore() {
        if (!initialized || startupProgressRestoreReady.isCompleted) {
            return
        }
        val restored = withTimeoutOrNull(STARTUP_PROGRESS_RESTORE_WAIT_TIMEOUT_MS) {
            startupProgressRestoreReady.await()
            true
        } == true
        if (!restored) {
            NPLogger.w(
                TAG,
                "等待启动下载进度恢复超时，继续执行 durable operation"
            )
        }
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
        awaitStartupProgressRestore()
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
        preserveStaging: Boolean = false,
        replacingAttemptId: Long? = null
    ) {
        val appContext = context.applicationContext
        scope.launch {
            awaitDownloadedSongDeletion(setOf(song.stableKey()))
            val admissionTicket = awaitDownloadAdmissionTicket(appContext)
            var inFlightRequestToRecover: DownloadExecutionRequest? = null
            val admitted = admitDownloadMutation(appContext, admissionTicket) admission@{
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
                val stagedQueue = stageAndPromotePendingDownloadQueue(
                    context = appContext,
                    songs = listOf(song),
                    userInitiated = true
                ) ?: run {
                    NPLogger.d(
                        TAG,
                        "单曲下载意图在存储变更期间已被取消: song=${song.name}"
                    )
                    return@admission
                }
                val operationId = stagedQueue.operationIds.singleOrNull()
                if (operationId == null) {
                    NPLogger.w(TAG, "持久化下载 operation 失败: song=${song.name}")
                    return@admission
                }
                var operationState = DownloadExecutionRoomStore.state(appContext, operationId)
                val persistedRequest = DownloadExecutionRoomStore.read(appContext, operationId)
                if (persistedRequest?.song?.stableKey() != song.stableKey()) {
                    NPLogger.w(
                        TAG,
                        "持久化下载 operation 未确认，保留队列等待恢复: song=${song.name}"
                    )
                    return@admission
                }
                if (
                    operationState == WAITING_STORAGE_MUTATION_OPERATION_STATE ||
                        ManagedDownloadDirectoryMutationFence.isActive(appContext)
                ) {
                    taskStore.updateTaskStatus(
                        songKey = song.stableKey(),
                        status = DownloadStatus.WAITING_NETWORK,
                        expectedAttemptId = replacingAttemptId
                    )
                    NPLogger.d(
                        TAG,
                        "单曲下载恢复已登记等待目录迁移: " +
                            "song=${song.name}, operationId=$operationId"
                    )
                    return@admission
                }
                if (operationState == METADATA_ACTION_REQUIRED_OPERATION_STATE) {
                    if (isDownloadMetadataPostProcessingEnabled(appContext)) {
                        NPLogger.w(
                            TAG,
                            "下载容器需要关闭内嵌元信息后才能继续: " +
                                "song=${song.name}, operationId=$operationId"
                        )
                        return@admission
                    }
                    val reopened = DownloadExecutionRoomStore.updateState(
                        context = appContext,
                        operationId = operationId,
                        state = "ASSETS_ENRICHING"
                    )
                    if (!reopened) {
                        NPLogger.w(
                            TAG,
                            "无法在关闭内嵌元信息后恢复下载收尾: " +
                                "song=${song.name}, operationId=$operationId"
                        )
                        return@admission
                    }
                    operationState = DownloadExecutionRoomStore.state(appContext, operationId)
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
                taskStore.updateTaskStatus(
                    songKey = song.stableKey(),
                    status = DownloadStatus.QUEUED,
                    expectedAttemptId = replacingAttemptId
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
        if (
            ManagedDownloadDirectoryMutationFence.deferOperationIfActive(
                context = appContext,
                operationId = operationId
            )
        ) {
            return DownloadExecutionResult.AlreadyHandled
        }
        val songKey = song.stableKey()
        downloadAdmissionGate.awaitOpen()
        awaitDownloadedSongDeletion(setOf(songKey))
        val admissionTicket = downloadAdmissionGate.ticket()
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
            if (
                deferDownloadOperationExecutionForNetworkPolicyIfNeeded(
                    context = appContext,
                    request = persistedRequest,
                    preparedAttemptId = preparedAttemptId
                )
            ) {
                admissionResult = DownloadExecutionResult.NetworkPolicyWaiting
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
            admittedRequestGeneration = reuseOrBeginDownloadRequestGeneration(
                song = song,
                attemptId = effectiveAttemptId
            )
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
            artifactLeaseOwnerId = persistedRequest.artifactLeaseId,
            downloadAudioQuality = persistedRequest.downloadAudioQuality
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
            "FINALIZED" -> return DownloadExecutionResult.Accepted
            "CANCEL_REQUESTED",
            "CANCELLED" -> return DownloadExecutionResult.Cancelled
            "STOPPED" -> return DownloadExecutionResult.UserStopped
            METADATA_ACTION_REQUIRED_OPERATION_STATE -> {
                return DownloadExecutionResult.UserActionRequired
            }
            "RETRYABLE" -> return DownloadExecutionResult.Retry
            WAITING_STORAGE_MUTATION_OPERATION_STATE,
            "CORE_COMMITTED",
            "ASSETS_ENRICHING" -> return DownloadExecutionResult.AlreadyHandled
            "DEGRADED_COMPLETE" -> {
                if (isMetadataEmbeddingActionRequired(context, operationId, songKey)) {
                    return DownloadExecutionResult.UserActionRequired
                }
                return DownloadExecutionResult.Retry
            }
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

    private suspend fun deferDownloadOperationExecutionForNetworkPolicyIfNeeded(
        context: Context,
        request: DownloadExecutionRequest,
        preparedAttemptId: Long?
    ): Boolean {
        val networkType = context.currentDownloadNetworkTypeOrNull()
        if (!shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = request.requiresWifiNetwork,
                networkType = networkType,
                mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
            )
        ) {
            return false
        }
        val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()

        val songKey = request.song.stableKey()
        val durableAttemptIds = (preparedAttemptId ?: request.attemptId)
            ?.takeIf { attemptId -> attemptId > 0L }
            ?.let { attemptId -> mapOf(songKey to attemptId) }
            ?: emptyMap()
        if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
            NPLogger.d(
                TAG,
                "执行宿主网络策略已过期，保留 WIFI 恢复路径: operationId=${request.operationId}"
            )
            return false
        }
        var effectiveAttemptId: Long? = null
        val waitingStateCommitted = mutateWifiBoundNetworkPolicyIfStillRequired(
            context = context,
            snapshotEpoch = networkPolicyEpoch
        ) {
            effectiveAttemptId = taskStore.ensureDownloadTasks(
                songs = listOf(request.song),
                status = DownloadStatus.WAITING_NETWORK,
                durableAttemptIds = durableAttemptIds
            )[songKey]
            effectiveAttemptId?.let { attemptId ->
                taskStore.updateTaskStatus(
                    songKey = songKey,
                    status = DownloadStatus.WAITING_NETWORK,
                    expectedAttemptId = attemptId
                )
            }
            mobileDataDownloadOverrideAllowed = false
        }
        if (!waitingStateCommitted) {
            NPLogger.d(
                TAG,
                "执行宿主网络策略提交已过期，保留 WIFI 恢复路径: operationId=${request.operationId}"
            )
            return false
        }
        if (effectiveAttemptId != null) {
            runCatching {
                DownloadExecutionRoomStore.upsert(
                    context = context,
                    request = request.copy(attemptId = effectiveAttemptId),
                    state = "RETRYABLE"
                )
                DownloadExecutionRoomStore.updateState(
                    context = context,
                    operationId = request.operationId,
                    state = "RETRYABLE",
                    errorCode = "NETWORK_POLICY_WAITING"
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "写入 WIFI 下载等待状态失败: operationId=${request.operationId}, " +
                        "error=${error.message}"
                )
            }
        }
        if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
            recoverWifiBoundDownloadsIfNetworkPolicyExpired(
                context = context,
                snapshotEpoch = networkPolicyEpoch,
                reason = "execution_network_policy_stale"
            )
            return true
        }
        NPLogger.w(
            TAG,
            "下载执行宿主在非 WIFI 网络被阻止: operationId=${request.operationId}, " +
                "song=${request.song.name}, networkType=$networkType"
        )
        networkType?.let { confirmedNetworkType ->
            publishMobileDataDownloadInterruptionRequestIfNeeded(
                context = context,
                networkType = confirmedNetworkType,
                fallbackTaskCount = 1,
                reason = "execution_network_policy"
            )
        }
        recoverWifiBoundDownloadsIfNetworkPolicyExpired(
            context = context,
            snapshotEpoch = networkPolicyEpoch,
            reason = "execution_network_policy_publish_stale"
        )
        return true
    }

    private suspend fun isMetadataEmbeddingActionRequired(
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

    private suspend fun startDownloadConfirmed(
        context: Context,
        song: SongItem,
        cleanupBeforeStart: Boolean,
        requestGeneration: Long,
        deferForNetworkPolicy: Boolean,
        preparedAttemptId: Long? = null,
        operationId: String? = null,
        artifactLeaseOwnerId: String? = null,
        downloadAudioQuality: DownloadAudioQualitySelection? = null
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
                    val cancellationSettled = awaitSongCancellationSettled(
                        songKey = songKey,
                        timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS
                    )
                    if (!shouldClearNetworkPolicyPauseAfterCancellationSettled(cancellationSettled)) {
                        NPLogger.w(
                            TAG,
                            "前一下载取消未在预算内收敛，保留网络暂停标记: " +
                                "song=${song.name}, songKey=$songKey"
                        )
                        if (AudioDownloadManager.isDownloadPausedForNetworkPolicy(songKey)) {
                            updateTaskStatus(
                                songKey = songKey,
                                status = DownloadStatus.WAITING_NETWORK,
                                expectedAttemptId = attemptId
                            )
                            operationId?.let { id ->
                                runCatching {
                                    DownloadExecutionRoomStore.updateState(
                                        context = appContext,
                                        operationId = id,
                                        state = "RETRYABLE",
                                        errorCode = "CANCELLATION_SETTLEMENT_PENDING"
                                    )
                                    WifiBoundDownloadWakeWorker.rearmAfterNetworkPolicyWait(
                                        context = appContext,
                                        operationId = id
                                    )
                                }.onFailure { error ->
                                    NPLogger.w(
                                        TAG,
                                        "登记取消收敛后的 WIFI 唤醒失败: " +
                                            "operationId=$id, error=${error.message}"
                                    )
                                }
                            }
                        }
                        return@withSongExecutionLock
                    }
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
                    if (requiresFinalizationRecovery) {
                        val pendingAudio = findPendingAudioForFinalization(
                            context = appContext,
                            song = song,
                            operationId = operationId,
                            preferredAudioName = artifactClaim.artifact.audioName
                        )
                        if (pendingAudio == null) {
                            NPLogger.w(
                                TAG,
                                "未找到可确认的 pending 音频，拒绝重下并等待恢复: " +
                                    "song=${song.name}, operationId=$operationId"
                            )
                            return@withSongExecutionLock
                        }
                        finalizeCompletedDownload(
                            context = appContext,
                            song = song,
                            refreshCatalog = true,
                            expectedAttemptId = attemptId,
                            operationId = operationId,
                            expectedArtifactLeaseId = acquiredLeaseId,
                            storedAudioHint = pendingAudio
                        )
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
                    val needsFinalization = existingAudio?.let { audio ->
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
                        ).isNotEmpty()
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
                        resumeBatchDownloadPresentationOnRetry(
                            songKey = songKey,
                            attemptId = attemptId
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
                                operationId = operationId,
                                downloadAudioQuality = downloadAudioQuality
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
            } catch (error: DownloadStorageMutationDeferredException) {
                markDownloadArtifactRetryable(
                    context = appContext,
                    song = song,
                    leaseId = acquiredLeaseId,
                    errorCode = DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
                )
                acquiredLeaseId?.let { leaseId ->
                    managedDownloadArtifactLeases.remove(songKey, leaseId)
                }
                removeDownloadTask(songKey, expectedAttemptId = attemptId)
                NPLogger.d(
                    TAG,
                    "下载提交已转入目录迁移持久等待: " +
                        "song=${song.name}, operationId=$operationId"
                )
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
    ): Job? {
        if (songs.isEmpty()) return null

        val appContext = context.applicationContext
        val requestedSongs = songs.distinctBy(SongItem::stableKey)
        if (requestedSongs.isEmpty()) {
            return null
        }
        val requestedSongKeys = requestedSongs.mapTo(linkedSetOf()) { song ->
            song.stableKey()
        }
        val batchPresentationId = beginBatchDownloadPresentation(requestedSongs)
        val startupJob = scope.launch {
            awaitDownloadedSongDeletion(requestedSongs.map(SongItem::stableKey))
            val admissionTicket = awaitDownloadAdmissionTicket(appContext)
            var existingRequestsToRecover = emptyList<DownloadExecutionRequest>()
            val admitted = admitDownloadMutation(appContext, admissionTicket) admission@{
                val inFlightOperationsBySongKey =
                    DownloadExecutionRoomStore.findReadableOperationsBySongKeys(
                        context = appContext,
                        songKeys = requestedSongKeys,
                        states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                        excludeUserStoppedOperations = true
                    )
                val inFlightOperationRequests = requestedSongs.mapNotNull { song ->
                    val songKey = song.stableKey()
                    inFlightOperationsBySongKey[songKey]
                        ?.takeIf { request -> request.song.stableKey() == songKey }
                }
                val inFlightOperationSongKeys = inFlightOperationRequests
                    .mapTo(linkedSetOf()) { request -> request.song.stableKey() }
                existingRequestsToRecover = inFlightOperationRequests.filter { request ->
                    !DownloadExecutionHosts.default.isExecuting(request.operationId)
                }
                bindBatchDownloadPresentationAttempts(
                    batchId = batchPresentationId,
                    attemptIdsBySongKey = taskStore.currentTasks()
                        .asSequence()
                        .filter { task -> task.song.stableKey() in requestedSongKeys }
                        .associate { task -> task.song.stableKey() to task.attemptId }
                )
                val candidateSongs = selectBatchDownloadCandidates(
                    songs = requestedSongs,
                    inFlightSongKeys = inFlightOperationSongKeys
                )
                if (candidateSongs.isEmpty()) {
                    NPLogger.d(
                        TAG,
                        "批量下载请求已有持久化运行 operation，准备恢复交接: " +
                            "requested=${requestedSongs.size}, " +
                            "rehandoff=${existingRequestsToRecover.size}"
                    )
                    if (inFlightOperationSongKeys.isEmpty()) {
                        clearBatchDownloadPresentation(batchPresentationId)
                    }
                    return@admission
                }
                val candidateSongKeys = candidateSongs.mapTo(linkedSetOf()) { song ->
                    song.stableKey()
                }
                clearSongCancellationForFreshStart(appContext, candidateSongKeys)
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
                val stagedQueue = stageAndPromotePendingDownloadQueue(
                    context = appContext,
                    songs = candidateSongs,
                    userInitiated = userInitiated
                ) ?: run {
                    NPLogger.d(
                        TAG,
                        "批量下载意图在存储变更期间已被取消: " +
                            "requested=${candidateSongs.size}"
                    )
                    clearBatchDownloadPresentation(batchPresentationId)
                    return@admission
                }
                if (stagedQueue.skippedSongKeys.isNotEmpty()) {
                    removeBatchDownloadPresentationMembers(
                        batchId = batchPresentationId,
                        songKeys = stagedQueue.skippedSongKeys
                    )
                    NPLogger.w(
                        TAG,
                        "批量下载跳过无法提升的持久化意图，继续其余歌曲: " +
                            "skipped=${stagedQueue.skippedSongKeys.size}, " +
                            "requested=${candidateSongs.size}"
                    )
                }
                val operationIds = stagedQueue.operationIds
                val operationSnapshots = DownloadExecutionRoomStore.readOperationSnapshots(
                    context = appContext,
                    operationIds = operationIds
                )
                val operationIdsBySongKey = linkedMapOf<String, String>()
                operationIds.forEach { operationId ->
                    val operationSnapshot = operationSnapshots[operationId]
                    if (operationSnapshot != null) {
                        operationIdsBySongKey[operationSnapshot.request.song.stableKey()] = operationId
                    }
                }
                val handedOffSongKeys = operationIdsBySongKey
                    .filterValues { operationId ->
                        operationSnapshots[operationId]?.state in
                            DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES
                    }
                    .keys
                if (handedOffSongKeys.isNotEmpty()) {
                    bindBatchDownloadPresentationAttempts(
                        batchId = batchPresentationId,
                        attemptIdsBySongKey = taskStore.currentTasks()
                            .asSequence()
                            .filter { task -> task.song.stableKey() in handedOffSongKeys }
                            .associate { task -> task.song.stableKey() to task.attemptId }
                    )
                }
                val schedulableSongs = candidateSongs.filter { song ->
                    val operationId = operationIdsBySongKey[song.stableKey()]
                    operationId != null &&
                        operationSnapshots[operationId]?.state in
                        DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
                }
                if (schedulableSongs.isEmpty()) {
                    NPLogger.d(
                        TAG,
                        "批量下载 operation 已被其他执行接管: " +
                            "requested=${candidateSongs.size}"
                    )
                    if (
                        inFlightOperationSongKeys.isEmpty() &&
                            handedOffSongKeys.isEmpty()
                    ) {
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
                    requests = existingRequestsToRecover,
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
        registerActiveBatchDownloadJob(startupJob)
        return startupJob
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
            networkType = session.context.currentDownloadNetworkTypeOrNull(),
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
        val deferredSongKeys = deferQueuedDownloadStartForNetworkPolicyIfNeeded(
            context = session.context,
            songs = session.pendingSongs.map(QueuedDownloadRequest::song),
            attemptIdsBySongKey = pendingAttemptIds,
            requestGeneration = session.requestGeneration,
            reason = "batch_start",
            deferForNetworkPolicy = session.deferForNetworkPolicy
        )
        val schedulableRequests = session.pendingSongs.filterNot { request ->
            request.song.stableKey() in deferredSongKeys
        }
        if (schedulableRequests.isEmpty()) {
            return
        }
        NPLogger.d(
            TAG,
            "批量下载正式开始: pendingSongs=${schedulableRequests.size}, " +
                "waitingForWifi=${deferredSongKeys.size}, " +
                "preparedQueuedSongs=${session.preparedQueuedSongs}, " +
                "settledSongKeys=${session.settledSongKeys.size}"
        )
        schedulePendingBatchDownloads(
            session = session,
            pendingAttemptIds = pendingAttemptIds,
            requests = schedulableRequests
        )
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
                        terminalState = BatchDownloadTerminalState.CANCELLED
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
                                terminalState = BatchDownloadTerminalState.COMPLETED
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
                    terminalState = BatchDownloadTerminalState.COMPLETED
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
                    terminalState = BatchDownloadTerminalState.FAILED
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
            terminalState = BatchDownloadTerminalState.COMPLETED
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
            terminalState = BatchDownloadTerminalState.COMPLETED
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
                    terminalState = BatchDownloadTerminalState.CANCELLED
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
        pendingAttemptIds: Map<String, Long>,
        requests: List<QueuedDownloadRequest> = session.pendingSongs
    ) {
        for (request in requests) {
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
                val admitted = try {
                    downloadAdmissionGate.admit(admissionTicket) recoveryAdmission@{
                        val latest = DownloadExecutionRoomStore.read(
                            context = appContext,
                            operationId = candidate.operationId
                        ) ?: return@recoveryAdmission
                        val state = DownloadExecutionRoomStore.state(
                            context = appContext,
                            operationId = candidate.operationId
                        )
                        val requestMatchesSong =
                            latest.song.stableKey() == candidate.song.stableKey()
                        val userStopped = requestMatchesSong &&
                            canScheduleRecoveredDownloadOperation(state) &&
                            DownloadExecutionRoomStore.isStopped(
                                context = appContext,
                                operationId = candidate.operationId
                            )
                        if (
                            !shouldRehandoffRecoveredDownloadOperation(
                                operationState = state,
                                requestMatchesSong = requestMatchesSong,
                                isExecuting = DownloadExecutionHosts.default
                                    .isExecuting(candidate.operationId),
                                isStoppedByUser = userStopped
                            )
                        ) {
                            return@recoveryAdmission
                        }
                        schedule = DownloadExecutionHosts.default.schedule(
                            context = appContext,
                            request = latest
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    NPLogger.w(
                        TAG,
                        "读取遗留下载 operation 失败，保留状态等待下次恢复: " +
                            "song=${candidate.song.name}, operationId=${candidate.operationId}, " +
                            "error=${error.message}",
                        error
                    )
                    continue
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
        val networkType = context.currentDownloadNetworkTypeOrNull()
        if (networkType == TrafficNetworkType.WIFI) {
            return false
        }
        if (networkType == null) {
            NPLogger.d(TAG, "下载网络类型未知，暂不展示移动网络确认提示")
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

    private suspend fun findPendingAudioForFinalization(
        context: Context,
        song: SongItem,
        operationId: String?,
        preferredAudioName: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val songKey = song.stableKey()
        val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank)
        val preferred = preferredAudioName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { name ->
                ManagedDownloadStorage.findDownloadedAudioByName(
                    context = context,
                    name = name
                )
            }
            ?.takeIf(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
        val candidates = mutableListOf<ManagedDownloadStorage.StoredEntry>()
        preferred?.let(candidates::add)
        candidates += ManagedDownloadStorage.listPendingAudioWrites(
            context = context,
            forceRefresh = true
        ).filterNot { candidate -> candidate.reference == preferred?.reference }
        for (candidate in candidates) {
            val metadata = readDownloadedMetadata(context, candidate)
            if (
                (normalizedOperationId != null && metadata?.operationId == normalizedOperationId) ||
                    metadata?.stableKey == songKey
            ) {
                return candidate
            }
        }
        return null
    }

    private fun buildBatchDownloadLibrarySnapshot(
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
        val references = downloadedSongPlaybackReferenceCandidates(downloadedSong)
        if (references.isEmpty()) return null
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        )
        var sawMissing = false
        var sawUncertain = false
        var sawIncompleteAudio = false
        var sawIndexMismatch = false
        for (reference in references) {
            if (reference.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)) {
                sawUncertain = true
                continue
            }
            val evidence = ManagedDownloadReferenceLookup.inspect(context, reference)
            when (evidence) {
                ManagedDownloadReferenceLookup.Result.Present -> Unit
                ManagedDownloadReferenceLookup.Result.Missing -> {
                    sawMissing = true
                    continue
                }
                is ManagedDownloadReferenceLookup.Result.PermissionLost,
                is ManagedDownloadReferenceLookup.Result.ProviderFailure,
                ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                    sawUncertain = true
                    continue
                }
            }
            val cachedAudio = snapshot?.let { currentSnapshot ->
                currentSnapshot.audioEntriesByLookupKey[reference]
                    ?: currentSnapshot.pendingAudioEntries.firstOrNull { entry ->
                        entry.reference == reference ||
                            entry.mediaUri == reference ||
                            entry.localFilePath == reference
                    }
                    ?: ManagedDownloadStorage.findPendingDownloadedAudio(currentSnapshot, song)
            }
            if (snapshot == null || !snapshot.rootEntriesComplete) {
                if (
                    shouldTrustDirectPresentDownloadedSongReference(
                        reference = reference,
                        evidence = evidence,
                        snapshot = snapshot,
                        cachedAudio = cachedAudio
                    )
                ) {
                    NPLogger.d(
                        TAG,
                        "正式下载音频已确认可读，跳过未完成快照门禁: " +
                            "song=${song.name}, reference=$reference, " +
                            "rootEntriesComplete=${snapshot?.rootEntriesComplete}"
                    )
                    scheduleCatalogReconcile(context, forceRefresh = snapshot != null)
                    return downloadedSong
                }
                sawUncertain = true
                continue
            }
            if (
                cachedAudio != null &&
                !isReadableManagedAudioPlaybackAllowed(
                    audioIsPending = cachedAudio.isPendingAudioWrite,
                    downloadActive = false,
                    downloadCancelled = false,
                    metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, cachedAudio),
                    // 非 pending 文件已经由 Present 证据确认可读。旧 v15
                    // 元数据可能只有 downloadFinalized=false 而没有 artifactState,
                    // 不能因为缺少新阶段字段把既有本地歌曲切回远端解析。
                    allowLegacyPublishedAudio = !cachedAudio.isPendingAudioWrite
                )
            ) {
                sawIncompleteAudio = true
                sawUncertain = true
                continue
            }
            if (!shouldTrustFastDownloadedSongCatalogHit(reference, snapshot.knownReferences)) {
                sawIndexMismatch = true
                sawUncertain = true
                continue
            }
            return downloadedSong
        }

        when {
            snapshot == null -> {
                NPLogger.d(
                    TAG,
                    "下载快索引缺少当前目录快照，拒绝把缓存条目视为完成: song=${song.name}"
                )
                scheduleCatalogReconcile(context, forceRefresh = false)
            }
            !snapshot.rootEntriesComplete -> {
                NPLogger.d(
                    TAG,
                    "下载目录快照不完整，拒绝把缓存条目视为完成: song=${song.name}"
                )
                scheduleCatalogReconcile(context, forceRefresh = !sawUncertain)
            }
            sawIncompleteAudio -> {
                NPLogger.d(
                    TAG,
                    "下载快索引命中未完成音频，等待 core 或元信息收尾: song=${song.name}"
                )
                scheduleCatalogReconcile(context, forceRefresh = !sawUncertain)
            }
            sawIndexMismatch -> {
                NPLogger.w(
                    TAG,
                    "下载目录缓存与索引不一致，后台刷新: song=${song.name}"
                )
                scheduleCatalogReconcile(context, forceRefresh = !sawUncertain)
            }
            sawMissing -> scheduleDownloadedSongReferenceReconcile(
                context = context,
                probe = DownloadedSongReferenceProbe(
                    sawMissing = true,
                    sawUncertain = sawUncertain
                )
            )
        }
        return null
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
            downloadFinalized = isFinalizedDownloadedMetadata(existingMetadata),
            metadataEmbeddingState = existingMetadata?.metadataEmbeddingState,
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
                awaitAllDownloadedSongDeletions()
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
        userInitiated: Boolean = false,
        requiresWifiNetwork: Boolean = true,
        downloadAudioQuality: DownloadAudioQualitySelection? = null
    ): List<String> {
        if (songs.isEmpty()) {
            return emptyList()
        }
        return ManagedDownloadStorage.upsertPendingDownloadQueue(
            context = context,
            songs = songs,
            userInitiated = userInitiated,
            requiresWifiNetwork = requiresWifiNetwork,
            downloadAudioQuality = downloadAudioQuality
        )
    }

    private fun beginDownloadRequestGeneration(songs: Collection<SongItem>): Long {
        val snapshot = requestGenerationTracker.begin(songs)
        NPLogger.d(TAG, "登记下载请求代际: generation=${snapshot.generation}, songs=${snapshot.songCount}")
        return snapshot.generation
    }

    private fun reuseOrBeginDownloadRequestGeneration(
        song: SongItem,
        attemptId: Long
    ): Long {
        val songKey = song.stableKey()
        val reuseCurrent = taskStore.isDownloadAttemptActive(
            songKey = songKey,
            expectedAttemptId = attemptId
        )
        val currentGeneration = requestGenerationTracker.currentGeneration(songKey)
        val generation = requestGenerationTracker.reuseOrBegin(
            song = song,
            reuseCurrent = reuseCurrent
        )
        if (reuseCurrent && currentGeneration != null) {
            NPLogger.d(
                TAG,
                "复用下载请求代际: generation=$generation, song=${song.name}"
            )
        } else {
            NPLogger.d(TAG, "登记下载请求代际: generation=$generation, songs=1")
        }
        return generation
    }

    private fun beginBatchDownloadPresentation(songs: Collection<SongItem>): Long {
        val activeAttemptIdsBySongKey = taskStore.currentTasks()
            .asSequence()
            .associate { task -> task.song.stableKey() to task.attemptId }
        val memberAttemptIds = songs
            .asSequence()
            .map(SongItem::stableKey)
            .filter(String::isNotBlank)
            .distinct()
            .associateWith { songKey ->
                activeAttemptIdsBySongKey[songKey]
                    ?.takeIf { attemptId -> attemptId > 0L }
            }
        val batchId = batchDownloadPresentationIdGenerator.incrementAndGet()
        _batchDownloadPresentations.update { presentations ->
            presentations + (
                batchId to BatchDownloadPresentationState(
                    id = batchId,
                    memberAttemptIds = memberAttemptIds
                )
            )
        }
        return batchId
    }

    private fun bindBatchDownloadPresentationAttempts(
        batchId: Long,
        attemptIdsBySongKey: Map<String, Long>
    ) {
        if (attemptIdsBySongKey.isEmpty()) {
            return
        }
        _batchDownloadPresentations.update { presentations ->
            val presentation = presentations[batchId] ?: return@update presentations
            val newlyBoundKeys = attemptIdsBySongKey
                .filter { (songKey, attemptId) ->
                    attemptId > 0L && presentation.memberAttemptIds[songKey] != attemptId
                }
                .keys
            val updatedMemberAttemptIds = presentation.memberAttemptIds.mapValues { (songKey, attemptId) ->
                attemptIdsBySongKey[songKey]
                    ?.takeIf { candidate -> candidate > 0L }
                    ?: attemptId
            }
            if (
                updatedMemberAttemptIds == presentation.memberAttemptIds &&
                    newlyBoundKeys.isEmpty()
            ) {
                return@update presentations
            }
            presentations + (
                batchId to presentation.copy(
                    memberAttemptIds = updatedMemberAttemptIds,
                    terminalStates = presentation.terminalStates.filterKeys { songKey ->
                        songKey !in newlyBoundKeys
                    }
                )
            )
        }
    }

    private fun updateBatchDownloadPresentationProgress(
        progress: AudioDownloadManager.DownloadProgress
    ) {
        val songKey = progress.songKey
        val fraction = downloadProgressFraction(progress)
        _batchDownloadPresentations.update { presentations ->
            var changed = false
            val updatedPresentations = presentations.mapValues { (_, presentation) ->
                val expectedAttemptId = presentation.memberAttemptIds[songKey]
                if (
                    songKey !in presentation.memberAttemptIds ||
                        (expectedAttemptId != null && expectedAttemptId != progress.attemptId)
                ) {
                    return@mapValues presentation
                }
                val retainedFraction = presentation.maximumObservedFractions[songKey] ?: 0f
                if (fraction <= retainedFraction) {
                    return@mapValues presentation
                }
                changed = true
                presentation.copy(
                    maximumObservedFractions = presentation.maximumObservedFractions +
                        (songKey to fraction)
                )
            }
            if (changed) updatedPresentations else presentations
        }
    }

    private fun markBatchDownloadPresentationTerminal(
        songKey: String,
        attemptId: Long?,
        terminalState: BatchDownloadTerminalState
    ) {
        if (attemptId == null || attemptId <= 0L) {
            return
        }
        val completedBatchIds = linkedSetOf<Long>()
        _batchDownloadPresentations.update { presentations ->
            var changed = false
            val updatedPresentations = presentations.mapValues { (presentationId, presentation) ->
                val memberAttemptId = presentation.memberAttemptIds[songKey]
                if (
                    songKey !in presentation.memberAttemptIds ||
                        (memberAttemptId != null && memberAttemptId != attemptId) ||
                        presentation.terminalStates[songKey] != null
                ) {
                    return@mapValues presentation
                }
                val updatedMemberAttemptIds = if (memberAttemptId == null) {
                    presentation.memberAttemptIds + (songKey to attemptId)
                } else {
                    presentation.memberAttemptIds
                }
                val updatedTerminalStates = presentation.terminalStates +
                    (songKey to terminalState)
                val updatedPresentation = presentation.copy(
                    memberAttemptIds = updatedMemberAttemptIds,
                    terminalStates = updatedTerminalStates
                )
                if (updatedTerminalStates.size == updatedMemberAttemptIds.size) {
                    completedBatchIds += presentationId
                }
                changed = true
                updatedPresentation
            }
            if (changed) updatedPresentations else presentations
        }
        completedBatchIds.forEach(::scheduleCompletedBatchDownloadPresentationRemoval)
    }

    private fun resumeBatchDownloadPresentationOnRetry(
        songKey: String,
        attemptId: Long
    ) {
        _batchDownloadPresentations.update { presentations ->
            var changed = false
            val updatedPresentations = presentations.mapValues { (_, presentation) ->
                val updatedPresentation = resumeBatchDownloadPresentationForRetry(
                    presentation = presentation,
                    songKey = songKey,
                    attemptId = attemptId
                )
                if (updatedPresentation != presentation) {
                    changed = true
                }
                updatedPresentation
            }
            if (changed) updatedPresentations else presentations
        }
    }

    private fun scheduleCompletedBatchDownloadPresentationRemoval(batchId: Long) {
        scope.launch {
            delay(DOWNLOAD_TASK_COMPLETED_RETENTION_MS)
            _batchDownloadPresentations.update { presentations ->
                val presentation = presentations[batchId] ?: return@update presentations
                if (presentation.terminalStates.size == presentation.memberAttemptIds.size) {
                    presentations - batchId
                } else {
                    presentations
                }
            }
        }
    }

    private fun clearBatchDownloadPresentation(batchId: Long? = null) {
        _batchDownloadPresentations.update { presentations ->
            if (batchId == null) {
                emptyMap()
            } else {
                presentations - batchId
            }
        }
    }

    private fun removeBatchDownloadPresentationMembers(
        batchId: Long,
        songKeys: Collection<String>
    ) {
        val keysToRemove = songKeys.filter(String::isNotBlank).toSet()
        if (keysToRemove.isEmpty()) {
            return
        }
        _batchDownloadPresentations.update { presentations ->
            val presentation = presentations[batchId] ?: return@update presentations
            val memberAttemptIds = presentation.memberAttemptIds.filterKeys { songKey ->
                songKey !in keysToRemove
            }
            if (memberAttemptIds.isEmpty()) {
                return@update presentations - batchId
            }
            presentations + (
                batchId to presentation.copy(
                    memberAttemptIds = memberAttemptIds,
                    terminalStates = presentation.terminalStates.filterKeys { songKey ->
                        songKey in memberAttemptIds
                    },
                    maximumObservedFractions = presentation.maximumObservedFractions.filterKeys { songKey ->
                        songKey in memberAttemptIds
                    }
                )
            )
        }
    }

    private fun invalidateDownloadRequestGenerations(songKeys: Collection<String>) =
        requestGenerationTracker.snapshotAndInvalidate(songKeys).also { snapshot ->
            if (snapshot.invalidatedCount > 0) {
                NPLogger.d(TAG, "失效下载请求代际: songs=${snapshot.invalidatedCount}")
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
        DownloadExecutionRoomStore.clearUserStopForStableKeys(
            context = context.applicationContext,
            stableKeys = keys
        )
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
        requestDownloadTaskCancellation(setOf(songKey))
    }

    private fun requestDownloadTaskCancellation(songKeys: Collection<String>): Job? {
        val appContext = AppContainer.applicationContext
        val requestedSongKeys = songKeys
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (requestedSongKeys.isEmpty()) return null
        val tasksBySongKey = if (requestedSongKeys.size == 1) {
            val songKey = requestedSongKeys.single()
            mapOf(songKey to taskStore.findTask(songKey))
        } else {
            taskStore.currentTasks().associateBy { task -> task.song.stableKey() }
        }
        val cancellationKeys = requestedSongKeys.filter { songKey ->
            val task = tasksBySongKey[songKey]
            task == null || isDownloadTaskCancellationCandidate(task)
        }
        if (cancellationKeys.isEmpty()) return null
        cancellationKeys.forEach(::markSongCancelled)
        val invalidation = invalidateDownloadRequestGenerations(cancellationKeys)
        val cancellationEntries = cancellationKeys.map { songKey ->
            DownloadTaskCancellationEntry(
                songKey = songKey,
                task = tasksBySongKey[songKey],
                cancellationGeneration = invalidation.generationsBySongKey[songKey]
            )
        }
        cancellationEntries.forEach { entry ->
            entry.task?.let { currentTask ->
                markBatchDownloadPresentationTerminal(
                    songKey = entry.songKey,
                    attemptId = currentTask.attemptId,
                    terminalState = BatchDownloadTerminalState.CANCELLED
                )
                removeDownloadTask(
                    songKey = entry.songKey,
                    expectedAttemptId = currentTask.attemptId
                )
            }
        }
        return scope.launch {
            cancelDownloadTasksDurably(
                context = appContext,
                entries = cancellationEntries
            )
        }
    }

    private suspend fun cancelDownloadTasksDurably(
        context: Context,
        entries: Collection<DownloadTaskCancellationEntry>
    ) {
        val logSettlement = entries.size == 1
        val failureCount = AtomicInteger(0)
        val failureLock = Any()
        var firstFailure: Exception? = null
        runBoundedDownloadCancellationCleanup(
            items = entries,
            parallelism = DOWNLOAD_CANCEL_CLEANUP_PARALLELISM
        ) { entry ->
            try {
                cancelDownloadTaskDurably(
                    context = context,
                    task = entry.task,
                    cancellationGeneration = entry.cancellationGeneration,
                    songKey = entry.songKey,
                    logSettlement = logSettlement
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failureCount.incrementAndGet()
                synchronized(failureLock) {
                    if (firstFailure == null) firstFailure = error
                }
            }
        }
        if (failureCount.get() > 0) {
            NPLogger.e(
                TAG,
                "批量下载取消持久清理失败: songs=${failureCount.get()}, " +
                    "total=${entries.size}",
                firstFailure
            )
        }
    }

    private suspend fun cancelDownloadTaskDurably(
        context: Context,
        task: DownloadTask?,
        cancellationGeneration: Long?,
        songKey: String,
        logSettlement: Boolean = true
    ) {
        if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            return
        }
        val operationId = DownloadExecutionHosts.default.operationIdForSong(context, songKey)
        val operationRequest = operationId
            ?.let { id -> DownloadExecutionOperationStore().read(context, id) }
            ?.takeIf { request -> request.song.stableKey() == songKey }
        if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            return
        }
        if (operationId != null) {
            DownloadExecutionHosts.default.cancel(context, operationId)
        } else {
            DownloadExecutionHosts.default.cancelForSong(context, songKey)
        }
        if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            return
        }
        requestOperationCancellation(setOf(songKey))
        if (task == null) {
            val settled = awaitSongCancellationSettled(
                songKey = songKey,
                timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS,
                clearCancellationWhenSettled = false,
                logProgress = logSettlement
            )
            if (!settled) return
            if (isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
                operationRequest?.let { request ->
                    releaseDownloadArtifactAfterExecutionOwnershipLoss(
                        context = context,
                        song = request.song,
                        operationId = request.operationId,
                        expectedLeaseId = request.artifactLeaseId
                    )
                }
                ManagedDownloadStorage.deletePendingWorkingDownloadArtifacts(
                    context,
                    setOf(songKey)
                )
                val rootCleanupSucceeded = operationRequest?.let { request ->
                    cleanupCancelledPendingDownloadArtifacts(
                        context = context,
                        song = request.song,
                        operationId = request.operationId
                    )
                } ?: true
                if (shouldPurgeCancelledDownloadOperation(
                        keepCancellationOperation = false,
                        cleanupSucceeded = rootCleanupSucceeded
                    )
                ) {
                    DownloadExecutionRoomStore.purgeCancelled(context, setOf(songKey))
                } else {
                    NPLogger.w(
                        TAG,
                        "无任务卡片的取消清理未确认，保留 Room 凭据: songKey=$songKey"
                    )
                }
                clearSongCancelled(songKey)
            }
            return
        }
        forgetPendingDownloadQueueEntries(context, setOf(songKey))
        cancelDownloadTaskInBackground(
            task = task,
            cancellationGeneration = cancellationGeneration,
            operationRequest = operationRequest,
            logSettlement = logSettlement
        )
    }

    fun clearAllDownloadTasks() {
        cancelAllDownloadTasks()
    }

    fun cancelAllDownloadTasks() {
        requestAllDownloadTaskCancellation()
    }

    private fun requestAllDownloadTaskCancellation(
        purpose: DownloadClearPurpose = DownloadClearPurpose.TASK_PROGRESS
    ): Job {
        val appContext = AppContainer.applicationContext
        val hadPersistedClearFence = PersistentDownloadClearFenceStore.isActive(appContext)
        val clearToken = downloadAdmissionGate.beginClear()
        val clearFenceEpoch = if (clearToken.ownsClear) {
            PersistentDownloadClearFenceStore.beginClear(purpose)
        } else {
            null
        }
        // 先同步落盘栅栏，再把耗时取消工作交给后台，进程马上被杀也能在重启时续接
        val fenceActivatedImmediately = clearToken.ownsClear &&
            PersistentDownloadClearFenceStore.activate(appContext)
        downloadClearVisibility.begin(clearToken)
        dismissMobileDataDownloadInterruptionRequest()
        if (!clearToken.ownsClear) {
            return scope.launch {
                downloadAdmissionGate.awaitClear(clearToken)
            }
        }
        return scope.launch {
            try {
                if (clearToken.ownsClear) {
                    if (!hadPersistedClearFence) {
                        clearPersistedDownloadClearProgress(appContext)
                    } else {
                        restoreDownloadClearProgress(appContext, clearToken)
                    }
                    persistDownloadClearProgress(appContext, clearToken)
                }
                if (!fenceActivatedImmediately) {
                    activateDownloadClearFence(appContext)
                }
                downloadClearVisibility.markFencePersisted(clearToken)
                persistDownloadClearProgress(appContext, clearToken)
                downloadClearVisibility.update(
                    token = clearToken,
                    phase = DownloadClearVisibility.ClearPhase.CANCELLING,
                    completedSteps = 1,
                    affectedItemCount = taskStore.currentTasks().size
                )
                persistDownloadClearProgress(appContext, clearToken)
                clearBatchDownloadPresentation()
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
                            val allOperationIdentities = if (
                                initialHostCancellationSnapshotCaptured
                            ) {
                                emptyList()
                            } else {
                                DownloadExecutionRoomStore.listAllOperationIdentities(appContext)
                            }
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
                            val newlyDiscoveredCancellation =
                                invalidateDownloadRequestGenerations(newlyDiscoveredSongKeys)
                            val newlyDiscoveredGenerations =
                                newlyDiscoveredCancellation.generationsBySongKey
                            newlyDiscoveredGenerations.forEach { (songKey, generation) ->
                                cancellationGenerations.putIfAbsent(songKey, generation)
                            }
                            clearPendingDownloadQueue(appContext)
                            if (!initialHostCancellationSnapshotCaptured) {
                                stopDownloadExecutionImmediately(
                                    context = appContext,
                                    reason = "durable download cancellation recorded"
                                )
                            } else if (newlyDiscoveredOperationIds.isNotEmpty()) {
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
                            downloadClearVisibility.update(
                                token = clearToken,
                                phase = DownloadClearVisibility.ClearPhase.CLEANING,
                                completedSteps = 2,
                                affectedItemCount = clearSongKeys.size,
                                completedItemCount = 0,
                                totalItemCount = 0
                            )
                            persistDownloadClearProgress(appContext, clearToken)
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
                                workingFilesBySongKey = clearWorkingFilesBySongKey,
                                clearToken = clearToken
                            )
                            if (!settlement.isSettled) {
                                NPLogger.w(
                                    TAG,
                                    "下载清空仍在等待执行收敛: activeSongs=" +
                                        "${settlement.activeSongKeys.size}, activeOperations=" +
                                        "${settlement.activeOperationIds.size}, " +
                                        "batchJobsSettled=${settlement.batchJobsSettled}, " +
                                        "residualWorking=${settlement.residualWorkingSongKeys.size}, " +
                                        "residualPending=" +
                                        settlement.residualPendingArtifactSongKeys.size
                                )
                                taskStore.clearAllTasks()
                                stopDownloadExecutionImmediately(
                                    context = appContext,
                                    reason = "waiting for download clear convergence"
                                )
                                delay(DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS)
                                continue
                            }
                            downloadClearVisibility.update(
                                token = clearToken,
                                phase = DownloadClearVisibility.ClearPhase.PURGING,
                                completedSteps = 3,
                                affectedItemCount = clearSongKeys.size
                            )
                            persistDownloadClearProgress(appContext, clearToken)
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
                            downloadClearVisibility.update(
                                token = clearToken,
                                phase = DownloadClearVisibility.ClearPhase.PURGING,
                                completedSteps = 4,
                                affectedItemCount = clearSongKeys.size
                            )
                            persistDownloadClearProgress(appContext, clearToken)
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
                clearPersistedDownloadClearProgress(appContext)
                // 栅栏释放后再触发一次扫描，确保清空期间被延后的 catalog 最终收敛
                refreshDownloadedSongsForManager(appContext, forceRefresh = true)
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

    private fun persistDownloadClearProgress(
        context: Context,
        token: DownloadAdmissionGate.ClearToken
    ) {
        if (!token.ownsClear) return
        downloadClearVisibility.progress.value?.let { progress ->
            PersistentDownloadClearProgressStore.save(context, progress)
        }
    }

    private fun restoreDownloadClearProgress(
        context: Context,
        token: DownloadAdmissionGate.ClearToken
    ) {
        if (!token.ownsClear) return
        PersistentDownloadClearProgressStore.read(context)?.let { progress ->
            downloadClearVisibility.restore(token, progress)
        }
    }

    private fun clearPersistedDownloadClearProgress(context: Context) {
        PersistentDownloadClearProgressStore.clear(context)
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

    fun interruptDownloadsForWifiDisconnected(callbackNetworkType: TrafficNetworkType?) {
        val appContext = AppContainer.applicationContext
        val initialNetworkType = appContext.currentDownloadNetworkTypeOrNull()
        if (callbackNetworkType == null || initialNetworkType == null) {
            mobileDataDownloadOverrideAllowed = false
            scope.launch {
                pauseActiveDownloadsForUnknownNetwork(
                    context = appContext,
                    reason = "wifi_disconnected_unknown_network"
                )
            }
            return
        }
        if (!shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = callbackNetworkType,
                currentNetworkType = initialNetworkType
            )
        ) {
            NPLogger.d(
                TAG,
                "忽略未生效的 WIFI 断开回调: callbackType=$callbackNetworkType, " +
                    "currentType=$initialNetworkType"
            )
            return
        }
        mobileDataDownloadOverrideAllowed = false
        scope.launch {
            val currentNetworkType = appContext.currentDownloadNetworkTypeOrNull()
            if (currentNetworkType == null) {
                pauseActiveDownloadsForUnknownNetwork(
                    context = appContext,
                    reason = "wifi_disconnected_network_snapshot_lost"
                )
                return@launch
            }
            if (!shouldPauseDownloadsForWifiDisconnect(
                    callbackNetworkType = callbackNetworkType,
                    currentNetworkType = currentNetworkType
                )
            ) {
                NPLogger.d(
                    TAG,
                    "忽略未生效的 WIFI 断开回调: callbackType=$callbackNetworkType, currentType=$currentNetworkType"
                )
                return@launch
            }
            val interruptionSnapshotEpoch = mobileDataDownloadInterruptionEpoch.get()
            val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()
            val activeTasks = wifiBoundTasksForNetworkPolicy(
                context = appContext,
                tasks = currentActiveNetworkPolicyTasks()
            )
            val waitingSongs = wifiBoundSongsForNetworkPolicy(
                context = appContext,
                songs = currentWaitingNetworkTaskSongs()
            )
            val persistedSongKeys = persistedWifiBoundSongKeys(appContext)
            if (!hasWifiBoundNetworkPolicyDownloads(
                    activeTaskCount = activeTasks.size,
                    persistedQueuedCount = persistedSongKeys.size + waitingSongs.size
                )
            ) {
                return@launch
            }
            val taskCount = wifiBoundDownloadTaskCount(
                activeSongKeys = activeTasks.map { task -> task.song.stableKey() } +
                    waitingSongs.map(SongItem::stableKey),
                persistedSongKeys = persistedSongKeys
            )
            if (!isWifiBoundNetworkPolicyStillRequired(appContext, networkPolicyEpoch)) {
                NPLogger.d(
                    TAG,
                    "WIFI 断开策略已过期，保留 WIFI 恢复路径: " +
                        "callbackType=$callbackNetworkType"
                )
                return@launch
            }
            NPLogger.w(
                TAG,
                "WIFI 已断开，等待用户确认下载策略: callbackType=$callbackNetworkType, " +
                    "currentType=$currentNetworkType, " +
                    "activeTasks=${activeTasks.size}, persisted=${persistedSongKeys.size}, waiting=${waitingSongs.size}"
            )
            publishMobileDataDownloadInterruptionRequestIfNeeded(
                context = appContext,
                networkType = currentNetworkType,
                fallbackTaskCount = taskCount.coerceAtLeast(1),
                reason = "wifi_disconnected",
                authoritativeTaskCount = taskCount,
                interruptionSnapshotEpoch = interruptionSnapshotEpoch
            )
            val paused = pauseDownloadTasksForNetworkPolicy(
                context = appContext,
                activeTasks = activeTasks,
                networkPolicyEpoch = networkPolicyEpoch
            )
            if (!paused) {
                recoverWifiBoundDownloadsIfNetworkPolicyExpired(
                    context = appContext,
                    snapshotEpoch = networkPolicyEpoch,
                    reason = "wifi_disconnect_policy_stale"
                )
            }
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
        dismissMobileDataDownloadInterruptionRequest()
        recoverPendingDownloadsOnCurrentNetwork(context)
    }

    fun waitDownloadsForWifi(request: MobileDataDownloadInterruptionRequest) {
        if (_mobileDataDownloadInterruptionRequest.value?.id != request.id) {
            return
        }
        dismissMobileDataDownloadInterruptionRequest()
        mobileDataDownloadOverrideAllowed = false
        scope.launch {
            val appContext = AppContainer.applicationContext
            val activeTasks = taskStore.currentTasks().filter { task ->
                task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
            }
            val paused = pauseDownloadTasksForNetworkPolicy(
                context = appContext,
                activeTasks = activeTasks
            )
            if (!paused &&
                appContext.currentDownloadNetworkTypeOrNull() == TrafficNetworkType.WIFI
            ) {
                recoverPendingDownloadsForNetworkRestored(
                    context = appContext,
                    reason = "user_wait_wifi_network_already_restored"
                )
            }
        }
    }

    fun cancelAllDownloadsForMobileData(request: MobileDataDownloadInterruptionRequest) {
        if (_mobileDataDownloadInterruptionRequest.value?.id != request.id) {
            return
        }
        dismissMobileDataDownloadInterruptionRequest()
        cancelAllDownloadTasks()
    }

    private fun recoverPendingDownloadsOnCurrentNetwork(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            withPendingDownloadRecoverySlot("current_network") {
                if (isDownloadClearFenceActive(appContext)) {
                    return@withPendingDownloadRecoverySlot
                }
                val networkType = appContext.currentDownloadNetworkTypeOrNull()
                if (networkType == null) {
                    return@withPendingDownloadRecoverySlot
                }
                if (networkType != TrafficNetworkType.WIFI && !mobileDataDownloadOverrideAllowed) {
                    return@withPendingDownloadRecoverySlot
                }
                promoteWaitingStorageMutationsForRecovery(appContext)
                waitForActiveDownloadJobsToSettle()
                waitForQueuedTasksToAttachToBatch()
                if (hasBlockingActiveDownloadOperationsForRecovery()) {
                    return@withPendingDownloadRecoverySlot
                }
                recoverPendingResumableDownloads(
                    appContext,
                    reason = "mobile_data_user_confirmed"
                )
                delay(1_500L)
            }
        }
    }

    internal suspend fun recoverPendingDownloadsFromWifiWake(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            withPendingDownloadRecoverySlot("wifi_wake") {
            if (isDownloadClearFenceActive(appContext)) {
                return@withPendingDownloadRecoverySlot true
            }
            if (appContext.currentDownloadNetworkTypeOrNull() != TrafficNetworkType.WIFI) {
                return@withPendingDownloadRecoverySlot false
            }
            if (!onWifiBoundDownloadNetworkRestored(appContext, "wifi_wake")) {
                return@withPendingDownloadRecoverySlot false
            }
            promoteWaitingStorageMutationsForRecovery(appContext)
            repairFinalizedDownloadedCoversFromRoot(appContext)
            if (!hasPendingRecoveryCandidates(appContext)) {
                true
            } else {
                reconcilePendingDownloadArtifacts(appContext)
                waitForActiveDownloadJobsToSettle()
                waitForQueuedTasksToAttachToBatch()
                if (hasBlockingActiveDownloadOperationsForRecovery()) {
                    NPLogger.d(
                        TAG,
                        "WIFI 唤醒恢复已有活动下载，保留 WorkManager 重试"
                    )
                    false
                } else {
                    val accepted = recoverPendingResumableDownloads(
                        context = appContext,
                        reason = "wifi_wake"
                    )
                    if (!hasPendingRecoveryCandidates(appContext)) {
                        true
                    } else {
                        NPLogger.d(
                            TAG,
                            "WIFI 唤醒恢复尚未成为终态，保留 WorkManager 重试: " +
                                "accepted=$accepted"
                        )
                        false
                    }
                }
            }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "WIFI 唤醒恢复失败，将由 WorkManager 重试: ${error.message}",
                error
            )
            false
        }
    }

    private suspend fun cancelDownloadTaskInBackground(
        task: DownloadTask,
        cancellationGeneration: Long?,
        operationRequest: DownloadExecutionRequest?,
        logSettlement: Boolean
    ) {
        val appContext = AppContainer.applicationContext
        val songKey = task.song.stableKey()
        if (isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
            requestOperationCancellation(setOf(songKey))
        }
        AudioDownloadManager.cancelSongDownload(songKey)
        val settled = awaitSongCancellationSettled(
            songKey = songKey,
            timeoutMs = DOWNLOAD_CANCEL_FAST_SETTLE_TIMEOUT_MS,
            clearCancellationWhenSettled = false,
            logProgress = logSettlement
        )
        if (!settled) return
        withSongExecutionLock(songKey) {
            if (!isCancellationCleanupStillCurrent(songKey, cancellationGeneration)) {
                if (logSettlement) {
                    NPLogger.d(
                        TAG,
                        "跳过过期单曲取消清理: song=${task.song.name}, songKey=$songKey"
                    )
                }
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
            cleanupCancelledDownloadArtifacts(
                context = appContext,
                song = task.song,
                operationId = operationRequest?.operationId
            )
            clearSongCancelled(songKey)
        }
    }

    private fun createDownloadClearProgressReporter(
        token: DownloadAdmissionGate.ClearToken,
        affectedItemCount: Int
    ): (Int, Int) -> Unit {
        val progressLock = Any()
        var lastReportedItems = -1
        var lastReportedAtMs = 0L
        return { completedItems, totalItems ->
            val normalizedTotal = totalItems.coerceAtLeast(0)
            val normalizedCompleted = completedItems
                .coerceAtLeast(0)
                .let { completed ->
                    if (normalizedTotal > 0) {
                        completed.coerceAtMost(normalizedTotal)
                    } else {
                        completed
                    }
                }
            val nowMs = System.currentTimeMillis()
            synchronized(progressLock) {
                val shouldReport = normalizedCompleted == 0 ||
                    normalizedCompleted >= normalizedTotal && normalizedTotal > 0 ||
                    normalizedCompleted - lastReportedItems >=
                        DOWNLOAD_CLEAR_PROGRESS_UPDATE_BATCH_SIZE ||
                    nowMs - lastReportedAtMs >= DOWNLOAD_CLEAR_PROGRESS_UPDATE_INTERVAL_MS
                if (!shouldReport) {
                    return@synchronized
                }
                lastReportedItems = normalizedCompleted
                lastReportedAtMs = nowMs
                downloadClearVisibility.update(
                    token = token,
                    phase = DownloadClearVisibility.ClearPhase.CLEANING,
                    completedSteps = 2,
                    affectedItemCount = affectedItemCount,
                    completedItemCount = normalizedCompleted,
                    totalItemCount = normalizedTotal
                )
                persistDownloadClearProgress(
                    context = AppContainer.applicationContext,
                    token = token
                )
            }
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
        workingFilesBySongKey: Map<String, Collection<File>> = emptyMap(),
        clearToken: DownloadAdmissionGate.ClearToken? = null
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
        // pending 音频和恢复元数据统一批量删除。逐 operation 扫描 staging 目录会把
        // 大批量清空退化为 O(歌曲数) 次目录遍历，且更容易撞上 SAF 瞬时失败。
        val clearProgressReporter = clearToken?.let { token ->
            createDownloadClearProgressReporter(
                token = token,
                affectedItemCount = activeKeys.size
            )
        }
        val residualPendingArtifactSongKeys = cleanupCancelledPendingDownloadArtifacts(
            context = appContext,
            operationRequests = operationRequests,
            cancellationGenerations = cancellationGenerations,
            onProgress = clearProgressReporter
        )
        val batchCleanupKeys = activeKeys
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
            residualWorkingSongKeys = residualWorkingSongKeys,
            residualPendingArtifactSongKeys = residualPendingArtifactSongKeys
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

    private suspend fun pauseDownloadTasksForNetworkPolicy(
        context: Context,
        activeTasks: List<DownloadTask>,
        networkPolicyEpoch: Long = wifiBoundNetworkPolicyEpoch.get()
    ): Boolean {
        if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
            return false
        }
        val wifiBoundActiveTasks = wifiBoundTasksForNetworkPolicy(
            context = context,
            tasks = activeTasks
        )
        val activeKeys = persistedWifiBoundSongKeys(context).toMutableSet()
        wifiBoundActiveTasks.mapTo(activeKeys) { task -> task.song.stableKey() }
        wifiBoundSongsForNetworkPolicy(
            context = context,
            songs = currentWaitingNetworkTaskSongs()
        ).mapTo(activeKeys) { song -> song.stableKey() }
        if (activeKeys.isEmpty()) {
            return false
        }
        val paused = mutateWifiBoundNetworkPolicyIfStillRequired(
            context = context,
            snapshotEpoch = networkPolicyEpoch
        ) {
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(activeKeys)
            taskStore.applyWaitingNetworkStatus(wifiBoundActiveTasks)
            wifiBoundActiveTasks.filter { task ->
                task.status == DownloadStatus.DOWNLOADING ||
                    AudioDownloadManager.isSongDownloadActive(task.song.stableKey())
            }.forEach { task ->
                DownloadExecutionHosts.default.stopForSong(
                    context = context.applicationContext,
                    songKey = task.song.stableKey(),
                    preventReschedule = false
                )
            }
            mobileDataDownloadOverrideAllowed = false
        }
        if (!paused) {
            return false
        }
        scheduleWifiBoundDownloadWakeups(context, activeKeys)
        return true
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

        val appContext = context.applicationContext
        scope.launch {
            if (task.status == DownloadStatus.CANCELLED) {
                DownloadExecutionHosts.default.cancelForSong(
                    context = appContext,
                    songKey = songKey
                )
                if (
                    !awaitSongCancellationSettled(
                        songKey = songKey,
                        clearCancellationWhenSettled = false,
                        logProgress = false
                    )
                ) {
                    NPLogger.w(TAG, "取消中的下载仍未收敛，暂不恢复: song=${task.song.name}")
                    return@launch
                }
                DownloadExecutionRoomStore.purgeCancelled(appContext, setOf(songKey))
            }
            clearSongCancelled(songKey)
            scheduleUserDownload(
                context = appContext,
                song = task.song,
                skipTrafficRiskPrompt = false,
                preserveStaging = task.status == DownloadStatus.WAITING_NETWORK,
                replacingAttemptId = task.attemptId
            )
        }
    }

    private suspend fun awaitSongCancellationSettled(
        songKey: String,
        timeoutMs: Long = DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS,
        clearCancellationWhenSettled: Boolean = true,
        logProgress: Boolean = true
    ): Boolean {
        if (!isSongCancelled(songKey) && !AudioDownloadManager.isSongDownloadActive(songKey)) {
            return true
        }
        if (logProgress) {
            NPLogger.d(
                TAG,
                "等待歌曲取消状态收敛: songKey=$songKey, " +
                    "cancelled=${isSongCancelled(songKey)}, " +
                    "active=${AudioDownloadManager.isSongDownloadActive(songKey)}"
            )
        }

        val deadlineAt = System.currentTimeMillis() + timeoutMs
        while (AudioDownloadManager.isSongDownloadActive(songKey) && System.currentTimeMillis() < deadlineAt) {
            delay(50)
        }
        if (AudioDownloadManager.isSongDownloadActive(songKey)) {
            if (logProgress) {
                NPLogger.w(TAG, "等待取消中的下载清理超时: songKey=$songKey")
            }
            return false
        }
        if (logProgress) {
            NPLogger.d(
                TAG,
                "歌曲取消状态已收敛: songKey=$songKey, " +
                    "cancelledBeforeClear=${isSongCancelled(songKey)}"
            )
        }
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

    private fun resolveFinalizedManagedAudioSnapshot(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        candidate: ManagedDownloadStorage.StoredEntry
    ): FinalizedManagedAudioSnapshot? {
        val currentAudio = listOfNotNull(
            candidate.reference,
            candidate.mediaUri,
            candidate.localFilePath
        ).asSequence()
            .mapNotNull(snapshot.audioEntriesByLookupKey::get)
            .firstOrNull()
            ?: return null
        val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, currentAudio)
            ?: return null
        if (!isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = snapshot.rootEntriesComplete,
                isPendingAudioWrite = currentAudio.isPendingAudioWrite,
                metadata = metadata
            )
        ) {
            return null
        }
        return FinalizedManagedAudioSnapshot(
            snapshot = snapshot,
            audio = currentAudio,
            metadata = metadata
        )
    }

    /**
     * 播放入口允许 core 已提交但仍使用 pending 文件名的音频
     * 只接受 provider Present 和持久化完成状态，不能仅凭目录中有文件就放行
     */
    private fun resolvePlayableManagedAudioSnapshot(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        candidate: ManagedDownloadStorage.StoredEntry,
        song: SongItem
    ): PlayableManagedAudioSnapshot? {
        val references = listOfNotNull(
            candidate.reference.takeIf(String::isNotBlank),
            candidate.mediaUri.takeIf(String::isNotBlank),
            candidate.localFilePath?.takeIf(String::isNotBlank)
        )
        val candidateEntries = sequence {
            references.forEach { reference ->
                snapshot.audioEntriesByLookupKey[reference]?.let { yield(it) }
                snapshot.pendingAudioEntries.firstOrNull { entry ->
                    entry.reference == reference ||
                        entry.mediaUri == reference ||
                        entry.localFilePath == reference
                }?.let { yield(it) }
            }
            ManagedDownloadStorage.findDownloadedAudio(snapshot, song)?.let { yield(it) }
            ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)?.let { yield(it) }
        }.distinctBy(ManagedDownloadStorage.StoredEntry::reference)
        val currentAudio = candidateEntries.firstOrNull { audio ->
            val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
            metadata == null || isDownloadedMetadataIdentityCompatible(song, metadata)
        } ?: return null
        val playbackReference = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(
            entry = currentAudio,
            allowPending = currentAudio.isPendingAudioWrite
        ) ?: return null
        if (
            ManagedDownloadReferenceLookup.inspect(context, playbackReference) !=
                ManagedDownloadReferenceLookup.Result.Present
        ) {
            return null
        }
        val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, currentAudio)
        if (
            !isReadableManagedAudioPlaybackAllowed(
                audioIsPending = currentAudio.isPendingAudioWrite,
                downloadActive = false,
                downloadCancelled = false,
                metadata = metadata,
                allowLegacyPublishedAudio = !currentAudio.isPendingAudioWrite
            )
        ) {
            return null
        }
        return PlayableManagedAudioSnapshot(
            snapshot = snapshot,
            audio = currentAudio,
            metadata = metadata
        )
    }

    private fun isDownloadedMetadataIdentityCompatible(
        song: SongItem,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        val expectedRemoteIdentity = song.remoteSourceIdentityOrNull() ?: return true
        val metadataStableKey = metadata.stableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return true
        val expectedKeys = buildSet {
            add(song.stableKey())
            song.sourceStableKey
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
        if (metadataStableKey in expectedKeys) {
            return true
        }
        val metadataSong = SongItem(
            id = metadata.songId ?: song.id,
            name = metadata.name ?: song.name,
            artist = metadata.artist ?: song.artist,
            album = metadata.album ?: metadata.identityAlbum ?: song.album,
            albumId = 0L,
            durationMs = metadata.durationMs.takeIf { it > 0L } ?: song.durationMs,
            coverUrl = metadata.coverUrl,
            mediaUri = metadata.mediaUri,
            channelId = metadata.channelId,
            audioId = metadata.audioId,
            subAudioId = metadata.subAudioId,
            sourceStableKey = metadataStableKey
        )
        return metadataSong.remoteSourceIdentityOrNull() == expectedRemoteIdentity
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
        val optimisticSong = buildOptimisticDownloadedSong(
            song = song,
            storedAudio = storedAudio,
            sidecarReferences = sidecarReferences
        )
        publishOptimisticDownloadedSongs(
            context = context,
            songs = listOf(optimisticSong)
        )
        scope.launch {
            val appContext = context.applicationContext
            runCatching {
                ManagedLibraryItemRoomStore.upsert(
                    context = appContext,
                    song = song,
                    audio = storedAudio,
                    state = state
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "更新 managed_library_item 预览失败: ${error.message}")
            }
            upsertCompletedFastIndexEntry(
                context = appContext,
                song = song,
                storedAudio = storedAudio,
                state = state,
                coverPath = optimisticSong.coverPath
            )
        }
    }

    private suspend fun updateFastIndexAfterMetadataEdit(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        state: String,
        coverPath: String?
    ) {
        val stableKey = song.stableKey()
        val updatedAtMs = System.currentTimeMillis()
        val result = runCatching {
            ManagedDownloadStorage.updateExistingFastIndexEntry(
                context = context,
                stableKey = stableKey
            ) { existing ->
                existing.copy(
                    audioName = storedAudio.logicalName,
                    audioReference = storedAudio.reference,
                    metadataName = "${storedAudio.logicalName}$METADATA_SUFFIX",
                    state = state,
                    downloadTimeMs = existing.downloadTimeMs
                        ?: storedAudio.lastModifiedMs.takeIf { it > 0L },
                    updatedAtMs = updatedAtMs,
                    songId = song.id.takeIf { it > 0L } ?: existing.songId,
                    title = song.name,
                    artist = song.artist,
                    album = song.album,
                    mediaUri = song.mediaUri ?: existing.mediaUri,
                    channelId = song.channelId ?: existing.channelId,
                    audioId = song.audioId ?: existing.audioId,
                    subAudioId = song.subAudioId ?: existing.subAudioId,
                    playlistContextId = song.playlistContextId
                        ?: existing.playlistContextId,
                    durationMs = song.durationMs.takeIf { it > 0L }
                        ?: existing.durationMs,
                    coverPath = coverPath
                )
            }
        }.getOrElse { error ->
            ManagedLibraryFastIndexMutationResult.Failed(
                shard = "",
                error = error
            )
        }
        when (result) {
            is ManagedLibraryFastIndexMutationResult.EntryMissing -> {
                upsertCompletedFastIndexEntry(
                    context = context,
                    song = song,
                    storedAudio = storedAudio,
                    state = state,
                    coverPath = coverPath
                )
            }
            is ManagedLibraryFastIndexMutationResult.Failed -> {
                reportFastIndexFailure(context, song, result)
            }
            else -> Unit
        }
    }

    private suspend fun upsertCompletedFastIndexEntry(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        state: String,
        coverPath: String?
    ) {
        val result = runCatching {
            ManagedDownloadStorage.upsertCompleteFastIndexEntry(
                context = context,
                song = song,
                audio = storedAudio,
                state = state,
                coverPath = coverPath
            )
        }.getOrElse { error ->
            ManagedLibraryFastIndexMutationResult.Failed(
                shard = "",
                error = error
            )
        }
        if (result is ManagedLibraryFastIndexMutationResult.Failed) {
            reportFastIndexFailure(context, song, result)
        }
    }

    private fun reportFastIndexFailure(
        context: Context,
        song: SongItem,
        result: ManagedLibraryFastIndexMutationResult.Failed
    ) {
        NPLogger.w(
            TAG,
            "更新 managed library 快速索引失败: song=${song.name}, " +
                "error=${result.error.message}"
        )
        scheduleCatalogReconcile(context, forceRefresh = true)
    }

    private fun publishOptimisticDownloadedSongs(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        if (songs.isEmpty()) {
            return
        }

        synchronized(downloadedSongCatalogMutationLock) {
            var mergedSongs = _downloadedSongs.value
            songs.forEach { song ->
                mergedSongs = upsertDownloadedSongCatalog(mergedSongs, song)
            }
            if (mergedSongs != _downloadedSongs.value) {
                publishDownloadedSongs(context, mergedSongs, persistCatalog = true)
            }
        }
    }

    private fun buildOptimisticDownloadedSong(
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
    ): DownloadedSong {
        val remoteSource = song.remoteDownloadIdentityOrNull()
            ?: song.remoteSourceIdentityOrNull()
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
