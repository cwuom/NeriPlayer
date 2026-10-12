package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.api.sync.http.startOnLoopback
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavArchiveReadBudgetTest {
    @Test fun `bounded archive status errors retain their classification without cancelling requests`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val client = client(calls)
        try {
            MockWebServer().use { server ->
                server.startOnLoopback()
                val api = WebDavApiClient("test-user", "test-password", client, "auth")
                for (code in listOf(401, 403, 404, 500)) {
                    server.enqueue(MockResponse(code = code, body = "failure"))
                    if (code == 404) server.enqueue(MockResponse(code = 404))
                    val before = calls.size
                    val error = api.getArchiveFileContent(server.url("/neriplayer-sync-v3.manifest").toString()).exceptionOrNull()
                    when (code) {
                        401 -> assertTrue(error is WebDavAuthException)
                        403 -> assertTrue(error is WebDavAccessDeniedException)
                        404 -> assertTrue(error is WebDavDirectoryNotFoundException)
                        else -> assertEquals(code, (error as WebDavApiException).statusCode)
                    }
                    assertEquals(if (code == 404) 2 else 1, calls.size - before)
                    calls.drop(before).forEach { assertFalse("Bounded status errors must retain reusable connections", it.isCanceled()) }
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `oversized archive success and error bodies cancel fixed and chunked requests`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val client = client(calls)
        val content = ByteArray(2 * 1024 * 1024 + 1) { 1 }
        try {
            MockWebServer().use { server ->
                server.startOnLoopback()
                val api = WebDavApiClient("test-user", "test-password", client, "auth")
                for (code in listOf(200, 500)) {
                    for (chunked in listOf(false, true)) {
                        val response = MockResponse.Builder().code(code)
                        val body = Buffer().write(content)
                        server.enqueue((if (chunked) response.chunkedBody(body, 8192) else response.body(body)).build())
                        val error = api.getArchiveFileContent(server.url("/neriplayer-sync-v3.manifest").toString()).exceptionOrNull()
                        assertTrue(error is IOException)
                        assertTrue(error!!.message!!.contains("too large"))
                        assertTrue("Read limits must cancel before closing an unread body", calls.last().isCanceled())
                    }
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun client(calls: MutableList<Call>): OkHttpClient = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
        }).build()
}
