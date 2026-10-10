package moe.ouom.neriplayer.activity.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseMobileUserAgentFallbackTest {

    @Test
    fun `a desktop default user agent is replaced on phones`() {
        val settings = resolveNeteaseWebLoginWebSettings(
            smallestScreenWidthDp = 411,
            defaultUserAgent = NETEASE_DESKTOP_WEB_USER_AGENT
        )

        assertEquals(NETEASE_MOBILE_LOGIN_URL, settings.url)
        assertEquals(NETEASE_MOBILE_WEB_USER_AGENT, settings.userAgent)
    }

    @Test
    fun `an Android user agent without the mobile token is replaced on phones`() {
        val settings = resolveNeteaseWebLoginWebSettings(
            smallestScreenWidthDp = 411,
            defaultUserAgent = "Mozilla/5.0 (Linux; Android 14; Tablet; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Safari/537.36"
        )

        assertEquals(NETEASE_MOBILE_WEB_USER_AGENT, settings.userAgent)
    }

    @Test
    fun `android and mobile tokens are matched without regard to case`() {
        val settings = resolveNeteaseWebLoginWebSettings(
            smallestScreenWidthDp = 411,
            defaultUserAgent = "  Mozilla/5.0 (linux; ANDROID 14; Phone) MOBILE Safari/537.36  "
        )

        assertEquals("Mozilla/5.0 (linux; ANDROID 14; Phone) MOBILE Safari/537.36", settings.userAgent)
    }
}
