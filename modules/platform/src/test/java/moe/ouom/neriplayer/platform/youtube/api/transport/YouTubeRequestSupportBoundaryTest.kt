package moe.ouom.neriplayer.platform.youtube.api.transport

import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeRequestSupportBoundaryTest {
    private val auth = YouTubeAuthBundle(
        cookieHeader = "SAPISID=sap; SID=sid",
        xGoogAuthUser = "2",
        userAgent = "UnitTestAgent/1.0"
    )

    @Test
    fun `host normalization tolerates missing hosts, padding and trailing dots`() {
        assertEquals("", normalizeYouTubeHost(null))
        assertEquals("music.youtube.com", normalizeYouTubeHost(" Music.YouTube.COM. "))
        assertTrue(isYouTubeMusicHost("MUSIC.youtube.com."))
        assertFalse(isTrustedYouTubeHost(null))
    }

    @Test
    fun `bootstrap fingerprint folds YouTube page origins into the music origin`() {
        val musicFingerprint = "SAPISID=sap; SID=sid|2|$YOUTUBE_MUSIC_ORIGIN|UnitTestAgent/1.0"

        assertEquals(musicFingerprint, auth.buildBootstrapAuthFingerprint(origin = YOUTUBE_MUSIC_ORIGIN))
        assertEquals(musicFingerprint, auth.buildBootstrapAuthFingerprint(origin = "https://www.youtube.com/"))
        assertEquals(musicFingerprint, auth.buildBootstrapAuthFingerprint(origin = "   "))
        assertEquals(
            "SAPISID=sap; SID=sid|2|https://example.com|UnitTestAgent/1.0",
            auth.buildBootstrapAuthFingerprint(origin = "https://example.com/")
        )
        assertEquals(
            "SAPISID=sap; SID=sid|2|http://bad host|UnitTestAgent/1.0",
            auth.buildBootstrapAuthFingerprint(origin = "http://bad host")
        )
    }

    @Test
    fun `innertube headers can omit authorization while keeping consent and auth user`() {
        val headers = auth.buildYouTubeInnertubeRequestHeaders(
            original = mapOf("X-Custom" to "1"),
            includeAuthorization = false
        )

        assertEquals(
            mapOf(
                "X-Custom" to "1",
                "Cookie" to "SAPISID=sap; SID=sid; SOCS=CAI",
                "User-Agent" to "UnitTestAgent/1.0",
                "X-Goog-AuthUser" to "2"
            ),
            headers
        )
        assertTrue(auth.buildYouTubeInnertubeRequestHeaders().getValue("Authorization").startsWith("SAPISIDHASH "))
    }

    @Test
    fun `media uri encodes the video and optional playlist ids`() {
        val mediaUri = buildYouTubeMusicMediaUri("a b/c", playlistId = "PL&1")

        assertEquals("ytmusic://video/abc", buildYouTubeMusicMediaUri("abc"))
        assertEquals("ytmusic://video/abc", buildYouTubeMusicMediaUri("abc", playlistId = "  "))
        assertEquals("ytmusic://video/a+b%2Fc?playlistId=PL%261", mediaUri)
        assertEquals("a b/c", extractYouTubeMusicVideoId(mediaUri))
    }

    @Test
    fun `stream user agent follows the client named in the stream url`() {
        val stream = "https://rr1.googlevideo.com/videoplayback"

        assertEquals(YOUTUBE_STREAM_IOS_USER_AGENT, auth.resolveYouTubeStreamUserAgent("$stream?c=ios&itag=140"))
        assertEquals(YOUTUBE_STREAM_ANDROID_USER_AGENT, auth.resolveYouTubeStreamUserAgent("$stream?c=ANDROID"))
        assertEquals(
            YOUTUBE_STREAM_ANDROID_USER_AGENT,
            auth.resolveYouTubeStreamUserAgent("$stream?c=android_testsuite")
        )
        listOf(
            null,
            " ",
            "$stream?c=",
            "$stream?itag=140",
            "$stream?c=WEB_REMIX",
            "http://bad host/videoplayback?c=IOS"
        ).forEach { streamUrl ->
            assertEquals(streamUrl.toString(), "UnitTestAgent/1.0", auth.resolveYouTubeStreamUserAgent(streamUrl))
        }
    }
}
