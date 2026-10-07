package moe.ouom.neriplayer.platform.youtube.api.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCookieSupportTest {

    @Test
    fun `cookie strings skip empty, unnamed and valueless segments`() {
        assertEquals(
            linkedMapOf("SID" to "a=b", "HSID" to "", "PREF" to "f6=8"),
            YouTubeCookieSupport.parseCookieString(" SID = a=b ;; =orphan; flag; HSID=; PREF=f6=8 ; ")
        )
        assertEquals(linkedMapOf<String, String>(), YouTubeCookieSupport.parseCookieString("   "))
    }

    @Test
    fun `merged cookie strings let later sources override earlier ones`() {
        assertEquals(
            linkedMapOf("SID" to "new", "PREF" to "f6=8", "YSC" to "y"),
            YouTubeCookieSupport.mergeCookieStrings(listOf("SID=old; PREF=f6=8", "  ", "SID=new; YSC=y"))
        )
    }

    @Test
    fun `persisted cookies keep stable session keys and drop page cookies`() {
        assertEquals(
            linkedMapOf(
                "SID" to "sid",
                "__Secure-3PSIDTS" to "ts",
                "__Secure-ROLLOUT_TOKEN" to "rollout",
                "VISITOR_INFO1_LIVE" to "visitor"
            ),
            YouTubeCookieSupport.sanitizePersistedCookies(
                linkedMapOf(
                    "SID" to "sid",
                    " " to "blank-key",
                    "HSID" to " ",
                    "__Secure-3PSIDTS" to "ts",
                    "__Secure-ROLLOUT_TOKEN" to "rollout",
                    "VISITOR_INFO1_LIVE" to "visitor",
                    "ST-abc" to "page-only"
                )
            )
        )
    }

    @Test
    fun `web login seed cookies only carry consent and visitor state`() {
        val browserCookies = linkedMapOf(
            "SOCS" to "CAI",
            "CONSENT" to " ",
            "PREF" to "hl=en",
            "VISITOR_INFO1_LIVE" to "visitor",
            "SID" to "sid",
            "" to "unnamed"
        )

        assertEquals(
            linkedMapOf("SOCS" to "CAI", "PREF" to "hl=en"),
            YouTubeCookieSupport.sanitizeWebLoginGoogleSeedCookies(browserCookies)
        )
        assertEquals(
            linkedMapOf("SOCS" to "CAI", "PREF" to "hl=en", "VISITOR_INFO1_LIVE" to "visitor"),
            YouTubeCookieSupport.sanitizeWebLoginYouTubeSeedCookies(browserCookies)
        )
    }

    @Test
    fun `web login reset only clears cookies that carry a value`() {
        assertEquals(
            listOf("SID", "YSC"),
            YouTubeCookieSupport.collectWebLoginResetCookieKeys(
                linkedMapOf("SID" to "sid", " " to "blank-key", "PREF" to " ", "YSC" to "ysc")
            )
        )
    }

    @Test
    fun `request cookies are useful only when they carry a login key`() {
        assertFalse(YouTubeCookieSupport.hasUsefulRequestCookies("  "))
        assertFalse(YouTubeCookieSupport.hasUsefulRequestCookies("PREF=f6=8; SAPISID= "))
        assertTrue(YouTubeCookieSupport.hasUsefulRequestCookies("PREF=f6=8; SAPISID=sap"))
    }
}
