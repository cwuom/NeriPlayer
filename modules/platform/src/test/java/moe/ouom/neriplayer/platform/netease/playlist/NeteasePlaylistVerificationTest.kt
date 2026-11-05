package moe.ouom.neriplayer.platform.netease.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseRequestSession
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseRequestSessionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.io.IOException

class NeteasePlaylistVerificationTest {
    private val noPause: (Long) -> Unit = { error("unexpected backoff") }
    private val rejectedRaw = """{"code":524,"message":"当前环境异常，已取消喜欢"}"""
    private val rejected = NeteasePlaylistAddOutcome.Rejected(524, "当前环境异常，已取消喜欢")
    private val firstBatch = listOf(3388734818L, 3381878429L)
    private val secondBatch = listOf(3388734819L, 3381878430L)

    @Test
    fun `524 obtains verification once and uses verified requests for later batches`() = runTest {
        var tokenCalls = 0
        val client = verificationClient {
            tokenCalls += 1
            "test-check-token"
        }
        val requests = mutableListOf<Request>()
        interceptSession(client, requests)
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        assertEquals(NeteasePlaylistAddOutcome.Ok, session.add(firstBatch))
        assertEquals(NeteasePlaylistAddOutcome.Ok, session.add(secondBatch))

        assertEquals(1, tokenCalls)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.encodedPath == "/weapi/playlist/manipulate/tracks" })
        assertTrue(requests.all { it.url.queryParameter("csrf_token") == "test-csrf" })
        verify(client).addSongsToPlaylist(eq(91L), anyList())
    }

    @Test
    fun `successful ordinary insertion does not obtain verification`() = runTest {
        var tokenCalls = 0
        val client = verificationClient {
            tokenCalls += 1
            "test-check-token"
        }
        doReturn("""{"code":200}""").`when`(client).addSongsToPlaylist(eq(91L), anyList())
        val requests = mutableListOf<Request>()
        interceptSession(client, requests)
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        assertEquals(NeteasePlaylistAddOutcome.Ok, session.add(firstBatch))

        assertEquals(0, tokenCalls)
        assertTrue(requests.isEmpty())
        verify(client).addSongsToPlaylist(91L, firstBatch)
    }

    @Test
    fun `empty verification is attempted once and preserves the original rejection`() = runTest {
        var tokenCalls = 0
        val client = verificationClient {
            tokenCalls += 1
            ""
        }
        val requests = mutableListOf<Request>()
        interceptSession(client, requests)
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        assertEquals(rejected, session.add(firstBatch))
        assertEquals(rejected, session.add(secondBatch))

        assertEquals(1, tokenCalls)
        assertTrue(requests.isEmpty())
        verify(client, times(2)).addSongsToPlaylist(eq(91L), anyList())
    }

    @Test
    fun `failed verification is attempted once and preserves the original rejection`() = runTest {
        var tokenCalls = 0
        val client = verificationClient {
            tokenCalls += 1
            throw IOException("verification unavailable")
        }
        val requests = mutableListOf<Request>()
        interceptSession(client, requests)
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        assertEquals(rejected, session.add(firstBatch))
        assertEquals(rejected, session.add(secondBatch))

        assertEquals(1, tokenCalls)
        assertTrue(requests.isEmpty())
        verify(client, times(2)).addSongsToPlaylist(eq(91L), anyList())
    }

    @Test
    fun `account switch during verification rejects the write before sending a request`() = runTest {
        var tokenCalls = 0
        val requests = mutableListOf<Request>()
        lateinit var client: NeteaseClient
        client = verificationClient {
            tokenCalls += 1
            client.setPersistedCookies(mapOf("MUSIC_U" to "other-account", "__csrf" to "other-csrf"))
            interceptSession(client, requests)
            "test-check-token"
        }
        interceptSession(client, requests)
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        val outcome = session.add(firstBatch)

        assertEquals(301, (outcome as? NeteasePlaylistAddOutcome.Rejected)?.code)
        assertEquals(1, tokenCalls)
        assertTrue(requests.isEmpty())
        verify(client).addSongsToPlaylist(eq(91L), anyList())
    }

    @Test
    fun `verified session expiration refreshes once and retries the same batch`() = runTest {
        var tokenCalls = 0
        var postCalls = 0
        val client = verificationClient {
            tokenCalls += 1
            "test-check-token"
        }
        val requests = mutableListOf<Request>()
        interceptSession(client, requests) { request ->
            when (request.method) {
                "GET" -> """{"code":200}"""
                "POST" -> {
                    postCalls += 1
                    if (postCalls == 1) """{"code":301}""" else """{"code":200}"""
                }
                else -> error("Unexpected request method: ${request.method}")
            }
        }
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        assertEquals(NeteasePlaylistAddOutcome.Ok, session.add(firstBatch))

        assertEquals(1, tokenCalls)
        assertEquals(listOf("POST", "GET", "POST"), requests.map { it.method })
        verify(client).addSongsToPlaylist(eq(91L), anyList())
    }

    @Test
    fun `persistent verified session expiration stops after one refresh and retry`() = runTest {
        var tokenCalls = 0
        val client = verificationClient {
            tokenCalls += 1
            "test-check-token"
        }
        val requests = mutableListOf<Request>()
        interceptSession(client, requests) { request ->
            if (request.method == "GET") """{"code":200}""" else """{"code":301}"""
        }
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        val outcome = session.add(firstBatch)

        assertEquals(301, (outcome as? NeteasePlaylistAddOutcome.Rejected)?.code)
        assertEquals(1, tokenCalls)
        assertEquals(listOf("POST", "GET", "POST"), requests.map { it.method })
        verify(client).addSongsToPlaylist(eq(91L), anyList())
    }

    @Test
    fun `account switch during verified session refresh prevents a second write`() = runTest {
        var tokenCalls = 0
        val client = verificationClient {
            tokenCalls += 1
            "test-check-token"
        }
        val requests = mutableListOf<Request>()
        interceptSession(client, requests) { request ->
            if (request.method == "GET") {
                client.setPersistedCookies(mapOf("MUSIC_U" to "other-account", "__csrf" to "other-csrf"))
                interceptSession(client, requests)
                """{"code":200}"""
            } else {
                """{"code":301}"""
            }
        }
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        val outcome = session.add(firstBatch)

        assertEquals(301, (outcome as? NeteasePlaylistAddOutcome.Rejected)?.code)
        assertEquals(1, tokenCalls)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        verify(client).addSongsToPlaylist(eq(91L), anyList())
    }

    @Test
    fun `rejected verification leaves later healthy batches on the ordinary insertion path`() = runTest {
        for (code in listOf(524, 403)) {
            var tokenCalls = 0
            val client = verificationClient {
                tokenCalls += 1
                "test-check-token"
            }
            doReturn("""{"code":200}""").`when`(client).addSongsToPlaylist(91L, secondBatch)
            val requests = mutableListOf<Request>()
            interceptSession(client, requests) { """{"code":$code,"message":"verification rejected"}""" }
            val session = NeteasePlaylistAddSession(client, 91L, noPause)

            assertEquals(NeteasePlaylistAddOutcome.Rejected(code, "verification rejected"), session.add(firstBatch))
            assertEquals(NeteasePlaylistAddOutcome.Ok, session.add(secondBatch))

            assertEquals(1, tokenCalls)
            assertEquals(1, requests.size)
            verify(client).addSongsToPlaylist(91L, firstBatch)
            verify(client).addSongsToPlaylist(91L, secondBatch)
        }
    }

    @Test
    fun `verification cancellation propagates without retrying or sending a request`() {
        var tokenCalls = 0
        val cancellation = CancellationException("verification cancelled")
        val client = verificationClient {
            tokenCalls += 1
            throw cancellation
        }
        val requests = mutableListOf<Request>()
        interceptSession(client, requests)
        val session = NeteasePlaylistAddSession(client, 91L, noPause)

        val error = assertThrows(CancellationException::class.java) {
            runTest { session.add(firstBatch) }
        }

        assertEquals(cancellation, error)
        assertEquals(1, tokenCalls)
        assertTrue(requests.isEmpty())
        verify(client).addSongsToPlaylist(eq(91L), anyList())
    }

    private fun verificationClient(tokenProvider: suspend () -> String): NeteaseClient {
        val client = spy(NeteaseClient(tokenProvider))
        client.setPersistedCookies(mapOf("MUSIC_U" to "test-account", "__csrf" to "test-csrf"))
        doReturn(rejectedRaw).`when`(client).addSongsToPlaylist(eq(91L), anyList())
        return client
    }

    private fun interceptSession(
        client: NeteaseClient,
        requests: MutableList<Request>,
        response: (Request) -> String = { """{"code":200}""" }
    ) {
        val store = NeteaseClient::class.java.getDeclaredField("sessionStore").run {
            isAccessible = true
            get(client) as NeteaseRequestSessionStore
        }
        val session = store.currentSession()
        val offlineClient = session.okHttpClient.newBuilder().addInterceptor { chain ->
            requests += chain.request()
            val raw = response(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(raw.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        NeteaseRequestSession::class.java.getDeclaredField("okHttpClient").apply {
            isAccessible = true
            set(session, offlineClient)
        }
    }
}
