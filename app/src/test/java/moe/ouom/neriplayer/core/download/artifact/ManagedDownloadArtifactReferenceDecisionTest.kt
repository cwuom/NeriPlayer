package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadArtifactReferenceDecisionTest {
    @Test
    fun `only typed missing evidence enters missing confirmation`() {
        assertEquals(
            ManagedDownloadArtifactReferenceState.PRESENT,
            classifyManagedDownloadArtifactReference(
                ManagedDownloadReferenceLookup.Result.Present
            )
        )
        assertEquals(
            ManagedDownloadArtifactReferenceState.MISSING,
            classifyManagedDownloadArtifactReference(
                ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertEquals(
            ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED,
            classifyManagedDownloadArtifactReference(
                ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("grant revoked")
                )
            )
        )
        assertEquals(
            ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED,
            classifyManagedDownloadArtifactReference(
                ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider unavailable")
                )
            )
        )
        assertEquals(
            ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED,
            classifyManagedDownloadArtifactReference(
                ManagedDownloadReferenceLookup.Result.OutOfScope
            )
        )
    }
}
