package moe.ouom.neriplayer.data.settings.appearance

import android.content.res.Configuration
import kotlin.math.roundToInt
import moe.ouom.neriplayer.data.settings.TABLET_SMALLEST_SCREEN_WIDTH_DP

private const val MIN_UI_DENSITY_SCALE = 0.6f
private const val PHONE_MAX_UI_DENSITY_SCALE = 1.2f
private const val CAR_MAX_UI_DENSITY_SCALE = 1.5f
private const val UI_DENSITY_SCALE_STEP = 0.05f

class UiDensityScaleRange internal constructor(val maximumScale: Float) {
    val valueRange: ClosedFloatingPointRange<Float> = MIN_UI_DENSITY_SCALE..maximumScale
    val steps: Int = ((maximumScale - MIN_UI_DENSITY_SCALE) / UI_DENSITY_SCALE_STEP).roundToInt() - 1

    fun normalize(scale: Float): Float = UiDensityScalePreference.normalize(scale).coerceAtMost(maximumScale)
}

object UiDensityScalePreference {
    fun forDevice(smallestScreenWidthDp: Int, uiMode: Int): UiDensityScaleRange {
        val carMode = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_CAR
        val maximum = when {
            carMode -> CAR_MAX_UI_DENSITY_SCALE
            smallestScreenWidthDp >= TABLET_SMALLEST_SCREEN_WIDTH_DP -> {
                // 用整数档位下取整，避免浮点除法丢失边界处的一档
                val stepsPerUnit = (1f / UI_DENSITY_SCALE_STEP).roundToInt().toLong()
                val maximumSteps = smallestScreenWidthDp.toLong() * stepsPerUnit / TABLET_SMALLEST_SCREEN_WIDTH_DP
                (maximumSteps.toDouble() / stepsPerUnit).toFloat()
            }
            else -> PHONE_MAX_UI_DENSITY_SCALE
        }
        return UiDensityScaleRange(maximum)
    }

    fun normalize(scale: Float): Float {
        if (!scale.isFinite()) return 1f
        return scale.coerceAtLeast(MIN_UI_DENSITY_SCALE)
    }
}
