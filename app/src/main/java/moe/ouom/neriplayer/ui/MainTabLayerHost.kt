package moe.ouom.neriplayer.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS
import moe.ouom.neriplayer.ui.effect.glass.DRAWER_NAVIGATION_CLOSE_DURATION_MS
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassNavigationOwner
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassMainTabTransitionSpec
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class MainTabGlassOwner(
    val route: String
)

internal data class MainTabLayerScene(
    val route: String,
    val offsetFraction: Float,
    val glassOwner: MainTabGlassOwner = MainTabGlassOwner(route),
    val restored: Boolean = false,
    val restorationToken: Long = 0L
)

internal val LocalMainTabSceneRestored = staticCompositionLocalOf { false }

internal fun shouldSuppressRestoredMainTabHostEntry(
    restoredEntry: Boolean,
    initialDepth: Int,
    targetDepth: Int
): Boolean = restoredEntry && targetDepth > initialDepth

@Composable
internal fun rememberMainTabSceneRestoredEntry(): Boolean =
    LocalMainTabSceneRestored.current

@Composable
internal fun rememberMainTabDetailVisibilityState(
    detailKey: Any?
): MutableTransitionState<Boolean> {
    val restoredDetailVisibility = key(detailKey) {
        var wasVisibleBeforeTabSwitch by rememberSaveable { mutableStateOf(false) }
        val startsVisible = wasVisibleBeforeTabSwitch
        SideEffect {
            wasVisibleBeforeTabSwitch = true
        }
        startsVisible
    }
    return remember(detailKey) {
        MutableTransitionState(
            restoredDetailVisibility
        ).apply {
            targetState = true
        }
    }
}

@Composable
internal fun <S> Transition<S>.animateMainTabDetailCloseRootRevealFraction(
    navigationDepth: (S) -> Int,
    label: String
): Float {
    val revealFraction by animateFloat(
        transitionSpec = {
            tween(
                durationMillis = DRAWER_NAVIGATION_CLOSE_DURATION_MS,
                easing = FastOutSlowInEasing
            )
        },
        label = "${label}_root_reveal"
    ) { state ->
        if (navigationDepth(state) == 0) 1f else 0f
    }
    val closingToRoot = navigationDepth(currentState) > 0 &&
        navigationDepth(targetState) == 0
    return if (closingToRoot) revealFraction else 1f
}

internal fun Modifier.clipMainTabDetailCloseRoot(
    revealFraction: Float
): Modifier = drawWithContent {
    val revealBottom = size.height * revealFraction.coerceIn(0f, 1f)
    clipRect(bottom = revealBottom) {
        this@drawWithContent.drawContent()
    }
}

