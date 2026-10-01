package moe.ouom.neriplayer.api.youtube.client

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.api.youtube.auth.YouTubeAuthProvider
import moe.ouom.neriplayer.api.youtube.auth.YouTubeAuthRefresher
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthAutoRefreshResult
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.api.youtube.auth.evaluateYouTubeAuthHealth
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicClientAuthSessionTest {
    @Test
    fun readsUpdatedSessionAfterRefreshWithoutDependingOnAuthStorage() = runBlocking {
        val session = RecordingSession()
        val requests = mutableListOf<Request>()
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val response = if (request.url.encodedPath == "/") {
                """
                    {"INNERTUBE_API_KEY":"test-key","INNERTUBE_CLIENT_VERSION":"test-version",
                     "VISITOR_DATA":"test-visitor","LOGGED_IN":true,"SESSION_INDEX":0}
                """.trimIndent()
            } else {
                """
                    {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
                      {"url":"https://audio.example/song.m4a","mimeType":"audio/mp4",
                       "approxDurationMs":"123000","bitrate":128000}
                    ]}}
                """.trimIndent()
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(response.toResponseBody())
                .build()
        }.build()
        val client = YouTubeMusicClient(session, httpClient, session)

        val audio = client.getPlayableAudio("test-video")
        val diagnostic = JSONObject(client.debugBootstrap().rawJson)

        assertEquals(listOf("playable_audio" to false), session.refreshes)
        assertEquals("https://audio.example/song.m4a", audio.url)
        assertEquals(123000L, audio.durationMs)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertTrue(request.header("Cookie").orEmpty().contains("SAPISID=refreshed"))
            assertEquals("2", request.header("X-Goog-AuthUser"))
        }
        assertEquals("2", diagnostic.getJSONObject("bootstrap").getString("sessionIndex"))
        assertEquals(session.getAuthHealthOnce().state.name, diagnostic.getJSONObject("health").getString("state"))
    }

    private class RecordingSession : YouTubeAuthProvider, YouTubeAuthRefresher {
        var auth = YouTubeAuthBundle(cookieHeader = "SAPISID=old; SID=old")
        val refreshes = mutableListOf<Pair<String, Boolean>>()

        override fun getAuthOnce(): YouTubeAuthBundle = auth

        override fun getAuthHealthOnce() = evaluateYouTubeAuthHealth(auth)

        override suspend fun refreshIfNeeded(reason: String, force: Boolean): YouTubeAuthAutoRefreshResult {
            refreshes += reason to force
            auth = YouTubeAuthBundle(
                cookieHeader = "SAPISID=refreshed; SID=refreshed",
                xGoogAuthUser = "2"
            )
            return YouTubeAuthAutoRefreshResult(true, true, true, reason)
        }
    }
}
