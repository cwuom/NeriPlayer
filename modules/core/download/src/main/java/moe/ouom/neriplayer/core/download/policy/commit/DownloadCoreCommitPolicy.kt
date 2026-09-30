package moe.ouom.neriplayer.core.download.policy.commit

import moe.ouom.neriplayer.data.model.download.execution.DownloadCoreCommitPhase
import java.util.Locale
import java.util.UUID
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata

fun shouldRollbackCancelledAudio(
    phase: DownloadCoreCommitPhase
): Boolean {
    return phase == DownloadCoreCommitPhase.STAGING
}

fun shouldPreserveAudioAfterCancellation(
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
fun shouldPreserveAudioForCancellationRollback(
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

fun shouldPublishCoreCommit(
    metadataAlreadyCoreCommitted: Boolean,
    metadataWriteSucceeded: Boolean
): Boolean {
    return metadataAlreadyCoreCommitted || metadataWriteSucceeded
}

fun shouldAcceptOrphanCoreCommit(
    allowMissingTask: Boolean,
    operationState: String?,
    coreMetadataDurable: Boolean
): Boolean {
    return allowMissingTask && operationState == null && coreMetadataDurable
}

fun isDurableCoreArtifactState(state: String?): Boolean {
    return state in setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "FINALIZED",
        "DEGRADED_COMPLETE",
        "COMPLETE",
        "COMPLETED"
    )
}

fun shouldCleanupCancelledPendingArtifacts(operationState: String?): Boolean {
    return operationState == "CANCEL_REQUESTED" || operationState == "CANCELLED"
}

/** a commit-boundary stop may leave a pending pair even though its state is durable */
fun shouldCleanupCancelledPendingArtifacts(
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

fun requiresDownloadFinalizationRecovery(state: String?): Boolean {
    return state in setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}

/** artifact 记录存在时必须由其终态确认完成，不能被旧 operation 完成位覆盖 */
fun isDownloadFinalizationDurablySettled(
    operationState: String?,
    artifactState: String?
): Boolean {
    val artifactFinalized = artifactState in setOf(
        "FINALIZED",
        "COMPLETE"
    )
    if (artifactState != null) {
        return artifactFinalized
    }
    return operationState in setOf(
        "COMPLETED",
        "FINALIZED",
        "COMPLETE"
    )
}

fun requiresFinalizedPublicationRecovery(
    metadataFinalized: Boolean?,
    operationState: String?,
    artifactState: String?,
    recoveryLeaseOwned: Boolean = false
): Boolean {
    if (metadataFinalized != true) return false
    if (recoveryLeaseOwned) return true
    return operationState in setOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE",
        "RETRYABLE"
    ) || artifactState in setOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}

/** 缺少 operation 行时仍使用跨进程稳定的恢复 owner，避免崩溃后等待 stale lease */
fun finalizedPublicationRecoveryLeaseOwnerId(
    stableKey: String,
    operationId: String?
): String {
    val normalizedOperationId = normalizedPublicationValue(operationId).orEmpty()
    val seed = "finalized-publication-v1\u0000${stableKey.trim()}\u0000$normalizedOperationId"
    return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
}

/**
 * 只有带有当前操作凭据的活动替换才需要把正式文件退回 pending
 * 旧版本或待修复元信息不能因为缺少完成标记而暂时失去可播放引用
 */
fun shouldDemotePublishedAudioForFinalization(
    metadata: DownloadedAudioMetadata?
): Boolean {
    if (metadata == null || normalizedPublicationValue(metadata.operationId) == null) return false
    if (metadata.downloadFinalized == true) return false
    val state = normalizedPublicationValue(metadata.artifactState)?.uppercase(Locale.ROOT) ?: return false
    return state in setOf(
        "QUEUED", "DOWNLOADING", "VERIFYING", "COMMITTING", "STAGING", "REPLACING"
    )
}

private fun normalizedPublicationValue(value: String?): String? =
    value?.trim()?.takeIf(String::isNotBlank)
