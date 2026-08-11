package moe.ouom.neriplayer.activity.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseWebLoginUrlTest {
    @Test
    fun phoneUsesMobileLoginPage() {
        assertEquals(
            NETEASE_MOBILE_LOGIN_URL,
            resolveNeteaseWebLoginUrl(smallestScreenWidthDp = 599)
        )
    }

    @Test
    fun tabletUsesDesktopLoginPageAtTabletBoundary() {
        assertEquals(
            NETEASE_DESKTOP_LOGIN_URL,
            resolveNeteaseWebLoginUrl(smallestScreenWidthDp = 600)
        )
    }

    @Test
    fun largeTabletUsesDesktopLoginPage() {
        assertEquals(
            NETEASE_DESKTOP_LOGIN_URL,
            resolveNeteaseWebLoginUrl(smallestScreenWidthDp = 840)
        )
    }

    @Test
    fun phoneUsesMobileBrowserUserAgent() {
        val userAgent = resolveNeteaseWebLoginUserAgent(smallestScreenWidthDp = 599)

        assertEquals(NETEASE_MOBILE_WEB_USER_AGENT, userAgent)
        org.junit.Assert.assertTrue(userAgent.contains("Android"))
        org.junit.Assert.assertTrue(userAgent.contains("Mobile"))
        org.junit.Assert.assertFalse(userAgent.contains("Windows"))
    }

    @Test
    fun phoneUsesDeviceMobileUserAgentWithoutWebViewMarkers() {
        val settings = resolveNeteaseWebLoginWebSettings(
            smallestScreenWidthDp = 411,
            defaultUserAgent =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Version/4.0 Chrome/140.0 Mobile Safari/537.36; wv"
        )

        org.junit.Assert.assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0 Mobile Safari/537.36",
            settings.userAgent
        )
        org.junit.Assert.assertFalse(settings.userAgent.contains("; wv"))
        org.junit.Assert.assertFalse(settings.userAgent.contains("Version/4.0"))
    }

    @Test
    fun phoneUsesDeviceViewportInsteadOfDesktopWideViewport() {
        val settings = resolveNeteaseWebLoginWebSettings(smallestScreenWidthDp = 411)

        org.junit.Assert.assertEquals(NETEASE_MOBILE_LOGIN_URL, settings.url)
        org.junit.Assert.assertFalse(settings.useWideViewPort)
        org.junit.Assert.assertFalse(settings.loadWithOverviewMode)
    }

    @Test
    fun tabletUsesDesktopBrowserUserAgent() {
        val settings = resolveNeteaseWebLoginWebSettings(smallestScreenWidthDp = 600)

        assertEquals(NETEASE_DESKTOP_WEB_USER_AGENT, settings.userAgent)
        org.junit.Assert.assertTrue(settings.useWideViewPort)
        org.junit.Assert.assertTrue(settings.loadWithOverviewMode)
    }
}
