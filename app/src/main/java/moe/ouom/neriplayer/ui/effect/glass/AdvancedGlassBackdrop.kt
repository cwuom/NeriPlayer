package moe.ouom.neriplayer.ui.effect.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

@Stable
internal class AdvancedGlassBackdrop internal constructor() {
    internal var positionInWindow: Offset by mutableStateOf(Offset.Unspecified)
    internal var renderEffect: RenderEffect? by mutableStateOf(null)
    internal var localBlurPlan: AdvancedGlassLocalBlurPlan? by mutableStateOf(null)
    internal var freezeLocalBlurFrame: Boolean by mutableStateOf(false)
    private var localBlurRenderer: Any? = null
    private var localBlurRendererCacheKey = NoLocalBlurRendererCacheKey
    private val localBlurHandoffGuards = mutableStateMapOf<Any, Unit>()

    internal val hasActiveBlur: Boolean
        get() = renderEffect != null || localBlurPlan != null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun localBlurRenderer(cacheKey: Int): AdvancedGlassLocalBlurRenderer {
        val existing = localBlurRenderer as? AdvancedGlassLocalBlurRenderer
        if (existing != null && localBlurRendererCacheKey == cacheKey) return existing
        return AdvancedGlassLocalBlurRenderer().also { renderer ->
            localBlurRenderer = renderer
            localBlurRendererCacheKey = cacheKey
        }
    }

    internal fun invalidateLocalBlurRenderer() {
        localBlurRenderer = null
        localBlurRendererCacheKey = NoLocalBlurRendererCacheKey
        freezeLocalBlurFrame = false
    }

    internal val hasLocalBlurHandoffGuard: Boolean
        get() = localBlurHandoffGuards.isNotEmpty()

    internal fun setLocalBlurHandoffGuard(key: Any, enabled: Boolean) {
        if (enabled) {
            localBlurHandoffGuards[key] = Unit
        } else {
            localBlurHandoffGuards.remove(key)
        }
    }

    internal fun removeLocalBlurHandoffGuard(key: Any) {
        localBlurHandoffGuards -= key
    }
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
    .drawWithContent {
        val plan = backdrop.localBlurPlan
        if (plan == null || Build.VERSION.SDK_INT < ADVANCED_GLASS_BACKEND_MIN_SDK) {
            drawContent()
        } else {
            backdrop.localBlurRenderer(plan.rendererCacheKey).render(
                scope = this,
                plan = plan,
                freezeFrame = backdrop.freezeLocalBlurFrame
            )
        }
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

private const val NoLocalBlurRendererCacheKey = Int.MIN_VALUE
