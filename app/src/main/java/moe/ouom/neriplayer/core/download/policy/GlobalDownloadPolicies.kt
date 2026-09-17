package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.manager.runtime.POST_CORE_DOWNLOAD_OPERATION_STATES
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.clear.DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.isFormalManagedAudioReference
import moe.ouom.neriplayer.core.player.download.isReadableManagedAudioPlaybackAllowed
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType

internal fun shouldRebuildDownloadedLibrarySnapshot(recoveredArtifactCount: Int): Boolean {
    return recoveredArtifactCount > 0
}

/** 批量任务复用共享快照，避免每首歌在真正传输前串行扫描整个目录 */
internal fun shouldForceFreshStartStorageScan(isBatchOperation: Boolean): Boolean {
    return !isBatchOperation
}

internal fun shouldRetainDownloadClearVisibility(
    retainInMemoryState: Boolean,
    durableFenceActive: Boolean
): Boolean {
    return retainInMemoryState || durableFenceActive
}

/** 只有目录清理已经确认完成时，才允许删除取消 operation 的持久凭据 */
internal fun shouldPurgeCancelledDownloadOperation(
    keepCancellationOperation: Boolean,
    cleanupSucceeded: Boolean
): Boolean {
    return !keepCancellationOperation && cleanupSucceeded
}

/** 取消超时后使用有界退避，避免旧任务无限占用同一首歌的执行槽位 */
internal fun cancellationConvergenceDelayMs(attempt: Int): Long? {
    return when (attempt) {
        1 -> 0L
        2 -> 150L
        3 -> 300L
        4 -> 600L
        5 -> 1_000L
        6 -> 1_500L
        7 -> 2_000L
        else -> null
    }
}

/** 快照边界未解析时必须保留取消凭据，不能退化成按 stableKey 清理 */
internal fun shouldRetainUnresolvedCancellationSnapshot(
    snapshotBoundary: Boolean,
    snapshotResolved: Boolean,
    operationIds: Collection<String>
): Boolean {
    return snapshotBoundary && !snapshotResolved && operationIds.none { id ->
        id.trim().isNotBlank()
    }
}

/** 空快照也要经过收敛，才能释放取消代次和防重入标记 */
internal fun shouldScheduleCancellationConvergence(
    operationIds: Collection<String>,
    snapshotBoundary: Boolean
): Boolean {
    return snapshotBoundary || operationIds.any { it.trim().isNotBlank() }
}

/** 取消快照按毫秒截断，新代次必须落在边界之后，避免被延迟查询误认成旧任务 */
internal fun nextDownloadOperationCreatedAtMs(
    requestedAtMs: Long,
    cancellationCutoffs: Collection<Long>
): Long {
    val normalizedRequestedAtMs = requestedAtMs.coerceAtLeast(0L)
    val latestCutoff = cancellationCutoffs.maxOrNull()?.coerceAtLeast(0L)
        ?: return normalizedRequestedAtMs
    val afterCutoff = if (latestCutoff == Long.MAX_VALUE) {
        Long.MAX_VALUE
    } else {
        latestCutoff + 1L
    }
    return maxOf(normalizedRequestedAtMs, afterCutoff)
}

/** 完整目录扫描后只显示当前残留数量，避免沿用上一轮任务总数 */
internal fun resolveDownloadClearRetainedTotalItemCount(
    currentTotalItemCount: Int?,
    artifactTotalItemCount: Int,
    scanComplete: Boolean
): Int {
    val normalizedArtifactCount = artifactTotalItemCount.coerceAtLeast(0)
    if (scanComplete) return normalizedArtifactCount
    return maxOf(
        currentTotalItemCount?.coerceAtLeast(0) ?: 0,
        normalizedArtifactCount
    )
}

