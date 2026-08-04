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
import androidx.compose.runtime.Immutable
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
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS
import moe.ouom.neriplayer.ui.effect.glass.DRAWER_NAVIGATION_CLOSE_DURATION_MS
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassNavigationOwner
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassMainTabTransitionSpec
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
internal data class MainTabGlassOwner(
    val route: String
)

internal enum class MainTabLayerScenePhase {
    Settled,
    Exiting,
    Entering
}

@Immutable
internal data class MainTabLayerScene(
    val route: String,
    val phase: MainTabLayerScenePhase,
    val direction: Int = 0,
    val transitionToken: Long = 0L,
    val glassOwner: MainTabGlassOwner = MainTabGlassOwner(route),
    val restored: Boolean = false,
    val restorationToken: Long = 0L
)

internal fun resolveMainTabLayerSceneOffsetFraction(
    phase: MainTabLayerScenePhase,
    direction: Int,
    progress: Float
): Float {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return when (phase) {
        MainTabLayerScenePhase.Settled -> 0f
        MainTabLayerScenePhase.Exiting -> -direction * clampedProgress
        MainTabLayerScenePhase.Entering -> direction * (1f - clampedProgress)
    }
}

internal val LocalMainTabSceneRestored = staticCompositionLocalOf { false }

internal fun shouldSuppressRestoredMainTabHostEntry(
    restoredEntry: Boolean,
    initialDepth: Int,
    targetDepth: Int
): Boolean = restoredEntry && targetDepth > initialDepth

internal fun resolveMainTabDetailInitialVisibility(
    restoredDetailVisibility: Boolean,
    initiallyVisible: Boolean
): Boolean = restoredDetailVisibility || initiallyVisible

@Composable
internal fun rememberMainTabSceneRestoredEntry(): Boolean =
    LocalMainTabSceneRestored.current

