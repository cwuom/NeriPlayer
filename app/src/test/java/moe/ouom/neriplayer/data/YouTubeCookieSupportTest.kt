package moe.ouom.neriplayer.data

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
    fun isLoggedIn_returnsFalseWithoutAuthCookies() {
        assertFalse(
            YouTubeCookieSupport.isLoggedIn(
                mapOf("VISITOR_INFO1_LIVE" to "value")
            )
        )
    }

    @Test
    fun hasActiveSessionCookies_ignoresLoginInfoOnly() {
        assertFalse(
            YouTubeCookieSupport.hasActiveSessionCookies(
                mapOf("LOGIN_INFO" to "value")
            )
        )
    }

    @Test
    fun hasActiveSessionCookies_returnsTrueForSessionCookie() {
        assertTrue(
            YouTubeCookieSupport.hasActiveSessionCookies(
                mapOf("SAPISID" to "value")
            )
        )
    }
}
