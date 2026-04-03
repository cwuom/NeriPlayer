package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.auth.youtube.evaluateYouTubeAuthHealth
import moe.ouom.neriplayer.data.auth.youtube.parseCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.buildAuthCacheFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun evaluateYouTubeAuthHealth_shouldKeepLoginStateWithoutExpiryClassification() {
        val health = evaluateYouTubeAuthHealth(
            YouTubeAuthBundle(
                cookies = mapOf("LOGIN_INFO" to "token"),
                savedAt = 1_000L
            ),
            now = 2_000L
        )

        assertEquals(YouTubeAuthState.Valid, health.state)
        assertTrue(health.activeCookieKeys.isEmpty())
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateYouTubeAuthHealth_shouldTreatPsidTsAsActiveSessionCookie() {
        val health = evaluateYouTubeAuthHealth(
            YouTubeAuthBundle(
                cookies = mapOf(
                    "LOGIN_INFO" to "token",
                    "__Secure-1PSIDTS" to "session"
                ),
                savedAt = 1_000L
            ),
            now = 2_000L
        )

        assertEquals(YouTubeAuthState.Valid, health.state)
        assertFalse(health.shouldPromptRelogin)
        assertTrue(health.activeCookieKeys.contains("__Secure-1PSIDTS"))
    }

    @Test
    fun evaluateYouTubeAuthHealth_shouldKeepOldCookiesValidWithoutExpiryCheck() {
        val now = 40L * 24L * 60L * 60L * 1000L
        val bundle = YouTubeAuthBundle(
            cookies = mapOf("SAPISID" to "cookie-value"),
            savedAt = now - (90L * 24L * 60L * 60L * 1000L)
        )

        val health = evaluateYouTubeAuthHealth(bundle, now = now)

        assertEquals(YouTubeAuthState.Valid, health.state)
        assertFalse(health.shouldPromptRelogin)
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

    @Test
    fun buildAuthCacheFingerprint_shouldChangeWhenSessionContextChanges() {
        val base = YouTubeAuthBundle(
            cookies = mapOf("SAPISID" to "cookie-value"),
            xGoogAuthUser = "0",
            userAgent = "desktop-a"
        )

        val changedAuthUser = base.copy(xGoogAuthUser = "3")
        val changedUserAgent = base.copy(userAgent = "desktop-b")

        assertNotEquals(
            base.buildAuthCacheFingerprint(userAgent = base.userAgent),
            changedAuthUser.buildAuthCacheFingerprint(userAgent = changedAuthUser.userAgent)
        )
        assertNotEquals(
            base.buildAuthCacheFingerprint(userAgent = base.userAgent),
            changedUserAgent.buildAuthCacheFingerprint(userAgent = changedUserAgent.userAgent)
        )
    }
}
