package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDownloadManagerPendingPlaybackPolicyTest {
    @Test
    fun `pending catalog playback requires every durable publication signal`() {
        assertFalse(
            shouldAllowPendingCatalogPlayback(
                referenceIsPending = true,
                catalogEntryAvailable = false,
                snapshotAvailable = true,
                durableCoreCommitAvailable = true
            )
        )
        assertFalse(
            shouldAllowPendingCatalogPlayback(
                referenceIsPending = true,
                catalogEntryAvailable = true,
                snapshotAvailable = false,
                durableCoreCommitAvailable = true
            )
        )
        assertFalse(
            shouldAllowPendingCatalogPlayback(
                referenceIsPending = true,
                catalogEntryAvailable = true,
                snapshotAvailable = true,
                durableCoreCommitAvailable = false
            )
        )
        assertTrue(
            shouldAllowPendingCatalogPlayback(
                referenceIsPending = true,
                catalogEntryAvailable = true,
                snapshotAvailable = true,
                durableCoreCommitAvailable = true
            )
        )
    }

    @Test
    fun `non pending references retain legacy playback compatibility`() {
        assertTrue(
            shouldAllowPendingCatalogPlayback(
                referenceIsPending = false,
                catalogEntryAvailable = false,
                snapshotAvailable = false
            )
        )
    }
}
