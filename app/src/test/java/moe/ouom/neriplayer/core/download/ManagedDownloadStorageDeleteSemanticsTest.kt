package moe.ouom.neriplayer.core.download

import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageDeleteSemanticsTest {

    @Test
    fun `missing saf document failures are treated as already deleted`() {
        assertTrue(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                FileNotFoundException("Missing file for primary:neriplayer-download/test.flac")
            )
        )
        assertTrue(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                IllegalArgumentException("Failed to determine if uri is child of primary:neriplayer-download")
            )
        )
    }

    @Test
    fun `unrelated delete failures are not swallowed as missing document`() {
        assertFalse(
            ManagedDownloadStorage.isMissingManagedDocumentFailure(
                IllegalStateException("provider offline")
            )
        )
    }
}
