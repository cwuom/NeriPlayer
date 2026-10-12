package moe.ouom.neriplayer.api.sync.webdav

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WebDavArchiveLeaseConcurrencyTest {
    private val root = "https://sync.test/dav/".toHttpUrl()
    private val token = "urn:uuid:fixture-token"
    private val xml = """
        <d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>
        <d:locktype><d:write/></d:locktype><d:lockscope><d:exclusive/></d:lockscope>
        <d:depth>infinity</d:depth><d:timeout>Second-300</d:timeout>
        <d:locktoken><d:href>$token</d:href></d:locktoken>
        <d:lockroot><d:href>https://sync.test/dav/</d:href></d:lockroot>
        </d:activelock></d:lockdiscovery></d:prop>
    """.trimIndent()

    @Test fun `concurrent lease reads do not serialize HTTP requests or response parsing`() {
        val requests = CopyOnWriteArrayList<Request>()
        val requestsEntered = CountDownLatch(4)
        val releaseRequests = CountDownLatch(1)
        val parsersEntered = CountDownLatch(4)
        val releaseParsers = CountDownLatch(1)
        val client = client(requests) { request ->
            if (request.method == "GET") {
                requestsEntered.countDown()
                await(releaseRequests)
            }
            response(request)
        }
        withWorkers { workers ->
            lease(client).use { lease ->
                try {
                    val reads = (0 until 4).map { index ->
                        workers.submit<String> {
                            lease.execute(Request.Builder().url(root.resolve("object-$index")!!)) { response, _ ->
                                parsersEntered.countDown()
                                await(releaseParsers)
                                response.body.string()
                            }
                        }
                    }
                    await(requestsEntered)
                    releaseRequests.countDown()
                    await(parsersEntered)
                    releaseParsers.countDown()
                    assertEquals(List(4) { "body" }, reads.map { it.get(5, TimeUnit.SECONDS) })
                } finally {
                    releaseRequests.countDown()
                    releaseParsers.countDown()
                }
            }
        }
        assertEquals(4, requests.count { it.method == "GET" })
        assertTrue(requests.filter { it.method == "GET" }.all { it.header("If") == "<$root> (<$token>)" })
    }

    @Test fun `concurrent readers share one pending lease refresh`() {
        val requests = CopyOnWriteArrayList<Request>()
        val refreshEntered = CountDownLatch(1)
        val duplicateRefreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val remainingReadersStarted = CountDownLatch(3)
        val refreshes = AtomicInteger()
        val client = client(requests) { request ->
            if (request.method == "LOCK") {
                if (refreshes.incrementAndGet() == 1) refreshEntered.countDown()
                else duplicateRefreshEntered.countDown()
                await(releaseRefresh)
            }
            response(request, if (request.method == "LOCK") xml else "body")
        }
        withWorkers { workers ->
            lease(client, AtomicLong(250_000L)).use { lease ->
                try {
                    val firstRead = workers.submit<String> { read(lease) }
                    await(refreshEntered)
                    val otherReads = List(3) {
                        workers.submit<String> {
                            remainingReadersStarted.countDown()
                            read(lease)
                        }
                    }
                    await(remainingReadersStarted)
                    val duplicateRefresh = duplicateRefreshEntered.await(1, TimeUnit.SECONDS)
                    releaseRefresh.countDown()
                    assertEquals(List(4) { "body" }, (listOf(firstRead) + otherReads).map { it.get(5, TimeUnit.SECONDS) })
                    assertFalse("Readers must wait for the pending refresh", duplicateRefresh)
                    assertEquals(1, refreshes.get())
                } finally {
                    releaseRefresh.countDown()
                }
            }
        }
        assertEquals(4, requests.count { it.method == "GET" })
    }

    @Test fun `closing a lease during parsing rejects the pending result and subsequent reads`() {
        val requests = CopyOnWriteArrayList<Request>()
        val parserEntered = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        val client = client(requests) { response(it) }
        withWorkers { workers ->
            val lease = lease(client)
            try {
                val pendingRead = workers.submit<Result<String>> {
                    runCatching {
                        lease.execute(Request.Builder().url(root)) { response, _ ->
                            parserEntered.countDown()
                            await(releaseParser)
                            response.body.string()
                        }
                    }
                }
                await(parserEntered)
                workers.submit { lease.close() }.get(5, TimeUnit.SECONDS)
                val countAfterClose = requests.size
                assertThrows(WebDavArchiveLeaseLostException::class.java) { read(lease) }
                assertEquals(countAfterClose, requests.size)
                releaseParser.countDown()
                assertTrue(pendingRead.get(5, TimeUnit.SECONDS).exceptionOrNull() is WebDavArchiveLeaseLostException)
                lease.close()
                assertEquals(1, requests.count { it.method == "UNLOCK" })
            } finally {
                releaseParser.countDown()
                lease.close()
            }
        }
    }

    @Test fun `expiration during concurrent parsing rejects every pending result`() {
        val requests = CopyOnWriteArrayList<Request>()
        val parsersEntered = CountDownLatch(4)
        val releaseParsers = CountDownLatch(1)
        val now = AtomicLong()
        val client = client(requests) { response(it) }
        withWorkers { workers ->
            lease(client, now).use { lease ->
                try {
                    val pendingReads = List(4) {
                        workers.submit<Result<String>> {
                            runCatching {
                                lease.execute(Request.Builder().url(root)) { response, _ ->
                                    parsersEntered.countDown()
                                    await(releaseParsers)
                                    response.body.string()
                                }
                            }
                        }
                    }
                    await(parsersEntered)
                    now.set(300_000L)
                    releaseParsers.countDown()
                    assertTrue(pendingReads.all { it.get(5, TimeUnit.SECONDS).exceptionOrNull() is WebDavArchiveLeaseLostException })
                    val countAfterExpiration = requests.size
                    assertThrows(WebDavArchiveLeaseLostException::class.java) { read(lease) }
                    assertEquals(countAfterExpiration, requests.size)
                } finally {
                    releaseParsers.countDown()
                }
            }
        }
        assertEquals(4, requests.count { it.method == "GET" })
    }

    private fun lease(client: OkHttpClient, now: AtomicLong = AtomicLong()): WebDavArchiveLease =
        WebDavArchiveLease(root, token, client, "Basic fixture", "auth", now::get, 300_000L, {})

    private fun read(lease: WebDavArchiveLease): String =
        lease.execute(Request.Builder().url(root)) { response, _ -> response.body.string() }

    private fun client(requests: MutableList<Request>, answer: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            answer(chain.request())
        }.build()

    private fun response(request: Request, body: String = "body"): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(if (request.method == "UNLOCK") 204 else 200)
        .message("fixture").header("Lock-Token", "<$token>").body(body.toResponseBody()).build()

    private fun await(latch: CountDownLatch) {
        assertTrue("Concurrent operation did not reach the expected stage", latch.await(5, TimeUnit.SECONDS))
    }

    private fun withWorkers(action: (ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(4)
        try {
            action(workers)
        } finally {
            workers.shutdownNow()
            assertTrue("Lease test workers did not terminate", workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
