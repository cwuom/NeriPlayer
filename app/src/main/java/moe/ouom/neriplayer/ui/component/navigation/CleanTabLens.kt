package moe.ouom.neriplayer.ui.component.navigation

import android.graphics.BlurMaskFilter
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import kotlin.math.abs
import kotlin.math.roundToInt

private val RestInset = 4.dp
private const val PressedScale = 1.34f
private const val PressedMagnification = 1.2f
private val LensEdgeBand = 12.dp
private val LensRefraction = 4.dp
private val LensInnerShadow = 8.dp
private val LensElevation = 14.dp

private fun lerpFloat(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/**
 * Indicator rectangle in bar-local space: the resting pill behind the selected tab, swelling
 * into the lifted lens while pressed. Moving fast stretches it along the travel and flattens it,
 * the same squash-and-stretch the iOS tab bar uses, so the drag reads as liquid.
 */
internal fun CleanTabDragState.indicatorRect(barHeightPx: Float, density: Density): Rect? {
    val reference = anyBounds() ?: return null
    val centerX = pillCenterX.value
    if (centerX.isNaN()) return null
    val inset = with(density) { RestInset.toPx() }
    val restWidth = reference.width - inset
    val restHeight = barHeightPx - inset * 2f
    val progress = dragProgress.value.coerceIn(0f, 1f)
    val scale = lerpFloat(1f, PressedScale, progress)
    val stretch = abs(velocity.value / reference.width.coerceAtLeast(1f) / 10f)
    // stretch mostly along the travel; a deep vertical squash reads as the lens deflating
    val scaleX = scale / (1f - (stretch * 0.6f).coerceAtMost(0.14f))
    val scaleY = scale * (1f - (stretch * 0.12f).coerceAtMost(0.06f))
    val width = restWidth * scaleX
    val height = restHeight * scaleY
    return Rect(
        left = centerX - width / 2f,
        top = reference.center.y - height / 2f,
        right = centerX + width / 2f,
        bottom = reference.center.y + height / 2f
    )
}

/**
 * Records the bar content into [layer] and draws it with the indicator cut out; the indicator
 * paints that area itself (tinted, magnified and refracted), so nothing ever doubles up.
 */
internal fun ContentDrawScope.drawBarContentAroundIndicator(
    layer: GraphicsLayer,
    indicator: Rect?,
    path: Path
) {
    layer.record { this@drawBarContentAroundIndicator.drawContent() }
    if (indicator == null) {
        drawLayer(layer)
        return
    }
    path.reset()
    path.addRoundRect(RoundRect(indicator, CornerRadius(indicator.height / 2f)))
    clipPath(path, ClipOp.Difference) { drawLayer(layer) }
}

/**
 * The single selection indicator of the clean tab bar, after miuix's port of the iOS liquid
 * glass bar: a quiet tinted pill at rest; on press it lifts out of the bar as a glass lens that
 * magnifies the tabs beneath in the accent color, refracts them at the rim and gains an inner
 * shadow and a specular edge. While pressed it is a glass region itself, so the overhang over
 * the page is blurred like the bar.
 */
@Composable
internal fun CleanTabIndicator(
    tabDrag: CleanTabDragState,
    hostCoordinates: () -> LayoutCoordinates?,
    barLayer: GraphicsLayer,
    barHeightPx: Float,
    dark: Boolean
) {
    val density = LocalDensity.current
    val rect = tabDrag.indicatorRect(barHeightPx, density) ?: return
    val bar = tabDrag.bar ?: return
    val host = hostCoordinates() ?: return
    if (!bar.isAttached || !host.isAttached) return
    val barOffset = host.localPositionOf(bar, Offset.Zero)
    val progress = tabDrag.dragProgress.value.coerceIn(0f, 1f)
    val liquidGlassEnabled = LocalAdvancedGlassController.current.isLiquidGlassEnabled
    val glassProgress = if (liquidGlassEnabled) progress else 0f
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val restFill = colors.onSurface.copy(alpha = if (dark) 0.12f else 0.07f)
    val liftedFill = Color.White.copy(alpha = if (dark) 0.08f else 0.22f)
    val rim = if (dark) Color.White.copy(alpha = 0.5f) else Color.White
    val refraction = remember(liquidGlassEnabled) {
        if (liquidGlassEnabled) LensRefractionEffect() else null
    }
    val accentPaint = remember(accent) {
        Paint().apply { colorFilter = ColorFilter.tint(accent, BlendMode.SrcIn) }
    }
    Box(
        modifier = Modifier
            .offset { IntOffset((barOffset.x + rect.left).roundToInt(), (barOffset.y + rect.top).roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer {
                shape = CircleShape
                clip = false
                shadowElevation = LensElevation.toPx() * glassProgress
                ambientShadowColor = Color.Black.copy(alpha = 0.08f)
                spotShadowColor = Color.Black.copy(alpha = if (dark) 0.34f else 0.2f)
            }
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.BottomNavigation,
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            fallbackColor = Color.Transparent,
            tintColor = colors.surfaceContainerLow,
            enabled = progress > 0.02f
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        shape = CircleShape
                        renderEffect = if (glassProgress > 0.01f) {
                            refraction?.effect(
                                width = size.width,
                                height = size.height,
                                edgeBandPx = LensEdgeBand.toPx(),
                                refractionPx = LensRefraction.toPx() * glassProgress
                            )
                        } else {
                            null
                        }
                    }
            ) {
                drawRoundRect(
                    color = lerp(restFill, liftedFill, glassProgress),
                    cornerRadius = CornerRadius(size.height / 2f)
                )
                // bar point q lands at (q - c) * m + half: magnify about the indicator center
                val magnification = lerpFloat(1f, PressedMagnification, progress)
                val center = rect.center
                withTransform({
                    translate(size.width / 2f, size.height / 2f)
                    scale(magnification, magnification, pivot = Offset.Zero)
                    translate(-center.x, -center.y)
                }) {
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(center.x - size.width, center.y - size.height, center.x + size.width, center.y + size.height), accentPaint)
                        drawLayer(barLayer)
                        canvas.restore()
                    }
                }
                if (glassProgress > 0.01f) drawInnerShadow(glassProgress)
            }
        }
        if (glassProgress > 0.01f) {
            Canvas(Modifier.fillMaxSize()) { drawSpecularRim(rim, glassProgress, dark) }
        }
    }
}

