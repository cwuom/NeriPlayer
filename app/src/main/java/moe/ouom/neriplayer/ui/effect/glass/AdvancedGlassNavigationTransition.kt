package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

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

/**
 * iOS push between pages that bring their own opaque background: the new page slides in over the
 * full width on top while the old one drifts a third of the way left, and popping reverses it
 */
internal fun iosPushContentTransform(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(
        durationMillis = if (forward) IOS_PUSH_OPEN_DURATION_MS else IOS_PUSH_CLOSE_DURATION_MS,
        easing = IOS_PUSH_EASING
    )
    val underlayOffset = { width: Int -> (width * IOS_PUSH_UNDERLAY_SHIFT_FRACTION).roundToInt() }
    return if (forward) {
        ContentTransform(
            targetContentEnter = slideInHorizontally(spec) { it },
            initialContentExit = slideOutHorizontally(spec, underlayOffset),
            targetContentZIndex = 1f
        )
    } else {
        ContentTransform(
            targetContentEnter = slideInHorizontally(spec, underlayOffset),
            initialContentExit = slideOutHorizontally(spec) { it },
            targetContentZIndex = -1f
        )
    }
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
    retainedExit: ExitTransition = ExitTransition.None,
    targetContentZIndex: Float = if (forward) 1f else -1f
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
        targetContentZIndex = targetContentZIndex
    )
}

internal data class AdvancedGlassSceneMotion(
    val revealTopFraction: Float,
    val contentTranslationYFraction: Float,
    val contentScale: Float,
    /** Whole-scene horizontal shift (background included) as a fraction of its width. */
    val sceneTranslationXFraction: Float = 0f,
    /** Black scrim over the whole scene, used while it sits under a pushed page. */
    val sceneDim: Float = 0f
) {
    companion object {
        val None = AdvancedGlassSceneMotion(
            revealTopFraction = 0f,
            contentTranslationYFraction = 0f,
            contentScale = 1f
        )
    }
}

/*
 * iOS push: the new page slides in over the full width while the page underneath drifts a third
 * of the way left and dims a little; popping reverses it. Used for the drawer (non-coherent)
 * navigation style when the clean style runs with enhanced visual effects
 */
internal const val IOS_PUSH_UNDERLAY_SHIFT_FRACTION = -0.3f
internal const val IOS_PUSH_UNDERLAY_DIM = 0.1f
internal const val IOS_PUSH_OPEN_DURATION_MS = 420
internal const val IOS_PUSH_CLOSE_DURATION_MS = 380
internal val IOS_PUSH_EASING = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * Back gestures seek the push transition, and seeking runs through [IOS_PUSH_EASING]; feeding the
 * gesture progress through this inverse first keeps the page glued to the finger, as on iOS
 */
internal val IOS_PUSH_GESTURE_EASING: Easing = InverseEasing(IOS_PUSH_EASING)

/** Inverse of a monotonic easing on [0, 1], solved by bisection. */
internal class InverseEasing(private val easing: Easing) : Easing {
    override fun transform(fraction: Float): Float {
        val target = fraction.coerceIn(0f, 1f)
        // 曲线两端很平，二分到不了精确端点；端点直接给出，保证拖满即完成
        if (target <= 0f || target >= 1f) return target
        var low = 0f
        var high = 1f
        repeat(InverseEasingIterations) {
            val mid = (low + high) / 2f
            if (easing.transform(mid) < target) low = mid else high = mid
        }
        return (low + high) / 2f
    }
}

private const val InverseEasingIterations = 24

private val IosPushIncomingMotion = AdvancedGlassSceneMotion(
    revealTopFraction = 0f,
    contentTranslationYFraction = 0f,
    contentScale = 1f,
    sceneTranslationXFraction = 1f
)
private val IosPushUnderlayMotion = AdvancedGlassSceneMotion(
    revealTopFraction = 0f,
    contentTranslationYFraction = 0f,
    contentScale = 1f,
    sceneTranslationXFraction = IOS_PUSH_UNDERLAY_SHIFT_FRACTION,
    sceneDim = IOS_PUSH_UNDERLAY_DIM
)

internal fun sceneMotionSpec(opening: Boolean, iosPush: Boolean): FiniteAnimationSpec<Float> = when {
    iosPush -> tween(
        durationMillis = if (opening) IOS_PUSH_OPEN_DURATION_MS else IOS_PUSH_CLOSE_DURATION_MS,
        easing = IOS_PUSH_EASING
    )
    else -> tween(
        durationMillis = if (opening) DRAWER_NAVIGATION_OPEN_DURATION_MS else DRAWER_NAVIGATION_CLOSE_DURATION_MS,
        easing = FastOutSlowInEasing
    )
}

internal fun resolveAdvancedGlassSceneZIndex(navigationDepth: Int): Float =
    navigationDepth.coerceAtLeast(0).toFloat()

