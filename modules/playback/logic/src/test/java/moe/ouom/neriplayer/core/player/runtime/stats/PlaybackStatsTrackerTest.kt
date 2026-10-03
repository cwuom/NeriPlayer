package moe.ouom.neriplayer.core.player.runtime.stats


import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStatsTrackerTest {
    @Test
    fun `track ending while a clear is observed cannot create a new epoch phantom play`() {
        var elapsed = 0L
        var clear = 0L
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { elapsed }, readClearedAt = { clear })
        tracker.onSongChanged(testSong(1, "song"))
        tracker.onPlayingChanged(true)
        elapsed = 30_000
        clear = 2000
        assertNull(tracker.onTrackEnded())
        elapsed = 45_000
        assertEquals(15_000L, tracker.flushPeriodic()!!.listenedMs)
    }

    @Test
    fun `new playback carries its observed clear epoch even if the device wall clock is behind`() {
        var elapsed = 0L
        val clear = System.currentTimeMillis() + 86_400_000
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { elapsed }, readClearedAt = { clear })
        tracker.onSongChanged(testSong(1, "song"))
        tracker.onPlayingChanged(true)
        elapsed = 30_000
        val snapshot = tracker.flushPeriodic()!!
        assertEquals(clear, snapshot.observedClearedAt)
        assertEquals(30_000L, snapshot.listenedMs)
        assertEquals(1, snapshot.playCountIncrement)
    }

    @Test
    fun `observing a clear drops the mixed segment and starts a new count threshold`() {
        var elapsed = 0L
        var clear = 0L
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { elapsed }, readClearedAt = { clear })
        tracker.onSongChanged(testSong(1, "song"))
        tracker.onPlayingChanged(true)
        elapsed = 30_000
        val beforeClear = tracker.flushPeriodic()!!
        elapsed = 40_000
        clear = 2000
        assertEquals(false, tracker.shouldFlushPeriodically())
        assertNull(tracker.flushPeriodic())
        elapsed = 55_000
        val fresh = tracker.flushPeriodic()!!
        assertEquals(15_000L, fresh.listenedMs)
        assertEquals(0, fresh.playCountIncrement)
        assertEquals(2000L, fresh.observedClearedAt)
        assertEquals(0L, beforeClear.observedClearedAt)
        elapsed = 70_000
        assertEquals(1, tracker.flushPeriodic()!!.playCountIncrement)
    }

    @Test
    fun `periodic flush eligibility requires active tracking and the elapsed interval`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
        assertEquals(false, tracker.shouldFlushPeriodically())
        tracker.onPlayingChanged(true)
        assertEquals(false, tracker.shouldFlushPeriodically())
        tracker.onSongChanged(testSong(1L, "song"))
        tracker.onPlayingChanged(true)
        now = PLAYBACK_STATS_PERIODIC_FLUSH_MS - 1L
        assertEquals(false, tracker.shouldFlushPeriodically())
        now++
        assertEquals(true, tracker.shouldFlushPeriodically())

        val disabled = PlaybackStatsTracker(songKey = { it.id.toString() }, periodicFlushMs = 0L, nowElapsedMs = { now })
        disabled.onSongChanged(testSong(1L, "song"))
        disabled.onPlayingChanged(true)
        assertEquals(false, disabled.shouldFlushPeriodically())
    }

    @Test
    fun `backward progress away from the end and unknown durations do not count a repeat`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
        tracker.onSongChanged(testSong(1L, "song"))
        tracker.onPlayingChanged(true)
        tracker.onPlaybackProgress(10_000L)
        now = 20_000L
        assertNull(tracker.onPlaybackProgress(300L))
        assertNull(tracker.onPlaybackProgress(1_000L))
        tracker.onSongChanged(testSong(2L, "unknown").copy(durationMs = 0L))
        assertNull(tracker.onPlaybackProgress(50_000L))
        assertNull(tracker.onPlaybackProgress(0L))
    }

    @Test
    fun `periodic flush keeps current play count pending until threshold`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
    fun `counted local playlist playback keeps the playlist source id`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })

        tracker.onSongChanged(
            song = testSong(id = 20L, name = "local"),
            localPlaylistId = 88L
        )
        tracker.onPlayingChanged(true)

        now = 30_000L
        val snapshot = tracker.flushPeriodic()

        assertEquals(1, snapshot?.playCountIncrement)
        assertEquals(88L, snapshot?.localPlaylistId)
    }

    @Test
    fun `track end counts repeat one cycles independently`() {
        var now = 0L
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
        val tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now })
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
