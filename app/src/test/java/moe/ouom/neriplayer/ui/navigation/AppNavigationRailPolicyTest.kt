package moe.ouom.neriplayer.ui.navigation

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.BottomBarLayoutInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationRailPolicyTest {
    @Test
    fun `only sufficiently wide landscape tablet windows use the rail`() {
        assertTrue(shouldUseAppNavigationRail(800, isLandscape = true, 1280.dp))
        assertTrue(shouldUseAppNavigationRail(600, isLandscape = true, 840.dp))
        assertFalse(shouldUseAppNavigationRail(800, isLandscape = false, 1280.dp))
        assertFalse(shouldUseAppNavigationRail(360, isLandscape = true, 1280.dp))
        assertFalse(shouldUseAppNavigationRail(800, isLandscape = true, 839.dp))
    }

    @Test
    fun `rail content reserves only the visible mini player after safe area consumption`() {
        for (blur in listOf(false, true)) {
            assertEquals(
                BottomBarLayoutInsets(0.dp, 64.dp, 0.dp),
                resolveAppNavigationLayoutInsets(true, blur, 80.dp, 64.dp)
            )
            assertEquals(
                BottomBarLayoutInsets(0.dp, 0.dp, 0.dp),
                resolveAppNavigationLayoutInsets(true, blur, 80.dp, 0.dp)
            )
        }
    }

    @Test
    fun `bottom navigation keeps the existing opaque and glass insets`() {
        assertEquals(
            BottomBarLayoutInsets(80.dp, 64.dp, 0.dp),
            resolveAppNavigationLayoutInsets(false, false, 80.dp, 64.dp)
        )
        assertEquals(
            BottomBarLayoutInsets(0.dp, 144.dp, 80.dp),
            resolveAppNavigationLayoutInsets(false, true, 80.dp, 64.dp)
        )
    }
}
