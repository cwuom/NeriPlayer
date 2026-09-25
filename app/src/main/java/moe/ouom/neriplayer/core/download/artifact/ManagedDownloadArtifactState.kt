package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity

internal enum class ManagedDownloadArtifactState {
    QUEUED,
    DOWNLOADING,
    /** 存储空间或目录能力暂时不可用，但仍保留原 lease 和工作文件 */
    WAITING_STORAGE,
    VERIFYING,
    COMMITTING,
    CORE_COMMITTED,
    ASSETS_ENRICHING,
    FINALIZED,
    DEGRADED_COMPLETE,
    REPAIR_REQUIRED,
    MISSING_CONFIRMED,
    FAILED_RETRYABLE,
    CANCELLED;

    companion object {
        fun fromPersisted(value: String): ManagedDownloadArtifactState {
            return entries.firstOrNull { state -> state.name == value }
                ?: REPAIR_REQUIRED
        }
    }
}

internal sealed interface ManagedDownloadArtifactClaim {
    data class Acquired(
        val artifact: ManagedDownloadArtifactEntity,
        /** 用户明确重新下载时保留旧的、无法确认归属的引用，不做破坏性清理 */
        val preservesExistingReference: Boolean = false
    ) : ManagedDownloadArtifactClaim

    data class AlreadyDownloaded(
        val artifact: ManagedDownloadArtifactEntity
    ) : ManagedDownloadArtifactClaim

    data class InFlight(
        val artifact: ManagedDownloadArtifactEntity
    ) : ManagedDownloadArtifactClaim

    data class RepairRequired(
        val artifact: ManagedDownloadArtifactEntity
    ) : ManagedDownloadArtifactClaim
}

internal fun ManagedDownloadArtifactClaim?.ownedLeaseIdOrNull(): String? {
    return (this as? ManagedDownloadArtifactClaim.Acquired)?.artifact?.leaseId
}

/** 用包装对象区分“无主 lease 可安全收口”和“仍由其它下载占用” */
internal data class ManagedDownloadArtifactPublicationLease(
    val leaseId: String?
)

internal fun ManagedDownloadArtifactClaim.finalizedPublicationLeaseOrNull():
    ManagedDownloadArtifactPublicationLease? {
    return when (this) {
        is ManagedDownloadArtifactClaim.Acquired ->
            ManagedDownloadArtifactPublicationLease(artifact.leaseId)

        is ManagedDownloadArtifactClaim.AlreadyDownloaded ->
            ManagedDownloadArtifactPublicationLease(artifact.leaseId)

        is ManagedDownloadArtifactClaim.RepairRequired ->
            ManagedDownloadArtifactPublicationLease(artifact.leaseId)

        is ManagedDownloadArtifactClaim.InFlight -> null
    }
}

internal object ManagedDownloadArtifactPolicy {
    const val DEFAULT_STALE_LEASE_MS = 15 * 60 * 1_000L

    fun decide(
        existing: ManagedDownloadArtifactEntity?,
        nowMs: Long,
        staleLeaseMs: Long = DEFAULT_STALE_LEASE_MS,
        leaseOwnerId: String? = null
    ): ManagedDownloadArtifactDecision {
        if (existing == null) {
            return ManagedDownloadArtifactDecision.Acquire
        }
        return when (ManagedDownloadArtifactState.fromPersisted(existing.state)) {
            ManagedDownloadArtifactState.FINALIZED ->
                ManagedDownloadArtifactDecision.AlreadyDownloaded

            ManagedDownloadArtifactState.REPAIR_REQUIRED -> {
                if (existing.audioReference.isNullOrBlank()) {
                    ManagedDownloadArtifactDecision.Acquire
                } else {
                    ManagedDownloadArtifactDecision.RepairRequired
                }
            }

            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE -> {
                // 这些状态只有在有 durable 音频引用时才代表可恢复的已下载文件。
                // 旧版本或中断写入可能只留下状态行；不能让这种行把新请求
                // 永久导向 finalization recovery，否则真实传输永远不会开始
                if (existing.audioReference.isNullOrBlank()) {
                    ManagedDownloadArtifactDecision.Acquire
                } else {
                    ManagedDownloadArtifactDecision.AlreadyDownloaded
                }
            }

            ManagedDownloadArtifactState.MISSING_CONFIRMED ->
                ManagedDownloadArtifactDecision.Acquire

            ManagedDownloadArtifactState.WAITING_STORAGE -> {
                if (
                    existing.leaseId == null ||
                        leaseOwnerId != null && existing.leaseId == leaseOwnerId ||
                        nowMs - existing.updatedAtMs >= staleLeaseMs
                ) {
                    ManagedDownloadArtifactDecision.Acquire
                } else {
                    ManagedDownloadArtifactDecision.InFlight
                }
            }

            ManagedDownloadArtifactState.QUEUED,
            ManagedDownloadArtifactState.DOWNLOADING,
            ManagedDownloadArtifactState.VERIFYING,
            ManagedDownloadArtifactState.COMMITTING -> {
                if (
                    leaseOwnerId != null &&
                    existing.leaseId == leaseOwnerId
                ) {
                    ManagedDownloadArtifactDecision.Acquire
                } else if (nowMs - existing.updatedAtMs >= staleLeaseMs) {
                    ManagedDownloadArtifactDecision.Acquire
                } else {
                    ManagedDownloadArtifactDecision.InFlight
                }
            }

            ManagedDownloadArtifactState.FAILED_RETRYABLE,
            ManagedDownloadArtifactState.CANCELLED ->
                ManagedDownloadArtifactDecision.Acquire
        }
    }
}

