package moe.ouom.neriplayer.core.download.artifact

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

internal object ManagedDownloadArtifactPolicy {
    const val DEFAULT_STALE_LEASE_MS = 15 * 60 * 1_000L

    fun decide(
        existing: ManagedDownloadArtifactEntity?,
        nowMs: Long,
        staleLeaseMs: Long = DEFAULT_STALE_LEASE_MS
    ): ManagedDownloadArtifactDecision {
        if (existing == null) {
            return ManagedDownloadArtifactDecision.Acquire
        }
        return when (ManagedDownloadArtifactState.fromPersisted(existing.state)) {
            ManagedDownloadArtifactState.FINALIZED ->
                ManagedDownloadArtifactDecision.AlreadyDownloaded

            ManagedDownloadArtifactState.REPAIR_REQUIRED ->
                ManagedDownloadArtifactDecision.RepairRequired

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
                if (nowMs - existing.updatedAtMs >= staleLeaseMs) {
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

internal fun resolveArtifactStateUpdate(
    current: ManagedDownloadArtifactState,
    requested: ManagedDownloadArtifactState
): ManagedDownloadArtifactState {
    return if (
        current == ManagedDownloadArtifactState.REPAIR_REQUIRED &&
            requested == ManagedDownloadArtifactState.FAILED_RETRYABLE
    ) {
        ManagedDownloadArtifactState.REPAIR_REQUIRED
    } else {
        requested
    }
}
