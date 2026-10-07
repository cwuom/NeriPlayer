package moe.ouom.neriplayer.data.auth.web

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeWebLoginSavedSessionTest {
    @Test
    fun `sessions carrying their real save time complete once the page confirms them`() {
        assertTrue(shouldAutoCompleteYouTubeWebLogin(liveSession(savedAt = SAVED_AT), pageConfirmedSession = true))
        assertFalse(shouldAutoCompleteYouTubeWebLogin(liveSession(savedAt = SAVED_AT), pageConfirmedSession = false))
    }

    @Test
    fun `saved bundles without session cookies never complete`() {
        val loginInfoOnly = YouTubeAuthBundle(cookies = mapOf("LOGIN_INFO" to "login-token"), savedAt = SAVED_AT)

        assertFalse(shouldAutoCompleteYouTubeWebLogin(loginInfoOnly, pageConfirmedSession = true))
    }

    private fun liveSession(savedAt: Long) = YouTubeAuthBundle(
        cookies = linkedMapOf(
            "SAPISID" to "sapisid-cookie",
            "__Secure-1PAPISID" to "papisid-cookie",
            "__Secure-1PSIDTS" to "session-token",
            "SID" to "sid-cookie",
            "SIDCC" to "sidcc-cookie"
        ),
        xGoogAuthUser = "0",
        savedAt = savedAt
    )

    private companion object {
        const val SAVED_AT = 1_700_000_000_000L
    }
}
