package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSections
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ksp.annotations.SettingValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BiliSponsorBlockSettingTest {
    @Test
    fun `bili sponsor block switch is opt in and belongs to playback`() {
        val setting = AutoSettingsSchema.playback.biliSponsorBlockEnabled
        val metadata = AutoSettingsMetadata.setting("bili_sponsor_block_enabled")

        assertEquals("bili_sponsor_block_enabled", setting.key)
        assertFalse(setting.defaultValue)
        assertEquals(SettingValueType.Boolean, metadata?.valueType)
        assertEquals(SettingUiType.Switch, metadata?.ui)
        assertEquals(AutoSettingsSections.playback, metadata?.section)
        assertEquals(CoreCommonR.string.settings_bili_sponsor_block, metadata?.titleRes)
    }
}
