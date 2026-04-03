package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.auth.bili.BiliAuthBundle
import moe.ouom.neriplayer.data.auth.bili.evaluateBiliAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliAuthRepositoryTest {

    @Test
    fun biliAuthBundle_jsonRoundTripDropsBlankKeysAndKeepsSavedAt() {
        val original = BiliAuthBundle(
            cookies = linkedMapOf(
                "SESSDATA" to "sess-cookie",
                "" to "ignored",
                "bili_jct" to "csrf-token"
            ),
            savedAt = 123L
        )

        val restored = BiliAuthBundle.fromJson(original.toJson())

        assertEquals("sess-cookie", restored.cookies["SESSDATA"])
        assertEquals("csrf-token", restored.cookies["bili_jct"])
        assertFalse(restored.cookies.containsKey(""))
        assertEquals(123L, restored.savedAt)
    }

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
    fun evaluateBiliAuthHealth_keepsSavedCookieValidWithoutExpiryCheck() {
        val now = 50L * 24L * 60L * 60L * 1000L
        val snapshot = BiliAuthBundle(
            cookies = mapOf("SESSDATA" to "cookie"),
            savedAt = now - (90L * 24L * 60L * 60L * 1000L)
        )

        val health = evaluateBiliAuthHealth(snapshot, now = now)

        assertEquals(SavedCookieAuthState.Valid, health.state)
        assertFalse(health.shouldPromptRelogin)
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
