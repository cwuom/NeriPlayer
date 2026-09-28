package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatsOwnerTest {
    @Test
    fun `song transition records listened time and attributed local playlist play`() = runTest {
        var now = 0L
        val writes = RecordingPort()
        val owner = PlaybackStatsOwner(backgroundScope, writes, PlaybackStatsTracker(nowElapsedMs = { now }))

        owner.onSongChanged(song(1L), localPlaylistId = 88L, writesEnabled = true)
        owner.onPlayingChanged(true, "start", writesEnabled = true)
        now = 31_000L
        owner.onSongChanged(song(2L), localPlaylistId = null, writesEnabled = true)
        runCurrent()

        assertEquals(1, writes.records.size)
        assertEquals(31_000L, writes.records.single().listenedMs)
        assertEquals(1, writes.records.single().playCountIncrement)
        assertEquals(88L, writes.records.single().localPlaylistId)
    }

    @Test
    fun `track end and progress wrap both enter ordered persistence`() = runTest {
        var now = 0L
        val writes = RecordingPort()
        val owner = PlaybackStatsOwner(backgroundScope, writes, PlaybackStatsTracker(nowElapsedMs = { now }))
        owner.onSongChanged(song(1L), null, writesEnabled = true)
        owner.onPlayingChanged(true, "start", writesEnabled = true)

        now = 30_000L
        assertFalse(owner.onProgress(59_000L, writesEnabled = true))
        assertTrue(owner.onProgress(500L, writesEnabled = true))
        owner.onManualSeek(300L)
        assertFalse(owner.onProgress(600L, writesEnabled = true))
        now = 60_000L
        owner.onTrackEnded(writesEnabled = true)
        runCurrent()

        assertEquals(listOf(1, 1), writes.records.map { it.playCountIncrement })
        assertEquals(listOf(30_000L, 30_000L), writes.records.map { it.listenedMs })
    }

    @Test
    fun `pending writes finish in order even when the next snapshot is ready`() = runTest {
        var now = 0L
        val gate = CompletableDeferred<Unit>()
        val writes = RecordingPort(firstRecordGate = gate)
        val owner = PlaybackStatsOwner(backgroundScope, writes, PlaybackStatsTracker(nowElapsedMs = { now }))
        owner.onSongChanged(song(1L), null, writesEnabled = true)
        owner.onPlayingChanged(true, "start", writesEnabled = true)

        now = 15_000L
        owner.flushPeriodic(writesEnabled = true)
        runCurrent()
        now = 30_000L
        owner.flushPeriodic(writesEnabled = true)
        runCurrent()
        assertEquals(1, writes.records.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(2, writes.records.size)
        assertEquals(listOf(0, 1), writes.records.map { it.playCountIncrement })
    }

    @Test
    fun `blocking flush writes final snapshot and repository state once`() {
        var now = 0L
        val writes = RecordingPort(pendingWrites = true)
        val owner = PlaybackStatsOwner(
            scope = CoroutineScope(Dispatchers.Unconfined),
            writes = writes,
            tracker = PlaybackStatsTracker(nowElapsedMs = { now }),
            blockForPersistence = { _, block -> runBlocking { block() } }
        )
        owner.onSongChanged(song(1L), localPlaylistId = 77L, writesEnabled = true)
        owner.onPlayingChanged(true, "start", writesEnabled = true)
        now = 35_000L

        owner.flushBlocking("pause", stopTracking = true, writesEnabled = true)
        owner.flushBlocking("pause_again", stopTracking = true, writesEnabled = true)

        assertEquals(1, writes.records.size)
        assertEquals(77L, writes.records.single().localPlaylistId)
        assertEquals(1, writes.flushCount)
    }

    @Test
    fun `release keeps scope alive until asynchronous final write completes`() = runTest {
        var now = 0L
        val gate = CompletableDeferred<Unit>()
        val writes = RecordingPort(firstRecordGate = gate)
        val scope = backgroundScope
        val owner = PlaybackStatsOwner(scope, writes, PlaybackStatsTracker(nowElapsedMs = { now }))
        owner.onSongChanged(song(1L), null, writesEnabled = true)
        owner.onPlayingChanged(true, "start", writesEnabled = true)
        now = 31_000L

        owner.flushAsync("release", stopTracking = true, writesEnabled = true)
        owner.cancelSharedScopeAfterWrites()
        runCurrent()
        assertFalse(scope.coroutineContext[Job]!!.isCancelled)
        assertEquals(1, writes.records.size)

        gate.complete(Unit)
        runCurrent()
        assertTrue(scope.coroutineContext[Job]!!.isCancelled)
    }

    @Test
    fun `uninitialized player tracks transitions without writing`() = runTest {
        var now = 0L
        val writes = RecordingPort()
        val owner = PlaybackStatsOwner(backgroundScope, writes, PlaybackStatsTracker(nowElapsedMs = { now }))
        owner.onSongChanged(song(1L), null, writesEnabled = false)
        owner.onPlayingChanged(true, "start", writesEnabled = false)
        now = 31_000L
        owner.onTrackEnded(writesEnabled = false)
        owner.flushAsync("release", stopTracking = true, writesEnabled = false)
        runCurrent()

        assertTrue(writes.records.isEmpty())
        assertEquals(0, writes.flushCount)
    }

    private class RecordingPort(
        private val firstRecordGate: CompletableDeferred<Unit>? = null,
        var pendingWrites: Boolean = false
    ) : PlaybackStatsWritePort {
        val records = mutableListOf<PlaybackStatsSnapshot>()
        var flushCount = 0

        override suspend fun record(snapshot: PlaybackStatsSnapshot) {
            records += snapshot
            if (records.size == 1) firstRecordGate?.await()
        }

        override fun hasPendingWrites(): Boolean = pendingWrites

        override suspend fun flushPendingWrites() {
            flushCount++
            pendingWrites = false
        }
    }

    private fun song(id: Long): SongItem = SongItem(
        id = id,
        name = "song $id",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null
    )
}
