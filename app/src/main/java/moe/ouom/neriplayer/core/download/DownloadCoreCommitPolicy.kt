package moe.ouom.neriplayer.core.download

import java.util.Locale
import java.util.UUID

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

internal fun shouldAcceptOrphanCoreCommit(
    allowMissingTask: Boolean,
    operationState: String?,
    coreMetadataDurable: Boolean
): Boolean {
    return allowMissingTask && operationState == null && coreMetadataDurable
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

/** a commit-boundary stop may leave a pending pair even though its state is durable */
internal fun shouldCleanupCancelledPendingArtifacts(
    operationState: String?,
    stopRequestedByUser: Boolean
): Boolean {
    return shouldCleanupCancelledPendingArtifacts(operationState) ||
        stopRequestedByUser && operationState in setOf(
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        )
}

internal fun requiresDownloadFinalizationRecovery(state: String?): Boolean {
    return state in setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}

/** 只有持久化状态越过 core 收尾边界后，恢复流程才能停止重试 */
internal fun isDownloadFinalizationDurablySettled(
    operationState: String?,
    artifactState: String?
): Boolean {
    return operationState in setOf(
        "COMPLETED",
        "FINALIZED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE",
        "COMPLETE"
    ) || artifactState in setOf(
        "FINALIZED",
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

/** 缺少 operation 行时仍使用跨进程稳定的恢复 owner，避免崩溃后等待 stale lease */
internal fun finalizedPublicationRecoveryLeaseOwnerId(
    stableKey: String,
    operationId: String?
): String {
    val normalizedOperationId = operationId?.trim()?.takeIf(String::isNotBlank).orEmpty()
    val seed = "finalized-publication-v1\u0000${stableKey.trim()}\u0000$normalizedOperationId"
    return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
}

/** 最终发布被新代次接管时不能再把旧回调当成可重试故障 */
internal enum class FinalizedDownloadPublicationResult {
    PUBLISHED,
    STALE,
    RECOVERY_REQUIRED;

    val requiresRecovery: Boolean
        get() = this == RECOVERY_REQUIRED
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
