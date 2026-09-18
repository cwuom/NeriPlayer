package moe.ouom.neriplayer.core.download.model

import kotlin.math.floor
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.database.entity.DOWNLOAD_BATCH_POST_CORE_PENDING_FRACTION_MILLI
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.format.formatFileSize

data class DownloadTask(
    val song: SongItem,
    val progress: AudioDownloadManager.DownloadProgress?,
    val status: DownloadStatus,
    val attemptId: Long = 0L
)

/** immutable membership for one user initiated batch, kept apart from transient task cards */
internal data class BatchDownloadPresentationState(
    val id: Long,
    val memberAttemptIds: Map<String, Long?>,
    val memberOperationIds: Map<String, String> = emptyMap(),
    val terminalStates: Map<String, BatchDownloadTerminalState> = emptyMap(),
    val maximumObservedFractions: Map<String, Float> = emptyMap(),
    /** 当前目录已经确认完成的成员, 等待真实传输时再清除 */
    val initiallyCompletedSongKeys: Set<String> = emptySet(),
    val batchId: String? = null,
    val batchGeneration: Long? = null
)

internal enum class BatchDownloadTerminalState {
    COMPLETED,
    FAILED,
    CANCELLED
}

/** progress shown for a batch, where every selected song has equal weight */
internal data class BatchDownloadOverallProgress(
    val totalSongs: Int,
    val completedSongs: Int,
    val percentage: Int,
    val fraction: Float,
    val activeSongCount: Int,
    val hasPendingSongs: Boolean
)

/** terminal incomplete batches are represented by their failed task rows instead of stale progress */
internal fun batchDownloadProgressForDisplay(
    progress: BatchDownloadOverallProgress?
): BatchDownloadOverallProgress? {
    return progress?.takeIf { current ->
        current.hasPendingSongs || current.completedSongs == current.totalSongs
    }
}

private const val INCOMPLETE_BATCH_PROGRESS_CEILING = 0.99f
private const val DOWNLOAD_TRANSFER_PROGRESS_SHARE = 0.90f
private const val DOWNLOAD_VERIFYING_PROGRESS_FRACTION = 0.92f
private const val DOWNLOAD_CORE_COMMIT_PROGRESS_FRACTION = 0.94f
private const val DOWNLOAD_POST_CORE_PROGRESS_FRACTION =
    DOWNLOAD_BATCH_POST_CORE_PENDING_FRACTION_MILLI / 1_000f
private const val DOWNLOAD_FINALIZING_PROGRESS_FRACTION = 0.99f

