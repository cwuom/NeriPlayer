package moe.ouom.neriplayer.api.lyrics.client

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlLyrics
import moe.ouom.neriplayer.data.model.lyrics.amll.AmllTtmlSearchResult
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmllTtmlClientCancellationTest {
    private val clients = mutableListOf<OkHttpClient>()
    private val candidate = AmllTtmlSearchResult(
        file = "signal.ttml",
        title = "Signal",
        titles = listOf("Signal"),
        artist = "Artist",
        artists = listOf("Artist"),
        albums = listOf("Album"),
        ncmIds = emptyList(),
        qqIds = emptyList(),
        score = 100
    )

    @After
    fun closeClients() {
        clients.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `cancelling lookup cancels the active request and closes its late response`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val releaseResponse = CountDownLatch(1)
        val body = TrackingBody("[]")
        val client = client { chain ->
            started.complete(chain.call())
            if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                throw IOException("response was not released")
            }
            response(chain, body)
        }
        val lookup = launch { client.searchLyrics("Signal") }

        try {
            val activeCall = withTimeout(5_000L) { started.await() }
            lookup.cancel()

            assertTrue("lookup cancellation must cancel its HTTP call", activeCall.isCanceled())
        } finally {
            releaseResponse.countDown()
            lookup.cancelAndJoin()
        }
        assertTrue("late response must be closed", body.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `response read cancellation propagates and closes the response`() = runTest {
        val cancellation = CancellationException("response read cancelled")
        val body = TrackingBody("lyrics", failure = cancellation)
        val client = client { chain -> response(chain, body) }

        val actual = runCatching { client.getLyrics(candidate) }.exceptionOrNull()

        assertTrue("cancellation must reach the caller", actual is CancellationException)
        assertSame(cancellation, generateSequence(actual) { it.cause }.last())
        assertEquals(0L, body.closed.count)
    }

    @Test
    fun `unsuccessful response returns no lyrics and closes the response`() = runTest {
        val body = TrackingBody("unavailable")
        val client = client { chain -> response(chain, body, code = 503) }

        assertNull(client.getLyrics(candidate))
        assertEquals(0L, body.closed.count)
    }

    @Test
    fun `ordinary response read failure returns no lyrics and closes the response`() = runTest {
        val body = TrackingBody("lyrics", failure = IOException("response interrupted"))
        val client = client { chain -> response(chain, body) }

        assertNull(client.getLyrics(candidate))
        assertEquals(0L, body.closed.count)
    }

    @Test
    fun `successful response preserves raw lyrics and metadata and closes the response`() = runTest {
        val body = TrackingBody("<tt>lyrics</tt>")
        val client = client { chain -> response(chain, body) }

        val lyrics = client.getLyrics(candidate)

        assertEquals(
            AmllTtmlLyrics("<tt>lyrics</tt>", "signal.ttml", "Signal", listOf("Artist"), "Album"),
            lyrics
        )
        assertEquals(0L, body.closed.count)
    }

    private fun client(interceptor: Interceptor): AmllTtmlClient {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        clients += client
        return AmllTtmlClient(client, baseUrl = "https://amll.test")
    }

    private fun response(chain: Interceptor.Chain, body: ResponseBody, code: Int = 200): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test response")
            .body(body)
            .build()

    private class TrackingBody(content: String, failure: Exception? = null) : ResponseBody() {
        val closed = CountDownLatch(1)
        private val source = object : ForwardingSource(Buffer().writeUtf8(content)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failure != null) throw failure
                return super.read(sink, byteCount)
            }

            override fun close() {
                closed.countDown()
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = source
    }
}
