package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

@Stable
internal class AdvancedGlassBackdrop internal constructor() {
    internal var positionInWindow: Offset by mutableStateOf(Offset.Unspecified)
    internal var renderEffect: RenderEffect? by mutableStateOf(null)
}

@Composable
internal fun rememberAdvancedGlassBackdrop(): AdvancedGlassBackdrop = remember {
    AdvancedGlassBackdrop()
}

internal fun Modifier.captureAdvancedGlassBackdrop(
    backdrop: AdvancedGlassBackdrop
): Modifier = this
    .onGloballyPositioned { coordinates ->
        backdrop.positionInWindow = coordinates.attachedPositionInWindow()
    }
    .graphicsLayer {
        val effect = backdrop.renderEffect
        compositingStrategy = resolveAdvancedGlassCompositingStrategy(effect != null)
        renderEffect = effect
    }

internal fun resolveAdvancedGlassCompositingStrategy(
    hasRenderEffect: Boolean
): CompositingStrategy = if (hasRenderEffect) {
    CompositingStrategy.Offscreen
} else {
    CompositingStrategy.Auto
}

private fun LayoutCoordinates.attachedPositionInWindow(): Offset = if (isAttached) {
    positionInWindow()
} else {
    Offset.Unspecified
}
