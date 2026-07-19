package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassNavigationTransitionTest {
    @Test
    fun settingsNavigationUsesNonlinearSpringMotion() {
        val animationSpec = advancedGlassNavigationSpringSpec()
        val springSpec = animationSpec as? SpringSpec<*>
            ?: throw AssertionError("设置导航动画不再是 SpringSpec")

        assertEquals(
            ADVANCED_GLASS_NAVIGATION_DAMPING_RATIO,
            springSpec.dampingRatio
        )
        assertEquals(
            ADVANCED_GLASS_NAVIGATION_STIFFNESS,
            springSpec.stiffness
        )
    }

    @Test
    fun mainTabNavigationUsesSlowerSpringThanSettings() {
        val settingsSpec = advancedGlassNavigationSpringSpec() as? SpringSpec<*>
            ?: throw AssertionError("设置导航动画不再是 SpringSpec")
        val mainTabSpec = advancedGlassMainTabNavigationSpringSpec() as? SpringSpec<*>
            ?: throw AssertionError("主 Tab 导航动画不再是 SpringSpec")

        assertEquals(
            ADVANCED_GLASS_NAVIGATION_DAMPING_RATIO,
            mainTabSpec.dampingRatio
        )
        assertEquals(
            ADVANCED_GLASS_MAIN_TAB_NAVIGATION_STIFFNESS,
            mainTabSpec.stiffness
        )
        assertTrue(mainTabSpec.stiffness < settingsSpec.stiffness)
    }
}
