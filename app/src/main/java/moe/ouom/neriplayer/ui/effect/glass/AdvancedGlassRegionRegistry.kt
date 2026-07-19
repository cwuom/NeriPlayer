package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect

internal data class AdvancedGlassRegion(
    val role: AdvancedGlassRole,
    val boundsInWindow: Rect,
    val cornerRadiiPx: AdvancedGlassCornerRadii,
    val navigationOwner: Any?
)

internal data class AdvancedGlassCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
) {
    companion object {
        val Zero = AdvancedGlassCornerRadii(0f, 0f, 0f, 0f)
    }
}

@Stable
internal class AdvancedGlassRegionRegistry {
    private val mutableRegions = mutableStateMapOf<Any, AdvancedGlassRegion>()
    private val handoffGuards = mutableStateMapOf<Any, Unit>()

    val regions: Collection<AdvancedGlassRegion>
        get() = mutableRegions.values

    val retainsEffectDuringHandoff: Boolean
        get() = handoffGuards.isNotEmpty()

    fun update(key: Any, region: AdvancedGlassRegion) {
        if (mutableRegions[key] != region) {
            mutableRegions[key] = region
        }
    }

    fun remove(key: Any) {
        mutableRegions.remove(key)
    }

    fun setHandoffGuard(key: Any, enabled: Boolean) {
        if (enabled) {
            handoffGuards[key] = Unit
        } else {
            handoffGuards.remove(key)
        }
    }

    fun removeHandoffGuard(key: Any) {
        handoffGuards.remove(key)
    }
}
