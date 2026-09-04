package moe.ouom.neriplayer.ui.component.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadManagerSheetTest {

    @Test
    fun `active batch admission remains cancellable before rows are created`() {
        assertTrue(
            canCancelBatchDownload(
                hasPendingBatchSongs = false,
                pendingTaskCount = 0,
                hasActiveDownloadOperations = true
            )
        )
    }

    @Test
    fun `empty inactive manager has no cancellation action`() {
        assertFalse(
            canCancelBatchDownload(
                hasPendingBatchSongs = false,
                pendingTaskCount = 0,
                hasActiveDownloadOperations = false
            )
        )
    }
}
