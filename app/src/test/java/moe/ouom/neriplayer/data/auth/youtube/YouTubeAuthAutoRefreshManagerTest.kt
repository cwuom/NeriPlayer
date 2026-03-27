package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthAutoRefreshManagerTest {

    @Test
    fun shouldAcceptYouTubeRefreshResult_rejectsSettledGuestPage() {
        assertFalse(
            shouldAcceptYouTubeRefreshResult(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                authChanged = true,
                recoveredActiveSession = true
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_acceptsLiveSessionSignal() {
        assertTrue(
            shouldAcceptYouTubeRefreshResult(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = true,
                authChanged = false,
                recoveredActiveSession = false
            )
        )
    }

    @Test
    fun shouldAcceptYouTubeRefreshResult_allowsCookieRecoveryBeforePageSettles() {
        assertTrue(
            shouldAcceptYouTubeRefreshResult(
                pageReady = false,
                hasYtcfg = false,
                hasLiveSessionSignal = false,
                authChanged = true,
                recoveredActiveSession = false
            )
        )
    }

    @Test
    fun shouldTriggerYouTubeRefreshLogin_acceptsSettledGuestPageWithTrustedLoginUrl() {
        assertTrue(
            shouldTriggerYouTubeRefreshLogin(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = false,
                loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube"
            )
        )
    }

    @Test
    fun shouldTriggerYouTubeRefreshLogin_rejectsLiveSessionPage() {
        assertFalse(
            shouldTriggerYouTubeRefreshLogin(
                pageReady = true,
                hasYtcfg = true,
                hasLiveSessionSignal = true,
                loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube"
            )
        )
    }

    @Test
    fun resolveYouTubeRefreshLoginUrl_prefersTrustedSignInUrlFromPage() {
        assertEquals(
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/",
            resolveYouTubeRefreshLoginUrl(
                currentUrl = "https://music.youtube.com/",
                signInUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/",
                hasYtcfg = true
            )
        )
    }

    @Test
    fun resolveYouTubeRefreshLoginUrl_buildsGoogleFallbackForGuestPage() {
        assertEquals(
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F",
            resolveYouTubeRefreshLoginUrl(
                currentUrl = "https://music.youtube.com/",
                signInUrl = "",
                hasYtcfg = true
            )
        )
    }

    @Test
    fun resolveObservedYouTubeAuthUser_fallsBackToPageSessionIndex() {
        assertEquals(
            "7",
            resolveObservedYouTubeAuthUser(
                capturedAuthUser = "",
                pageSessionIndex = "7"
            )
        )
    }

    @Test
    fun resolveObservedYouTubeAuthUser_prefersCapturedAuthUser() {
        assertEquals(
            "3",
            resolveObservedYouTubeAuthUser(
                capturedAuthUser = "3",
                pageSessionIndex = "7"
            )
        )
    }
}
