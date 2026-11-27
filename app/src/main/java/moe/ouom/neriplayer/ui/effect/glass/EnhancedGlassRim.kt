package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.pow

// 高光沿顶边最亮，按缓出曲线渐隐，避免线性渐变在中段留下一道「塑料」分界
private const val SheenExtent = 0.5f
private const val SheenSteps = 8
private const val SheenFalloffPower = 2.4f

/**
 * Second glass tier for floating chrome in the enhanced clean style: a hairline rim that catches
 * light on the top edge and bounces faintly along the bottom, plus a soft sheen that fades out
 * over the upper half along an eased curve. Static brushes cached per size, drawn over the
 * content in the frame the surface already draws
 */
internal fun Modifier.enhancedGlassRim(shape: Shape, dark: Boolean): Modifier = drawWithCache {
    val strokeWidth = 1.dp.toPx()
    val inset = strokeWidth / 2f
    val outline = shape.createOutline(size, layoutDirection, this)
    val rimOutline = shape.createOutline(
        Size(size.width - strokeWidth, size.height - strokeWidth),
        layoutDirection,
        this
    )
    val rimPeak = if (dark) 0.22f else 0.9f
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = rimPeak),
        0.16f to Color.White.copy(alpha = rimPeak * 0.55f),
        0.36f to Color.White.copy(alpha = rimPeak * 0.18f),
        0.62f to Color.White.copy(alpha = rimPeak * 0.08f),
        1f to Color.White.copy(alpha = rimPeak * 0.42f),
        startY = inset,
        endY = size.height - inset
    )
    val sheen = easedSheen(peak = if (dark) 0.05f else 0.18f, height = size.height)
    val stroke = Stroke(width = strokeWidth)
    onDrawWithContent {
        drawContent()
        drawOutline(outline, sheen)
        translate(inset, inset) { drawOutline(rimOutline, rim, style = stroke) }
    }
}

/** Vertical white sheen whose alpha follows (1 - t)^p over [SheenExtent] of [height]. */
internal fun easedSheen(peak: Float, height: Float): Brush {
    val stops = Array(SheenSteps + 1) { step ->
        val t = step / SheenSteps.toFloat()
        val alpha = peak * (1f - t).pow(SheenFalloffPower)
        (t * SheenExtent) to Color.White.copy(alpha = alpha)
    }
    return Brush.verticalGradient(*stops, startY = 0f, endY = height)
}
