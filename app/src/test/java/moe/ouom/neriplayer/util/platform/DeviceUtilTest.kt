package moe.ouom.neriplayer.util.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUtilTest {
    @Test
    fun `oneplus high density display gets hardware correction`() {
        assertTrue(
            isOnePlusHighDensityDisplay(
                manufacturer = "OnePlus",
                brand = "ONEPLUS",
                densityDpi = 560
            )
        )
        assertEquals(
            0.95f,
            resolveOnePlusHighDensityUiScale(
                userScale = 1.0f,
                manufacturer = "OnePlus",
                brand = "ONEPLUS",
                densityDpi = 560
            ),
            0.001f
        )
    }

    @Test
    fun `oneplus user scale remains independent from hardware correction`() {
        assertEquals(
            0.855f,
            resolveOnePlusHighDensityUiScale(
                userScale = 0.9f,
                manufacturer = "OnePlus",
                brand = null,
                densityDpi = 510
            ),
            0.001f
        )
    }

    @Test
    fun `lower density oneplus display is not corrected`() {
        assertFalse(
            isOnePlusHighDensityDisplay(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                densityDpi = ONEPLUS_HIGH_DENSITY_DPI_THRESHOLD - 1
            )
        )
        assertEquals(
            1.0f,
            resolveOnePlusHighDensityUiScale(
                userScale = 1.0f,
                manufacturer = "OnePlus",
                brand = "OnePlus",
                densityDpi = 450
            ),
            0.001f
        )
    }

    @Test
    fun `other manufacturers are not corrected even at high density`() {
        assertFalse(
            isOnePlusHighDensityDisplay(
                manufacturer = "OPPO",
                brand = "OPPO",
                densityDpi = 560
            )
        )
        assertEquals(
            1.0f,
            resolveOnePlusHighDensityUiScale(
                userScale = 1.0f,
                manufacturer = "OPPO",
                brand = "OPPO",
                densityDpi = 560
            ),
            0.001f
        )
    }
}
