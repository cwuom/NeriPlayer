package moe.ouom.neriplayer.api.sync.github

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import moe.ouom.neriplayer.api.sync.http.startOnLoopback
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubArchiveReadCancellationHttpTest {
    @Test
    fun `cancelling a fixed ref read without a response cancels its actual HTTP call`() = runBlocking {
        assertCancelledRead(waitForBody = false)
    }

    @Test
    fun `cancelling a fixed ref read interrupts a blocked response body`() = runBlocking {
        assertCancelledRead(waitForBody = true)
    }

    @Test
    fun `fixed ref HTTP failures retain their original classification`() = runBlocking {
        val client = OkHttpClient()
        try {
            MockWebServer().use { server ->
                server.startOnLoopback()
                val transport = transport(client, server.url("/").toString())
                for (code in listOf(404, 401, 403, 429, 500)) {
                    server.enqueue(MockResponse.Builder().code(code).body("failure").apply {
                        if (code == 429) addHeader("Retry-After", "60")
                    }.build())
                    val error = transport.getFileContentAtRef("owner", "repo", "backup.json", "fixed-head").exceptionOrNull()
                    when (code) {
                        404 -> assertTrue(error is GitHubFileNotFoundException)
                        401 -> assertTrue(error is TokenExpiredException)
                        429 -> assertTrue(error is GitHubRateLimitException)
                        else -> {
                            assertTrue(error is GitHubApiException)
                            assertFalse(error is GitHubRateLimitException)
                            assertEquals(code, (error as GitHubApiException).statusCode)
                        }
                    }
                    assertEquals("fixed-head", server.takeRequest(5, TimeUnit.SECONDS)?.url?.queryParameter("ref"))
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `fixed ref transport failures preserve the original exception`() = runBlocking {
        val failure = IOException("synthetic network failure")
        val client = OkHttpClient.Builder().addInterceptor { throw failure }.build()
        try {
            val result = transport(client, "http://127.0.0.1/").getFileContentAtRef("owner", "repo", "backup.json", "fixed-head")
            assertSame(failure, result.exceptionOrNull())
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun assertCancelledRead(waitForBody: Boolean) {
        val entered = CountDownLatch(1)
        val readingBody = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).eventListener(object : EventListener() {
            override fun callStart(call: Call) { activeCall.set(call) }
            override fun responseBodyStart(call: Call) { readingBody.countDown() }
        }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MockWebServer().use { server ->
                if (waitForBody) {
                    server.enqueue(MockResponse.Builder().body(object : MockResponseBody {
                        override val contentLength = 4L
                        override fun writeTo(sink: BufferedSink) {
                            sink.writeUtf8("a")
                            sink.flush()
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for body cancellation" }
                            sink.writeUtf8("bcd")
                        }
                    }).build())
                } else {
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for response cancellation" }
                            return MockResponse(code = 200, body = "data")
                        }
                    }
                }
                server.startOnLoopback()
                val transport = transport(client, server.url("/").toString())
                val transfer = scope.async {
                    transport.getFileContentAtRef("owner", "repo", "backup.json", "fixed-head")
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    if (waitForBody) assertTrue(readingBody.await(5, TimeUnit.SECONDS))
                    val call = requireNotNull(activeCall.get())
                    val cancellation = CancellationException("synthetic sibling failure")
                    transfer.cancel(cancellation)
                    withTimeout(2_000L) { transfer.join() }
                    assertTrue("cancellation must reach the actual HTTP call", call.isCanceled())
                    val thrown = runCatching { transfer.await() }.exceptionOrNull()
                    assertTrue(thrown is CancellationException)
                    assertTrue("cancellation must preserve its original cause", generateSequence(thrown) { it.cause }.any { it === cancellation })
                } finally {
                    activeCall.get()?.cancel()
                    release.countDown()
                    transfer.cancel()
                    withTimeout(5_000L) { transfer.join() }
                }
            }
        } finally {
            release.countDown()
            scope.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun transport(client: OkHttpClient, apiBase: String) =
        GitHubRepositorySyncTransport(client, "test-token", apiBase.trimEnd('/'), "expired")
}
