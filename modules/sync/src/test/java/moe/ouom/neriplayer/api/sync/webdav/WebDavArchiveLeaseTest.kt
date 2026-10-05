package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveEntry
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WebDavArchiveLeaseTest {
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

    @Test fun `only exclusive depth infinity grants with matching token root and actual timeout are accepted`() {
        val grant = WebDavArchiveLockResponse.read(response(200, xml), root, "auth")
        assertEquals(300_000L, grant.durationMs)
        assertEquals(2_000L, WebDavArchiveLockResponse.read(response(200, xml.replace("Second-300", "Second-2")), root, "auth").durationMs)
        for (bad in listOf(xml.replace("exclusive", "shared"), xml.replace("infinity", "0"),
            xml.replace("https://sync.test/dav/", "https://other.test/dav/"), xml.replace("Second-300", "Second-0"),
            xml.replace("Second-300", "Second-301"), xml.replace("Second-300", "Infinite"),
            xml.replace("Second-300", "unknown"), xml.replace(token, "relative"), xml.replace("<d:depth>infinity</d:depth>", ""))) {
            assertThrows(Exception::class.java) { WebDavArchiveLockResponse.read(response(200, bad), root, "auth") }
        }
        assertThrows(IOException::class.java) { WebDavArchiveLockResponse.read(response(200, xml).newBuilder().removeHeader("Lock-Token").build(), root, "auth") }
        assertThrows(IOException::class.java) { WebDavArchiveLockResponse.read(response(200, xml), root, "auth", "urn:uuid:other") }
        assertEquals(token, WebDavArchiveLockResponse.read(response(200, xml).newBuilder().removeHeader("Lock-Token").build(), root, "auth", token).token)
    }

    @Test fun `header tokens require one bounded URI or decimal token with optional brackets`() {
        assertNull(WebDavArchiveLockResponse.headerToken(response(200, xml).newBuilder().removeHeader("Lock-Token").build()))
        for (header in listOf("", "<$token", "$token>", "<>", "<relative>", "relative", "<urn:>", "<urn:contains space>", "<$token>>", "<urn:${"a".repeat(1021)}>", "123 456", "-123", "123abc", "1".repeat(1025))) {
            assertNull(header, WebDavArchiveLockResponse.headerToken(response(200, xml).newBuilder().header("Lock-Token", header).build()))
        }
        assertEquals(token, WebDavArchiveLockResponse.headerToken(response(200, xml).newBuilder().header("Lock-Token", "  <$token>  ").build()))
        assertEquals(token, WebDavArchiveLockResponse.headerToken(response(200, xml).newBuilder().header("Lock-Token", token).build()))
        assertEquals("123456", WebDavArchiveLockResponse.headerToken(response(200, xml).newBuilder().header("Lock-Token", "<123456>").build()))
        assertEquals("123456", WebDavArchiveLockResponse.headerToken(response(200, xml).newBuilder().header("Lock-Token", "123456").build()))
        val longest = "urn:" + "a".repeat(1020)
        assertEquals(longest, WebDavArchiveLockResponse.headerToken(response(200, xml).newBuilder().header("Lock-Token", "<$longest>").build()))
    }

    @Test fun `WsgiDAV bare and rclone decimal tokens protect requests and release before reacquisition`() {
        for (grantToken in listOf(token, "1770000123456789")) {
            val requests = arrayListOf<Request>()
            val client = client(requests) { request ->
                response(if (request.method == "UNLOCK") 204 else 200, xml.replace(token, grantToken), request)
                    .newBuilder().header("Lock-Token", grantToken).build()
            }
            repeat(2) {
                acquire(client).use { lease -> lease.execute<Unit>(Request.Builder().url(root)) { _, _ -> } }
            }
            assertEquals(listOf("LOCK", "GET", "UNLOCK", "LOCK", "GET", "UNLOCK"), requests.map { it.method })
            assertTrue(requests.filter { it.method == "GET" }.all { it.header("If") == "<$root> (<$grantToken>)" })
            assertTrue(requests.filter { it.method == "UNLOCK" }.all { it.header("Lock-Token") == "<$grantToken>" })
        }
    }

    @Test fun `rejected bare or decimal grants release the header token without trusting a different XML token`() {
        for (grantToken in listOf(token, "1770000123456789")) {
            val requests = arrayListOf<Request>()
            val client = client(requests) { request ->
                response(if (request.method == "UNLOCK") 204 else 200, xml.replace(token, "urn:uuid:other"), request)
                    .newBuilder().header("Lock-Token", grantToken).build()
            }
            assertThrows(IOException::class.java) { acquire(client) }
            assertEquals(listOf("LOCK", "UNLOCK"), requests.map { it.method })
            assertEquals("<$grantToken>", requests.last().header("Lock-Token"))
        }
    }

    @Test fun `rclone refresh may canonicalize only the trailing collection slash`() {
        var now = 0L
        val requests = arrayListOf<Request>()
        val client = client(requests) { request ->
            val body = if (request.method == "LOCK" && request.body == null) xml.replace(root.toString(), "https://sync.test/dav") else xml
            response(if (request.method == "UNLOCK") 204 else 200, body, request)
        }
        acquire(client) { now }.use { lease ->
            now = 250_000L
            lease.execute<Unit>(Request.Builder().url(root)) { _, _ -> }
        }
        assertEquals(listOf("LOCK", "LOCK", "GET", "UNLOCK"), requests.map { it.method })
        assertThrows(IOException::class.java) {
            WebDavArchiveLockResponse.read(response(200, xml.replace(root.toString(), "https://sync.test/dav")), root, "auth")
        }
        for (otherRoot in listOf("https://sync.test/other", "https://other.test/dav", "https://sync.test/dav?other=1")) {
            assertThrows(IOException::class.java) {
                WebDavArchiveLockResponse.read(response(200, xml.replace(root.toString(), otherRoot)), root, "auth", token)
            }
        }
    }

    @Test fun `legacy DAV grants may omit lockroot but cannot provide empty or ambiguous roots`() {
        val rootElement = "<d:lockroot><d:href>https://sync.test/dav/</d:href></d:lockroot>"
        val withoutRoot = xml.replace(rootElement, "")
        assertEquals(token, WebDavArchiveLockResponse.read(response(200, withoutRoot), root, "auth").token)
        assertEquals(token, WebDavArchiveLockResponse.read(response(200, withoutRoot), root, "auth", token).token)
        for (bad in listOf(xml.replace(rootElement, "$rootElement$rootElement"),
            xml.replace("https://sync.test/dav/", "http://[invalid"),
            xml.replace(rootElement, "<d:lockroot><d:href/></d:lockroot>"))) {
            assertThrows(IOException::class.java) { WebDavArchiveLockResponse.read(response(200, bad), root, "auth") }
        }
    }

    @Test fun `timeout seconds require decimal digits and a valid unsigned 32 bit value`() {
        for (timeout in listOf("Second-+300", "Second--1", "Second-", "Second-0", "Second-4294967296", "Second-999999999999999999999", "Second-1.5", "second-300")) {
            assertThrows(IOException::class.java) { WebDavArchiveLockResponse.read(response(200, xml.replace("Second-300", timeout)), root, "auth") }
        }
        assertEquals(3_000L, WebDavArchiveLockResponse.read(response(200, xml.replace("Second-300", "Second-0003")), root, "auth").durationMs)
    }

    @Test fun `lock errors preserve unsupported busy authentication and access decisions`() {
        for (code in listOf(401, 403, 423, 207, 500)) {
            val error = assertThrows(IOException::class.java) { WebDavArchiveLockResponse.read(response(code, xml), root, "auth") }
            when (code) {
                401 -> assertTrue(error is WebDavAuthException)
                403 -> assertTrue(error is WebDavAccessDeniedException)
                423 -> assertTrue(error is WebDavContentConflictException)
                else -> assertTrue(error is WebDavApiException)
            }
        }
    }

    @Test fun `malformed successful grant releases its own known token and preserves the original failure`() {
        val requests = arrayListOf<Request>()
        val client = client(requests) { request -> response(if (request.method == "UNLOCK") 204 else 200, "<bad/>", request) }
        assertThrows(IOException::class.java) { acquire(client) }
        assertEquals(listOf("LOCK", "UNLOCK"), requests.map { it.method })
    }

    @Test fun `a created resource grant is rejected and released before returning the acquisition error`() {
        for (unlockCode in listOf(204, 500)) {
            val requests = arrayListOf<Request>()
            val client = client(requests) { request ->
                response(if (request.method == "UNLOCK") unlockCode else 201, xml, request)
            }
            val error = assertThrows(WebDavApiException::class.java) { acquire(client) }
            assertEquals(201, error.statusCode)
            assertEquals(listOf("LOCK", "UNLOCK"), requests.map { it.method })
            assertEquals("<$token>", requests.last().header("Lock-Token"))
            assertEquals(if (unlockCode == 204) 0 else 1, error.suppressed.size)
        }
    }

    @Test fun `a created resource without a valid token cannot release an unrelated lock`() {
        val requests = arrayListOf<Request>()
        val client = client(requests) { request -> response(201, xml, request).newBuilder().removeHeader("Lock-Token").build() }
        val error = assertThrows(WebDavApiException::class.java) { acquire(client) }
        assertEquals(201, error.statusCode)
        assertEquals(listOf("LOCK"), requests.map { it.method })
    }

    @Test fun `first valid unbounded grant is released before falling back without a lease`() {
        for (timeout in listOf("Infinite", "Second-301", "Second-4294967295")) {
            val requests = arrayListOf<Request>()
            val client = client(requests) { request ->
                response(if (request.method == "UNLOCK") 204 else 200, xml.replace("Second-300", timeout), request)
            }
            assertNull(WebDavArchiveLease.acquire("https://sync.test/dav/backup", client, "Basic fixture", "auth", false, {}))
            assertEquals(listOf("LOCK", "UNLOCK"), requests.map { it.method })
            assertEquals("<$token>", requests.last().header("Lock-Token"))
        }
    }

    @Test fun `known finite targets refuse unbounded grants and an unlock failure never permits fallback`() {
        for ((knownSupported, unlockCode) in listOf(true to 204, false to 500)) {
            val requests = arrayListOf<Request>()
            val client = client(requests) { request ->
                response(if (request.method == "UNLOCK") unlockCode else 200, xml.replace("Second-300", "Infinite"), request)
            }
            val error = assertThrows(IOException::class.java) {
                WebDavArchiveLease.acquire("https://sync.test/dav/backup", client, "Basic fixture", "auth", knownSupported, {})
            }
            assertEquals("WebDAV archive requires a finite lease of at most 300 seconds", error.message)
            assertEquals(if (unlockCode == 204) 0 else 1, error.suppressed.size)
            assertEquals(listOf("LOCK", "UNLOCK"), requests.map { it.method })
        }
    }

    @Test fun `expired lease refuses subsequent reads and expiration during a response rejects that response`() {
        var now = 0L
        val requests = arrayListOf<Request>()
        val client = client(requests) { request ->
            if (request.method == "GET") now = 300_000L
            response(if (request.method == "UNLOCK") 204 else 200, if (request.method == "LOCK") xml else "body", request)
        }
        val lease = WebDavArchiveLease.acquire("https://sync.test/dav/backup", client, "Basic fixture", "auth", false, {}, { now })!!
        lease.use {
            assertThrows(WebDavArchiveLeaseLostException::class.java) { it.execute<Unit>(Request.Builder().url(root)) { _, _ -> } }
            val count = requests.size
            assertThrows(WebDavArchiveLeaseLostException::class.java) { it.execute<Unit>(Request.Builder().url(root)) { _, _ -> } }
            assertEquals(count, requests.size)
        }
        assertEquals("UNLOCK", requests.last().method)
    }

    @Test fun `refresh uses the actual granted lifetime and does not accept a missing refresh timeout`() {
        var now = 0L
        val requests = arrayListOf<Request>()
        val client = client(requests) { request ->
            val body = if (request.method == "LOCK" && request.body == null) xml.replace("<d:timeout>Second-300</d:timeout>", "") else xml
            response(if (request.method == "UNLOCK") 204 else 200, body, request)
        }
        acquire(client) { now }.use { lease ->
            now = 250_000L
            assertThrows(IOException::class.java) { lease.execute<Unit>(Request.Builder().url(root)) { _, _ -> } }
        }
        assertEquals(listOf("LOCK", "LOCK", "UNLOCK"), requests.map { it.method })
    }

    @Test fun `a successful refresh uses a shorter actual lifetime instead of the requested timeout`() {
        var now = 0L
        val requests = arrayListOf<Request>()
        val client = client(requests) { request ->
            val body = if (request.method == "LOCK" && request.body == null) xml.replace("Second-300", "Second-30") else xml
            response(if (request.method == "UNLOCK") 204 else 200, body, request)
        }
        acquire(client) { now }.use { lease ->
            now = 250_000L
            lease.execute<Unit>(Request.Builder().url(root)) { _, _ -> }
            now = 280_000L
            assertThrows(WebDavArchiveLeaseLostException::class.java) { lease.execute<Unit>(Request.Builder().url(root)) { _, _ -> } }
        }
        assertEquals(listOf("LOCK", "LOCK", "GET", "UNLOCK"), requests.map { it.method })
    }

    @Test fun `a scheduling pause before dispatch cannot send a request after its lease budget`() {
        var clockReads = 0
        val requests = arrayListOf<Request>()
        val client = client(requests) { request -> response(204, "", request) }
        WebDavArchiveLease(root, token, client, "Basic fixture", "auth", { if (++clockReads >= 3) 300_000L else 0L },
            300_000L, {}).use { lease ->
            assertThrows(WebDavArchiveLeaseLostException::class.java) { lease.execute<Unit>(Request.Builder().url(root)) { _, _ -> } }
        }
        assertEquals(listOf("UNLOCK"), requests.map { it.method })
    }

    @Test fun `cancellation after acquisition unlocks and keeps the same cancellation instance`() {
        val cancellation = CancellationException("cancelled")
        var checks = 0
        val requests = arrayListOf<Request>()
        val client = client(requests) { request -> response(if (request.method == "UNLOCK") 204 else 200, xml, request) }
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            WebDavArchiveLease.acquire("https://sync.test/dav/backup", client, "Basic fixture", "auth", false,
                { if (++checks == 2) throw cancellation })
        })
        assertEquals(listOf("LOCK", "UNLOCK"), requests.map { it.method })
    }

    @Test fun `locked requests carry a positive root token and avoid proxy cache validation bypass`() {
        val requests = arrayListOf<Request>()
        val client = client(requests) { request -> response(if (request.method == "UNLOCK") 204 else 200, xml, request) }
        acquire(client).use { it.execute<Unit>(Request.Builder().url("https://sync.test/dav/object")) { _, _ -> } }
        val get = requests.single { it.method == "GET" }
        assertEquals("<https://sync.test/dav/> (<$token>)", get.header("If"))
        assertEquals("no-cache", get.header("Cache-Control"))
        assertEquals("no-cache", get.header("Pragma"))
    }

    @Test fun `query targets keep conditional sync without probing an optional collection lease`() {
        val requests = arrayListOf<Request>()
        val client = client(requests) { request -> response(500, "unexpected request", request) }
        assertNull(WebDavArchiveLease.acquire("https://sync.test/dav/manifest?route=fixture", client, "Basic fixture", "auth", false, {}))
        assertThrows(IOException::class.java) {
            WebDavArchiveLease.acquire("https://sync.test/dav/manifest?route=fixture", client, "Basic fixture", "auth", true, {})
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `a fragment does not change the HTTP lock root or its tagged conditions`() {
        val requests = arrayListOf<Request>()
        val client = client(requests) { request -> response(if (request.method == "UNLOCK") 204 else 200, xml, request) }
        WebDavArchiveLease.acquire("https://sync.test/dav/manifest#display", client, "Basic fixture", "auth", false, {}, { 0L })!!.use { lease ->
            assertEquals(root, lease.root)
            lease.execute<Unit>(Request.Builder().url("https://sync.test/dav/object#display")) { _, _ -> }
        }
        assertTrue(requests.filter { it.method == "LOCK" || it.method == "UNLOCK" }.all { it.url.fragment == null })
        assertEquals("<https://sync.test/dav/> (<$token>)", requests.single { it.method == "GET" }.header("If"))
    }

    @Test fun `a redirect cannot send an owned object DELETE to an unrelated file`() {
        for (status in listOf(307, 308)) {
            val requests = CopyOnWriteArrayList<RecordedRequest>()
            val client = OkHttpClient()
            try {
                MockWebServer().use { server ->
                    server.start()
                    val lockedRoot = server.url("/dav/")
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            requests += request
                            return when (request.method) {
                                "LOCK" -> MockResponse.Builder().code(200).addHeader("Lock-Token", "<$token>")
                                    .body(xml.replace(root.toString(), lockedRoot.toString())).build()
                                "DELETE" -> MockResponse.Builder().code(status).addHeader("Location", "/dav/notes.bin").build()
                                "UNLOCK" -> MockResponse(code = 204)
                                else -> MockResponse(code = 500)
                            }
                        }
                    }
                    val api = WebDavApiClient("fixture", "password", client, "auth")
                    api.acquireArchiveLease(server.url("/dav/manifest").toString(), false, {}).getOrThrow()!!.use { lease ->
                        val result = api.deleteArchiveObject(WebDavArchiveEntry("neriplayer-sync-v4-${"a".repeat(64)}.zst", "\"same-etag\""), lease)
                        assertEquals(status, (result.exceptionOrNull() as WebDavApiException).statusCode)
                    }
                    assertEquals(listOf("LOCK", "DELETE", "UNLOCK"), requests.map { it.method })
                    assertTrue(requests.none { it.url.encodedPath == "/dav/notes.bin" })
                    assertTrue(client.followRedirects)
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun acquire(client: OkHttpClient, now: () -> Long = { 0L }): WebDavArchiveLease =
        WebDavArchiveLease.acquire("https://sync.test/dav/backup", client, "Basic fixture", "auth", false, {}, now)!!
    private fun client(requests: MutableList<Request>, answer: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> requests += chain.request(); answer(chain.request()) }.build()
    private fun response(code: Int, body: String, request: Request = Request.Builder().url(root).build()): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").header("Lock-Token", "<$token>").body(body.toResponseBody()).build()
}
