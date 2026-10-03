package moe.ouom.neriplayer.api.sync.github

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.After
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Base64
import java.security.MessageDigest

class GitHubRepositorySyncTransportTest {
    private val ownedClients = ArrayList<OkHttpClient>()

    @After
    fun closeOwnedClients() {
        for (client in ownedClients) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    private val manifestPath = "neriplayer-sync-v3.manifest"
    private fun archiveObject(content: ByteArray): Pair<String, ByteArray> {
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        return "neriplayer-sync-v3-$hash.zst" to content
    }

    @Test
    fun `reads archive objects at the supplied immutable head without resolving another head`() = runBlocking {
        val script = ScriptedInterceptor(listOf(StubResponse.binary(200, byteArrayOf(1, 2))))

        assertArrayEquals(byteArrayOf(1, 2), newTransport(script)
            .getFileContentAtRef("owner", "repo", "object.zst", "snapshot-head").getOrThrow())

        assertEquals(1, script.requests.size)
        assertEquals("snapshot-head", script.requests.single().url.queryParameter("ref"))
    }

    @Test
    fun `commits multiple archive files with one manifest publication`() = runBlocking {
        val script = ScriptedInterceptor(listOf(
            StubResponse.json(200, """{"node_id":"repository-node"}"""),
            StubResponse.json(200, """{"tree":{"sha":"base-tree"}}"""),
            StubResponse.json(201, """{"sha":"object-blob"}"""),
            StubResponse.json(201, """{"sha":"manifest-blob"}"""),
            StubResponse.json(201, """{"sha":"updated-tree"}"""),
            StubResponse.json(201, """{"sha":"new-commit"}"""),
            StubResponse.json(200, """{"data":{"updateRefs":{"clientMutationId":"new-commit"}}}""")
        ))
        val files = sequenceOf(archiveObject(byteArrayOf(1)), manifestPath to byteArrayOf(2))

        val sha = newTransport(script).updateFilesContent("owner", "repo", files,
            GitHubSyncHead("main", "expected-head"), "sync").getOrThrow()

        assertEquals("new-commit", sha)
        val tree = JSONObject(String(script.requests[4].body, StandardCharsets.UTF_8))
        assertEquals(2, tree.getJSONArray("tree").length())
        val commit = JSONObject(String(script.requests[5].body, StandardCharsets.UTF_8))
        assertEquals("expected-head", commit.getJSONArray("parents").getString(0))
        assertTrue(script.requests.none { it.method == "PATCH" })
        assertEquals("/graphql", script.requests.last().url.encodedPath)
        val publication = JSONObject(String(script.requests.last().body, StandardCharsets.UTF_8)).getJSONObject("variables").getJSONObject("input")
        assertEquals("repository-node", publication.getString("repositoryId"))
        val update = publication.getJSONArray("refUpdates").getJSONObject(0)
        assertEquals("expected-head", update.getString("beforeOid"))
        assertEquals("new-commit", update.getString("afterOid"))
        assertFalse(update.getBoolean("force"))
    }

    @Test
    fun `large archives chain unpublished trees before one final commit`() = runBlocking {
        val requests = mutableListOf<Request>()
        var treeCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val path = request.url.encodedPath
            val response = when {
                path == "/repos/owner/repo" -> """{"node_id":"repository-node"}"""
                request.method == "GET" -> """{"tree":{"sha":"base-tree"}}"""
                path == "/graphql" -> """{"data":{"updateRefs":{"clientMutationId":"final-commit"}}}"""
                path.endsWith("/git/trees") -> """{"sha":"tree-${++treeCalls}"}"""
                path.endsWith("/git/commits") -> """{"sha":"final-commit"}"""
                else -> """{"sha":"blob"}"""
            }
            Response.Builder().request(request).code(200).message("test").protocol(Protocol.HTTP_1_1)
                .body(response.toResponseBody()).build()
        }.build()
        ownedClients += client
        val transport = GitHubRepositorySyncTransport(client, "test", "https://sync.test", "expired")
        val files = (0..1000).asSequence().map { archiveObject(it.toString().toByteArray()) }

        transport.updateFilesContent("owner", "repo", files, GitHubSyncHead("main", "head"), "sync").getOrThrow()

        val trees = requests.filter { it.url.encodedPath.endsWith("/git/trees") }
        assertEquals(2, trees.size)
        fun body(request: Request): JSONObject = Buffer().use { request.body!!.writeTo(it); JSONObject(it.readUtf8()) }
        assertEquals(1000, body(trees[0]).getJSONArray("tree").length())
        assertEquals("tree-1", body(trees[1]).getString("base_tree"))
        assertEquals(1, requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") })
        assertEquals(1, requests.count { it.url.encodedPath == "/graphql" })
        assertTrue(requests.none { it.method == "PATCH" })
    }

    @Test
    fun `failed archive object creation cannot publish a manifest`() = runBlocking {
        val script = ScriptedInterceptor(listOf(
            StubResponse.json(200, """{"node_id":"repository-node"}"""),
            StubResponse.json(200, """{"tree":{"sha":"base"}}"""),
            StubResponse.json(500, """{"message":"failed"}""")
        ))
        val result = newTransport(script).updateFilesContent("owner", "repo",
            sequenceOf(archiveObject(byteArrayOf(1))), GitHubSyncHead("main", "head"), "sync")
        assertTrue(result.isFailure)
        assertTrue(script.requests.none { it.method == "PATCH" || it.url.encodedPath.endsWith("/git/trees") })
    }

    @Test
    fun `atomic stale reference error is a retriable content conflict`() = runBlocking {
        val script = ScriptedInterceptor(listOf(
            StubResponse.json(200, """{"node_id":"repository-node"}"""),
            StubResponse.json(200, """{"tree":{"sha":"base"}}"""),
            StubResponse.json(201, """{"sha":"blob"}"""),
            StubResponse.json(201, """{"sha":"tree"}"""),
            StubResponse.json(201, """{"sha":"commit"}"""),
            StubResponse.json(200, """{"errors":[{"type":"STALE_DATA","message":"Reference does not match beforeOid"}]}""")
        ))
        val result = newTransport(script).updateFilesContent("owner", "repo",
            sequenceOf(manifestPath to byteArrayOf(1)), GitHubSyncHead("main", "head"), "sync")
        assertTrue(result.exceptionOrNull() is GitHubContentConflictException)
    }

    @Test
    fun `archive rejects non protocol paths and mismatched content addresses before creating blobs`() = runBlocking {
        val invalidPaths = listOf("", "backup.json", "../object.zst", "/object.zst",
            "neriplayer-sync-v3-${"0".repeat(64)}.zst")
        for (path in invalidPaths) {
            val script = ScriptedInterceptor(listOf(StubResponse.json(200, """{"node_id":"repository-node"}"""),
                StubResponse.json(200, """{"tree":{"sha":"base"}}""")))
            val result = newTransport(script).updateFilesContent("owner", "repo",
                sequenceOf(path to byteArrayOf(1)), GitHubSyncHead("main", "head"), "sync")
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(2, script.requests.size)
            assertTrue(script.requests.all { it.method == "GET" })
        }
    }

    @Test
    fun `archive object budget refuses empty and oversized content`() {
        assertTrue(runCatching { GitHubArchiveUploadValidation.validate(manifestPath, byteArrayOf()) }.isFailure)
        assertTrue(runCatching { GitHubArchiveUploadValidation.validate(manifestPath, ByteArray(2 * 1024 * 1024 + 1)) }.isFailure)
        val maximumObject = archiveObject(ByteArray(2 * 1024 * 1024))
        GitHubArchiveUploadValidation.validate(maximumObject.first, maximumObject.second)
    }

    @Test
    fun `empty archive cannot create a commit or move the branch`() = runBlocking {
        val script = ScriptedInterceptor(listOf(StubResponse.json(200, """{"node_id":"repository-node"}"""),
            StubResponse.json(200, """{"tree":{"sha":"base"}}""")))
        val result = newTransport(script).updateFilesContent("owner", "repo", emptySequence(),
            GitHubSyncHead("main", "head"), "sync")
        assertTrue(result.isFailure)
        assertEquals(2, script.requests.size)
    }

    @Test
    fun `tree and commit failures leave the expected head unpublished`() = runBlocking {
        for (failureAtCommit in listOf(false, true)) {
            val responses = mutableListOf(
                StubResponse.json(200, """{"node_id":"repository-node"}"""),
                StubResponse.json(200, """{"tree":{"sha":"base"}}"""),
                StubResponse.json(201, """{"sha":"blob"}""")
            )
            if (failureAtCommit) responses += StubResponse.json(201, """{"sha":"tree"}""")
            responses += StubResponse.json(500, """{"message":"failed"}""")
            val script = ScriptedInterceptor(responses)
            val result = newTransport(script).updateFilesContent("owner", "repo",
                sequenceOf(manifestPath to byteArrayOf(1)), GitHubSyncHead("main", "head"), "sync")
            assertTrue(result.isFailure)
            assertTrue(script.requests.none { it.method == "PATCH" })
        }
    }

    @Test
    fun `repository head resolution preserves branch and fails on incomplete metadata`() = runBlocking {
        val valid = ScriptedInterceptor(listOf(
            StubResponse.json(200, """{"default_branch":"sync"}"""),
            StubResponse.json(200, """{"object":{"sha":"snapshot"}}""")
        ))
        assertEquals(GitHubSyncHead("sync", "snapshot"), newTransport(valid).getRepositoryHead("owner", "repo").getOrThrow())
        assertEquals("/repos/owner/repo/git/ref/heads/sync", valid.requests.last().url.encodedPath)
        val missingBranch = ScriptedInterceptor(listOf(StubResponse.json(200, "{}")))
        assertTrue(newTransport(missingBranch).getRepositoryHead("owner", "repo").isFailure)
        val missingHead = ScriptedInterceptor(listOf(
            StubResponse.json(200, """{"default_branch":"main"}"""), StubResponse.json(200, """{"object":{}}""")
        ))
        assertTrue(newTransport(missingHead).getRepositoryHead("owner", "repo").isFailure)
    }

    @Test
    fun `reads repository raw backup file`() = runBlocking {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0xFF.toByte(), 0x42)
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"head-sha"}}"""),
                StubResponse.binary(200, payload)
            )
        )

        val result = newTransport(script)
            .getFileContent("owner", "repo", "backup-raw.bin", strict = true)
            .getOrThrow()

        assertArrayEquals(payload, result.first)
        assertEquals("head-sha", result.second)
        assertEquals(3, script.requests.size)
        assertEquals("/repos/owner/repo", script.requests[0].url.encodedPath)
        assertEquals(
            "/repos/owner/repo/contents/backup-raw.bin",
            script.requests[2].url.encodedPath
        )
        assertEquals("head-sha", script.requests[2].url.queryParameter("ref"))
        assertEquals("application/vnd.github.raw", script.requests[2].headers["Accept"])
    }

    @Test
    fun `reports missing raw backup without release fallback`() = runBlocking {
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"head-sha"}}"""),
                StubResponse.json(404, "{}")
            )
        )

        val result = newTransport(script)
            .getFileContent("owner", "repo", "backup-raw.bin", strict = true)

        assertTrue(result.exceptionOrNull() is GitHubFileNotFoundException)
        assertEquals(3, script.requests.size)
        assertEquals(
            "/repos/owner/repo/contents/backup-raw.bin",
            script.requests.last().url.encodedPath
        )
        assertTrue(script.requests.none { it.url.encodedPath.contains("/releases") })
    }

    @Test
    fun `uploads over one MiB as a repository binary blob`() = runBlocking {
        val payload = ByteArray(1024 * 1024 + 1) { index -> (index % 251).toByte() }.apply {
            this[0] = 0x1F.toByte()
            this[1] = 0x8B.toByte()
            this[2] = 0x08.toByte()
        }
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"parent-sha"}}"""),
                StubResponse.json(200, """{"tree":{"sha":"base-tree"}}"""),
                StubResponse.json(201, """{"sha":"binary-blob"}"""),
                StubResponse.json(201, """{"sha":"updated-tree"}"""),
                StubResponse.json(201, """{"sha":"binary-commit"}"""),
                StubResponse.json(200, """{"object":{"sha":"binary-commit"}}""")
            )
        )

        val newHead = newTransport(script)
            .updateFileContent(
                owner = "owner",
                repo = "repo",
                content = payload,
                remoteHead = "",
                path = "backup-raw.bin",
                message = "sync",
                branch = null
            )
            .getOrThrow()

        assertTrue(payload.size > 1024 * 1024)
        assertEquals("binary-commit", newHead)
        assertEquals(7, script.requests.size)

        val blobRequest = script.requests[3]
        assertEquals("POST", blobRequest.method)
        assertEquals("/repos/owner/repo/git/blobs", blobRequest.url.encodedPath)
        val blobBody = JSONObject(String(blobRequest.body, StandardCharsets.UTF_8))
        assertEquals("base64", blobBody.getString("encoding"))
        assertArrayEquals(payload, Base64.getDecoder().decode(blobBody.getString("content")))

        val treeRequest = script.requests[4]
        val treeBody = JSONObject(String(treeRequest.body, StandardCharsets.UTF_8))
        val treeEntry = treeBody.getJSONArray("tree").getJSONObject(0)
        assertEquals("backup-raw.bin", treeEntry.getString("path"))
        assertEquals("binary-blob", treeEntry.getString("sha"))
        assertEquals("blob", treeEntry.getString("type"))

        val commitRequest = script.requests[5]
        val commitBody = JSONObject(String(commitRequest.body, StandardCharsets.UTF_8))
        assertEquals("sync", commitBody.getString("message"))
        assertEquals("updated-tree", commitBody.getString("tree"))

        val refRequest = script.requests[6]
        assertEquals("PATCH", refRequest.method)
        val refBody = String(refRequest.body, StandardCharsets.UTF_8)
        assertTrue(refBody.contains("\"force\":false"))
        assertTrue(refBody.contains("\"sha\":\"binary-commit\""))
        assertTrue(script.requests.none { it.url.encodedPath.contains("/releases") })
    }

    @Test
    fun `does not move branch when binary blob creation fails`() = runBlocking {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x01)
        val script = ScriptedInterceptor(
            listOf(
                StubResponse.json(200, """{"default_branch":"main"}"""),
                StubResponse.json(200, """{"object":{"sha":"parent-sha"}}"""),
                StubResponse.json(200, """{"tree":{"sha":"base-tree"}}"""),
                StubResponse.json(500, """{"message":"temporary failure"}""")
            )
        )

        val result = newTransport(script).updateFileContent(
            owner = "owner",
            repo = "repo",
            content = payload,
            remoteHead = "",
            path = "backup-raw.bin",
            message = "sync",
            branch = null
        )

        assertTrue(result.isFailure)
        assertEquals(4, script.requests.size)
        assertTrue(script.requests.none { it.method == "PATCH" })
    }

    private fun newTransport(script: ScriptedInterceptor): GitHubRepositorySyncTransport {
        val client = OkHttpClient.Builder().addInterceptor(script).build()
        ownedClients += client
        return GitHubRepositorySyncTransport(
            client = client,
            token = "token",
            apiBase = "https://api.example.test",
            tokenExpiredMessage = "expired"
        )
    }

    private class CapturedRequest(
        val method: String,
        val url: HttpUrl,
        val headers: Headers,
        val body: ByteArray
    )

    private inner class ScriptedInterceptor(responses: List<StubResponse>) : Interceptor {
        private val responses = ArrayDeque(responses)
        val requests = mutableListOf<CapturedRequest>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += CapturedRequest(
                method = request.method,
                url = request.url,
                headers = request.headers,
                body = readBodyBytes(request)
            )
            check(responses.isNotEmpty()) { "Unexpected request: ${request.url}" }
            return responses.removeFirst().toResponse(request)
        }
    }

    private class StubResponse(
        val code: Int,
        val body: ByteArray,
        val contentType: String
    ) {
        fun toResponse(request: Request): Response {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }

        companion object {
            fun json(code: Int, body: String): StubResponse = StubResponse(
                code = code,
                body = body.toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json; charset=utf-8"
            )

            fun binary(code: Int, body: ByteArray): StubResponse = StubResponse(
                code = code,
                body = body,
                contentType = "application/octet-stream"
            )
        }
    }

    private fun readBodyBytes(request: Request): ByteArray {
        val requestBody = request.body ?: return ByteArray(0)
        return Buffer().use { buffer ->
            requestBody.writeTo(buffer)
            buffer.readByteArray()
        }
    }
}
