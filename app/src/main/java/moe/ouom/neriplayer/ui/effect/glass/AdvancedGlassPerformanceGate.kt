package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import kotlin.math.min
import kotlin.math.roundToInt

internal fun resolveStableAdvancedGlassRenderRegions(
    backdropPositionInWindow: Offset,
    regions: List<AdvancedGlassRegion>,
    backdropScaleInWindow: Offset = Offset(1f, 1f),
    backdropCoordinates: LayoutCoordinates? = null
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
        val local = localBoundsInBackdrop(backdropCoordinates, region.layoutCoordinates)
        if (local != null) {
            return@map AdvancedGlassRenderRegion(
                left = local.left.roundToPhysicalPixel(),
                top = local.top.roundToPhysicalPixel(),
                right = local.right.roundToPhysicalPixel(),
                bottom = local.bottom.roundToPhysicalPixel(),
                cornerRadiiPx = region.cornerRadiiPx.toBackdropLocalPixels(cornerScale),
                opacity = region.opacity.coerceIn(0f, 1f),
                lens = if (isGlobalAdvancedGlassNavigation(region.role)) 1f else 0f
            )
        }
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
            opacity = region.opacity.coerceIn(0f, 1f),
            lens = if (isGlobalAdvancedGlassNavigation(region.role)) 1f else 0f
        )
    }
}

/**
 * Region bounds straight in the backdrop's local space. Window positions are sampled per node
 * at different times, and while the IME pans the window they disagree by the pan offset, which
 * shifts the blur away from part of a card.
 */
private fun localBoundsInBackdrop(
    backdrop: LayoutCoordinates?,
    region: LayoutCoordinates?
): Rect? {
    if (backdrop == null || region == null || !backdrop.isAttached || !region.isAttached) return null
    return runCatching { backdrop.localBoundingBoxOf(region, clipBounds = true) }
        .getOrNull()
        ?.takeIf { it.width > 0f && it.height > 0f }
}

private fun AdvancedGlassCornerRadii.toBackdropLocalPixels(scale: Float) = AdvancedGlassCornerRadii(
    topLeft = (topLeft / scale).roundToPhysicalPixel(),
    topRight = (topRight / scale).roundToPhysicalPixel(),
    bottomRight = (bottomRight / scale).roundToPhysicalPixel(),
    bottomLeft = (bottomLeft / scale).roundToPhysicalPixel()
)

private fun Float.roundToPhysicalPixel(): Float = roundToInt().toFloat()
