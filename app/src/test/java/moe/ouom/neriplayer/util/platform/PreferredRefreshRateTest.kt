package moe.ouom.neriplayer.util.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferredRefreshRateTest {
    @Test
    fun `disabled setting clears the window refresh preference`() {
        assertEquals(
            0f,
            resolvePreferredRefreshRate(
                highRefreshRateEnabled = false,
                supportedRefreshRates = listOf(60f, 90f, 120f),
                currentRefreshRate = 90f
            ),
            0f
        )
    }

    @Test
    fun `enabled setting requests the highest supported refresh rate`() {
        assertEquals(
            144f,
            resolvePreferredRefreshRate(
                highRefreshRateEnabled = true,
                supportedRefreshRates = listOf(60f, 144f, 90f),
                currentRefreshRate = 90f
            ),
            0f
        )
    }

    @Test
    fun `enabled setting falls back to the current valid refresh rate`() {
        assertEquals(
            90f,
            resolvePreferredRefreshRate(
                highRefreshRateEnabled = true,
                supportedRefreshRates = listOf(0f, Float.NaN),
                currentRefreshRate = 90f
            ),
            0f
        )
    }
}
