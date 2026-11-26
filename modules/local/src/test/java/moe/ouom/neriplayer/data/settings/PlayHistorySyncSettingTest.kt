package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.data.settings.generated.AutoSettingsBackupKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSections
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ksp.annotations.SettingValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistorySyncSettingTest {
    @Test
    fun `platform play history sync switches are opt in and exportable`() {
        listOf(
            AutoSettingsSchema.playback.neteasePlayHistorySyncEnabled,
            AutoSettingsSchema.playback.biliPlayHistorySyncEnabled
        ).forEach { setting ->
            val metadata = AutoSettingsMetadata.setting(setting.key)

            assertFalse("${setting.key} must default to off", setting.defaultValue)
            assertEquals(SettingValueType.Boolean, metadata?.valueType)
            assertEquals(SettingUiType.Custom, metadata?.ui)
            assertEquals(AutoSettingsSections.playback, metadata?.section)
            assertTrue(AutoSettingsBackupKeys.booleanKeys.any { it.name == setting.key })
        }
    }
}
