package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.netease.NeteaseAuthBundle
import moe.ouom.neriplayer.data.auth.netease.evaluateNeteaseAuthHealth
import moe.ouom.neriplayer.data.auth.netease.validateAndSanitizeNeteaseCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseAuthRepositoryTest {

    @Test
    fun evaluateNeteaseAuthHealth_returnsMissingWithoutKnownCookies() {
        val health = evaluateNeteaseAuthHealth(
            NeteaseAuthBundle(
                cookies = mapOf("VISITOR" to "value")
            ),
            now = 1_000L
        )

        assertEquals(SavedCookieAuthState.Missing, health.state)
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateNeteaseAuthHealth_returnsMissingWithoutMusicCookie() {
        val health = evaluateNeteaseAuthHealth(
            NeteaseAuthBundle(
                cookies = mapOf("__csrf" to "csrf-token"),
                savedAt = 1_000L
            ),
            now = 2_000L
        )

        assertEquals(SavedCookieAuthState.Missing, health.state)
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateNeteaseAuthHealth_keepsSavedCookieValidWithoutExpiryCheck() {
        val now = 50L * 24L * 60L * 60L * 1000L
        val snapshot = NeteaseAuthBundle(
            cookies = mapOf("MUSIC_U" to "cookie"),
            savedAt = now - (90L * 24L * 60L * 60L * 1000L)
        )

        val health = evaluateNeteaseAuthHealth(snapshot, now = now)

        assertEquals(SavedCookieAuthState.Valid, health.state)
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun evaluateNeteaseAuthHealth_returnsValidForRecentCookie() {
        val now = 10_000L
        val snapshot = NeteaseAuthBundle(
            cookies = mapOf("MUSIC_U" to "cookie"),
            savedAt = now - 1_000L
        )

        val health = evaluateNeteaseAuthHealth(snapshot, now = now)

        assertEquals(SavedCookieAuthState.Valid, health.state)
        assertFalse(health.shouldPromptRelogin)
    }

    @Test
    fun validateAndSanitizeNeteaseCookies_rejectsIllegalEntriesAndKeepsFallbacks() {
        val validation = validateAndSanitizeNeteaseCookies(
            mapOf(
                "MUSIC_U" to "cookie-value",
                "bad key" to "value",
                "__csrf" to "csrf\nvalue"
            )
        )

        assertTrue(validation.isAccepted)
        assertEquals("cookie-value", validation.sanitizedCookies["MUSIC_U"])
        assertEquals("pc", validation.sanitizedCookies["os"])
        assertEquals("8.10.35", validation.sanitizedCookies["appver"])
        assertTrue(validation.rejectedKeys.contains("bad key"))
        assertTrue(validation.rejectedKeys.contains("__csrf"))
    }

    @Test
    fun validateAndSanitizeNeteaseCookies_rejectsMissingLoginCookie() {
        val validation = validateAndSanitizeNeteaseCookies(
            mapOf(
                "__csrf" to "csrf-token"
            )
        )

        assertFalse(validation.isAccepted)
        assertFalse(validation.hasLoginCookie)
    }
}
