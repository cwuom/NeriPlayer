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

    @Test
    fun `missing catalog entry is evicted only with complete evidence`() {
        assertTrue(
            shouldEvictMissingDownloadedSongCatalogEntry(
                sawMissing = true,
                sawUncertain = false,
                hasActiveDownload = false
            )
        )
        assertFalse(
            shouldEvictMissingDownloadedSongCatalogEntry(
                sawMissing = true,
                sawUncertain = true,
                hasActiveDownload = false
            )
        )
        assertFalse(
            shouldEvictMissingDownloadedSongCatalogEntry(
                sawMissing = true,
                sawUncertain = false,
                hasActiveDownload = true
            )
        )
        assertFalse(
            shouldEvictMissingDownloadedSongCatalogEntry(
                sawMissing = false,
                sawUncertain = false,
                hasActiveDownload = false
            )
        )
    }
}
