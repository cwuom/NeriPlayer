package moe.ouom.neriplayer.api.sync.github

import java.io.Closeable
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import moe.ouom.neriplayer.api.sync.http.startOnLoopback
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubArchiveBatchPublicationHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `small archive publishes binary objects and manifest with three requests`() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            val files = archiveFiles(12)
            assertEquals(COMMIT, fixture.upload(files).getOrThrow())
            assertEquals(listOf("GET", "GET", "POST"), fixture.requests.map { it.method })
            assertEquals(1, fixture.requests.count { it.url.encodedPath == "/graphql" })
            val input = fixture.batchInput()
            assertEquals(HEAD, input.getString("expectedHeadOid"))
            assertEquals("owner/repo", input.getJSONObject("branch").getString("repositoryNameWithOwner"))
            assertEquals("main", input.getJSONObject("branch").getString("branchName"))
            val additions = input.getJSONObject("fileChanges").getJSONArray("additions")
            assertEquals(files.size, additions.length())
            files.forEachIndexed { index, (path, bytes) ->
                assertEquals(path, additions.getJSONObject(index).getString("path"))
                assertArrayEquals(bytes, Base64.getDecoder().decode(additions.getJSONObject(index).getString("contents")))
            }
        }
    }

    @Test
    fun `batch conflict never falls back to another publication`() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            fixture.batchFailure = """{"errors":[{"type":"STALE_DATA","message":"branch moved"}]}"""
            assertTrue(fixture.upload(archiveFiles(2)).exceptionOrNull() is GitHubContentConflictException)
            assertEquals(1, fixture.requests.count { it.method == "POST" })
            assertFalse(fixture.requests.any { it.url.encodedPath.endsWith("/git/blobs") })
        }
    }

    @Test
    fun `HTTP and ambiguous acknowledgement failures never retry through staging`() = runBlocking {
        for ((code, body) in listOf(500 to "failure", 200 to "{}", 200 to """{"data":null,"errors":[{"type":"FORBIDDEN","message":"denied"}]}""")) {
            Fixture(temporary.newFolder()).use { fixture ->
                fixture.batchCode = code
                fixture.batchFailure = body
                assertTrue(fixture.upload(archiveFiles(1)).isFailure)
                assertEquals(1, fixture.requests.count { it.method == "POST" })
            }
        }
    }

    @Test
    fun `unclassified branch changes including ancestor rewinds are conflicts`() = runBlocking {
        for (changedHead in listOf("2".repeat(40), "0".repeat(40))) {
            Fixture(temporary.newFolder()).use { fixture ->
                fixture.branchHead = changedHead
                fixture.batchFailure = """{"errors":[{"type":"UNPROCESSABLE","message":"expected head mismatch"}]}"""
                assertTrue(fixture.upload(archiveFiles(1)).exceptionOrNull() is GitHubContentConflictException)
                assertEquals(changedHead, fixture.branchHead)
                assertEquals(1, fixture.requests.count { it.method == "POST" })
            }
        }
    }

    @Test
    fun `permission failures at unchanged head retain their classification`() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            fixture.batchFailure = """{"errors":[{"type":"FORBIDDEN","message":"permission denied"}]}"""
            val error = fixture.upload(archiveFiles(1)).exceptionOrNull()
            assertTrue(error is GitHubGraphQlException)
            assertFalse(error is GitHubContentConflictException)
            assertEquals(1, fixture.requests.count { it.method == "POST" })
        }
        Fixture(temporary.newFolder()).use { fixture ->
            fixture.batchCode = 401
            fixture.batchFailure = "expired"
            assertTrue(fixture.upload(archiveFiles(1)).exceptionOrNull() is TokenExpiredException)
            assertEquals(1, fixture.requests.count { it.method == "POST" })
        }
    }

    @Test
    fun `oversized publication response cannot acknowledge or retry a batch`() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            fixture.batchFailure = "x".repeat(64 * 1024 + 1)
            assertTrue(fixture.upload(archiveFiles(1)).isFailure)
            assertEquals(1, fixture.requests.count { it.method == "POST" })
            assertTrue(fixture.activeCall.get().isCanceled())
        }
    }

    @Test
    fun `batch rate limits persist repository cooldown without sending staging requests`() = runBlocking {
        for (code in listOf(200, 403, 429)) {
            Fixture(temporary.newFolder()).use { fixture ->
                fixture.batchCode = code
                fixture.batchFailure = """{"errors":[{"type":"RATE_LIMITED","message":"rate limit"}]}"""
                val error = fixture.upload(archiveFiles(1)).exceptionOrNull() as GitHubRateLimitException
                assertEquals(fixture.now + 120_000L, error.retryAtMillis)
                val requestCount = fixture.requests.size
                assertTrue(fixture.upload(archiveFiles(1)).exceptionOrNull() is GitHubRateLimitException)
                assertEquals(requestCount, fixture.requests.size)
                assertEquals(1, fixture.requests.count { it.method == "POST" })
            }
        }
    }

    @Test
    fun `large one shot sequence falls back before publication and retains staged recovery`() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            val files = archiveFiles(65)
            fixture.rejectCommit = true
            assertTrue(fixture.upload(files, oneShot = true).isFailure)
            fixture.rejectCommit = false
            assertEquals(COMMIT, fixture.upload(files, oneShot = true).getOrThrow())
            assertEquals(files.size, fixture.requests.count { it.url.encodedPath.endsWith("/git/blobs") })
            assertEquals(1, fixture.requests.count { it.url.encodedPath == "/graphql" })
            assertTrue(fixture.batchRequests.isEmpty())
        }
    }

    @Test
    fun `exactly 64 changes use a batch and 65 changes use staged publication`() = runBlocking {
        for (objectCount in listOf(63, 64)) {
            Fixture(temporary.newFolder()).use { fixture ->
                val files = archiveFiles(objectCount)
                assertEquals(COMMIT, fixture.upload(files, oneShot = true).getOrThrow())
                assertEquals(if (objectCount == 63) 1 else 0, fixture.batchRequests.size)
                assertEquals(if (objectCount == 63) 0 else files.size,
                    fixture.requests.count { it.url.encodedPath.endsWith("/git/blobs") })
            }
        }
    }

    @Test
    fun `byte budget fallback preserves all binary data`() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            val bytes = ByteArray(1600 * 1024) { (it % 251).toByte() }
            val second = bytes.copyOf().apply { this[0] = 1 }
            val files = listOf(objectFile(bytes), objectFile(second), MANIFEST to byteArrayOf(1))
            assertEquals(COMMIT, fixture.upload(files, oneShot = true).getOrThrow())
            assertTrue(fixture.batchRequests.isEmpty())
            val blob = fixture.requests.first { it.url.encodedPath.endsWith("/git/blobs") }
            assertArrayEquals(bytes, Base64.getDecoder().decode(JSONObject(blob.body!!.utf8()).getString("content")))
        }
    }

    @Test
    fun `batch cancellation interrupts the actual publication call`() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            fixture.blockBatch = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val transfer = scope.async { fixture.upload(archiveFiles(1)) }
                assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
                transfer.cancel()
                withTimeout(5_000) { transfer.join() }
                assertTrue(fixture.activeCall.get().isCanceled())
                assertEquals(1, fixture.requests.count { it.method == "POST" })
            } finally {
                fixture.release.countDown()
                scope.cancel()
            }
        }
    }

    @Test
    fun `bounded batch removes serial network round trips`() = runBlocking {
        val files = archiveFiles(12)
        var classicMillis = 0L
        Fixture(temporary.newFolder()).use { fixture ->
            fixture.requestDelayMillis = 120
            val started = System.nanoTime()
            fixture.upload(files, retainClosure = false).getOrThrow()
            classicMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(18, fixture.requests.size)
        }
        Fixture(temporary.newFolder()).use { fixture ->
            fixture.requestDelayMillis = 120
            val started = System.nanoTime()
            fixture.upload(files).getOrThrow()
            val batchMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(3, fixture.requests.size)
            println("GitHub publication benchmark: 13 files, 120 ms per request, classic=$classicMillis ms (18 requests), batch=$batchMillis ms (3 requests)")
        }
    }

    private class Fixture(directory: File) : Closeable {
        val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        val batchRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        val activeCall = AtomicReference<Call>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val now = 1_000_000L
        var batchCode = 200
        var batchFailure: String? = null
        var rejectCommit = false
        var blockBatch = false
        var requestDelayMillis = 0L
        var branchHead = HEAD
        private val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { activeCall.set(call) }
        }).build()
        private val server = MockWebServer()
        private val transport: GitHubRepositorySyncTransport

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (requestDelayMillis > 0) Thread.sleep(requestDelayMillis)
                    val path = request.url.encodedPath
                    return when {
                        path == "/graphql" -> publication(request)
                        path == "/repos/owner/repo" -> response("""{"node_id":"repository-node"}""")
                        path == "/repos/owner/repo/git/ref/heads/main" -> response("""{"object":{"sha":"$branchHead"}}""")
                        request.method == "GET" && path.endsWith("/git/commits/$HEAD") -> response("""{"tree":{"sha":"$TREE"}}""")
                        request.method == "GET" && path.endsWith("/git/trees/$TREE") -> response("""{"sha":"$TREE","truncated":false,"tree":[]}""")
                        path.endsWith("/git/blobs") -> {
                            val bytes = Base64.getDecoder().decode(JSONObject(request.body!!.utf8()).getString("content"))
                            response("""{"sha":"${GitHubSyncCheckpoint.gitBlobSha(bytes)}"}""")
                        }
                        path.endsWith("/git/trees") -> response("""{"sha":"$TREE"}""")
                        path.endsWith("/git/commits") -> if (rejectCommit) MockResponse(code = 500, body = "failed") else response("""{"sha":"$COMMIT"}""")
                        else -> MockResponse(code = 404, body = "unexpected")
                    }
                }
            }
            server.startOnLoopback()
            transport = GitHubRepositorySyncTransport(client, "test", server.url("/").toString().trimEnd('/'), "expired", directory) { now }
        }

        suspend fun upload(files: List<Pair<String, ByteArray>>, oneShot: Boolean = false, retainClosure: Boolean = true): Result<String> {
            val sequence = if (oneShot) files.iterator().asSequence() else files.asSequence()
            val retained = if (retainClosure) files.map { it.first }.filter { it != MANIFEST }.toSet() else null
            return transport.updateFilesContent("owner", "repo", sequence, GitHubSyncHead("main", HEAD), "sync", retained)
        }

        fun batchInput(): JSONObject = JSONObject(batchRequests.single().body!!.utf8()).getJSONObject("variables").getJSONObject("input")

        private fun publication(request: RecordedRequest): MockResponse {
            val input = JSONObject(request.body!!.utf8()).getJSONObject("variables").getJSONObject("input")
            if (!input.has("fileChanges")) return response("""{"data":{"updateRefs":{"clientMutationId":"$COMMIT"}}}""")
            batchRequests += request
            if (blockBatch) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            val body = batchFailure ?: JSONObject().put("data", JSONObject().put("createCommitOnBranch", JSONObject()
                .put("clientMutationId", input.getString("clientMutationId"))
                .put("commit", JSONObject().put("oid", COMMIT).put("parents", JSONObject().put("nodes", JSONArray().put(JSONObject().put("oid", HEAD)))))
                .put("ref", JSONObject().put("name", "main").put("prefix", "refs/heads/").put("repository", JSONObject().put("nameWithOwner", "owner/repo"))
                    .put("target", JSONObject().put("oid", COMMIT))))).toString()
            return MockResponse.Builder().code(batchCode).body(body).apply { if (batchFailure?.contains("rate limit") == true) addHeader("Retry-After", "120") }.build()
        }

        private fun response(body: String) = MockResponse(code = 200, body = body)

        override fun close() {
            release.countDown()
            server.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun archiveFiles(count: Int) = (0 until count).map { objectFile(byteArrayOf(it.toByte(), 0, -1, 10)) } + (MANIFEST to byteArrayOf(1, 2))
    private fun objectFile(bytes: ByteArray): Pair<String, ByteArray> = "neriplayer-sync-v4-${GitHubSyncCheckpoint.contentKey(bytes)}.zst" to bytes

    private companion object {
        const val MANIFEST = "neriplayer-sync-v3.manifest"
        val HEAD = "1".repeat(40)
        val TREE = "2".repeat(40)
        val COMMIT = "3".repeat(40)
    }
}
