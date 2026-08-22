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

internal fun shouldPreserveAudioAfterCancellation(
    downloadFinalized: Boolean?,
    artifactState: String?
): Boolean {
    if (downloadFinalized == true) return true
    return artifactState in setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "FINALIZED",
        "DEGRADED_COMPLETE"
    )
}
