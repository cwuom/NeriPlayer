package moe.ouom.neriplayer.api.sync.webdav

import moe.ouom.neriplayer.api.sync.testing.SyncHttpFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavDirectoryTest {
    private val remote = "https://example.test/dav/a%20b/nested/neriplayer-sync.json"
    private fun api(fixture: SyncHttpFixture) =
        WebDavApiClient("user", "pass", fixture.client, "authentication failed")

    @Test
    fun `validation rejects a missing directory instead of accepting the missing file`() {
        val fixture = SyncHttpFixture(status = 404, directoryStatus = 404)
        val result = api(fixture).validateConnection("https://example.test/dav/", "a b/nested")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("directory"))
        assertFalse(result.exceptionOrNull() is WebDavAuthException)
        assertEquals(listOf("GET", "PROPFIND"), fixture.requests.map { it.method })
        assertEquals("https://example.test/dav/a%20b/nested/", fixture.requests.last().url.toString())
        assertEquals("0", fixture.requests.last().header("Depth"))
        assertEquals(fixture.requests.first().header("Authorization"), fixture.requests.last().header("Authorization"))
    }

    @Test
    fun `missing directory cannot enter the first sync upload path`() {
        val result = api(SyncHttpFixture(status = 404, directoryStatus = 404)).getFileContentStrict(remote)

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is WebDavFileNotFoundException)
        assertFalse(result.exceptionOrNull() is WebDavAuthException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("directory"))
    }

    @Test
    fun `missing file in an existing directory remains eligible for first sync`() {
        val fixture = SyncHttpFixture(status = 404, directoryStatus = 207)
        val client = api(fixture)

        assertTrue(client.validateConnection("https://example.test/dav/", "a b/nested").isSuccess)
        assertTrue(client.getFileContentStrict(remote).exceptionOrNull() is WebDavFileNotFoundException)
        assertEquals(listOf("GET", "PROPFIND", "GET", "PROPFIND"), fixture.requests.map { it.method })
    }

    @Test
    fun `empty base path probes the configured server directory`() {
        val fixture = SyncHttpFixture(status = 404, directoryStatus = 207)

        assertTrue(api(fixture).validateConnection("https://example.test/dav/", "").isSuccess)
        assertEquals("https://example.test/dav/", fixture.requests.last().url.toString())
    }

    @Test
    fun `forbidden responses report directory access instead of invalid credentials`() {
        val fixture = SyncHttpFixture(status = 403)
        val client = api(fixture)
        val results = listOf(
            client.validateConnection("https://example.test/dav/", "nested"),
            client.getFileContentStrict(remote),
            client.updateFileContent(remote, byteArrayOf(1), createOnly = true)
        )

        for (result in results) {
            val error = result.exceptionOrNull()
            assertTrue(error is WebDavApiException)
            assertFalse(error is WebDavAuthException)
            assertEquals(403, (error as WebDavApiException).statusCode)
            assertTrue(error.message!!.contains("directory"))
        }
    }

    @Test
    fun `upload distinguishes a missing directory from a content conflict`() {
        for (status in listOf(404, 409)) {
            val fixture = SyncHttpFixture(status = status, directoryStatus = 404)
            val result = api(fixture).updateFileContent(remote, byteArrayOf(1), createOnly = true)

            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull() is WebDavContentConflictException)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("directory"))
            assertEquals(listOf("PUT", "PROPFIND"), fixture.requests.map { it.method })
            assertEquals("*", fixture.requests.first().header("If-None-Match"))
        }
    }

    @Test
    fun `upload conflict in an existing directory preserves concurrency protection`() {
        val fixture = SyncHttpFixture(status = 409, directoryStatus = 207)
        val result = api(fixture).updateFileContent(remote, byteArrayOf(1), createOnly = true)

        assertTrue(result.exceptionOrNull() is WebDavContentConflictException)
        assertEquals("*", fixture.requests.first().header("If-None-Match"))
    }

    @Test
    fun `directory probe preserves authentication permission and server failures`() {
        for (status in listOf(401, 403, 500)) {
            val fixture = SyncHttpFixture(status = 404, directoryStatus = status)
            val result = api(fixture).getFileContentStrict(remote)
            val error = result.exceptionOrNull()

            if (status == 401) {
                assertTrue(error is WebDavAuthException)
            } else {
                assertTrue(error is WebDavApiException)
                assertEquals(status, (error as WebDavApiException).statusCode)
            }
            assertFalse(error is WebDavFileNotFoundException)
        }
    }

    @Test
    fun `multi status resource failure cannot enter first sync`() {
        for (status in listOf(401, 403, 404, 500)) {
            val body = """
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/a%20b/nested/</d:href>
                <d:status>HTTP/1.1 $status Failure</d:status></d:response></d:multistatus>
            """.trimIndent().toByteArray()
            val fixture = SyncHttpFixture(status = 404, directoryContent = body)
            val result = api(fixture).getFileContentStrict(remote)
            val error = result.exceptionOrNull()

            assertFalse(error is WebDavFileNotFoundException)
            when (status) {
                401 -> assertTrue(error is WebDavAuthException)
                404 -> assertTrue(error!!.message!!.contains("directory"))
                else -> assertEquals(status, (error as WebDavApiException).statusCode)
            }
        }
    }

    @Test
    fun `invalid directory XML and document types are rejected before first sync`() {
        for (body in listOf(
            "{}", "<d:multistatus xmlns:d=\"DAV:\"/>",
            "<!DOCTYPE d:multistatus [<!ENTITY remote SYSTEM \"https://example.test/private\">]>" +
                "<d:multistatus xmlns:d=\"DAV:\">&remote;</d:multistatus>"
        )) {
            val fixture = SyncHttpFixture(status = 404, directoryContent = body.toByteArray())
            val result = api(fixture).getFileContentStrict(remote)

            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull() is WebDavFileNotFoundException)
            assertFalse(result.exceptionOrNull() is WebDavAuthException)
        }
    }

    @Test
    fun `missing properties do not mean the directory itself is missing`() {
        val body = """
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href>
            <d:propstat><d:prop/><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            </d:response></d:multistatus>
        """.trimIndent().toByteArray()
        val error = api(SyncHttpFixture(status = 404, directoryContent = body))
            .getFileContentStrict(remote).exceptionOrNull()

        assertTrue(error is WebDavApiException)
        assertFalse(error is WebDavDirectoryNotFoundException)
        assertFalse(error is WebDavFileNotFoundException)
    }
}
