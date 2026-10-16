package moe.ouom.neriplayer.ui.theme

/*
 * Continuous-curvature corner geometry adapted from miuix-squircle (Apache-2.0,
 * compose-miuix-ui contributors): per-corner radii, layout-direction aware and
 * exposed as a CornerBasedShape so it can back MaterialTheme.shapes.
 */

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** Corner tile length as a multiple of the nominal radius; 1.0 would be a circular arc. */
internal const val CONTINUOUS_CORNER_EXTENSION = 1.1f

/** Cubic handle ratio of the miuix squircle curve. */
private const val CONTINUOUS_CORNER_CONTROL = 0.643f

class ContinuousRoundedCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    private val extension: Float = CONTINUOUS_CORNER_EXTENSION
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): ContinuousRoundedCornerShape = ContinuousRoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart,
        extension = extension
    )

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) {
            return Outline.Rectangle(size.toRect())
        }
        val ltr = layoutDirection == LayoutDirection.Ltr
        val topLeft = if (ltr) topStart else topEnd
        val topRight = if (ltr) topEnd else topStart
        val bottomRight = if (ltr) bottomEnd else bottomStart
        val bottomLeft = if (ltr) bottomStart else bottomEnd
        // RenderNode can only clip to arbitrary paths from API 30
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Outline.Rounded(
                RoundRect(
                    rect = size.toRect(),
                    topLeft = CornerRadius(topLeft),
                    topRight = CornerRadius(topRight),
                    bottomRight = CornerRadius(bottomRight),
                    bottomLeft = CornerRadius(bottomLeft)
                )
            )
        }
        return Outline.Generic(
            Path().apply {
                addContinuousRoundRect(
                    width = size.width,
                    height = size.height,
                    topLeft = topLeft,
                    topRight = topRight,
                    bottomRight = bottomRight,
                    bottomLeft = bottomLeft,
                    extension = extension
                )
            }
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContinuousRoundedCornerShape) return false
        return topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart &&
            extension == other.extension
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + extension.hashCode()
        return result
    }

    override fun toString(): String =
        "ContinuousRoundedCornerShape(topStart=$topStart, topEnd=$topEnd, " +
            "bottomEnd=$bottomEnd, bottomStart=$bottomStart)"
}

fun ContinuousRoundedCornerShape(radius: Dp): ContinuousRoundedCornerShape {
    val corner = CornerSize(radius)
    return ContinuousRoundedCornerShape(corner, corner, corner, corner)
}

fun ContinuousRoundedCornerShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp
): ContinuousRoundedCornerShape = ContinuousRoundedCornerShape(
    topStart = CornerSize(topStart),
    topEnd = CornerSize(topEnd),
    bottomEnd = CornerSize(bottomEnd),
    bottomStart = CornerSize(bottomStart)
)

internal fun Path.addContinuousRoundRect(
    width: Float,
    height: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
    extension: Float = CONTINUOUS_CORNER_EXTENSION
) {
    if (width <= 0f || height <= 0f) return
    val halfMin = min(width, height) * 0.5f
    val ext = extension.coerceIn(1f, 2f)
    fun tile(radius: Float) = max(0f, radius * ext).coerceAtMost(halfMin)
    val tl = tile(topLeft)
    val tr = tile(topRight)
    val br = tile(bottomRight)
    val bl = tile(bottomLeft)
    val handle = 1f - CONTINUOUS_CORNER_CONTROL
    moveTo(tl, 0f)
    lineTo(width - tr, 0f)
    cubicTo(width - tr * handle, 0f, width, tr * handle, width, tr)
    lineTo(width, height - br)
    cubicTo(width, height - br * handle, width - br * handle, height, width - br, height)
    lineTo(bl, height)
    cubicTo(bl * handle, height, 0f, height - bl * handle, 0f, height - bl)
    lineTo(0f, tl)
    cubicTo(0f, tl * handle, tl * handle, 0f, tl, 0f)
    close()
}
