package moe.ouom.neriplayer.core.download.manager.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactLeaseOwnerPolicyTest {
    @Test
    fun `queued row without runtime owner cannot retain a transfer lease`() {
        assertFalse(
            isLiveArtifactLeaseOwner(
                candidateOwnsLease = true,
                payloadReadable = true,
                headerInLiveState = true,
                executionActive = false,
                enrichmentActive = false,
                explicitlyCancelled = false
            )
        )
    }

    @Test
    fun `executing operation blocks reclaim only when it owns the same lease`() {
        assertFalse(
            isLiveArtifactLeaseOwner(
                candidateOwnsLease = false,
                payloadReadable = true,
                headerInLiveState = true,
                executionActive = true,
                enrichmentActive = false,
                explicitlyCancelled = false
            )
        )
        assertTrue(
            isLiveArtifactLeaseOwner(
                candidateOwnsLease = true,
                payloadReadable = true,
                headerInLiveState = true,
                executionActive = true,
                enrichmentActive = false,
                explicitlyCancelled = false
            )
        )
    }

    @Test
    fun `unreadable payload is retained only for an active non-cancelled owner`() {
        assertTrue(
            isLiveArtifactLeaseOwner(
                candidateOwnsLease = false,
                payloadReadable = false,
                headerInLiveState = true,
                executionActive = false,
                enrichmentActive = true,
                explicitlyCancelled = false
            )
        )
        assertFalse(
            isLiveArtifactLeaseOwner(
                candidateOwnsLease = false,
                payloadReadable = false,
                headerInLiveState = true,
                executionActive = true,
                enrichmentActive = false,
                explicitlyCancelled = true
            )
        )
    }
}
