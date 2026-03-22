package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeGoogleVideoRangeSupportTest {

    @Test
    fun shouldUseChunkedRange_matchesYoutubeGoogleVideoPlaybackUrl() {
        val url =
            "https://rr2---sn-aigzrn7k.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&clen=3965665"

        assertTrue(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsNonYoutubeUrl() {
        val url = "https://example.com/audio.mp3"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsHlsManifestUrl() {
        val url =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1773862162/id/demo/itag/234/source/youtube/playlist/index.m3u8"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsHlsSegmentUrl() {
        val url =
            "https://rr1---sn-aigzrnze.googlevideo.com/videoplayback/id/demo/itag/234/source/youtube/playlist/index.m3u8/begin/0/len/3750/file/seg.ts"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldUseChunkedRange_rejectsResolvedWebRemixDirectUrl() {
        val url =
            "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                "?source=youtube&id=audio-demo&n=resolved-n&sig=resolved-signature&mime=audio%2Fwebm"

        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
    }

    @Test
    fun shouldForceExplicitFullRange_matchesResolvedWebRemixDirectUrlWithContentLength() {
        val url =
            "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                "?source=youtube&id=audio-demo&n=resolved-n&sig=resolved-signature&mime=audio%2Fwebm&clen=3965665"

        assertTrue(YouTubeGoogleVideoRangeSupport.shouldForceExplicitFullRange(url))
    }

    @Test
    fun supportsSeekingWithoutUrlRefresh_acceptsSigOnlyDirectUrl() {
        val url =
            "https://rr4---sn-3pm7dnes.googlevideo.com/videoplayback" +
                "?source=youtube&mime=audio%2Fwebm&sig=resolved-signature&clen=3433755"

        assertTrue(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh(url))
        assertFalse(YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(url))
        assertTrue(YouTubeGoogleVideoRangeSupport.shouldForceExplicitFullRange(url))
    }

    @Test
    fun resolveQueryContentLength_readsClenFromUrl() {
        val url =
            "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                "?source=youtube&clen=3965665&mime=audio%2Fwebm"

        assertEquals(3_965_665L, YouTubeGoogleVideoRangeSupport.resolveQueryContentLength(url))
    }

    @Test
    fun buildFullRangeHeader_buildsInclusiveRange() {
        assertEquals("bytes=0-3965664", YouTubeGoogleVideoRangeSupport.buildFullRangeHeader(3_965_665L))
    }

    @Test
    fun buildRangeHeader_respectsStartPositionAndKnownLength() {
        assertEquals(
            "bytes=1048576-2097151",
            YouTubeGoogleVideoRangeSupport.buildRangeHeader(
                startPosition = 1_048_576L,
                requestedLength = 1_048_576L,
                totalContentLength = 3_965_665L
            )
        )
    }

    @Test
    fun candidateChunkLengths_clampsToRequestedLength() {
        val candidates = YouTubeGoogleVideoRangeSupport.candidateChunkLengths(300_000L)

        assertEquals(listOf(300_000L, 150_000L, 131_072L), candidates)
    }

    @Test
    fun candidateChunkLengths_respectsLargerPreferredChunkSize() {
        val candidates = YouTubeGoogleVideoRangeSupport.candidateChunkLengths(
            requestLength = 10L * 1024L * 1024L,
            preferredChunkSize = 4L * 1024L * 1024L
        )

        assertEquals(
            listOf(4_194_304L, 2_097_152L, 1_048_576L, 524_288L, 262_144L, 131_072L),
            candidates
        )
    }

    @Test
    fun resolveTotalContentLength_prefersContentRangeBeforeQuery() {
        val url = "https://rr2---sn.googlevideo.com/videoplayback?source=youtube&clen=3965665"
        val headers = mapOf(
            "Content-Range" to listOf("bytes 0-1023/1234567"),
            "Content-Length" to listOf("1024")
        )

        val total = YouTubeGoogleVideoRangeSupport.resolveTotalContentLength(url, headers)

        assertEquals(1_234_567L, total)
    }

    @Test
    fun resolveChunkResponseLength_usesContentRangeWhenNeeded() {
        val headers = mapOf(
            "Content-Range" to listOf("bytes 0-1023/3965665")
        )

        val resolved = YouTubeGoogleVideoRangeSupport.resolveChunkResponseLength(
            requestedLength = 1_048_576L,
            headers = headers,
            delegateOpenLength = -1L
        )

        assertEquals(1_024L, resolved)
    }

    @Test
    fun executeChunkLengthFallback_doesNotRetryOn403() {
        val attempts = mutableListOf<Long>()

        val error = runCatching {
            YouTubeGoogleVideoRangeSupport.executeChunkLengthFallback(300_000L) { chunkLength ->
                attempts += chunkLength
                throw ChunkRequestIOException(403, "HTTP 403")
            }
        }.exceptionOrNull()

        // 403 不再 fallback，只尝试一次就抛出
        assertEquals(listOf(300_000L), attempts)
        assertTrue(error is ChunkRequestIOException)
    }

    @Test
    fun executeChunkLengthFallback_retriesWithSmallerChunkOn416() {
        val attempts = mutableListOf<Long>()

        val result = YouTubeGoogleVideoRangeSupport.executeChunkLengthFallback(300_000L) { chunkLength ->
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
