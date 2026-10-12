package moe.ouom.neriplayer.data.sync.host

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class WebDavArchiveReadCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `cancelling an archive GET cancels its call before releasing the lease`() = runBlocking {
        assertPendingReadIsCancelled("GET")
    }

    @Test fun `cancelling an archive directory probe after GET 404 cancels its call`() = runBlocking {
        assertPendingReadIsCancelled("PROPFIND")
    }

    @Test fun `cancelling an archive GET interrupts a blocked response body`() = runBlocking {
        assertPendingReadIsCancelled("GET", waitForBody = true)
    }

    private suspend fun assertPendingReadIsCancelled(pendingMethod: String, waitForBody: Boolean = false) = coroutineScope {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val requestEntered = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val readingBody = CountDownLatch(1)
        val callFailed = CountDownLatch(1)
        val pendingCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                if (call.request().method == pendingMethod) pendingCall.set(call)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                if (call === pendingCall.get()) callFailed.countDown()
            }

            override fun responseBodyStart(call: Call) {
                if (call === pendingCall.get()) readingBody.countDown()
            }
        }).build()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method == "LOCK") return MockResponse.Builder().code(200)
                        .addHeader("Lock-Token", "<urn:uuid:read-cancellation>")
                        .body(lockResponse(server.url("/").toString())).build()
                    if (request.method == "UNLOCK") return MockResponse(code = 204)
                    if (request.method == pendingMethod) {
                        if (waitForBody) return MockResponse.Builder().body(object : MockResponseBody {
                            override val contentLength = 4L
                            override fun writeTo(sink: BufferedSink) {
                                sink.writeUtf8("a")
                                sink.flush()
                                requestEntered.countDown()
                                check(releaseResponse.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release the archive body" }
                                sink.writeUtf8("bcd")
                            }
                        }).build()
                        requestEntered.countDown()
                        check(releaseResponse.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release the archive response" }
                        return MockResponse(code = 200, body = "unused")
                    }
                    return MockResponse(code = 404)
                }
            }
            server.startOnLoopback()
            val backend = WebDavSyncBackend(mock(WebDavStorage::class.java),
                WebDavApiClient("test-user", "test-password", client, "auth"), server.url("/backup").toString(),
                SyncRemoteSnapshotDecoder { it }, { IOException("invalid") }, {}, SyncArchiveRepository(temporary.newFolder()),
                metadataGuard = { write -> write(); true })
            val read = async(Dispatchers.IO) {
                backend.fetch()
            }
            try {
                assertTrue("Archive request did not start", requestEntered.await(5, TimeUnit.SECONDS))
                if (waitForBody) assertTrue("Archive body read did not start", readingBody.await(5, TimeUnit.SECONDS))
                read.cancel()
                assertTrue("Cancelled archive request remained active", callFailed.await(2, TimeUnit.SECONDS))
                assertTrue(pendingCall.get().isCanceled())
                withTimeout(2_000L) { read.join() }
                assertTrue(read.isCancelled)
                assertEquals(1, requests.count { it.method == "UNLOCK" })
                assertTrue(requests.filter { it.method == "GET" || it.method == "PROPFIND" }
                    .all { it.headers["If"] == "<${server.url("/")}> (<urn:uuid:read-cancellation>)" })
            } finally {
                releaseResponse.countDown()
                read.cancel()
                withTimeout(5_000L) { read.join() }
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun lockResponse(root: String) = """
        <d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>
        <d:locktype><d:write/></d:locktype><d:lockscope><d:exclusive/></d:lockscope>
        <d:depth>infinity</d:depth><d:timeout>Second-300</d:timeout>
        <d:locktoken><d:href>urn:uuid:read-cancellation</d:href></d:locktoken>
        <d:lockroot><d:href>$root</d:href></d:lockroot>
        </d:activelock></d:lockdiscovery></d:prop>
    """.trimIndent()
}
