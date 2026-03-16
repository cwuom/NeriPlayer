package moe.ouom.neriplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliAuthRepositoryTest {

    @Test
    fun evaluateBiliAuthHealth_returnsMissingWithoutKnownCookies() {
        val health = evaluateBiliAuthHealth(
            BiliAuthBundle(
                cookies = mapOf("buvid3" to "value")
            ),
            now = 1_000L
        )

        assertEquals(SavedCookieAuthState.Missing, health.state)
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateBiliAuthHealth_returnsMissingWithoutSessdata() {
        val health = evaluateBiliAuthHealth(
            BiliAuthBundle(
                cookies = mapOf("DedeUserID" to "12345", "bili_jct" to "csrf"),
                savedAt = 1_000L
            ),
            now = 2_000L
        )

        assertEquals(SavedCookieAuthState.Missing, health.state)
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateBiliAuthHealth_returnsStaleForOldCookie() {
        val now = 50L * 24L * 60L * 60L * 1000L
        val snapshot = BiliAuthBundle(
            cookies = mapOf("SESSDATA" to "cookie"),
            savedAt = now - BILI_AUTH_STALE_AFTER_MS - 1L
        )

        val health = evaluateBiliAuthHealth(snapshot, now = now)

        assertEquals(SavedCookieAuthState.Stale, health.state)
        assertTrue(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateBiliAuthHealth_returnsValidForRecentCookie() {
        val now = 10_000L
        val snapshot = BiliAuthBundle(
            cookies = mapOf("SESSDATA" to "cookie"),
            savedAt = now - 1_000L
        )

        val health = evaluateBiliAuthHealth(snapshot, now = now)

        assertEquals(SavedCookieAuthState.Valid, health.state)
        assertFalse(health.shouldPromptRelogin)
    }
}
