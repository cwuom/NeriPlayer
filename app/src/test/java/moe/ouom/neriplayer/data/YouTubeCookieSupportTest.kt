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
    fun isLoggedIn_returnsFalseForPsidTsCookies() {
        assertFalse(
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
        assertFalse(YouTubeCookieSupport.importantLoginCookieKeys.contains("LOGIN_INFO"))
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
    fun hasUsefulRequestCookies_rejectsLoginInfoOnlyHeader() {
        assertFalse(
            YouTubeCookieSupport.hasUsefulRequestCookies(
                "LOGIN_INFO=token; VISITOR_INFO1_LIVE=visitor"
            )
        )
    }

    @Test
    fun hasUsefulRequestCookies_acceptsRealLoginCookieHeader() {
        assertTrue(
            YouTubeCookieSupport.hasUsefulRequestCookies(
                "LOGIN_INFO=token; SAPISID=real-session"
            )
        )
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

    @Test
    fun sanitizeWebLoginGoogleSeedCookies_keepsOnlyLowRiskGoogleCookies() {
        val sanitized = YouTubeCookieSupport.sanitizeWebLoginGoogleSeedCookies(
            mapOf(
                "SAPISID" to "auth",
                "SID" to "sid",
                "SOCS" to "CAI",
                "CONSENT" to "YES+",
                "PREF" to "tz=Asia/Taipei",
                "VISITOR_INFO1_LIVE" to "visitor"
            )
        )

        assertEquals(setOf("SOCS", "CONSENT", "PREF"), sanitized.keys)
    }

    @Test
    fun sanitizeWebLoginYouTubeSeedCookies_keepsVisitorButDropsAuthCookies() {
        val sanitized = YouTubeCookieSupport.sanitizeWebLoginYouTubeSeedCookies(
            mapOf(
                "SAPISID" to "auth",
                "__Secure-1PSID" to "psid",
                "SOCS" to "CAI",
                "VISITOR_INFO1_LIVE" to "visitor",
                "VISITOR_PRIVACY_METADATA" to "privacy",
                "PREF" to "tz=Asia/Taipei"
            )
        )

        assertEquals(
            setOf("SOCS", "VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "PREF"),
            sanitized.keys
        )
    }

    @Test
    fun collectWebLoginBlockingCookieKeys_matchesAccountCookiesOnly() {
        val blockingKeys = YouTubeCookieSupport.collectWebLoginBlockingCookieKeys(
            mapOf(
                "SID" to "sid",
                "LOGIN_INFO" to "login",
                "__Secure-1PSIDTS" to "psidts",
                "__Secure-3PAPISID" to "papisid",
                "SOCS" to "CAI",
                "VISITOR_INFO1_LIVE" to "visitor"
            )
        )

        assertTrue(blockingKeys.contains("SID"))
        assertTrue(blockingKeys.contains("LOGIN_INFO"))
        assertTrue(blockingKeys.contains("__Secure-1PSIDTS"))
        assertTrue(blockingKeys.contains("__Secure-3PAPISID"))
        assertFalse(blockingKeys.contains("SOCS"))
        assertFalse(blockingKeys.contains("VISITOR_INFO1_LIVE"))
    }
}
