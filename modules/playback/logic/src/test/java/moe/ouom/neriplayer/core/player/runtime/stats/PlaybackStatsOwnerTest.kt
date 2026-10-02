package moe.ouom.neriplayer.core.player.runtime.stats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import java.io.IOException
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatsOwnerTest {
    @Test
    fun `a hung database write keeps every later playback delta with bounded IO workers`() = runTest {
        var now = 0L
        val gate = CompletableDeferred<Unit>()
        val accepted = mutableListOf<PlaybackStatsSnapshot>()
        val persistenceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val writes = object : PlaybackStatsWritePort {
            override suspend fun record(snapshot: PlaybackStatsSnapshot) {
                gate.await()
                accepted += snapshot
            }
            override fun hasPendingWrites() = false
            override suspend fun flushPendingWrites() = Unit
        }
        val owner = owner(persistenceScope, writes,
            PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }))
        try {
            owner.onSongChanged(song(1), 42, true)
            owner.onPlayingChanged(true, "start", true)
            repeat(600) {
                now += 15_000
                owner.flushPeriodic(true)
                runCurrent()
            }
            assertTrue(persistenceScope.coroutineContext[Job]!!.children.count { !it.isCompleted } in 1..2)
            gate.complete(Unit)
            runCurrent()
            assertEquals(600, accepted.size)
            assertEquals(600, accepted.map { it.eventId }.toSet().size)
            assertEquals(9_000_000L, accepted.sumOf { it.listenedMs })
            assertTrue(accepted.all { it.localPlaylistId == 42L })
        } finally {
            persistenceScope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `more than a receipt window of later snapshots cannot bypass an uncertain first event`() = runTest {
        var now = 0L
        var blocked = true
        val attempts = mutableListOf<PlaybackStatsSnapshot>()
        val accepted = mutableListOf<PlaybackStatsSnapshot>()
        val writes = object : PlaybackStatsWritePort {
            override suspend fun record(snapshot: PlaybackStatsSnapshot) {
                attempts += snapshot
                if (blocked) throw IOException("playlist commit acknowledgement unavailable")
                accepted += snapshot
            }
            override fun hasPendingWrites() = false
            override suspend fun flushPendingWrites() = Unit
        }
        val owner = owner(backgroundScope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }),
            blockForPersistence = { _, block -> runBlocking { block() } })
        owner.onSongChanged(song(1), 42, true)
        owner.onPlayingChanged(true, "start", true)
        repeat(300) {
            now += 30_000
            owner.onTrackEnded(true)
            runCurrent()
        }
        assertEquals(300, attempts.size)
        assertEquals(1, attempts.map { it.eventId }.toSet().size)
        assertTrue(accepted.isEmpty())

        blocked = false
        owner.drainBlocking("recovered", true)
        assertEquals(300, accepted.size)
        assertEquals(300, accepted.map { it.eventId }.toSet().size)
        assertSame(attempts.first(), accepted.first())
    }

    @Test
    fun `blocking drain retries an unaccepted delta after its asynchronous job completed`() = runTest {
        var now = 0L
        var unavailable = true
        val accepted = mutableListOf<PlaybackStatsSnapshot>()
        var flushes = 0
        val writes = object : PlaybackStatsWritePort {
            override suspend fun record(snapshot: PlaybackStatsSnapshot) {
                if (unavailable) throw IOException("journal unavailable")
                accepted += snapshot
            }
            override fun hasPendingWrites() = false
            override suspend fun flushPendingWrites() { flushes++ }
        }
        val owner = owner(
            backgroundScope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }),
            blockForPersistence = { _, block -> runBlocking { block() } }
        )
        owner.onSongChanged(song(1), null, true)
        owner.onPlayingChanged(true, "start", true)
        now = 30_000
        owner.flushPeriodic(true)
        runCurrent()
        unavailable = false
        owner.drainBlocking("recovered", true)
        owner.drainBlocking("already_done", true)
        assertEquals(1, accepted.size)
        assertEquals(30_000L, accepted.single().listenedMs)
        assertEquals(1, flushes)
    }

    @Test
    fun `unaccepted playback delta is retained and retried before the next delta`() = runTest {
        var now = 0L
        var unavailable = true
        val attempts = mutableListOf<PlaybackStatsSnapshot>()
        val accepted = mutableListOf<PlaybackStatsSnapshot>()
        val writes = object : PlaybackStatsWritePort {
            override suspend fun record(snapshot: PlaybackStatsSnapshot) {
                attempts += snapshot
                if (unavailable) throw IOException("journal unavailable")
                accepted += snapshot
            }
            override fun hasPendingWrites() = false
            override suspend fun flushPendingWrites() = Unit
        }
        val owner = owner(backgroundScope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }))
        owner.onSongChanged(song(1), null, true)
        owner.onPlayingChanged(true, "start", true)
        now = 30_000
        owner.flushPeriodic(true)
        runCurrent()
        assertEquals(1, attempts.size)
        assertTrue(accepted.isEmpty())
        unavailable = false
        now = 60_000
        owner.onTrackEnded(true)
        runCurrent()

        assertEquals(2, accepted.size)
        assertSame(attempts.first(), accepted.first())
        assertEquals(listOf(30_000L, 30_000L), accepted.map { it.listenedMs })
        assertEquals(listOf(1, 0), accepted.map { it.playCountIncrement })
    }

    @Test
    fun `song transition records listened time and attributed local playlist play`() = runTest {
        var now = 0L
        val writes = RecordingPort()
        val owner = owner(backgroundScope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }))

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
        val owner = owner(backgroundScope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }))
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
        val owner = owner(backgroundScope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }))
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
        val owner = owner(
            scope = CoroutineScope(Dispatchers.Unconfined),
            writes = writes,
            tracker = PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }),
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
    fun `release cancels player scope while the independent journal finishes its final write`() = runTest {
        var now = 0L
        val gate = CompletableDeferred<Unit>()
        val writes = RecordingPort(firstRecordGate = gate)
        val scope = backgroundScope
        val persistenceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), persistenceScope)
        val owner = owner(scope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }), pending = pending)
        owner.onSongChanged(song(1L), null, writesEnabled = true)
        owner.onPlayingChanged(true, "start", writesEnabled = true)
        now = 31_000L

        owner.flushAsync("release", stopTracking = true, writesEnabled = true)
        owner.cancelSharedScopeAfterWrites()
        runCurrent()
        assertTrue(scope.coroutineContext[Job]!!.isCancelled)
        assertEquals(1, writes.records.size)

        gate.complete(Unit)
        runCurrent()
        assertTrue(scope.coroutineContext[Job]!!.isCancelled)
        assertFalse(pending.hasPendingWork())
        persistenceScope.cancel()
    }

    @Test
    fun `uninitialized player tracks transitions without writing`() = runTest {
        var now = 0L
        val writes = RecordingPort()
        val owner = owner(backgroundScope, writes, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }))
        owner.onSongChanged(song(1L), null, writesEnabled = false)
        owner.onPlayingChanged(true, "start", writesEnabled = false)
        now = 31_000L
        owner.onTrackEnded(writesEnabled = false)
        owner.flushAsync("release", stopTracking = true, writesEnabled = false)
        runCurrent()

        assertTrue(writes.records.isEmpty())
        assertEquals(0, writes.flushCount)
    }

    @Test
    fun `same playback cycle keeps its counted flag after storage collection resumes`() = runTest {
        val fixture = suspendedFixture()
        fixture.start()
        fixture.now = 30_000
        fixture.owner.flushPeriodic(true)
        runCurrent()
        fixture.now = 35_000
        fixture.owner.onProgress(35_000, true)
        fixture.store.unavailable = false
        runCurrent()
        fixture.now = 60_000
        fixture.owner.onProgress(35_000, true)
        fixture.now = 90_000
        fixture.owner.flushPeriodic(true)
        runCurrent()

        assertEquals(1, fixture.port.records.sumOf { it.playCountIncrement })
        assertEquals(65_000L, fixture.port.records.sumOf { it.listenedMs })
    }

    @Test
    fun `same playback cycle retains its uncounted listening threshold without the stopped interval`() = runTest {
        val fixture = suspendedFixture()
        fixture.start()
        fixture.now = 15_000
        fixture.owner.flushPeriodic(true)
        runCurrent()
        fixture.owner.onProgress(15_000, true)
        fixture.store.unavailable = false
        runCurrent()
        fixture.now = 60_000
        fixture.owner.onProgress(15_000, true)
        fixture.now = 75_000
        fixture.owner.flushPeriodic(true)
        runCurrent()

        assertEquals(listOf(0, 1), fixture.port.records.map { it.playCountIncrement })
        assertEquals(30_000L, fixture.port.records.sumOf { it.listenedMs })
    }

    @Test
    fun `track end and a song round trip during suspended collection start a new real cycle`() = runTest {
        for (changeSong in listOf(false, true)) {
            val fixture = suspendedFixture()
            fixture.start()
            fixture.now = 30_000
            fixture.owner.flushPeriodic(true)
            runCurrent()
            fixture.owner.onProgress(30_000, true)
            if (changeSong) {
                fixture.owner.onSongChanged(song(2), 42, true)
                fixture.owner.onSongChanged(song(1), 42, true)
            } else fixture.owner.onTrackEnded(true)
            fixture.store.unavailable = false
            runCurrent()
            fixture.now = 60_000
            fixture.owner.onProgress(0, true)
            fixture.now = 90_000
            fixture.owner.flushPeriodic(true)
            runCurrent()

            assertEquals(listOf(1, 1), fixture.port.records.map { it.playCountIncrement })
            assertEquals(60_000L, fixture.port.records.sumOf { it.listenedMs })
        }
    }

    @Test
    fun `blocked staging stops sampling at the shared bound and retired owners cannot add more events`() = runTest {
        var now = 0L
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), backgroundScope)
        val old = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }), pending = pending)
        old.onSongChanged(song(1).copy(matchedLyric = "large lyric".repeat(1000)), 42, true)
        old.onPlayingChanged(true, "start", true)
        repeat(600) { now += 15_000; old.flushPeriodic(true) }
        assertEquals(PlaybackStatsPendingWrites.HANDOFF_CAPACITY, pending.inMemoryEventCount)
        val replacement = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }), pending = pending)
        replacement.onSongChanged(song(2), 43, true)
        replacement.onPlayingChanged(true, "new", true)
        repeat(600) { now += 15_000; old.onTrackEnded(true); replacement.flushPeriodic(true) }
        assertTrue(pending.inMemoryEventCount <= PlaybackStatsPendingWrites.MAX_CAPTURED_EVENTS)
        runCurrent()
        assertEquals(32, port.records.size)
        assertTrue(port.records.all { it.song.id == 1L && it.song.matchedLyric == null })
        replacement.onProgress(0, true)
        now += 30_000
        replacement.flushPeriodic(true)
        runCurrent()
        assertEquals(2L, port.records.last().song.id)
        assertEquals(43L, port.records.last().localPlaylistId)
        assertEquals(30_000L, port.records.last().listenedMs)
    }

    @Test
    fun `a seek does not invent a suspended cycle and a changed clear fence starts fresh timing`() = runTest {
        var now = 0L
        var clearedAt = 0L
        val store = FakePendingStore().apply { unavailable = true }
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(store, backgroundScope)
        val owner = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() },
            nowElapsedMs = { now }, readClearedAt = { clearedAt }), pending = pending)
        owner.onSongChanged(song(1), 42, true)
        owner.onPlayingChanged(true, "start", true)
        now = 30_000
        owner.flushPeriodic(true)
        runCurrent()
        owner.onProgress(59_000, true)
        owner.onManualSeek(0)
        owner.onProgress(0, true)
        store.unavailable = false
        runCurrent()
        now = 60_000
        owner.onProgress(0, true)
        now = 90_000
        owner.flushPeriodic(true)
        runCurrent()
        assertEquals(1, port.records.sumOf { it.playCountIncrement })
        clearedAt = 100
        owner.onProgress(0, true)
        now = 120_000
        owner.flushPeriodic(true)
        runCurrent()
        now = 150_000
        owner.flushPeriodic(true)
        runCurrent()
        assertEquals(2, port.records.sumOf { it.playCountIncrement })
        assertEquals(listOf(0L, 0L, 100L), port.records.map { it.observedClearedAt })
        assertEquals(90_000L, port.records.sumOf { it.listenedMs })
    }

    @Test
    fun `force restore freezes sampling across apply and rebases the next event without resetting its cycle`() = runTest {
        var now = 0L
        var clearedAt = 100L
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), backgroundScope)
        val owner = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() },
            nowElapsedMs = { now }, readClearedAt = { clearedAt }), pending = pending)
        owner.onSongChanged(song(1), 42, true)
        owner.onPlayingChanged(true, "start", true)
        now = 30_000
        owner.flushPeriodic(true)
        runCurrent()
        pending.withStatisticsRestore(port) {
            owner.onPlayingChanged(true, "preloaded during restore", false)
            now = 45_000
            owner.flushPeriodic(true)
            runCurrent()
            assertEquals(1, port.records.size)
            clearedAt = 0
        }
        now = 60_000
        owner.onProgress(0, true)
        now = 75_000
        owner.flushPeriodic(true)
        runCurrent()
        assertEquals(listOf(100L, 0L), port.records.map { it.observedClearedAt })
        assertEquals(listOf(1, 0), port.records.map { it.playCountIncrement })
        assertEquals(listOf(30_000L, 15_000L), port.records.map { it.listenedMs })
    }

    @Test
    fun `failed restore staging preserves the sampled threshold and never runs apply`() = runTest {
        val fixture = suspendedFixture()
        val pending = PlaybackStatsPendingWrites(fixture.store, backgroundScope)
        fixture.owner = owner(backgroundScope, fixture.port,
            PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { fixture.now }), pending = pending)
        fixture.start()
        fixture.now = 15_000
        var applied = false
        try {
            pending.withStatisticsRestore(fixture.port) { applied = true }
            error("the journal failure must abort restore")
        } catch (expected: IOException) {
            assertFalse(applied)
        }
        fixture.store.unavailable = false
        fixture.owner.onProgress(0, true)
        runCurrent()
        fixture.now = 60_000
        fixture.owner.onProgress(0, true)
        fixture.now = 75_000
        fixture.owner.flushPeriodic(true)
        runCurrent()
        assertEquals(listOf(15_000L, 15_000L), fixture.port.records.map { it.listenedMs })
        assertEquals(listOf(0, 1), fixture.port.records.map { it.playCountIncrement })
    }

    @Test
    fun `commit followed by cancellation still rebases the live owner to the published fence`() = runTest {
        var now = 0L
        var clearedAt = 100L
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), backgroundScope)
        val owner = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() },
            nowElapsedMs = { now }, readClearedAt = { clearedAt }), pending = pending)
        owner.onSongChanged(song(1), 42, true)
        owner.onPlayingChanged(true, "start", true)
        now = 30_000
        owner.flushPeriodic(true)
        runCurrent()
        try {
            pending.withStatisticsRestore(port) { clearedAt = 0; throw CancellationException("committed") }
            error("expected cancellation")
        } catch (expected: CancellationException) { assertTrue(pending.canCollect) }
        now = 60_000
        owner.onProgress(0, true)
        now = 75_000
        owner.flushPeriodic(true)
        runCurrent()
        assertEquals(listOf(100L, 0L), port.records.map { it.observedClearedAt })
        assertEquals(listOf(1, 0), port.records.map { it.playCountIncrement })
    }

    @Test
    fun `owner replacement during restore only resumes the current song and rejects retired callbacks`() = runTest {
        var now = 0L
        var clearedAt = 100L
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), backgroundScope)
        fun newOwner() = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() },
            nowElapsedMs = { now }, readClearedAt = { clearedAt }), pending = pending)
        val retired = newOwner()
        retired.onSongChanged(song(1), 42, true)
        retired.onPlayingChanged(true, "start", true)
        now = 30_000
        retired.flushPeriodic(true)
        runCurrent()
        lateinit var replacement: PlaybackStatsOwner
        pending.withStatisticsRestore(port) {
            replacement = newOwner()
            replacement.onSongChanged(song(2), 43, true)
            replacement.onPlayingChanged(true, "replacement", true)
            now = 45_000
            replacement.onTrackEnded(true)
            replacement.onPlayingChanged(true, "preloaded replacement", false)
            retired.onTrackEnded(true)
            runCurrent()
            assertEquals(1, port.records.size)
            clearedAt = 0
        }
        now = 60_000
        replacement.onProgress(0, true)
        now = 90_000
        replacement.flushPeriodic(true)
        retired.onTrackEnded(true)
        runCurrent()
        assertEquals(listOf(1L, 2L), port.records.map { it.song.id })
        assertEquals(listOf(42L, 43L), port.records.map { it.localPlaylistId })
        assertEquals(0L, port.records.last().observedClearedAt)
        assertEquals(30_000L, port.records.last().listenedMs)
    }

    @Test
    fun `nested restores share one frozen session and a different persistence port is rejected`() = runTest {
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), backgroundScope)
        var restored = false
        withTimeout(1_000) {
            pending.withStatisticsRestore(port) {
                assertFalse(pending.canCollect)
                pending.withStatisticsRestore(port) { restored = true }
            }
        }
        assertTrue(restored)
        assertTrue(pending.canCollect)
        assertEquals(1, port.flushCount)
        assertThrows(IllegalStateException::class.java) { pending.activate(RecordingPort()) }
    }

    @Test
    fun `concurrent restores stay serial and a newer ordinary clear still resets the counted cycle`() = runTest {
        var now = 0L
        var clearedAt = 100L
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), backgroundScope)
        val owner = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() },
            nowElapsedMs = { now }, readClearedAt = { clearedAt }), pending = pending)
        owner.onSongChanged(song(1), 42, true)
        owner.onPlayingChanged(true, "start", true)
        now = 30_000
        owner.flushPeriodic(true)
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val first = backgroundScope.launch {
            pending.withStatisticsRestore(port) { order += 1; entered.complete(Unit); finish.await(); clearedAt = 0 }
        }
        runCurrent()
        entered.await()
        val second = backgroundScope.launch {
            pending.withStatisticsRestore(port) { order += 2; clearedAt = 200 }
        }
        runCurrent()
        assertEquals(listOf(1), order)
        assertFalse(pending.canCollect)
        finish.complete(Unit)
        runCurrent()
        first.join()
        second.join()
        assertEquals(listOf(1, 2), order)
        now = 60_000
        owner.onProgress(0, true)
        now = 90_000
        owner.flushPeriodic(true)
        runCurrent()
        assertEquals(listOf(100L, 200L), port.records.map { it.observedClearedAt })
        assertEquals(listOf(1, 1), port.records.map { it.playCountIncrement })
        assertEquals(listOf(30_000L, 30_000L), port.records.map { it.listenedMs })
    }

    @Test
    fun `activation replays a stored prefix and stays pending until handoff and drain both finish`() = runTest {
        val store = FakePendingStore()
        val prefix = PlaybackStatsSnapshot(song(1), 15_000, 0, false, 42, "stored-prefix")
        store.append(prefix)
        val pending = PlaybackStatsPendingWrites(store, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val port = RecordingPort(firstRecordGate = gate)

        assertFalse(pending.hasPendingWork())
        assertEquals(prefix, store.first())
        pending.activate(port)
        assertTrue(pending.hasPendingWork())
        runCurrent()
        assertTrue(pending.hasPendingWork())
        assertEquals(listOf(prefix), port.records)
        val later = prefix.copy(eventId = "new-handoff", listenedMs = 20_000)
        pending.enqueue(later)
        assertTrue(pending.hasPendingWork())
        runCurrent()
        assertTrue(pending.hasPendingWork())
        assertEquals(listOf(prefix), port.records)

        gate.complete(Unit)
        runCurrent()
        assertFalse(pending.hasPendingWork())
        assertEquals(listOf(prefix, later.copy(song = later.song.forPlaybackStatistics())), port.records)
        assertEquals(null, store.first())
    }

    @Test
    fun `replacing a running owner captures its final event once and grants the new owner exclusive sampling`() = runTest {
        var now = 0L
        val port = RecordingPort()
        val pending = PlaybackStatsPendingWrites(FakePendingStore(), backgroundScope)
        fun newOwner() = owner(backgroundScope, port, PlaybackStatsTracker(songKey = { it.id.toString() },
            nowElapsedMs = { now }), pending = pending)
        val old = newOwner()
        old.onSongChanged(song(1), 42, true)
        old.onPlayingChanged(true, "old", true)
        now = 31_000
        val replacement = newOwner()
        assertTrue(pending.hasPendingWork())
        replacement.onSongChanged(song(2), 43, true)
        replacement.onPlayingChanged(true, "replacement", true)
        old.onTrackEnded(true)
        old.flushAsync("late old release", true, true)
        runCurrent()
        assertEquals(1, port.records.size)
        assertEquals(31_000L, port.records.single().listenedMs)
        assertEquals(1, port.records.single().playCountIncrement)
        assertEquals(42L, port.records.single().localPlaylistId)
        now = 61_000
        replacement.flushPeriodic(true)
        old.onSongChanged(song(3), 44, true)
        runCurrent()
        assertEquals(listOf(1L, 2L), port.records.map { it.song.id })
        assertEquals(listOf(42L, 43L), port.records.map { it.localPlaylistId })
        assertEquals(listOf(31_000L, 30_000L), port.records.map { it.listenedMs })
        assertFalse(pending.hasPendingWork())
    }

    private fun kotlinx.coroutines.test.TestScope.suspendedFixture(): SuspendedFixture {
        val fixture = SuspendedFixture()
        fixture.owner = owner(backgroundScope, fixture.port,
            PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { fixture.now }),
            pending = PlaybackStatsPendingWrites(fixture.store, backgroundScope))
        return fixture
    }

    private class SuspendedFixture {
        var now = 0L
        val store = FakePendingStore().apply { unavailable = true }
        val port = RecordingPort()
        lateinit var owner: PlaybackStatsOwner
        fun start() {
            owner.onSongChanged(SongItem(1, "song", "artist", "album", 1, 60_000, null), 42, true)
            owner.onPlayingChanged(true, "start", true)
        }
    }

    private fun owner(
        scope: CoroutineScope,
        writes: PlaybackStatsWritePort,
        tracker: PlaybackStatsTracker,
        blockForPersistence: ((Long, suspend () -> Unit) -> Unit)? = null,
        pending: PlaybackStatsPendingWrites = PlaybackStatsPendingWrites(FakePendingStore(), scope)
    ): PlaybackStatsOwner = if (blockForPersistence == null) {
        PlaybackStatsOwner(scope, writes, tracker, pending)
    } else {
        PlaybackStatsOwner(scope, writes, tracker, pending, blockForPersistence)
    }

    private class FakePendingStore : PlaybackStatsPendingStore {
        private val events = ArrayDeque<PlaybackStatsSnapshot>()
        var unavailable = false
        override fun append(snapshot: PlaybackStatsSnapshot) {
            if (unavailable) throw IOException("storage unavailable")
            events.addLast(snapshot)
        }
        override fun first(): PlaybackStatsSnapshot? = events.firstOrNull()
        override fun acknowledge(eventId: String) {
            assertEquals(eventId, events.removeFirst().eventId)
        }
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
