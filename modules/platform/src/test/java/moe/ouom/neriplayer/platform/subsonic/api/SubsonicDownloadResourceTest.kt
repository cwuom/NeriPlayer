package moe.ouom.neriplayer.platform.subsonic.api

import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SubsonicDownloadResourceTest {
    private val profile = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Fixture",
        "https://example.test:4533/music/", "fixture-user")
    private val ref = ServerSongRef(profile.id, "track / + ? & 中文")
    private val accounts = mock(SubsonicAccounts::class.java).also {
        `when`(it.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "fixture-password"))
    }

    private fun opaque(kind: String = "download") = "https://" + ServerSongRef.RESOURCE_HOST +
        "/v1/" + profile.id + "/" + kind + "/" + ServerSongRef.encode(ref.songId)

    private fun client(body: TrackingBody, status: Int = 200, headers: Map<String, String> = emptyMap(),
                       inspect: (Request) -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(SubsonicResourceInterceptor { accounts })
        .addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture").header("Content-Type", body.type)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .body(body).build()
        }.build()

    @Test fun downloadUsesItsOwnRawEndpointAndPreservesResumeHeaders() {
        val body = TrackingBody("fLaC", "audio/flac")
        val opaque = opaque()
        val request = Request.Builder().url(opaque).header("Range", "bytes=4096-")
            .header("If-Range", "\"original-version\"").header("Accept-Encoding", "identity")
            .header("Authorization", "fixture-bearer").header("Cookie", "fixture-cookie")
            .header("Referer", "https://unrelated.test/").header("Origin", "https://unrelated.test").build()
        val client = client(body, 206, mapOf("Content-Range" to "bytes 4096-4099/4100")) { resolved ->
            assertEquals("/music/rest/download.view", resolved.url.encodedPath)
            assertEquals(ref.songId, resolved.url.queryParameter("id"))
            assertEquals("raw", resolved.url.queryParameter("format"))
            assertEquals(4533, resolved.url.port)
            assertEquals("bytes=4096-", resolved.header("Range"))
            assertEquals("\"original-version\"", resolved.header("If-Range"))
            assertEquals("identity", resolved.header("Accept-Encoding"))
            for (name in listOf("Authorization", "Cookie", "Referer", "Origin")) assertNull(resolved.header(name))
        }
        client.newCall(request).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals(opaque, response.request.url.toString())
            assertEquals("bytes 4096-4099/4100", response.header("Content-Range"))
            assertEquals("fLaC", response.body.string())
        }
        assertTrue(body.closed)
    }

    @Test fun playbackStillUsesTheStreamEndpoint() {
        val body = TrackingBody("fLaC", "audio/flac")
        val client = client(body) { request ->
            assertEquals("/music/rest/stream.view", request.url.encodedPath)
            assertEquals("raw", request.url.queryParameter("format"))
            assertEquals(ref.songId, request.url.queryParameter("id"))
        }
        client.newCall(Request.Builder().url(ref.resourceUrl()).build()).execute().use { response ->
            assertEquals(ref.resourceUrl(), response.request.url.toString())
            assertEquals("fLaC", response.body.string())
        }
        assertTrue(body.closed)
    }

    @Test fun rangeEndResponsesRemainAvailableToBothDownloadAndPlaybackCallers() {
        for (kind in listOf("stream", "download")) {
            val body = TrackingBody("range diagnostic", "text/xml")
            val opaque = opaque(kind)
            val client = client(body, 416, mapOf("Content-Range" to "bytes */4096")) { request ->
                assertEquals("/music/rest/" + kind + ".view", request.url.encodedPath)
                assertEquals("bytes=4096-", request.header("Range"))
                assertEquals("\"original-version\"", request.header("If-Range"))
            }
            val request = Request.Builder().url(opaque).header("Range", "bytes=4096-")
                .header("If-Range", "\"original-version\"").build()
            client.newCall(request).execute().use { response ->
                assertEquals(416, response.code)
                assertEquals(opaque, response.request.url.toString())
                assertEquals("bytes */4096", response.header("Content-Range"))
                assertFalse(body.closed)
                assertEquals("range diagnostic", response.body.string())
            }
            assertTrue(body.closed)
        }
    }

    @Test fun authenticationAndDownloadPermissionRefusalsNeverFallBackToStreaming() {
        for ((status, kind) in listOf(401 to SubsonicFailureKind.AUTHENTICATION, 403 to SubsonicFailureKind.FORBIDDEN)) {
            val body = TrackingBody("fixture private server error", "text/plain")
            val methods = mutableListOf<String>()
            val client = client(body, status) { methods += it.url.pathSegments.last() }
            val error = assertThrows(SubsonicException::class.java) {
                client.newCall(Request.Builder().url(opaque()).build()).execute().close()
            }
            assertEquals(kind, error.kind)
            assertEquals(status, error.httpStatus)
            assertFalse(error.retryable)
            assertSafe(error)
            assertEquals(listOf("download.view"), methods)
            assertTrue(body.closed)
        }
    }

    @Test fun protocolPermissionErrorsAreTypedAndClosedForJsonAndXml() {
        for ((type, payload) in listOf("application/json" to
            "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":50,\"message\":\"fixture private server error\"}}}",
            "text/xml" to "<subsonic-response status=\"failed\"><error code=\"50\" message=\"fixture private server error\"/></subsonic-response>")) {
            val body = TrackingBody(payload, type)
            val methods = mutableListOf<String>()
            val client = client(body) { methods += it.url.pathSegments.last() }
            val error = assertThrows(SubsonicException::class.java) {
                client.newCall(Request.Builder().url(opaque()).build()).execute().close()
            }
            assertEquals(50, error.code)
            assertEquals(SubsonicFailureKind.FORBIDDEN, error.kind)
            assertFalse(error.retryable)
            assertSafe(error)
            assertEquals(listOf("download.view"), methods)
            assertTrue(body.closed)
        }
    }

    @Test fun temporaryDownloadFailuresRetainRetryAfterWithoutSwitchingEndpoints() {
        for ((status, kind) in listOf(429 to SubsonicFailureKind.RATE_LIMITED, 503 to SubsonicFailureKind.SERVER)) {
            val body = TrackingBody("fixture private server error", "text/plain")
            val methods = mutableListOf<String>()
            val client = client(body, status, mapOf("Retry-After" to "7")) { methods += it.url.pathSegments.last() }
            val error = assertThrows(SubsonicException::class.java) {
                client.newCall(Request.Builder().url(opaque()).build()).execute().close()
            }
            assertEquals(kind, error.kind)
            assertTrue(error.retryable)
            assertEquals(7000L, error.retryAfterMs)
            assertSafe(error)
            assertEquals(listOf("download.view"), methods)
            assertTrue(body.closed)
        }
    }

    private fun assertSafe(error: SubsonicException) {
        assertNull(error.cause)
        for (sensitive in listOf("example.test", "fixture-password", "fixture-user", "fixture private server error")) {
            assertFalse(error.toString().contains(sensitive))
        }
    }

    private class TrackingBody(content: String, val type: String) : ResponseBody() {
        private val buffer = Buffer().writeUtf8(content)
        private val length = buffer.size
        var closed = false
            private set
        override fun contentType(): MediaType = type.toMediaType()
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = buffer
        override fun close() {
            closed = true
            super.close()
        }
    }
}
