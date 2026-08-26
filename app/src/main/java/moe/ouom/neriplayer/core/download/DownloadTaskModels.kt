package moe.ouom.neriplayer.core.download

import kotlin.math.floor
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
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
    val terminalStates: Map<String, BatchDownloadTerminalState> = emptyMap(),
    val maximumObservedFractions: Map<String, Float> = emptyMap()
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
                        completedSongs++
                        completedFraction += 1f
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
    val fraction = (completedFraction / totalSongs.toFloat()).coerceIn(0f, 1f)
    return BatchDownloadOverallProgress(
        totalSongs = totalSongs,
        completedSongs = completedSongs,
        percentage = floor(fraction * 100f).toInt(),
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
                        maximumObservedFraction =
                            presentation.maximumObservedFractions[songKey] ?: 0f
                    )
            }
        }
    if (membersBySongKey.isEmpty()) {
        return null
    }

    val tasksBySongKey = tasks.associateBy { task -> task.song.stableKey() }
    val memberAttemptIds = linkedMapOf<String, Long?>()
    val terminalStates = linkedMapOf<String, BatchDownloadTerminalState>()
    val maximumObservedFractions = linkedMapOf<String, Float>()
    membersBySongKey.forEach { (songKey, members) ->
        val selected = selectBatchPresentationMember(
            members = members,
            task = tasksBySongKey[songKey]
        )
        memberAttemptIds[songKey] = selected.attemptId
        selected.terminalState?.let { terminalState ->
            terminalStates[songKey] = terminalState
        }
        mergedBatchPresentationMaximumObservedFraction(selected, members)
            .coerceIn(0f, 1f)
            .takeIf { fraction -> fraction > 0f }
            ?.let { fraction -> maximumObservedFractions[songKey] = fraction }
    }
    return BatchDownloadPresentationState(
        id = presentations.maxOf(BatchDownloadPresentationState::id),
        memberAttemptIds = memberAttemptIds,
        terminalStates = terminalStates,
        maximumObservedFractions = maximumObservedFractions
    )
}

private data class BatchPresentationMember(
    val presentationId: Long,
    val attemptId: Long?,
    val terminalState: BatchDownloadTerminalState?,
    val maximumObservedFraction: Float
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
    if (progress.stage == AudioDownloadManager.DownloadStage.FINALIZING) {
        return 1f
    }
    if (progress.totalBytes <= 0L) {
        return 0f
    }
    return (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
        .coerceIn(0f, 1f)
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

internal fun mergeDownloadTaskProgress(
    current: AudioDownloadManager.DownloadProgress?,
    incoming: AudioDownloadManager.DownloadProgress
): AudioDownloadManager.DownloadProgress {
    val currentAttemptId = current?.attemptId
    if (current == null || currentAttemptId == null || currentAttemptId != incoming.attemptId) {
        return incoming
    }
    if (downloadProgressFraction(incoming) >= downloadProgressFraction(current)) {
        return incoming
    }
    val retained = if (
        current.stage == AudioDownloadManager.DownloadStage.FINALIZING &&
            current.totalBytes > 0L &&
            current.bytesRead < current.totalBytes
    ) {
        current.copy(bytesRead = current.totalBytes)
    } else {
        current
    }
    return retained.copy(
        songId = incoming.songId,
        fileName = incoming.fileName,
        speedBytesPerSec = incoming.speedBytesPerSec,
        stage = incoming.stage
    )
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
    val queuedTaskCount: Int = 0,
    val hasActiveTasks: Boolean = false,
    val hasActiveOperations: Boolean = false
) {
    val hasPendingTasks: Boolean
        get() = pendingTaskCount > 0
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
    requestMatchesSong: Boolean
): BatchOperationScheduleAction {
    if (operationState == null || !requestMatchesSong) {
        return BatchOperationScheduleAction.INVALID
    }
    if (operationState in DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES) {
        return BatchOperationScheduleAction.HANDED_OFF
    }
    if (operationState in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES) {
        return BatchOperationScheduleAction.SCHEDULE
    }
    if (operationState in setOf("CANCEL_REQUESTED", "CANCELLED", "STOPPED")) {
        return BatchOperationScheduleAction.RELEASE
    }
    return BatchOperationScheduleAction.SETTLED
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
    return tasks.filter { task -> task.status == DownloadStatus.DOWNLOADING }
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
            DownloadStatus.FAILED -> pendingTaskCount++
            DownloadStatus.COMPLETED,
            DownloadStatus.CANCELLED -> Unit
        }
    }

    return DownloadTaskSummary(
        pendingTaskCount = pendingTaskCount,
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
            task.status == DownloadStatus.WAITING_NETWORK ||
            task.status == DownloadStatus.FAILED
    }
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
