package moe.ouom.neriplayer.ui.onboarding

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.createBitmap
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.ui.component.common.ThemeRevealOverlay

private const val STARTUP_THEME_REVEAL_WATCHDOG_DELAY_MILLIS = 1300L
private const val STARTUP_THEME_REVEAL_CAPTURE_TIMEOUT_MILLIS = 500L
private const val STARTUP_THEME_REVEAL_DURATION_MILLIS = 680

/**
 * 截下当前画面盖在最上层, 再写入新的主题或界面风格, 圆形揭示从点按处露出新界面
 * 深浅色切换和风格切换共用这一套, 保证两者的过渡手感一致
 */
@Stable
internal class StartupRevealController(
    private val scope: CoroutineScope,
    private val activity: Activity?,
    private val rootView: View,
    private val lifecycleOwner: LifecycleOwner
) {
    var snapshot by mutableStateOf<ImageBitmap?>(null)
        private set
    var originInWindow by mutableStateOf<Offset?>(null)
        private set
    var startRadiusPx by mutableFloatStateOf(0f)
        private set
    var fallbackColor by mutableStateOf<Color?>(null)
        private set
    var captureInFlight by mutableStateOf(false)
        private set
    var token by mutableIntStateOf(0)
        private set
    private var captureJob: Job? = null

    val revealActive: Boolean
        get() = originInWindow != null && fallbackColor != null

    val busy: Boolean
        get() = shouldBlockStartupOnboardingThemeToggle(captureInFlight, revealActive)

    fun request(
        origin: Offset,
        startRadius: Float,
        coverColor: Color,
        beforeApply: () -> Unit,
        apply: suspend () -> Unit,
        onFailure: () -> Unit
    ) {
        if (busy) return
        val captureToken = token + 1
        token = captureToken
        captureJob?.cancel()
        captureInFlight = true
        captureJob = scope.launch {
            var applyStarted = false
            var applyCompleted = false
            val captureView = activity?.window?.decorView?.rootView ?: rootView.rootView
            try {
                awaitStartupStableDraw(captureView)
                val captured = withTimeoutOrNull(STARTUP_THEME_REVEAL_CAPTURE_TIMEOUT_MILLIS.milliseconds) {
                    runCatching { captureStartupRevealSnapshot(activity, captureView) }.getOrNull()
                }
                val lifecycleActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                val activityValid = activity == null || (!activity.isFinishing && !activity.isDestroyed)
                if (token != captureToken || !lifecycleActive || !activityValid) return@launch
                clearVisual()
                snapshot = captured
                fallbackColor = coverColor
                originInWindow = origin
                startRadiusPx = startRadius.coerceAtLeast(1f)
                captureInFlight = false
                beforeApply()
                applyStarted = true
                apply()
                applyCompleted = true
            } finally {
                if (token == captureToken) {
                    if (applyStarted && !applyCompleted) {
                        onFailure()
                        clearVisual()
                    }
                    captureJob = null
                    captureInFlight = false
                }
            }
        }
    }

    fun finish(captureToken: Int) {
        if (token == captureToken) clearVisual()
    }

    fun cancel() {
        token += 1
        captureJob?.cancel()
        captureJob = null
        captureInFlight = false
        clearVisual()
    }

    private fun clearVisual() {
        snapshot = null
        originInWindow = null
        startRadiusPx = 0f
        fallbackColor = null
    }
}

@Composable
internal fun rememberStartupRevealController(activity: Activity?): StartupRevealController {
    val scope = rememberCoroutineScope()
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(scope, activity, rootView, lifecycleOwner) {
        StartupRevealController(scope, activity, rootView, lifecycleOwner)
    }
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    return controller
}

@Composable
internal fun StartupRevealOverlay(controller: StartupRevealController) {
    val activeToken = controller.token
    LaunchedEffect(controller.revealActive, activeToken) {
        if (!controller.revealActive) return@LaunchedEffect
        // 看门狗: 揭示动画异常中断时也要撤掉截图, 不让旧画面挡住界面
        delay(STARTUP_THEME_REVEAL_WATCHDOG_DELAY_MILLIS.milliseconds)
        controller.finish(activeToken)
    }
    val origin = controller.originInWindow
    val cover = controller.fallbackColor
    if (origin != null && cover != null) {
        ThemeRevealOverlay(
            snapshot = controller.snapshot,
            fallbackColor = cover,
            originInWindow = origin,
            modifier = Modifier.fillMaxSize(),
            startRadiusPx = controller.startRadiusPx,
            durationMillis = STARTUP_THEME_REVEAL_DURATION_MILLIS,
            onFinished = { controller.finish(activeToken) }
        )
    }
}

private suspend fun captureStartupRevealSnapshot(activity: Activity?, fallbackView: View): ImageBitmap? {
    val windowBitmap = activity?.let { currentActivity ->
        suspendCancellableCoroutine { continuation ->
            val decorView = currentActivity.window.decorView
            if (decorView.width <= 0 || decorView.height <= 0) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val bitmap = createBitmap(decorView.width, decorView.height)
            PixelCopy.request(
                currentActivity.window,
                bitmap,
                { result ->
                    if (continuation.isActive) {
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(bitmap)
                        } else {
                            bitmap.recycle()
                            continuation.resume(null)
                        }
                    } else {
                        bitmap.recycle()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }
    return windowBitmap?.asImageBitmap() ?: captureStartupRevealFallbackSnapshot(fallbackView)
}

private suspend fun captureStartupRevealFallbackSnapshot(view: View): ImageBitmap? =
    withContext(Dispatchers.Main.immediate) {
        runCatching {
            if (view.width > 0 && view.height > 0) view.drawToBitmap().asImageBitmap() else null
        }.getOrNull()
    }

private suspend fun awaitStartupNextDraw(view: View) {
    if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
    withTimeoutOrNull(120L.milliseconds) {
        suspendCancellableCoroutine { continuation ->
            val observer = view.viewTreeObserver
            var handled = false
            val drawListener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (handled) return
                    handled = true
                    view.post {
                        if (observer.isAlive) observer.removeOnDrawListener(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            observer.addOnDrawListener(drawListener)
            continuation.invokeOnCancellation {
                if (handled) return@invokeOnCancellation
                handled = true
                view.post {
                    if (observer.isAlive) observer.removeOnDrawListener(drawListener)
                }
            }
            view.invalidate()
        }
    }
}

private suspend fun awaitStartupStableDraw(view: View) {
    repeat(2) { awaitStartupNextDraw(view) }
}
