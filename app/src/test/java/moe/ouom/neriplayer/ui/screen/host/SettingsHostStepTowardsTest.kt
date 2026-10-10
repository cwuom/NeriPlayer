package moe.ouom.neriplayer.ui.screen.host

import moe.ouom.neriplayer.ui.screen.host.SettingsScreenState.DownloadManager
import moe.ouom.neriplayer.ui.screen.host.SettingsScreenState.DownloadProgress
import moe.ouom.neriplayer.ui.screen.host.SettingsScreenState.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHostStepTowardsTest {

    @Test
    fun `deeper requests advance exactly one level at a time`() {
        assertEquals(DownloadManager, Settings.nextTowards(DownloadManager))
        assertEquals(DownloadManager, Settings.nextTowards(DownloadProgress))
        assertEquals(DownloadProgress, DownloadManager.nextTowards(DownloadProgress))
    }

    @Test
    fun `shallower requests step back exactly one level at a time`() {
        assertEquals(DownloadManager, DownloadProgress.nextTowards(Settings))
        assertEquals(DownloadManager, DownloadProgress.nextTowards(DownloadManager))
        assertEquals(Settings, DownloadManager.nextTowards(Settings))
    }

    @Test
    fun `reaching the requested level stays put`() {
        SettingsScreenState.entries.forEach { state ->
            assertEquals(state, state.nextTowards(state))
        }
    }
}
