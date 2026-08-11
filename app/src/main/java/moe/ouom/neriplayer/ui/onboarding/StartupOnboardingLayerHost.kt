package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal const val STARTUP_ONBOARDING_STEP_ENTER_DURATION_MS = 360
internal const val STARTUP_ONBOARDING_STEP_EXIT_DURATION_MS = 300
internal const val STARTUP_ONBOARDING_STEP_ENTER_FADE_DURATION_MS = 280
internal const val STARTUP_ONBOARDING_STEP_EXIT_FADE_DURATION_MS = 220
internal const val STARTUP_ONBOARDING_GLASS_OWNER_HANDOFF_PROGRESS = 0.5f

internal enum class StartupOnboardingLayerScenePhase {
    Settled,
    Exiting,
    Entering
}

@Immutable
internal data class StartupOnboardingLayerScene(
    val stepIndex: Int,
    val phase: StartupOnboardingLayerScenePhase,
    val direction: Int = 0,
    val transitionToken: Long = 0L,
    val preparing: Boolean = false
)

@Immutable
internal data class StartupOnboardingLayerSceneMotion(
    val offset: IntOffset,
    val alpha: Float
)

@Immutable
internal data class StartupOnboardingLayerTransitionProgress(
    val enteringTranslation: Float,
    val exitingTranslation: Float,
    val enteringAlpha: Float,
    val exitingAlpha: Float
)

internal fun reverseStartupOnboardingLayerTransitionProgress(
    enteringTranslationProgress: Float,
    exitingTranslationProgress: Float,
    enteringAlphaProgress: Float,
    exitingAlphaProgress: Float
): StartupOnboardingLayerTransitionProgress {
    val enteringTranslation = enteringTranslationProgress.coerceIn(0f, 1f)
    val exitingTranslation = exitingTranslationProgress.coerceIn(0f, 1f)
    val enteringAlpha = enteringAlphaProgress.coerceIn(0f, 1f)
    val exitingAlpha = exitingAlphaProgress.coerceIn(0f, 1f)
    return StartupOnboardingLayerTransitionProgress(
        enteringTranslation = (1f - exitingTranslation * 5f / 7f)
            .coerceIn(0f, 1f),
        exitingTranslation = ((1f - enteringTranslation) * 7f / 5f)
            .coerceIn(0f, 1f),
        enteringAlpha = 1f - exitingAlpha,
        exitingAlpha = 1f - enteringAlpha
    )
}

internal fun resolveStartupOnboardingGlassOwnerStepIndex(
    fromStepIndex: Int?,
    toStepIndex: Int,
    enteringAlphaProgress: Float,
    running: Boolean,
    preparingIncomingScene: Boolean
): Int {
    if (!running || fromStepIndex == null) {
        return toStepIndex
    }
    if (preparingIncomingScene) return fromStepIndex
    return if (
        enteringAlphaProgress >= STARTUP_ONBOARDING_GLASS_OWNER_HANDOFF_PROGRESS
    ) {
        toStepIndex
    } else {
        fromStepIndex
    }
}

internal fun resolveStartupOnboardingLayerSceneMotion(
    scene: StartupOnboardingLayerScene,
    widthPx: Int,
    enteringTranslationProgress: Float,
    exitingTranslationProgress: Float,
    enteringAlphaProgress: Float,
    exitingAlphaProgress: Float
): StartupOnboardingLayerSceneMotion {
    val safeWidth = widthPx.coerceAtLeast(0)
    val direction = if (scene.direction < 0) -1 else 1
    return when (scene.phase) {
        StartupOnboardingLayerScenePhase.Settled -> {
            StartupOnboardingLayerSceneMotion(IntOffset.Zero, 1f)
        }

        StartupOnboardingLayerScenePhase.Entering -> {
            val enterDistance = safeWidth / 5
            val offsetX = if (scene.preparing) {
                direction * safeWidth
            } else {
                (
                    direction * enterDistance *
                        (1f - enteringTranslationProgress.coerceIn(0f, 1f))
                    ).roundToInt()
            }
            StartupOnboardingLayerSceneMotion(
                offset = IntOffset(offsetX, 0),
                alpha = if (scene.preparing) 0f else {
                    enteringAlphaProgress.coerceIn(0f, 1f)
                }
            )
        }

        StartupOnboardingLayerScenePhase.Exiting -> {
            val exitDistance = safeWidth / 7
            StartupOnboardingLayerSceneMotion(
                offset = IntOffset(
                    (
                        -direction * exitDistance *
                            exitingTranslationProgress.coerceIn(0f, 1f)
                        ).roundToInt(),
                    0
                ),
                alpha = 1f - exitingAlphaProgress.coerceIn(0f, 1f)
            )
        }
    }
}

