package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.data.identity.stableKey

import moe.ouom.neriplayer.core.player.playback.PENDING_TRACK_END_DEDUPLICATION_KEY
import moe.ouom.neriplayer.core.player.playback.shouldHandleTrackEnd
import moe.ouom.neriplayer.core.player.playback.shouldSkipDuplicateTrackEnd
import moe.ouom.neriplayer.core.player.playback.trackEndDeduplicationKey
import moe.ouom.neriplayer.core.player.playback.trackEndKeyForSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
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

    @Test
    fun `media id takes precedence over the current song fallback`() {
        val song = SongItem(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 60_000L,
            coverUrl = null
        )

        assertEquals("media-id", trackEndKeyForSong("media-id", song))
        assertEquals(song.stableKey(), trackEndKeyForSong(null, song))
        assertEquals(PENDING_TRACK_END_DEDUPLICATION_KEY, trackEndKeyForSong(null, null))
    }

    @Test
    fun `repeat one can report the same key while ordinary playback suppresses it`() {
        assertTrue(shouldSkipDuplicateTrackEnd(false, "same", "same"))
        assertFalse(shouldSkipDuplicateTrackEnd(true, "same", "same"))
        assertFalse(shouldSkipDuplicateTrackEnd(false, "previous", "next"))
    }
}
