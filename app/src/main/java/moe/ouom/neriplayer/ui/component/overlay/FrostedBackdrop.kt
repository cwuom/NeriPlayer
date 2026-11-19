package moe.ouom.neriplayer.ui.component.overlay

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.graphics.createBitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** 截图按 1/4 分辨率采样，磨砂会抹平细节，换来很小的内存与带宽开销 */
private const val FrostedDownscale = 0.25f
private const val FrostedSaturation = 1.8f
private const val FrostedRevealMillis = 160

/**
 * One capture of the window behind a floating card, used to frost only the card's own area.
 * [originOnScreen] is where the captured window starts on screen; [downscale] maps screen px to
 * capture px
 */
@Immutable
internal class FrostedSnapshot(
    val image: ImageBitmap,
    val originOnScreen: Offset,
    val downscale: Float
)

/**
 * Captures the window hosting the caller once when [enabled] turns on (and again for every new
 * [captureKey]). Returns null below Android 12, where RenderEffect blur is unavailable, while the
 * capture is pending, and when it fails; callers then draw a solid card
 */
@Composable
internal fun rememberFrostedSnapshot(enabled: Boolean, captureKey: Any? = Unit): FrostedSnapshot? {
    val view = LocalView.current
    var snapshot by remember { mutableStateOf<FrostedSnapshot?>(null) }
    val active = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    DisposableEffect(view, active, captureKey) {
        var disposed = false
        if (active) {
            val window = view.findOverlayDialogWindow() ?: view.context.findActivity()?.window
            if (window != null) {
                captureFrostedSnapshot(window) { result -> if (!disposed) snapshot = result }
            }
        } else {
            snapshot = null
        }
        onDispose { disposed = true }
    }
    return if (active) snapshot else null
}

private fun captureFrostedSnapshot(window: Window, onResult: (FrostedSnapshot?) -> Unit) {
    val decor = window.decorView
    val width = (decor.width * FrostedDownscale).roundToInt()
    val height = (decor.height * FrostedDownscale).roundToInt()
    if (width <= 0 || height <= 0 || !decor.isAttachedToWindow) {
        onResult(null)
        return
    }
    val location = IntArray(2)
    decor.getLocationOnScreen(location)
    val bitmap = createBitmap(width, height)
    val origin = Offset(location[0].toFloat(), location[1].toFloat())
    try {
        PixelCopy.request(
            window,
            bitmap,
            { result ->
                onResult(
                    if (result == PixelCopy.SUCCESS) {
                        FrostedSnapshot(bitmap.asImageBitmap(), origin, FrostedDownscale)
                    } else {
                        null
                    }
                )
            },
            Handler(Looper.getMainLooper())
        )
    } catch (_: IllegalArgumentException) {
        // 窗口尚未有可用 Surface 时 PixelCopy 直接抛出，按纯色卡片处理
        onResult(null)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Background of a frosted card: its slice of [snapshot], blurred and saturated, under a
 * translucent [frostTint]. Until a snapshot arrives, or without one, it is a plain [solidColor]
 * fill, and it crossfades into the frost so the card never pops
 */
@Composable
internal fun BoxScope.FrostedCardBackground(
    snapshot: FrostedSnapshot?,
    shape: Shape,
    solidColor: Color,
    frostTint: Color,
    blurRadius: Dp
) {
    val frost by animateFloatAsState(
        targetValue = if (snapshot != null) 1f else 0f,
        animationSpec = tween(FrostedRevealMillis),
        label = "frosted-card-reveal"
    )
    var originOnScreen by remember { mutableStateOf(Offset.Unspecified) }
    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned { originOnScreen = it.positionOnScreen() }
            .clip(shape)
    ) {
        if (snapshot != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val radius = blurRadius.toPx()
                        renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                        alpha = frost
                    }
                    .drawBehind {
                        val cardOrigin = originOnScreen
                        if (!cardOrigin.isSpecified) return@drawBehind
                        val offset = snapshot.originOnScreen - cardOrigin
                        val scale = 1f / snapshot.downscale
                        drawImage(
                            image = snapshot.image,
                            dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                            dstSize = IntSize(
                                (snapshot.image.width * scale).roundToInt(),
                                (snapshot.image.height * scale).roundToInt()
                            ),
                            colorFilter = FrostedColorFilter,
                            filterQuality = FilterQuality.Low
                        )
                    }
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .drawBehind { drawRect(lerp(solidColor, frostTint, frost)) }
        )
    }
}

private val FrostedColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(FrostedSaturation) }
)
