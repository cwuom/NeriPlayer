package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeWebCookieStoreTest {

    @Test
    fun shouldApplyYouTubeConsentCookie_readdsConsentWhenReplacingExistingCookies() {
        assertTrue(
            shouldApplyYouTubeConsentCookie(
                includeConsentCookie = true,
                sanitizedCookies = emptyMap(),
                existingCookies = mapOf("SOCS" to "legacy"),
                replaceExisting = true
            )
        )
    }

    @Test
    fun shouldApplyYouTubeConsentCookie_skipsWhenPersistedConsentExists() {
        assertFalse(
            shouldApplyYouTubeConsentCookie(
                includeConsentCookie = true,
                sanitizedCookies = mapOf("SOCS" to "persisted"),
                existingCookies = emptyMap(),
                replaceExisting = true
            )
        )
    }

    @Test
    fun shouldApplyYouTubeConsentCookie_skipsWhenExistingConsentCanBeReused() {
        assertFalse(
            shouldApplyYouTubeConsentCookie(
                includeConsentCookie = true,
                sanitizedCookies = emptyMap(),
                existingCookies = mapOf("SOCS" to "existing"),
                replaceExisting = false
            )
        )
    }

    @Test
    fun resolveYouTubeWebCookieDomain_usesGoogleDomainForGoogleHosts() {
        assertTrue(
            resolveYouTubeWebCookieDomain("https://accounts.google.com/ServiceLogin") == ".google.com"
        )
    }

    @Test
    fun resolveYouTubeWebCookieDomain_usesYouTubeDomainForYouTubeHosts() {
        assertTrue(
            resolveYouTubeWebCookieDomain("https://music.youtube.com/") == ".youtube.com"
        )
    }
}
