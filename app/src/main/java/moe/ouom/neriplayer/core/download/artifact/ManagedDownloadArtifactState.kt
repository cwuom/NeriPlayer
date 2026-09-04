package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity

internal enum class ManagedDownloadArtifactState {
    QUEUED,
    DOWNLOADING,
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
        val artifact: ManagedDownloadArtifactEntity
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
            ManagedDownloadArtifactState.DEGRADED_COMPLETE ->
                ManagedDownloadArtifactDecision.AlreadyDownloaded

            ManagedDownloadArtifactState.MISSING_CONFIRMED ->
                ManagedDownloadArtifactDecision.Acquire

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