internal fun aggregateBatchDownloadProgress(
    presentation: BatchDownloadPresentationState,
    tasks: List<DownloadTask>
): BatchDownloadOverallProgress? {
    if (presentation.memberAttemptIds.isEmpty()) {
        return null
    }

    val tasksBySongKey = tasks.associateBy { task -> task.song.stableKey() }
    var completedSongs = 0
    var completedFraction = 0f
    var activeSongCount = 0
    var hasPendingSongs = false

    presentation.memberAttemptIds.forEach { (songKey, expectedAttemptId) ->
        val retainedFraction = presentation.maximumObservedFractions[songKey]
            ?.coerceIn(0f, 1f)
            ?: 0f
        when (presentation.terminalStates[songKey]) {
            BatchDownloadTerminalState.COMPLETED -> {
                completedSongs++
                completedFraction += 1f
            }

            BatchDownloadTerminalState.FAILED,
            BatchDownloadTerminalState.CANCELLED -> {
                completedFraction += retainedFraction
            }

            null -> {
                val task = tasksBySongKey[songKey]
                    ?.takeIf { candidate ->
                        if (expectedAttemptId == null) {
                            candidate.status == DownloadStatus.QUEUED ||
                                candidate.status == DownloadStatus.DOWNLOADING ||
                                candidate.status == DownloadStatus.WAITING_NETWORK
                        } else {
                            candidate.attemptId == expectedAttemptId
                        }
                    }
                when (task?.status) {
                    DownloadStatus.COMPLETED -> {
                        // 批量完成只认 terminalStates。core 音频先完成时任务可能已经是
                        // COMPLETED，但最终发布还没有确认，不能提前增加完成歌曲数
                        completedFraction += retainedFraction
                        hasPendingSongs = true
                    }

                    DownloadStatus.DOWNLOADING -> {
                        activeSongCount++
                        completedFraction += maxOf(
                            retainedFraction,
                            task.progress?.let(::downloadProgressFraction) ?: 0f
                        )
                        hasPendingSongs = true
                    }

                    DownloadStatus.WAITING_NETWORK -> {
                        completedFraction += maxOf(
                            retainedFraction,
                            task.progress?.let(::downloadProgressFraction) ?: 0f
                        )
                        hasPendingSongs = true
                    }

                    DownloadStatus.QUEUED,
                    null -> {
                        completedFraction += retainedFraction
                        hasPendingSongs = true
                    }

                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELLED -> completedFraction += retainedFraction
                }
            }
        }
    }

    val totalSongs = presentation.memberAttemptIds.size
    val rawFraction = (completedFraction / totalSongs.toFloat()).coerceIn(0f, 1f)
    val allSongsCompleted = completedSongs == totalSongs
    val fraction = if (allSongsCompleted) {
        1f
    } else {
        rawFraction.coerceAtMost(INCOMPLETE_BATCH_PROGRESS_CEILING)
    }
    return BatchDownloadOverallProgress(
        totalSongs = totalSongs,
        completedSongs = completedSongs,
        percentage = if (allSongsCompleted) 100 else floor(fraction * 100f).toInt(),
        fraction = fraction,
        activeSongCount = activeSongCount,
        hasPendingSongs = hasPendingSongs
    )
}

/** combines overlapping user batch selections without double-counting one song */
internal fun aggregateBatchDownloadProgress(
    presentations: Collection<BatchDownloadPresentationState>,
    tasks: List<DownloadTask>
): BatchDownloadOverallProgress? {
    val mergedPresentation = mergeBatchDownloadPresentations(presentations, tasks)
        ?: return null
    return aggregateBatchDownloadProgress(mergedPresentation, tasks)
}

/**
 * chooses one current membership for each stable key before deriving overall progress
 *
 * A second batch can select a song already owned by an earlier batch. The durable
 * operation remains singular, while the presentation keeps both user selections until
 * they settle. Prefer the current non-terminal membership so a newer retry never
 * inherits a completed state from an older request.
 */
