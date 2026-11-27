package moe.ouom.neriplayer.core.player.runtime.report

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformPlayReportTrackerTest {
    private var elapsed = 0L
    private val tracker = PlatformPlayReportTracker(
        songKey = { it.id.toString() },
        heartbeatIntervalMs = 15_000L,
        nowElapsedMs = { elapsed }
    )

    @Test
    fun `switching songs finishes the previous listen with only real playing time`() {
        tracker.onSongChanged(song(1))
        tracker.onPlayingChanged(true)
        advance(20_000)
        tracker.onProgress(20_000)
        tracker.onPlayingChanged(false)
        advance(60_000)
        tracker.onPlayingChanged(true)
        advance(10_000)
        tracker.onProgress(30_000)

        val finished = tracker.onSongChanged(song(2)).single() as PlatformPlayReportEvent.Finished

        assertEquals(1L, finished.song.id)
        assertEquals(30_000L, finished.listenedMs)
        assertEquals(30_000L, finished.positionMs)
        assertFalse(finished.completed)
    }

    @Test
    fun `song that never played does not produce a report`() {
        tracker.onSongChanged(song(1))
        advance(30_000)

        assertTrue(tracker.onSongChanged(song(2)).isEmpty())
    }

    @Test
    fun `auto advance near the end is reported as completed`() {
        tracker.onSongChanged(song(1, durationMs = 100_000))
        tracker.onPlayingChanged(true)
        advance(99_000)
        tracker.onProgress(99_000)

        val finished = tracker.onSongChanged(song(2)).single() as PlatformPlayReportEvent.Finished

        assertTrue(finished.completed)
    }

    @Test
    fun `repeat one wrap is reported as a finished listen and starts a new session`() {
        tracker.onSongChanged(song(1, durationMs = 60_000))
        tracker.onPlayingChanged(true)
        advance(59_000)
        tracker.onProgress(59_000)
        advance(1_500)

        val wrap = tracker.onProgress(500).single() as PlatformPlayReportEvent.Finished
        assertTrue(wrap.completed)
        assertEquals(60_500L, wrap.listenedMs)
        assertEquals(59_000L, wrap.positionMs)

        advance(20_000)
        tracker.onProgress(20_500)
        val second = tracker.onSongChanged(song(2)).single() as PlatformPlayReportEvent.Finished
        assertEquals(20_000L, second.listenedMs)
        assertFalse(second.completed)
    }

    @Test
    fun `stopping at the end of the queue settles the listen once`() {
        tracker.onSongChanged(song(1, durationMs = 60_000))
        tracker.onPlayingChanged(true)
        advance(58_500)
        tracker.onProgress(58_500)

        val finished = tracker.onPlayingChanged(false).single() as PlatformPlayReportEvent.Finished
        assertTrue(finished.completed)
        assertEquals(58_500L, finished.listenedMs)
        assertTrue(tracker.onProgress(0).isEmpty())
        assertTrue(tracker.onSongChanged(song(2)).isEmpty())
    }

    @Test
    fun `stale position from the previous track is not treated as a repeat`() {
        tracker.onSongChanged(song(1, durationMs = 40_000))
        tracker.onPlayingChanged(true)
        tracker.onProgress(39_000)
        advance(200)

        assertTrue(tracker.onProgress(0).isEmpty())
        advance(10_000)
        val finished = tracker.onSongChanged(song(2)).single() as PlatformPlayReportEvent.Finished
        assertEquals(10_200L, finished.listenedMs)
        assertFalse(finished.completed)
    }

    @Test
    fun `heartbeats follow listened time and pauses add one with the paused position`() {
        tracker.onSongChanged(song(1))
        tracker.onPlayingChanged(true)
        advance(10_000)
        assertTrue(tracker.onProgress(10_000).isEmpty())
        advance(6_000)
        val beat = tracker.onProgress(16_000).single() as PlatformPlayReportEvent.Heartbeat
        assertEquals(16_000L, beat.listenedMs)

        advance(4_000)
        tracker.onProgress(20_000)
        val paused = tracker.onPlayingChanged(false).single() as PlatformPlayReportEvent.Heartbeat
        assertEquals(20_000L, paused.listenedMs)
        assertEquals(20_000L, paused.positionMs)

        advance(30_000)
        assertTrue(tracker.onProgress(20_000).isEmpty())
        assertTrue(tracker.onPlayingChanged(true).isEmpty())
        assertTrue(tracker.onPlayingChanged(false).isEmpty())
    }

    @Test
    fun `playback state without a song never reports`() {
        assertTrue(tracker.onPlayingChanged(true).isEmpty())
        advance(30_000)
        assertTrue(tracker.onProgress(30_000).isEmpty())
        assertTrue(tracker.onPlayingChanged(false).isEmpty())
        assertTrue(tracker.onSongChanged(null).isEmpty())
    }

    @Test
    fun `metadata refresh of the same song keeps the session`() {
        tracker.onSongChanged(song(1))
        tracker.onPlayingChanged(true)
        advance(40_000)

        assertTrue(tracker.onSongChanged(song(1).copy(name = "renamed")).isEmpty())
        val finished = tracker.onSongChanged(null).single() as PlatformPlayReportEvent.Finished
        assertEquals("renamed", finished.song.name)
        assertEquals(40_000L, finished.listenedMs)
    }

    private fun advance(ms: Long) {
        elapsed += ms
    }

    private fun song(id: Long, durationMs: Long = 200_000L) = SongItem(
        id = id,
        name = "song$id",
        artist = "artist",
        album = "Netease",
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = null,
        channelId = "netease"
    )
}
