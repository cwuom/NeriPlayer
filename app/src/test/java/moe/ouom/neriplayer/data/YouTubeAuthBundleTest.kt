package moe.ouom.neriplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthBundleTest {

    @Test
    fun parseCookieHeader_shouldSplitCookiePairs() {
        val parsed = parseCookieHeader("SAPISID=abc; __Secure-1PAPISID=def; VISITOR_INFO1_LIVE=ghi")

        assertEquals("abc", parsed["SAPISID"])
        assertEquals("def", parsed["__Secure-1PAPISID"])
        assertEquals("ghi", parsed["VISITOR_INFO1_LIVE"])
    }

    @Test
    fun normalized_shouldBackfillCookieHeaderFromCookieMap() {
        val bundle = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SAPISID" to "abc",
                "__Secure-1PAPISID" to "def"
            )
        ).normalized(savedAt = 123L)

        assertTrue(bundle.cookieHeader.contains("SAPISID=abc"))
        assertTrue(bundle.cookieHeader.contains("__Secure-1PAPISID=def"))
        assertEquals(123L, bundle.savedAt)
    }

    @Test
    fun isUsable_shouldRequireAuthSignal() {
        assertFalse(YouTubeAuthBundle().isUsable())
        assertTrue(YouTubeAuthBundle(authorization = "SAPISIDHASH 1_abc").isUsable())
        assertTrue(
            YouTubeAuthBundle(
                cookies = mapOf("__Secure-1PAPISID" to "cookie-value")
            ).isUsable()
        )
    }

    @Test
    fun evaluateYouTubeAuthHealth_shouldReturnMissingWithoutImportantCookies() {
        val health = evaluateYouTubeAuthHealth(
            YouTubeAuthBundle(
                cookies = mapOf("VISITOR_INFO1_LIVE" to "visitor")
            ),
            now = 1_000L
        )

        assertEquals(YouTubeAuthState.Missing, health.state)
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateYouTubeAuthHealth_shouldReturnExpiredWithoutActiveSessionCookie() {
        val health = evaluateYouTubeAuthHealth(
            YouTubeAuthBundle(
                cookies = mapOf("LOGIN_INFO" to "token"),
                savedAt = 1_000L
            ),
            now = 2_000L
        )

        assertEquals(YouTubeAuthState.Expired, health.state)
        assertTrue(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateYouTubeAuthHealth_shouldReturnStaleForOldCookies() {
        val now = 40L * 24L * 60L * 60L * 1000L
        val bundle = YouTubeAuthBundle(
            cookies = mapOf("SAPISID" to "cookie-value"),
            savedAt = now - YOUTUBE_AUTH_STALE_AFTER_MS - 1L
        )

        val health = evaluateYouTubeAuthHealth(bundle, now = now)

        assertEquals(YouTubeAuthState.Stale, health.state)
        assertTrue(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateYouTubeAuthHealth_shouldReturnValidForRecentCookies() {
        val now = 10_000L
        val bundle = YouTubeAuthBundle(
            cookies = mapOf("SAPISID" to "cookie-value"),
            savedAt = now - 1_000L
        )

        val health = evaluateYouTubeAuthHealth(bundle, now = now)

        assertEquals(YouTubeAuthState.Valid, health.state)
        assertFalse(health.shouldPromptRelogin)
    }
}
