package moe.ouom.neriplayer.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

@Composable
fun ThemeRevealOverlay(
    snapshot: ImageBitmap?,
    fallbackColor: Color,
    originInWindow: Offset,
    startRadiusPx: Float = 1f,
    legacySnapshotDim: Boolean = false,
    modifier: Modifier = Modifier,
    durationMillis: Int = 720,
    onFinished: () -> Unit
) {
    var containerOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
    val progress = remember(snapshot, fallbackColor, originInWindow) {
        Animatable(0f)
    }

    LaunchedEffect(snapshot, fallbackColor, originInWindow) {
        progress.stop()
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        )
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerOffsetInWindow = coordinates.positionInWindow()
            }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                if (snapshot != null) {
                    val snapshotAlpha = if (legacySnapshotDim) {
                        0.78f + (1f - progress.value) * 0.18f
                    } else {
                        1f
                    }
                    drawImage(
                        image = snapshot,
                        dstSize = IntSize(
                            width = size.width.roundToInt().coerceAtLeast(1),
                            height = size.height.roundToInt().coerceAtLeast(1)
                        ),
                        alpha = snapshotAlpha
                    )
                } else {
                    drawRect(color = fallbackColor)
                }
                val origin = originInWindow - containerOffsetInWindow
                val maxRadius = maxRevealRadius(origin, size)
                val initialRadius = startRadiusPx.coerceIn(1f, maxRadius.coerceAtLeast(1f))
                val radius = initialRadius + (maxRadius - initialRadius) * progress.value
                val haloAlpha = (1f - progress.value) * 0.08f
                if (haloAlpha > 0.001f) {
                    val haloRadius = radius * 1.12f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = haloAlpha),
                                Color.Transparent
                            ),
                            center = origin,
                            radius = haloRadius
                        ),
                        radius = haloRadius,
                        center = origin
                    )
                }
                drawCircle(
                    color = Color.Transparent,
                    radius = radius,
                    center = origin,
                    blendMode = BlendMode.Clear
                )
            }
    )
}

private fun maxRevealRadius(origin: Offset, size: Size): Float {
    val corners = listOf(
        Offset.Zero,
        Offset(size.width, 0f),
        Offset(0f, size.height),
        Offset(size.width, size.height)
    )
    return corners.maxOf { corner -> (corner - origin).getDistance() }
}
