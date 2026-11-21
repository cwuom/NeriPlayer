package moe.ouom.neriplayer.ui.component.navigation

import android.graphics.BlurMaskFilter
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import kotlin.math.abs
import kotlin.math.roundToInt

private val RestInset = 4.dp
private const val PressedScale = 1.34f
private const val PressedMagnification = 1.2f
private val LensEdgeBand = 12.dp
private val LensRefraction = 4.dp
private val LensInnerShadow = 8.dp
private val LensElevation = 14.dp

/*
 * enhanced, after the iOS 26 / FlClash tab bar: pressing swells the pill about its centre into a
 * clear glass lens a little taller than the bar (no lift, a faint shadow), the tabs under it are
 * magnified and bent by a thick-glass refraction with a chromatic rim, and a fast drag stretches
 * it like a droplet. A glass rim stays on the resting pill and a dark hairline frames the lens
 */
// 只轻微放大，图标和文字留在原位，不会被顶到透镜上下沿
private const val EnhancedPressedMagnification = 1.12f
// 参照 miuix：只在边缘一圈折射，中间的图标保持清晰，边缘带一点色散
private val EnhancedRefractionHeight = 12.dp
private val EnhancedRefractionAmount = 16.dp
private const val EnhancedChromaticAberration = 0.5f
private const val EnhancedRestRim = 0.32f
private val EnhancedLensHairline = 0.67.dp

