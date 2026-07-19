package moe.ouom.neriplayer.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
    val glassOwner: MainTabGlassOwner = MainTabGlassOwner(route)
)

@Composable
internal fun MainTabLayerHost(
    selectedRoute: String,
    modifier: Modifier = Modifier,
    onVisibleGlassOwnersChanged: (Set<MainTabGlassOwner>) -> Unit = {},
    content: @Composable (route: String) -> Unit
) {
    val controller = rememberMainTabLayerTransitionController(selectedRoute)
    LaunchedEffect(controller, selectedRoute) {
        controller.request(selectedRoute)
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
                        LocalAdvancedGlassNavigationOwner provides scene.glassOwner
                    ) {
                        saveableStateHolder.SaveableStateProvider(scene.route) {
                            content(scene.route)
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
    private var transitionJob: Job? = null
    private var generation = 0L

    val visibleScenes: List<MainTabLayerScene>
        get() {
            val fromRoute = fromRouteState
            if (!runningState || fromRoute == null || fromRoute == toRouteState) {
                return listOf(MainTabLayerScene(toRouteState, 0f))
            }
            val direction = directionState.toFloat()
            return listOf(
                MainTabLayerScene(
                    route = fromRoute,
                    offsetFraction = -direction * progressState
                ),
                MainTabLayerScene(
                    route = toRouteState,
                    offsetFraction = direction * (1f - progressState)
                )
            )
        }

    fun request(targetRoute: String) {
        if (targetRoute == toRouteState && (!runningState || fromRouteState == null)) return
        val next = resolveNextTransition(targetRoute) ?: return
        val requestGeneration = ++generation
        transitionJob?.cancel()
        fromRouteState = next.fromRoute
        toRouteState = next.toRoute
        directionState = next.direction
        progressState = next.progress.coerceIn(0f, 1f)
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
    }

    private fun resolveNextTransition(targetRoute: String): TransitionStart? {
        val currentFromRoute = fromRouteState
        val currentToRoute = toRouteState
        if (!runningState || currentFromRoute == null || currentFromRoute == currentToRoute) {
            val direction = resolveMainTabTransitionDirection(currentToRoute, targetRoute)
                ?: return null
            return TransitionStart(
                fromRoute = currentToRoute,
                toRoute = targetRoute,
                direction = direction,
                progress = 0f
            )
        }
        if (targetRoute == currentToRoute) return null
        if (targetRoute == currentFromRoute) {
            return TransitionStart(
                fromRoute = currentToRoute,
                toRoute = currentFromRoute,
                direction = -directionState,
                progress = 1f - progressState
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
                    progress = nextProgress
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
    }

    private data class TransitionStart(
        val fromRoute: String,
        val toRoute: String,
        val direction: Int,
        val progress: Float
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
