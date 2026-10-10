package moe.ouom.neriplayer.core.download.storage.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoverLimitsTest {

    @Test
    fun `cover pixel budget rejects empty dimensions and budgets`() {
        assertFalse(isCoverPixelBudgetWithin(width = 0, height = 10))
        assertFalse(isCoverPixelBudgetWithin(width = 10, height = -1))
        assertFalse(isCoverPixelBudgetWithin(width = 10, height = 10, maxPixels = 0L))
    }

    @Test
    fun `cover pixel budget is inclusive`() {
        assertTrue(isCoverPixelBudgetWithin(width = 4_000, height = 4_000))
        assertFalse(isCoverPixelBudgetWithin(width = 4_001, height = 4_000))
        assertTrue(isCoverPixelBudgetWithin(width = 50_000, height = 50_000, maxPixels = 2_500_000_000L))
    }
}
