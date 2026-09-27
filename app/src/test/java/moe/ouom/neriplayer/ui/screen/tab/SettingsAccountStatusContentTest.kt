package moe.ouom.neriplayer.ui.screen.tab

import android.content.res.Resources
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
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
    fun `valid account shows supplied relative saved time`() {
        `when`(resources.getString(R.string.settings_bili_status_valid, "relative"))
            .thenReturn("valid relative")

        val actual = accountStatusText(
            resources, true, true, "relative",
            R.string.settings_bili_status_valid,
            R.string.settings_bili_status_saved_invalid,
            R.string.settings_bili_status_missing
        )

        assertEquals("valid relative", actual)
        verify(resources).getString(R.string.settings_bili_status_valid, "relative")
    }

    @Test
    fun `expired saved credentials and missing credentials use different status`() {
        `when`(resources.getString(R.string.settings_youtube_status_saved_invalid))
            .thenReturn("saved invalid")
        `when`(resources.getString(R.string.settings_youtube_status_missing))
            .thenReturn("missing")

        val saved = accountStatusText(
            resources, false, true, "unused",
            R.string.settings_youtube_status_valid,
            R.string.settings_youtube_status_saved_invalid,
            R.string.settings_youtube_status_missing
        )
        val missing = accountStatusText(
            resources, false, false, "unused",
            R.string.settings_youtube_status_valid,
            R.string.settings_youtube_status_saved_invalid,
            R.string.settings_youtube_status_missing
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
