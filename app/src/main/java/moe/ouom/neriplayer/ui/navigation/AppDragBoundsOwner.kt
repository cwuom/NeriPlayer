package moe.ouom.neriplayer.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

internal data class AppDragBounds(
    val miniPlayer: Rect?,
    val bottomTabBar: Rect?
)

@Stable
internal class AppDragBoundsOwner {
    private var measuredMiniPlayerBounds by mutableStateOf<Rect?>(null)
    private var measuredTabBarBounds by mutableStateOf<Rect?>(null)

    fun updateMiniPlayerBounds(bounds: Rect) {
        measuredMiniPlayerBounds = bounds
    }

    fun updateTabBarBounds(bounds: Rect) {
        measuredTabBarBounds = bounds
    }

    fun visibleBounds(hasSong: Boolean, showNowPlaying: Boolean): AppDragBounds = AppDragBounds(
        miniPlayer = visibleMeasuredBounds(measuredMiniPlayerBounds, hasSong && !showNowPlaying),
        bottomTabBar = visibleMeasuredBounds(measuredTabBarBounds, !showNowPlaying)
    )

    private fun visibleMeasuredBounds(bounds: Rect?, visible: Boolean): Rect? =
        bounds?.takeIf { visible && !it.isEmpty }
}

internal fun Modifier.reportBottomTabBounds(
    tabInsets: WindowInsets,
    density: Density,
    layoutDirection: LayoutDirection,
    onBoundsChanged: (Rect) -> Unit
): Modifier = onGloballyPositioned { coordinates ->
    onBoundsChanged(
        resolveBottomTabDragBounds(
            bounds = coordinates.boundsInRoot(),
            leftInset = tabInsets.getLeft(density, layoutDirection),
            rightInset = tabInsets.getRight(density, layoutDirection),
            bottomInset = tabInsets.getBottom(density)
        )
    )
}

internal fun resolveBottomTabDragBounds(
    bounds: Rect,
    leftInset: Int,
    rightInset: Int,
    bottomInset: Int
): Rect {
    val left = bounds.left + leftInset
    return bounds.copy(
        left = left,
        right = (bounds.right - rightInset).coerceAtLeast(left),
        bottom = (bounds.bottom - bottomInset).coerceAtLeast(bounds.top)
    )
}
