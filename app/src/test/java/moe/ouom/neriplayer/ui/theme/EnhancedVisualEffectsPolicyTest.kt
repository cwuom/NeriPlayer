package moe.ouom.neriplayer.ui.theme

import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ui.effect.glass.VisualEffectsDeviceProfile
import moe.ouom.neriplayer.ui.effect.glass.resolveEnhancedVisualEffectsAllowed
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsSearchScrollAnchor
import moe.ouom.neriplayer.ui.screen.tab.settings.page.searchTargetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedVisualEffectsPolicyTest {
    @Test
    fun enhancedEffectsBackOffInBatterySaverAndOnLowRamDevices() {
        assertTrue(resolveEnhancedVisualEffectsAllowed(powerSaveMode = false, lowRamDevice = false))
        assertFalse(resolveEnhancedVisualEffectsAllowed(powerSaveMode = true, lowRamDevice = false))
        assertFalse(resolveEnhancedVisualEffectsAllowed(powerSaveMode = false, lowRamDevice = true))
    }

    @Test
    fun dimensityPolicyOnlyGatesLiquidGlass() {
        val dimensity = VisualEffectsDeviceProfile(
            powerSaveMode = false,
            lowRamDevice = false,
            dimensityBlurCompatibility = true
        )

        assertTrue(dimensity.enhancedVisualEffectsAllowed)
        assertFalse(dimensity.liquidGlassAllowed(AdvancedBlurQuality.Default))
        assertTrue(dimensity.liquidGlassAllowed(AdvancedBlurQuality.High))
    }

    @Test
    fun settingIsSearchableInTheGeneralMotionCard() {
        val setting = AutoSettingsMetadata.settings.single { it.keyName == "enhanced_visual_effects_enabled" }

        assertEquals(SettingUiType.Custom, setting.ui)
        assertEquals(SettingsPage.Motion, setting.settingsPage())
        assertEquals("setting:enhanced_visual_effects_enabled", setting.searchTargetId())
        assertEquals(1, settingsSearchScrollAnchor(SettingsPage.Motion, setting.searchTargetId()).itemIndex)
    }
}
