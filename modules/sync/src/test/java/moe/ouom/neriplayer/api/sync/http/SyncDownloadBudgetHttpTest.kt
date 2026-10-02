package moe.ouom.neriplayer.api.sync.http

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.github.GitHubApiException
import moe.ouom.neriplayer.api.sync.github.GitHubRateLimitException
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAccessDeniedException
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class SyncDownloadBudgetHttpTest {
    @Test
    fun `canonical v3 files reject oversized fixed and chunked HTTP responses`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val client = clientRecordingCalls(calls)
        MockWebServer().use { server ->
            server.start()
            try {
                val github = GitHubApiClient("test-token", client, "expired", server.url("/").toString().trimEnd('/'))
                val webdav = WebDavApiClient("test-user", "test-password", client, "auth")
                val bytes = ByteArray(SyncFileTransferLimits.ARCHIVE_FILE_BYTES + 1) { 1 }
                for (name in listOf("neriplayer-sync-v3.manifest", "neriplayer-sync-v3-${"a".repeat(64)}.zst")) {
                    for (chunked in listOf(false, true)) {
                        server.enqueue(response(bytes, chunked))
                        val githubResult = github.getFileContentAtRef("owner", "repo", name, "head")
                        assertTrue(githubResult.exceptionOrNull() is IOException)
                        assertTrue(githubResult.exceptionOrNull()!!.message!!.contains("too large"))
                        assertTrue("GitHub rejects the oversized body before connection reuse drains it", calls.last().isCanceled())
                        server.enqueue(response(bytes, chunked))
                        val webdavResult = webdav.getFileContentStrict(server.url("/$name").toString())
                        assertTrue(webdavResult.exceptionOrNull() is IOException)
                        assertTrue(webdavResult.exceptionOrNull()!!.message!!.contains("too large"))
                        assertTrue("WebDAV rejects the oversized body before connection reuse drains it", calls.last().isCanceled())
                    }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `exact v3 maximum succeeds and larger legacy snapshots retain their old allowance`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val client = clientRecordingCalls(calls)
        MockWebServer().use { server ->
            server.start()
            try {
                val github = GitHubApiClient("test-token", client, "expired", server.url("/").toString().trimEnd('/'))
                val webdav = WebDavApiClient("test-user", "test-password", client, "auth")
                val files = listOf("neriplayer-sync-v3.manifest" to SyncFileTransferLimits.ARCHIVE_FILE_BYTES,
                    "backup.bin" to SyncFileTransferLimits.ARCHIVE_FILE_BYTES + 1)
                for ((name, size) in files) {
                    val bytes = ByteArray(size) { 1 }
                    server.enqueue(response(bytes, chunked = true))
                    assertEquals(size, github.getFileContentAtRef("owner", "repo", name, "head").getOrThrow().size)
                    assertFalse("Legal GitHub content must retain a reusable connection", calls.last().isCanceled())
                    server.enqueue(response(bytes, chunked = false))
                    assertEquals(size, webdav.getFileContentStrict(server.url("/$name").toString()).getOrThrow().content.size)
                    assertFalse("Legal WebDAV content must retain a reusable connection", calls.last().isCanceled())
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `v3 fixed and chunked oversized server errors cancel owned requests before closing`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val client = clientRecordingCalls(calls)
        MockWebServer().use { server ->
            server.start()
            try {
                val github = GitHubApiClient("test-token", client, "expired", server.url("/").toString().trimEnd('/'))
                val webdav = WebDavApiClient("test-user", "test-password", client, "auth")
                val bytes = ByteArray(SyncFileTransferLimits.ARCHIVE_FILE_BYTES + 1) { 'x'.code.toByte() }
                for (name in listOf("neriplayer-sync-v3.manifest", "neriplayer-sync-v3-${"a".repeat(64)}.zst")) {
                    for (chunked in listOf(false, true)) {
                        server.enqueue(response(bytes, chunked, code = 500))
                        val githubError = github.getFileContentAtRef("owner", "repo", name, "head").exceptionOrNull()
                        assertTrue(githubError is IOException)
                        assertTrue(githubError!!.message!!.contains("too large"))
                        assertTrue(calls.last().isCanceled())
                        server.enqueue(response(bytes, chunked, code = 500))
                        val webdavError = webdav.getFileContentStrict(server.url("/$name").toString()).exceptionOrNull()
                        assertTrue(webdavError is IOException)
                        assertTrue(webdavError!!.message!!.contains("too large"))
                        assertTrue(calls.last().isCanceled())
                    }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `legal small v3 errors and larger legacy errors preserve typed status without cancelling requests`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val client = clientRecordingCalls(calls)
        MockWebServer().use { server ->
            server.start()
            try {
                val github = GitHubApiClient("test-token", client, "expired", server.url("/").toString().trimEnd('/'))
                val webdav = WebDavApiClient("test-user", "test-password", client, "auth")
                val files = listOf("neriplayer-sync-v3.manifest" to """{"message":"unavailable"}""".toByteArray(),
                    "backup.bin" to ByteArray(SyncFileTransferLimits.ARCHIVE_FILE_BYTES + 1) { 'x'.code.toByte() })
                for ((name, bytes) in files) {
                    for (chunked in listOf(false, true)) {
                        server.enqueue(response(bytes, chunked, code = 500))
                        val githubError = github.getFileContentAtRef("owner", "repo", name, "head").exceptionOrNull() as GitHubApiException
                        assertEquals(500, githubError.statusCode)
                        assertFalse(calls.last().isCanceled())
                        server.enqueue(response(bytes, chunked, code = 500))
                        val webdavError = webdav.getFileContentStrict(server.url("/$name").toString()).exceptionOrNull() as WebDavApiException
                        assertEquals(500, webdavError.statusCode)
                        assertFalse(calls.last().isCanceled())
                    }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `bounded v3 auth and quota errors keep their existing exception contracts`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val client = clientRecordingCalls(calls)
        MockWebServer().use { server ->
            server.start()
            try {
                val github = GitHubApiClient("test-token", client, "expired", server.url("/").toString().trimEnd('/'))
                val webdav = WebDavApiClient("test-user", "test-password", client, "auth")
                val name = "neriplayer-sync-v3.manifest"
                server.enqueue(MockResponse(code = 401, body = """{"message":"expired"}"""))
                assertTrue(github.getFileContentAtRef("owner", "repo", name, "head").exceptionOrNull() is TokenExpiredException)
                assertFalse(calls.last().isCanceled())
                server.enqueue(MockResponse(code = 401, body = "expired"))
                assertTrue(webdav.getFileContentStrict(server.url("/$name").toString()).exceptionOrNull() is WebDavAuthException)
                assertFalse(calls.last().isCanceled())
                server.enqueue(MockResponse.Builder().code(403).addHeader("Retry-After", "120")
                    .body("""{"message":"secondary rate limit"}""").build())
                assertTrue(github.getFileContentAtRef("owner", "repo", name, "head").exceptionOrNull() is GitHubRateLimitException)
                assertFalse(calls.last().isCanceled())
                server.enqueue(MockResponse(code = 403, body = "denied"))
                assertTrue(webdav.getFileContentStrict(server.url("/$name").toString()).exceptionOrNull() is WebDavAccessDeniedException)
                assertFalse(calls.last().isCanceled())
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun response(bytes: ByteArray, chunked: Boolean, code: Int = 200): MockResponse {
        val body = Buffer().write(bytes)
        val builder = MockResponse.Builder().code(code)
        return (if (chunked) builder.chunkedBody(body, 8_192) else builder.body(body)).build()
    }

    private fun clientRecordingCalls(calls: MutableList<Call>): OkHttpClient = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
        }).build()
}
