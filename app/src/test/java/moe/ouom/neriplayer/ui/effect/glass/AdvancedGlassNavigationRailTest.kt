package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassNavigationRailTest {
    @Test
    fun `global rail remains active while page ownership filtering stays enabled`() {
        val activeOwner = Any()
        val inactiveOwner = Any()
        val activeOwners = setOf(activeOwner)
        assertTrue(
            isAdvancedGlassNavigationOwnerActive(
                isGlobalAdvancedGlassNavigation(AdvancedGlassRole.NavigationRail),
                activeOwners,
                inactiveOwner
            )
        )
        assertNull(advancedGlassRegionNavigationOwner(AdvancedGlassRole.NavigationRail, inactiveOwner))
        assertFalse(
            isAdvancedGlassNavigationOwnerActive(
                isGlobalAdvancedGlassNavigation(AdvancedGlassRole.ScreenTopTab),
                activeOwners,
                inactiveOwner
            )
        )
        assertSame(inactiveOwner, advancedGlassRegionNavigationOwner(AdvancedGlassRole.ScreenTopTab, inactiveOwner))
        assertNull(advancedGlassRegionNavigationOwner(AdvancedGlassRole.BottomNavigation, inactiveOwner))
        assertNull(advancedGlassRegionNavigationOwner(AdvancedGlassRole.MiniPlayer, inactiveOwner))
    }

    @Test
    fun `rail supports base blur without enhanced blur and retains background surface tokens`() {
        val controller = AdvancedGlassController(
            sdkInt = 36,
            advancedBlurEnabled = true,
            enhancedAdvancedBlurEnabled = false,
            backendReady = true
        )
        assertTrue(canSampleAdvancedGlassBackdrop(controller, 0, AdvancedGlassRole.NavigationRail))
        assertFalse(canSampleAdvancedGlassBackdrop(controller, 1, AdvancedGlassRole.NavigationRail))
        assertFalse(canSampleAdvancedGlassBackdrop(controller.copy(advancedBlurEnabled = false), 0, AdvancedGlassRole.NavigationRail))
        assertEquals(
            advancedGlassTokens(AdvancedGlassRole.ScreenTopTab, isDarkTheme = false, enhancedBlurRadiusDp = 40f),
            advancedGlassTokens(AdvancedGlassRole.NavigationRail, isDarkTheme = false, enhancedBlurRadiusDp = 40f)
        )
    }
}