internal fun Modifier.advancedGlassSceneZIndex(navigationDepth: Int): Modifier =
    zIndex(resolveAdvancedGlassSceneZIndex(navigationDepth))

/** Animates every scene motion channel with one spec; equal endpoints make a channel free. */
@Composable
private fun <S> Transition<S>.animateSceneMotionChannels(
    spec: Transition.Segment<S>.() -> FiniteAnimationSpec<Float>,
    label: String,
    resolve: (S) -> AdvancedGlassSceneMotion
): AdvancedGlassSceneMotion {
    val revealTopFraction by animateFloat({ spec() }, "${label}_reveal") { resolve(it).revealTopFraction }
    val contentTranslationYFraction by animateFloat({ spec() }, "${label}_content_translation") {
        resolve(it).contentTranslationYFraction
    }
    val contentScale by animateFloat({ spec() }, "${label}_content_scale") { resolve(it).contentScale }
    val sceneTranslationXFraction by animateFloat({ spec() }, "${label}_scene_translation_x") {
        resolve(it).sceneTranslationXFraction
    }
    val sceneDim by animateFloat({ spec() }, "${label}_scene_dim") { resolve(it).sceneDim }
    return AdvancedGlassSceneMotion(
        revealTopFraction = revealTopFraction,
        contentTranslationYFraction = contentTranslationYFraction,
        contentScale = contentScale,
        sceneTranslationXFraction = sceneTranslationXFraction,
        sceneDim = sceneDim
    )
}

@Composable
internal fun <S> Transition<S>.animateAdvancedGlassSceneMotion(
    sceneState: S,
    coherentFeedbackEnabled: Boolean,
    navigationDepth: (S) -> Int,
    label: String,
    iosPush: Boolean = false
): AdvancedGlassSceneMotion {
    if (coherentFeedbackEnabled) {
        return AdvancedGlassSceneMotion.None
    }
    return animateSceneMotionChannels(
        spec = { sceneMotionSpec(navigationDepth(targetState) > navigationDepth(initialState), iosPush) },
        label = label
    ) { transitionState ->
        resolveAdvancedGlassDrawerSceneMotion(
            sceneState = sceneState,
            activeState = transitionState,
            navigationDepth = navigationDepth,
            iosPush = iosPush
        )
    }
}

internal fun <S> resolveAdvancedGlassDrawerSceneMotion(
    sceneState: S,
    activeState: S,
    navigationDepth: (S) -> Int,
    iosPush: Boolean = false
): AdvancedGlassSceneMotion {
    if (sceneState == activeState) {
        return AdvancedGlassSceneMotion.None
    }
    val underlay = navigationDepth(sceneState) < navigationDepth(activeState)
    return when {
        iosPush -> if (underlay) IosPushUnderlayMotion else IosPushIncomingMotion
        underlay -> AdvancedGlassSceneMotion(
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
    // 详情页保持抽屉式转场，iOS 推入只用于设置的多级页面
    val spec = sceneMotionSpec(opening, iosPush = false)
    return animateSceneMotionChannels(spec = { spec }, label = label) { state ->
        resolveAdvancedGlassVisibilitySceneMotion(
            state = state,
            enteringFromDeeperScene = enteringFromDeeperScene,
            exitingToDeeperScene = exitingToDeeperScene
        )
    }
}

internal fun resolveAdvancedGlassVisibilitySceneMotion(
    state: EnterExitState,
    enteringFromDeeperScene: Boolean,
    exitingToDeeperScene: Boolean,
    iosPush: Boolean = false
): AdvancedGlassSceneMotion {
    val underlay = when (state) {
        EnterExitState.Visible -> return AdvancedGlassSceneMotion.None
        EnterExitState.PreEnter -> enteringFromDeeperScene
        EnterExitState.PostExit -> exitingToDeeperScene
    }
    return when {
        iosPush -> if (underlay) IosPushUnderlayMotion else IosPushIncomingMotion
        underlay -> AdvancedGlassSceneMotion(
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

internal fun <S> AnimatedContentTransitionScope<S>.advancedGlassHostNavigationTransition(
    forward: Boolean,
    coherentFeedbackEnabled: Boolean,
    targetContentZIndex: Float = if (forward) 1f else -1f
): ContentTransform = if (coherentFeedbackEnabled) {
    isolatedAdvancedGlassVerticalTransition(forward)
} else {
    buildAdvancedGlassDrawerTransition(
        forward = forward,
        retainedExit = ExitTransition.KeepUntilTransitionsFinished,
        targetContentZIndex = targetContentZIndex
    )
}

internal const val DRAWER_NAVIGATION_OPEN_DURATION_MS = 300
internal const val DRAWER_NAVIGATION_CLOSE_DURATION_MS = 280
internal const val DRAWER_BACKGROUND_SINK_FRACTION = 0f
internal const val DRAWER_RECESSED_CONTENT_SCALE = 0.98f
private const val DRAWER_ROOT_RETAIN_ALPHA = 0.999f
