package moe.ouom.neriplayer.data.auth.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthSyncTest {

    @Test
    fun collectObservedYouTubeAuthCookies_overlaysRequestCookieHeader() {
        val observed = collectObservedYouTubeAuthCookies(
            snapshotCookies = linkedMapOf(
                "__Secure-3PSID" to "snapshot-psid",
                "VISITOR_INFO1_LIVE" to "snapshot-visitor"
            ),
            requestCookieHeader = "SID=request-sid; SAPISID=request-sapisid; VISITOR_INFO1_LIVE=request-visitor"
        )

        assertEquals("request-sid", observed["SID"])
        assertEquals("request-sapisid", observed["SAPISID"])
        assertEquals("snapshot-psid", observed["__Secure-3PSID"])
        assertEquals("request-visitor", observed["VISITOR_INFO1_LIVE"])
    }

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
    fun mergeYouTubeAuthBundle_snapshotModeDropsStaleBaseCookies() {
        val base = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SAPISID" to "old-sapisid",
                "LOGIN_INFO" to "stale-login-token"
            ),
            xGoogAuthUser = "2",
            savedAt = 100L
        )

        val merged = mergeYouTubeAuthBundle(
            base = base,
            observedCookies = mapOf(
                "__Secure-1PAPISID" to "fresh-papisid",
                "SID" to "fresh-sid"
            ),
            observedCookiesAreSnapshot = true,
            savedAt = 200L
        )

        assertEquals(setOf("__Secure-1PAPISID", "SID"), merged.cookies.keys)
        assertEquals("fresh-papisid", merged.cookies["__Secure-1PAPISID"])
        assertEquals("fresh-sid", merged.cookies["SID"])
        assertFalse(merged.cookies.containsKey("SAPISID"))
        assertFalse(merged.cookies.containsKey("LOGIN_INFO"))
        assertEquals("2", merged.xGoogAuthUser)
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
    fun buildRefreshObserverFingerprint_ignoresNonIdentityCookieChurn() {
        val base = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SID" to "sid-value",
                "LOGIN_INFO" to "login-token",
                "VISITOR_INFO1_LIVE" to "visitor-a"
            ),
            xGoogAuthUser = "3"
        )
        val changedNoise = base.copy(
            cookies = linkedMapOf(
                "SID" to "sid-value",
                "LOGIN_INFO" to "login-token",
                "VISITOR_INFO1_LIVE" to "visitor-b",
                "YSC" to "ysc-token"
            )
        )

        assertEquals(
            base.buildRefreshObserverFingerprint(),
            changedNoise.buildRefreshObserverFingerprint()
        )
    }

    @Test
    fun buildRefreshObserverFingerprint_fallsBackToImportantLoginCookieValues() {
        val first = YouTubeAuthBundle(
            cookies = linkedMapOf("SAPISID" to "sap-a")
        )
        val second = first.copy(
            cookies = linkedMapOf("SAPISID" to "sap-b")
        )

        assertFalse(
            first.buildRefreshObserverFingerprint() ==
                second.buildRefreshObserverFingerprint()
        )
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
    fun preserveMatchingYouTubeAuthCookies_restoresMissingStrongCookiesForSameSession() {
        val previous = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SID" to "same-sid",
                "SAPISID" to "stable-sapisid",
                "__Secure-1PAPISID" to "stable-papisid",
                "HSID" to "stable-hsid",
                "VISITOR_INFO1_LIVE" to "visitor-a"
            ),
            savedAt = 100L
        )
        val current = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SID" to "same-sid",
                "__Secure-1PAPISID" to "stable-papisid",
                "VISITOR_INFO1_LIVE" to "visitor-b"
            ),
            savedAt = 200L
        )

        val preserved = preserveMatchingYouTubeAuthCookies(
            previous = previous,
            current = current
        )

        assertEquals("same-sid", preserved.cookies["SID"])
        assertEquals("stable-sapisid", preserved.cookies["SAPISID"])
        assertEquals("stable-papisid", preserved.cookies["__Secure-1PAPISID"])
        assertEquals("stable-hsid", preserved.cookies["HSID"])
        assertEquals("visitor-b", preserved.cookies["VISITOR_INFO1_LIVE"])
        assertEquals(200L, preserved.savedAt)
    }

    @Test
    fun preserveMatchingYouTubeAuthCookies_doesNotRetainCookiesAcrossIdentityConflict() {
        val previous = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SID" to "old-sid",
                "SAPISID" to "stable-sapisid",
                "__Secure-1PAPISID" to "stable-papisid"
            ),
            savedAt = 100L
        )
        val current = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SID" to "new-sid",
                "__Secure-1PAPISID" to "fresh-papisid"
            ),
            savedAt = 200L
        )

        val preserved = preserveMatchingYouTubeAuthCookies(
            previous = previous,
            current = current
        )

        assertFalse(preserved.cookies.containsKey("SAPISID"))
        assertEquals("new-sid", preserved.cookies["SID"])
        assertEquals("fresh-papisid", preserved.cookies["__Secure-1PAPISID"])
    }

    @Test
    fun normalized_keepsStableWebContextCookiesIntroducedByBackgroundSync() {
        val normalized = YouTubeAuthBundle(
            cookies = linkedMapOf(
                "SAPISID" to "sapisid",
                "SID" to "sid",
                "__Secure-1PSIDTS" to "psidts",
                "VISITOR_PRIVACY_METADATA" to "privacy",
                "VISITOR_INFO1_LIVE" to "visitor",
                "YSC" to "ysc",
                "__Secure-ROLLOUT_TOKEN" to "rollout"
            )
        ).normalized()

        assertEquals("sapisid", normalized.cookies["SAPISID"])
        assertEquals("sid", normalized.cookies["SID"])
        assertEquals("psidts", normalized.cookies["__Secure-1PSIDTS"])
        assertEquals("privacy", normalized.cookies["VISITOR_PRIVACY_METADATA"])
        assertEquals("visitor", normalized.cookies["VISITOR_INFO1_LIVE"])
        assertEquals("ysc", normalized.cookies["YSC"])
        assertEquals("rollout", normalized.cookies["__Secure-ROLLOUT_TOKEN"])
        assertTrue(normalized.cookieHeader.contains("VISITOR_INFO1_LIVE=visitor"))
        assertTrue(normalized.cookieHeader.contains("YSC=ysc"))
        assertTrue(normalized.cookieHeader.contains("__Secure-ROLLOUT_TOKEN=rollout"))
    }
}