private fun lerpFloat(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/**
 * Indicator rectangle in bar-local space: the resting pill behind the selected tab, swelling
 * into the lifted lens while pressed. Moving fast stretches it along the travel and flattens it,
 * the same squash-and-stretch the iOS tab bar uses, so the drag reads as liquid.
 */
internal fun CleanTabDragState.indicatorRect(
    barHeightPx: Float,
    density: Density,
    enhanced: Boolean = false
): Rect? {
    val reference = anyBounds() ?: return null
    val centerX = pillCenterX.value
    if (centerX.isNaN()) return null
    val inset = with(density) { RestInset.toPx() }
    val restWidth = reference.width - inset
    val restHeight = barHeightPx - inset * 2f
    val progress = dragProgress.value.coerceIn(0f, 1f)
    // stretch mostly along the travel; a deep vertical squash reads as the lens deflating
    val stretchX: Float
    val squashY: Float
    if (enhanced) {
        // 果冻弹簧：移动越快拉得越长，停下时回弹成略高略窄再落定
        val (jellyX, jellyY) = jellyScale(jelly)
        stretchX = jellyX
        squashY = jellyY
    } else {
        val stretch = abs(velocity.value / reference.width.coerceAtLeast(1f) / 10f)
        stretchX = 1f / (1f - (stretch * 0.6f).coerceAtMost(0.14f))
        squashY = 1f - (stretch * 0.12f).coerceAtMost(0.06f)
    }
    val width: Float
    val height: Float
    if (enhanced) {
        // 静止胶囊等比放大，宽高各自的弹簧带出果冻回弹，参照 miuix
        width = restWidth * lensScaleX.value * stretchX
        height = restHeight * lensScaleY.value * squashY
    } else {
        val scale = lerpFloat(1f, PressedScale, progress)
        width = restWidth * scale * stretchX
        height = restHeight * scale * squashY
    }
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
    dark: Boolean,
    enhanced: Boolean = false
) {
    val density = LocalDensity.current
    // 逐帧变化的位置、按压进度和果冻形变只在布局与绘制阶段读取，动画期间透镜只重画不重组
    val ready by remember(tabDrag) { derivedStateOf { !tabDrag.pillCenterX.value.isNaN() } }
    if (!ready) return
    val glassActive by remember(tabDrag) { derivedStateOf { tabDrag.dragProgress.value > 0.02f } }
    val glassController = LocalAdvancedGlassController.current
    val liquidGlassEnabled = glassController.isLiquidGlassEnabled
    // 透镜折射只在按住时、只在透镜内计算，增强模式下不受整套液态玻璃管线的开关限制
    val refracts = liquidGlassEnabled || enhanced
    val rimShown by remember(tabDrag, enhanced, refracts) {
        derivedStateOf { enhanced || (refracts && tabDrag.dragProgress.value > 0.01f) }
    }
    val pressedMagnification = if (enhanced) EnhancedPressedMagnification else PressedMagnification
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val restFill = when {
        !enhanced -> colors.onSurface.copy(alpha = if (dark) 0.12f else 0.07f)
        dark -> Color.White.copy(alpha = 0.1f)
        else -> Color.Black.copy(alpha = 0.1f)
    }
    // 增强模式的透镜是一块放大的底栏玻璃：用底栏自身的材质填满，伸出底栏的上下两截也是玻璃
    val liftedFill = if (enhanced) {
        enhancedLensBody(
            barColor = colors.surfaceContainerLow,
            dark = dark,
            overBackground = LocalHasCustomBackground.current,
            backdropBlurred = glassController.isBaseBlurEnabled
        )
    } else {
        Color.White.copy(alpha = if (dark) 0.08f else 0.22f)
    }
    val rim = if (dark) Color.White.copy(alpha = 0.5f) else Color.White
    val lensShading = remember(enhanced) { if (enhanced) LensShading() else null }
    val pressSpot = remember(enhanced) { if (enhanced) CleanTabLensSpot() else null }
    val refraction = remember(liquidGlassEnabled, enhanced) {
        if (liquidGlassEnabled && !enhanced) LensRefractionEffect() else null
    }
    val liquidRefraction = remember(enhanced) {
        if (enhanced) CleanTabLensRefraction() else null
    }
    val accentPaint = remember(accent) {
        Paint().apply { colorFilter = ColorFilter.tint(accent, BlendMode.SrcIn) }
    }
    fun pressProgress() = tabDrag.dragProgress.value.coerceIn(0f, 1f)
    fun glassProgress() = if (refracts) pressProgress() else 0f
    fun rimProgress() = if (enhanced) maxOf(glassProgress(), EnhancedRestRim) else glassProgress()
    fun frame() = tabDrag.lensFrame(hostCoordinates(), barHeightPx, density, enhanced)
    Box(
        modifier = Modifier
            .floatingFrame {
                val (rect, barOffset) = frame() ?: return@floatingFrame null
                IntRect(
                    left = (barOffset.x + rect.left).roundToInt(),
                    top = (barOffset.y + rect.top).roundToInt(),
                    right = (barOffset.x + rect.right).roundToInt(),
                    bottom = (barOffset.y + rect.bottom).roundToInt()
                )
            }
            .graphicsLayer {
                shape = CircleShape
                clip = false
                // 增强透镜贴着底栏鼓起，不投影，避免读作被抬起
                shadowElevation = if (enhanced) 0f else LensElevation.toPx() * glassProgress()
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
            enabled = glassActive
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        shape = CircleShape
                        val glass = glassProgress()
                        renderEffect = when {
                            glass <= 0.01f -> null
                            liquidRefraction != null -> liquidRefraction.effect(
                                width = size.width,
                                height = size.height,
                                refractionHeight = EnhancedRefractionHeight.toPx() * glass,
                                refractionAmount = EnhancedRefractionAmount.toPx() * glass,
                                chromaticAberration = EnhancedChromaticAberration
                            )
                            else -> refraction?.effect(
                                width = size.width,
                                height = size.height,
                                edgeBandPx = LensEdgeBand.toPx(),
                                refractionPx = LensRefraction.toPx() * glass
                            )
                        }
                    }
            ) {
                val rect = frame()?.first ?: return@Canvas
                val progress = pressProgress()
                val glass = glassProgress()
                drawRoundRect(
                    color = lerp(restFill, liftedFill, glass),
                    cornerRadius = CornerRadius(size.height / 2f)
                )
                // bar point q lands at (q - c) * m + half: magnify about the indicator center
                val magnification = lerpFloat(1f, pressedMagnification, progress)
                val center = rect.center
                withTransform({
                    translate(size.width / 2f, size.height / 2f)
                    scale(magnification, magnification, pivot = Offset.Zero)
                    translate(-center.x, -center.y)
                }) {
                    // 底栏自己的轮廓也画进透镜，经折射在上下沿散出彩边，参照 FlClash
                    if (enhanced && glass > 0.01f) drawBarOutline(tabDrag, glass, dark)
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(center.x - size.width, center.y - size.height, center.x + size.width, center.y + size.height), accentPaint)
                        drawLayer(barLayer)
                        canvas.restore()
                    }
                }
                // 模糊遮罩每帧都要在 CPU 上重新光栅化，增强透镜改用体积渐变表现边缘厚度
                if (!enhanced && glass > 0.01f) drawInnerShadow(glass)
            }
        }
        if (rimShown) {
            Canvas(Modifier.fillMaxSize()) {
                if (lensShading != null) {
                    // 体积光画在折射层之外：折射的色散会把平滑渐变拆成一圈圈彩纹
                    lensShading.draw(this, glassProgress(), dark)
                    // 手指处的柔光同样画在折射层之外，用叠加混合与无色阶的光斑
                    frame()?.first?.let { rect ->
                        pressSpot?.draw(this, tabDrag.glowCenter - rect.left, tabDrag.glowAlpha.value, dark)
                    }
                    drawEnhancedLensRim(rimProgress(), dark)
                    drawLensHairline(pressProgress())
                } else {
                    drawSpecularRim(rim, rimProgress(), dark)
                }
            }
        }
    }
}

