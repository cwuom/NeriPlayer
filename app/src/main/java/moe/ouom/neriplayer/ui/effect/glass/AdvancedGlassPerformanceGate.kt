package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import kotlin.math.roundToInt

internal fun resolveStableAdvancedGlassRenderRegions(
    backdropPositionInWindow: Offset,
    regions: List<AdvancedGlassRegion>
): List<AdvancedGlassRenderRegion> {
    if (!backdropPositionInWindow.isSpecified) return emptyList()

    return regions.map { region ->
        val bounds = region.boundsInWindow
        AdvancedGlassRenderRegion(
            left = (bounds.left - backdropPositionInWindow.x).roundToPhysicalPixel(),
            top = (bounds.top - backdropPositionInWindow.y).roundToPhysicalPixel(),
            right = (bounds.right - backdropPositionInWindow.x).roundToPhysicalPixel(),
            bottom = (bounds.bottom - backdropPositionInWindow.y).roundToPhysicalPixel(),
            cornerRadiiPx = region.cornerRadiiPx.roundToPhysicalPixels()
        )
    }
}

private fun AdvancedGlassCornerRadii.roundToPhysicalPixels() = AdvancedGlassCornerRadii(
    topLeft = topLeft.roundToPhysicalPixel(),
    topRight = topRight.roundToPhysicalPixel(),
    bottomRight = bottomRight.roundToPhysicalPixel(),
    bottomLeft = bottomLeft.roundToPhysicalPixel()
)

private fun Float.roundToPhysicalPixel(): Float = roundToInt().toFloat()
