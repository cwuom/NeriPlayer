package moe.ouom.neriplayer.core.download.storage.commit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafProviderSizeDriftAcceptanceTest {
    @Test
    fun `missing, non positive or matching provider sizes need no read back`() {
        assertTrue(isSafProviderSizeDriftRecoverable(copiedBytes = 128L, reportedBytes = 0L, countedBytes = null))
        assertTrue(isSafProviderSizeDriftRecoverable(copiedBytes = 128L, reportedBytes = -5L, countedBytes = 1L))
        assertTrue(isSafProviderSizeDriftRecoverable(copiedBytes = 128L, reportedBytes = 128L, countedBytes = null))
        assertTrue(isSafProviderSizeDriftRecoverable(copiedBytes = 0L, reportedBytes = 0L, countedBytes = null))
    }

    @Test
    fun `a drifting provider size without a read back count is rejected`() {
        assertFalse(isSafProviderSizeDriftRecoverable(copiedBytes = 128L, reportedBytes = 96L, countedBytes = null))
    }
}
