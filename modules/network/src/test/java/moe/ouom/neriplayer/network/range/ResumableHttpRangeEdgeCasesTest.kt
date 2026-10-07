package moe.ouom.neriplayer.network.range

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ResumableHttpRangeEdgeCasesTest {

    private val baseUrl = "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback"

    @Test
    fun `query content length needs a positive numeric clen parameter`() {
        assertEquals(10L, ResumableHttpRangeSupport.resolveQueryContentLength("$baseUrl?a=1&clen=10&b=2"))
        assertNull(ResumableHttpRangeSupport.resolveQueryContentLength("$baseUrl?mime=audio%2Fwebm"))
        assertNull(ResumableHttpRangeSupport.resolveQueryContentLength("$baseUrl?xclen=10"))
        assertNull(ResumableHttpRangeSupport.resolveQueryContentLength("$baseUrl?clen=0"))
        assertNull(ResumableHttpRangeSupport.resolveQueryContentLength("$baseUrl?clen=99999999999999999999"))
    }

    @Test
    fun `only range not satisfiable responses retry with a smaller chunk`() {
        assertTrue(ResumableHttpRangeSupport.shouldRetryChunkError(ChunkRequestIOException(416, "HTTP 416")))
        assertFalse(ResumableHttpRangeSupport.shouldRetryChunkError(ChunkRequestIOException(500, "HTTP 500")))
        assertTrue(ResumableHttpRangeSupport.shouldRetryChunkError(invalidResponseCode(416)))
        assertFalse(ResumableHttpRangeSupport.shouldRetryChunkError(invalidResponseCode(403)))
        assertFalse(ResumableHttpRangeSupport.shouldRetryChunkError(IOException("connection reset")))
    }

    @Test
    fun `chunked requests replace the range header and keep the rest of the request`() {
        val request = Request.Builder()
            .url("$baseUrl?clen=4096")
            .header("Range", "bytes=0-1")
            .header("User-Agent", "NeriPlayer")
            .build()

        val chunk = ResumableHttpRangeSupport.buildChunkedRequest(request, start = 1_024L, length = 512L)

        assertEquals(listOf("bytes=1024-1535"), chunk.headers("Range"))
        assertEquals("NeriPlayer", chunk.header("User-Agent"))
        assertEquals(request.url, chunk.url)
    }

    @Test
    fun `chunked requests need a non negative start and a positive length`() {
        val request = Request.Builder().url(baseUrl).build()

        val negativeStart = assertThrows(IllegalArgumentException::class.java) {
            ResumableHttpRangeSupport.buildChunkedRequest(request, start = -1L, length = 10L)
        }
        val emptyLength = assertThrows(IllegalArgumentException::class.java) {
            ResumableHttpRangeSupport.buildChunkedRequest(request, start = 0L, length = 0L)
        }

        assertEquals("start must be non-negative", negativeStart.message)
        assertEquals("length must be positive", emptyLength.message)
    }

    @Test
    fun `unusable content ranges fall back to content length and then the requested length`() {
        assertEquals(4_096L, resolveChunkLength("bytes 0-1023/2048", delegateOpenLength = 4_096L))
        assertEquals(1_024L, resolveChunkLength("bytes 0-1023/2048", contentLength = "2048"))
        assertEquals(2_048L, resolveChunkLength("bytes */3965665", contentLength = "2048"))
        assertEquals(2_048L, resolveChunkLength("bytes 100-", contentLength = "2048"))
        assertEquals(2_048L, resolveChunkLength("bytes 500-100/1000", contentLength = "2048"))
        assertEquals(REQUESTED_LENGTH, resolveChunkLength("bytes 500-100/1000", contentLength = "0"))
        assertEquals(REQUESTED_LENGTH, resolveChunkLength(contentRange = null))
    }

    private fun resolveChunkLength(
        contentRange: String?,
        contentLength: String? = null,
        delegateOpenLength: Long = -1L
    ): Long {
        val headers = buildMap {
            contentRange?.let { put("content-range", listOf(it)) }
            contentLength?.let { put("Content-Length", listOf(it)) }
        }
        return ResumableHttpRangeSupport.resolveChunkResponseLength(
            requestedLength = REQUESTED_LENGTH,
            headers = headers,
            delegateOpenLength = delegateOpenLength
        )
    }

    private fun invalidResponseCode(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code,
        "status $code",
        null,
        emptyMap(),
        DataSpec(mock(Uri::class.java)),
        ByteArray(0)
    )

    private companion object {
        const val REQUESTED_LENGTH = 1_048_576L
    }
}
