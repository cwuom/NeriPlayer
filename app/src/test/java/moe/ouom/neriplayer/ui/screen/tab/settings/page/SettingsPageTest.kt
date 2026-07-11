package moe.ouom.neriplayer.ui.screen.tab.settings.page

import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SettingsPageTest {
    @Test
    fun settingsHomePageGroupsDoNotCaptureNullDuringEnumInitialization() {
        @Suppress("UNCHECKED_CAST")
        val groups = SettingsHomePageGroups as List<List<SettingsPage?>>

        groups.flatten().forEach { page ->
            assertNotNull(page)
        }
    }

    @Test
    fun schemaBackedPagesUseSourceMetadata() {
        assertEquals(AutoSettingsSchema.general.metadata.titleRes, SettingsPage.General.titleRes)
        assertEquals(AutoSettingsSchema.general.metadata.descriptionRes, SettingsPage.General.descriptionRes)
        assertEquals(AutoSettingsSchema.backup.metadata.titleRes, SettingsPage.Backup.titleRes)
        assertEquals(AutoSettingsSchema.backup.metadata.descriptionRes, SettingsPage.Backup.descriptionRes)
    }

    @Test
    fun usbExclusiveBackTargetReturnsPlaybackPage() {
        assertEquals(SettingsPage.Playback, SettingsPage.UsbExclusive.backTargetPage())
        assertEquals(null, SettingsPage.Playback.backTargetPage())
    }
}