/**
 * Lays the node out at [frame] (parent-local px) while reporting a zero size to the parent, so an
 * element that swells past its container never grows it. Without this the pressed lens, taller
 * than the bar, enlarged the bottom-aligned bar slot and lifted the whole bar and mini player
 */
internal fun Modifier.floatingFrame(frame: () -> IntRect?): Modifier = layout { measurable, _ ->
    val rect = frame()
    val width = rect?.width?.coerceAtLeast(0) ?: 0
    val height = rect?.height?.coerceAtLeast(0) ?: 0
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(0, 0) {
        // 位置只在放置阶段读取，移动透镜不触发重新测量
        val current = frame() ?: return@layout
        placeable.place(current.left, current.top)
    }
}

/** The indicator rectangle and the bar's offset inside the host, or null before layout. */
private fun CleanTabDragState.lensFrame(
    host: LayoutCoordinates?,
    barHeightPx: Float,
    density: Density,
    enhanced: Boolean
): Pair<Rect, Offset>? {
    val rect = indicatorRect(barHeightPx, density, enhanced) ?: return null
    val bar = bar ?: return null
    if (host == null || !bar.isAttached || !host.isAttached) return null
    return rect to host.localPositionOf(bar, Offset.Zero)
}

/**
 * Body of the pressed enhanced lens, matched to the bar so the lens reads as the same glass
 * swelling up: over a blurred backdrop only a light glaze (the glass surface already tints it),
 * otherwise the bar's own solid fill, a touch brighter
 */
private fun enhancedLensBody(
    barColor: Color,
    dark: Boolean,
    overBackground: Boolean,
    backdropBlurred: Boolean
): Color {
    // 参照 miuix：模糊背景上只压一层 3% 的黑，玻璃感交给折射、内阴影和边缘高光，不叠白雾
    if (backdropBlurred) return Color.Black.copy(alpha = 0.03f)
    val body = lerp(barColor, Color.White, if (dark) 0.05f else 0.28f)
    return body.copy(alpha = if (overBackground) 0.62f else 0.9f)
}

/**
 * Volume of the lens, drawn over the refracted body: the glass darkens softly toward the rim
 * and a glaze of light settles just inside the top edge, so the clear body reads as a domed
 * drop. Dithered native paints keep the faint gradients free of banding; shaders are rebuilt
 * only when the lens changes size
 */
private class LensShading {
    private val edgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val glazePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var shaderSize = Size.Zero
    private var shaderDark = false

