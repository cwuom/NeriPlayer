package moe.ouom.neriplayer.platform.youtube.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthRefreshLoginPolicyTest {
    private val trustedLogin = "https://accounts.google.com/ServiceLogin?service=youtube"

    @Test
    fun `refresh pages settle once interactive or complete`() {
        assertFalse(isYouTubeRefreshPageSettled("loading"))
        assertTrue(isYouTubeRefreshPageSettled("Interactive"))
        assertTrue(isYouTubeRefreshPageSettled("COMPLETE"))
    }

    @Test
    fun `refresh login urls must be https on a trusted login host`() {
        assertTrue(isTrustedYouTubeRefreshLoginUrl("HTTPS://accounts.google.com/ServiceLogin"))
        assertFalse(isTrustedYouTubeRefreshLoginUrl("http://accounts.google.com/ServiceLogin"))
        assertFalse(isTrustedYouTubeRefreshLoginUrl("accounts.google.com/ServiceLogin"))
        assertFalse(isTrustedYouTubeRefreshLoginUrl("https://bad host/ServiceLogin"))
        assertFalse(isTrustedYouTubeRefreshLoginUrl("https://accounts.example.com/ServiceLogin"))
    }

    @Test
    fun `refresh login url falls back to a service login that returns to the page`() {
        assertEquals(
            trustedLogin,
            resolveYouTubeRefreshLoginUrl(currentUrl = "", signInUrl = "  $trustedLogin  ", hasYtcfg = false)
        )
        assertEquals("", resolveYouTubeRefreshLoginUrl(currentUrl = "https://music.youtube.com/", signInUrl = "", hasYtcfg = false))
        assertEquals(
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com",
            resolveYouTubeRefreshLoginUrl(
                currentUrl = "   ",
                signInUrl = "https://accounts.example.com/login",
                hasYtcfg = true
            )
        )
        assertEquals(
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=" +
                "https%3A%2F%2Fmusic.youtube.com%2Flibrary%3Fx%3D1",
            resolveYouTubeRefreshLoginUrl(
                currentUrl = " https://music.youtube.com/library?x=1 ",
                signInUrl = "  ",
                hasYtcfg = true
            )
        )
    }

    @Test
    fun `refresh login needs a settled guest page and a trusted login url`() {
        fun trigger(
            pageReady: Boolean = true,
            hasYtcfg: Boolean = true,
            loginUrl: String = trustedLogin,
            hasActiveSessionCookies: Boolean = false
        ) = shouldTriggerYouTubeRefreshLogin(
            pageReady = pageReady,
            hasYtcfg = hasYtcfg,
            hasLiveSessionSignal = false,
            loginUrl = loginUrl,
            hasActiveSessionCookies = hasActiveSessionCookies
        )

        assertTrue(trigger())
        assertFalse(trigger(hasActiveSessionCookies = true))
        assertFalse(trigger(pageReady = false))
        assertFalse(trigger(hasYtcfg = false))
        assertFalse(trigger(loginUrl = " "))
        assertFalse(trigger(loginUrl = "https://accounts.example.com/login"))
    }
}
