package moe.ouom.neriplayer.core.download

/**
 * tracks the point after which a cancellation no longer owns the committed media
 */
internal enum class DownloadCoreCommitPhase {
    STAGING,
    COMMITTING,
    CORE_COMMITTED
}

internal fun shouldRollbackCancelledAudio(
    phase: DownloadCoreCommitPhase
): Boolean {
    return phase == DownloadCoreCommitPhase.STAGING
}
