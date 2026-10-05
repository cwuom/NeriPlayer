package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountEntryAction
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.accountStatusText
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.hasValidSavedCookieHealth
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.hasValidYouTubeAuthHealth
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.shouldLoadSettingsAccountProfiles
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SettingsAccountStatusContentTest {
    private val resources = mock(Resources::class.java)

    @Test
    fun `account profiles load only while the visible account page owns the active screen`() {
        assertTrue(shouldLoadSettingsAccountProfiles(true, true, SettingsPage.Accounts))
        assertFalse(shouldLoadSettingsAccountProfiles(false, true, SettingsPage.Accounts))
        assertFalse(shouldLoadSettingsAccountProfiles(true, false, SettingsPage.Accounts))
        assertFalse(shouldLoadSettingsAccountProfiles(true, true, SettingsPage.General))
        assertFalse(shouldLoadSettingsAccountProfiles(true, true, null))
    }

    @Test
    fun `valid account shows supplied relative saved time`() {
        `when`(resources.getString(CoreCommonR.string.settings_bili_status_valid, "relative"))
            .thenReturn("valid relative")

        val actual = accountStatusText(
            resources, true, true, "relative",
            CoreCommonR.string.settings_bili_status_valid,
            CoreCommonR.string.settings_bili_status_saved_invalid,
            CoreCommonR.string.settings_bili_status_missing
        )

        assertEquals("valid relative", actual)
        verify(resources).getString(CoreCommonR.string.settings_bili_status_valid, "relative")
    }

    @Test
    fun `expired saved credentials and missing credentials use different status`() {
        `when`(resources.getString(CoreCommonR.string.settings_youtube_status_saved_invalid))
            .thenReturn("saved invalid")
        `when`(resources.getString(CoreCommonR.string.settings_youtube_status_missing))
            .thenReturn("missing")

        val saved = accountStatusText(
            resources, false, true, "unused",
            CoreCommonR.string.settings_youtube_status_valid,
            CoreCommonR.string.settings_youtube_status_saved_invalid,
            CoreCommonR.string.settings_youtube_status_missing
        )
        val missing = accountStatusText(
            resources, false, false, "unused",
            CoreCommonR.string.settings_youtube_status_valid,
            CoreCommonR.string.settings_youtube_status_saved_invalid,
            CoreCommonR.string.settings_youtube_status_missing
        )

        assertEquals("saved invalid", saved)
        assertEquals("missing", missing)
    }

    @Test
    fun `saved cookie and youtube health map valid status consistently`() {
        assertTrue(hasValidSavedCookieHealth(SavedCookieAuthState.Valid))
        assertFalse(hasValidSavedCookieHealth(SavedCookieAuthState.Checking))
        assertFalse(hasValidSavedCookieHealth(SavedCookieAuthState.Missing))
        assertTrue(hasValidYouTubeAuthHealth(YouTubeAuthState.Valid))
        assertFalse(hasValidYouTubeAuthHealth(YouTubeAuthState.Missing))
    }

    @Test
    fun `account row opens saved credential action only when credentials exist`() {
        val calls = mutableListOf<String>()
        SettingsAccountEntryAction(true, { calls += "saved" }, { calls += "sheet" }).onClick()
        SettingsAccountEntryAction(false, { calls += "saved" }, { calls += "sheet" }).onClick()

        assertEquals(listOf("saved", "sheet"), calls)
    }
}
