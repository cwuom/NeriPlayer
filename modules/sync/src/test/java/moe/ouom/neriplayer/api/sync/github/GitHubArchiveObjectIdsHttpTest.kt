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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubArchiveObjectIdsHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ref = "1".repeat(40)
    private val tree = "2".repeat(40)
    private val v3 = "neriplayer-sync-v3-${"a".repeat(64)}.zst"
    private val v4 = "neriplayer-sync-v4-${"b".repeat(64)}.zst"
    private val firstOid = "3".repeat(40)
    private val secondOid = "4".repeat(40)
    private val commitBody get() = """{"tree":{"sha":"$tree"}}"""

    @Test
    fun `object proof uses the fixed commit and complete nonrecursive root tree only`() = runBlocking {
        withServer { server, client ->
            server.enqueue(MockResponse(body = commitBody))
            val entries = listOf(
                entry(v3, firstOid), entry(v4, secondOid), entry("notes.txt", firstOid),
                entry("folder/$v3", firstOid), entry("neriplayer-sync-v3.manifest", firstOid),
                entry("neriplayer-sync-v4-${"c".repeat(64)}.zst", firstOid, mode = "100755"),
                entry("neriplayer-sync-v4-${"d".repeat(64)}.zst", firstOid, mode = "120000"),
                entry("neriplayer-sync-v4-${"e".repeat(64)}.zst", firstOid, mode = "040000", type = "tree")
            )
            server.enqueue(MockResponse(body = listing(entries)))
            val result = transport(client, server).getArchiveObjectIds("owner", "repo", ref).getOrThrow()
            assertEquals(linkedMapOf(v3 to firstOid, v4 to secondOid), result)
            assertRequest(server, "/repos/owner/repo/git/commits/$ref")
            assertRequest(server, "/repos/owner/repo/git/trees/$tree")
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `public client exposes empty complete object proofs`() = runBlocking {
        withServer { server, client ->
            server.enqueue(MockResponse(body = commitBody))
            server.enqueue(MockResponse(body = listing(emptyList())))
            val api = GitHubApiClient("test-token", client, "expired", server.url("/").toString().trimEnd('/'))
            assertEquals(emptyMap<String, String>(), api.getArchiveObjectIds("owner", "repo", ref).getOrThrow())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `proof reads reject moving or malformed refs before issuing requests`() = runBlocking {
        withServer { server, client ->
            val transport = transport(client, server)
            for (invalid in listOf("", "main", "refs/heads/main", "v1.0", "A".repeat(40), "1".repeat(39), "$ref?ref=main")) {
                val failure = transport.getArchiveObjectIds("owner", "repo", invalid).exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(failure?.message.orEmpty().contains("fixed commit ID"))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `incomplete ambiguous and invalid root proofs fail without returning partial maps`() = runBlocking {
        val valid = entry(v3, firstOid)
        val invalid = listOf(
            listing(listOf(valid), sha = "5".repeat(40)),
            listing(listOf(valid), truncated = "true"),
            listing(listOf(valid)).replace(",\"truncated\":false", ""),
            listing(listOf(valid), truncated = "\"false\""),
            listing(listOf(valid, valid)),
            listing(listOf(valid, entry("notes.txt", firstOid), entry("notes.txt", firstOid))),
            listing(listOf(entry(v3, "invalid"))),
            listing(listOf(entry(v3, "A".repeat(40)))),
            listing(listOf(valid.replace("\"sha\":\"$firstOid\"", "\"sha\":7"))),
            listing(listOf(valid.replace("\"path\":\"$v3\"", "\"path\":\"$v3\",\"path\":\"$v4\""))),
            listing(listOf(valid.replace(",\"mode\":\"100644\"", ""))),
            listing(listOf(valid)).replace("\"tree\":[", "\"tree\":null,\"tree\":["),
            listing(listOf(valid)) + "{}"
        )
        withServer { server, client ->
            val transport = transport(client, server)
            for (body in invalid) {
                server.enqueue(MockResponse(body = commitBody))
                server.enqueue(MockResponse(body = body))
                val failure = transport.getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
                assertTrue("invalid proof must fail: $body; got $failure", failure is IOException)
                assertFalse(failure is GitHubApiException)
            }
            assertEquals(invalid.size * 2, server.requestCount)
        }
    }

    @Test
    fun `invalid commit tree identifiers stop before reading a root tree`() = runBlocking {
        val bodies = listOf("{}", "{\"tree\":null}", "{\"tree\":[]}", "{\"tree\":{}}",
            "{\"tree\":{\"sha\":7}}", "{\"tree\":{\"sha\":\"short\"}}", "{\"tree\":{\"sha\":\"${"A".repeat(40)}\"}}",
            "$commitBody{}", "not json")
        withServer { server, client ->
            val transport = transport(client, server)
            for (body in bodies) {
                server.enqueue(MockResponse(body = body))
                val failure = transport.getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
                assertTrue("invalid commit must fail: $body; got $failure", failure is IOException)
            }
            assertEquals(bodies.size, server.requestCount)
        }
    }

    @Test
    fun `commit and root HTTP errors preserve status classification`() = runBlocking {
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { activeCall.set(call) }
        }).build()
        withServer(client) { server, configuredClient ->
            val transport = transport(configuredClient, server)
            for (atTree in listOf(false, true)) for (status in listOf(401, 403, 404, 429, 500)) {
                if (atTree) server.enqueue(MockResponse(body = commitBody))
                server.enqueue(MockResponse.Builder().code(status).body("failure").apply {
                    if (status == 429) addHeader("Retry-After", "60")
                }.build())
                val error = transport.getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
                when (status) {
                    401 -> assertTrue(error is TokenExpiredException)
                    429 -> assertTrue(error is GitHubRateLimitException)
                    else -> {
                        assertTrue(error is GitHubApiException)
                        assertFalse(error is GitHubRateLimitException)
                        assertEquals(status, (error as GitHubApiException).statusCode)
                    }
                }
                assertFalse("legal error bodies must not cancel completed requests", requireNotNull(activeCall.get()).isCanceled())
            }
        }
    }

    @Test
    fun `proof reads honor the shared repository rate checkpoint`() = runBlocking {
        withServer { server, client ->
            server.enqueue(MockResponse(body = commitBody))
            server.enqueue(MockResponse.Builder().code(429).body("limited").addHeader("Retry-After", "60").build())
            val transport = GitHubRepositorySyncTransport(client, "test-token", server.url("/").toString().trimEnd('/'),
                "expired", temporary.newFolder("checkpoint")) { 1_000L }
            val first = transport.getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
            val second = transport.getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
            assertTrue(first is GitHubRateLimitException)
            assertTrue(second is GitHubRateLimitException)
            assertEquals((first as GitHubRateLimitException).retryAtMillis, (second as GitHubRateLimitException).retryAtMillis)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `proof network errors preserve their original exception identity`() = runBlocking {
        for (atTree in listOf(false, true)) {
            val failure = IOException("synthetic proof failure")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (!atTree || chain.request().url.encodedPath.contains("/git/trees/")) throw failure
                chain.proceed(chain.request())
            }.build()
            try {
                MockWebServer().use { server ->
                    server.startOnLoopback()
                    if (atTree) server.enqueue(MockResponse(body = commitBody))
                    assertSame(failure, transport(client, server).getArchiveObjectIds("owner", "repo", ref).exceptionOrNull())
                }
            } finally { closeClient(client) }
        }
    }

    @Test
    fun `proof redirects are rejected in both read stages`() = runBlocking {
        withServer { server, client ->
            for (atTree in listOf(false, true)) {
                if (atTree) server.enqueue(MockResponse(body = commitBody))
                server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/different-endpoint").build())
                server.enqueue(MockResponse(body = if (atTree) listing(emptyList()) else commitBody))
                val failure = transport(client, server).getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(failure?.message.orEmpty().contains("unexpected endpoint"))
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test
    fun `oversize proof responses cancel before draining their bodies`() = runBlocking {
        for (atTree in listOf(false, true)) {
            val activeCall = AtomicReference<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call) }
            }).build()
            try {
                MockWebServer().use { server ->
                    server.startOnLoopback()
                    if (atTree) server.enqueue(MockResponse(body = commitBody))
                    server.enqueue(MockResponse.Builder().body(object : MockResponseBody {
                        override val contentLength = 12L * 1024L * 1024L + 1L
                        override fun writeTo(sink: BufferedSink) { sink.writeUtf8("{}").flush() }
                    }).build())
                    val failure = transport(client, server).getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
                    assertTrue(failure is IOException)
                    assertTrue(requireNotNull(activeCall.get()).isCanceled())
                }
            } finally { closeClient(client) }
        }
    }

    @Test
    fun `chunked proof responses enforce the same byte budget`() = runBlocking {
        val padding = "x".repeat(12 * 1024 * 1024)
        withServer { server, client ->
            for (atTree in listOf(false, true)) {
                if (atTree) server.enqueue(MockResponse(body = commitBody))
                val body = if (atTree) listing(emptyList()) else commitBody
                val oversized = body.dropLast(1) + ",\"padding\":\"$padding\"}"
                server.enqueue(MockResponse.Builder().chunkedBody(oversized, 8_192).build())
                val failure = transport(client, server).getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(failure?.message.orEmpty().contains("too large"))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `complete proofs retain the total root entry count budget`() = runBlocking {
        val entries = (0..100_000).map { entry("unrelated-$it", firstOid) }
        withServer { server, client ->
            server.enqueue(MockResponse(body = commitBody))
            server.enqueue(MockResponse(body = listing(entries)))
            val failure = transport(client, server).getArchiveObjectIds("owner", "repo", ref).exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("too many entries"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `cancelling commit and root requests without responses interrupts actual calls`() = runBlocking {
        for (atTree in listOf(false, true)) assertCancelledRead(atTree, waitForBody = false)
    }

    @Test
    fun `cancelling commit and root requests with blocked bodies interrupts actual calls`() = runBlocking {
        for (atTree in listOf(false, true)) assertCancelledRead(atTree, waitForBody = true)
    }

    private suspend fun assertCancelledRead(atTree: Boolean, waitForBody: Boolean) {
        val entered = CountDownLatch(1)
        val readingBody = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).eventListener(object : EventListener() {
            override fun callStart(call: Call) { activeCall.set(call) }
            override fun responseBodyStart(call: Call) {
                if (call.request().url.encodedPath.contains(if (atTree) "/git/trees/" else "/git/commits/")) readingBody.countDown()
            }
        }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (atTree && request.url.encodedPath.contains("/git/commits/")) return MockResponse(body = commitBody)
                        return blockedResponse(waitForBody, entered, release)
                    }
                }
                server.startOnLoopback()
                val transfer = scope.async { transport(client, server).getArchiveObjectIds("owner", "repo", ref) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    if (waitForBody) assertTrue(readingBody.await(5, TimeUnit.SECONDS))
                    val call = requireNotNull(activeCall.get())
                    val cancellation = CancellationException("synthetic proof cancellation")
                    transfer.cancel(cancellation)
                    withTimeout(2_000L) { transfer.join() }
                    assertTrue("cancellation must reach the actual proof call", call.isCanceled())
                    val thrown = runCatching { transfer.await() }.exceptionOrNull()
                    assertTrue(thrown is CancellationException)
                    assertTrue(generateSequence(thrown) { it.cause }.any { it === cancellation })
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
            closeClient(client)
        }
    }

    private fun blockedResponse(waitForBody: Boolean, entered: CountDownLatch, release: CountDownLatch): MockResponse {
        if (!waitForBody) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for proof cancellation" }
            return MockResponse(body = commitBody)
        }
        return MockResponse.Builder().body(object : MockResponseBody {
            override val contentLength = 4L
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("{").flush()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for proof body cancellation" }
                sink.writeUtf8("  }")
            }
        }).build()
    }

    private suspend fun withServer(client: OkHttpClient = OkHttpClient(), action: suspend (MockWebServer, OkHttpClient) -> Unit) {
        try { MockWebServer().use { server -> server.startOnLoopback(); action(server, client) } }
        finally { closeClient(client) }
    }

    private fun assertRequest(server: MockWebServer, path: String) {
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals(path, request.url.encodedPath)
        assertEquals(null, request.url.query)
    }

    private fun entry(path: String, oid: String, mode: String = "100644", type: String = "blob"): String =
        """{"path":"$path","mode":"$mode","type":"$type","sha":"$oid"}"""

    private fun listing(entries: List<String>, sha: String = tree, truncated: String = "false"): String =
        """{"sha":"$sha","tree":[${entries.joinToString(",")}],"truncated":$truncated}"""

    private fun transport(client: OkHttpClient, server: MockWebServer) =
        GitHubRepositorySyncTransport(client, "test-token", server.url("/").toString().trimEnd('/'), "expired")

    private fun closeClient(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
