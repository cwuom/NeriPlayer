package moe.ouom.neriplayer.core.player.runtime.stats

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIdentityCallbacksTest {
    @Test
    fun `injected identity keeps listening time across different song representations`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(songKey = { "shared-source" }, nowElapsedMs = { now })
        val original = song(1L)
        val downloaded = song(2L)
        tracker.onSongChanged(original)
        tracker.onPlayingChanged(true)
        now = 40_000L

        assertNull(tracker.onSongChanged(downloaded))
        val snapshot = tracker.onPlayingChanged(false)!!

        assertSame(downloaded, snapshot.song)
        assertEquals(40_000L, snapshot.listenedMs)
        assertEquals(1, snapshot.playCountIncrement)
    }

    @Test
    fun `track end fallback uses the injected source identity`() {
        assertEquals("source-key", trackEndKeyForSong(null, song(2L)) { "source-key" })
        assertEquals("media-key", trackEndKeyForSong("media-key", song(2L)) { "source-key" })
    }

    @Test
    fun `only repeated completion for a non repeat track is suppressed`() {
        assertTrue(shouldSkipDuplicateTrackEnd(false, "song", "song"))
        assertFalse(shouldSkipDuplicateTrackEnd(true, "song", "song"))
        assertFalse(shouldSkipDuplicateTrackEnd(false, null, "song"))
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song $id",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null
    )
}
