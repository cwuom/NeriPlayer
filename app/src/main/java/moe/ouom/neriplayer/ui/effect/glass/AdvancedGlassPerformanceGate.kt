package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import kotlin.math.min
import kotlin.math.roundToInt

internal fun resolveStableAdvancedGlassRenderRegions(
    backdropPositionInWindow: Offset,
    regions: List<AdvancedGlassRegion>,
    backdropScaleInWindow: Offset = Offset(1f, 1f)
): List<AdvancedGlassRenderRegion> {
    if (!backdropPositionInWindow.isSpecified || !backdropScaleInWindow.isSpecified ||
        !backdropScaleInWindow.x.isFinite() || !backdropScaleInWindow.y.isFinite() ||
        backdropScaleInWindow.x <= 0f || backdropScaleInWindow.y <= 0f
    ) {
        return emptyList()
    }

    // 窗口边界包含父级缩放，遮罩则画在背景层未缩放的局部坐标中
    val cornerScale = min(backdropScaleInWindow.x, backdropScaleInWindow.y)
    return regions.filter { it.opacity > 0f }.map { region ->
        val bounds = region.boundsInWindow
        AdvancedGlassRenderRegion(
            left = ((bounds.left - backdropPositionInWindow.x) / backdropScaleInWindow.x)
                .roundToPhysicalPixel(),
            top = ((bounds.top - backdropPositionInWindow.y) / backdropScaleInWindow.y)
                .roundToPhysicalPixel(),
            right = ((bounds.right - backdropPositionInWindow.x) / backdropScaleInWindow.x)
                .roundToPhysicalPixel(),
            bottom = ((bounds.bottom - backdropPositionInWindow.y) / backdropScaleInWindow.y)
                .roundToPhysicalPixel(),
            cornerRadiiPx = region.cornerRadiiPx.toBackdropLocalPixels(cornerScale),
            opacity = region.opacity.coerceIn(0f, 1f)
        )
    }
}

private fun AdvancedGlassCornerRadii.toBackdropLocalPixels(scale: Float) = AdvancedGlassCornerRadii(
    topLeft = (topLeft / scale).roundToPhysicalPixel(),
    topRight = (topRight / scale).roundToPhysicalPixel(),
    bottomRight = (bottomRight / scale).roundToPhysicalPixel(),
    bottomLeft = (bottomLeft / scale).roundToPhysicalPixel()
)

private fun Float.roundToPhysicalPixel(): Float = roundToInt().toFloat()
