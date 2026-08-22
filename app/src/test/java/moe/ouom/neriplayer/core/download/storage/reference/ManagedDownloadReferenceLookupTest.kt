package moe.ouom.neriplayer.core.download.storage.reference

import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadReferenceLookupTest {
    @Test
    fun `only an explicit missing provider failure can mark reference missing`() {
        assertTrue(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.Missing
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider unavailable")
                )
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("permission revoked")
                )
            )
        )
    }

    @Test
    fun `file not found is missing while an arbitrary io failure is not`() {
        assertTrue(
            ManagedDownloadReferenceLookup.isMissingFailure(
                FileNotFoundException("missing document")
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.isMissingFailure(
                IllegalStateException("provider returned an empty cursor")
            )
        )
    }
}
