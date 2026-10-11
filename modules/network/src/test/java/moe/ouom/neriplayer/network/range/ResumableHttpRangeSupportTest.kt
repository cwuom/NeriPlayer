package moe.ouom.neriplayer.network.range

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
    fun candidateChunkLengthsKeepsShortRequestsAndUnknownLengths() {
        assertEquals(listOf(131_072L, 4_096L), ResumableHttpRangeSupport.candidateChunkLengths(4_096L))
        assertEquals(listOf(131_072L), ResumableHttpRangeSupport.candidateChunkLengths(131_072L))
        assertEquals(
            listOf(1_048_576L, 524_288L, 262_144L, 131_072L),
            ResumableHttpRangeSupport.candidateChunkLengths(requestLength = -1L)
        )
        assertEquals(
            listOf(131_072L),
            ResumableHttpRangeSupport.candidateChunkLengths(requestLength = 0L, preferredChunkSize = 1L)
        )
    }

    @Test
    fun resolveTotalContentLengthFallsBackFromRangeToQueryToLength() {
        val queryUrl = "https://example.com/audio?clen=900"
        val plainUrl = "https://example.com/audio"

        assertEquals(900L, ResumableHttpRangeSupport.resolveTotalContentLength(queryUrl, mapOf(
            "content-range" to listOf("bytes 0-9/*"), "Content-Length" to listOf("10")
        )))
        assertEquals(10L, ResumableHttpRangeSupport.resolveTotalContentLength(plainUrl, mapOf(
            "Content-Range" to listOf("bytes 0-9/0"), "content-length" to listOf("10")
        )))
        assertEquals(null, ResumableHttpRangeSupport.resolveTotalContentLength(plainUrl, mapOf(
            "Content-Length" to listOf("not-a-number")
        )))
        assertEquals(null, ResumableHttpRangeSupport.resolveTotalContentLength(plainUrl, mapOf(
            "Content-Length" to emptyList()
        )))
        assertEquals(null, ResumableHttpRangeSupport.resolveTotalContentLength(plainUrl, emptyMap()))
    }

    @Test
    fun resolveChunkResponseLengthPrefersOpenLengthThenHeadersThenRequest() {
        val rangeHeaders = mapOf("Content-Range" to listOf("bytes 0-1023/3965665"))

        assertEquals(77L, ResumableHttpRangeSupport.resolveChunkResponseLength(5L, rangeHeaders, delegateOpenLength = 77L))
        assertEquals(512L, ResumableHttpRangeSupport.resolveChunkResponseLength(5L, mapOf(
            "Content-Range" to listOf("bytes 9-3/100"), "Content-Length" to listOf("512")
        ), delegateOpenLength = 0L))
        assertEquals(5L, ResumableHttpRangeSupport.resolveChunkResponseLength(5L, mapOf(
            "Content-Range" to listOf("bytes x-3/100"), "Content-Length" to listOf("0")
        ), delegateOpenLength = -1L))
        assertEquals(5L, ResumableHttpRangeSupport.resolveChunkResponseLength(5L, mapOf(
            "Content-Range" to listOf("bytes 3-/100")
        ), delegateOpenLength = -1L))
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
