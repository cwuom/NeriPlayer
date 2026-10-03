package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsBackupApplyTest {
    @Test fun manualRestoreAcknowledgesPreClearSpoolEventsBeforeRestoringAnOlderClearEpoch() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val song = SongItem(1, "song", "artist", "netease", 0, 180_000, null)
            val identityKey = song.stableKey()
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(1).copy(identityKey = identityKey)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val cleared = store.clear(500)
            var queued = true
            suspend fun replaySpool() {
                repository.recordListenDeltaNow(song, 2_500, 1, scheduleSync = false,
                    eventId = "pre-clear-spool", playedAt = 450, observedClearedAt = 0)
                queued = false
            }
            PlaybackStatsCaptureBarrier.install { replaySpool(); assertNull(store.readTrack(identityKey)) }
            repository.applyMergedStats(source(listOf(syncTrack(1).copy(identityKey = identityKey)), emptyList()), 0, respectLocalClear = false)
            if (queued) replaySpool()
            assertEquals(60_000L, store.readTrack(identityKey)?.totalListenMs)
            assertEquals(2, store.readTrack(identityKey)?.playCount)
            assertEquals(1, store.readIfRoomPrimary()?.stats?.size)
            assertEquals(0L, store.readPrimaryState()?.clearedAt)
            assertEquals(cleared.counterEpochStartedAt, store.readPrimaryState()?.counterEpochStartedAt)
            replaySpool()
            assertEquals(60_000L, store.readTrack(identityKey)?.totalListenMs)
            assertEquals(1, store.readIfRoomPrimary()?.stats?.size)
            assertEquals(0L, store.pendingDeltaCount())
        } finally { PlaybackStatsCaptureBarrier.install {}; database.close() }
    }

    @Test fun failedManualRestoreSpoolBarrierPreservesTheCurrentClearFenceAndPrimaryRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(1)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val cleared = store.clear(500)
            val failure = IOException("spool unavailable")
            PlaybackStatsCaptureBarrier.install { throw failure }
            assertSame(failure, runCatching {
                repository.applyMergedStats(source(listOf(syncTrack(1)), emptyList()), 0, respectLocalClear = false)
            }.exceptionOrNull())
            assertEquals(cleared, store.readPrimaryState())
            assertNull(store.readTrack("track|1"))
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playback_stats_snapshot").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        } finally { PlaybackStatsCaptureBarrier.install {}; database.close() }
    }

    @Test fun clearRespectingMergeDoesNotDrainRuntimeSpoolOrLowerTheClearFence() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(1)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val cleared = store.clear(500)
            PlaybackStatsCaptureBarrier.install(
                { error("clear-respecting merge must not flush runtime spool") },
                { _, _ -> error("clear-respecting merge must not suspend runtime sampling") }
            )
            repository.applyMergedStats(listOf(syncTrack(1)), 0, respectLocalClear = true)
            assertEquals(cleared.clearedAt, store.readPrimaryState()?.clearedAt)
            assertNull(store.readTrack("track|1"))
        } finally { PlaybackStatsCaptureBarrier.install {}; database.close() }
    }

    @Test fun forceRestoreGuardCoversPagingAndCommitBeforeFollowingDeltasUseTheRestoredFence() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val song = SongItem(1, "song", "artist", "netease", 0, 180_000, null)
            val identityKey = song.stableKey()
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(1).copy(identityKey = identityKey)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val cleared = store.clear(500)
            var samplingPaused = false
            var observedClear = cleared.clearedAt
            var guardedPages = 0
            PlaybackStatsCaptureBarrier.install({}, { _, block ->
                assertEquals(cleared.clearedAt, store.readPrimaryState()?.clearedAt)
                samplingPaused = true
                try {
                    block()
                    assertEquals(0L, store.readPrimaryState()?.clearedAt)
                    assertEquals(0L, repository.statsClearedAtFlow.value)
                } finally {
                    observedClear = repository.statsClearedAtFlow.value
                    samplingPaused = false
                }
            })
            val delegate = source(listOf(syncTrack(1).copy(identityKey = identityKey)), emptyList())
            val incoming = object : SyncPlaybackSource by delegate {
                override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> {
                    val cursor = delegate.openTracks()
                    return object : SyncPlaybackCursor<SyncTrackStat> by cursor {
                        override suspend fun nextPage(): List<SyncTrackStat> {
                            assertTrue("all backup pages must be read while sampling is paused", samplingPaused)
                            assertEquals(cleared.clearedAt, store.readPrimaryState()?.clearedAt)
                            guardedPages++
                            return cursor.nextPage()
                        }
                    }
                }
            }
            repository.applyMergedStats(incoming, 0, respectLocalClear = false)
            assertEquals(2, guardedPages)
            assertTrue(!samplingPaused)
            repository.recordListenDeltaNow(song, 15_000, 1, scheduleSync = false,
                eventId = "post-restore", playedAt = 600, observedClearedAt = observedClear)
            repository.flushPendingWrites()
            assertEquals(75_000L, store.readTrack(identityKey)?.totalListenMs)
            assertEquals(3, store.readTrack(identityKey)?.playCount)
            assertEquals(1, store.readIfRoomPrimary()?.stats?.size)
            assertEquals(cleared.counterEpochStartedAt, store.readPrimaryState()?.counterEpochStartedAt)
            assertEquals(0L, store.pendingDeltaCount())
        } finally { PlaybackStatsCaptureBarrier.install {}; database.close() }
    }

    @Test fun cancellationAfterRestoreCommitLeavesThePublishedFenceAvailableToTheRuntimeFinally() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(1)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            store.clear(500)
            val cancellation = CancellationException("cancelled after committed restore")
            var restoredFence = -1L
            PlaybackStatsCaptureBarrier.install({}, { _, block ->
                try { block(); throw cancellation }
                finally { restoredFence = repository.statsClearedAtFlow.value }
            })
            assertSame(cancellation, runCatching {
                repository.applyMergedStats(listOf(syncTrack(1)), 0, respectLocalClear = false)
            }.exceptionOrNull())
            assertEquals(0L, restoredFence)
            assertEquals(0L, store.readPrimaryState()?.clearedAt)
            assertEquals(60_000L, store.readTrack("track|1")?.totalListenMs)
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playback_stats_snapshot").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        } finally { PlaybackStatsCaptureBarrier.install {}; database.close() }
    }

    @Test fun pagedBackupMergesAllRowsAndLiftsDailyTotalsOnceBeforeCommit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(1).copy(customName = "my title")
            val local = track(999).copy(localFilePath = "/audio.flac")
            store.importLegacyAndPromote(listOf(original, local), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val revision = checkNotNull(store.readPrimaryState()).revision
            val incoming = (1L..520L).map { syncTrack(it) }
            val buckets = incoming.map { bucket(it, 0) } + incoming.map { bucket(it, 86_400_000) }
            repository.applyMergedStats(source(incoming, buckets), 0, respectLocalClear = false)
            val restored = checkNotNull(store.readIfRoomPrimary())
            assertEquals(521, restored.stats.size)
            assertEquals(1040, restored.dailyStats.size)
            assertEquals("my title", store.readTrack(original.identityKey)?.customName)
            assertEquals(4, store.readTrack(original.identityKey)?.playCount)
            assertEquals(local, store.readTrack(local.identityKey))
            assertEquals(revision + 1, store.readPrimaryState()?.revision)
            repository.applyMergedStats(source(incoming, buckets), 0, respectLocalClear = false)
            assertEquals(4, store.readTrack(original.identityKey)?.playCount)
        } finally { database.close() }
    }

    @Test fun failedOrCancelledFinalCursorReadCannotCommitPartialBackupPages() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (failure in listOf(IOException("read failed"), CancellationException("cancelled"))) {
            val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
            try {
                val store = PlaybackStatsRoomStore(database)
                val original = track(1)
                store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
                val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
                assertTrue(repository.awaitInitialized())
                val revision = checkNotNull(store.readPrimaryState()).revision
                val incoming = source((1L..256L).map(::syncTrack), emptyList(), failure)
                assertSame(failure, runCatching { repository.applyMergedStats(incoming, 0, false) }.exceptionOrNull())
                assertEquals(listOf(original), store.readIfRoomPrimary()?.stats)
                assertEquals(revision, store.readPrimaryState()?.revision)
            } finally { database.close() }
        }
    }

    @Test fun concurrentPrimaryWriteRejectsThePreparedBackupWithoutOverwritingIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(1)
            store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            val newer = original.copy(totalListenMs = 99_000)
            val delegate = source(listOf(syncTrack(1)), emptyList())
            val incoming = object : SyncPlaybackSource by delegate {
                override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> {
                    val cursor = delegate.openTracks()
                    return object : SyncPlaybackCursor<SyncTrackStat> by cursor {
                        override suspend fun nextPage(): List<SyncTrackStat> {
                            val page = cursor.nextPage()
                            if (page.isNotEmpty()) store.replaceAll(listOf(newer), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
                            return page
                        }
                    }
                }
            }
            assertTrue(runCatching { repository.applyMergedStats(incoming, 0, false) }.exceptionOrNull() is IOException)
            assertEquals(newer, store.readTrack(original.identityKey))
        } finally { database.close() }
    }

    private fun track(id: Long) = TrackStat(id, "song", "artist", "netease", 0, null, 180_000, 30_000, 1, 200, 100,
        null, null, null, null, null, null, "track|$id")

    private fun syncTrack(id: Long) = SyncTrackStat("track|$id", "song", "artist", "netease", 60_000, 2, 300, 100, null, 180_000, null, id, 0)

    private fun bucket(track: SyncTrackStat, day: Long) = SyncPlaybackStatBucket(day, track.identityKey, track.name, track.artist,
        track.album, track.totalListenMs, track.playCount, track.lastPlayedAt, track.firstPlayedAt, track.coverUrl,
        track.durationMs, track.mediaUri, track.id, track.albumId)

    private fun source(tracks: List<SyncTrackStat>, buckets: List<SyncPlaybackStatBucket>, failure: Exception? = null) = object : SyncPlaybackSource {
        override fun openTracks() = cursor(tracks, failure)
        override fun openBuckets(order: SyncPlaybackBucketOrder) = cursor(buckets, null)
        override fun close() = Unit
    }

    private fun <T> cursor(rows: List<T>, failure: Exception?) = object : SyncPlaybackCursor<T> {
        private var offset = 0
        override suspend fun nextPage(): List<T> {
            if (offset == rows.size) { failure?.let { throw it }; return emptyList() }
            val end = minOf(offset + 256, rows.size)
            return rows.subList(offset, end).also { offset = end }
        }
        override fun close() = Unit
    }
}
