package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

internal fun buildAdvancedGlassDrawerTransition(
    forward: Boolean,
    retainedExit: ExitTransition = ExitTransition.None
): ContentTransform {
    val durationMillis = if (forward) {
        DRAWER_NAVIGATION_OPEN_DURATION_MS
    } else {
        DRAWER_NAVIGATION_CLOSE_DURATION_MS
    }
    val animationSpec = tween<Float>(
        durationMillis = durationMillis,
        easing = FastOutSlowInEasing
    )
    return ContentTransform(
        targetContentEnter = fadeIn(
            initialAlpha = DRAWER_ROOT_RETAIN_ALPHA,
            animationSpec = animationSpec
        ),
        initialContentExit = retainedExit,
        targetContentZIndex = if (forward) 1f else -1f
    )
}

internal data class AdvancedGlassSceneMotion(
    val revealTopFraction: Float,
    val contentTranslationYFraction: Float,
    val contentScale: Float
) {
    companion object {
        val None = AdvancedGlassSceneMotion(
            revealTopFraction = 0f,
            contentTranslationYFraction = 0f,
            contentScale = 1f
        )
    }
}

@Composable
internal fun <S> Transition<S>.animateAdvancedGlassSceneMotion(
    sceneState: S,
    coherentFeedbackEnabled: Boolean,
    navigationDepth: (S) -> Int,
    label: String
): AdvancedGlassSceneMotion {
    if (coherentFeedbackEnabled) {
        return AdvancedGlassSceneMotion.None
    }
    val revealTopFraction by animateFloat(
        transitionSpec = {
            val durationMillis = if (
                navigationDepth(targetState) > navigationDepth(initialState)
            ) {
                DRAWER_NAVIGATION_OPEN_DURATION_MS
            } else {
                DRAWER_NAVIGATION_CLOSE_DURATION_MS
            }
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        },
        label = "${label}_reveal"
    ) { transitionState ->
        resolveAdvancedGlassDrawerSceneMotion(
            sceneState = sceneState,
            activeState = transitionState,
            navigationDepth = navigationDepth
        ).revealTopFraction
    }
    val contentTranslationYFraction by animateFloat(
        transitionSpec = {
            val durationMillis = if (
                navigationDepth(targetState) > navigationDepth(initialState)
            ) {
                DRAWER_NAVIGATION_OPEN_DURATION_MS
            } else {
                DRAWER_NAVIGATION_CLOSE_DURATION_MS
            }
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        },
        label = "${label}_content_translation"
    ) { transitionState ->
        resolveAdvancedGlassDrawerSceneMotion(
            sceneState = sceneState,
            activeState = transitionState,
            navigationDepth = navigationDepth
        ).contentTranslationYFraction
    }
    val contentScale by animateFloat(
        transitionSpec = {
            val durationMillis = if (
                navigationDepth(targetState) > navigationDepth(initialState)
            ) {
                DRAWER_NAVIGATION_OPEN_DURATION_MS
            } else {
                DRAWER_NAVIGATION_CLOSE_DURATION_MS
            }
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        },
        label = "${label}_content_scale"
    ) { transitionState ->
        resolveAdvancedGlassDrawerSceneMotion(
            sceneState = sceneState,
            activeState = transitionState,
            navigationDepth = navigationDepth
        ).contentScale
    }
    return AdvancedGlassSceneMotion(
        revealTopFraction = revealTopFraction,
        contentTranslationYFraction = contentTranslationYFraction,
        contentScale = contentScale
    )
}

internal fun <S> resolveAdvancedGlassDrawerSceneMotion(
    sceneState: S,
    activeState: S,
    navigationDepth: (S) -> Int
): AdvancedGlassSceneMotion {
    if (sceneState == activeState) {
        return AdvancedGlassSceneMotion.None
    }
    return when {
        navigationDepth(sceneState) < navigationDepth(activeState) -> AdvancedGlassSceneMotion(
            revealTopFraction = 0f,
            contentTranslationYFraction = DRAWER_BACKGROUND_SINK_FRACTION,
            contentScale = DRAWER_RECESSED_CONTENT_SCALE
        )
        else -> AdvancedGlassSceneMotion(
            revealTopFraction = 1f,
            contentTranslationYFraction = 1f,
            contentScale = 1f
        )
    }
}