/** Soft dark falloff just inside the rim, so the lens reads as thick glass rather than a sticker. */
private fun DrawScope.drawInnerShadow(progress: Float) {
    val radius = LensInnerShadow.toPx() * progress
    if (radius < 0.5f) return
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = radius * 2f
            color = Color.Black.copy(alpha = 0.15f * progress).toArgb()
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        val corner = size.height / 2f
        canvas.nativeCanvas.drawRoundRect(
            -radius, -radius, size.width + radius, size.height + radius,
            corner + radius, corner + radius, paint
        )
    }
}

/** Light catching the top edge and a softer bounce along the bottom, fading in with the press. */
private fun DrawScope.drawSpecularRim(rim: Color, progress: Float, dark: Boolean) {
    val corner = CornerRadius(size.height / 2f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to rim.copy(alpha = rim.alpha * progress),
            0.32f to rim.copy(alpha = rim.alpha * 0.12f * progress),
            0.7f to rim.copy(alpha = rim.alpha * 0.08f * progress),
            1f to rim.copy(alpha = rim.alpha * 0.6f * progress)
        ),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx())
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = (if (dark) 0.08f else 0.2f) * progress),
            0.42f to Color.Transparent
        ),
        cornerRadius = corner
    )
}

/** Edge refraction for the lens content; plain magnification below Android 13. */
private class LensRefractionEffect {
    private val shader: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeShader(LensShaderSource) }.getOrNull()
    } else {
        null
    }

    fun effect(
        width: Float,
        height: Float,
        edgeBandPx: Float,
        refractionPx: Float
    ): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val runtime = shader as? RuntimeShader ?: return null
        return build(runtime, width, height, edgeBandPx, refractionPx)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun build(
        runtime: RuntimeShader,
        width: Float,
        height: Float,
        edgeBandPx: Float,
        refractionPx: Float
    ): androidx.compose.ui.graphics.RenderEffect {
        runtime.setFloatUniform("size", width, height)
        runtime.setFloatUniform("band", edgeBandPx)
        runtime.setFloatUniform("strength", refractionPx)
        return android.graphics.RenderEffect
            .createRuntimeShaderEffect(runtime, "content")
            .asComposeRenderEffect()
    }
}

private const val LensShaderSource = """
uniform shader content;
uniform float2 size;
uniform float band;
uniform float strength;

half4 main(float2 p) {
    float2 c = size * 0.5;
    float radius = min(c.x, c.y);
    float2 q = abs(p - c) - (c - radius);
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float t = 1.0 - smoothstep(0.0, band, -dist);
    float2 dir = (p - c) / max(length(p - c), 0.001);
    float2 off = -dir * strength * t * t;
    half4 g = content.eval(p + off);
    half r = content.eval(p + off * 1.06).r;
    half b = content.eval(p + off * 0.94).b;
    return half4(r, g.g, b, g.a);
}
"""