internal fun mergeBatchDownloadPresentations(
    presentations: Collection<BatchDownloadPresentationState>,
    tasks: List<DownloadTask>
): BatchDownloadPresentationState? {
    val membersBySongKey = linkedMapOf<String, MutableList<BatchPresentationMember>>()
    presentations
        .asSequence()
        .filter { presentation -> presentation.memberAttemptIds.isNotEmpty() }
        .sortedBy(BatchDownloadPresentationState::id)
        .forEach { presentation ->
            presentation.memberAttemptIds.forEach { (songKey, attemptId) ->
                if (songKey.isBlank()) {
                    return@forEach
                }
                membersBySongKey.getOrPut(songKey, ::mutableListOf) +=
                    BatchPresentationMember(
                        presentationId = presentation.id,
                        attemptId = attemptId,
                        terminalState = presentation.terminalStates[songKey],
                        operationId = presentation.memberOperationIds[songKey],
                        maximumObservedFraction =
                            presentation.maximumObservedFractions[songKey] ?: 0f,
                        initiallyCompleted = songKey in presentation.initiallyCompletedSongKeys
                    )
            }
        }
    if (membersBySongKey.isEmpty()) {
        return null
    }

    val tasksBySongKey = tasks.associateBy { task -> task.song.stableKey() }
    val memberAttemptIds = linkedMapOf<String, Long?>()
    val memberOperationIds = linkedMapOf<String, String>()
    val terminalStates = linkedMapOf<String, BatchDownloadTerminalState>()
    val maximumObservedFractions = linkedMapOf<String, Float>()
    val initiallyCompletedSongKeys = linkedSetOf<String>()
    membersBySongKey.forEach { (songKey, members) ->
        val selected = selectBatchPresentationMember(
            members = members,
            task = tasksBySongKey[songKey]
        )
        memberAttemptIds[songKey] = selected.attemptId
        selected.operationId?.let { operationId ->
            memberOperationIds[songKey] = operationId
        }
        selected.terminalState?.let { terminalState ->
            terminalStates[songKey] = terminalState
        }
        if (selected.initiallyCompleted) {
            initiallyCompletedSongKeys += songKey
        }
        mergedBatchPresentationMaximumObservedFraction(selected, members)
            .coerceIn(0f, 1f)
            .takeIf { fraction -> fraction > 0f }
            ?.let { fraction -> maximumObservedFractions[songKey] = fraction }
    }
    return BatchDownloadPresentationState(
        id = presentations.maxOf(BatchDownloadPresentationState::id),
        memberAttemptIds = memberAttemptIds,
        memberOperationIds = memberOperationIds,
        terminalStates = terminalStates,
        maximumObservedFractions = maximumObservedFractions,
        initiallyCompletedSongKeys = initiallyCompletedSongKeys,
        batchId = presentations
            .maxByOrNull(BatchDownloadPresentationState::id)
            ?.batchId,
        batchGeneration = presentations
            .maxByOrNull(BatchDownloadPresentationState::id)
            ?.batchGeneration
    )
}

private data class BatchPresentationMember(
    val presentationId: Long,
    val attemptId: Long?,
    val operationId: String?,
    val terminalState: BatchDownloadTerminalState?,
    val maximumObservedFraction: Float,
    val initiallyCompleted: Boolean
)

private fun selectBatchPresentationMember(
    members: List<BatchPresentationMember>,
    task: DownloadTask?
): BatchPresentationMember {
    val pendingMembers = members.filter { member -> member.terminalState == null }
    if (pendingMembers.isEmpty()) {
        return requireNotNull(members.maxByOrNull(BatchPresentationMember::presentationId))
    }
    val currentAttemptId = task
        ?.takeIf { candidate ->
            candidate.status == DownloadStatus.QUEUED ||
                candidate.status == DownloadStatus.DOWNLOADING ||
                candidate.status == DownloadStatus.WAITING_NETWORK
        }
        ?.attemptId
    if (currentAttemptId != null) {
        pendingMembers.lastOrNull { member -> member.attemptId == currentAttemptId }
            ?.let { member -> return member }
    }
    return requireNotNull(pendingMembers.maxByOrNull(BatchPresentationMember::presentationId))
}

private fun mergedBatchPresentationMaximumObservedFraction(
    selected: BatchPresentationMember,
    members: List<BatchPresentationMember>
): Float {
    val matchingMembers = if (selected.terminalState == null) {
        members.filter { member ->
            member.terminalState == null && member.attemptId == selected.attemptId
        }
    } else {
        listOf(selected)
    }
    return matchingMembers.maxOfOrNull(BatchPresentationMember::maximumObservedFraction)
        ?: selected.maximumObservedFraction
}

