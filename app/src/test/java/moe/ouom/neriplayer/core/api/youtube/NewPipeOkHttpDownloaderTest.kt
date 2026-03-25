package moe.ouom.neriplayer.core.api.youtube

import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

class NewPipeOkHttpDownloaderTest {

    @Test
    fun execute_shouldAttachYouTubeLoginHeadersWhenAuthIsAvailable() {
        var capturedRequest: okhttp3.Request? = null
        val downloader = NewPipeOkHttpDownloader(
            client = captureClient { capturedRequest = it },
            authProvider = {
                YouTubeAuthBundle(
                    cookieHeader = "SAPISID=sap-value; SID=sid-value",
                    xGoogAuthUser = "5",
                    userAgent = "UnitTestAgent/1.0"
                )
            }
        )

        downloader.execute(
            Request.Builder()
                .post(
                    "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false",
                    "{}".toByteArray()
                )
                .setHeader("Content-Type", "application/json")
                .build()
        )

        val request = requireNotNull(capturedRequest)
        val cookieHeader = request.header("Cookie").orEmpty()
        assertTrue(cookieHeader.contains("SAPISID=sap-value"))
        assertTrue(cookieHeader.contains("SOCS=CAI"))
        assertEquals("5", request.header("X-Goog-AuthUser"))
        assertEquals("UnitTestAgent/1.0", request.header("User-Agent"))
        assertTrue(request.header("Authorization").orEmpty().startsWith("SAPISIDHASH "))
        assertNull(request.header("Origin"))
        assertNull(request.header("X-Origin"))
        assertNull(request.header("Referer"))
    }

    @Test
    fun execute_shouldOnlyAppendConsentCookieWhenAuthIsMissing() {
        var capturedRequest: okhttp3.Request? = null
        val downloader = NewPipeOkHttpDownloader(
            client = captureClient { capturedRequest = it }
        )

        downloader.execute(
            Request.Builder()
                .get("https://www.youtube.com/watch?v=test-video")
                .build()
        )

        val request = requireNotNull(capturedRequest)
        assertEquals("SOCS=CAI", request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Goog-AuthUser"))
        assertFalse(request.headers.names().contains("Origin"))
    }

    @Test
    fun execute_shouldAttachWebOriginHeadersForMusicYouTubeRequests() {
        var capturedRequest: okhttp3.Request? = null
        val downloader = NewPipeOkHttpDownloader(
            client = captureClient { capturedRequest = it },
            authProvider = {
                YouTubeAuthBundle(
                    cookieHeader = "SAPISID=sap-value; SID=sid-value",
                    xGoogAuthUser = "0",
                    userAgent = "UnitTestAgent/2.0"
                )
            }
        )

        downloader.execute(
            Request.Builder()
                .get("https://music.youtube.com/watch?v=test-video")
                .build()
        )

        val request = requireNotNull(capturedRequest)
        assertEquals(YOUTUBE_MUSIC_ORIGIN, request.header("Origin"))
        assertEquals(YOUTUBE_MUSIC_ORIGIN, request.header("X-Origin"))
        assertEquals("$YOUTUBE_MUSIC_ORIGIN/", request.header("Referer"))
        assertTrue(request.header("Authorization").orEmpty().startsWith("SAPISIDHASH "))
    }

    @Test
    fun execute_shouldNotAttachYoutubeHeadersToLookalikeHost() {
        var capturedRequest: okhttp3.Request? = null
        val downloader = NewPipeOkHttpDownloader(
            client = captureClient { capturedRequest = it },
            authProvider = {
                YouTubeAuthBundle(
                    cookieHeader = "SAPISID=sap-value; SID=sid-value",
                    xGoogAuthUser = "0",
                    userAgent = "UnitTestAgent/2.0"
                )
            }
        )

        downloader.execute(
            Request.Builder()
                .get("https://evilyoutube.com/watch?v=test-video")
                .build()
        )

        val request = requireNotNull(capturedRequest)
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Goog-AuthUser"))
        assertNull(request.header("Origin"))
        assertNull(request.header("X-Origin"))
        assertNull(request.header("Referer"))
    }

    @Test
    fun execute_shouldStripSensitiveHeadersForGoogleVideoRequests() {
        var capturedRequest: okhttp3.Request? = null
        val downloader = NewPipeOkHttpDownloader(
            client = captureClient { capturedRequest = it },
            authProvider = {
                YouTubeAuthBundle(
                    cookieHeader = "SAPISID=sap-value; SID=sid-value",
                    xGoogAuthUser = "0",
                    userAgent = "UnitTestAgent/3.0"
                )
            }
        )

        downloader.execute(
            Request.Builder()
                .get("https://rr1---sn-a5mekn6r.googlevideo.com/videoplayback?source=youtube&c=WEB_REMIX")
                .setHeader("Cookie", "legacy=1")
                .setHeader("Authorization", "Bearer should-be-removed")
                .setHeader("X-Goog-AuthUser", "9")
                .setHeader("Referer", "https://music.youtube.com/watch?v=test-video")
                .build()
        )

        val request = requireNotNull(capturedRequest)
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Goog-AuthUser"))
        assertEquals("UnitTestAgent/3.0", request.header("User-Agent"))
        assertEquals(YOUTUBE_MUSIC_ORIGIN, request.header("Origin"))
        assertEquals("$YOUTUBE_MUSIC_ORIGIN/", request.header("Referer"))
    }

    private fun captureClient(
        capture: (okhttp3.Request) -> Unit
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    capture(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody())
                        .build()
                }
            )
            .build()
    }
}
