package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthSyncTest {

    @Test
    fun mergeYouTubeAuthBundle_prefersObservedCookiesAndPreservesStoredFields() {
        val base = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SAPISID" to "old-sapisid",
                "LOGIN_INFO" to "login-token"
            ),
            authorization = "SAPISIDHASH old",
            xGoogAuthUser = "2",
            origin = "https://music.youtube.com",
            userAgent = "stored-ua",
            savedAt = 100L
        )

        val merged = mergeYouTubeAuthBundle(
            base = base,
            observedCookies = mapOf(
                "SAPISID" to "new-sapisid",
                "__Secure-1PAPISID" to "papisid"
            ),
            savedAt = 200L
        )

        assertEquals("new-sapisid", merged.cookies["SAPISID"])
        assertEquals("papisid", merged.cookies["__Secure-1PAPISID"])
        assertEquals("login-token", merged.cookies["LOGIN_INFO"])
        assertEquals("SAPISIDHASH old", merged.authorization)
        assertEquals("2", merged.xGoogAuthUser)
        assertEquals("stored-ua", merged.userAgent)
        assertEquals(200L, merged.savedAt)
    }

    @Test
    fun hasMeaningfulYouTubeAuthChange_ignoresSavedAtOnly() {
        val previous = YouTubeAuthBundle(
            cookies = mapOf("SAPISID" to "cookie"),
            authorization = "SAPISIDHASH auth",
            savedAt = 100L
        )
        val current = previous.copy(savedAt = 200L)

        assertFalse(hasMeaningfulYouTubeAuthChange(previous, current))
    }

    @Test
    fun mergeYouTubeAuthCookieUpdates_updatesAndRemovesCookies() {
        val base = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SAPISID" to "old-sapisid",
                "LOGIN_INFO" to "login-token"
            ),
            savedAt = 100L
        )

        val merged = mergeYouTubeAuthCookieUpdates(
            base = base,
            setCookieHeaders = listOf(
                "SAPISID=new-sapisid; Path=/; Secure; HttpOnly",
                "LOGIN_INFO=; Max-Age=0; Path=/; Secure",
                "__Secure-1PAPISID=papisid; Path=/; Secure"
            ),
            savedAt = 300L
        )

        assertNotNull(merged)
        assertEquals("new-sapisid", merged?.cookies?.get("SAPISID"))
        assertEquals("papisid", merged?.cookies?.get("__Secure-1PAPISID"))
        assertTrue(merged?.cookies?.containsKey("LOGIN_INFO") == false)
        assertEquals(300L, merged?.savedAt)
    }

    @Test
    fun normalized_filtersTransientCookiesIntroducedByBackgroundSync() {
        val normalized = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SAPISID" to "sapisid",
                "SID" to "sid",
                "VISITOR_INFO1_LIVE" to "visitor",
                "YSC" to "ysc",
                "__Secure-ROLLOUT_TOKEN" to "rollout"
            )
        ).normalized()

        assertEquals("sapisid", normalized.cookies["SAPISID"])
        assertEquals("sid", normalized.cookies["SID"])
        assertNull(normalized.cookies["VISITOR_INFO1_LIVE"])
        assertNull(normalized.cookies["YSC"])
        assertNull(normalized.cookies["__Secure-ROLLOUT_TOKEN"])
        assertFalse(normalized.cookieHeader.contains("VISITOR_INFO1_LIVE"))
        assertFalse(normalized.cookieHeader.contains("YSC"))
        assertFalse(normalized.cookieHeader.contains("__Secure-ROLLOUT_TOKEN"))
    }
}
