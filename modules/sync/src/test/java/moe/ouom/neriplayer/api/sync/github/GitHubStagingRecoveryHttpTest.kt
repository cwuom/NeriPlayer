package moe.ouom.neriplayer.api.sync.github

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitHubStagingRecoveryHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `rate limited upload resumes acknowledged blobs across transport instances`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val first = objectFile("first")
            val second = objectFile("second")
            fixture.limitKey = GitHubSyncCheckpoint.contentKey(second.second)
            fixture.remainingLimits = 1
            fixture.rateHeaders = Headers.headersOf("Retry-After", "120")
            val files = listOf(first, second, manifestFile())

            val limited = upload(fixture, files).exceptionOrNull() as GitHubRateLimitException
            assertEquals(fixture.now + 120_000L, limited.retryAtMillis)
            assertTrue(limited.automaticRetryAllowed)
            assertEquals("head", fixture.head)
            assertTrue(fixture.publicationRequests.isEmpty())
            val requestsBeforeCooldownRetry = fixture.requests.size
            assertTrue(upload(fixture, files).exceptionOrNull() is GitHubRateLimitException)
            assertEquals(requestsBeforeCooldownRetry, fixture.requests.size)

            fixture.now = limited.retryAtMillis + 1L
            assertEquals("commit", upload(fixture, files).getOrThrow())
            assertEquals(1, fixture.postsFor(first.second))
            assertEquals(2, fixture.postsFor(second.second))
            assertEquals(1, fixture.postsFor(manifestFile().second))
            assertEquals(1, fixture.publicationRequests.size)
        }
    }

    @Test
    fun `primary HTTP 403 is typed with reset time while permission denial stays an API failure`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("primary")
            fixture.limitKey = GitHubSyncCheckpoint.contentKey(item.second)
            fixture.remainingLimits = 1
            fixture.rateCode = 403
            fixture.rateHeaders = Headers.headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "2500")
            val primary = upload(fixture, listOf(item, manifestFile())).exceptionOrNull() as GitHubRateLimitException
            assertEquals(403, primary.statusCode)
            assertEquals(2_500_000L, primary.retryAtMillis)
            fixture.now = primary.retryAtMillis + 1L
            fixture.remainingLimits = 1
            fixture.rateHeaders = Headers.headersOf()
            fixture.rateBody = """{"message":"Resource not accessible by integration"}"""
            val denied = upload(fixture, listOf(item, manifestFile())).exceptionOrNull()
            assertTrue(denied is GitHubApiException)
            assertFalse(denied is GitHubRateLimitException)
            assertEquals("head", fixture.head)
        }
    }

    @Test
    fun `redirected rate response outside repository scope cannot write a repository cooldown`() = runBlocking {
        for (path in listOf("login", "access/denied/error")) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.redirectReadTo = path
                val error = upload(fixture, listOf(objectFile("redirect"), manifestFile())).exceptionOrNull() as GitHubRateLimitException
                assertFalse(error.automaticRetryAllowed)
                assertEquals(0, fixture.directory.listFiles()!!.size)
                assertEquals(2, fixture.requests.size)
                assertTrue(fixture.requests.all { it.method == "GET" })
            }
        }
    }

    @Test
    fun `caller without checkpoint storage still receives a typed bounded rate failure`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("no-store")
            fixture.limitKey = GitHubSyncCheckpoint.contentKey(item.second)
            fixture.remainingLimits = 1
            val transport = fixture.transport(checkpointEnabled = false)
            val result = transport.updateFilesContent("owner", "repo", sequenceOf(item, manifestFile()), GitHubSyncHead("main", "head"), "sync")
            val rate = result.exceptionOrNull() as GitHubRateLimitException
            assertFalse(rate.automaticRetryAllowed)
            assertEquals(0, fixture.directory.listFiles()!!.size)
            assertEquals("head", fixture.head)
        }
    }

    @Test
    fun `enterprise API path prefix selects the same checkpoint namespace on recovery`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("enterprise")
            val files = listOf(item, manifestFile())
            fixture.rejectCommit = true
            assertTrue(fixture.transport(apiPath = "/api/v3/").updateFilesContent("owner", "repo", files.asSequence(),
                GitHubSyncHead("main", "head"), "sync").isFailure)
            fixture.rejectCommit = false
            assertTrue(fixture.transport(apiPath = "/api/v3/").updateFilesContent("owner", "repo", files.asSequence(),
                GitHubSyncHead("main", "head"), "sync").isSuccess)
            assertEquals(1, fixture.postsFor(item.second))
            assertTrue(fixture.requests.dropLast(1).all { it.url.encodedPath.startsWith("/api/v3/repos/") })
            assertEquals("/api/graphql", fixture.publicationRequests.single().url.encodedPath)
        }
    }

    @Test
    fun `damaged checkpoint and mismatched remote acknowledgement cannot be trusted`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("content")
            fixture.rejectCommit = true
            val files = listOf(item, manifestFile())
            assertTrue(upload(fixture, files).isFailure)
            val checkpoint = fixture.directory.walkTopDown().single { it.name == "${GitHubSyncCheckpoint.contentKey(item.second)}.blob" }
            checkpoint.writeText("blob-v1\n${"0".repeat(40)}\n")
            fixture.rejectCommit = false
            fixture.invalidBlobAcknowledgement = true
            assertTrue(upload(fixture, files).isFailure)
            assertEquals("head", fixture.head)
            assertTrue(fixture.publicationRequests.isEmpty())
            assertFalse(checkpoint.exists())
            fixture.invalidBlobAcknowledgement = false
            assertTrue(upload(fixture, files).isSuccess)
            assertEquals(3, fixture.postsFor(item.second))
        }
    }

    @Test
    fun `collected remote blob invalidates only cached entries and recovers next attempt`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("collected")
            fixture.rejectCommit = true
            val files = listOf(item, manifestFile())
            assertTrue(upload(fixture, files).isFailure)
            fixture.availableBlobs.remove(GitHubSyncCheckpoint.gitBlobSha(item.second))
            fixture.rejectCommit = false
            val staleTree = upload(fixture, files)
            assertEquals(422, (staleTree.exceptionOrNull() as GitHubApiException).statusCode)
            assertEquals("head", fixture.head)
            assertTrue(fixture.publicationRequests.isEmpty())
            assertTrue(upload(fixture, files).isSuccess)
            assertEquals(2, fixture.postsFor(item.second))
        }
    }

    @Test
    fun `CAS rejection keeps blobs reusable without overwriting the advanced branch`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("cas")
            fixture.advanceBeforePublication = true
            val files = listOf(item, manifestFile())
            val result = upload(fixture, files)
            assertTrue(result.exceptionOrNull() is GitHubContentConflictException)
            assertEquals("other-head", fixture.head)
            fixture.advanceBeforePublication = false
            assertTrue(upload(fixture, files, expectedHead = "other-head").isSuccess)
            assertEquals(1, fixture.postsFor(item.second))
            val commits = fixture.requests.filter { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") }
            assertEquals("other-head", JSONObject(commits.last().body!!.utf8()).getJSONArray("parents").getString(0))
            val patches = fixture.publicationRequests
            assertTrue(patches.all { !JSONObject(it.body!!.utf8()).getJSONObject("variables").getJSONObject("input")
                .getJSONArray("refUpdates").getJSONObject(0).getBoolean("force") })
        }
    }

    @Test
    fun `atomic publication rejects an external ancestor rewind that REST fast forward would accept`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            fixture.rewindBeforePublication = true
            val item = objectFile("rewind")
            val files = listOf(item, manifestFile())
            assertTrue(upload(fixture, files).exceptionOrNull() is GitHubContentConflictException)
            assertEquals("ancestor-head", fixture.head)
            val before = JSONObject(fixture.publicationRequests.single().body!!.utf8()).getJSONObject("variables")
                .getJSONObject("input").getJSONArray("refUpdates").getJSONObject(0).getString("beforeOid")
            assertEquals("head", before)
            fixture.rewindBeforePublication = false
            assertEquals("commit", upload(fixture, files, expectedHead = "ancestor-head").getOrThrow())
            assertEquals(1, fixture.postsFor(item.second))
        }
    }

    @Test
    fun `GraphQL limits at HTTP 200 403 and 429 persist cooldown and reuse staged blobs`() = runBlocking {
        for (code in listOf(200, 403, 429)) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.publicationCode = code
                fixture.remainingPublicationLimits = 1
                fixture.publicationRateHeaders = if (code == 200) {
                    Headers.headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "2500")
                } else Headers.headersOf("Retry-After", "120")
                val item = objectFile("publication-limit")
                val files = listOf(item, manifestFile())
                val error = upload(fixture, files).exceptionOrNull() as GitHubRateLimitException
                assertTrue(error.automaticRetryAllowed)
                assertEquals(if (code == 200) 2_500_000L else fixture.now + 120_000L, error.retryAtMillis)
                assertEquals("head", fixture.head)
                val requestsBeforeRetry = fixture.requests.size
                assertTrue(upload(fixture, files).exceptionOrNull() is GitHubRateLimitException)
                assertEquals(requestsBeforeRetry, fixture.requests.size)
                fixture.now = error.retryAtMillis + 1L
                assertEquals("commit", upload(fixture, files).getOrThrow())
                assertEquals(1, fixture.postsFor(item.second))
                assertEquals(1, fixture.postsFor(manifestFile().second))
                assertEquals(2, fixture.publicationRequests.size)
            }
        }
    }

    @Test
    fun `GraphQL partial errors unsupported endpoints and permissions fail without REST publication`() = runBlocking {
        val responses = listOf(
            200 to """{"errors":[{"type":"FORBIDDEN","message":"Permission denied"}],"data":{"updateRefs":{"clientMutationId":"commit"}}}""",
            200 to """{"errors":[{"message":"Field updateRefs is not supported"}]}""",
            200 to """{"data":{"updateRefs":{"clientMutationId":"wrong"}}}""",
            404 to """{"message":"GraphQL unavailable"}""",
            403 to """{"message":"Resource not accessible by integration"}"""
        )
        for ((code, body) in responses) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.publicationCode = code
                fixture.publicationBody = body
                val error = upload(fixture, listOf(objectFile("closed"), manifestFile())).exceptionOrNull()
                assertTrue(error is IOException)
                assertFalse(error is GitHubRateLimitException)
                assertFalse(error is GitHubContentConflictException)
                assertEquals("head", fixture.head)
                assertEquals(1, fixture.publicationRequests.size)
            }
        }
    }

    @Test
    fun `GraphQL error quota headers identify limits without relying on a particular error type`() = runBlocking {
        for (headers in listOf(Headers.headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "2500"),
            Headers.headersOf("Retry-After", "120"))) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.publicationBody = """{"errors":[{"message":"quota reached"}]}"""
                fixture.publicationRateHeaders = headers
                val item = objectFile("quota-header")
                val files = listOf(item, manifestFile())
                val failure = upload(fixture, files).exceptionOrNull()
                assertTrue("Unexpected quota classification for $headers: $failure", failure is GitHubRateLimitException)
                val error = failure as GitHubRateLimitException
                assertTrue(error.automaticRetryAllowed)
                val expectedRetryAt = if (headers["Retry-After"] == null) 2_500_000L else fixture.now + 120_000L
                assertEquals(expectedRetryAt, error.retryAtMillis)
                assertEquals("head", fixture.head)
                assertEquals(1, fixture.publicationRequests.size)
                val requestCount = fixture.requests.size
                assertTrue(upload(fixture, files).exceptionOrNull() is GitHubRateLimitException)
                assertEquals(requestCount, fixture.requests.size)
                fixture.publicationBody = null
                fixture.now = error.retryAtMillis + 1L
                assertEquals("commit", upload(fixture, files).getOrThrow())
                assertEquals(1, fixture.postsFor(item.second))
                assertEquals(1, fixture.postsFor(manifestFile().second))
                assertEquals(2, fixture.publicationRequests.size)
            }
        }
    }

    @Test
    fun `GraphQL typed limits without headers persist bounded cooldown across new transport instances`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            fixture.remainingPublicationLimits = 1
            val item = objectFile("graphql-fallback-limit")
            val files = listOf(item, manifestFile())
            val error = upload(fixture, files).exceptionOrNull() as GitHubRateLimitException
            assertEquals(fixture.now + GitHubRateLimitPolicy.MIN_RETRY_DELAY_MS, error.retryAtMillis)
            assertTrue(error.automaticRetryAllowed)
            assertEquals("head", fixture.head)
            val requestCount = fixture.requests.size
            assertTrue(upload(fixture, files).exceptionOrNull() is GitHubRateLimitException)
            assertEquals(requestCount, fixture.requests.size)
            fixture.now = error.retryAtMillis + 1L
            assertEquals("commit", upload(fixture, files).getOrThrow())
            assertEquals(1, fixture.postsFor(item.second))
            assertEquals(1, fixture.postsFor(manifestFile().second))
            assertEquals(2, fixture.publicationRequests.size)
        }
    }

    @Test
    fun `GraphQL quota without checkpoint storage reports a retry deadline without enabling automatic continuation`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            fixture.remainingPublicationLimits = 1
            val item = objectFile("graphql-no-checkpoint")
            val files = listOf(item, manifestFile())
            val error = fixture.transport(checkpointEnabled = false).updateFilesContent(
                "owner", "repo", files.asSequence(), GitHubSyncHead("main", "head"), "sync"
            ).exceptionOrNull() as GitHubRateLimitException
            assertEquals(403, error.statusCode)
            assertEquals(fixture.now + GitHubRateLimitPolicy.MIN_RETRY_DELAY_MS, error.retryAtMillis)
            assertFalse(error.automaticRetryAllowed)
            assertEquals(0, fixture.directory.listFiles()!!.size)
            assertEquals("head", fixture.head)
            assertEquals(1, fixture.publicationRequests.size)
            fixture.now = error.retryAtMillis + 1L
            assertEquals("commit", fixture.transport(checkpointEnabled = false).updateFilesContent(
                "owner", "repo", files.asSequence(), GitHubSyncHead("main", "head"), "sync"
            ).getOrThrow())
            assertEquals(2, fixture.postsFor(item.second))
            assertEquals(2, fixture.postsFor(manifestFile().second))
            assertEquals(2, fixture.publicationRequests.size)
        }
    }

    @Test
    fun `redirected mutation acknowledgements and rates cannot change repository state or cooldown`() = runBlocking {
        for (differentOrigin in listOf(false, true)) {
            for (rateResponse in listOf(false, true)) {
                MockWebServer().use { redirectServer ->
                    redirectServer.start()
                    val body = if (rateResponse) """{"errors":[{"type":"RATE_LIMITED","message":"rate limit"}]}"""
                        else """{"data":{"updateRefs":{"clientMutationId":"commit"}}}"""
                    val code = if (rateResponse) 429 else 200
                    redirectServer.enqueue(MockResponse(code = code, body = body, headers = Headers.headersOf("Retry-After", "3600")))
                    ServerFixture(temporary.newFolder()).use { fixture ->
                        fixture.redirectPublicationUrl = if (differentOrigin) redirectServer.url("/graphql").toString()
                            else fixture.url("/publication-redirect")
                        fixture.redirectedPublicationBody = body
                        fixture.redirectedPublicationCode = code
                        val item = objectFile("redirect-publication")
                        val files = listOf(item, manifestFile())
                        val error = upload(fixture, files).exceptionOrNull()
                        assertTrue(error is IOException)
                        assertTrue(error!!.message!!.contains("unexpected endpoint"))
                        assertFalse(error is GitHubRateLimitException)
                        assertEquals("head", fixture.head)
                        assertTrue(fixture.directory.walkTopDown().none { it.name == "rate-limit" })
                        fixture.redirectPublicationUrl = null
                        assertEquals("commit", upload(fixture, files).getOrThrow())
                        assertEquals(1, fixture.postsFor(item.second))
                    }
                }
            }
        }
    }

    @Test
    fun `atomic mutation responses enforce the exact budget and cancel oversized owned bodies`() = runBlocking {
        val prefix = "{\"data\":{\"updateRefs\":{\"clientMutationId\":\"commit\"}},\"padding\":\""
        val suffix = "\"}"
        for (chunked in listOf(false, true)) {
            for (extra in listOf(0, 1)) {
                ServerFixture(temporary.newFolder()).use { fixture ->
                    val targetSize = GitHubArchiveRefUpdate.MAX_RESPONSE_BYTES + extra
                    fixture.publicationBody = prefix + "x".repeat(targetSize - prefix.length - suffix.length) + suffix
                    fixture.publicationResponseChunked = chunked
                    val result = upload(fixture, listOf(objectFile("response-budget"), manifestFile()))
                    val call = fixture.calls.single { it.request().url.encodedPath.endsWith("/graphql") }
                    if (extra == 0) {
                        assertEquals("commit", result.getOrThrow())
                        assertFalse(call.isCanceled())
                    } else {
                        assertTrue(result.exceptionOrNull() is IOException)
                        assertTrue(result.exceptionOrNull()!!.message!!.contains("too large"))
                        assertTrue(call.isCanceled())
                    }
                }
            }
        }
    }

    @Test
    fun `unknown GraphQL rejection type still detects a changed head by a read only ref check`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            fixture.advanceBeforePublication = true
            fixture.publicationBody = """{"errors":[{"type":"UNPROCESSABLE","message":"Update rejected"}]}"""
            val error = upload(fixture, listOf(objectFile("head-check"), manifestFile())).exceptionOrNull()
            assertTrue(error is GitHubContentConflictException)
            assertEquals("other-head", fixture.head)
            assertEquals("GET", fixture.requests.last().method)
            assertEquals("/repos/owner/repo/git/ref/heads/main", fixture.requests.last().url.encodedPath)
        }
    }

    @Test
    fun `invalid repository node IDs fail before staging any archive object`() = runBlocking {
        for (body in listOf("{}", """{"node_id":null}""", """{"node_id":7}""", """{"node_id":" "}""")) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.repositoryBody = body
                assertTrue(upload(fixture, listOf(objectFile("node-id"), manifestFile())).exceptionOrNull() is IOException)
                assertEquals(1, fixture.requests.size)
                assertTrue(fixture.requests.all { it.method == "GET" })
                assertEquals("head", fixture.head)
            }
        }
    }

    @Test
    fun `canceling a pending GraphQL publication cancels its owned call and preserves reusable staging`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            fixture.blockWindow = "publication"
            val item = objectFile("cancel-publication")
            val files = listOf(item, manifestFile())
            val job = async(Dispatchers.Default) { upload(fixture, files) }
            try {
                assertTrue(fixture.blockEntered.await(10, TimeUnit.SECONDS))
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
                assertTrue(fixture.calls.single { it.request().url.encodedPath.endsWith("/graphql") }.isCanceled())
                assertEquals("head", fixture.head)
            } finally {
                fixture.blockWindow = null
                fixture.blockRelease.countDown()
                job.cancel()
                job.join()
            }
            assertEquals("commit", upload(fixture, files).getOrThrow())
            assertEquals(1, fixture.postsFor(item.second))
        }
    }

    @Test
    fun `repository namespace separates identical blob checkpoints`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("shared")
            val files = listOf(item, manifestFile())
            assertTrue(upload(fixture, files).isSuccess)
            fixture.head = "head"
            assertTrue(upload(fixture, files, owner = "different-owner").isSuccess)
            assertEquals(2, fixture.postsFor(item.second))
            assertEquals(2, fixture.directory.listFiles()!!.count { it.isDirectory })
        }
    }

    @Test
    fun `maximum binary block including HTTP and JSON envelope stays below three megabytes`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            val file = "neriplayer-sync-v3-${GitHubSyncCheckpoint.contentKey(bytes)}.zst" to bytes
            assertTrue(upload(fixture, listOf(file, manifestFile())).isSuccess)
            val blob = fixture.requests.single { it.method == "POST" && it.bodySize > 2_000_000L }
            val wireBytes = blob.bodySize + blob.headers.toString().toByteArray().size + blob.requestLine.toByteArray().size + 4L
            assertTrue("$wireBytes bytes", wireBytes < 3_000_000L)
        }
    }

    @Test
    fun `consecutive limits stop automatic continuation and new object progress resets the budget`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            val item = objectFile("limited")
            fixture.limitKey = GitHubSyncCheckpoint.contentKey(item.second)
            fixture.remainingLimits = 4
            val files = listOf(item, manifestFile())
            repeat(4) { attempt ->
                val error = upload(fixture, files).exceptionOrNull() as GitHubRateLimitException
                assertEquals(attempt < 3, error.automaticRetryAllowed)
                assertEquals(fixture.now + 60_000L * (1L shl attempt), error.retryAtMillis)
                fixture.now = error.retryAtMillis + 1L
            }
            fixture.limitKey = GitHubSyncCheckpoint.contentKey(manifestFile().second)
            fixture.remainingLimits = 1
            val afterProgress = upload(fixture, files).exceptionOrNull() as GitHubRateLimitException
            assertTrue(afterProgress.automaticRetryAllowed)
            assertEquals(fixture.now + 60_000L, afterProgress.retryAtMillis)
            assertEquals("head", fixture.head)
        }
    }

    @Test
    fun `cancellation during final blob or commit response prevents manifest publication`() = runBlocking {
        for (window in listOf("blob", "commit")) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.blockWindow = window
                val job = async(Dispatchers.Default) { upload(fixture, listOf(objectFile("cancel"), manifestFile())) }
                try {
                    assertTrue("HTTP request reached $window", fixture.blockEntered.await(10, TimeUnit.SECONDS))
                    job.cancel()
                    fixture.blockRelease.countDown()
                    job.join()
                    assertTrue(job.isCancelled)
                    assertEquals("head", fixture.head)
                    assertTrue(fixture.publicationRequests.isEmpty())
                    if (window == "blob") {
                        assertTrue(fixture.requests.none { it.url.encodedPath.endsWith("/git/trees") })
                        assertTrue(fixture.requests.none { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") })
                    }
                } finally {
                    fixture.blockRelease.countDown()
                    job.cancel()
                    job.join()
                }
            }
        }
    }

    @Test
    fun `large fixed and chunked tree listings publish using only the leading top-level SHA`() = runBlocking {
        val responseBody = largeTreeResponse(shaFirst = true)
        assertTrue(responseBody.toByteArray().size > 12 * 1024 * 1024)
        for (chunked in listOf(false, true)) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.treeResponseBody = responseBody
                fixture.treeResponseChunked = chunked
                val files = listOf(objectFile("large-tree"), manifestFile())
                assertEquals("commit", upload(fixture, files).getOrThrow())
                assertEquals("commit", fixture.head)
                assertTrue(fixture.calls.single { it.request().url.encodedPath.endsWith("/git/trees") }.isCanceled())
                assertEquals(listOf(
                    "GET /repos/owner/repo",
                    "GET /repos/owner/repo/git/commits/head",
                    "POST /repos/owner/repo/git/blobs",
                    "POST /repos/owner/repo/git/blobs",
                    "POST /repos/owner/repo/git/trees",
                    "POST /repos/owner/repo/git/commits",
                    "POST /graphql"
                ), fixture.requests.map { "${it.method} ${it.url.encodedPath}" })
                val commit = JSONObject(fixture.requests.single { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") }.body!!.utf8())
                assertEquals("tree", commit.getString("tree"))
                assertEquals("head", commit.getJSONArray("parents").getString(0))
                val publication = JSONObject(fixture.publicationRequests.single().body!!.utf8()).getJSONObject("variables")
                    .getJSONObject("input").getJSONArray("refUpdates").getJSONObject(0)
                assertEquals("head", publication.getString("beforeOid"))
                assertEquals("commit", publication.getString("afterOid"))
                assertFalse(publication.getBoolean("force"))
            }
        }
    }

    @Test
    fun `tree SHA beyond the response prefix budget never creates or publishes a commit`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            fixture.treeResponseBody = largeTreeResponse(shaFirst = false)
            fixture.treeResponseChunked = true
            val error = upload(fixture, listOf(objectFile("late-tree-sha"), manifestFile())).exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.contains("prefix budget"))
            assertUnpublishedTreeFailure(fixture)
        }
    }

    @Test
    fun `missing nested-only malformed and non-string tree SHAs cannot publish a snapshot`() = runBlocking {
        val invalidResponses = listOf(
            """{"tree":[{"sha":"nested"}]}""",
            """{"sha":null}""",
            """{"sha":7}""",
            """{"sha":{}}""",
            """{"sha":""}""",
            """{"sha":"  "}""",
            """[{"sha":"nested"}]""",
            """{"sha":"unfinished""",
            """{sha:"tree"}"""
        )
        for (body in invalidResponses) {
            ServerFixture(temporary.newFolder()).use { fixture ->
                fixture.treeResponseBody = body
                assertTrue(body, upload(fixture, listOf(objectFile("invalid-tree"), manifestFile())).exceptionOrNull() is IOException)
                assertUnpublishedTreeFailure(fixture)
            }
        }
    }

    @Test
    fun `small response ignores nested SHA and uses a later top-level SHA`() = runBlocking {
        ServerFixture(temporary.newFolder()).use { fixture ->
            fixture.treeResponseBody = """{"tree":[{"sha":"nested"}],"truncated":true,"sha":"tree"}"""
            assertEquals("commit", upload(fixture, listOf(objectFile("nested-tree"), manifestFile())).getOrThrow())
            val commit = JSONObject(fixture.requests.single { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") }.body!!.utf8())
            assertEquals("tree", commit.getString("tree"))
        }
    }

    private fun assertUnpublishedTreeFailure(fixture: ServerFixture) {
        assertEquals("head", fixture.head)
        assertTrue(fixture.requests.none { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") })
        assertTrue(fixture.publicationRequests.isEmpty())
        assertTrue(fixture.calls.single { it.request().url.encodedPath.endsWith("/git/trees") }.isCanceled())
    }

    private fun largeTreeResponse(shaFirst: Boolean): String = buildString {
        val hash = "a".repeat(64)
        val entry = """{"path":"neriplayer-sync-v3-$hash.zst","mode":"100644","type":"blob","sha":"${"b".repeat(40)}","size":1024,"url":"https://api.github.com/repos/owner/repo/git/blobs/${"b".repeat(40)}"}"""
        append('{')
        if (shaFirst) append("\"sha\":\"tree\",")
        append("\"tree\":[")
        repeat(50_000) { index ->
            if (index > 0) append(',')
            append(entry)
        }
        append(']')
        if (!shaFirst) append(",\"sha\":\"tree\"")
        append('}')
    }

    private suspend fun upload(
        fixture: ServerFixture,
        files: List<Pair<String, ByteArray>>,
        expectedHead: String = "head",
        owner: String = "owner"
    ): Result<String> = fixture.transport().updateFilesContent(owner, "repo", files.asSequence(),
        GitHubSyncHead("main", expectedHead), "sync")

    private fun objectFile(text: String): Pair<String, ByteArray> {
        val bytes = text.toByteArray()
        return "neriplayer-sync-v3-${GitHubSyncCheckpoint.contentKey(bytes)}.zst" to bytes
    }

    private fun manifestFile(): Pair<String, ByteArray> = "neriplayer-sync-v3.manifest" to "manifest".toByteArray()

    private class ServerFixture(val directory: File) : Closeable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val calls = CopyOnWriteArrayList<Call>()
        val publicationRequests get() = requests.filter { it.url.encodedPath.endsWith("/graphql") }
        val availableBlobs = mutableSetOf<String>()
        var now = 1_000_000L
        var head = "head"
        var limitKey: String? = null
        var remainingLimits = 0
        var rateHeaders = Headers.headersOf()
        var rateCode = 429
        var rateBody = """{"message":"secondary rate limit"}"""
        var invalidBlobAcknowledgement = false
        var rejectCommit = false
        var advanceBeforePublication = false
        var rewindBeforePublication = false
        var repositoryBody = """{"node_id":"repository-node"}"""
        var publicationBody: String? = null
        var publicationCode = 200
        var remainingPublicationLimits = 0
        var publicationRateHeaders = Headers.headersOf()
        var publicationResponseChunked = false
        var redirectPublicationUrl: String? = null
        var redirectedPublicationBody = ""
        var redirectedPublicationCode = 200
        var blockWindow: String? = null
        var redirectReadTo: String? = null
        var treeResponseBody: String? = null
        var treeResponseChunked = false
        val blockEntered = CountDownLatch(1)
        val blockRelease = CountDownLatch(1)
        private var commitParent = ""
        private val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
        }).build()
        private val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return respond(request)
                }
            }
            start()
        }

        fun transport(checkpointEnabled: Boolean = true, apiPath: String = "/"): GitHubRepositorySyncTransport = GitHubRepositorySyncTransport(
            client, "test-token", server.url(apiPath).toString().trimEnd('/'), "expired", if (checkpointEnabled) directory else null, { now }
        )

        fun postsFor(content: ByteArray): Int = requests.count {
            it.method == "POST" && it.url.encodedPath.endsWith("/git/blobs") &&
                Base64.getDecoder().decode(JSONObject(it.body!!.utf8()).getString("content")).contentEquals(content)
        }

        fun url(path: String): String = server.url(path).toString()

        private fun respond(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            if (path == "/publication-redirect") return MockResponse(code = redirectedPublicationCode,
                body = redirectedPublicationBody, headers = Headers.headersOf("Retry-After", "3600"))
            if (request.method == "GET") {
                if (!path.contains("/repos/")) return MockResponse(code = 429, body = """{"message":"rate limit"}""")
                redirectReadTo?.let { return MockResponse(code = 307, headers = Headers.headersOf("Location", server.url("/$it").toString())) }
                if (path.contains("/git/ref/heads/")) return MockResponse(body = """{"object":{"sha":"$head"}}""")
                if (!path.contains("/git/")) return MockResponse(body = repositoryBody)
                return MockResponse(body = """{"tree":{"sha":"base-tree"}}""")
            }
            if (path.endsWith("/git/blobs")) return createBlob(request)
            if (path.endsWith("/git/trees")) return createTree(request)
            if (path.endsWith("/git/commits")) return createCommit(request)
            if (path.endsWith("/graphql")) return publish(request)
            return MockResponse(code = 404)
        }

        private fun createBlob(request: RecordedRequest): MockResponse {
            val content = Base64.getDecoder().decode(JSONObject(request.body!!.utf8()).getString("content"))
            if (blockWindow == "blob" && content.contentEquals("manifest".toByteArray())) awaitRelease()
            if (GitHubSyncCheckpoint.contentKey(content) == limitKey && remainingLimits > 0) {
                remainingLimits--
                return MockResponse(code = rateCode, headers = rateHeaders, body = rateBody)
            }
            val sha = GitHubSyncCheckpoint.gitBlobSha(content)
            availableBlobs += sha
            return MockResponse(code = 201, body = """{"sha":"${if (invalidBlobAcknowledgement) "0".repeat(40) else sha}"}""")
        }

        private fun createTree(request: RecordedRequest): MockResponse {
            val entries = JSONObject(request.body!!.utf8()).getJSONArray("tree")
            for (index in 0 until entries.length()) {
                if (entries.getJSONObject(index).getString("sha") !in availableBlobs) {
                    return MockResponse(code = 422, body = """{"message":"invalid blob SHA"}""")
                }
            }
            val body = treeResponseBody ?: """{"sha":"tree"}"""
            val response = MockResponse.Builder().code(201)
            return (if (treeResponseChunked) response.chunkedBody(Buffer().writeUtf8(body), 8_192) else response.body(body)).build()
        }

        private fun createCommit(request: RecordedRequest): MockResponse {
            if (blockWindow == "commit") awaitRelease()
            if (rejectCommit) return MockResponse(code = 500, body = """{"message":"unavailable"}""")
            commitParent = JSONObject(request.body!!.utf8()).getJSONArray("parents").getString(0)
            return MockResponse(code = 201, body = """{"sha":"commit"}""")
        }

        private fun publish(request: RecordedRequest): MockResponse {
            redirectPublicationUrl?.let { return MockResponse(code = 307, headers = Headers.headersOf("Location", it)) }
            if (blockWindow == "publication") {
                awaitRelease()
                return MockResponse(code = 503, body = """{"message":"aborted test publication"}""")
            }
            if (remainingPublicationLimits > 0) {
                remainingPublicationLimits--
                return MockResponse(code = publicationCode, headers = publicationRateHeaders,
                    body = """{"errors":[{"type":"RATE_LIMITED","message":"secondary rate limit"}]}""")
            }
            if (advanceBeforePublication) head = "other-head"
            if (rewindBeforePublication) head = "ancestor-head"
            publicationBody?.let {
                val response = MockResponse.Builder().code(publicationCode).headers(publicationRateHeaders)
                return (if (publicationResponseChunked) response.chunkedBody(Buffer().writeUtf8(it), 8_192) else response.body(it)).build()
            }
            val input = JSONObject(request.body!!.utf8()).getJSONObject("variables").getJSONObject("input")
            val update = input.getJSONArray("refUpdates").getJSONObject(0)
            check(input.getString("repositoryId") == "repository-node")
            check(update.getString("name") == "refs/heads/main")
            check(!update.getBoolean("force"))
            if (update.getString("beforeOid") != head) return MockResponse(body =
                """{"errors":[{"type":"STALE_DATA","message":"Reference does not match beforeOid"}]}""")
            check(commitParent == head)
            head = update.getString("afterOid")
            return MockResponse(body = """{"data":{"updateRefs":{"clientMutationId":"${input.getString("clientMutationId")}"}}}""")
        }

        private fun awaitRelease() {
            blockEntered.countDown()
            check(blockRelease.await(10, TimeUnit.SECONDS)) { "Timed out waiting for cancellation test" }
        }

        override fun close() {
            blockRelease.countDown()
            server.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            assertTrue("v3 must never fall back to REST reference publication", requests.none { it.method == "PATCH" })
        }
    }
}
