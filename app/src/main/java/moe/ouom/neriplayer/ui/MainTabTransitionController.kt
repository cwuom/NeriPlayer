package moe.ouom.neriplayer.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.effect.glass.ADVANCED_GLASS_MAIN_TAB_EXIT_DURATION_MS
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassMainTabEnterSpec
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassMainTabExitSpec
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
internal class MainTabTransitionController(
    private val scope: CoroutineScope,
    initialRoute: String?,
    initialEntryId: String?,
    private val onNavigate: (String) -> String?
) {
    private var offsetState by mutableFloatStateOf(0f)
    private var displayedRoute = initialRoute
    private var renderedRouteState by mutableStateOf(initialRoute)
    private var renderedEntryIdState by mutableStateOf(initialEntryId)
    private var requestedRoute: String? = null
    private var transitionJob: Job? = null
    private var generation = 0L
    private var runningState by mutableStateOf(false)

    internal val isRunning: Boolean
        get() = runningState

    internal val offsetFraction: Float
        get() = offsetState

    internal val renderedRoute: String?
        get() = renderedRouteState

    internal val renderedEntryId: String?
        get() = renderedEntryIdState

    internal fun request(targetRoute: String) {
        if (targetRoute == requestedRoute) return
        if (!runningState && targetRoute == displayedRoute) return

        val direction = resolveMainTabTransitionDirection(displayedRoute, targetRoute)
        if (targetRoute == displayedRoute) {
            requestedRoute = targetRoute
            val requestGeneration = ++generation
            cancelCurrentTransition()
            runningState = true
            transitionJob = scope.launch {
                try {
                    animateIn(requestGeneration)
                } finally {
                    if (requestGeneration == generation) {
                        runningState = false
                        requestedRoute = null
                        transitionJob = null
                    }
                }
            }
            return
        }
        if (direction == null) {
            val requestGeneration = ++generation
            cancelCurrentTransition()
            runningState = true
            transitionJob = scope.launch {
                try {
                    offsetState = 0f
                    displayedRoute = targetRoute
                    val targetEntryId = onNavigate(targetRoute)
                    renderedRouteState = targetRoute
                    renderedEntryIdState = targetEntryId
                } finally {
                    if (requestGeneration == generation) {
                        runningState = false
                        requestedRoute = null
                        transitionJob = null
                    }
                }
            }
            return
        }

        requestedRoute = targetRoute
        val requestGeneration = ++generation
        cancelCurrentTransition()
        runningState = true
        transitionJob = scope.launch {
            try {
                val activeDirection = resolveMainTabTransitionDirection(
                    initialRoute = displayedRoute,
                    targetRoute = targetRoute
                ) ?: return@launch
                val exitTarget = -activeDirection * MAIN_TAB_EDGE_OFFSET_FRACTION
                val remainingExitFraction = (
                    abs(exitTarget - offsetState) / MAIN_TAB_EDGE_OFFSET_FRACTION
                ).coerceIn(MAIN_TAB_MIN_EXIT_DURATION_FRACTION, 1f)
                val exitDurationMillis = (
                    ADVANCED_GLASS_MAIN_TAB_EXIT_DURATION_MS *
                        remainingExitFraction
                ).roundToInt()
                animateOffsetTo(
                    targetValue = exitTarget,
                    animationSpec = advancedGlassMainTabExitSpec(exitDurationMillis),
                    requestGeneration = requestGeneration
                )

                commitRouteAndAnimateIn(
                    targetRoute = targetRoute,
                    direction = activeDirection,
                    requestGeneration = requestGeneration
                )
            } finally {
                if (requestGeneration == generation) {
                    runningState = false
                    requestedRoute = null
                    transitionJob = null
                }
            }
        }
    }

    internal fun syncRoute(route: String?, entryId: String?) {
        if (route == null) return
        if (runningState) {
            if (route == displayedRoute || route == requestedRoute) {
                if (route == renderedRouteState && entryId != null) {
                    renderedEntryIdState = entryId
                }
                return
            }
            generation++
            cancelCurrentTransition()
            runningState = false
        }
        displayedRoute = route
        renderedRouteState = route
        renderedEntryIdState = entryId
        requestedRoute = null
        offsetState = 0f
    }

    internal fun dispose() {
        generation++
        cancelCurrentTransition()
        runningState = false
        requestedRoute = null
    }

    private fun cancelCurrentTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    private suspend fun commitRouteAndAnimateIn(
        targetRoute: String,
        direction: Int,
        requestGeneration: Long
    ) {
        offsetState = direction * MAIN_TAB_EDGE_OFFSET_FRACTION
        displayedRoute = targetRoute
        val targetEntryId = onNavigate(targetRoute)
        renderedRouteState = targetRoute
        renderedEntryIdState = targetEntryId
        animateIn(requestGeneration)
    }

    private suspend fun animateIn(requestGeneration: Long) {
        animateOffsetTo(
            targetValue = 0f,
            animationSpec = advancedGlassMainTabEnterSpec(),
            requestGeneration = requestGeneration
        )
    }

    private suspend fun animateOffsetTo(
        targetValue: Float,
        animationSpec: FiniteAnimationSpec<Float>,
        requestGeneration: Long
    ) {
        animate(
            initialValue = offsetState,
            targetValue = targetValue,
            animationSpec = animationSpec
        ) { value, _ ->
            if (requestGeneration == generation) {
                offsetState = value
            }
        }
    }
}

@Composable
internal fun rememberMainTabTransitionController(
    currentRoute: String?,
    currentEntryId: String?,
    initialRoute: String,
    onNavigate: (String) -> String?
): MainTabTransitionController {
    val scope = rememberCoroutineScope()
    val latestOnNavigate by rememberUpdatedState(onNavigate)
    val controller = remember(scope) {
        MainTabTransitionController(
            scope = scope,
            initialRoute = currentRoute ?: initialRoute,
            initialEntryId = currentEntryId,
            onNavigate = { route -> latestOnNavigate(route) }
        )
    }
    LaunchedEffect(controller, currentRoute, currentEntryId, controller.isRunning) {
        controller.syncRoute(currentRoute, currentEntryId)
    }
    DisposableEffect(controller) {
        onDispose(controller::dispose)
    }
    return controller
}

internal const val MAIN_TAB_EDGE_OFFSET_FRACTION = 0.14f
private const val MAIN_TAB_MIN_EXIT_DURATION_FRACTION = 0.25f
