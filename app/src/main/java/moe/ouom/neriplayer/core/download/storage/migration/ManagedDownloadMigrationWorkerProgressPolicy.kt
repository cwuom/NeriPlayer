package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

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
