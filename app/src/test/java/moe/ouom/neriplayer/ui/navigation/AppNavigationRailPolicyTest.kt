package moe.ouom.neriplayer.ui.navigation

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.BottomBarLayoutInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationRailPolicyTest {
    @Test
    fun `only sufficiently wide tablet windows use the rail`() {
        assertTrue(shouldUseAppNavigationRail(800, 1280.dp))
        assertTrue(shouldUseAppNavigationRail(600, 840.dp))
        assertFalse(shouldUseAppNavigationRail(360, 1280.dp))
        assertFalse(shouldUseAppNavigationRail(800, 839.dp))
    }

    @Test
    fun `wide portrait tablet windows use the rail regardless of orientation`() {
        // 12.9 inch class tablet in portrait: 1024dp wide, smallest width 1024dp
        assertTrue(shouldUseAppNavigationRail(1024, 1024.dp))
        // typical 11 inch tablet in portrait stays below the rail width
        assertFalse(shouldUseAppNavigationRail(800, 800.dp))
        // split-screen tablet window narrower than the rail threshold
        assertFalse(shouldUseAppNavigationRail(800, 640.dp))
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