@Composable
internal fun rememberStartupOnboardingLayerTransitionState(
    initialStepIndex: Int
): StartupOnboardingLayerTransitionState {
    val scope = rememberCoroutineScope()
    val transitionState = remember(scope) {
        StartupOnboardingLayerTransitionState(
            controller = StartupOnboardingLayerTransitionController(
                scope = scope,
                initialStepIndex = initialStepIndex
            )
        )
    }
    DisposableEffect(transitionState) {
        onDispose(transitionState::dispose)
    }
    return transitionState
}

@Stable
internal class StartupOnboardingLayerTransitionState internal constructor(
    private val controller: StartupOnboardingLayerTransitionController
) {
    val isRunning: Boolean
        get() = controller.isRunning

    fun canReverseTo(targetStepIndex: Int): Boolean =
        controller.canReverseTo(targetStepIndex)

    val visibleScenes: List<StartupOnboardingLayerScene>
        get() = controller.visibleScenes

    val activeGlassStepIndex: Int
        get() = controller.activeGlassStepIndex

    fun request(targetStepIndex: Int) {
        controller.request(targetStepIndex)
    }

    fun onContainerWidthChanged(widthPx: Int) {
        controller.onContainerWidthChanged(widthPx)
    }

    fun onInitialSceneFrameRendered() {
        controller.onInitialSceneFrameRendered()
    }

    fun onIncomingScenePrepared(transitionToken: Long) {
        controller.onIncomingScenePrepared(transitionToken)
    }

    fun motionFor(
        scene: StartupOnboardingLayerScene,
        widthPx: Int
    ): StartupOnboardingLayerSceneMotion = controller.motionFor(scene, widthPx)

    fun dispose() {
        controller.dispose()
    }
}

@Composable
internal fun StartupOnboardingLayerHost(
    transitionState: StartupOnboardingLayerTransitionState,
    modifier: Modifier = Modifier,
    content: @Composable (StartupOnboardingLayerScene) -> Unit
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val visibleScenes = transitionState.visibleScenes

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                widthPx = size.width
                transitionState.onContainerWidthChanged(size.width)
            }
    ) {
        LaunchedEffect(transitionState) {
            withFrameNanos { }
            withFrameNanos { }
            transitionState.onInitialSceneFrameRendered()
        }
        visibleScenes.forEach { scene ->
            key(scene.stepIndex) {
                val motion = transitionState.motionFor(scene, widthPx)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { motion.offset }
                        .graphicsLayer {
                            alpha = motion.alpha
                        }
                ) {
                    content(scene)
                }
                if (
                    scene.phase == StartupOnboardingLayerScenePhase.Entering &&
                    scene.preparing &&
                    scene.transitionToken != 0L
                ) {
                    LaunchedEffect(scene.transitionToken) {
                        withFrameNanos { }
                        withFrameNanos { }
                        transitionState.onIncomingScenePrepared(scene.transitionToken)
                    }
                }
            }
        }
    }
}