/** 只有未完成扫描或仍有未受保护残留时，清理失败才阻塞清空栅栏 */
internal fun shouldBlockDownloadClearForPendingArtifacts(
    scanComplete: Boolean,
    blockingArtifactCount: Int
): Boolean {
    if (!scanComplete) return true
    if (blockingArtifactCount > 0) return true
    return false
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

internal fun shouldEvictMissingDownloadedSongCatalogEntry(
    sawMissing: Boolean,
    sawUncertain: Boolean,
    hasActiveDownload: Boolean
): Boolean {
    return sawMissing && !sawUncertain && !hasActiveDownload
}

/** pending 收敛结果只在完整扫描确认后才允许迁移继续 */
internal data class PendingDownloadRecoverySummary(
    val leaseAcquired: Boolean = false,
    val initialScanComplete: Boolean = false,
    val pendingScanComplete: Boolean = false,
    val remainingArtifactCount: Int = 0,
    val discoveredAudioCount: Int = 0,
    val attemptedAudioCount: Int = 0,
    val failedAudioCount: Int = 0
) {
    val isConverged: Boolean
        get() = leaseAcquired &&
            initialScanComplete &&
            pendingScanComplete &&
            failedAudioCount == 0 &&
            remainingArtifactCount == 0
}

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

/** core 音频已经提交但收尾失败时，任务必须保留在可恢复的活动态 */
internal fun resolvePostCoreEnrichmentTaskStatus(
    coreAudioCommitted: Boolean
): DownloadStatus {
    return if (coreAudioCommitted) {
        DownloadStatus.QUEUED
    } else {
        DownloadStatus.FAILED
    }
}

/** 只有可播放 core 且没有需要用户处理的容器问题时才自动重试收尾 */
internal fun shouldSchedulePostCoreEnrichmentRetry(
    coreAudioCommitted: Boolean,
    operationState: String?,
    metadataActionRequired: Boolean,
    userStopped: Boolean,
    allowInFlightState: Boolean = false,
    songCancelled: Boolean = false
): Boolean {
    return coreAudioCommitted &&
        (
            operationState == "DEGRADED_COMPLETE" ||
                allowInFlightState && operationState in setOf(
                    "CORE_COMMITTED",
                    "ASSETS_ENRICHING",
                    "COMPLETED"
                )
            ) &&
        !metadataActionRequired &&
        !userStopped &&
        !songCancelled
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
    onFailure: (Throwable) -> Unit,
    maxAttempts: Int = 1,
    retryDelayMs: Long = 0L
): Boolean {
    val attemptLimit = maxAttempts.coerceAtLeast(1)
    repeat(attemptLimit) { attemptIndex ->
        try {
            block()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            if (attemptIndex + 1 < attemptLimit && retryDelayMs > 0L) {
                delay(retryDelayMs)
            }
        }
    }
    return false
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

internal data class PendingWorkingProgressRecord(
    val operationId: String?,
    val stableKey: String,
    val bytesWritten: Long
)

internal class PendingWorkingProgressSnapshot internal constructor(
    private val bytesByOperationAndSongKey: Map<Pair<String, String>, Long>,
    private val bytesBySongKey: Map<String, Long>
) {
    fun workingFileBytes(operationId: String?, stableKey: String): Long {
        val normalizedSongKey = stableKey.trim().takeIf(String::isNotEmpty) ?: return 0L
        val operationBytes = operationId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { normalizedOperationId ->
                bytesByOperationAndSongKey[normalizedOperationId to normalizedSongKey]
            }
        return operationBytes ?: bytesBySongKey[normalizedSongKey] ?: 0L
    }

    companion object {
        val Empty = PendingWorkingProgressSnapshot(
            bytesByOperationAndSongKey = emptyMap(),
            bytesBySongKey = emptyMap()
        )
    }
}

internal fun buildPendingWorkingProgressSnapshot(
    records: Iterable<PendingWorkingProgressRecord>
): PendingWorkingProgressSnapshot {
    val bytesByOperationAndSongKey = mutableMapOf<Pair<String, String>, Long>()
    val bytesBySongKey = mutableMapOf<String, Long>()
    records.forEach { record ->
        val normalizedSongKey = record.stableKey.trim().takeIf(String::isNotEmpty)
            ?: return@forEach
        val bytesWritten = record.bytesWritten.takeIf { it > 0L } ?: return@forEach
        bytesBySongKey.merge(normalizedSongKey, bytesWritten, ::maxOf)
        record.operationId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { normalizedOperationId ->
                bytesByOperationAndSongKey.merge(
                    normalizedOperationId to normalizedSongKey,
                    bytesWritten,
                    ::maxOf
                )
            }
    }
    return PendingWorkingProgressSnapshot(
        bytesByOperationAndSongKey = bytesByOperationAndSongKey,
        bytesBySongKey = bytesBySongKey
    )
}

internal fun resolveRecoveredDownloadProgress(
    workingFileBytes: Long,
    checkpointTotalBytes: Long?,
    checkpointBytesWritten: Long? = null
): RecoveredDownloadProgress? {
    // 文件和检查点都可见时，以较小值作为安全前缀，避免把未 fsync 的尾部当成已完成
    // 文件暂时不可见时才回退到检查点，这样重启不会因为一次迟到写入而跳过数据
    val fileBytes = workingFileBytes.coerceAtLeast(0L)
    val checkpointBytes = checkpointBytesWritten?.coerceAtLeast(0L)
    val durableBytes = when {
        fileBytes > 0L && checkpointBytes != null -> minOf(fileBytes, checkpointBytes)
        fileBytes > 0L -> fileBytes
        checkpointBytes != null -> checkpointBytes
        else -> 0L
    }
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

internal data class RecoveredDownloadTaskPresentation(
    val status: DownloadStatus,
    val stage: AudioDownloadManager.DownloadStage
)

internal fun recoveredDownloadTaskPresentation(
    operationState: String,
    stopRequestedByUser: Boolean,
    batchStateBits: Int?,
    nextRetryAtMs: Long? = null,
    lastErrorCode: String? = null,
    nowMs: Long = System.currentTimeMillis()
): RecoveredDownloadTaskPresentation? {
    // STOPPED 由显式恢复列表展示并等待用户操作，不能伪装成普通排队卡片
    if (stopRequestedByUser || operationState == "STOPPED") return null
    if (operationState == "CANCELLED" || operationState == "CANCEL_REQUESTED") return null
    if (operationState == "INVALID" || operationState == "METADATA_ACTION_REQUIRED") {
        return RecoveredDownloadTaskPresentation(
            status = DownloadStatus.FAILED,
            stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
        )
    }
    val normalizedErrorCode = lastErrorCode?.trim()?.takeIf(String::isNotBlank)
    val waitsForNetwork =
        (batchStateBits ?: 0) and DownloadBatchState.NETWORK_WAIT != 0 ||
            normalizedErrorCode == "NETWORK_POLICY_WAITING" ||
            normalizedErrorCode == GlobalDownloadManager.DOWNLOAD_NETWORK_UNAVAILABLE_ERROR_CODE
    if (waitsForNetwork) {
        return RecoveredDownloadTaskPresentation(
            status = DownloadStatus.WAITING_NETWORK,
            stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
        )
    }
    if (operationState in POST_CORE_DOWNLOAD_OPERATION_STATES) {
        // 重启后只让共享 Worker 选中的少量任务进入可见收尾态，其余凭据留在 Room
        return RecoveredDownloadTaskPresentation(
            status = DownloadStatus.QUEUED,
            stage = if (nextRetryAtMs?.let { it > nowMs } == true) {
                AudioDownloadManager.DownloadStage.WAITING_RETRY
            } else {
                AudioDownloadManager.DownloadStage.WAITING_HOST
            }
        )
    }
    return when {
        operationState == "WAITING_STORAGE_MUTATION" ||
            normalizedErrorCode == DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR ->
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP
            )
        nextRetryAtMs?.let { it > nowMs } == true ->
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
            )
        else -> RecoveredDownloadTaskPresentation(
            status = DownloadStatus.QUEUED,
            stage = AudioDownloadManager.DownloadStage.WAITING_HOST
        )
    }
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

/** Provider 清理未结束时复用同一工作，避免多个删除协程并发操作同一目录 */
internal class DownloadClearProviderCleanupCoordinator<K, T : Any>(
    private val scope: CoroutineScope
) {
    internal data class Handle<K, T : Any>(
        val key: K,
        val operation: Deferred<T>
    )

    private val lock = Any()
    private var activeCleanup: Handle<K, T>? = null

    fun activeOrNull(): Handle<K, T>? = synchronized(lock) {
        activeCleanup?.takeIf { handle -> handle.operation.isActive }
    }

    fun getOrStart(
        key: K,
        block: suspend () -> T
    ): Handle<K, T> = synchronized(lock) {
        activeCleanup?.takeIf { handle -> handle.operation.isActive }?.let { handle ->
            return@synchronized handle
        }
        val operation = scope.async { block() }
        Handle(key = key, operation = operation).also { handle ->
            activeCleanup = handle
            operation.invokeOnCompletion {
                synchronized(lock) {
                    if (activeCleanup === handle) {
                        activeCleanup = null
                    }
                }
            }
        }
    }
}

/** 等待超时只解除调用方，Provider 工作及其目录 lease 仍由原协程持有 */
internal suspend fun <T : Any> awaitDownloadClearProviderCleanup(
    cleanup: Deferred<T>,
    timeoutMs: Long
): T? {
    require(timeoutMs > 0L) { "timeoutMs must be positive" }
    return withTimeoutOrNull(timeoutMs) { cleanup.await() }
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

internal fun shouldPersistDownloadClearProgress(
    completedItemCount: Int,
    totalItemCount: Int,
    lastPersistedItemCount: Int,
    nowMs: Long,
    lastPersistedAtMs: Long,
    minIntervalMs: Long,
    batchSize: Int
): Boolean {
    val normalizedTotal = totalItemCount.coerceAtLeast(0)
    val normalizedCompleted = completedItemCount.coerceAtLeast(0).let { completed ->
        if (normalizedTotal > 0) {
            completed.coerceAtMost(normalizedTotal)
        } else {
            completed
        }
    }
    if (lastPersistedItemCount < 0) return true
    if (normalizedTotal in 1..normalizedCompleted) return true
    if (normalizedCompleted - lastPersistedItemCount >= batchSize.coerceAtLeast(1)) {
        return true
    }
    return nowMs - lastPersistedAtMs >= minIntervalMs.coerceAtLeast(0L)
}

internal fun shouldDeleteEntireDownloadedLibrary(
    explicitlyRequested: Boolean,
    pendingDeleteIntentExists: Boolean
): Boolean = explicitlyRequested || pendingDeleteIntentExists