@Composable
internal fun Transition<EnterExitState>.animateAdvancedGlassVisibilitySceneMotion(
    coherentFeedbackEnabled: Boolean,
    enteringFromDeeperScene: Boolean,
    exitingToDeeperScene: Boolean,
    label: String
): AdvancedGlassSceneMotion {
    if (coherentFeedbackEnabled) {
        return AdvancedGlassSceneMotion.None
    }
    val opening = when {
        targetState == EnterExitState.Visible -> !enteringFromDeeperScene
        targetState == EnterExitState.PostExit -> exitingToDeeperScene
        else -> true
    }
    val durationMillis = if (opening) {
        DRAWER_NAVIGATION_OPEN_DURATION_MS
    } else {
        DRAWER_NAVIGATION_CLOSE_DURATION_MS
    }
    val revealTopFraction by animateFloat(
        transitionSpec = {
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        },
        label = "${label}_reveal"
    ) { state ->
        resolveAdvancedGlassVisibilitySceneMotion(
            state = state,
            enteringFromDeeperScene = enteringFromDeeperScene,
            exitingToDeeperScene = exitingToDeeperScene
        ).revealTopFraction
    }
    val contentTranslationYFraction by animateFloat(
        transitionSpec = {
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        },
        label = "${label}_content_translation"
    ) { state ->
        resolveAdvancedGlassVisibilitySceneMotion(
            state = state,
            enteringFromDeeperScene = enteringFromDeeperScene,
            exitingToDeeperScene = exitingToDeeperScene
        ).contentTranslationYFraction
    }
    val contentScale by animateFloat(
        transitionSpec = {
            tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        },
        label = "${label}_content_scale"
    ) { state ->
        resolveAdvancedGlassVisibilitySceneMotion(
            state = state,
            enteringFromDeeperScene = enteringFromDeeperScene,
            exitingToDeeperScene = exitingToDeeperScene
        ).contentScale
    }
    return AdvancedGlassSceneMotion(
        revealTopFraction = revealTopFraction,
        contentTranslationYFraction = contentTranslationYFraction,
        contentScale = contentScale
    )
}

internal fun resolveAdvancedGlassVisibilitySceneMotion(
    state: EnterExitState,
    enteringFromDeeperScene: Boolean,
    exitingToDeeperScene: Boolean
): AdvancedGlassSceneMotion = when (state) {
    EnterExitState.Visible -> AdvancedGlassSceneMotion.None
    EnterExitState.PreEnter -> if (enteringFromDeeperScene) {
        AdvancedGlassSceneMotion(
            revealTopFraction = 0f,
            contentTranslationYFraction = DRAWER_BACKGROUND_SINK_FRACTION,
            contentScale = DRAWER_RECESSED_CONTENT_SCALE
        )
    } else {
        AdvancedGlassSceneMotion(
            revealTopFraction = 1f,
            contentTranslationYFraction = 1f,
            contentScale = 1f
        )
    }
    EnterExitState.PostExit -> if (exitingToDeeperScene) {
        AdvancedGlassSceneMotion(
            revealTopFraction = 0f,
            contentTranslationYFraction = DRAWER_BACKGROUND_SINK_FRACTION,
            contentScale = DRAWER_RECESSED_CONTENT_SCALE
        )
    } else {
        AdvancedGlassSceneMotion(
            revealTopFraction = 1f,
            contentTranslationYFraction = 1f,
            contentScale = 1f
        )
    }
}

internal fun <S> AnimatedContentTransitionScope<S>.advancedGlassHostNavigationTransition(
    forward: Boolean,
    coherentFeedbackEnabled: Boolean
): ContentTransform = if (coherentFeedbackEnabled) {
    isolatedAdvancedGlassVerticalTransition(forward)
} else {
    buildAdvancedGlassDrawerTransition(
        forward = forward,
        retainedExit = ExitTransition.KeepUntilTransitionsFinished
    )
}

internal const val DRAWER_NAVIGATION_OPEN_DURATION_MS = 300
internal const val DRAWER_NAVIGATION_CLOSE_DURATION_MS = 280
internal const val DRAWER_BACKGROUND_SINK_FRACTION = 0f
internal const val DRAWER_RECESSED_CONTENT_SCALE = 0.98f
private const val DRAWER_ROOT_RETAIN_ALPHA = 0.999f