@Stable
internal class StartupOnboardingLayerTransitionController(
    private val scope: CoroutineScope,
    initialStepIndex: Int
) {
    private var fromStepIndex by mutableStateOf<Int?>(null)
    private var toStepIndex by mutableIntStateOf(initialStepIndex)
    private var direction by mutableIntStateOf(1)
    private var enteringTranslationProgress by mutableFloatStateOf(1f)
    private var exitingTranslationProgress by mutableFloatStateOf(1f)
    private var enteringAlphaProgress by mutableFloatStateOf(1f)
    private var exitingAlphaProgress by mutableFloatStateOf(1f)
    private var running by mutableStateOf(false)
    private var awaitingIncomingScenePreparation by mutableStateOf(false)
    private var containerReady = false
    private var pendingStepIndex: Int? = null
    private var queuedStepIndex: Int? = null
    private var transitionJob: Job? = null
    private var generation = 0L
    private var containerHasWidth = false
    private var initialSceneFrameRendered = false

    val isRunning: Boolean
        get() = running

    fun canReverseTo(targetStepIndex: Int): Boolean =
        running && fromStepIndex == targetStepIndex

    val activeGlassStepIndex: Int
        get() = resolveStartupOnboardingGlassOwnerStepIndex(
            fromStepIndex = fromStepIndex,
            toStepIndex = toStepIndex,
            enteringAlphaProgress = enteringAlphaProgress,
            running = running,
            preparingIncomingScene = awaitingIncomingScenePreparation
        )

    val visibleScenes: List<StartupOnboardingLayerScene>
        get() {
            val from = fromStepIndex
            if (!running || from == null || from == toStepIndex) {
                return listOf(
                    StartupOnboardingLayerScene(
                        stepIndex = toStepIndex,
                        phase = StartupOnboardingLayerScenePhase.Settled
                    )
                )
            }
            return listOf(
                StartupOnboardingLayerScene(
                    stepIndex = from,
                    phase = StartupOnboardingLayerScenePhase.Exiting,
                    direction = direction
                ),
                StartupOnboardingLayerScene(
                    stepIndex = toStepIndex,
                    phase = StartupOnboardingLayerScenePhase.Entering,
                    direction = direction,
                    transitionToken = if (awaitingIncomingScenePreparation) {
                        generation
                    } else {
                        0L
                    },
                    preparing = awaitingIncomingScenePreparation
                )
            )
        }

    fun request(targetStepIndex: Int) {
        if (!containerReady) {
            if (targetStepIndex == toStepIndex) {
                pendingStepIndex = null
                return
            }
            pendingStepIndex = targetStepIndex
            return
        }
        if (!running) {
            startTransition(targetStepIndex)
            return
        }
        if (targetStepIndex == toStepIndex) {
            queuedStepIndex = null
            return
        }
        if (awaitingIncomingScenePreparation) {
            if (targetStepIndex == fromStepIndex) {
                cancelIncomingScenePreparation()
            } else {
                replaceIncomingScenePreparation(targetStepIndex)
            }
            return
        }
        if (targetStepIndex == fromStepIndex) {
            reverseRunningTransition()
            return
        }
        queuedStepIndex = targetStepIndex
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
            !running ||
            !awaitingIncomingScenePreparation ||
            transitionToken != generation
        ) {
            return
        }
        awaitingIncomingScenePreparation = false
        launchTransition(transitionToken)
    }

    fun motionFor(
        scene: StartupOnboardingLayerScene,
        widthPx: Int
    ): StartupOnboardingLayerSceneMotion = resolveStartupOnboardingLayerSceneMotion(
        scene = scene,
        widthPx = widthPx,
        enteringTranslationProgress = enteringTranslationProgress,
        exitingTranslationProgress = exitingTranslationProgress,
        enteringAlphaProgress = enteringAlphaProgress,
        exitingAlphaProgress = exitingAlphaProgress
    )

    fun dispose() {
        generation++
        transitionJob?.cancel()
        transitionJob = null
        fromStepIndex = null
        running = false
        awaitingIncomingScenePreparation = false
        pendingStepIndex = null
        queuedStepIndex = null
        containerHasWidth = false
        initialSceneFrameRendered = false
    }

    private fun startPendingTransitionIfReady() {
        if (containerReady || !containerHasWidth || !initialSceneFrameRendered) return
        containerReady = true
        val pending = pendingStepIndex ?: return
        pendingStepIndex = null
        if (pending != toStepIndex) {
            startTransition(pending)
        }
    }

    private fun startTransition(targetStepIndex: Int) {
        if (targetStepIndex == toStepIndex && !running) return
        val currentStepIndex = toStepIndex
        val transitionDirection = if (targetStepIndex > currentStepIndex) 1 else -1
        generation++
        transitionJob?.cancel()
        transitionJob = null
        fromStepIndex = currentStepIndex
        toStepIndex = targetStepIndex
        direction = transitionDirection
        enteringTranslationProgress = 0f
        exitingTranslationProgress = 0f
        enteringAlphaProgress = 0f
        exitingAlphaProgress = 0f
        awaitingIncomingScenePreparation = true
        running = true
    }

    private fun cancelIncomingScenePreparation() {
        val previousStepIndex = fromStepIndex ?: return
        generation++
        transitionJob?.cancel()
        transitionJob = null
        fromStepIndex = null
        toStepIndex = previousStepIndex
        enteringTranslationProgress = 1f
        exitingTranslationProgress = 1f
        enteringAlphaProgress = 1f
        exitingAlphaProgress = 1f
        running = false
        awaitingIncomingScenePreparation = false
        queuedStepIndex = null
    }

    private fun replaceIncomingScenePreparation(targetStepIndex: Int) {
        val currentFromStepIndex = fromStepIndex ?: return
        generation++
        transitionJob?.cancel()
        transitionJob = null
        toStepIndex = targetStepIndex
        direction = if (targetStepIndex > currentFromStepIndex) 1 else -1
        enteringTranslationProgress = 0f
        exitingTranslationProgress = 0f
        enteringAlphaProgress = 0f
        exitingAlphaProgress = 0f
        awaitingIncomingScenePreparation = true
        running = true
        queuedStepIndex = null
    }

    private fun reverseRunningTransition() {
        val previousFromStepIndex = fromStepIndex ?: return
        val previousToStepIndex = toStepIndex
        val reversedProgress = reverseStartupOnboardingLayerTransitionProgress(
            enteringTranslationProgress = enteringTranslationProgress,
            exitingTranslationProgress = exitingTranslationProgress,
            enteringAlphaProgress = enteringAlphaProgress,
            exitingAlphaProgress = exitingAlphaProgress
        )
        val transitionToken = ++generation
        transitionJob?.cancel()
        transitionJob = null
        fromStepIndex = previousToStepIndex
        toStepIndex = previousFromStepIndex
        direction = -direction
        enteringTranslationProgress = reversedProgress.enteringTranslation
        exitingTranslationProgress = reversedProgress.exitingTranslation
        enteringAlphaProgress = reversedProgress.enteringAlpha
        exitingAlphaProgress = reversedProgress.exitingAlpha
        awaitingIncomingScenePreparation = false
        running = true
        queuedStepIndex = null
        launchTransition(transitionToken)
    }

    private fun launchTransition(transitionToken: Long) {
        val enteringTranslationStart = enteringTranslationProgress
        val exitingTranslationStart = exitingTranslationProgress
        val enteringAlphaStart = enteringAlphaProgress
        val exitingAlphaStart = exitingAlphaProgress
        transitionJob = scope.launch {
            try {
                coroutineScope {
                    launch {
                        animate(
                            initialValue = enteringTranslationStart,
                            targetValue = 1f,
                            animationSpec = startupOnboardingRemainingAnimationSpec(
                                durationMillis = STARTUP_ONBOARDING_STEP_ENTER_DURATION_MS,
                                startProgress = enteringTranslationStart
                            )
                        ) { value, _ ->
                            if (transitionToken == generation) {
                                enteringTranslationProgress = value
                            }
                        }
                    }
                    launch {
                        animate(
                            initialValue = exitingTranslationStart,
                            targetValue = 1f,
                            animationSpec = startupOnboardingRemainingAnimationSpec(
                                durationMillis = STARTUP_ONBOARDING_STEP_EXIT_DURATION_MS,
                                startProgress = exitingTranslationStart
                            )
                        ) { value, _ ->
                            if (transitionToken == generation) {
                                exitingTranslationProgress = value
                            }
                        }
                    }
                    launch {
                        animate(
                            initialValue = enteringAlphaStart,
                            targetValue = 1f,
                            animationSpec = startupOnboardingRemainingAnimationSpec(
                                durationMillis = STARTUP_ONBOARDING_STEP_ENTER_FADE_DURATION_MS,
                                startProgress = enteringAlphaStart
                            )
                        ) { value, _ ->
                            if (transitionToken == generation) {
                                enteringAlphaProgress = value
                            }
                        }
                    }
                    launch {
                        animate(
                            initialValue = exitingAlphaStart,
                            targetValue = 1f,
                            animationSpec = startupOnboardingRemainingAnimationSpec(
                                durationMillis = STARTUP_ONBOARDING_STEP_EXIT_FADE_DURATION_MS,
                                startProgress = exitingAlphaStart
                            )
                        ) { value, _ ->
                            if (transitionToken == generation) {
                                exitingAlphaProgress = value
                            }
                        }
                    }
                }
            } finally {
                if (transitionToken == generation) {
                    settleAtTarget()
                }
            }
        }
    }

    private fun startupOnboardingRemainingAnimationSpec(
        durationMillis: Int,
        startProgress: Float
    ) = tween<Float>(
        durationMillis = (
            durationMillis * (1f - startProgress.coerceIn(0f, 1f))
            ).roundToInt().coerceAtLeast(1),
        easing = FastOutSlowInEasing
    )

    private fun settleAtTarget() {
        fromStepIndex = null
        enteringTranslationProgress = 1f
        exitingTranslationProgress = 1f
        enteringAlphaProgress = 1f
        exitingAlphaProgress = 1f
        running = false
        awaitingIncomingScenePreparation = false
        transitionJob = null
        val queued = queuedStepIndex ?: return
        queuedStepIndex = null
        if (queued != toStepIndex) {
            startTransition(queued)
        }
    }
}
