package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoreCommitStorageReferenceTest {
    @Test
    fun `provider failure and permission loss are never missing evidence`() {
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider offline")
                )
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("grant revoked")
                )
            )
        )
        assertTrue(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.Missing
            )
        )
    }
}