internal fun downloadProgressFraction(progress: AudioDownloadManager.DownloadProgress): Float {
    val transferFraction = if (progress.totalBytes > 0L) {
        (progress.bytesRead.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val weightedTransferFraction = transferFraction * DOWNLOAD_TRANSFER_PROGRESS_SHARE
    return when (progress.stage) {
        AudioDownloadManager.DownloadStage.VERIFYING_AUDIO ->
            maxOf(weightedTransferFraction, DOWNLOAD_VERIFYING_PROGRESS_FRACTION)
        AudioDownloadManager.DownloadStage.COMMITTING_CORE ->
            DOWNLOAD_CORE_COMMIT_PROGRESS_FRACTION
        AudioDownloadManager.DownloadStage.ASSETS_ENRICHING ->
            DOWNLOAD_POST_CORE_PROGRESS_FRACTION
        AudioDownloadManager.DownloadStage.FINALIZING ->
            DOWNLOAD_FINALIZING_PROGRESS_FRACTION
        AudioDownloadManager.DownloadStage.WAITING_HOST,
        AudioDownloadManager.DownloadStage.WAITING_RETRY -> if (transferFraction >= 1f) {
            DOWNLOAD_POST_CORE_PROGRESS_FRACTION
        } else {
            weightedTransferFraction
        }
        AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP,
        AudioDownloadManager.DownloadStage.RESOLVING_SOURCE,
        AudioDownloadManager.DownloadStage.PREPARING_STORAGE,
        AudioDownloadManager.DownloadStage.TRANSFERRING -> weightedTransferFraction
    }
}

/** renders only values that are known so an unknown content length stays honest */
internal fun formatDownloadTransferProgress(
    progress: AudioDownloadManager.DownloadProgress,
    showSpeed: Boolean = true
): String {
    val totalBytes = progress.totalBytes.takeIf { total -> total > 0L }
    val downloadedBytes = progress.bytesRead
        .coerceAtLeast(0L)
        .let { bytes -> totalBytes?.let(bytes::coerceAtMost) ?: bytes }
    val percentageText = totalBytes?.let { total ->
        "${((downloadedBytes.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)}%"
    }
    val transferText = totalBytes?.let { total ->
        "${formatFileSize(downloadedBytes)} / ${formatFileSize(total)}"
    } ?: formatFileSize(downloadedBytes)
    val speedText = progress.speedBytesPerSec
        .takeIf { speed ->
            showSpeed &&
                progress.stage == AudioDownloadManager.DownloadStage.TRANSFERRING &&
                speed > 0L
        }
        ?.let { speed -> "${formatFileSize(speed)}/s" }
    return listOfNotNull(percentageText, transferText, speedText)
        .joinToString(" · ")
}

internal fun mergeDownloadProgress(
    current: AudioDownloadManager.DownloadProgress?,
    incoming: AudioDownloadManager.DownloadProgress
): AudioDownloadManager.DownloadProgress {
    if (current == null || current.attemptId != incoming.attemptId) {
        return incoming
    }
    if (current.operationId == incoming.operationId &&
        current.publicationSequence > incoming.publicationSequence
    ) {
        return current
    }
    val currentGeneration = current.transferGeneration
    val incomingGeneration = incoming.transferGeneration
    if (
        currentGeneration != null &&
            incomingGeneration != null &&
            incomingGeneration < currentGeneration
    ) {
        return current
    }
    val finalizedBytesFloor = if (
        current.stage == AudioDownloadManager.DownloadStage.FINALIZING &&
            current.totalBytes > 0L
    ) {
        current.totalBytes
    } else {
        0L
    }
    return incoming.copy(
        bytesRead = maxOf(
            current.bytesRead.coerceAtLeast(0L),
            incoming.bytesRead.coerceAtLeast(0L),
            finalizedBytesFloor
        ),
        totalBytes = mergeKnownDownloadTotalBytes(
            current.totalBytes,
            incoming.totalBytes
        ),
        durableBytesRead = mergeDurableDownloadBytes(
            current = current.durableBytesRead,
            incoming = incoming.durableBytesRead,
            visibleBytes = maxOf(
                current.bytesRead.coerceAtLeast(0L),
                incoming.bytesRead.coerceAtLeast(0L),
                finalizedBytesFloor
            )
        ),
        transferGeneration = incomingGeneration ?: currentGeneration
    )
}

private fun mergeDurableDownloadBytes(
    current: Long?,
    incoming: Long?,
    visibleBytes: Long
): Long? {
    val merged = when {
        current == null -> incoming
        incoming == null -> current
        else -> maxOf(current, incoming)
    } ?: return null
    return merged.coerceAtLeast(0L).coerceAtMost(visibleBytes)
}

internal fun mergeDownloadTaskProgress(
    current: AudioDownloadManager.DownloadProgress?,
    incoming: AudioDownloadManager.DownloadProgress
): AudioDownloadManager.DownloadProgress {
    return mergeDownloadProgress(current, incoming)
}

internal fun mergeKnownDownloadTotalBytes(
    currentTotalBytes: Long,
    incomingTotalBytes: Long
): Long {
    return when {
        currentTotalBytes > 0L && incomingTotalBytes > 0L -> {
            maxOf(currentTotalBytes, incomingTotalBytes)
        }
        currentTotalBytes > 0L -> currentTotalBytes
        incomingTotalBytes > 0L -> incomingTotalBytes
        else -> 0L
    }
}

internal fun resumeBatchDownloadPresentationForRetry(
    presentation: BatchDownloadPresentationState,
    songKey: String,
    attemptId: Long
): BatchDownloadPresentationState {
    if (
        presentation.memberAttemptIds[songKey] != attemptId ||
            presentation.terminalStates[songKey] != BatchDownloadTerminalState.FAILED
    ) {
        return presentation
    }
    return presentation.copy(terminalStates = presentation.terminalStates - songKey)
}

/** durable candidates that must wait for a user action after an OS stop */
internal data class ExplicitDownloadResumeCandidate(
    val operationId: String,
    val song: SongItem,
    val queueOrder: Int
)

internal fun visibleExplicitResumeCandidates(
    candidates: Collection<ExplicitDownloadResumeCandidate>,
    activeSongKeys: Set<String>
): List<ExplicitDownloadResumeCandidate> {
    return candidates
        .asSequence()
        .filter { candidate -> candidate.song.stableKey() !in activeSongKeys }
        .distinctBy { candidate -> candidate.song.stableKey() }
        .sortedWith(
            compareBy<ExplicitDownloadResumeCandidate> { it.queueOrder }
                .thenBy { it.operationId }
        )
        .toList()
}

data class DownloadTaskSummary(
    val pendingTaskCount: Int = 0,
    val failedTaskCount: Int = 0,
    val queuedTaskCount: Int = 0,
    val hasActiveTasks: Boolean = false,
    val hasActiveOperations: Boolean = false
) {
    val hasPendingTasks: Boolean
        get() = pendingTaskCount > 0

    val hasFailedTasks: Boolean
        get() = failedTaskCount > 0

    /** admission, recovery and failed-task retry must remain reachable from the manager entry */
    val hasDownloadManagerEntry: Boolean
        get() = hasPendingTasks || hasActiveOperations || hasFailedTasks
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    WAITING_NETWORK,
    COMPLETED,
    FAILED,
    CANCELLED
}

internal data class QueuedDownloadRequest(
    val song: SongItem,
    val attemptId: Long,
    val operationId: String
)

internal fun selectBatchRequestsForEarlyHandoff(
    pendingRequests: List<QueuedDownloadRequest>,
    scheduledSongKeys: Set<String>,
    maximumHandoffs: Int
): List<QueuedDownloadRequest> {
    val remainingHandoffs = maximumHandoffs - scheduledSongKeys.size
    if (remainingHandoffs <= 0) {
        return emptyList()
    }
    return pendingRequests
        .asSequence()
        .filter { request -> request.song.stableKey() !in scheduledSongKeys }
        .take(remainingHandoffs)
        .toList()
}

internal enum class BatchOperationScheduleAction {
    SCHEDULE,
    HANDED_OFF,
    RELEASE,
    SETTLED,
    INVALID
}

internal fun resolveBatchOperationScheduleAction(
    operationState: String?,
    requestMatchesSong: Boolean,
    isExecuting: Boolean = true
): BatchOperationScheduleAction {
    if (operationState == null || !requestMatchesSong) {
        return BatchOperationScheduleAction.INVALID
    }
    if (operationState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES) {
        // Room 状态可能在进程死亡或宿主异常退出后滞留为 RUNNING。
        // 只有当前进程仍持有执行标记时才能把它当作已交接，否则必须重新入宿主。
        return if (isExecuting) {
            BatchOperationScheduleAction.HANDED_OFF
        } else {
            BatchOperationScheduleAction.SCHEDULE
        }
    }
    if (operationState in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES) {
        return BatchOperationScheduleAction.SCHEDULE
    }
    if (operationState in setOf("CANCEL_REQUESTED", "CANCELLED", "STOPPED")) {
        return BatchOperationScheduleAction.RELEASE
    }
    return BatchOperationScheduleAction.SETTLED
}

internal fun shouldPreserveBatchPreparationForHandedOffOperation(
    operationState: String?,
    requestMatchesSong: Boolean,
    attemptId: Long?,
    requestGenerationCurrent: Boolean,
    isExecuting: Boolean = true
): Boolean {
    return attemptId != null &&
        requestGenerationCurrent &&
        resolveBatchOperationScheduleAction(
            operationState = operationState,
            requestMatchesSong = requestMatchesSong,
            isExecuting = isExecuting
        ) == BatchOperationScheduleAction.HANDED_OFF
}

internal fun canScheduleRecoveredDownloadOperation(operationState: String?): Boolean {
    return operationState in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES ||
        operationState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES
}

/** avoids handing the same durable operation to a second live execution host */
internal fun shouldRehandoffRecoveredDownloadOperation(
    operationState: String?,
    requestMatchesSong: Boolean,
    isExecuting: Boolean,
    isStoppedByUser: Boolean
): Boolean {
    return requestMatchesSong &&
        !isExecuting &&
        !isStoppedByUser &&
        canScheduleRecoveredDownloadOperation(operationState)
}

internal fun selectBatchDownloadCandidates(
    songs: Collection<SongItem>,
    inFlightSongKeys: Set<String>
): List<SongItem> {
    return songs.distinctBy(SongItem::stableKey)
        .filterNot { song -> song.stableKey() in inFlightSongKeys }
}

internal fun resolveDownloadPreserveStaging(
    persistedPreserveStaging: Boolean,
    preserveRequested: Boolean
): Boolean = persistedPreserveStaging || preserveRequested

internal fun selectBatchArtifactLeaseForCancellation(
    handedOff: Boolean,
    capturedLeaseId: String?
): String? {
    return capturedLeaseId?.takeUnless { handedOff }
}

internal fun isDownloadTaskFinalizing(task: DownloadTask?): Boolean {
    return task?.status == DownloadStatus.DOWNLOADING &&
        task.progress?.stage == AudioDownloadManager.DownloadStage.FINALIZING
}

internal fun isDownloadTaskCancellable(task: DownloadTask?): Boolean {
    return task?.status == DownloadStatus.QUEUED ||
        task?.status == DownloadStatus.DOWNLOADING ||
        task?.status == DownloadStatus.WAITING_NETWORK
}

internal fun isDownloadTaskCancellationCandidate(task: DownloadTask): Boolean {
    return task.status != DownloadStatus.COMPLETED &&
        task.status != DownloadStatus.CANCELLED
}

internal fun visibleDownloadProgressTasks(tasks: List<DownloadTask>): List<DownloadTask> {
    return tasks
        .asSequence()
        .filter { task ->
            (
                task.status == DownloadStatus.QUEUED ||
                    task.status == DownloadStatus.DOWNLOADING ||
                    task.status == DownloadStatus.WAITING_NETWORK
                ) && hasDownloadTaskStartedWork(task)
        }
        // task store 保留入队次序，网络等待和重试不能让卡片前后跳动
        .toList()
}

/** terminal failures stay visible for manual retry without becoming pending work */
internal fun visibleFailedDownloadTasks(tasks: List<DownloadTask>): List<DownloadTask> {
    return tasks.filter { task -> task.status == DownloadStatus.FAILED }
}

/**
 * true after the host has started source, storage, transfer, or post-transfer work
 * so host waits cannot hide an operation that is already being processed
 */
internal fun hasDownloadTaskStartedWork(task: DownloadTask): Boolean {
    val stage = currentTaskProgress(task)?.stage ?: return false
    return stage == AudioDownloadManager.DownloadStage.RESOLVING_SOURCE ||
        stage == AudioDownloadManager.DownloadStage.PREPARING_STORAGE ||
        stage == AudioDownloadManager.DownloadStage.TRANSFERRING ||
        stage == AudioDownloadManager.DownloadStage.VERIFYING_AUDIO ||
        stage == AudioDownloadManager.DownloadStage.COMMITTING_CORE ||
        stage == AudioDownloadManager.DownloadStage.ASSETS_ENRICHING ||
        stage == AudioDownloadManager.DownloadStage.WAITING_RETRY ||
        stage == AudioDownloadManager.DownloadStage.FINALIZING
}

private fun currentTaskProgress(
    task: DownloadTask
): AudioDownloadManager.DownloadProgress? {
    return task.progress?.takeIf { progress ->
        progress.attemptId == null || progress.attemptId == task.attemptId
    }
}

internal fun activeDownloadTaskWithProgress(tasks: List<DownloadTask>): DownloadTask? {
    return tasks.firstOrNull { task ->
        task.status == DownloadStatus.DOWNLOADING &&
            task.progress?.let { progress ->
                progress.attemptId == null || progress.attemptId == task.attemptId
            } == true
    }
}

internal fun shouldHideRemoteDownloadAction(
    hasLocalDownload: Boolean,
    task: DownloadTask?
): Boolean {
    if (!hasLocalDownload) {
        return false
    }
    return task == null || task.status == DownloadStatus.COMPLETED
}

fun buildDownloadTaskSummary(tasks: List<DownloadTask>): DownloadTaskSummary {
    var pendingTaskCount = 0
    var failedTaskCount = 0
    var queuedTaskCount = 0
    var hasActiveTasks = false
    var hasActiveOperations = false

    tasks.forEach { task ->
        when (task.status) {
            DownloadStatus.QUEUED -> {
                pendingTaskCount++
                queuedTaskCount++
                hasActiveOperations = true
            }

            DownloadStatus.DOWNLOADING -> {
                pendingTaskCount++
                hasActiveTasks = true
                hasActiveOperations = true
            }

            DownloadStatus.WAITING_NETWORK -> pendingTaskCount++
            DownloadStatus.FAILED -> failedTaskCount++
            DownloadStatus.COMPLETED,
            DownloadStatus.CANCELLED -> Unit
        }
    }

    return DownloadTaskSummary(
        pendingTaskCount = pendingTaskCount,
        failedTaskCount = failedTaskCount,
        queuedTaskCount = queuedTaskCount,
        hasActiveTasks = hasActiveTasks,
        hasActiveOperations = hasActiveOperations
    )
}

internal fun stabilizeDownloadTaskSummary(
    taskSummary: DownloadTaskSummary,
    isSingleDownloading: Boolean,
    hasActiveBatchJobs: Boolean
): DownloadTaskSummary {
    if (!isSingleDownloading && !hasActiveBatchJobs) {
        return taskSummary
    }
    if (taskSummary.hasPendingTasks) {
        return taskSummary.copy(
            hasActiveTasks = taskSummary.hasActiveTasks || isSingleDownloading,
            hasActiveOperations = true
        )
    }
    return taskSummary.copy(
        pendingTaskCount = 0,
        queuedTaskCount = 0,
        hasActiveTasks = isSingleDownloading,
        hasActiveOperations = true
    )
}

fun countPendingDownloadTasks(tasks: List<DownloadTask>): Int {
    return tasks.count { task ->
        task.status == DownloadStatus.QUEUED ||
            task.status == DownloadStatus.DOWNLOADING ||
            task.status == DownloadStatus.WAITING_NETWORK
    }
}

fun countFailedDownloadTasks(tasks: List<DownloadTask>): Int {
    return tasks.count { task -> task.status == DownloadStatus.FAILED }
}

internal fun shouldApplyTaskMutation(
    task: DownloadTask?,
    expectedAttemptId: Long?
): Boolean {
    if (task == null) {
        return false
    }
    return expectedAttemptId == null || task.attemptId == expectedAttemptId
}

internal fun shouldApplyTaskProgressMutation(
    task: DownloadTask?,
    attemptId: Long?
): Boolean {
    return task != null && attemptId != null && task.attemptId == attemptId
}

internal fun isActiveDownloadAttempt(
    tasks: List<DownloadTask>,
    songKey: String,
    expectedAttemptId: Long?
): Boolean {
    val task = tasks.firstOrNull { it.song.stableKey() == songKey } ?: return false
    if (!shouldApplyTaskMutation(task, expectedAttemptId)) {
        return false
    }
    return task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
}

internal fun applyWaitingNetworkStatus(
    tasks: List<DownloadTask>,
    waitingTasks: Collection<DownloadTask>
): List<DownloadTask> {
    if (tasks.isEmpty() || waitingTasks.isEmpty()) {
        return tasks
    }
    val waitingTaskKeys = waitingTasks
        .mapTo(mutableSetOf()) { task -> task.song.stableKey() to task.attemptId }
    var changed = false
    val updatedTasks = tasks.map { task ->
        val shouldWait =
            task.status in arrayOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) &&
                waitingTaskKeys.contains(task.song.stableKey() to task.attemptId)
        if (!shouldWait) {
            return@map task
        }
        changed = true
        task.copy(status = DownloadStatus.WAITING_NETWORK)
    }
    return if (changed) updatedTasks else tasks
}

internal fun applyCancelledStatus(
    tasks: List<DownloadTask>,
    cancelledTasks: Collection<DownloadTask>
): List<DownloadTask> {
    if (tasks.isEmpty() || cancelledTasks.isEmpty()) {
        return tasks
    }
    val cancelledTaskKeys = cancelledTasks
        .mapTo(mutableSetOf()) { task -> task.song.stableKey() to task.attemptId }
    var changed = false
    val updatedTasks = tasks.map { task ->
        val shouldCancel =
            task.status in arrayOf(
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.WAITING_NETWORK
            ) &&
                cancelledTaskKeys.contains(task.song.stableKey() to task.attemptId)
        if (!shouldCancel) {
            return@map task
        }
        changed = true
        task.copy(status = DownloadStatus.CANCELLED, progress = null)
    }
    return if (changed) updatedTasks else tasks
}

fun hasPendingDownloadTasks(tasks: List<DownloadTask>): Boolean {
    return countPendingDownloadTasks(tasks) > 0
}

fun hasActiveDownloadTasks(tasks: List<DownloadTask>): Boolean {
    return tasks.any { it.status == DownloadStatus.DOWNLOADING }
}

internal fun hasActiveDownloadOperations(
    tasks: List<DownloadTask>,
    isSingleDownloading: Boolean,
    hasActiveBatchJobs: Boolean
): Boolean {
    if (isSingleDownloading || hasActiveBatchJobs) {
        return true
    }
    return tasks.any { task ->
        task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
    }
}

internal fun hasRecoveryBlockingDownloadOperations(
    tasks: List<DownloadTask>,
    isSingleDownloading: Boolean,
    hasActiveBatchJobs: Boolean
): Boolean {
    if (isSingleDownloading || hasActiveBatchJobs) {
        return true
    }
    return tasks.any { task ->
        task.status == DownloadStatus.DOWNLOADING
    }
}

/** 活动传输存在时也要把持久队列交给共享泵，不能让 WIFI 唤醒机会空转 */
internal fun shouldHandoffBlockedWifiRecoveryToSharedPump(
    hasPendingCandidates: Boolean,
    hasBlockingActiveOperations: Boolean
): Boolean {
    return hasPendingCandidates && hasBlockingActiveOperations
}
