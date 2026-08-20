package moe.ouom.neriplayer.core.download.storage.tree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMediaScanIsolationTest {

    @Test
    fun `descriptor-accessible marker is accepted when document file reports missing`() {
        assertTrue(
            ManagedDownloadMediaScanIsolation.isUsableNoMediaMarker(
                documentExists = false,
                descriptorAccessible = true
            )
        )
        assertTrue(
            ManagedDownloadMediaScanIsolation.isUsableNoMediaMarker(
                documentExists = true,
                descriptorAccessible = false
            )
        )
        assertFalse(
            ManagedDownloadMediaScanIsolation.isUsableNoMediaMarker(
                documentExists = false,
                descriptorAccessible = false
            )
        )
    }
}
