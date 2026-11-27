package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.ui.screen.tab.settings.home.effectiveSettingsStartDestination
import moe.ouom.neriplayer.ui.screen.tab.settings.home.isSettingsHomeStartAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.home.internationalHomeSectionTitles
import moe.ouom.neriplayer.ui.screen.tab.settings.home.settingsStartDestinationLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.R as AppR

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
    fun `Bilibili alone keeps home available until its own switch is disabled`() {
        assertTrue(isSettingsHomeStartAvailable(false, false, false, false, false, showBilibili = true))
        assertFalse(isSettingsHomeStartAvailable(false, false, false, false, false, showBilibili = false))
    }

    @Test
    fun `international availability follows its own sections and local history`() {
        val layout = InternationalHomeLayout()
        val continueOnly = layout.copy(hidden = setOf(InternationalHomeSectionIds.SPOTLIGHT, InternationalHomeSectionIds.FEED))
        val none = layout.copy(hidden = layout.order.toSet())
        assertTrue(isSettingsHomeStartAvailable(false, false, false, false, false, false, layout))
        assertTrue(isSettingsHomeStartAvailable(false, false, false, false, true, false, continueOnly))
        assertFalse(isSettingsHomeStartAvailable(true, true, true, true, false, true, continueOnly))
        assertFalse(isSettingsHomeStartAvailable(true, true, true, true, true, true, none))
    }

    @Test
    fun `unavailable home redirects only a configured home destination`() {
        assertEquals("explore", effectiveSettingsStartDestination("home", false))
        assertEquals("home", effectiveSettingsStartDestination("home", true))
        assertEquals("library", effectiveSettingsStartDestination("library", false))
    }

    @Test
    fun `each international section has its own editor label`() {
        assertEquals(
            mapOf(
                InternationalHomeSectionIds.SPOTLIGHT to CoreCommonR.string.international_home_spotlight,
                InternationalHomeSectionIds.CONTINUE to CoreCommonR.string.player_continue,
                InternationalHomeSectionIds.FEED to CoreCommonR.string.international_home_feed
            ),
            internationalHomeSectionTitles
        )
    }

    @Test
    fun `start destination labels keep the original fallback`() {
        assertEquals(AppR.string.nav_explore, settingsStartDestinationLabelRes("explore"))
        assertEquals(AppR.string.nav_library, settingsStartDestinationLabelRes("library"))
        assertEquals(AppR.string.nav_settings, settingsStartDestinationLabelRes("settings"))
        assertEquals(AppR.string.nav_home, settingsStartDestinationLabelRes("home"))
        assertEquals(AppR.string.nav_home, settingsStartDestinationLabelRes("unknown"))
    }
}
