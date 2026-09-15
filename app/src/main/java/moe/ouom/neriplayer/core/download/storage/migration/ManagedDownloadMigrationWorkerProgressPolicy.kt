package moe.ouom.neriplayer.core.download.storage.migration

import androidx.work.WorkInfo
import java.util.Locale
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

internal data class MigrationSharedProgress(
    val processed: Int,
    val total: Int
)

internal fun selectActiveMigrationWorkInfo(
    workInfos: Iterable<WorkInfo>,
    preferredWorkId: String? = null,
    fallbackWorkId: String? = null
): WorkInfo? {
    val active = workInfos.filterNot { info -> info.state.isFinished }
    if (active.isEmpty()) return null

    fun findById(workId: String?): WorkInfo? {
        return workId?.trim()?.takeIf(String::isNotBlank)?.let { requestedId ->
            active.firstOrNull { info ->
                migrationWorkIdsEqual(info.id.toString(), requestedId)
            }
        }
    }

    findById(preferredWorkId)?.let { return it }
    findById(fallbackWorkId)?.let { return it }

    // WorkManager 不保证同一唯一名称返回记录的顺序
    return active.minWithOrNull(
        compareBy<WorkInfo>(
            { migrationWorkStatePriority(it.state) },
            { -it.generation },
            { -it.runAttemptCount },
            { it.id.toString().lowercase(Locale.ROOT) }
        )
    )
}

/** WorkManager 行丢失或结束时仍保留迁移横幅 */
internal fun shouldPreserveMigrationUiAfterWorkInfo(
    workInfoState: WorkInfo.State?,
    requestAutoResume: Boolean,
    journalPhase: ManagedMigrationReplacementJournalPhase?,
    hasPersistedRequest: Boolean,
    checkpointReadFailed: Boolean = false
): Boolean {
    if (checkpointReadFailed) return true
    if (workInfoState != null && !workInfoState.isFinished) return true
    return requestAutoResume || (
        !hasPersistedRequest &&
        journalPhase != null &&
            journalPhase != ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
        )
}

internal fun shouldResumePersistedMigrationAfterWorkInfo(
    workInfoState: WorkInfo.State?,
    requestAutoResume: Boolean,
    journalPhase: ManagedMigrationReplacementJournalPhase?,
    hasPersistedRequest: Boolean,
    checkpointReadFailed: Boolean = false
): Boolean {
    if (workInfoState != null && !workInfoState.isFinished) return false
    if (checkpointReadFailed && !requestAutoResume && journalPhase == null) return false
    return requestAutoResume || (
        !hasPersistedRequest &&
        journalPhase != null &&
            journalPhase != ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
        )
}

internal fun migrationWorkIdsEqual(left: String?, right: String?): Boolean {
    val normalizedLeft = left?.trim()?.takeIf(String::isNotBlank) ?: return false
    val normalizedRight = right?.trim()?.takeIf(String::isNotBlank) ?: return false
    if (normalizedLeft.equals(normalizedRight, ignoreCase = true)) return true
    val leftUuid = runCatching { UUID.fromString(normalizedLeft) }.getOrNull()
    val rightUuid = runCatching { UUID.fromString(normalizedRight) }.getOrNull()
    return leftUuid != null && leftUuid == rightUuid
}

internal fun shouldReplaceActiveMigrationWork(
    persistedRequest: ManagedMigrationRequest?,
    activeWorkId: String?
): Boolean {
    val persistedId = persistedRequest?.workId
    return persistedId != null &&
        activeWorkId != null &&
        !migrationWorkIdsEqual(persistedId, activeWorkId)
}

internal fun shouldSupersedePersistedMigrationRequest(
    persistedRequest: ManagedMigrationRequest?,
    requestedRequest: ManagedMigrationRequest
): Boolean {
    persistedRequest ?: return false
    return !ManagedDownloadStorage.areEquivalentDirectoryUris(
        persistedRequest.fromDirectoryUri,
        requestedRequest.fromDirectoryUri
    ) || !ManagedDownloadStorage.areEquivalentDirectoryUris(
        persistedRequest.toDirectoryUri,
        requestedRequest.toDirectoryUri
    )
}

private fun migrationWorkStatePriority(state: WorkInfo.State): Int {
    return when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.ENQUEUED -> 1
        WorkInfo.State.BLOCKED -> 2
        else -> 3
    }
}

internal fun migrationProgressForSharedProcessing(
    progress: ManagedDownloadStorage.MigrationProgress
): MigrationSharedProgress {
    return if (
        progress.stage == ManagedDownloadStorage.MigrationStage.CLEANING_UP &&
        progress.cleanupFilesTotal > 0
    ) {
        MigrationSharedProgress(
            processed = progress.cleanupFilesProcessed.coerceAtLeast(0),
            total = progress.cleanupFilesTotal.coerceAtLeast(0)
        )
    } else {
        MigrationSharedProgress(
            processed = progress.processedFiles.coerceAtLeast(0),
            total = progress.totalFiles.coerceAtLeast(0)
        )
    }
}

/** 合并所有持久检查点，恢复后的 Worker 不让界面进度倒退 */
internal fun selectMigrationProgressCheckpoint(
    checkpointIds: Iterable<String>,
    readProgress: (String) -> ManagedDownloadStorage.MigrationProgress?
): ManagedDownloadStorage.MigrationProgress? {
    var selected: ManagedDownloadStorage.MigrationProgress? = null
    checkpointIds.forEach { checkpointId ->
        val candidate = readProgress(checkpointId) ?: return@forEach
        selected = selected?.let { floor ->
            mergeMigrationProgressFloor(floor = floor, current = candidate)
        } ?: candidate
    }
    return selected
}

internal data class MigrationProgressThrottleState(
    val lastPublishedAtMs: Long = Long.MIN_VALUE,
    val lastStage: ManagedDownloadStorage.MigrationStage? = null,
    val lastPercent: Int? = null
)

internal fun shouldPublishMigrationProgress(
    progress: ManagedDownloadStorage.MigrationProgress,
    nowMs: Long,
    state: MigrationProgressThrottleState,
    minIntervalMs: Long,
    percentDelta: Int
): Boolean {
    val percent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
    val stageChanged = progress.stage != state.lastStage
    val percentChangedEnough = state.lastPercent == null ||
        kotlin.math.abs(percent - state.lastPercent) >= percentDelta
    return state.lastPublishedAtMs == Long.MIN_VALUE ||
        stageChanged ||
        percentChangedEnough ||
        nowMs - state.lastPublishedAtMs >= minIntervalMs
}

internal fun updateMigrationProgressThrottleState(
    progress: ManagedDownloadStorage.MigrationProgress,
    nowMs: Long
): MigrationProgressThrottleState {
    return MigrationProgressThrottleState(
        lastPublishedAtMs = nowMs,
        lastStage = progress.stage,
        lastPercent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
    )
}

internal enum class MigrationCleanupWorkDecision {
    COMPLETE,
    RETRY,
    FAILURE
}

internal fun migrationCleanupWorkDecision(
    result: ManagedDownloadStorage.MigrationResult
): MigrationCleanupWorkDecision {
    return when {
        result.cleanupFailedFiles == 0 -> MigrationCleanupWorkDecision.COMPLETE
        result.hasOnlyRetryableCleanupFailures -> MigrationCleanupWorkDecision.RETRY
        else -> MigrationCleanupWorkDecision.FAILURE
    }
}