internal enum class ManagedDownloadArtifactDecision {
    Acquire,
    AlreadyDownloaded,
    InFlight,
    RepairRequired
}

/** 恢复只能续接旧 request owner 或同一个恢复 owner，不能走通用 stale lease 接管 */
internal fun hasForeignPostCoreRecoveryLeaseOwner(
    currentLeaseOwnerId: String?,
    recoveryLeaseOwnerId: String?,
    previousLeaseOwnerId: String?
): Boolean {
    val normalizedCurrentOwnerId = currentLeaseOwnerId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return false
    val normalizedRecoveryOwnerId = recoveryLeaseOwnerId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val normalizedPreviousOwnerId = previousLeaseOwnerId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    return normalizedCurrentOwnerId != normalizedRecoveryOwnerId &&
        normalizedCurrentOwnerId != normalizedPreviousOwnerId
}

/** post-core 恢复使用独立 owner 接管，避免旧下载回调复用 operation lease 清掉新租约 */
internal fun shouldReclaimPostCoreArtifactLeaseForRecovery(
    artifactState: ManagedDownloadArtifactState,
    currentLeaseOwnerId: String?,
    recoveryEnabled: Boolean,
    recoveryLeaseOwnerId: String?,
    previousLeaseOwnerId: String?
): Boolean {
    val normalizedRecoveryOwnerId = recoveryLeaseOwnerId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return false
    if (!recoveryEnabled || artifactState !in setOf(
            ManagedDownloadArtifactState.QUEUED,
            ManagedDownloadArtifactState.DOWNLOADING,
            ManagedDownloadArtifactState.WAITING_STORAGE,
            ManagedDownloadArtifactState.VERIFYING,
            ManagedDownloadArtifactState.COMMITTING,
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            ManagedDownloadArtifactState.REPAIR_REQUIRED,
            ManagedDownloadArtifactState.FINALIZED
        )
    ) {
        return false
    }
    val normalizedCurrentOwnerId = currentLeaseOwnerId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return true
    val normalizedPreviousOwnerId = previousLeaseOwnerId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    return normalizedCurrentOwnerId == normalizedRecoveryOwnerId ||
        normalizedCurrentOwnerId == normalizedPreviousOwnerId
}

internal enum class ManagedDownloadArtifactFinalizationDisposition {
    SETTLED,
    FINALIZATION_REQUIRED,
    UNAVAILABLE
}

internal enum class ManagedDownloadArtifactMetadataIdentity {
    MATCHING,
    MISSING,
    MISMATCHED
}

internal fun resolveFinalizedArtifactCompletionDisposition(
    artifactState: ManagedDownloadArtifactState,
    snapshotIsComplete: Boolean,
    matchingAudioFound: Boolean,
    metadataIdentity: ManagedDownloadArtifactMetadataIdentity,
    metadataHasStrictCompletion: Boolean
): ManagedDownloadArtifactFinalizationDisposition {
    if (
        artifactState != ManagedDownloadArtifactState.FINALIZED ||
        !snapshotIsComplete ||
        !matchingAudioFound ||
        metadataIdentity == ManagedDownloadArtifactMetadataIdentity.MISMATCHED
    ) {
        return ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE
    }
    if (metadataIdentity == ManagedDownloadArtifactMetadataIdentity.MISSING) {
        return ManagedDownloadArtifactFinalizationDisposition.FINALIZATION_REQUIRED
    }
    return if (metadataHasStrictCompletion) {
        ManagedDownloadArtifactFinalizationDisposition.SETTLED
    } else {
        ManagedDownloadArtifactFinalizationDisposition.FINALIZATION_REQUIRED
    }
}

