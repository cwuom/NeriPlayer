package moe.ouom.neriplayer.api.sync.github

import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.api.sync.http.startOnLoopback
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubArchiveProofHttpTest {
    @Test fun `branch metadata supplies a fixed head and tree with an encoded branch name`() = runBlocking {
        withServer { server, transport ->
            val branch = "feature/歌词"
            server.enqueue(MockResponse(body = """{"default_branch":"$branch"}"""))
            server.enqueue(MockResponse(body = branchBody(branch)))
            val snapshot = transport.getArchiveHead("owner", "repo").getOrThrow()
            assertEquals(GitHubSyncHead(branch, HEAD), snapshot.head)
            assertEquals(TREE, snapshot.treeSha)
            assertEquals("/repos/owner/repo", server.takeRequest().url.encodedPath)
            val request = server.takeRequest()
            assertEquals(listOf("repos", "owner", "repo", "branches", branch), request.url.pathSegments)
            assertTrue(request.url.encodedPath.contains("feature%2F"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `each archive read resolves the current default branch`() = runBlocking {
        withServer { server, transport ->
            for (branch in listOf("main", "master")) {
                server.enqueue(MockResponse(body = """{"default_branch":"$branch"}"""))
                server.enqueue(MockResponse(body = branchBody(branch)))
                assertEquals(branch, transport.getArchiveHead("owner", "repo").getOrThrow().head.branch)
                assertEquals("/repos/owner/repo", server.takeRequest().url.encodedPath)
                assertEquals("/repos/owner/repo/branches/$branch", server.takeRequest().url.encodedPath)
            }
        }
    }

    @Test fun `invalid repository and branch identifiers never produce trusted archive heads`() = runBlocking {
        withServer { server, transport ->
            for (repository in listOf("{}", "{\"default_branch\":7}", "{\"default_branch\":\" \"}")) {
                server.enqueue(MockResponse(body = repository))
                assertTrue(transport.getArchiveHead("owner", "repo").exceptionOrNull() is IOException)
                assertEquals("/repos/owner/repo", server.takeRequest().url.encodedPath)
            }
            val invalid = listOf("{}", branchBody("other"), branchBody(head = "short"),
                branchBody(head = "A".repeat(40)), branchBody(tree = "short"),
                branchBody().replace("\"sha\":\"$TREE\"", "\"sha\":7"),
                branchBody().replace("\"commit\":{\"tree\"", "\"details\":{\"tree\""))
            for (body in invalid) {
                server.enqueue(MockResponse(body = """{"default_branch":"main"}"""))
                server.enqueue(MockResponse(body = body))
                assertTrue(transport.getArchiveHead("owner", "repo").exceptionOrNull() is IOException)
                server.takeRequest()
                server.takeRequest()
            }
            assertEquals(3 + invalid.size * 2, server.requestCount)
        }
    }

    @Test fun `a scoped head reads exactly one complete root proof`() = runBlocking {
        withServer { server, transport ->
            server.enqueue(MockResponse(body = listing()))
            val head = head(server.url("/").toString())
            val proof = transport.getArchiveProof("owner", "repo", head).getOrThrow()
            assertEquals(mapOf(OBJECT to OID), proof.objectIds)
            assertEquals("/repos/owner/repo/git/trees/$TREE", server.takeRequest().url.encodedPath)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `incomplete or ambiguous scoped root proofs fail closed`() = runBlocking {
        withServer { server, transport ->
            val bodies = listOf(listing().replace("false", "true"), listing().replace(TREE, HEAD),
                """{"sha":"$TREE","tree":[],"truncated":"false"}""",
                """{"sha":"$TREE","tree":[$ENTRY,$ENTRY],"truncated":false}""")
            for (body in bodies) {
                server.enqueue(MockResponse(body = body))
                assertTrue(transport.getArchiveProof("owner", "repo", head(server.url("/").toString())).isFailure)
            }
            assertEquals(bodies.size, server.requestCount)
        }
    }

    @Test fun `heads from other servers paths or repositories are rejected before IO`() = runBlocking {
        withServer { server, transport ->
            val base = server.url("/").toString()
            val wrong = listOf(head(server.url("/enterprise/api/v3/").toString()),
                head("https://different.test"), head(base, owner = "different"), head(base, repo = "other"))
            for (snapshot in wrong) {
                assertTrue(transport.getArchiveProof("owner", "repo", snapshot).exceptionOrNull() is IOException)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `proof owns an immutable copy and binds the exact branch and head`() {
        val snapshot = head("https://API.GITHUB.com/")
        val mutable = linkedMapOf(OBJECT to OID)
        val proof = GitHubArchiveProof(snapshot, mutable)
        mutable.clear()
        assertEquals(mapOf(OBJECT to OID), proof.objectIds)
        assertThrows(UnsupportedOperationException::class.java) { (proof.objectIds as MutableMap).clear() }
        assertTrue(proof.matches("https://api.github.com", "owner", "repo", snapshot.head))
        assertFalse(proof.matches("https://api.github.com/v3", "owner", "repo", snapshot.head))
        assertFalse(proof.matches("https://api.github.com", "owner", "repo", GitHubSyncHead("other", HEAD)))
        assertFalse(proof.matches("https://api.github.com", "owner", "repo", GitHubSyncHead("main", OID)))
    }

    @Test fun `publication ignores proofs with a different origin repository branch or head`() = runBlocking {
        withServer { server, transport ->
            val base = server.url("/").toString()
            val snapshots = listOf(head(server.url("/v3").toString()), head(base, owner = "other"),
                head(base, repo = "other"), head(base, branch = "other"), head(base, sha = OID))
            for (snapshot in snapshots) {
                server.enqueue(MockResponse(body = """{"tree":{"sha":"$TREE"}}"""))
                server.enqueue(MockResponse(body = listing()))
                server.enqueue(MockResponse(body = """{"errors":[{"type":"STALE_DATA","message":"moved"}]}"""))
                val proof = GitHubArchiveProof(snapshot, mapOf(OBSOLETE to OID))
                val result = transport.updateFilesContent("owner", "repo",
                    sequenceOf(MANIFEST to "manifest".toByteArray()), GitHubSyncHead("main", HEAD), "sync",
                    setOf(OBJECT), proof)
                assertTrue(result.exceptionOrNull() is GitHubContentConflictException)
                assertEquals("/repos/owner/repo/git/commits/$HEAD", server.takeRequest().url.encodedPath)
                assertEquals("/repos/owner/repo/git/trees/$TREE", server.takeRequest().url.encodedPath)
                val mutation = server.takeRequest()
                assertEquals("/graphql", mutation.url.encodedPath)
                val input = JSONObject(mutation.body!!.utf8()).getJSONObject("variables").getJSONObject("input")
                assertEquals(HEAD, input.getString("expectedHeadOid"))
                assertEquals(0, input.getJSONObject("fileChanges").getJSONArray("deletions").length())
            }
            assertEquals(snapshots.size * 3, server.requestCount)
        }
    }

    @Test fun `head HTTP failures preserve authentication rate and status classification`() = runBlocking {
        withServer { server, transport ->
            for (atBranch in listOf(false, true)) for (status in listOf(401, 403, 404, 429, 500)) {
                if (atBranch) server.enqueue(MockResponse(body = """{"default_branch":"main"}"""))
                server.enqueue(MockResponse.Builder().code(status).body("failure").build())
                val error = transport.getArchiveHead("owner", "repo").exceptionOrNull()
                when (status) {
                    401 -> assertTrue(error is TokenExpiredException)
                    429 -> assertTrue(error is GitHubRateLimitException)
                    else -> assertEquals(status, (error as GitHubApiException).statusCode)
                }
            }
        }
    }

    private suspend fun withServer(block: suspend (MockWebServer, GitHubRepositorySyncTransport) -> Unit) {
        val client = OkHttpClient()
        try {
            MockWebServer().use { server ->
                server.startOnLoopback()
                block(server, GitHubRepositorySyncTransport(client, "test-token", server.url("/").toString().trimEnd('/'), "expired"))
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun head(base: String, owner: String = "owner", repo: String = "repo", branch: String = "main", sha: String = HEAD) =
        GitHubArchiveHead(base, owner, repo, GitHubSyncHead(branch, sha), TREE)

    private fun branchBody(branch: String = "main", head: String = HEAD, tree: String = TREE) =
        """{"name":"$branch","commit":{"sha":"$head","commit":{"tree":{"sha":"$tree"}}}}"""

    private fun listing() = """{"sha":"$TREE","tree":[$ENTRY],"truncated":false}"""

    private companion object {
        val HEAD = "1".repeat(40)
        val TREE = "2".repeat(40)
        val OID = "3".repeat(40)
        val OBJECT = "neriplayer-sync-v4-${"a".repeat(64)}.zst"
        val OBSOLETE = "neriplayer-sync-v4-${"b".repeat(64)}.zst"
        val ENTRY = """{"path":"$OBJECT","mode":"100644","type":"blob","sha":"$OID"}"""
        const val MANIFEST = "neriplayer-sync-v3.manifest"
    }
}
