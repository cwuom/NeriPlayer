package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.home.SettingsHomeCardCopy
import moe.ouom.neriplayer.ui.screen.tab.settings.home.effectiveSettingsStartDestination
import moe.ouom.neriplayer.ui.screen.tab.settings.home.isSettingsHomeStartAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.home.settingsHomeCardCopy
import moe.ouom.neriplayer.ui.screen.tab.settings.home.settingsStartDestinationLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeStartPresentationTest {
    @Test
    fun `home is available for any visible content card with usable data`() {
        assertTrue(isSettingsHomeStartAvailable(true, false, false, false, false))
        assertTrue(isSettingsHomeStartAvailable(false, true, false, false, false))
        assertTrue(isSettingsHomeStartAvailable(false, false, true, false, false))
        assertTrue(isSettingsHomeStartAvailable(false, false, false, true, true))
        assertFalse(isSettingsHomeStartAvailable(false, false, false, true, false))
        assertFalse(isSettingsHomeStartAvailable(false, false, false, false, true))
    }

    @Test
    fun `unavailable home redirects only a configured home destination`() {
        assertEquals("explore", effectiveSettingsStartDestination("home", false))
        assertEquals("home", effectiveSettingsStartDestination("home", true))
        assertEquals("library", effectiveSettingsStartDestination("library", false))
    }

    @Test
    fun `home copy switches the complete card family for international mode`() {
        val domestic = settingsHomeCardCopy(false)
        val international = settingsHomeCardCopy(true)

        assertEquals(
            SettingsHomeCardCopy(
                CoreCommonR.string.settings_home_card_netease_trending,
                CoreCommonR.string.settings_home_card_netease_radar,
                CoreCommonR.string.settings_home_card_netease_recommended,
                CoreCommonR.string.settings_home_card_netease_trending_desc,
                CoreCommonR.string.settings_home_card_netease_radar_desc,
                CoreCommonR.string.settings_home_card_netease_recommended_desc
            ),
            domestic
        )
        assertEquals(
            SettingsHomeCardCopy(
                CoreCommonR.string.home_ytmusic_guess_you_like,
                CoreCommonR.string.home_ytmusic_daily_discover,
                CoreCommonR.string.home_ytmusic_more_recommendations,
                CoreCommonR.string.settings_home_card_ytmusic_guess_you_like_desc,
                CoreCommonR.string.settings_home_card_ytmusic_daily_discover_desc,
                CoreCommonR.string.settings_home_card_ytmusic_more_recommendations_desc
            ),
            international
        )
    }

    @Test
    fun `start destination labels keep the original fallback`() {
        assertEquals(CoreCommonR.string.nav_explore, settingsStartDestinationLabelRes("explore"))
        assertEquals(CoreCommonR.string.nav_library, settingsStartDestinationLabelRes("library"))
        assertEquals(CoreCommonR.string.nav_settings, settingsStartDestinationLabelRes("settings"))
        assertEquals(CoreCommonR.string.nav_home, settingsStartDestinationLabelRes("home"))
        assertEquals(CoreCommonR.string.nav_home, settingsStartDestinationLabelRes("unknown"))
    }
}