internal fun matchesManagedDownloadArtifactLease(
    currentLeaseId: String?,
    expectedLeaseId: String?
): Boolean {
    return currentLeaseId == expectedLeaseId
}

/** artifact 状态写入的结果必须区分租约缺失和实际写入成功 */
internal enum class ManagedDownloadArtifactMutationResult {
    APPLIED,
    INVALID_STABLE_KEY,
    EXPECTED_LEASE_NOT_FOUND;

    val isApplied: Boolean
        get() = this == APPLIED
}

/**
 * 当前根目录可能留有旧行，租约 owner 才是跨根收尾时的唯一写入身份
 */
internal fun selectManagedDownloadArtifactForLeaseMutation(
    current: ManagedDownloadArtifactEntity?,
    sameKeyArtifacts: Collection<ManagedDownloadArtifactEntity>,
    expectedLeaseId: String?
): ManagedDownloadArtifactEntity? {
    val normalizedLeaseId = expectedLeaseId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (normalizedLeaseId == null) {
        return current
    }
    if (current?.leaseId == normalizedLeaseId) {
        return current
    }
    return sameKeyArtifacts
        .asSequence()
        .filter { artifact -> artifact.leaseId == normalizedLeaseId }
        .maxWithOrNull(
            compareBy<ManagedDownloadArtifactEntity> { artifact -> artifact.updatedAtMs }
                .thenBy { artifact -> artifact.rootKey }
        )
}

internal fun canApplyLeaseFreeArtifactTransition(
    currentState: ManagedDownloadArtifactState,
    currentLeaseId: String?,
    requestedState: ManagedDownloadArtifactState
): Boolean {
    if (currentLeaseId != null) {
        return false
    }
    return when (requestedState) {
        ManagedDownloadArtifactState.ASSETS_ENRICHING ->
            currentState in setOf(
                ManagedDownloadArtifactState.CORE_COMMITTED,
                ManagedDownloadArtifactState.ASSETS_ENRICHING
            )
        ManagedDownloadArtifactState.DEGRADED_COMPLETE ->
            currentState in setOf(
                ManagedDownloadArtifactState.CORE_COMMITTED,
                ManagedDownloadArtifactState.ASSETS_ENRICHING,
                ManagedDownloadArtifactState.DEGRADED_COMPLETE
            )
        ManagedDownloadArtifactState.MISSING_CONFIRMED ->
            currentState !in setOf(
                ManagedDownloadArtifactState.QUEUED,
                ManagedDownloadArtifactState.DOWNLOADING,
                ManagedDownloadArtifactState.VERIFYING,
                ManagedDownloadArtifactState.COMMITTING
            )
        else -> false
    }
}

internal enum class ManagedDownloadArtifactReferenceState {
    PRESENT,
    MISSING,
    REPAIR_REQUIRED
}

internal fun classifyManagedDownloadArtifactReference(
    result: ManagedDownloadReferenceLookup.Result
): ManagedDownloadArtifactReferenceState {
    return when (result) {
        ManagedDownloadReferenceLookup.Result.Present ->
            ManagedDownloadArtifactReferenceState.PRESENT
        ManagedDownloadReferenceLookup.Result.Missing ->
            ManagedDownloadArtifactReferenceState.MISSING
        ManagedDownloadReferenceLookup.Result.OutOfScope,
        is ManagedDownloadReferenceLookup.Result.PermissionLost,
        is ManagedDownloadReferenceLookup.Result.ProviderFailure ->
            ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED
    }
}

/** 用户明确重下时，只有无法确认的旧引用才允许让出 artifact lease */
internal fun shouldReclaimUnavailableArtifactForFreshTransfer(
    artifactState: ManagedDownloadArtifactState,
    referenceState: ManagedDownloadArtifactReferenceState,
    userInitiated: Boolean,
    currentLeaseId: String?,
    leaseOwnerId: String?
): Boolean {
    if (!userInitiated || referenceState == ManagedDownloadArtifactReferenceState.PRESENT) {
        return false
    }
    if (currentLeaseId != null && currentLeaseId != leaseOwnerId) {
        return false
    }
    return artifactState in setOf(
        ManagedDownloadArtifactState.CORE_COMMITTED,
        ManagedDownloadArtifactState.ASSETS_ENRICHING,
        ManagedDownloadArtifactState.DEGRADED_COMPLETE,
        ManagedDownloadArtifactState.FINALIZED,
        ManagedDownloadArtifactState.REPAIR_REQUIRED
    )
}

