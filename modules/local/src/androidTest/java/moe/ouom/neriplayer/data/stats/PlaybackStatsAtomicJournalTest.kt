package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class PlaybackStatsAtomicJournalTest {
    @Test
    fun completedReceiptRetentionIsBoundedWithoutUsingPlaybackWallClockOrder() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 1_000, 1_000)
            val json = Gson().toJson(song().toStatisticsMetadata())
            repeat(600) { index ->
                assertTrue(store.enqueueDelta("expired-$index", json, 30_000, 1, 600L - index, "device", observedClearedAt = 0))
            }
            assertEquals(0L, store.pendingDeltaCount())
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playback_stats_event_receipt").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("Completed receipts must remain bounded", cursor.getLong(0) <= 256)
            }
            assertTrue(database.playbackStatsSnapshotDao().receipt("expired-599") != null)
            assertFalse(store.enqueueDelta("expired-599", json, 30_000, 1, 1, "device", observedClearedAt = 0))
        } finally { database.close() }
    }

    @Test
    fun receiptCleanupPreservesEveryDurablePendingDelta() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val json = Gson().toJson(song().toStatisticsMetadata())
            repeat(300) { index -> assertTrue(store.enqueueDelta("pending-$index", json, 30_000, 1, 300L - index, "device")) }
            assertEquals(300L, store.pendingDeltaCount())
            repeat(300) { index -> assertFalse(store.enqueueDelta("pending-$index", json, 30_000, 1, 300L - index, "device")) }
            assertTrue(database.playbackStatsSnapshotDao().receipt("pending-0") != null)
        } finally { database.close() }
    }

    @Test
    fun committedEventReceiptsPreventDuplicatePlaybackAcrossRoomReopenAndClear() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "playback-journal-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        var database = open()
        try {
            var store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val metadata = song().toStatisticsMetadata()
            val json = Gson().toJson(metadata)
            assertTrue(store.enqueueDelta("event", json, 30_000, 1, 100, "device"))
            assertEquals(1L, store.pendingDeltaCount())
            database.close()
            database = open()
            store = PlaybackStatsRoomStore(database)
            val pending = store.pendingDeltas().single()
            store.applyDelta(pending, metadata.identityKey) { PlaybackStatsDeltaPolicy.apply(pending, metadata, it) }
            assertEquals(30_000L, store.readTrack(metadata.identityKey)?.totalListenMs)
            assertEquals(0L, store.pendingDeltaCount())
            assertFalse(store.enqueueDelta("event", json, 30_000, 1, 100, "device"))
            val clear = store.clear(200)
            assertFalse(store.enqueueDelta("event", json, 30_000, 1, 100, "device"))
            assertTrue(store.enqueueDelta("late", json, 50_000, 1, 150, "device"))
            assertEquals(0L, store.pendingDeltaCount())
            assertEquals(null, store.readTrack(metadata.identityKey))
            assertEquals(clear.revision, store.readPrimaryState()?.revision)
            assertTrue(runCatching { store.enqueueDelta("event", json, 30_001, 1, 100, "device") }.isFailure)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedMainTransactionRetainsAcknowledgedDeltaAndRetriesExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val before = checkNotNull(store.readPrimaryState())
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_playback_bucket BEFORE INSERT ON playback_stat_bucket BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END")
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "event", 100)
            assertTrue(repository.hasPendingWrites())
            assertEquals(1L, store.pendingDeltaCount())
            assertEquals(null, store.readTrack(song().toStatisticsMetadata().identityKey))
            assertEquals(before.revision, store.readPrimaryState()?.revision)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_playback_bucket")
            repository.flushPendingWrites()
            assertFalse(repository.hasPendingWrites())
            assertEquals(30_000L, store.readTrack(song().toStatisticsMetadata().identityKey)?.totalListenMs)
            assertEquals(before.revision + 1, store.readPrimaryState()?.revision)
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "event", 100)
            assertEquals(30_000L, store.readTrack(song().toStatisticsMetadata().identityKey)?.totalListenMs)
            assertEquals(before.revision + 1, store.readPrimaryState()?.revision)
            assertTrue(repository.checkCapturedRevision(before.revision + 1))
            assertEquals(before.revision + 1, store.readPrimaryState()?.revision)
        } finally { database.close() }
    }

    @Test
    fun newPlaybackAfterObservingARemoteFutureClearSurvivesLocalClockSkew() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val localNow = System.currentTimeMillis()
            val remoteClear = localNow + 86_400_000
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), remoteClear, remoteClear)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            assertEquals(remoteClear, repository.statsClearedAtFlow.value)
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "fresh-after-observed-clear", localNow)
            val key = song().toStatisticsMetadata().identityKey
            assertEquals(30_000L, store.readTrack(key)?.totalListenMs)
            val shard = database.playbackStatsDao().getTrackCounters(key).single()
            assertEquals(remoteClear, shard.epochStartedAt)
            assertTrue(shard.firstPlayedAt >= remoteClear)
            val bucket = database.playbackStatsDao().getBuckets().single()
            assertEquals(30_000L, bucket.totalListenMs)
            assertEquals(bucket.dayStartAt, database.playbackStatsDao().getDailyCounterShards().single().dayStartAt)
        } finally { database.close() }
    }

    @Test
    fun retainedOldEpochDoesNotReturnEvenWithAFastClockAndReceiptRetryRemainsStable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 2_000, 2_000)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "old-fast-clock", 3_000, observedClearedAt = 0)
            val key = song().toStatisticsMetadata().identityKey
            assertEquals(null, store.readTrack(key))
            assertEquals(0L, store.pendingDeltaCount())
            assertTrue(database.playbackStatsSnapshotDao().receipt("old-fast-clock") != null)
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "fresh", 1_000)
            assertEquals(30_000L, store.readTrack(key)?.totalListenMs)
            store.clear(4_000)
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "fresh", 1_000)
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "old-fast-clock", 3_000, observedClearedAt = 0)
            assertEquals(null, store.readTrack(key))
            assertEquals(0L, store.pendingDeltaCount())
        } finally { database.close() }
    }

    @Test
    fun clearObservationUsesCommittedMetadataIndependentlyOfWriterAcknowledgement() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            store.clear(4_000)
            val observed = withContext(Dispatchers.IO) {
                withTimeout(10_000) { repository.statsClearedAtFlow.first { it == 4_000L } }
            }
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "after-clear", 1_000, observedClearedAt = observed)
            assertEquals(30_000L, store.readTrack(song().toStatisticsMetadata().identityKey)?.totalListenMs)
        } finally { database.close() }
    }

    @Test
    fun freshPlaybackAfterAFutureClearKeepsItsWallClockDayAndMonth() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val localNow = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.JANUARY, 15, 12, 0, 0)
            }.timeInMillis
            val remoteClear = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.FEBRUARY, 15, 12, 0, 0)
            }.timeInMillis
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), remoteClear, remoteClear)
            val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "fresh-local-month", localNow, observedClearedAt = remoteClear)
            val key = song().toStatisticsMetadata().identityKey
            val bucket = database.playbackStatsDao().getBuckets().single()
            assertEquals(playbackStatsDayStartAt(localNow), bucket.dayStartAt)
            assertTrue(bucket.firstPlayedAt >= remoteClear)
            assertEquals(bucket.dayStartAt, database.playbackStatsDao().getDailyCounterShards().single().dayStartAt)
            assertEquals(30_000L, repository.readSummary(PlaybackStatsQuery(period = PlaybackStatsPeriod.MONTH, nowMillis = localNow)).totalListenMs)
            assertEquals(0L, repository.readSummary(PlaybackStatsQuery(period = PlaybackStatsPeriod.MONTH, nowMillis = remoteClear)).totalListenMs)
            assertEquals(30_000L, store.readTrack(key)?.totalListenMs)
            assertEquals(remoteClear, database.playbackStatsDao().getTrackCounters(key).single().epochStartedAt)
            val captured = repository.statsClearedAtFlow.value
            store.clear(remoteClear + 1)
            repository.recordListenDeltaNow(song(), 30_000, 1, false, "fresh-local-month", localNow, observedClearedAt = captured)
            assertEquals(null, store.readTrack(key))
            assertEquals(0L, store.pendingDeltaCount())
        } finally { database.close() }
    }

    private fun song() = SongItem(7, "song", "artist", "netease", 0, 180_000, null)
}
