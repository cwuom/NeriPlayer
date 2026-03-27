package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.auth.youtube.YouTubeCookieSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCookieSupportTest {

    @Test
    fun parseCookieString_parsesPairsAndKeepsLatestValue() {
        val parsed = YouTubeCookieSupport.parseCookieString(
            "VISITOR_INFO1_LIVE=abc; SAPISID=first; invalid; SAPISID=second"
        )

        assertEquals("abc", parsed["VISITOR_INFO1_LIVE"])
        assertEquals("second", parsed["SAPISID"])
    }

    @Test
    fun mergeCookieStrings_mergesMultipleSources() {
        val merged = YouTubeCookieSupport.mergeCookieStrings(
            listOf(
                "VISITOR_INFO1_LIVE=abc",
                "LOGIN_INFO=token"
            )
        )

        assertEquals("abc", merged["VISITOR_INFO1_LIVE"])
        assertEquals("token", merged["LOGIN_INFO"])
    }

    @Test
    fun isLoggedIn_returnsTrueForKnownAuthCookies() {
        assertTrue(
            YouTubeCookieSupport.isLoggedIn(
                mapOf("SAPISID" to "value")
            )
        )
    }

    @Test
    fun isLoggedIn_returnsTrueForPsidTsCookies() {
        assertTrue(
            YouTubeCookieSupport.isLoggedIn(
                mapOf("__Secure-1PSIDTS" to "value")
            )
        )
    }

    @Test
    fun isLoggedIn_returnsFalseWithoutAuthCookies() {
        assertFalse(
            YouTubeCookieSupport.isLoggedIn(
                mapOf("VISITOR_INFO1_LIVE" to "value")
            )
        )
    }

    @Test
    fun activeSessionCookieKeys_excludesLoginInfoOnly() {
        assertTrue(YouTubeCookieSupport.importantLoginCookieKeys.contains("LOGIN_INFO"))
        assertFalse(YouTubeCookieSupport.activeSessionCookieKeys.contains("LOGIN_INFO"))
    }

    @Test
    fun collectActiveSessionCookieKeys_recognizesPsidVariants() {
        val activeKeys = YouTubeCookieSupport.collectActiveSessionCookieKeys(
            mapOf(
                "__Secure-1PSIDTS" to "ts",
                "__Secure-3PSIDCC" to "cc",
                "VISITOR_INFO1_LIVE" to "visitor"
            )
        )

        assertTrue(activeKeys.contains("__Secure-1PSIDTS"))
        assertTrue(activeKeys.contains("__Secure-3PSIDCC"))
        assertFalse(activeKeys.contains("VISITOR_INFO1_LIVE"))
    }

    @Test
    fun sanitizePersistedCookies_keepsSessionCookiesAndDropsNoise() {
        val sanitized = YouTubeCookieSupport.sanitizePersistedCookies(
            mapOf(
                "SAPISID" to "value",
                "__Secure-1PAPISID" to "secure-value",
                "__Secure-1PSIDTS" to "ts-value",
                "VISITOR_PRIVACY_METADATA" to "privacy",
                "VISITOR_INFO1_LIVE" to "visitor"
            )
        )

        assertEquals("value", sanitized["SAPISID"])
        assertEquals("secure-value", sanitized["__Secure-1PAPISID"])
        assertEquals("ts-value", sanitized["__Secure-1PSIDTS"])
        assertEquals("privacy", sanitized["VISITOR_PRIVACY_METADATA"])
        assertEquals("visitor", sanitized["VISITOR_INFO1_LIVE"])
    }
}
