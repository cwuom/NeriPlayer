package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEndDeduplicationTest {

    @Test
    fun `same media item is treated as duplicate track end`() {
        val currentKey = trackEndDeduplicationKey(
            mediaId = "song-a|album-a",
            fallbackSongKey = "fallback-a"
        )

        assertFalse(shouldHandleTrackEnd(lastHandledKey = currentKey, currentKey = currentKey))
    }

    @Test
    fun `different media items are handled independently`() {
        val firstKey = trackEndDeduplicationKey(
            mediaId = "song-a|album-a",
            fallbackSongKey = "fallback-a"
        )
        val secondKey = trackEndDeduplicationKey(
            mediaId = "song-b|album-b",
            fallbackSongKey = "fallback-b"
        )

        assertTrue(shouldHandleTrackEnd(lastHandledKey = firstKey, currentKey = secondKey))
    }

    @Test
    fun `fallback song key is used before pending sentinel`() {
        assertEquals(
            "song-stable-key",
            trackEndDeduplicationKey(mediaId = null, fallbackSongKey = "song-stable-key")
        )
        assertEquals(
            PENDING_TRACK_END_DEDUPLICATION_KEY,
            trackEndDeduplicationKey(mediaId = null, fallbackSongKey = null)
        )
    }
}