    fun draw(scope: DrawScope, progress: Float, dark: Boolean) = with(scope) {
        if (progress <= 0.01f) return@with
        if (shaderSize != size || shaderDark != dark) {
            shaderSize = size
            shaderDark = dark
            rebuild(size, dark)
        }
        val alpha = (progress * 255f).roundToInt().coerceIn(0, 255)
        edgePaint.alpha = alpha
        glazePaint.alpha = alpha
        val radius = size.height / 2f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, edgePaint)
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, glazePaint)
        }
    }

    private fun rebuild(size: Size, dark: Boolean) {
        val edge = Color.Black.copy(alpha = if (dark) 0.14f else 0.09f)
        // 椭圆形渐变贴合胶囊外形，边缘一圈均匀变暗
        val radius = size.height * 0.5f
        val edgeShader = android.graphics.RadialGradient(
            size.width / 2f, size.height / 2f, radius,
            intArrayOf(Color.Transparent.toArgb(), Color.Transparent.toArgb(), edge.toArgb()),
            floatArrayOf(0f, 0.62f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        edgeShader.setLocalMatrix(android.graphics.Matrix().apply {
            setScale(size.width / size.height, 1f, size.width / 2f, size.height / 2f)
        })
        edgePaint.shader = edgeShader
        // 顶部只留一线很淡的光泽，避免整片发白
        val peak = if (dark) 0.04f else 0.06f
        val glazeStops = FloatArray(GlazeSteps + 1) { it / GlazeSteps.toFloat() * GlazeExtent }
        val glazeColors = IntArray(GlazeSteps + 1) { step ->
            val t = step / GlazeSteps.toFloat()
            Color.White.copy(alpha = peak * (1f - t) * (1f - t)).toArgb()
        }
        glazePaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, size.height, glazeColors, glazeStops, android.graphics.Shader.TileMode.CLAMP
        )
    }
}

private const val GlazeSteps = 8
private const val GlazeExtent = 0.55f

/**
 * Smooth specular rim of the enhanced lens: light concentrated on the upper edge, fading along
 * the curved sides with an eased falloff, and a faint bounce along the bottom. No flat sheen
 * fill, which made the lens read as plastic
 */
private fun DrawScope.drawEnhancedLensRim(progress: Float, dark: Boolean) {
    val stroke = 1.dp.toPx()
    val peak = if (dark) 0.42f else 0.95f
    fun white(fraction: Float) = Color.White.copy(alpha = peak * fraction * progress)
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to white(1f),
            0.14f to white(0.62f),
            0.3f to white(0.26f),
            0.48f to white(0.08f),
            0.7f to white(0.06f),
            1f to white(0.36f)
        ),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((size.height - stroke) / 2f),
        style = Stroke(width = stroke)
    )
}

/**
 * The bar's capsule edge in bar space, drawn under the magnified tabs. Inside the lens it lies
 * in the refracting bevel, so the dispersion splits it into the rainbow fringe a real lens shows
 */
private fun DrawScope.drawBarOutline(tabDrag: CleanTabDragState, progress: Float, dark: Boolean) {
    val bar = tabDrag.bar ?: return
    val width = BarOutlineWidth.toPx()
    val barSize = Size(bar.size.width.toFloat(), bar.size.height.toFloat())
    drawRoundRect(
        color = Color.White.copy(alpha = (if (dark) 0.5f else 0.95f) * progress),
        topLeft = Offset(width / 2f - BarOutlineInsetX.toPx(), width / 2f),
        size = Size(barSize.width + BarOutlineInsetX.toPx() * 2f - width, barSize.height - width),
        cornerRadius = CornerRadius(barSize.height / 2f),
        style = Stroke(width = width)
    )
}

private val BarOutlineWidth = 2.dp
// 底栏行内容左右各缩进 6dp，轮廓要落在胶囊真正的边上
private val BarOutlineInsetX = 6.dp

/** Thin dark line just outside the rim, so the lens edge reads as glass even over a busy page. */
private fun DrawScope.drawLensHairline(progress: Float) {
    if (progress <= 0.01f) return
    val width = EnhancedLensHairline.toPx()
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.18f * progress),
        topLeft = Offset(-width / 2f, -width / 2f),
        size = Size(size.width + width, size.height + width),
        cornerRadius = CornerRadius(size.height / 2f + width / 2f),
        style = Stroke(width = width)
    )
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
