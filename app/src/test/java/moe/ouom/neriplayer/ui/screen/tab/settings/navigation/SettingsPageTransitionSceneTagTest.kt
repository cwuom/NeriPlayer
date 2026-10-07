package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPageTransitionSceneTagTest {

    @Test
    fun `settings home uses the home scene tag`() {
        assertEquals("settings-transition-scene-home", settingsPageTransitionSceneTag(null))
    }

    @Test
    fun `each settings page gets a distinct scene tag`() {
        val tags = SettingsPage.entries.map(::settingsPageTransitionSceneTag)

        assertEquals("settings-transition-scene-General", settingsPageTransitionSceneTag(SettingsPage.General))
        assertEquals(SettingsPage.entries.size, tags.toSet().size)
    }
}
