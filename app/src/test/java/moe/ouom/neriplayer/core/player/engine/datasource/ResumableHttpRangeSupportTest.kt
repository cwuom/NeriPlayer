package moe.ouom.neriplayer.core.player.engine.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableHttpRangeSupportTest {

    @Test
    fun resolveQueryContentLengthReadsClenFromUrl() {
        val url =
            "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                "?source=youtube&clen=3965665&mime=audio%2Fwebm"

        assertEquals(3_965_665L, ResumableHttpRangeSupport.resolveQueryContentLength(url))
    }

    @Test
    fun candidateChunkLengthsClampsToRequestedLength() {
        val candidates = ResumableHttpRangeSupport.candidateChunkLengths(300_000L)

        assertEquals(listOf(300_000L, 150_000L, 131_072L), candidates)
    }

    @Test
    fun candidateChunkLengthsRespectsLargerPreferredChunkSize() {
        val candidates = ResumableHttpRangeSupport.candidateChunkLengths(
            requestLength = 10L * 1024L * 1024L,
            preferredChunkSize = 4L * 1024L * 1024L
        )

        assertEquals(
            listOf(4_194_304L, 2_097_152L, 1_048_576L, 524_288L, 262_144L, 131_072L),
            candidates
        )
    }

    @Test
    fun resolveTotalContentLengthPrefersContentRangeBeforeQuery() {
        val url = "https://rr2---sn.googlevideo.com/videoplayback?source=youtube&clen=3965665"
        val headers = mapOf(
            "Content-Range" to listOf("bytes 0-1023/1234567"),
            "Content-Length" to listOf("1024")
        )

        val total = ResumableHttpRangeSupport.resolveTotalContentLength(url, headers)

        assertEquals(1_234_567L, total)
    }

    @Test
    fun resolveChunkResponseLengthUsesContentRangeWhenNeeded() {
        val headers = mapOf(
            "Content-Range" to listOf("bytes 0-1023/3965665")
        )

        val resolved = ResumableHttpRangeSupport.resolveChunkResponseLength(
            requestedLength = 1_048_576L,
            headers = headers,
            delegateOpenLength = -1L
        )

        assertEquals(1_024L, resolved)
    }

    @Test
    fun executeChunkLengthFallbackDoesNotRetryOn403() {
        val attempts = mutableListOf<Long>()

        val error = runCatching {
            ResumableHttpRangeSupport.executeChunkLengthFallback(300_000L) { chunkLength ->
                attempts += chunkLength
                throw ChunkRequestIOException(403, "HTTP 403")
            }
        }.exceptionOrNull()

        assertEquals(listOf(300_000L), attempts)
        assertTrue(error is ChunkRequestIOException)
    }

    @Test
    fun executeChunkLengthFallbackRetriesWithSmallerChunkOn416() {
        val attempts = mutableListOf<Long>()

        val result = ResumableHttpRangeSupport.executeChunkLengthFallback(300_000L) { chunkLength ->
            attempts += chunkLength
            if (chunkLength == 300_000L) {
                throw ChunkRequestIOException(416, "HTTP 416")
            }
            "ok-$chunkLength"
        }

        assertEquals(listOf(300_000L, 150_000L), attempts)
        assertEquals(150_000L, result.chunkLength)
        assertEquals("ok-150000", result.value)
    }
}
