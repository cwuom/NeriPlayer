package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressOwnerTest {
    @Test
    fun `long form progress follows song identity and periodic persistence window`() {
        var now = 0L
        val first = song(1L, 1_800_000L)
        val port = RecordingPort().apply {
            current = first
            reportedPosition = 80_000L
            remembered = 70_000L
        }
        val owner = PlaybackProgressOwner(port) { now }
        owner.onCurrentSongPublished(first)

        assertEquals(70_000L, owner.resolveRememberedStartPosition(first, 0L, true))
        owner.persistPreviousSongProgress(first, first.copy(name = "renamed"))
        assertTrue(port.persisted.isEmpty())

        owner.persistPreviousSongProgress(first, song(2L, 1_800_000L))
        assertEquals(listOf(first.id to 80_000L), port.persisted)
        now = 15_000L
        owner.persistPeriodicLongFormProgress(90_000L, 15_000L)
        owner.persistPeriodicLongFormProgress(91_000L, 15_000L)
        assertEquals(listOf(80_000L, 90_000L), port.persisted.map { it.second })
    }

    @Test
    fun `duration backfill updates unknown queue and current song once`() {
        val current = song(1L, 0L)
        val port = RecordingPort().apply {
            this.current = current
            playerDuration = 1_200_000L
        }
        val owner = PlaybackProgressOwner(port) { 0L }

        owner.maybeBackfillCurrentSongDurationFromPlayer()

        assertEquals(1_200_000L, owner.duration.value)
        assertEquals(1_200_000L, port.current?.durationMs)
        assertEquals(1, port.queueUpdates)
        assertEquals(1, port.immediatePersists)
    }

    @Test
    fun `pending seek masks transient player position and clears on arrival`() {
        val owner = PlaybackProgressOwner(RecordingPort()) { 0L }
        owner.rememberPendingSeekPosition(60_000L)
        owner.setExpeditedYouTubeSeekRecoveryPending(true)

        assertEquals(60_000L, owner.resolveDisplayedPosition(1_000L))
        assertEquals(60_100L, owner.resolveDisplayedPosition(60_100L))
        assertNull(owner.pendingSeekPositionOrNull())
        assertFalse(owner.expeditedYouTubeSeekRecoveryPending)
    }

    @Test
    fun `pending media load exposes requested position before seek confirmation`() {
        val port = RecordingPort().apply {
            pendingLoad = true
            pendingLoadPosition = 42_000L
        }
        val owner = PlaybackProgressOwner(port) { 0L }
        owner.rememberPendingSeekPosition(60_000L)

        assertEquals(42_000L, owner.resolveDisplayedPosition(0L))
        assertEquals(60_000L, owner.pendingSeekPositionOrNull())
    }

    @Test
    fun `progress stats clock starts immediately and throttles subsequent ticks`() {
        var now = 1_000L
        val owner = PlaybackProgressOwner(RecordingPort()) { now }

        assertTrue(owner.shouldRecordStats(5_000L))
        now += 4_999L
        assertFalse(owner.shouldRecordStats(5_000L))
        now += 1L
        assertTrue(owner.shouldRecordStats(5_000L))
        owner.resetStatsClock()
        assertTrue(owner.shouldRecordStats(5_000L))
    }

    @Test
    fun `pause duration and short local flush use known positive duration`() {
        val owner = PlaybackProgressOwner(RecordingPort()) { 0L }
        assertEquals(5_000L, owner.resolveExpectedPauseDuration(5_000L, 10_000L))
        assertEquals(10_000L, owner.resolveExpectedPauseDuration(0L, 10_000L))
        assertEquals(10_000L, owner.resolveExpectedPauseDuration(null, 10_000L))
        assertTrue(owner.shouldFlushShortLocalSong(1L))
        assertTrue(owner.shouldFlushShortLocalSong(5_000L))
        assertFalse(owner.shouldFlushShortLocalSong(0L))
        assertFalse(owner.shouldFlushShortLocalSong(5_001L))
    }

    @Test
    fun `duration reconciliation skips invalid and unrelated songs`() {
        val first = song(1L, 0L)
        val port = RecordingPort().apply { current = first; queuedChanged = false }
        val owner = PlaybackProgressOwner(port) { 0L }
        owner.maybeUpdateSongDuration(first, 0L)
        assertEquals(0, port.queueUpdates)
        owner.maybeUpdateSongDuration(song(2L, 0L), 90_000L)
        assertEquals(0L, owner.duration.value)
        assertEquals(0, port.immediatePersists)
        owner.maybeUpdateSongDuration(first, 90_000L)
        assertEquals(90_000L, owner.duration.value)
        assertEquals(90_000L, port.current?.durationMs)
        assertEquals(1, port.immediatePersists)
        owner.maybeUpdateSongDuration(first, 100_000L)
        assertEquals(100_000L, owner.duration.value)
        assertEquals(1, port.immediatePersists)
    }

    @Test
    fun `backfill and current progress honor missing player and long form preference`() {
        val first = song(1L, 1_800_000L)
        val port = RecordingPort()
        val owner = PlaybackProgressOwner(port) { 20_000L }
        owner.maybeBackfillCurrentSongDurationFromPlayer()
        owner.persistCurrentLongFormProgress()
        assertTrue(port.persisted.isEmpty())
        port.current = first
        owner.maybeBackfillCurrentSongDurationFromPlayer()
        assertEquals(0, port.queueUpdates)
        port.playerDuration = 1_900_000L
        owner.maybeBackfillCurrentSongDurationFromPlayer()
        assertEquals(1_900_000L, owner.duration.value)
        port.reportedPosition = 50_000L
        owner.persistCurrentLongFormProgress()
        assertEquals(listOf(first.id to 50_000L), port.persisted)
        port.rememberEnabled = false
        owner.persistCurrentLongFormProgress()
        assertEquals(1, port.persisted.size)
        assertEquals(4_000L, owner.resolveRememberedStartPosition(first, 4_000L, false))
        assertEquals(4_000L, owner.resolveRememberedStartPosition(first, 4_000L, true))
    }

    @Test
    fun `periodic long form persistence skips short song and disabled preference`() {
        var now = 10_000L
        val port = RecordingPort().apply { current = song(1L, 30_000L) }
        val owner = PlaybackProgressOwner(port) { now }
        owner.persistPeriodicLongFormProgress(10_000L, 5_000L)
        assertTrue(port.persisted.isEmpty())
        port.current = song(2L, 1_800_000L)
        port.rememberEnabled = false
        owner.persistPeriodicLongFormProgress(10_000L, 5_000L)
        assertTrue(port.persisted.isEmpty())
        port.rememberEnabled = true
        owner.persistPeriodicLongFormProgress(10_000L, 5_000L)
        assertEquals(1, port.persisted.size)
        now += 4_999L
        owner.persistPeriodicLongFormProgress(11_000L, 5_000L)
        assertEquals(1, port.persisted.size)
    }

    private fun song(id: Long, durationMs: Long) = SongItem(
        id = id,
        name = "song $id",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = durationMs,
        coverUrl = null
    )

    private class RecordingPort : PlaybackProgressPort {
        var current: SongItem? = null
        var reportedPosition = 0L
        var playerDuration: Long? = null
        var remembered = 0L
        var rememberEnabled = true
        var queuedChanged = true
        var pendingLoad = false
        var pendingLoadPosition = 0L
        var queueUpdates = 0
        var immediatePersists = 0
        val persisted = mutableListOf<Pair<Long, Long>>()
        override fun rememberLongFormEnabled(): Boolean = rememberEnabled
        override fun rememberedPosition(song: SongItem): Long = remembered
        override fun writeRememberedPosition(song: SongItem, positionMs: Long) {
            persisted += song.id to positionMs
        }
        override fun currentSong(): SongItem? = current
        override fun reportedPositionMs(): Long = reportedPosition
        override fun playerPositionMs(): Long? = reportedPosition
        override fun playerDurationMs(): Long? = playerDuration
        override fun updateQueuedDurationIfUnknown(song: SongItem, durationMs: Long): Boolean {
            queueUpdates++
            return queuedChanged
        }
        override fun replaceCurrentSong(song: SongItem) { current = song }
        override fun scheduleImmediateStatePersist() { immediatePersists++ }
        override fun pendingMediaLoadActive(): Boolean = pendingLoad
        override fun pendingMediaLoadPositionMs(): Long = pendingLoadPosition
    }
}
