package moe.ouom.neriplayer.api.sync.webdav

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavResponseLifecycleTest {
    private val remote = "https://example.test/dav/neriplayer-sync.json"

    @Test
    fun `validation closes the missing file response before probing its directory`() {
        val fixture = ResponseLifecycleFixture(404)

        assertTrue(fixture.api.validateConnection("https://example.test/dav/", "").isSuccess)
        assertTrue(fixture.closedBeforeProbe)
        assertTrue(fixture.fileBody.closed)
    }

    @Test
    fun `strict read closes the missing file response before probing its directory`() {
        val fixture = ResponseLifecycleFixture(404)

        assertTrue(fixture.api.getFileContentStrict(remote).exceptionOrNull() is WebDavFileNotFoundException)
        assertTrue(fixture.closedBeforeProbe)
        assertTrue(fixture.fileBody.closed)
    }

    @Test
    fun `missing upload closes the response before probing and preserves its error`() {
        assertUploadFailure(404, WebDavApiException::class.java)
    }

    @Test
    fun `conflicting upload closes the response before probing and preserves its error`() {
        assertUploadFailure(409, WebDavContentConflictException::class.java)
    }

    @Test
    fun `failed directory probe runs after the file response is closed`() {
        val fixture = ResponseLifecycleFixture(404, directoryStatus = 403)
        val error = fixture.api.getFileContentStrict(remote).exceptionOrNull()

        assertTrue(error is WebDavAccessDeniedException)
        assertTrue(fixture.closedBeforeProbe)
        assertTrue(fixture.fileBody.closed)
    }

    private fun assertUploadFailure(status: Int, expectedType: Class<out WebDavApiException>) {
        val fixture = ResponseLifecycleFixture(status)
        val error = fixture.api.updateFileContent(remote, byteArrayOf(1), createOnly = true).exceptionOrNull()

        assertTrue(expectedType.isInstance(error))
        assertEquals(status, (error as WebDavApiException).statusCode)
        assertTrue(error.message!!.contains("upload rejected"))
        assertTrue(fixture.closedBeforeProbe)
        assertTrue(fixture.fileBody.closed)
    }

    private class ResponseLifecycleFixture(status: Int, directoryStatus: Int = 207) {
        val fileBody = TrackingResponseBody("upload rejected".toResponseBody())
        var closedBeforeProbe = false
            private set

        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val probe = chain.request().method == "PROPFIND"
            if (probe) closedBeforeProbe = fileBody.closed
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (probe) directoryStatus else status).message("fixture")
                .body(if (probe) directoryXml.toResponseBody("application/xml".toMediaType()) else fileBody)
                .build()
        }.build()
        val api = WebDavApiClient("user", "pass", client, "authentication failed")

        private companion object {
            const val directoryXml = """
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
            """
        }
    }

    private class TrackingResponseBody(private val delegate: ResponseBody) : ResponseBody() {
        var closed = false
            private set
        private val trackedSource = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                check(!closed) { "response body is closed" }
                return super.read(sink, byteCount)
            }
        }.buffer()

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = trackedSource

        override fun close() {
            closed = true
            trackedSource.close()
            delegate.close()
        }
    }
}
