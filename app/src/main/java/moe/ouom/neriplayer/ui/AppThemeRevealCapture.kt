package moe.ouom.neriplayer.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.data.settings.ThemeMode
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val THEME_REVEAL_SNAPSHOT_MAX_DIMENSION_PX = 1080
private const val THEME_REVEAL_STABLE_DRAW_PASSES = 1
internal const val THEME_REVEAL_DURATION_MILLIS = 720
internal const val THEME_REVEAL_WATCHDOG_DELAY_MILLIS = 900L
private val THEME_REVEAL_SNAPSHOT_CONFIG = Bitmap.Config.RGB_565

internal data class ThemeRevealSnapshotDimensions(
    val width: Int,
    val height: Int
)

internal fun resolveThemeRevealSnapshotDimensions(
    width: Int,
    height: Int,
    maxDimensionPx: Int = THEME_REVEAL_SNAPSHOT_MAX_DIMENSION_PX
): ThemeRevealSnapshotDimensions {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val maxDimension = maxOf(safeWidth, safeHeight)
    val downsampleRatio = (maxDimension.toFloat() / maxDimensionPx)
        .coerceAtLeast(1f)
    return ThemeRevealSnapshotDimensions(
        width = (safeWidth / downsampleRatio).roundToInt().coerceAtLeast(1),
        height = (safeHeight / downsampleRatio).roundToInt().coerceAtLeast(1)
    )
}

internal fun shouldBlockThemeModeChange(
    captureInFlight: Boolean,
    writeInFlight: Boolean,
    revealActive: Boolean,
    hasPendingThemePreference: Boolean
): Boolean = captureInFlight || writeInFlight || revealActive || hasPendingThemePreference

internal fun resolveThemeToggleTarget(isDark: Boolean): ThemeMode =
    if (isDark) ThemeMode.LIGHT else ThemeMode.DARK

private fun View.drawScaledThemeRevealBitmap(): Bitmap? {
    return runCatching { drawScaledThemeRevealBitmapOrNull() }.getOrNull()
}

private fun View.drawScaledThemeRevealBitmapOrNull(): Bitmap? {
    if (minOf(width, height) <= 0) return null
    val snapshotDimensions = resolveThemeRevealSnapshotDimensions(
        width = width,
        height = height
    )
    return createBitmap(
        snapshotDimensions.width,
        snapshotDimensions.height,
        THEME_REVEAL_SNAPSHOT_CONFIG
    ).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.scale(
            snapshotDimensions.width.toFloat() / width.toFloat(),
            snapshotDimensions.height.toFloat() / height.toFloat()
        )
        draw(canvas)
    }
}

internal suspend fun captureThemeRevealSnapshot(
    activity: Activity?,
    fallbackView: View
): ImageBitmap? = captureOptionalWindowThemeRevealSnapshot(activity)?.asImageBitmap()
    ?: captureThemeRevealFallbackSnapshot(fallbackView)

private suspend fun captureOptionalWindowThemeRevealSnapshot(activity: Activity?): Bitmap? =
    activity?.let { captureWindowThemeRevealSnapshot(it) }

private suspend fun captureWindowThemeRevealSnapshot(activity: Activity): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        val decorView = activity.window.decorView
        if (minOf(decorView.width, decorView.height) <= 0) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val snapshotDimensions = resolveThemeRevealSnapshotDimensions(
            width = decorView.width,
            height = decorView.height
        )
        val bitmap = createBitmap(
            snapshotDimensions.width,
            snapshotDimensions.height,
            THEME_REVEAL_SNAPSHOT_CONFIG
        )
        PixelCopy.request(
            activity.window,
            bitmap,
            { result -> continuation.resume(pixelCopyBitmapOrNull(result, bitmap)) },
            Handler(Looper.getMainLooper())
        )
    }

private fun pixelCopyBitmapOrNull(result: Int, bitmap: Bitmap): Bitmap? =
    if (result == PixelCopy.SUCCESS) bitmap else null

private suspend fun captureThemeRevealFallbackSnapshot(view: View): ImageBitmap? {
    return withContext(Dispatchers.Main.immediate) {
        runCatching { view.drawScaledThemeRevealBitmap()?.asImageBitmap() }.getOrNull()
    }
}

private suspend fun awaitNextDraw(view: View) {
    if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
        return
    }

    withTimeoutOrNull(120L) {
        suspendCancellableCoroutine { continuation ->
            val observer = view.viewTreeObserver
            var handled = false
            val drawListener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (handled) return
                    handled = true
                    view.post {
                        if (observer.isAlive) {
                            observer.removeOnDrawListener(this)
                        }
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
            }

            observer.addOnDrawListener(drawListener)
            continuation.invokeOnCancellation {
                if (handled) {
                    return@invokeOnCancellation
                }
                handled = true
                view.post {
                    if (observer.isAlive) {
                        observer.removeOnDrawListener(drawListener)
                    }
                }
            }
            view.invalidate()
        }
    }
}

internal suspend fun awaitStableDraw(view: View) {
    repeat(THEME_REVEAL_STABLE_DRAW_PASSES) {
        awaitNextDraw(view)
    }
}
