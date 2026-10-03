package moe.ouom.neriplayer.api.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.GitHubSyncHead
import moe.ouom.neriplayer.api.sync.github.GitHubTreeResponseReader
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncTransportAndroidTest {
    @Test
    fun packagedGitHubClientPinsRawReadsToTheFetchedHead() = runBlocking {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val bytes = byteArrayOf(0, 1, 2, -1)
        LoopbackServer { request ->
            when (request.target.substringBefore('?')) {
                "/repos/owner/repo" -> HttpReply(body = """{"default_branch":"main"}""".toByteArray())
                "/repos/owner/repo/git/ref/heads/main" -> HttpReply(body = """{"object":{"sha":"$sha"}}""".toByteArray())
                "/repos/owner/repo/contents/neriplayer-sync-v3.manifest" -> HttpReply(body = bytes)
                else -> HttpReply(status = 404)
            }
        }.use { server ->
            withClient { client ->
                val github = GitHubApiClient("instrumented-token", client, "expired", server.baseUrl)
                val head = github.getRepositoryHead("owner", "repo").getOrThrow()
                assertEquals(GitHubSyncHead("main", sha), head)
                assertArrayEquals(bytes, github.getFileContentAtRef("owner", "repo", "neriplayer-sync-v3.manifest", head.sha).getOrThrow())
                assertEquals(3, server.requests.size)
                val raw = server.requests.last()
                assertEquals("/repos/owner/repo/contents/neriplayer-sync-v3.manifest?ref=$sha", raw.target)
                assertEquals("application/vnd.github.raw", raw.headers["accept"])
                assertProductionClientVersion(server.requests)
            }
        }
    }

    @Test
    fun packagedGitHubClientBoundsFixedAndChunkedV3Downloads() = runBlocking {
        val bytes = ByteArray(2 * 1024 * 1024 + 1) { 1 }
        val objectName = "neriplayer-sync-v3-${"f".repeat(64)}.zst"
        LoopbackServer { request -> HttpReply(body = bytes, chunked = request.target.contains(objectName)) }.use { server ->
            withClient { client ->
                val github = GitHubApiClient("instrumented-token", client, "expired", server.baseUrl)
                for (name in listOf("neriplayer-sync-v3.manifest", objectName)) {
                    val error = github.getFileContentAtRef("owner", "repo", name, "fixed-head").exceptionOrNull()
                    assertTrue(error is IOException)
                    assertTrue(error!!.message!!.contains("too large"))
                }
                assertEquals(bytes.size, github.getFileContentAtRef("owner", "repo", "backup.bin", "fixed-head").getOrThrow().size)
                assertProductionClientVersion(server.requests)
            }
        }
    }

    @Test
    fun packagedGitHubClientStopsLargeTreeResponsesAfterTheirSha() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val largeTree = ("{\"sha\":\"$sha\",\"tree\":[\"" + "x".repeat(13 * 1024 * 1024) + "\"]}").toByteArray()
        val lateSha = ("{\"padding\":\"" + "x".repeat(65 * 1024) + "\",\"sha\":\"$sha\"}").toByteArray()
        LoopbackServer { request ->
            HttpReply(body = if (request.target.startsWith("/late")) lateSha else largeTree,
                chunked = request.target.endsWith("chunked"))
        }.use { server ->
            withClient { client ->
                for (name in listOf("large-fixed", "large-chunked", "late-fixed", "late-chunked")) {
                    val call = client.newCall(Request.Builder().url("${server.baseUrl}/$name").build())
                    val result = call.execute().use { response ->
                        runCatching { GitHubTreeResponseReader.readSha(response.body, call) }
                    }
                    if (name.startsWith("large")) assertEquals(sha, result.getOrThrow())
                    else assertTrue(result.exceptionOrNull() is IOException)
                    assertTrue("Tree response must be canceled before closing its body", call.isCanceled())
                }
                assertProductionClientVersion(server.requests)
            }
        }
    }

    @Test
    fun packagedGitHubClientPublishesWithAtomicExpectedRefAndRejectsAncestorRewind() = runBlocking {
        val originalHead = "a".repeat(40)
        val commit = "c".repeat(40)
        val ancestor = "d".repeat(40)
        var head = originalHead
        LoopbackServer { request ->
            val body = when (request.target) {
                "/repos/owner/repo" -> """{"node_id":"repository-node"}"""
                "/repos/owner/repo/git/blobs" -> """{"sha":"${"b".repeat(40)}"}"""
                "/repos/owner/repo/git/trees" -> """{"sha":"${"e".repeat(40)}"}"""
                "/repos/owner/repo/git/commits" -> """{"sha":"$commit"}"""
                "/graphql" -> {
                    val input = JSONObject(request.body.toString(Charsets.UTF_8)).getJSONObject("variables").getJSONObject("input")
                    val update = input.getJSONArray("refUpdates").getJSONObject(0)
                    check(input.getString("repositoryId") == "repository-node")
                    check(update.getString("name") == "refs/heads/main")
                    check(!update.getBoolean("force"))
                    if (update.getString("beforeOid") != head) {
                        """{"errors":[{"type":"STALE_DATA","message":"beforeOid does not match"}]}"""
                    } else {
                        head = update.getString("afterOid")
                        """{"data":{"updateRefs":{"clientMutationId":"${input.getString("clientMutationId")}"}}}"""
                    }
                }
                else -> """{"tree":{"sha":"${"f".repeat(40)}"}}"""
            }
            HttpReply(body = body.toByteArray())
        }.use { server ->
            withClient { client ->
                val github = GitHubApiClient("instrumented-token", client, "expired", server.baseUrl)
                val bytes = byteArrayOf(1, 2, 3)
                val path = "neriplayer-sync-v3-${WebDavApiClient.calculateFingerprint(bytes)}.zst"
                fun files() = sequenceOf(path to bytes, "neriplayer-sync-v3.manifest" to byteArrayOf(4))
                assertEquals(commit, github.updateFilesContent("owner", "repo", files(), GitHubSyncHead("main", originalHead)).getOrThrow())
                assertEquals(commit, head)
                head = ancestor
                val stale = github.updateFilesContent("owner", "repo", files(), GitHubSyncHead("main", commit)).exceptionOrNull()
                assertTrue(stale is GitHubContentConflictException)
                assertEquals(ancestor, head)
                assertTrue(server.requests.none { it.method == "PATCH" })
                val publications = server.requests.filter { it.target == "/graphql" }
                assertEquals(2, publications.size)
                assertTrue(publications.all { it.method == "POST" })
                val latest = JSONObject(publications.last().body.toString(Charsets.UTF_8)).getJSONObject("variables")
                    .getJSONObject("input").getJSONArray("refUpdates").getJSONObject(0)
                assertEquals(commit, latest.getString("beforeOid"))
                assertProductionClientVersion(server.requests)
            }
        }
    }

    @Test
    fun packagedWebDavClientPreservesStrongConditionsAndRejectsAStaleWrite() {
        var etag = "\"remote-v1\""
        val bytes = byteArrayOf(1, 2, 3)
        LoopbackServer { request ->
            if (request.method == "GET") {
                HttpReply(body = bytes, headers = mapOf("ETag" to etag))
            } else if (request.headers["if-match"] != null && request.headers["if-match"] != etag) {
                HttpReply(status = 412)
            } else {
                etag = "\"remote-v2\""
                HttpReply(status = 201, headers = mapOf("ETag" to etag))
            }
        }.use { server ->
            withClient { client ->
                val webdav = WebDavApiClient("instrumented-user", "instrumented-password", client, "auth")
                val url = "${server.baseUrl}/neriplayer-sync-v3.manifest"
                val snapshot = webdav.getFileContentStrict(url).getOrThrow()
                assertArrayEquals(bytes, snapshot.content)
                assertEquals("\"remote-v1\"", snapshot.version.etag)
                val replacement = byteArrayOf(4, 5)
                val written = webdav.updateFileContent(url, replacement, expectedVersion = snapshot.version).getOrThrow()
                assertEquals("\"remote-v2\"", written.version.etag)
                val protectedWrite = server.requests.last()
                assertEquals("PUT", protectedWrite.method)
                assertEquals("\"remote-v1\"", protectedWrite.headers["if-match"])
                assertNull(protectedWrite.headers["if-none-match"])
                assertArrayEquals(replacement, protectedWrite.body)
                val stale = webdav.updateFileContent(url, bytes, expectedVersion = snapshot.version).exceptionOrNull()
                assertTrue(stale is WebDavContentConflictException)
                assertEquals(412, (stale as WebDavContentConflictException).statusCode)
                webdav.updateFileContent("${server.baseUrl}/new-object.zst", bytes, createOnly = true).getOrThrow()
                val created = server.requests.last()
                assertEquals("*", created.headers["if-none-match"])
                assertNull(created.headers["if-match"])
                assertProductionClientVersion(server.requests)
            }
        }
    }

    private fun assertProductionClientVersion(requests: List<HttpRequest>) {
        assertTrue(requests.isNotEmpty())
        assertTrue("Android APK must exercise production OkHttp 5.4.0", requests.all { it.headers["user-agent"] == "okhttp/5.4.0" })
    }

    private inline fun <T> withClient(block: (OkHttpClient) -> T): T {
        val client = OkHttpClient()
        return try { block(client) } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private data class HttpRequest(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)
    private data class HttpReply(val status: Int = 200, val body: ByteArray = byteArrayOf(),
        val headers: Map<String, String> = emptyMap(), val chunked: Boolean = false)

    private class LoopbackServer(private val reply: (HttpRequest) -> HttpReply) : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val active = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        @Volatile private var failure: Exception? = null

        init {
            executor.submit {
                try {
                    while (!socket.isClosed) serve(socket.accept())
                } catch (error: IOException) {
                    if (!socket.isClosed) failure = error
                } catch (error: Exception) {
                    failure = error
                }
            }
        }

        private fun serve(connection: Socket) {
            active += connection
            try {
                connection.use {
                    it.soTimeout = 10_000
                    val request = readRequest(BufferedInputStream(it.getInputStream()))
                    requests += request
                    val response = reply(request)
                    // 超限响应会被客户端主动断开，测试服务只忽略响应写入时的连接关闭
                    try { writeReply(it.getOutputStream(), response) } catch (_: IOException) { }
                }
            } finally { active -= connection }
        }

        private fun readRequest(input: InputStream): HttpRequest {
            val line = readLine(input).split(' ', limit = 3)
            check(line.size == 3) { "Invalid loopback HTTP request" }
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val header = readLine(input)
                if (header.isEmpty()) break
                headers[header.substringBefore(':').lowercase(Locale.ROOT)] = header.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            check(length in 0..1_048_576) { "Loopback request body exceeds test budget" }
            val body = ByteArray(length)
            var position = 0
            while (position < body.size) {
                val read = input.read(body, position, body.size - position)
                if (read < 0) throw EOFException("Truncated loopback request")
                position += read
            }
            return HttpRequest(line[0], line[1], headers, body)
        }

        private fun readLine(input: InputStream): String {
            val line = ByteArrayOutputStream()
            while (line.size() < 16_384) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Missing loopback HTTP header")
                if (byte == 10) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.write(byte)
            }
            error("Loopback HTTP header exceeds test budget")
        }

        private fun writeReply(output: OutputStream, response: HttpReply) {
            val headers = buildString {
                append("HTTP/1.1 ${response.status} Test\r\nConnection: close\r\n")
                for ((name, value) in response.headers) append("$name: $value\r\n")
                if (response.chunked) append("Transfer-Encoding: chunked\r\n") else append("Content-Length: ${response.body.size}\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray(Charsets.ISO_8859_1))
            if (response.chunked) writeChunks(output, response.body) else output.write(response.body)
            output.flush()
        }

        private fun writeChunks(output: OutputStream, bytes: ByteArray) {
            for (offset in bytes.indices step 8_192) {
                val size = minOf(8_192, bytes.size - offset)
                output.write("${size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes, offset, size)
                output.write("\r\n".toByteArray(Charsets.US_ASCII))
            }
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }

        override fun close() {
            socket.close()
            for (connection in active) connection.close()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Loopback HTTP server did not stop" }
            failure?.let { throw AssertionError("Loopback HTTP server failed", it) }
        }
    }
}
