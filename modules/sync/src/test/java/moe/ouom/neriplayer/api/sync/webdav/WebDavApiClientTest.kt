package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.api.sync.testing.SyncHttpFixture
import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavApiClientTest {
    private val remote = "https://example.test/sync"
    private fun api(fixture: SyncHttpFixture) = WebDavApiClient("user", "pass", fixture.client, "authentication failed")

    @Test
    fun `URL joins normalized path segments and encodes spaces`() {
        assertEquals("https://example.test/dav/a%20b/c/neriplayer-sync.json", WebDavApiClient.buildRemoteFileUrl(" https://example.test/dav/ ", " /a b//c/ "))
        assertEquals("https://example.test/neriplayer-sync.json", WebDavApiClient.buildRemoteFileUrl("https://example.test/", ""))
    }

    @Test
    fun `archive sibling names retain configured parent path without allowing traversal`() {
        assertEquals("https://example.test/dav/a%20b/manifest", WebDavApiClient.buildSiblingFileUrl(
            "https://example.test/dav/a%20b/neriplayer-sync.json", "manifest"))
        for (name in listOf("", "../object", "/object", "a/b")) {
            assertTrue(runCatching { WebDavApiClient.buildSiblingFileUrl(remote, name) }.isFailure)
        }
    }

    @Test
    fun `read preserves bytes fingerprint and strong response validators`() {
        val content = byteArrayOf(0, 1, 0xFF.toByte())
        val fixture = SyncHttpFixture(content = content, headers = mapOf("ETag" to " \"version\" ", "Last-Modified" to " yesterday "))
        val snapshot = api(fixture).getFileContentStrict(remote).getOrThrow()
        assertArrayEquals(content, snapshot.content)
        assertEquals(WebDavApiClient.calculateFingerprint(content), snapshot.fingerprint)
        assertEquals(WebDavConcurrencyToken("\"version\"", "yesterday"), snapshot.version)
        assertTrue(fixture.requests.single().header("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `weak and blank response ETags are excluded from conditional writes`() {
        for (etag in listOf("W/\"v\"", " ")) {
            val fixture = SyncHttpFixture(headers = mapOf("ETag" to etag, "Last-Modified" to " "))
            val snapshot = api(fixture).getFileContentStrict(remote).getOrThrow()
            assertNull(snapshot.version.etag)
            assertNull(snapshot.version.lastModified)
            assertFalse(snapshot.version.hasConditionToken())
        }
    }

    @Test
    fun `validation treats missing target as usable but preserves authentication and server failures`() {
        for (status in listOf(200, 404)) assertTrue(api(SyncHttpFixture(status)).validateConnection("https://example.test", "").isSuccess)
        assertTrue(api(SyncHttpFixture(401)).validateConnection("https://example.test", "").exceptionOrNull() is WebDavAuthException)
        assertTrue(api(SyncHttpFixture(403)).validateConnection("https://example.test", "").exceptionOrNull() is WebDavApiException)
        for (body in listOf("", "failure")) assertTrue(api(SyncHttpFixture(500, body.toByteArray())).validateConnection("https://example.test", "").exceptionOrNull() is WebDavApiException)
        assertTrue(api(SyncHttpFixture()).validateConnection("bad URL", "").isFailure)
    }

    @Test
    fun `reads distinguish missing authentication and generic remote failure`() {
        assertTrue(api(SyncHttpFixture(404)).getFileContentStrict(remote).exceptionOrNull() is WebDavFileNotFoundException)
        assertTrue(api(SyncHttpFixture(401)).getFileContentStrict(remote).exceptionOrNull() is WebDavAuthException)
        assertTrue(api(SyncHttpFixture(403)).getFileContentStrict(remote).exceptionOrNull() is WebDavApiException)
        for (body in listOf("", "failure")) assertTrue(api(SyncHttpFixture(500, body.toByteArray())).getFileContentStrict(remote).exceptionOrNull() is WebDavApiException)
    }

    @Test
    fun `create only uses if none match while overwrite prefers strong ETag`() {
        val fixture = SyncHttpFixture()
        val client = api(fixture)
        client.updateFileContent(remote, byteArrayOf(1), createOnly = true).getOrThrow()
        assertEquals("*", fixture.requests.last().header("If-None-Match"))
        val token = WebDavConcurrencyToken("\"version\"", "yesterday")
        val written = client.updateFileContent(remote, byteArrayOf(2), expectedVersion = token).getOrThrow()
        assertEquals("\"version\"", fixture.requests.last().header("If-Match"))
        assertNull(fixture.requests.last().header("If-Unmodified-Since"))
        assertEquals(WebDavApiClient.calculateFingerprint(byteArrayOf(2)), written.fingerprint)
    }

    @Test
    fun `last modified fallback and explicit unconditional overwrite use intended headers`() {
        val fixture = SyncHttpFixture()
        val client = api(fixture)
        client.updateFileContent(remote, byteArrayOf(1), "application/json", WebDavConcurrencyToken(lastModified = "yesterday")).getOrThrow()
        assertEquals("yesterday", fixture.requests.last().header("If-Unmodified-Since"))
        assertNull(fixture.requests.last().header("If-Match"))
        client.updateFileContent(remote, byteArrayOf(1), expectedVersion = WebDavConcurrencyToken(), allowUnconditionalWrite = true).getOrThrow()
        assertNull(fixture.requests.last().header("If-Match"))
        client.updateFileContent(remote, byteArrayOf(1), allowUnconditionalWrite = true).getOrThrow()
        assertNull(fixture.requests.last().header("If-Match"))
        assertNull(fixture.requests.last().header("If-Unmodified-Since"))
    }

    @Test
    fun `missing concurrency token prevents network write`() {
        val fixture = SyncHttpFixture()
        val result = api(fixture).updateFileContent(remote, byteArrayOf(1), expectedVersion = WebDavConcurrencyToken())
        assertTrue(result.exceptionOrNull() is WebDavMissingConcurrencyTokenException)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `blank ETag uses last modified without an empty if match header`() {
        val fixture = SyncHttpFixture()
        api(fixture).updateFileContent(remote, byteArrayOf(1), expectedVersion = WebDavConcurrencyToken("", "yesterday")).getOrThrow()
        assertNull(fixture.requests.single().header("If-Match"))
        assertEquals("yesterday", fixture.requests.single().header("If-Unmodified-Since"))
    }

    @Test
    fun `write error classes preserve retry and concurrency decisions`() {
        for (status in listOf(401, 403, 409, 412, 423, 500)) for (body in listOf("error", "", " ")) {
            val client = api(SyncHttpFixture(status, body.toByteArray()))
            val error = client.updateFileContent(remote, byteArrayOf(1), createOnly = true).exceptionOrNull()
            when (status) {
                401 -> assertTrue(error is WebDavAuthException)
                409, 412, 423 -> assertTrue(error is WebDavContentConflictException)
                else -> assertTrue(error is WebDavApiException)
            }
        }
    }
}
