package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadArtifactPolicyTest {
    @Test
    fun `missing identity obtains the only lease`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = null,
                nowMs = 100L
            )
        )
    }

    @Test
    fun `finalized identity settles without another lease`() {
        assertEquals(
            ManagedDownloadArtifactDecision.AlreadyDownloaded,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.FINALIZED, 100L),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `active fresh lease rejects a concurrent request`() {
        assertEquals(
            ManagedDownloadArtifactDecision.InFlight,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.DOWNLOADING, 900L),
                nowMs = 1_000L,
                staleLeaseMs = 500L
            )
        )
    }

    @Test
    fun `stale active lease can be reclaimed after a process crash`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.COMMITTING, 100L),
                nowMs = 1_000L,
                staleLeaseMs = 500L
            )
        )
    }

    @Test
    fun `repair state never falls through to a new network download`() {
        assertEquals(
            ManagedDownloadArtifactDecision.RepairRequired,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.REPAIR_REQUIRED, 100L),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `confirmed missing artifact can be acquired again`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.MISSING_CONFIRMED, 100L),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `retryable write cannot downgrade a repair required artifact`() {
        assertEquals(
            ManagedDownloadArtifactState.REPAIR_REQUIRED,
            resolveArtifactStateUpdate(
                current = ManagedDownloadArtifactState.REPAIR_REQUIRED,
                requested = ManagedDownloadArtifactState.FAILED_RETRYABLE
            )
        )
    }

    private fun artifact(
        state: ManagedDownloadArtifactState,
        updatedAtMs: Long
    ): ManagedDownloadArtifactEntity {
        return ManagedDownloadArtifactEntity(
            rootKey = "root",
            stableKey = "netease|1|",
            artifactId = "managed:root:netease|1|",
            state = state.name,
            leaseId = "lease",
            audioReference = null,
            audioName = null,
            fileSize = null,
            contentHash = null,
            libraryAddedAtMs = null,
            sourceCreatedAtMs = null,
            sourceModifiedAtMs = null,
            downloadedAtMs = null,
            migratedAtMs = null,
            finalizedAtMs = null,
            updatedAtMs = updatedAtMs,
            needsReconcile = state != ManagedDownloadArtifactState.FINALIZED,
            lastErrorCode = null
        )
    }
}
