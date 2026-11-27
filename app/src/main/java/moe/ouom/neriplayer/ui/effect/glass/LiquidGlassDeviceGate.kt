package moe.ouom.neriplayer.ui.effect.glass

import android.app.ActivityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.appearance.currentBuildUsesDimensityBlurCompatibility
import moe.ouom.neriplayer.ui.effect.rememberSystemPowerPressure

/**
 * Liquid glass adds a lens pass on top of the frosted mask. It is skipped in battery saver,
 * under severe thermal throttling, on low-RAM devices, and under the existing Dimensity blur
 * policy unless the user chose High quality
 */
internal fun resolveLiquidGlassDeviceAllowed(
    powerSaveMode: Boolean,
    lowRamDevice: Boolean,
    dimensityDevice: Boolean,
    quality: AdvancedBlurQuality,
    thermalThrottling: Boolean = false
): Boolean = !powerSaveMode && !thermalThrottling && !lowRamDevice &&
    (!dimensityDevice || quality == AdvancedBlurQuality.High)

/**
 * Enhanced visual effects (menu springs, dialog window blur, deeper bar highlights) only add
 * transient work, so they ignore the Dimensity blur policy but still back off in battery saver
 * and on low-RAM devices
 */
internal fun resolveEnhancedVisualEffectsAllowed(
    powerSaveMode: Boolean,
    lowRamDevice: Boolean,
    thermalThrottling: Boolean = false
): Boolean = !powerSaveMode && !thermalThrottling && !lowRamDevice

/** Device-wide constraints shared by every optional visual effect, observed once per app root. */
internal data class VisualEffectsDeviceProfile(
    val powerSaveMode: Boolean,
    val lowRamDevice: Boolean,
    val dimensityBlurCompatibility: Boolean,
    val thermalThrottling: Boolean = false
) {
    fun liquidGlassAllowed(quality: AdvancedBlurQuality): Boolean =
        resolveLiquidGlassDeviceAllowed(
            powerSaveMode, lowRamDevice, dimensityBlurCompatibility, quality, thermalThrottling
        )

    val enhancedVisualEffectsAllowed: Boolean
        get() = resolveEnhancedVisualEffectsAllowed(powerSaveMode, lowRamDevice, thermalThrottling)
}

@Composable
internal fun rememberVisualEffectsDeviceProfile(): VisualEffectsDeviceProfile {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val powerPressure = rememberSystemPowerPressure()
    val lowRamDevice = remember(appContext) {
        appContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }
    val dimensityBlurCompatibility = remember { currentBuildUsesDimensityBlurCompatibility() }
    return VisualEffectsDeviceProfile(
        powerSaveMode = powerPressure.powerSaveMode,
        lowRamDevice = lowRamDevice,
        dimensityBlurCompatibility = dimensityBlurCompatibility,
        thermalThrottling = powerPressure.thermalThrottling
    )
}
