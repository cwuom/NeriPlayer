package moe.ouom.neriplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicSupportTest {

    @Test
    fun buildYouTubePlaybackRequestHeaders_attachesCookieAndPlaybackHeaders() {
        val headers = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "2",
            userAgent = "UnitTestAgent/3.0"
        ).buildYouTubePlaybackRequestHeaders(
            origin = YOUTUBE_WEB_ORIGIN,
            includeAuthorization = true,
            includeXOrigin = true
        )

        assertTrue(headers["Cookie"].orEmpty().contains("SAPISID=sap-value"))
        assertTrue(headers["Cookie"].orEmpty().contains("SOCS=CAI"))
        assertEquals("UnitTestAgent/3.0", headers["User-Agent"])
        assertEquals("2", headers["X-Goog-AuthUser"])
        assertEquals(YOUTUBE_WEB_ORIGIN, headers["Origin"])
        assertEquals("$YOUTUBE_WEB_ORIGIN/", headers["Referer"])
        assertEquals(YOUTUBE_WEB_ORIGIN, headers["X-Origin"])
        assertTrue(headers["Authorization"].orEmpty().startsWith("SAPISIDHASH "))
    }

    @Test
    fun buildYouTubePlaybackRequestHeaders_onlyAppendsConsentCookieWhenAuthMissing() {
        val headers = YouTubeAuthBundle().buildYouTubePlaybackRequestHeaders(
            origin = YOUTUBE_WEB_ORIGIN,
            includeAuthorization = true,
            includeXOrigin = false
        )

        assertEquals("SOCS=CAI", headers["Cookie"])
        assertEquals(YOUTUBE_WEB_ORIGIN, headers["Origin"])
        assertEquals("$YOUTUBE_WEB_ORIGIN/", headers["Referer"])
        assertFalse(headers.containsKey("Authorization"))
        assertEquals("0", headers["X-Goog-AuthUser"])
    }

    @Test
    fun resolveBootstrapUserAgent_replacesMobileUserAgentWithStableDesktopAgent() {
        val userAgent = YouTubeAuthBundle(
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        ).resolveBootstrapUserAgent()

        assertEquals(YOUTUBE_DEFAULT_WEB_USER_AGENT, userAgent)
    }

    @Test
    fun buildYouTubeInnertubeRequestHeaders_doesNotAttachOriginHeaders() {
        val headers = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "UnitTestAgent/4.0"
        ).buildYouTubeInnertubeRequestHeaders(
            authorizationOrigin = YOUTUBE_MUSIC_ORIGIN
        )

        assertTrue(headers["Cookie"].orEmpty().contains("SAPISID=sap-value"))
        assertEquals("UnitTestAgent/4.0", headers["User-Agent"])
        assertEquals("7", headers["X-Goog-AuthUser"])
        assertTrue(headers["Authorization"].orEmpty().startsWith("SAPISIDHASH "))
        assertNull(headers["Origin"])
        assertNull(headers["Referer"])
        assertNull(headers["X-Origin"])
    }

    @Test
    fun resolveAuthorizationHeader_appendsUserSessionMarkerWhenProvided() {
        val authorization = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value"
        ).resolveAuthorizationHeader(
            origin = YOUTUBE_WEB_ORIGIN,
            nowEpochSeconds = 1234L,
            userSessionId = "user-session-123"
        )

        assertTrue(authorization.startsWith("SAPISIDHASH "))
        assertTrue(authorization.contains("_u"))
    }

    @Test
    fun buildYouTubeStreamRequestHeaders_keepsGoogleVideoHeadersMinimal() {
        val headers = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "5",
            userAgent = "UnitTestAgent/5.0"
        ).buildYouTubeStreamRequestHeaders(
            refererOrigin = YOUTUBE_MUSIC_ORIGIN
        )

        assertEquals("UnitTestAgent/5.0", headers["User-Agent"])
        assertEquals(YOUTUBE_MUSIC_ORIGIN, headers["Origin"])
        assertEquals("$YOUTUBE_MUSIC_ORIGIN/", headers["Referer"])
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("X-Goog-AuthUser"))
        assertFalse(headers.containsKey("X-Origin"))
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun buildYouTubeStreamRequestHeaders_usesStableDesktopUserAgentWhenAuthUserAgentIsMobile() {
        val headers = YouTubeAuthBundle(
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        ).buildYouTubeStreamRequestHeaders(
            refererOrigin = YOUTUBE_MUSIC_ORIGIN
        )

        assertEquals(YOUTUBE_DEFAULT_WEB_USER_AGENT, headers["User-Agent"])
        assertEquals(YOUTUBE_MUSIC_ORIGIN, headers["Origin"])
        assertEquals("$YOUTUBE_MUSIC_ORIGIN/", headers["Referer"])
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("X-Goog-AuthUser"))
        assertFalse(headers.containsKey("X-Origin"))
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun buildYouTubeStreamRequestHeaders_stripsSensitiveOriginalHeadersButPreservesRange() {
        val headers = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "5",
            userAgent = "UnitTestAgent/7.0"
        ).buildYouTubeStreamRequestHeaders(
            original = mapOf(
                "Cookie" to "SAPISID=old",
                "Authorization" to "SAPISIDHASH old",
                "X-Goog-AuthUser" to "7",
                "X-Origin" to YOUTUBE_MUSIC_ORIGIN,
                "Range" to "bytes=0-1023"
            ),
            refererOrigin = YOUTUBE_MUSIC_ORIGIN
        )

        assertEquals("bytes=0-1023", headers["Range"])
        assertEquals("UnitTestAgent/7.0", headers["User-Agent"])
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("Authorization"))
        assertFalse(headers.containsKey("X-Goog-AuthUser"))
        assertFalse(headers.containsKey("X-Origin"))
    }

    @Test
    fun buildYouTubeStreamRequestHeaders_usesClientAlignedUaForIosGoogleVideo() {
        val headers = YouTubeAuthBundle(
            userAgent = "UnitTestAgent/8.0"
        ).buildYouTubeStreamRequestHeaders(
            refererOrigin = YOUTUBE_MUSIC_ORIGIN,
            streamUrl = "https://rr1---sn.googlevideo.com/videoplayback?source=youtube&c=IOS"
        )

        assertEquals(YOUTUBE_STREAM_IOS_USER_AGENT, headers["User-Agent"])
        assertEquals(YOUTUBE_MUSIC_ORIGIN, headers["Origin"])
        assertEquals("$YOUTUBE_MUSIC_ORIGIN/", headers["Referer"])
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("Authorization"))
        assertFalse(headers.containsKey("X-Goog-AuthUser"))
        assertFalse(headers.containsKey("X-Origin"))
    }
}