@Composable
internal fun MainTabLayerHost(
    selectedRoute: String,
    modifier: Modifier = Modifier,
    onVisibleGlassOwnersChanged: (Set<MainTabGlassOwner>) -> Unit = {},
    content: @Composable (route: String) -> Unit
) {
    val controller = rememberMainTabLayerTransitionController(selectedRoute)
    val visitedRoutes = remember { mutableSetOf(selectedRoute) }
    LaunchedEffect(controller, selectedRoute) {
        val restored = selectedRoute in visitedRoutes
        visitedRoutes += selectedRoute
        controller.request(
            targetRoute = selectedRoute,
            restored = restored
        )
    }
    val visibleScenes = controller.visibleScenes
    var widthPx by remember { mutableIntStateOf(0) }
    SideEffect {
        onVisibleGlassOwnersChanged(visibleScenes.mapTo(linkedSetOf()) { scene ->
            scene.glassOwner
        })
    }
    val saveableStateHolder = rememberSaveableStateHolder()
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size -> widthPx = size.width }
    ) {
        visibleScenes.forEach { scene ->
            key(scene.route) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                x = (scene.offsetFraction * widthPx).roundToInt(),
                                y = 0
                            )
                        }
                        .graphicsLayer()
                ) {
                    CompositionLocalProvider(
                        LocalAdvancedGlassNavigationOwner provides scene.glassOwner,
                        LocalMainTabSceneRestored provides scene.restored
                    ) {
                        saveableStateHolder.SaveableStateProvider(scene.route) {
                            content(scene.route)
                        }
                        if (scene.restored) {
                            LaunchedEffect(scene.restorationToken) {
                                withFrameNanos { }
                                controller.consumeRestoredScene(scene.restorationToken)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMainTabLayerTransitionController(
    initialRoute: String
): MainTabLayerTransitionController {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) {
        MainTabLayerTransitionController(scope, initialRoute)
    }
    DisposableEffect(controller) {
        onDispose(controller::dispose)
    }
    return controller
}

@Stable
internal class MainTabLayerTransitionController(
    private val scope: CoroutineScope,
    initialRoute: String
) {
    private var fromRouteState by mutableStateOf<String?>(null)
    private var toRouteState by mutableStateOf(initialRoute)
    private var directionState by mutableIntStateOf(1)
    private var progressState by mutableFloatStateOf(1f)
    private var runningState by mutableStateOf(false)
    private var targetSceneRestoredState by mutableStateOf(false)
    private var targetSceneRestorationToken = 0L
    private var transitionJob: Job? = null
    private var generation = 0L

    val visibleScenes: List<MainTabLayerScene>
        get() {
            val fromRoute = fromRouteState
            if (!runningState || fromRoute == null || fromRoute == toRouteState) {
                return listOf(
                    MainTabLayerScene(
                        route = toRouteState,
                        offsetFraction = 0f,
                        restored = targetSceneRestoredState,
                        restorationToken = targetSceneRestorationToken
                    )
                )
            }
            val direction = directionState.toFloat()
            return listOf(
                MainTabLayerScene(
                    route = fromRoute,
                    offsetFraction = -direction * progressState,
                    restored = false
                ),
                MainTabLayerScene(
                    route = toRouteState,
                    offsetFraction = direction * (1f - progressState),
                    restored = targetSceneRestoredState,
                    restorationToken = targetSceneRestorationToken
                )
            )
        }

    fun request(targetRoute: String, restored: Boolean = false) {
        if (targetRoute == toRouteState && (!runningState || fromRouteState == null)) return
        val next = resolveNextTransition(targetRoute, restored) ?: return
        val requestGeneration = ++generation
        transitionJob?.cancel()
        fromRouteState = next.fromRoute
        toRouteState = next.toRoute
        directionState = next.direction
        progressState = next.progress.coerceIn(0f, 1f)
        targetSceneRestoredState = next.restored
        targetSceneRestorationToken = requestGeneration
        runningState = true
        transitionJob = scope.launch {
            try {
                animateProgressToEnd(requestGeneration)
            } finally {
                if (requestGeneration == generation) {
                    settleAtTarget()
                }
            }
        }
    }

    fun dispose() {
        generation++
        transitionJob?.cancel()
        transitionJob = null
        runningState = false
        fromRouteState = null
        targetSceneRestoredState = false
        targetSceneRestorationToken = 0L
    }

    fun consumeRestoredScene(restorationToken: Long) {
        if (
            targetSceneRestoredState &&
            targetSceneRestorationToken == restorationToken
        ) {
            targetSceneRestoredState = false
        }
    }

    private fun resolveNextTransition(
        targetRoute: String,
        restored: Boolean
    ): TransitionStart? {
        val currentFromRoute = fromRouteState
        val currentToRoute = toRouteState
        if (!runningState || currentFromRoute == null || currentFromRoute == currentToRoute) {
            val direction = resolveMainTabTransitionDirection(currentToRoute, targetRoute)
                ?: return null
            return TransitionStart(
                fromRoute = currentToRoute,
                toRoute = targetRoute,
                direction = direction,
                progress = 0f,
                restored = restored
            )
        }
        if (targetRoute == currentToRoute) return null
        if (targetRoute == currentFromRoute) {
            return TransitionStart(
                fromRoute = currentToRoute,
                toRoute = currentFromRoute,
                direction = -directionState,
                progress = 1f - progressState,
                restored = restored
            )
        }

        val direction = directionState.toFloat()
        val candidates = listOf(
            RouteOffset(
                route = currentFromRoute,
                offsetFraction = -direction * progressState
            ),
            RouteOffset(
                route = currentToRoute,
                offsetFraction = direction * (1f - progressState)
            )
        )
        return candidates.mapNotNull { candidate ->
            val nextDirection = resolveMainTabTransitionDirection(
                initialRoute = candidate.route,
                targetRoute = targetRoute
            ) ?: return@mapNotNull null
            val nextProgress = (
                -candidate.offsetFraction / nextDirection.toFloat()
            ).coerceIn(0f, 1f)
            val projectedOffset = -nextDirection * nextProgress
            TransitionCandidate(
                start = TransitionStart(
                    fromRoute = candidate.route,
                    toRoute = targetRoute,
                    direction = nextDirection,
                    progress = nextProgress,
                    restored = restored
                ),
                snapDistance = abs(projectedOffset - candidate.offsetFraction),
                centerDistance = abs(candidate.offsetFraction)
            )
        }.minWithOrNull(
            compareBy<TransitionCandidate> { it.snapDistance }
                .thenBy { it.centerDistance }
        )?.start
    }

    private suspend fun animateProgressToEnd(requestGeneration: Long) {
        animate(
            initialValue = progressState,
            targetValue = 1f,
            animationSpec = remainingAnimationSpec()
        ) { value, _ ->
            if (requestGeneration == generation) {
                progressState = value
            }
        }
    }

    private fun remainingAnimationSpec(): FiniteAnimationSpec<Float> {
        val remainingFraction = (1f - progressState).coerceIn(0f, 1f)
        val duration = (
            ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS * remainingFraction
        ).roundToInt().coerceIn(
            minimumValue = MIN_INTERRUPTED_MAIN_TAB_TRANSITION_MS,
            maximumValue = ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS
        )
        return advancedGlassMainTabTransitionSpec(duration)
    }

    private fun settleAtTarget() {
        progressState = 1f
        fromRouteState = null
        runningState = false
        transitionJob = null
        targetSceneRestoredState = false
    }

    private data class TransitionStart(
        val fromRoute: String,
        val toRoute: String,
        val direction: Int,
        val progress: Float,
        val restored: Boolean
    )

    private data class RouteOffset(
        val route: String,
        val offsetFraction: Float
    )

    private data class TransitionCandidate(
        val start: TransitionStart,
        val snapDistance: Float,
        val centerDistance: Float
    )

    private companion object {
        const val MIN_INTERRUPTED_MAIN_TAB_TRANSITION_MS = 120
    }
}
