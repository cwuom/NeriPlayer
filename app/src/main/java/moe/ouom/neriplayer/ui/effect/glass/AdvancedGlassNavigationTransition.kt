package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
internal const val ADVANCED_GLASS_MAIN_TAB_EXIT_DURATION_MS = 70
internal const val ADVANCED_GLASS_MAIN_TAB_ENTER_DURATION_MS = 330
internal const val ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS =
    ADVANCED_GLASS_MAIN_TAB_EXIT_DURATION_MS +
        ADVANCED_GLASS_MAIN_TAB_ENTER_DURATION_MS
internal val ADVANCED_GLASS_MAIN_TAB_EXIT_EASING = CubicBezierEasing(
    0.4f,
    0f,
    0.2f,
    1f
)
internal val ADVANCED_GLASS_MAIN_TAB_ENTER_EASING = CubicBezierEasing(
    0.4f,
    0f,
    0.2f,
    1f
)

internal fun advancedGlassNavigationSpringSpec(): FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = ADVANCED_GLASS_NAVIGATION_DAMPING_RATIO,
    stiffness = ADVANCED_GLASS_NAVIGATION_STIFFNESS
)

internal fun <T> advancedGlassMainTabExitSpec(
    durationMillis: Int = ADVANCED_GLASS_MAIN_TAB_EXIT_DURATION_MS
): FiniteAnimationSpec<T> = tween(
    durationMillis = durationMillis,
    easing = ADVANCED_GLASS_MAIN_TAB_EXIT_EASING
)

internal fun <T> advancedGlassMainTabEnterSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = ADVANCED_GLASS_MAIN_TAB_ENTER_DURATION_MS,
    easing = ADVANCED_GLASS_MAIN_TAB_ENTER_EASING
)

internal fun <T> advancedGlassMainTabTransitionSpec(
    durationMillis: Int = ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS
): FiniteAnimationSpec<T> = tween(
    durationMillis = durationMillis,
    easing = ADVANCED_GLASS_MAIN_TAB_ENTER_EASING
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
