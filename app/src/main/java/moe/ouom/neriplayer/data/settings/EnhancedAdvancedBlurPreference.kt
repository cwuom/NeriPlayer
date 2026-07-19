package moe.ouom.neriplayer.data.settings

import kotlin.math.roundToInt

const val DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP = 36f
const val MIN_ENHANCED_ADVANCED_BLUR_RADIUS_DP = 12f
const val MAX_ENHANCED_ADVANCED_BLUR_RADIUS_DP = 64f
const val ENHANCED_ADVANCED_BLUR_RADIUS_STEP_DP = 4f

object EnhancedAdvancedBlurPreference {
    fun normalize(radiusDp: Float): Float {
        if (!radiusDp.isFinite()) {
            return DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
        }
        val clamped = radiusDp.coerceIn(
            MIN_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            MAX_ENHANCED_ADVANCED_BLUR_RADIUS_DP
        )
        val steps = (clamped / ENHANCED_ADVANCED_BLUR_RADIUS_STEP_DP).roundToInt()
        return steps * ENHANCED_ADVANCED_BLUR_RADIUS_STEP_DP
    }
}
