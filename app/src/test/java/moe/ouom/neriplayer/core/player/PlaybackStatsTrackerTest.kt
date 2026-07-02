package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStatsTrackerTest {

    @Test
    fun `periodic flush keeps current play count pending until threshold`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val song = testSong(id = 1L, name = "a")

        assertNull(tracker.onSongChanged(song))
        assertNull(tracker.onPlayingChanged(true))

        now = 15_000L
        val snapshot = tracker.flushPeriodic()

        assertNotNull(snapshot)
        assertEquals(15_000L, snapshot?.listenedMs)
        assertEquals(0, snapshot?.playCountIncrement)
        assertEquals(false, snapshot?.scheduleSync)
    }

    @Test
    fun `single play is counted once after listen threshold despite periodic flush`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val song = testSong(id = 2L, name = "b")

        tracker.onSongChanged(song)
        tracker.onPlayingChanged(true)

        now = 15_000L
        tracker.flushPeriodic()

        now = 30_000L
        val counted = tracker.flushPeriodic()

        assertEquals(15_000L, counted?.listenedMs)
        assertEquals(1, counted?.playCountIncrement)

        now = 45_000L
        val followUp = tracker.flushPeriodic()

        assertEquals(15_000L, followUp?.listenedMs)
        assertEquals(0, followUp?.playCountIncrement)
    }

    @Test
    fun `track end counts repeat one cycles independently`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val song = testSong(id = 3L, name = "c")

        tracker.onSongChanged(song)
        tracker.onPlayingChanged(true)

        now = 42_000L
        val firstEnd = tracker.onTrackEnded()

        assertEquals(42_000L, firstEnd?.listenedMs)
        assertEquals(1, firstEnd?.playCountIncrement)

        now = 84_000L
        val secondEnd = tracker.onTrackEnded()

        assertEquals(42_000L, secondEnd?.listenedMs)
        assertEquals(1, secondEnd?.playCountIncrement)
    }

    @Test
    fun `pause flushes current listened time`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val song = testSong(id = 4L, name = "d")

        tracker.onSongChanged(song)
        tracker.onPlayingChanged(true)

        now = 5_000L
        val snapshot = tracker.onPlayingChanged(false)

        assertEquals(5_000L, snapshot?.listenedMs)
        assertEquals(0, snapshot?.playCountIncrement)
        assertEquals(true, snapshot?.scheduleSync)
    }

    @Test
    fun `position wrap counts repeat one when ended callback is swallowed`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val song = testSong(id = 5L, name = "e")

        tracker.onSongChanged(song)
        tracker.onPlayingChanged(true)
        tracker.onPlaybackProgress(59_000L)

        now = 60_000L
        val snapshot = tracker.onPlaybackProgress(300L)

        assertEquals(60_000L, snapshot?.listenedMs)
        assertEquals(1, snapshot?.playCountIncrement)
    }

    @Test
    fun `song change waits for actual playing before counting new song`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val firstSong = testSong(id = 6L, name = "f")
        val secondSong = testSong(id = 7L, name = "g")

        tracker.onSongChanged(firstSong)
        tracker.onPlayingChanged(true)

        now = 10_000L
        val firstSnapshot = tracker.onSongChanged(secondSong)

        assertEquals(10_000L, firstSnapshot?.listenedMs)

        now = 40_000L
        assertNull(tracker.flushPeriodic())

        tracker.onPlayingChanged(false)
        tracker.onPlayingChanged(true)

        now = 55_000L
        val secondSnapshot = tracker.flushPeriodic()

        assertEquals(15_000L, secondSnapshot?.listenedMs)
        assertEquals(0, secondSnapshot?.playCountIncrement)
    }

    @Test
    fun `auto next starts new segment when false callback is swallowed`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val firstSong = testSong(id = 8L, name = "h")
        val secondSong = testSong(id = 9L, name = "i")

        tracker.onSongChanged(firstSong)
        tracker.onPlayingChanged(true)

        now = 60_000L
        val firstEnd = tracker.onTrackEnded()

        assertEquals(60_000L, firstEnd?.listenedMs)
        assertEquals(1, firstEnd?.playCountIncrement)

        tracker.onSongChanged(secondSong)

        now = 61_000L
        assertNull(tracker.onPlayingChanged(true))

        now = 76_000L
        val secondSnapshot = tracker.flushPeriodic()

        assertEquals(15_000L, secondSnapshot?.listenedMs)
        assertEquals(0, secondSnapshot?.playCountIncrement)
    }

    @Test
    fun `manual seek back to beginning does not count as repeat cycle`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(nowElapsedMs = { now })
        val song = testSong(id = 10L, name = "j")

        tracker.onSongChanged(song)
        tracker.onPlayingChanged(true)
        tracker.onPlaybackProgress(59_000L)
        tracker.onManualSeek(300L)

        now = 60_000L
        val snapshot = tracker.onPlaybackProgress(500L)

        assertNull(snapshot)
    }

    private fun testSong(
        id: Long,
        name: String
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null
        )
    }
}
