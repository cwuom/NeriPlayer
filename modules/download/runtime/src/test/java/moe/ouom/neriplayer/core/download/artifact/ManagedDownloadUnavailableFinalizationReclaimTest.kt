package moe.ouom.neriplayer.core.download.artifact

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadUnavailableFinalizationReclaimTest {
    @Test
    fun `user retry reclaims an unavailable finalization only when the lease is free or already ours`() {
        assertTrue(reclaim(currentLeaseId = null, leaseOwnerId = "worker-1"))
        assertTrue(reclaim(currentLeaseId = "worker-1", leaseOwnerId = "worker-1"))
        assertFalse(reclaim(currentLeaseId = "worker-2", leaseOwnerId = "worker-1"))
        assertFalse(reclaim(currentLeaseId = "worker-2", leaseOwnerId = null))
    }

    @Test
    fun `automatic retries and other finalization states never reclaim`() {
        assertFalse(reclaim(userInitiated = false))
        ManagedDownloadArtifactState.entries
            .filter { state -> state != ManagedDownloadArtifactState.FINALIZED }
            .forEach { state -> assertFalse(state.name, reclaim(artifactState = state)) }
        ManagedDownloadArtifactFinalizationDisposition.entries
            .filter { disposition -> disposition != ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE }
            .forEach { disposition -> assertFalse(disposition.name, reclaim(disposition = disposition)) }
    }

    private fun reclaim(
        artifactState: ManagedDownloadArtifactState = ManagedDownloadArtifactState.FINALIZED,
        disposition: ManagedDownloadArtifactFinalizationDisposition =
            ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE,
        userInitiated: Boolean = true,
        currentLeaseId: String? = null,
        leaseOwnerId: String? = "worker-1"
    ): Boolean = shouldReclaimUnavailableFinalizationForFreshTransfer(
        artifactState = artifactState,
        disposition = disposition,
        userInitiated = userInitiated,
        currentLeaseId = currentLeaseId,
        leaseOwnerId = leaseOwnerId
    )
}
