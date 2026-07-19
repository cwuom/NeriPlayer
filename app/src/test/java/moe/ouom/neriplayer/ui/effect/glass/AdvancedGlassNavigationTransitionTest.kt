package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
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
    fun mainTabNavigationUsesBoundedNonlinearMotion() {
        val exitSpec = advancedGlassMainTabExitSpec<Float>() as? TweenSpec<*>
            ?: throw AssertionError("主 Tab 退出动画不再是 TweenSpec")
        val enterSpec = advancedGlassMainTabEnterSpec<Float>() as? TweenSpec<*>
            ?: throw AssertionError("主 Tab 进入动画不再是 TweenSpec")
        val transitionSpec = advancedGlassMainTabTransitionSpec<Float>() as? TweenSpec<*>
            ?: throw AssertionError("主 Tab 成对转场动画不再是 TweenSpec")

        assertEquals(
            ADVANCED_GLASS_MAIN_TAB_EXIT_DURATION_MS,
            exitSpec.durationMillis
        )
        assertEquals(0, exitSpec.delay)
        assertEquals(
            ADVANCED_GLASS_MAIN_TAB_ENTER_DURATION_MS,
            enterSpec.durationMillis
        )
        assertEquals(0, enterSpec.delay)
        assertEquals(
            ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS,
            exitSpec.durationMillis + enterSpec.durationMillis
        )
        assertEquals(400, transitionSpec.durationMillis)
        assertEquals(0, transitionSpec.delay)
        assertTrue(
            "主 Tab 转场必须使用非线性缓动",
            transitionSpec.easing.transform(0.5f) != 0.5f
        )
        assertTrue(
            "主 Tab 转场前段不能太快",
            transitionSpec.easing.transform(0.25f) < 0.35f
        )
    }

    @Test
    fun mainTabExitCanUseOnlyTheRemainingVisibleDuration() {
        val exitSpec = advancedGlassMainTabExitSpec<Float>(durationMillis = 7) as? TweenSpec<*>
            ?: throw AssertionError("主 Tab 退出动画不再是 TweenSpec")

        assertEquals(7, exitSpec.durationMillis)
        assertEquals(0, exitSpec.delay)
    }
}
