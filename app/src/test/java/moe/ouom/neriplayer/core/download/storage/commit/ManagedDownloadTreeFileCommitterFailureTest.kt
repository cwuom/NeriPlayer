package moe.ouom.neriplayer.core.download.storage.commit

import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadTreeFileCommitterFailureTest {
    @Test
    fun `arbitrary provider failure does not permit create fallback`() {
        assertFalse(
            shouldRetryCreateAfterFailure(
                FileNotFoundException("provider temporarily unavailable")
            )
        )
    }

    @Test
    fun `explicit missing document permits create fallback`() {
        assertTrue(
            shouldRetryCreateAfterFailure(
                FileNotFoundException("Missing file for content://provider/document/id")
            )
        )
    }
}
