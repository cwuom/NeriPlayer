package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationPolicyTest {
    @Test
    fun `glass handoff requires both isolation and an active transition`() {
        assertFalse(shouldHandoffGlass(isolated = false, transitionRunning = false))
        assertFalse(shouldHandoffGlass(isolated = false, transitionRunning = true))
        assertFalse(shouldHandoffGlass(isolated = true, transitionRunning = false))
        assertTrue(shouldHandoffGlass(isolated = true, transitionRunning = true))
    }

    @Test
    fun `glass scene stays active for isolation or the selected page`() {
        assertTrue(shouldShowActiveGlassScene(true, SettingsPage.General, SettingsPage.Theme))
        assertTrue(shouldShowActiveGlassScene(false, SettingsPage.General, SettingsPage.General))
        assertFalse(shouldShowActiveGlassScene(false, SettingsPage.General, SettingsPage.Theme))
    }

    @Test
    fun `opening a detail page moves forward and returning moves back`() {
        assertTrue(isForwardSettingsPageTransition(SettingsPage.Playback, SettingsPage.UsbExclusive))
        assertFalse(isForwardSettingsPageTransition(SettingsPage.UsbExclusive, SettingsPage.Playback))
        assertTrue(isForwardSettingsPageTransition(SettingsPage.Storage, SettingsPage.StorageCacheDetails))
        assertFalse(isForwardSettingsPageTransition(SettingsPage.StorageCacheDetails, SettingsPage.Storage))
    }

    @Test
    fun `regular pages follow order while a missing target cannot move forward`() {
        assertTrue(isForwardSettingsPageTransition(null, SettingsPage.General))
        assertFalse(isForwardSettingsPageTransition(null, null))
        assertFalse(isForwardSettingsPageTransition(SettingsPage.General, null))
        assertTrue(isForwardSettingsPageTransition(SettingsPage.General, SettingsPage.Theme))
        assertFalse(isForwardSettingsPageTransition(SettingsPage.Theme, SettingsPage.General))
    }
}
