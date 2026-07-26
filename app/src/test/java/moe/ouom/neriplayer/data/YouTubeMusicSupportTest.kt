package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_DEFAULT_WEB_USER_AGENT
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_STREAM_IOS_USER_AGENT
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeInnertubeRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.effectiveCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeWebRemixDirectMissingPoToken
import moe.ouom.neriplayer.data.platform.youtube.resolveAuthorizationHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveBootstrapUserAgent
import moe.ouom.neriplayer.data.platform.youtube.resolveXGoogAuthUser
import moe.ouom.neriplayer.data.platform.youtube.resolveYouTubeMobileWebLoginUserAgent
import moe.ouom.neriplayer.data.platform.youtube.stripWebViewMarkersFromUserAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicSupportTest {

    @Test
    fun effectiveCookieHeader_sanitizesCookiesAndAppendsConsentCookie() {
        val cookieHeader = YouTubeAuthBundle(
            cookieHeader = "VISITOR_INFO1_LIVE=visitor; SAPISID=sap-value; SID=sid-value"
        ).effectiveCookieHeader()

        assertTrue(cookieHeader.contains("SAPISID=sap-value"))
        assertTrue(cookieHeader.contains("SID=sid-value"))
        assertTrue(cookieHeader.contains("SOCS=CAI"))
        assertTrue(cookieHeader.contains("VISITOR_INFO1_LIVE=visitor"))
    }

    @Test
    fun buildYouTubePageRequestHeaders_attachesConsentCookieAndOptionalAuthUser() {
        val headers = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "2",
            userAgent = "UnitTestAgent/3.0"
        ).buildYouTubePageRequestHeaders(includeAuthUser = true)

        assertTrue(headers["Cookie"].orEmpty().contains("SAPISID=sap-value"))
        assertTrue(headers["Cookie"].orEmpty().contains("SOCS=CAI"))
        assertEquals("UnitTestAgent/3.0", headers["User-Agent"])
        assertEquals("2", headers["X-Goog-AuthUser"])
        assertFalse(headers.containsKey("Authorization"))
        assertFalse(headers.containsKey("Origin"))
        assertFalse(headers.containsKey("Referer"))
        assertFalse(headers.containsKey("X-Origin"))
    }

    @Test
    fun buildYouTubePageRequestHeaders_onlyAppendsConsentCookieWhenAuthMissing() {
        val headers = YouTubeAuthBundle().buildYouTubePageRequestHeaders(includeAuthUser = true)

        assertEquals("SOCS=CAI", headers["Cookie"])
        assertEquals(YOUTUBE_DEFAULT_WEB_USER_AGENT, headers["User-Agent"])
        assertEquals("0", headers["X-Goog-AuthUser"])
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun resolveXGoogAuthUser_usesFallbackWhenStoredAuthUserMissing() {
        val authUser = YouTubeAuthBundle().resolveXGoogAuthUser(fallback = "7")

        assertEquals("7", authUser)
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
    fun buildBootstrapAuthFingerprint_normalizesMobileUserAgentBeforeHashing() {
        val mobileAuth = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "2",
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        )
        val desktopAuth = mobileAuth.copy(userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT)

        assertEquals(
            desktopAuth.buildBootstrapAuthFingerprint(),
            mobileAuth.buildBootstrapAuthFingerprint()
        )
    }

    @Test
    fun buildBootstrapAuthFingerprint_changesWhenAuthUserChangesWithSameCookies() {
        val base = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "0",
            userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
        )
        val changedAuthUser = base.copy(xGoogAuthUser = "3")

        assertNotEquals(
            base.buildBootstrapAuthFingerprint(),
            changedAuthUser.buildBootstrapAuthFingerprint()
        )
    }

    @Test
    fun buildBootstrapAuthFingerprint_ignoresVisitorCookieChurnWhenSessionCookiesStayStable() {
        val base = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value; VISITOR_INFO1_LIVE=visitor-a; YSC=ysc-a",
            xGoogAuthUser = "0",
            userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
        )
        val changedNoise = base.copy(
            cookieHeader = "SAPISID=sap-value; SID=sid-value; VISITOR_INFO1_LIVE=visitor-b; YSC=ysc-b"
        )

        assertEquals(
            base.buildBootstrapAuthFingerprint(),
            changedNoise.buildBootstrapAuthFingerprint()
        )
    }

    @Test
    fun buildBootstrapAuthFingerprint_changesWhenStableSessionCookieChanges() {
        val base = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value; VISITOR_INFO1_LIVE=visitor-a",
            xGoogAuthUser = "0",
            userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
        )
        val changedSession = base.copy(
            cookieHeader = "SAPISID=new-sap-value; SID=sid-value; VISITOR_INFO1_LIVE=visitor-b"
        )

        assertNotEquals(
            base.buildBootstrapAuthFingerprint(),
            changedSession.buildBootstrapAuthFingerprint()
        )
    }

    @Test
    fun buildBootstrapAuthFingerprint_ignoresLoginInfoAndSidccCookieChurn() {
        val base = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value; LOGIN_INFO=login-a; SIDCC=sidcc-a",
            xGoogAuthUser = "0",
            userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
        )
        val changedNoise = base.copy(
            cookieHeader = "SAPISID=sap-value; SID=sid-value; LOGIN_INFO=login-b; SIDCC=sidcc-b"
        )

        assertEquals(
            base.buildBootstrapAuthFingerprint(),
            changedNoise.buildBootstrapAuthFingerprint()
        )
    }

    @Test
    fun buildBootstrapAuthFingerprint_normalizesYoutubePageOrigin() {
        val auth = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "0",
            userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
        )

        assertEquals(
            auth.buildBootstrapAuthFingerprint(origin = YOUTUBE_MUSIC_ORIGIN),
            auth.buildBootstrapAuthFingerprint(origin = YOUTUBE_WEB_ORIGIN)
        )
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
    fun buildYouTubeInnertubeRequestHeaders_includesUserSessionScopedAuthorizationWhenProvided() {
        val headers = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "UnitTestAgent/4.0"
        ).buildYouTubeInnertubeRequestHeaders(
            authorizationOrigin = YOUTUBE_MUSIC_ORIGIN,
            userSessionId = "user-session-123"
        )

        assertEquals("7", headers["X-Goog-AuthUser"])
        assertTrue(headers["Authorization"].orEmpty().contains("_u"))
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

    @Test
    fun stripWebViewMarkersFromUserAgent_removesWvAndVersionMarkers() {
        val stripped = stripWebViewMarkersFromUserAgent(STANDARD_WEBVIEW_USER_AGENT)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36",
            stripped
        )
        assertFalse(stripped.contains("; wv"))
        assertFalse(stripped.contains("Version/4.0"))
        assertTrue(stripped.contains("Android 14"))
        assertTrue(stripped.contains("Mobile Safari"))
    }

    @Test
    fun stripWebViewMarkersFromUserAgent_handlesUaWithoutDeviceModel() {
        val stripped = stripWebViewMarkersFromUserAgent(
            "Mozilla/5.0 (Linux; Android 14; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
        )

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36",
            stripped
        )
    }

    @Test
    fun stripWebViewMarkersFromUserAgent_collapsesWhitespaceAndTrims() {
        val stripped = stripWebViewMarkersFromUserAgent(
            "  Mozilla/5.0   (Linux; Android 14; Pixel)   " +
                "Chrome/120.0.0.0  Mobile Safari/537.36  "
        )

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36",
            stripped
        )
    }

    @Test
    fun stripWebViewMarkersFromUserAgent_returnsEmptyForBlankInput() {
        assertEquals("", stripWebViewMarkersFromUserAgent(""))
        assertEquals("", stripWebViewMarkersFromUserAgent("   "))
    }

    @Test
    fun stripWebViewMarkersFromUserAgent_isIdempotentForCleanUa() {
        val clean = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/146.0.0.0 Mobile Safari/537.36"

        assertEquals(clean, stripWebViewMarkersFromUserAgent(clean))
    }

    @Test
    fun resolveYouTubeMobileWebLoginUserAgent_stripsMarkersFromDeviceDefault() {
        val resolved = resolveYouTubeMobileWebLoginUserAgent(STANDARD_WEBVIEW_USER_AGENT)

        assertFalse(resolved.contains("; wv"))
        assertFalse(resolved.contains("Version/4.0"))
        assertTrue(resolved.contains("Mobile Safari"))
        assertNotEquals(YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT, resolved)
    }

    @Test
    fun resolveYouTubeMobileWebLoginUserAgent_fallsBackWhenNullOrBlank() {
        assertEquals(
            YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT,
            resolveYouTubeMobileWebLoginUserAgent(null)
        )
        assertEquals(
            YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT,
            resolveYouTubeMobileWebLoginUserAgent("")
        )
        assertEquals(
            YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT,
            resolveYouTubeMobileWebLoginUserAgent("   ")
        )
    }

    @Test
    fun mobileFallbackUserAgent_isMobileAndFreeOfWebViewMarkers() {
        assertFalse(YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT.contains("; wv"))
        assertFalse(YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT.contains("Version/4.0"))
        assertTrue(YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT.contains("Mobile"))
        assertTrue(YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT.contains("Android"))
        assertFalse(YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT.contains("Windows"))
        assertNotEquals(YOUTUBE_DEFAULT_WEB_USER_AGENT, YOUTUBE_DEFAULT_MOBILE_WEB_USER_AGENT)
    }

    @Test
    fun isYouTubeWebRemixDirectMissingPoToken_flagsWebRemixGoogleVideoWithoutPot() {
        // 下载失败根因:WEB_REMIX googlevideo 直链缺 pot，整段下载会被 403
        assertTrue(
            isYouTubeWebRemixDirectMissingPoToken(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=123&c=WEB_REMIX&itag=140"
            )
        )
        // pot= 但值为空 同样视为缺失
        assertTrue(
            isYouTubeWebRemixDirectMissingPoToken(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?c=WEB_REMIX&itag=140&pot="
            )
        )
    }

    @Test
    fun isYouTubeWebRemixDirectMissingPoToken_acceptsWebRemixWithPot() {
        // 带 pot 的 WEB_REMIX 直链是下载期望的可用直链
        assertFalse(
            isYouTubeWebRemixDirectMissingPoToken(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?c=WEB_REMIX&itag=140&pot=Abc123Token"
            )
        )
    }

    @Test
    fun isYouTubeWebRemixDirectMissingPoToken_ignoresNonWebRemixAndNonGoogleVideo() {
        // 其它 client 直链不需要 pot，不能误伤
        assertFalse(
            isYouTubeWebRemixDirectMissingPoToken(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?c=ANDROID_MUSIC&itag=140"
            )
        )
        // googlevideo 但缺少 c 参数:无法确认为 WEB_REMIX，保持接受避免误伤
        assertFalse(
            isYouTubeWebRemixDirectMissingPoToken(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?itag=140"
            )
        )
        // 非 googlevideo 主机即使标注 WEB_REMIX 也不适用该判定
        assertFalse(
            isYouTubeWebRemixDirectMissingPoToken(
                "https://example.com/videoplayback?c=WEB_REMIX&itag=140"
            )
        )
        // 空输入不崩溃且不误判
        assertFalse(isYouTubeWebRemixDirectMissingPoToken(null))
        assertFalse(isYouTubeWebRemixDirectMissingPoToken(""))
    }

    private companion object {
        private const val STANDARD_WEBVIEW_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