@Composable
internal fun rememberMainTabDetailVisibilityState(
    detailKey: Any?,
    initiallyVisible: Boolean = false
): MutableTransitionState<Boolean> {
    val restoredDetailVisibility = key(detailKey) {
        var wasVisibleBeforeTabSwitch by rememberSaveable { mutableStateOf(false) }
        val startsVisible = resolveMainTabDetailInitialVisibility(
            restoredDetailVisibility = wasVisibleBeforeTabSwitch,
            initiallyVisible = initiallyVisible
        )
        SideEffect {
            wasVisibleBeforeTabSwitch = true
        }
        startsVisible
    }
    return remember(detailKey, initiallyVisible) {
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
internal fun rememberMainTabLayerTransitionState(
    initialRoute: String
): MainTabLayerTransitionState {
    val scope = rememberCoroutineScope()
    val transitionState = remember(scope) {
        MainTabLayerTransitionState(
            controller = MainTabLayerTransitionController(scope, initialRoute),
            initialRoute = initialRoute
        )
    }
    DisposableEffect(transitionState) {
        onDispose(transitionState::dispose)
    }
    return transitionState
}

@Stable
internal class MainTabLayerTransitionState internal constructor(
    private val controller: MainTabLayerTransitionController,
    initialRoute: String
) {
    private val visitedRoutes = mutableSetOf(initialRoute)

    val visibleScenes: List<MainTabLayerScene>
        get() = controller.visibleScenes

    fun request(targetRoute: String) {
        controller.request(
            targetRoute = targetRoute,
            restored = !visitedRoutes.add(targetRoute)
        )
    }

    fun offsetFractionFor(scene: MainTabLayerScene): Float =
        controller.offsetFractionFor(scene)

    fun onContainerWidthChanged(widthPx: Int) {
        controller.onContainerWidthChanged(widthPx)
    }

    fun onInitialSceneFrameRendered() {
        controller.onInitialSceneFrameRendered()
    }

    fun onIncomingScenePrepared(transitionToken: Long) {
        controller.onIncomingScenePrepared(transitionToken)
    }

    fun consumeRestoredScene(restorationToken: Long) {
        controller.consumeRestoredScene(restorationToken)
    }

    fun dispose() {
        controller.dispose()
    }
}

@Composable
internal fun MainTabLayerHost(
    selectedRoute: String,
    modifier: Modifier = Modifier,
    onVisibleGlassOwnersChanged: (Set<MainTabGlassOwner>) -> Unit = {},
    content: @Composable (route: String) -> Unit
) {
    val transitionState = rememberMainTabLayerTransitionState(selectedRoute)
    MainTabLayerHost(
        selectedRoute = selectedRoute,
        transitionState = transitionState,
        modifier = modifier,
        onVisibleGlassOwnersChanged = onVisibleGlassOwnersChanged,
        content = content
    )
}

@Composable
internal fun MainTabLayerHost(
    selectedRoute: String,
    transitionState: MainTabLayerTransitionState,
    modifier: Modifier = Modifier,
    onVisibleGlassOwnersChanged: (Set<MainTabGlassOwner>) -> Unit = {},
    content: @Composable (route: String) -> Unit
) {
    LaunchedEffect(transitionState, selectedRoute) {
        transitionState.request(selectedRoute)
    }
    LaunchedEffect(transitionState) {
        withFrameNanos { }
        withFrameNanos { }
        transitionState.onInitialSceneFrameRendered()
    }
    val visibleScenes = transitionState.visibleScenes
    var widthPx by remember { mutableIntStateOf(0) }
    SideEffect {
        onVisibleGlassOwnersChanged(visibleScenes.mapTo(linkedSetOf()) { scene ->
            scene.glassOwner
        })
    }
    val saveableStateHolder = rememberSaveableStateHolder()
    AdvancedGlassNavigationHandoff(enabled = visibleScenes.size > 1) {
        Box(
            modifier = modifier
                .clipToBounds()
                .onSizeChanged { size ->
                    widthPx = size.width
                    transitionState.onContainerWidthChanged(size.width)
                }
        ) {
            visibleScenes.forEach { scene ->
                key(scene.route) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                IntOffset(
                                    x = (
                                        transitionState.offsetFractionFor(scene) * widthPx
                                    ).roundToInt(),
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
                                    transitionState.consumeRestoredScene(scene.restorationToken)
                                }
                            }
                            if (
                                scene.phase == MainTabLayerScenePhase.Entering &&
                                scene.transitionToken != 0L
                            ) {
                                LaunchedEffect(scene.transitionToken) {
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    transitionState.onIncomingScenePrepared(
                                        scene.transitionToken
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
    private var containerReady = false
    private var containerHasWidth = false
    private var initialSceneFrameRendered = false
    private var pendingTransitionStart: TransitionStart? = null
    private var awaitingIncomingScenePreparation = false
    private var hasStartedTabAnimation = false
    private var queuedTransitionRequest: TransitionRequest? = null

    val visibleScenes: List<MainTabLayerScene>
        get() {
            val fromRoute = fromRouteState
            if (!runningState || fromRoute == null || fromRoute == toRouteState) {
                return listOf(
                    MainTabLayerScene(
                        route = toRouteState,
                        phase = MainTabLayerScenePhase.Settled,
                        restored = targetSceneRestoredState,
                        restorationToken = targetSceneRestorationToken
                    )
                )
            }
            return listOf(
                MainTabLayerScene(
                    route = fromRoute,
                    phase = MainTabLayerScenePhase.Exiting,
                    direction = directionState,
                    restored = false
                ),
                MainTabLayerScene(
                    route = toRouteState,
                    phase = MainTabLayerScenePhase.Entering,
                    direction = directionState,
                    transitionToken = if (awaitingIncomingScenePreparation) {
                        generation
                    } else {
                        0L
                    },
                    restored = targetSceneRestoredState,
                    restorationToken = targetSceneRestorationToken
                )
            )
        }

    fun offsetFractionFor(scene: MainTabLayerScene): Float =
        resolveMainTabLayerSceneOffsetFraction(
            phase = scene.phase,
            direction = scene.direction,
            progress = progressState
        )

    fun request(targetRoute: String, restored: Boolean = false) {
        if (!containerReady) {
            if (pendingTransitionStart?.toRoute == targetRoute) return
            if (targetRoute == toRouteState) {
                pendingTransitionStart = null
                return
            }
            pendingTransitionStart = resolveNextTransition(targetRoute, restored)
            return
        }
        if (runningState) {
            if (
                awaitingIncomingScenePreparation &&
                progressState == 0f &&
                targetRoute == fromRouteState
            ) {
                cancelIncomingScenePreparation()
                return
            }
            if (targetRoute == toRouteState) {
                queuedTransitionRequest = null
                return
            }
            if (awaitingIncomingScenePreparation && progressState == 0f) {
                val next = resolveNextTransition(targetRoute, restored) ?: return
                startTransition(next)
            } else {
                queuedTransitionRequest = TransitionRequest(targetRoute, restored)
            }
            return
        }
        if (targetRoute == toRouteState && (!runningState || fromRouteState == null)) return
        val next = resolveNextTransition(targetRoute, restored) ?: return
        startTransition(next)
    }

    fun onContainerWidthChanged(widthPx: Int) {
        if (widthPx <= 0) return
        containerHasWidth = true
        startPendingTransitionIfReady()
    }

    fun onInitialSceneFrameRendered() {
        initialSceneFrameRendered = true
        startPendingTransitionIfReady()
    }

    fun onIncomingScenePrepared(transitionToken: Long) {
        if (
            !runningState ||
            !awaitingIncomingScenePreparation ||
            transitionToken != generation
        ) {
            return
        }
        awaitingIncomingScenePreparation = false
        launchTransition(transitionToken)
    }

    private fun launchTransition(requestGeneration: Long) {
        hasStartedTabAnimation = true
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

    private fun startPendingTransitionIfReady() {
        if (containerReady || !containerHasWidth || !initialSceneFrameRendered) return
        containerReady = true
        val pendingTransition = pendingTransitionStart ?: return
        pendingTransitionStart = null
        startTransition(pendingTransition)
    }

    private fun startTransition(next: TransitionStart) {
        val requestGeneration = ++generation
        transitionJob?.cancel()
        fromRouteState = next.fromRoute
        toRouteState = next.toRoute
        directionState = next.direction
        progressState = next.progress.coerceIn(0f, 1f)
        targetSceneRestoredState = next.restored
        targetSceneRestorationToken = requestGeneration
        awaitingIncomingScenePreparation = !hasStartedTabAnimation
        runningState = true
        transitionJob = null
        if (!awaitingIncomingScenePreparation) {
            launchTransition(requestGeneration)
        }
    }

    private fun cancelIncomingScenePreparation() {
        val currentFromRoute = fromRouteState ?: return
        generation++
        transitionJob?.cancel()
        transitionJob = null
        fromRouteState = null
        toRouteState = currentFromRoute
        progressState = 1f
        runningState = false
        awaitingIncomingScenePreparation = false
        queuedTransitionRequest = null
        targetSceneRestoredState = false
        targetSceneRestorationToken = 0L
    }

    fun dispose() {
        generation++
        transitionJob?.cancel()
        transitionJob = null
        runningState = false
        fromRouteState = null
        pendingTransitionStart = null
        containerReady = false
        containerHasWidth = false
        initialSceneFrameRendered = false
        awaitingIncomingScenePreparation = false
        hasStartedTabAnimation = false
        queuedTransitionRequest = null
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
            animationSpec = mainTabAnimationSpec()
        ) { value, _ ->
            if (requestGeneration == generation) {
                progressState = value
            }
        }
    }

    private fun mainTabAnimationSpec(): FiniteAnimationSpec<Float> =
        advancedGlassMainTabTransitionSpec(
            ADVANCED_GLASS_MAIN_TAB_TRANSITION_DURATION_MS
        )

    private fun settleAtTarget() {
        progressState = 1f
        fromRouteState = null
        runningState = false
        transitionJob = null
        awaitingIncomingScenePreparation = false
        targetSceneRestoredState = false
        val queuedRequest = queuedTransitionRequest ?: return
        queuedTransitionRequest = null
        if (queuedRequest.targetRoute == toRouteState) return
        val next = resolveNextTransition(
            targetRoute = queuedRequest.targetRoute,
            restored = queuedRequest.restored
        ) ?: return
        startTransition(next)
    }

    private data class TransitionStart(
        val fromRoute: String,
        val toRoute: String,
        val direction: Int,
        val progress: Float,
        val restored: Boolean
    )

    private data class TransitionRequest(
        val targetRoute: String,
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
}
