package moe.ouom.neriplayer.core.download

import java.util.Locale

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

/**
 * 只有带有当前操作凭据的活动替换才需要把正式文件退回 pending
 * 旧版本或待修复元信息不能因为缺少完成标记而暂时失去可播放引用
 */
internal fun shouldDemotePublishedAudioForFinalization(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): Boolean {
    val operationId = metadata?.operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return false
    if (metadata.downloadFinalized == true) {
        return false
    }
    val state = metadata.artifactState
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?: return false
    return operationId.isNotBlank() && state in setOf(
        "QUEUED",
        "DOWNLOADING",
        "VERIFYING",
        "COMMITTING",
        "STAGING",
        "REPLACING"
    )
}
