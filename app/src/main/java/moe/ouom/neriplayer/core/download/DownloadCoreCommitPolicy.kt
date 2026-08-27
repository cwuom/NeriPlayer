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
        "DEGRADED_COMPLETE",
        "COMPLETE"
    )
}

/**
 * protects a durable-looking audio entry when cancellation cannot prove ownership
 */
internal fun shouldPreserveAudioForCancellationRollback(
    audioIsPending: Boolean,
    metadataReadable: Boolean,
    downloadFinalized: Boolean?,
    artifactState: String?,
    metadataOperationId: String?,
    operationId: String?
): Boolean {
    if (!metadataReadable && !audioIsPending) {
        return true
    }
    if (operationId != null && metadataOperationId != operationId) {
        return true
    }
    if (!audioIsPending && artifactState == "COMMITTING") {
        return true
    }
    return shouldPreserveAudioAfterCancellation(
        downloadFinalized = downloadFinalized,
        artifactState = artifactState
    )
}

internal fun shouldPublishCoreCommit(
    metadataAlreadyCoreCommitted: Boolean,
    metadataWriteSucceeded: Boolean
): Boolean {
    return metadataAlreadyCoreCommitted || metadataWriteSucceeded
}

internal fun isDurableCoreArtifactState(state: String?): Boolean {
    return state in setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "FINALIZED",
        "DEGRADED_COMPLETE",
        "COMPLETE",
        "COMPLETED"
    )
}

internal fun shouldCleanupCancelledPendingArtifacts(operationState: String?): Boolean {
    return operationState == "CANCEL_REQUESTED" || operationState == "CANCELLED"
}

internal fun requiresDownloadFinalizationRecovery(state: String?): Boolean {
    return state in setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}

internal fun requiresFinalizedPublicationRecovery(
    metadataFinalized: Boolean?,
    operationState: String?,
    artifactState: String?
): Boolean {
    if (metadataFinalized != true) return false
    return operationState in setOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    ) || artifactState in setOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}
