package moe.ouom.neriplayer.api.sync.github

import java.io.Closeable
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import moe.ouom.neriplayer.api.sync.http.startOnLoopback
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubArchiveGarbageCollectionHttpTest {
    private val manifest = "neriplayer-sync-v3.manifest"
    private val orphanV3 = "neriplayer-sync-v3-${"a".repeat(64)}.zst"
    private val orphanV4 = "neriplayer-sync-v4-${"b".repeat(64)}.zst"
    private val retained = "neriplayer-sync-v4-${"c".repeat(64)}.zst"
    private val originalHead = "1".repeat(40)
    private val movedHead = "2".repeat(40)

    @Test
    fun `publication removes only unreferenced owned blobs while old head and unrelated files remain readable`() = runBlocking {
        Fixture().use { fixture ->
            val content = "new data".toByteArray()
            val added = "neriplayer-sync-v4-${GitHubSyncCheckpoint.contentKey(content)}.zst"
            val head = fixture.transport.updateFilesContent("owner", "repo", sequenceOf(added to content, manifest to byteArrayOf(7)),
                GitHubSyncHead("main", originalHead), "sync", setOf(retained, added)).getOrThrow()
            val current = fixture.trees.getValue(fixture.commits.getValue(head))
            assertFalse(orphanV3 in current)
            assertFalse(orphanV4 in current)
            assertTrue(retained in current)
            assertTrue(added in current)
            for (path in listOf("notes.txt", "folder/$orphanV3", "neriplayer-sync-v4-${"d".repeat(64)}.zst")) assertTrue(path in current)
            assertTrue(orphanV3 in fixture.trees.getValue("base"))
            assertTrue(orphanV4 in fixture.trees.getValue("base"))
            val listingRequest = fixture.requests.single { it.url.encodedPath.endsWith("/git/trees/base") }
            assertEquals(null, listingRequest.url.queryParameter("recursive"))
            val publication = JSONObject(fixture.requests.single { it.url.encodedPath == "/graphql" }.body!!.utf8())
                .getJSONObject("variables").getJSONObject("input")
            assertEquals(originalHead, publication.getString("expectedHeadOid"))
            assertEquals("main", publication.getJSONObject("branch").getString("branchName"))
            assertEquals("owner/repo", publication.getJSONObject("branch").getString("repositoryNameWithOwner"))
            val changes = publication.getJSONObject("fileChanges")
            val additions = changes.getJSONArray("additions")
            assertEquals(2, additions.length())
            assertEquals(setOf(added, manifest), (0 until additions.length()).map { additions.getJSONObject(it).getString("path") }.toSet())
            for (index in 0 until additions.length()) {
                val addition = additions.getJSONObject(index)
                assertEquals(if (addition.getString("path") == added) "bmV3IGRhdGE=" else "Bw==", addition.getString("contents"))
            }
            val deletions = changes.getJSONArray("deletions")
            assertEquals(2, deletions.length())
            assertEquals(setOf(orphanV3, orphanV4), (0 until deletions.length()).map { deletions.getJSONObject(it).getString("path") }.toSet())
            assertEquals(3, fixture.commits.size)
            assertTrue(fixture.requests.none { it.method == "POST" && it.url.encodedPath != "/graphql" })
        }
    }

    @Test
    fun `concurrent branch movement rejects the entire new archive and cleanup commit`() = runBlocking {
        Fixture().use { fixture ->
            fixture.advanceBeforePublication = true
            val result = fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                GitHubSyncHead("main", originalHead), "sync", setOf(retained))
            assertTrue(result.exceptionOrNull() is GitHubContentConflictException)
            assertEquals(movedHead, fixture.head)
            assertEquals(fixture.trees.getValue("base"), fixture.trees.getValue(fixture.commits.getValue(fixture.head)))
            assertEquals(2, fixture.commits.size)
            assertTrue(fixture.requests.none { it.method == "PATCH" })
        }
    }

    @Test
    fun `more than one deletion batch creates unpublished trees and one atomic final commit`() = runBlocking {
        Fixture().use { fixture ->
            repeat(1_005) { index -> fixture.trees.getValue("base")["neriplayer-sync-v3-${index.toString(16).padStart(64, '0')}.zst"] = "e".repeat(40) }
            val result = fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                GitHubSyncHead("main", originalHead), "sync", setOf(retained)).getOrThrow()
            val current = fixture.trees.getValue(fixture.commits.getValue(result))
            assertEquals(setOf(retained, manifest, "notes.txt", "folder/$orphanV3", "neriplayer-sync-v4-${"d".repeat(64)}.zst"), current.keys)
            val trees = fixture.requests.filter { it.method == "POST" && it.url.encodedPath.endsWith("/git/trees") }
            assertEquals(2, trees.size)
            assertTrue(trees.all { JSONObject(it.body!!.utf8()).getJSONArray("tree").length() <= 1_000 })
            assertEquals(1, fixture.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") })
            assertEquals(1, fixture.requests.count { it.url.encodedPath == "/graphql" })
        }
    }

    @Test
    fun `too many compact file changes fall back to one atomic REST commit with cleanup`() = runBlocking {
        Fixture().use { fixture ->
            val files = (0 until 65).map { index ->
                val bytes = "new object $index".toByteArray()
                "neriplayer-sync-v4-${GitHubSyncCheckpoint.contentKey(bytes)}.zst" to bytes
            }
            val retainedPaths = files.map { it.first }.toSet() + retained
            val result = fixture.transport.updateFilesContent("owner", "repo", (files + (manifest to byteArrayOf(7))).asSequence(),
                GitHubSyncHead("main", originalHead), "sync", retainedPaths).getOrThrow()

            val current = fixture.trees.getValue(fixture.commits.getValue(result))
            assertEquals((fixture.trees.getValue("base").keys - setOf(orphanV3, orphanV4)) + retainedPaths, current.keys)
            assertTrue(orphanV3 in fixture.trees.getValue("base"))
            assertTrue(orphanV4 in fixture.trees.getValue("base"))
            assertClassicPublication(fixture)
            assertEquals(1, fixture.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/trees") })
            assertEquals(66, fixture.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/blobs") })
        }
    }

    @Test
    fun `compact JSON byte budget falls back to one atomic REST commit with cleanup`() = runBlocking {
        Fixture().use { fixture ->
            val files = listOf(ByteArray(1_572_863) { 7 }, ByteArray(1_572_863) { 9 }).map { bytes ->
                "neriplayer-sync-v4-${GitHubSyncCheckpoint.contentKey(bytes)}.zst" to bytes
            }
            val retainedPaths = files.map { it.first }.toSet() + retained
            val result = fixture.transport.updateFilesContent("owner", "repo", (files + (manifest to byteArrayOf(7))).asSequence(),
                GitHubSyncHead("main", originalHead), "sync", retainedPaths).getOrThrow()

            val current = fixture.trees.getValue(fixture.commits.getValue(result))
            assertEquals((fixture.trees.getValue("base").keys - setOf(orphanV3, orphanV4)) + retainedPaths, current.keys)
            assertTrue(orphanV3 in fixture.trees.getValue("base"))
            assertTrue(orphanV4 in fixture.trees.getValue("base"))
            assertClassicPublication(fixture)
            assertEquals(1, fixture.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/trees") })
            assertEquals(3, fixture.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/blobs") })
        }
    }

    private fun assertClassicPublication(fixture: Fixture) {
        val input = JSONObject(fixture.requests.single { it.url.encodedPath == "/graphql" }.body!!.utf8())
            .getJSONObject("variables").getJSONObject("input")
        val update = input.getJSONArray("refUpdates").getJSONObject(0)
        assertEquals(originalHead, update.getString("beforeOid"))
        assertFalse(update.getBoolean("force"))
        assertEquals(fixture.head, update.getString("afterOid"))
        assertEquals(1, fixture.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") })
        assertTrue(fixture.requests.none { it.method == "PATCH" })
    }

    @Test
    fun `cancellation while reading the old tree prevents staging or publishing any deletion`() = runBlocking {
        Fixture().use { fixture ->
            fixture.blockListing = true
            val job = async(Dispatchers.Default) {
                fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                    GitHubSyncHead("main", originalHead), "sync", setOf(retained))
            }
            try {
                assertTrue(fixture.listingEntered.await(10, TimeUnit.SECONDS))
                job.cancel()
                fixture.listingRelease.countDown()
                job.join()
                assertTrue(job.isCancelled)
                assertEquals(originalHead, fixture.head)
                assertTrue(fixture.requests.all { it.method == "GET" })
                assertTrue(fixture.calls.single { it.request().url.encodedPath.endsWith("/git/trees/base") }.isCanceled())
            } finally {
                fixture.listingRelease.countDown()
                job.cancel()
                job.join()
            }
        }
    }

    @Test
    fun `tree listing failures cannot upload objects create a commit or publish cleanup`() = runBlocking {
        for (invalid in listOf("http", "redirect", "truncated", "incomplete", "trailing")) {
            Fixture().use { fixture ->
                fixture.invalidListing = invalid
                assertTrue(fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                    GitHubSyncHead("main", originalHead), "sync", setOf(retained)).isFailure)
                assertEquals(originalHead, fixture.head)
                assertTrue(fixture.requests.all { it.method == "GET" })
            }
        }
    }

    @Test
    fun `invalid retained addresses or a missing retained object cannot publish an incomplete archive`() = runBlocking {
        for (paths in listOf(setOf("notes.txt"), setOf("folder/$retained"), setOf("neriplayer-sync-v4-${"f".repeat(64)}.zst"))) {
            Fixture().use { fixture ->
                assertTrue(fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                    GitHubSyncHead("main", originalHead), "sync", paths).isFailure)
                assertEquals(originalHead, fixture.head)
                assertTrue(fixture.requests.none { it.url.encodedPath.endsWith("/git/commits") && it.method == "POST" || it.url.encodedPath == "/graphql" })
            }
        }
    }

    @Test
    fun `cleanup requires a manifest and newly uploaded objects must belong to its supplied closure`() = runBlocking {
        val bytes = "unexpected".toByteArray()
        val objectPath = "neriplayer-sync-v4-${GitHubSyncCheckpoint.contentKey(bytes)}.zst"
        for (files in listOf(sequenceOf(objectPath to bytes), sequenceOf(objectPath to bytes, manifest to byteArrayOf(7)))) {
            Fixture().use { fixture ->
                assertTrue(fixture.transport.updateFilesContent("owner", "repo", files,
                    GitHubSyncHead("main", originalHead), "sync", setOf(retained)).isFailure)
                assertEquals(originalHead, fixture.head)
                assertTrue(fixture.requests.none { it.url.encodedPath == "/graphql" })
            }
        }
        Fixture().use { fixture ->
            assertTrue(fixture.transport.updateFilesContent("owner", "repo", sequenceOf(objectPath to bytes),
                GitHubSyncHead("main", originalHead), "sync", setOf(retained, objectPath)).isFailure)
            assertEquals(originalHead, fixture.head)
            assertTrue(fixture.requests.none { it.url.encodedPath == "/graphql" })
        }
        Fixture().use { fixture ->
            assertTrue(fixture.transport.updateFilesContent("owner", "repo", emptySequence(),
                GitHubSyncHead("main", originalHead), "sync", emptySet()).isFailure)
            assertEquals(originalHead, fixture.head)
        }
    }

    private inner class Fixture : Closeable {
        private val server = MockWebServer()
        val calls = CopyOnWriteArrayList<Call>()
        private val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
        }).build()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val trees = mutableMapOf("base" to linkedMapOf(orphanV3 to "e".repeat(40), orphanV4 to "f".repeat(40), retained to "c".repeat(40),
            manifest to "old-manifest", "notes.txt" to "notes", "folder/$orphanV3" to "nested",
            "neriplayer-sync-v4-${"d".repeat(64)}.zst" to "symlink"))
        val commits = mutableMapOf(originalHead to "base", movedHead to "base")
        var head = originalHead
        var advanceBeforePublication = false
        var invalidListing: String? = null
        var blockListing = false
        val listingEntered = CountDownLatch(1)
        val listingRelease = CountDownLatch(1)
        private var sequence = 0
        val transport: GitHubRepositorySyncTransport

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.url.encodedPath
                    return when {
                        path == "/repos/owner/repo" -> json("""{"node_id":"repository-node"}""")
                        path == "/repos/owner/repo/git/commits/$originalHead" -> json("""{"tree":{"sha":"base"}}""")
                        path == "/repos/owner/repo/git/trees/base" -> listing()
                        path.endsWith("/git/blobs") -> {
                            val bytes = Base64.getDecoder().decode(JSONObject(request.body!!.utf8()).getString("content"))
                            json(JSONObject().put("sha", GitHubSyncCheckpoint.gitBlobSha(bytes)).toString(), 201)
                        }
                        path.endsWith("/git/trees") && request.method == "POST" -> createTree(request)
                        path.endsWith("/git/commits") && request.method == "POST" -> {
                            val value = JSONObject(request.body!!.utf8())
                            assertEquals(originalHead, value.getJSONArray("parents").getString(0))
                            val commit = "commit-${++sequence}"
                            commits[commit] = value.getString("tree")
                            json(JSONObject().put("sha", commit).toString(), 201)
                        }
                        path == "/graphql" -> publish(request)
                        else -> MockResponse(code = 404)
                    }
                }
            }
            server.startOnLoopback()
            transport = GitHubRepositorySyncTransport(client, "test", server.url("/").toString().trimEnd('/'), "expired")
        }

        private fun listing(): MockResponse {
            if (blockListing) {
                listingEntered.countDown()
                check(listingRelease.await(10, TimeUnit.SECONDS))
            }
            if (invalidListing == "http") return json("""{"message":"read failed"}""", 500)
            if (invalidListing == "redirect") return MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", "/other-tree"))
            val entries = JSONArray()
            trees.getValue("base").forEach { (path, sha) -> entries.put(JSONObject().put("path", path).put("sha", sha)
                .put("type", "blob").put("mode", if (sha == "symlink") "120000" else "100644")) }
            val value = JSONObject().put("sha", "base").put("tree", entries).put("truncated", invalidListing == "truncated")
            var body = value.toString()
            if (invalidListing == "incomplete") body = body.dropLast(1)
            if (invalidListing == "trailing") body += "{}"
            return json(body)
        }

        private fun createTree(request: RecordedRequest): MockResponse {
            val body = JSONObject(request.body!!.utf8())
            val next = LinkedHashMap(trees.getValue(body.getString("base_tree")))
            val entries = body.getJSONArray("tree")
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val path = entry.getString("path")
                if (entry.isNull("sha")) next.remove(path) else next[path] = entry.getString("sha")
            }
            val sha = "tree-${++sequence}"
            trees[sha] = next
            return json(JSONObject().put("sha", sha).toString(), 201)
        }

        private fun publish(request: RecordedRequest): MockResponse {
            if (advanceBeforePublication) head = movedHead
            val body = JSONObject(request.body!!.utf8())
            val input = body.getJSONObject("variables").getJSONObject("input")
            if (body.getString("query").contains("createCommitOnBranch")) return createArchiveCommit(input)
            val update = input.getJSONArray("refUpdates").getJSONObject(0)
            if (update.getString("beforeOid") != head) return json("""{"errors":[{"type":"STALE_DATA","message":"ref moved"}]}""")
            head = update.getString("afterOid")
            return json(JSONObject().put("data", JSONObject().put("updateRefs", JSONObject().put("clientMutationId", head))).toString())
        }

        private fun createArchiveCommit(input: JSONObject): MockResponse {
            val expected = input.getString("expectedHeadOid")
            if (expected != head) return json("""{"errors":[{"type":"STALE_DATA","message":"ref moved"}]}""")
            val branch = input.getJSONObject("branch")
            assertEquals("main", branch.getString("branchName"))
            assertEquals("owner/repo", branch.getString("repositoryNameWithOwner"))
            val next = LinkedHashMap(trees.getValue(commits.getValue(expected)))
            val changes = input.getJSONObject("fileChanges")
            val additions = changes.getJSONArray("additions")
            for (index in 0 until additions.length()) {
                val addition = additions.getJSONObject(index)
                val encoded = addition.getString("contents")
                val content = Base64.getDecoder().decode(encoded)
                assertEquals(Base64.getEncoder().encodeToString(content), encoded)
                next[addition.getString("path")] = GitHubSyncCheckpoint.gitBlobSha(content)
            }
            val deletions = changes.getJSONArray("deletions")
            for (index in 0 until deletions.length()) next.remove(deletions.getJSONObject(index).getString("path"))
            val commit = (++sequence).toString(16).padStart(40, '0')
            val tree = "tree-${++sequence}"
            trees[tree] = next
            commits[commit] = tree
            head = commit
            val value = JSONObject().put("clientMutationId", input.getString("clientMutationId"))
                .put("commit", JSONObject().put("oid", commit)
                    .put("parents", JSONObject().put("nodes", JSONArray().put(JSONObject().put("oid", expected)))))
                .put("ref", JSONObject().put("name", "main").put("prefix", "refs/heads/")
                    .put("repository", JSONObject().put("nameWithOwner", "owner/repo"))
                    .put("target", JSONObject().put("oid", commit)))
            return json(JSONObject().put("data", JSONObject().put("createCommitOnBranch", value)).toString())
        }

        private fun json(body: String, code: Int = 200) = MockResponse(code = code, body = body)

        override fun close() {
            listingRelease.countDown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }
}
