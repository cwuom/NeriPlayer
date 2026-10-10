package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsAccountProfileNormalizationTest {

    @Test
    fun `literal null and blank nicknames never produce a profile`() {
        for (nickname in listOf("null", "  null  ", "", " \t ")) {
            assertNull(nickname, settingsAccountProfile(nickname, "https://example.invalid/face.jpg"))
        }
    }

    @Test
    fun `secure avatars are trimmed and kept while nicknames containing null survive`() {
        assertEquals(
            SettingsAccountProfile("nullable fan", "https://example.invalid/face.jpg"),
            settingsAccountProfile(" nullable fan ", "  https://example.invalid/face.jpg  ")
        )
    }

    @Test
    fun `youtube snapshots are equal only for the same authorization identity`() {
        val auth = YouTubeAuthBundle(cookieHeader = "SAPISID=synthetic-first; SID=synthetic-first", savedAt = 12L)
        val first = SettingsYouTubeAccountAuthorizationSnapshot(auth)
        val repeated = SettingsYouTubeAccountAuthorizationSnapshot(auth.copy())

        assertEquals(first, repeated)
        assertEquals(first.hashCode(), repeated.hashCode())
        assertNotEquals(first, SettingsYouTubeAccountAuthorizationSnapshot(auth.copy(xGoogAuthUser = "2")))
        assertNotEquals(first, SettingsYouTubeAccountAuthorizationSnapshot(auth.copy(savedAt = 13L)))
    }

    @Test
    fun `youtube snapshots never equal other snapshot types or null`() {
        val first = SettingsYouTubeAccountAuthorizationSnapshot(
            YouTubeAuthBundle(cookieHeader = "SAPISID=synthetic-first", savedAt = 12L)
        )

        assertNotEquals(first, SettingsAccountAuthorizationSnapshot(mapOf("SAPISID" to "synthetic-first")))
        assertNotEquals(first, null)
    }
}