/** 最终化证据不完整时，用户明确重试可保留旧引用并重新传输 */
internal fun shouldReclaimUnavailableFinalizationForFreshTransfer(
    artifactState: ManagedDownloadArtifactState,
    disposition: ManagedDownloadArtifactFinalizationDisposition,
    userInitiated: Boolean,
    currentLeaseId: String?,
    leaseOwnerId: String?
): Boolean {
    if (
        !userInitiated ||
            artifactState != ManagedDownloadArtifactState.FINALIZED ||
            disposition != ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE
    ) {
        return false
    }
    return currentLeaseId == null || currentLeaseId == leaseOwnerId
}

/** post-core 行仍有旧引用时，用户明确重新下载也必须能重新取得传输租约 */
internal fun shouldForceFreshTransferForUser(
    artifactState: ManagedDownloadArtifactState,
    userInitiated: Boolean,
    currentLeaseId: String?,
    leaseOwnerId: String?
): Boolean {
    if (!userInitiated) return false
    if (artifactState in setOf(
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE
        )
    ) {
        // post-core 已证明音频传输不再由旧 lease 持有。用户明确重试时可以
        // 让新的 operation 接管，旧 enrichment 的 lease CAS 会自行变为 stale
        return true
    }
    return artifactState == ManagedDownloadArtifactState.REPAIR_REQUIRED &&
        (currentLeaseId == null || currentLeaseId == leaseOwnerId)
}

internal fun resolveArtifactStateUpdate(
    current: ManagedDownloadArtifactState,
    requested: ManagedDownloadArtifactState
): ManagedDownloadArtifactState {
    return when (current) {
        ManagedDownloadArtifactState.FINALIZED -> ManagedDownloadArtifactState.FINALIZED
        ManagedDownloadArtifactState.DEGRADED_COMPLETE -> {
            if (requested == ManagedDownloadArtifactState.FINALIZED) {
                ManagedDownloadArtifactState.FINALIZED
            } else {
                ManagedDownloadArtifactState.DEGRADED_COMPLETE
            }
        }
        ManagedDownloadArtifactState.ASSETS_ENRICHING -> {
            if (requested in setOf(
                    ManagedDownloadArtifactState.FINALIZED,
                    ManagedDownloadArtifactState.DEGRADED_COMPLETE
                )
            ) {
                requested
            } else {
                ManagedDownloadArtifactState.ASSETS_ENRICHING
            }
        }
        ManagedDownloadArtifactState.CORE_COMMITTED -> {
            if (requested in setOf(
                    ManagedDownloadArtifactState.ASSETS_ENRICHING,
                    ManagedDownloadArtifactState.FINALIZED,
                    ManagedDownloadArtifactState.DEGRADED_COMPLETE
                )
            ) {
                requested
            } else {
                ManagedDownloadArtifactState.CORE_COMMITTED
            }
        }
        ManagedDownloadArtifactState.REPAIR_REQUIRED -> {
            if (requested == ManagedDownloadArtifactState.FAILED_RETRYABLE) {
                ManagedDownloadArtifactState.REPAIR_REQUIRED
            } else {
                requested
            }
        }
        else -> requested
    }
}

/** 单调状态拒绝取消降级时仍要释放旧租约，持久音频引用不能被覆盖 */
internal fun shouldReleaseLeaseAfterMonotonicArtifactUpdate(
    current: ManagedDownloadArtifactState,
    requested: ManagedDownloadArtifactState,
    clearLease: Boolean
): Boolean {
    if (!clearLease) return false
    if (requested != ManagedDownloadArtifactState.CANCELLED &&
        requested != ManagedDownloadArtifactState.FAILED_RETRYABLE
    ) {
        return false
    }
    return current == ManagedDownloadArtifactState.CORE_COMMITTED ||
        current == ManagedDownloadArtifactState.ASSETS_ENRICHING ||
        current == ManagedDownloadArtifactState.DEGRADED_COMPLETE ||
        current == ManagedDownloadArtifactState.FINALIZED
}
