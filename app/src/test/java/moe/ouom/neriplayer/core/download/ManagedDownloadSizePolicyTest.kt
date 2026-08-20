package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadSizePolicyTest {

    @Test
    fun `tolerance uses a bounded relative allowance`() {
        assertEquals(0L, ManagedDownloadSizePolicy.toleranceBytesFor(3L))
        assertEquals(1L, ManagedDownloadSizePolicy.toleranceBytesFor(256L))
        assertEquals(1_000L, ManagedDownloadSizePolicy.toleranceBytesFor(1_000_000L))
        assertEquals(
            ManagedDownloadSizePolicy.MAX_TRANSFER_SIZE_TOLERANCE_BYTES,
            ManagedDownloadSizePolicy.toleranceBytesFor(100_000_000L)
        )
    }

    @Test
    fun `bounded transfer tolerance does not accept empty or materially truncated files`() {
        assertFalse(ManagedDownloadSizePolicy.isTransferSizeComplete(1_000_000L, 0L))
        assertFalse(ManagedDownloadSizePolicy.isTransferSizeComplete(1_000_000L, 998_999L))
        assertFalse(ManagedDownloadSizePolicy.isTransferSizeComplete(1_000_000L, 1_001_001L))
        assertFalse(ManagedDownloadSizePolicy.isTransferSizeComplete(3L, 2L))
    }

    @Test
    fun `bounded transfer tolerance accepts provider size drift`() {
        assertTrue(ManagedDownloadSizePolicy.isTransferSizeComplete(null, 1L))
        assertTrue(ManagedDownloadSizePolicy.isTransferSizeComplete(1_000_000L, 999_000L))
        assertTrue(ManagedDownloadSizePolicy.isTransferSizeComplete(1_000_000L, 1_001_000L))
        assertTrue(
            ManagedDownloadSizePolicy.isTransferSizeComplete(
                100_000_000L,
                100_000_000L + ManagedDownloadSizePolicy.MAX_TRANSFER_SIZE_TOLERANCE_BYTES
            )
        )
    }
}
