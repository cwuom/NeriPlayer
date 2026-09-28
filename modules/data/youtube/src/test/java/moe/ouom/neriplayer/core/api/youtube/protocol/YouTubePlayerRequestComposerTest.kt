package moe.ouom.neriplayer.core.api.youtube.protocol

import moe.ouom.neriplayer.core.api.youtube.bootstrap.YouTubePlaybackBootstrap
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerRequestComposerTest {
    @Test
    fun `web remix request keeps browser and authenticated bootstrap context`() {
        val profile = profile(
            clientName = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
            clientId = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_ID,
            userAgent = "Mozilla/5.0 Chrome/120.0.0.0 Edg/121.0.0.0"
        )
        val prepared = YouTubePlayerRequestComposer.compose(
            videoId = "video-a",
            auth = YouTubeAuthBundle(cookieHeader = "SAPISID=sap-value; SID=sid-value"),
            bootstrap = bootstrap(loggedIn = true),
            profile = profile,
            requestLocale = YouTubeMusicRequestLocale("ja", "JP"),
            signatureTimestamp = 20529
        )

        val request = prepared.request
        val body = request.bodyJson()
        val context = body.getJSONObject("context")
        val client = context.getJSONObject("client")
        assertEquals("music.youtube.com", request.url.host)
        assertEquals("https://music.youtube.com", request.header("Origin"))
        assertEquals("2", request.header("X-Goog-AuthUser"))
        assertEquals("true", request.header("X-YouTube-Bootstrap-Logged-In"))
        assertEquals("https://music.youtube.com/watch?v=video-a&list=RDAMVMvideo-a", request.header("Referer"))
        assertEquals("bootstrap-version", prepared.clientVersion)
        assertEquals("Edge", client.getString("browserName"))
        assertEquals("121.0.0.0", client.getString("browserVersion"))
        assertEquals("visitor", client.getString("visitorData"))
        assertEquals("install-data", client.getJSONObject("configInfo").getString("appInstallData"))
        assertEquals("host.example", client.getString("remoteHost"))
        assertEquals(20529, body.getJSONObject("playbackContext")
            .getJSONObject("contentPlaybackContext").getInt("signatureTimestamp"))
        assertEquals("RDAMVMvideo-a", body.getString("playlistId"))
        assertNotNull(context.getJSONObject("adSignalsInfo"))
    }

    @Test
    fun `browser context recognizes Firefox and preserves the Chrome fallback`() {
        val cases = listOf(
            Triple("Mozilla/5.0 Firefox/123.4", "Firefox", "123.4"),
            Triple("Mozilla/5.0 Chrome/122.0.0.0", "Chrome", "122.0.0.0"),
            Triple("UnknownBrowser/1", "Chrome", "")
        )
        cases.forEach { (userAgent, expectedName, expectedVersion) ->
            val prepared = YouTubePlayerRequestComposer.compose(
                videoId = "video-browser",
                auth = YouTubeAuthBundle(),
                bootstrap = bootstrap(loggedIn = false).copy(userAgent = userAgent),
                profile = profile(
                    clientName = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
                    clientId = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_ID,
                    userAgent = userAgent
                ),
                requestLocale = YouTubeMusicRequestLocale("en", "US"),
                signatureTimestamp = null
            )
            val client = prepared.request.bodyJson().getJSONObject("context").getJSONObject("client")
            assertEquals(expectedName, client.getString("browserName"))
            assertEquals(expectedVersion, client.getString("browserVersion"))
        }
    }

    @Test
    fun `tv request keeps delegated session and wrapped video payload`() {
        val prepared = YouTubePlayerRequestComposer.compose(
            videoId = "video-b",
            auth = YouTubeAuthBundle(cookieHeader = "SAPISID=sap-value"),
            bootstrap = bootstrap(loggedIn = true),
            profile = profile(
                clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
                clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
                userAgent = "Cobalt/25",
                wrapPlayerRequest = true
            ),
            requestLocale = YouTubeMusicRequestLocale("en", "US"),
            signatureTimestamp = 42
        )

        val request = prepared.request
        val body = request.bodyJson()
        assertEquals("www.youtube.com", request.url.host)
        assertEquals("page-id", request.header("X-Goog-PageId"))
        assertEquals("true", request.header("X-Youtube-Bootstrap-Logged-In"))
        assertEquals("2", request.header("X-Goog-Api-Format-Version"))
        assertEquals("video-b", body.getJSONObject("playerRequest").getString("videoId"))
        assertFalse(body.getBoolean("disablePlayerResponse"))
    }

    @Test
    fun `logged out tv request omits delegated session and authorization`() {
        val prepared = YouTubePlayerRequestComposer.compose(
            videoId = "video-logged-out",
            auth = YouTubeAuthBundle(),
            bootstrap = bootstrap(loggedIn = false).copy(
                cookieHeader = "",
                delegatedSessionId = ""
            ),
            profile = profile(
                clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
                clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
                userAgent = "Cobalt/25"
            ),
            requestLocale = YouTubeMusicRequestLocale("en", "US"),
            signatureTimestamp = null
        )

        assertNull(prepared.request.header("X-Goog-PageId"))
        assertNull(prepared.request.header("Authorization"))
        assertNull(prepared.request.header("X-Youtube-Bootstrap-Logged-In"))
    }

    @Test
    fun `unauthenticated client omits login headers and browser playback context`() {
        val prepared = YouTubePlayerRequestComposer.compose(
            videoId = "video-c",
            auth = YouTubeAuthBundle(cookieHeader = "SAPISID=sap-value"),
            bootstrap = bootstrap(loggedIn = false),
            profile = profile(
                clientName = YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME,
                clientId = YOUTUBE_PLAYER_VISIONOS_CLIENT_ID,
                userAgent = "VisionOS/1",
                supportsAuthenticatedContext = false,
                includeSignatureTimestamp = false
            ),
            requestLocale = YouTubeMusicRequestLocale("en", "US"),
            signatureTimestamp = null
        )

        val request = prepared.request
        val body = request.bodyJson()
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Goog-AuthUser"))
        assertEquals("https://www.youtube.com/", request.header("Referer"))
        assertFalse(body.has("playbackContext"))
        assertFalse(body.has("playlistId"))
        assertEquals("video-c", body.getString("videoId"))
        assertTrue(body.getJSONObject("context").getJSONObject("client").has("utcOffsetMinutes"))
    }

    private fun profile(
        clientName: String,
        clientId: String,
        userAgent: String,
        wrapPlayerRequest: Boolean = false,
        supportsAuthenticatedContext: Boolean = true,
        includeSignatureTimestamp: Boolean = true
    ) = YouTubePlayerClientProfile(
        clientId = clientId,
        clientName = clientName,
        clientVersion = "profile-version",
        userAgent = userAgent,
        endpointPath = "player",
        wrapPlayerRequest = wrapPlayerRequest,
        supportsAuthenticatedContext = supportsAuthenticatedContext,
        includeSignatureTimestamp = includeSignatureTimestamp
    )

    private fun bootstrap(loggedIn: Boolean) = YouTubePlaybackBootstrap(
        apiKey = "api-key",
        webRemixClientVersion = "bootstrap-version",
        visitorData = "visitor",
        playerJsUrl = "https://music.youtube.com/player.js",
        cookieHeader = "SAPISID=sap-value; SID=sid-value",
        authFingerprint = "auth-fingerprint",
        sessionIndex = "2",
        userAgent = "Mozilla/5.0 Chrome/120.0.0.0 Edg/121.0.0.0",
        remoteHost = "host.example",
        signatureTimestamp = 20529,
        appInstallData = "install-data",
        coldConfigData = "cold-data",
        coldHashData = "cold-hash",
        hotHashData = "hot-hash",
        deviceExperimentId = "experiment",
        rolloutToken = "rollout",
        dataSyncId = "sync",
        delegatedSessionId = "page-id",
        userSessionId = "session-id",
        loggedIn = loggedIn,
        fetchedAtMs = 1L
    )

    private fun okhttp3.Request.bodyJson(): JSONObject {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
}
