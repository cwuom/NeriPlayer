package moe.ouom.neriplayer.ui.effect

import android.os.PowerManager
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.ui.effect.glass.VisualEffectsDeviceProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPowerPressureTest {
    @Test
    fun `only severe or worse thermal status counts as throttling`() {
        assertFalse(isSevereThermalThrottling(PowerManager.THERMAL_STATUS_NONE))
        assertFalse(isSevereThermalThrottling(PowerManager.THERMAL_STATUS_LIGHT))
        assertFalse(isSevereThermalThrottling(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(isSevereThermalThrottling(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(isSevereThermalThrottling(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(isSevereThermalThrottling(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `either power saver or thermal throttling reduces optional effects`() {
        assertFalse(SystemPowerPressure(powerSaveMode = false, thermalThrottling = false).reduceOptionalEffects)
        assertTrue(SystemPowerPressure(powerSaveMode = true, thermalThrottling = false).reduceOptionalEffects)
        assertTrue(SystemPowerPressure(powerSaveMode = false, thermalThrottling = true).reduceOptionalEffects)
    }

    @Test
    fun `thermal throttling pauses the lens and enhanced effects until the device cools`() {
        val cool = VisualEffectsDeviceProfile(
            powerSaveMode = false,
            lowRamDevice = false,
            dimensityBlurCompatibility = false
        )
        val hot = cool.copy(thermalThrottling = true)

        assertTrue(cool.liquidGlassAllowed(AdvancedBlurQuality.High))
        assertTrue(cool.enhancedVisualEffectsAllowed)
        assertFalse(hot.liquidGlassAllowed(AdvancedBlurQuality.High))
        assertFalse(hot.enhancedVisualEffectsAllowed)
    }
}
