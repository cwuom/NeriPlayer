package moe.ouom.neriplayer.ui.screen.tab.settings.page

import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLiquidGlassSearchTargetTest {
    @Test
    fun liquidGlassSearchTargetsTheAlwaysVisibleBlurPrerequisite() {
        val setting = AutoSettingsMetadata.settings.single { it.keyName == "liquid_glass_enabled" }

        assertEquals(SettingsPage.Motion, setting.settingsPage())
        assertEquals("setting:advanced_blur_enabled", setting.searchTargetId())
        assertEquals(
            2,
            settingsSearchScrollAnchor(SettingsPage.Motion, setting.searchTargetId()).itemIndex
        )
    }
}
