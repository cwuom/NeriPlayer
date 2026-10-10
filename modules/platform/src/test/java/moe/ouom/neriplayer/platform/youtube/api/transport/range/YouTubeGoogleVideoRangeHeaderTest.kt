package moe.ouom.neriplayer.platform.youtube.api.transport.range

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeGoogleVideoRangeHeaderTest {
    private val playback = "https://rr1---sn-abc.googlevideo.com/videoplayback"

    @Test
    fun `content range total is read case-insensitively and must be positive`() {
        assertEquals(
            1_000L,
            YouTubeGoogleVideoRangeSupport.resolveContentRangeTotal(
                mapOf("ETag" to listOf("abc"), "CONTENT-RANGE" to listOf(" bytes 0-99/1000 "))
            )
        )
        assertNull(YouTubeGoogleVideoRangeSupport.resolveContentRangeTotal(emptyMap()))
        assertNull(YouTubeGoogleVideoRangeSupport.resolveContentRangeTotal(mapOf("ETag" to listOf("abc"))))
        assertNull(YouTubeGoogleVideoRangeSupport.resolveContentRangeTotal(mapOf("content-range" to emptyList())))
        assertNull(YouTubeGoogleVideoRangeSupport.resolveContentRangeTotal(mapOf("Content-Range" to listOf("bytes 0-99/*"))))
        assertNull(YouTubeGoogleVideoRangeSupport.resolveContentRangeTotal(mapOf("Content-Range" to listOf("bytes */0"))))
        assertNull(YouTubeGoogleVideoRangeSupport.resolveContentRangeTotal(mapOf("Content-Range" to listOf("bytes 0-99"))))
    }

    @Test
    fun `seeking without refresh needs a resolved throttling or signature parameter`() {
        assertFalse(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh(playback))
        assertFalse(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh("$playback?"))
        assertFalse(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh("$playback?n=&itag=140"))
        assertTrue(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh("$playback?=orphan&n=abc"))
        assertTrue(YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh("$playback?itag=140&signature=s%3D1"))
    }
}
