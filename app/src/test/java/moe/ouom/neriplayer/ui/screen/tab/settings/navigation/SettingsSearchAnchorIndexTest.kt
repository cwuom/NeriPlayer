package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsSearchScrollAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchAnchorIndexTest {
    @Test
    fun cleanThemeSearchReachesTheSameColorSpecCardAfterRemovingTheHero() {
        val anchor = settingsSearchScrollAnchor(SettingsPage.Theme, "manual:theme_color_spec")

        assertEquals(5, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Theme, clean = false))
        assertEquals(4, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Theme, clean = true))
    }

    @Test
    fun cleanBackgroundSearchStartsAtTheFirstCard() {
        val anchor = settingsSearchScrollAnchor(SettingsPage.Background, "setting:background_image_uri")

        assertEquals(1, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Background, clean = false))
        assertEquals(0, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Background, clean = true))
    }

    @Test
    fun pageSearchNeverProducesANegativeListIndex() {
        val anchor = settingsSearchScrollAnchor(SettingsPage.Theme, "page:Theme")

        assertEquals(0, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Theme, clean = false))
        assertEquals(0, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Theme, clean = true))
    }

    @Test
    fun accountsSearchKeepsItsHeaderlessAnchorInBothStyles() {
        val anchor = settingsSearchScrollAnchor(SettingsPage.Accounts, "page:Accounts")

        assertEquals(0, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Accounts, clean = false))
        assertEquals(0, settingsDetailListIndex(anchor.itemIndex, SettingsPage.Accounts, clean = true))
        assertEquals(2, settingsDetailListIndex(2, SettingsPage.Accounts, clean = true))
    }
}
