package moe.ouom.neriplayer.core.download.storage.tree

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMediaScanIsolationTest {

    @Test
    fun `only typed accessible marker state is accepted`() {
        assertTrue(
            ManagedDownloadMediaScanIsolation.isUsableNoMediaMarker(
                ManagedDownloadReferenceIo.AccessResult.Accessible
            )
        )
        assertFalse(
            ManagedDownloadMediaScanIsolation.isUsableNoMediaMarker(
                ManagedDownloadReferenceIo.AccessResult.Missing
            )
        )
        assertFalse(
            ManagedDownloadMediaScanIsolation.isUsableNoMediaMarker(
                ManagedDownloadReferenceIo.AccessResult.PermissionLost
            )
        )
        assertFalse(
            ManagedDownloadMediaScanIsolation.isUsableNoMediaMarker(
                ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                    IllegalStateException("provider offline")
                )
            )
        )
    }
}
