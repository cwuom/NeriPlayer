package moe.ouom.neriplayer.ui.screen.host

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHostPageSelectionTest {
    @Test
    fun selectsThePageForEachNavigationState() {
        assertEquals(
            "settings",
            selectSettingsHostPage(SettingsScreenState.Settings, "settings", "manager", "progress")
        )
        assertEquals(
            "manager",
            selectSettingsHostPage(SettingsScreenState.DownloadManager, "settings", "manager", "progress")
        )
        assertEquals(
            "progress",
            selectSettingsHostPage(SettingsScreenState.DownloadProgress, "settings", "manager", "progress")
        )
    }
}
