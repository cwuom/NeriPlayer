package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

internal const val ADVANCED_GLASS_NAVIGATION_DAMPING_RATIO =
    Spring.DampingRatioNoBouncy
internal const val ADVANCED_GLASS_NAVIGATION_STIFFNESS =
    Spring.StiffnessMediumLow
internal const val ADVANCED_GLASS_MAIN_TAB_NAVIGATION_STIFFNESS =
    Spring.StiffnessLow

internal fun advancedGlassNavigationSpringSpec(): FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = ADVANCED_GLASS_NAVIGATION_DAMPING_RATIO,
    stiffness = ADVANCED_GLASS_NAVIGATION_STIFFNESS
)

internal fun advancedGlassMainTabNavigationSpringSpec(): FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = ADVANCED_GLASS_NAVIGATION_DAMPING_RATIO,
    stiffness = ADVANCED_GLASS_MAIN_TAB_NAVIGATION_STIFFNESS
)

internal fun isolatedAdvancedGlassHorizontalTransition(
    forward: Boolean
): ContentTransform {
    val direction = if (forward) 1 else -1
    val animationSpec = advancedGlassNavigationSpringSpec()
    return slideInHorizontally(animationSpec) { fullWidth -> direction * fullWidth } togetherWith
        slideOutHorizontally(animationSpec) { fullWidth -> -direction * fullWidth }
}

internal fun isolatedAdvancedGlassVerticalTransition(
    forward: Boolean
): ContentTransform {
    val direction = if (forward) 1 else -1
    val animationSpec = advancedGlassNavigationSpringSpec()
    return slideInVertically(animationSpec) { fullHeight -> direction * fullHeight } togetherWith
        slideOutVertically(animationSpec) { fullHeight -> -direction * fullHeight }
}
