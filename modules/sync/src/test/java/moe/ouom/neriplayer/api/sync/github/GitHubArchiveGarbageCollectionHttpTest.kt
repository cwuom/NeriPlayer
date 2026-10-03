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

    @Test
    fun `publication removes only unreferenced owned blobs while old head and unrelated files remain readable`() = runBlocking {
        Fixture().use { fixture ->
            val content = "new data".toByteArray()
            val added = "neriplayer-sync-v4-${GitHubSyncCheckpoint.contentKey(content)}.zst"
            val head = fixture.transport.updateFilesContent("owner", "repo", sequenceOf(added to content, manifest to byteArrayOf(7)),
                GitHubSyncHead("main", "head"), "sync", setOf(retained, added)).getOrThrow()
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
            val publication = JSONObject(fixture.requests.last().body!!.utf8()).getJSONObject("variables").getJSONObject("input")
                .getJSONArray("refUpdates").getJSONObject(0)
            assertEquals("head", publication.getString("beforeOid"))
            assertFalse(publication.getBoolean("force"))
            assertEquals(1, fixture.requests.count { it.method == "POST" && it.url.encodedPath.endsWith("/git/commits") })
        }
    }

    @Test
    fun `concurrent branch movement rejects the entire new archive and cleanup commit`() = runBlocking {
        Fixture().use { fixture ->
            fixture.advanceBeforePublication = true
            val result = fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                GitHubSyncHead("main", "head"), "sync", setOf(retained))
            assertTrue(result.exceptionOrNull() is GitHubContentConflictException)
            assertEquals("other-head", fixture.head)
            assertEquals(fixture.trees.getValue("base"), fixture.trees.getValue(fixture.commits.getValue(fixture.head)))
            assertTrue(fixture.requests.none { it.method == "PATCH" })
        }
    }

    @Test
    fun `more than one deletion batch creates unpublished trees and one atomic final commit`() = runBlocking {
        Fixture().use { fixture ->
            repeat(1_005) { index -> fixture.trees.getValue("base")["neriplayer-sync-v3-${index.toString(16).padStart(64, '0')}.zst"] = "e".repeat(40) }
            val result = fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                GitHubSyncHead("main", "head"), "sync", setOf(retained)).getOrThrow()
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
    fun `cancellation while reading the old tree prevents staging or publishing any deletion`() = runBlocking {
        Fixture().use { fixture ->
            fixture.blockListing = true
            val job = async(Dispatchers.Default) {
                fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                    GitHubSyncHead("main", "head"), "sync", setOf(retained))
            }
            try {
                assertTrue(fixture.listingEntered.await(10, TimeUnit.SECONDS))
                job.cancel()
                fixture.listingRelease.countDown()
                job.join()
                assertTrue(job.isCancelled)
                assertEquals("head", fixture.head)
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
                    GitHubSyncHead("main", "head"), "sync", setOf(retained)).isFailure)
                assertEquals("head", fixture.head)
                assertTrue(fixture.requests.all { it.method == "GET" })
            }
        }
    }

    @Test
    fun `invalid retained addresses or a missing retained object cannot publish an incomplete archive`() = runBlocking {
        for (paths in listOf(setOf("notes.txt"), setOf("folder/$retained"), setOf("neriplayer-sync-v4-${"f".repeat(64)}.zst"))) {
            Fixture().use { fixture ->
                assertTrue(fixture.transport.updateFilesContent("owner", "repo", sequenceOf(manifest to byteArrayOf(7)),
                    GitHubSyncHead("main", "head"), "sync", paths).isFailure)
                assertEquals("head", fixture.head)
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
                    GitHubSyncHead("main", "head"), "sync", setOf(retained)).isFailure)
                assertEquals("head", fixture.head)
                assertTrue(fixture.requests.none { it.url.encodedPath == "/graphql" })
            }
        }
        Fixture().use { fixture ->
            assertTrue(fixture.transport.updateFilesContent("owner", "repo", sequenceOf(objectPath to bytes),
                GitHubSyncHead("main", "head"), "sync", setOf(retained, objectPath)).isFailure)
            assertEquals("head", fixture.head)
            assertTrue(fixture.requests.none { it.url.encodedPath == "/graphql" })
        }
        Fixture().use { fixture ->
            assertTrue(fixture.transport.updateFilesContent("owner", "repo", emptySequence(),
                GitHubSyncHead("main", "head"), "sync", emptySet()).isFailure)
            assertEquals("head", fixture.head)
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
        val commits = mutableMapOf("head" to "base", "other-head" to "base")
        var head = "head"
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
                        path == "/repos/owner/repo/git/commits/head" -> json("""{"tree":{"sha":"base"}}""")
                        path == "/repos/owner/repo/git/trees/base" -> listing()
                        path.endsWith("/git/blobs") -> {
                            val bytes = Base64.getDecoder().decode(JSONObject(request.body!!.utf8()).getString("content"))
                            json(JSONObject().put("sha", GitHubSyncCheckpoint.gitBlobSha(bytes)).toString(), 201)
                        }
                        path.endsWith("/git/trees") && request.method == "POST" -> createTree(request)
                        path.endsWith("/git/commits") && request.method == "POST" -> {
                            val value = JSONObject(request.body!!.utf8())
                            assertEquals("head", value.getJSONArray("parents").getString(0))
                            val commit = "commit-${++sequence}"
                            commits[commit] = value.getString("tree")
                            json(JSONObject().put("sha", commit).toString(), 201)
                        }
                        path == "/graphql" -> publish(request)
                        else -> MockResponse(code = 404)
                    }
                }
            }
            server.start()
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
            if (advanceBeforePublication) head = "other-head"
            val update = JSONObject(request.body!!.utf8()).getJSONObject("variables").getJSONObject("input")
                .getJSONArray("refUpdates").getJSONObject(0)
            if (update.getString("beforeOid") != head) return json("""{"errors":[{"type":"STALE_DATA","message":"ref moved"}]}""")
            head = update.getString("afterOid")
            return json(JSONObject().put("data", JSONObject().put("updateRefs", JSONObject().put("clientMutationId", head))).toString())
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
