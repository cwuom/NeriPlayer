package moe.ouom.neriplayer.core.api.youtube

import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_DEFAULT_WEB_USER_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeWebPoTokenProviderTest {

    @Test
    fun buildYouTubeWebPoAuthFingerprint_changesWhenAuthUserChanges() {
        val base = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "0",
            userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
        )
        val switchedAccount = base.copy(xGoogAuthUser = "3")

        assertNotEquals(
            buildYouTubeWebPoAuthFingerprint(base),
            buildYouTubeWebPoAuthFingerprint(switchedAccount)
        )
    }

    @Test
    fun buildYouTubeWebPoAuthFingerprint_normalizesMobileUserAgent() {
        val desktopAuth = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = YOUTUBE_DEFAULT_WEB_USER_AGENT
        )
        val mobileAuth = desktopAuth.copy(
            userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"
        )

        assertEquals(
            buildYouTubeWebPoAuthFingerprint(desktopAuth),
            buildYouTubeWebPoAuthFingerprint(mobileAuth)
        )
    }

    @Test
    fun resolveWebPoBootstrapUrls_backgroundWarmupUsesMusicOnly() {
        assertEquals(
            listOf("https://music.youtube.com/"),
            resolveWebPoBootstrapUrls(backgroundWarmup = true)
        )
    }

    @Test
    fun resolveWebPoBootstrapUrls_foregroundMintKeepsYoutubeFallback() {
        val urls = resolveWebPoBootstrapUrls(backgroundWarmup = false)

        assertEquals("https://www.youtube.com/?themeRefresh=1", urls.first())
        assertTrue(urls.contains("https://music.youtube.com/"))
    }
}
