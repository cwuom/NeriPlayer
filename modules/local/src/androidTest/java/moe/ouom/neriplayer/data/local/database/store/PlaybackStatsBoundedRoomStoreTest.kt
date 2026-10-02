package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PlaybackStatsBoundedRoomStoreTest {
    @Test
    fun frozenPagesDoNotChangeWhenPrimaryReceivesANewCommit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val stats = (0..599).map { track(it) }
            store.importLegacyAndPromote(stats, emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val frozen = store.freezeSnapshot()
            store.replaceAll(listOf(track(0).copy(totalListenMs = 99_000)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            var after: String? = null
            var count = 0
            while (true) {
                val page = store.readFrozenTracks(frozen.id, after, 256)
                if (page.isEmpty()) break
                assertTrue(page.size <= 256)
                assertTrue(page.all { it.totalListenMs == 30_000L })
                count += page.size
                after = page.last().identityKey
            }
            assertEquals(600, count)
            assertFalse(store.commitFrozenSnapshot(frozen.id, frozen.revision))
            assertEquals(99_000L, store.readTrack("track|0000")?.totalListenMs)
            store.releaseSnapshot(frozen.id)
        } finally {
            database.close()
        }
    }

    @Test
    fun snapshotCommitAndRevisionAreAtomicAndPreserveLocalMetadata() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val original = track(0).copy(localFilePath = "/local/audio.flac", customName = "my name")
            store.importLegacyAndPromote(listOf(original), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val frozen = store.freezeSnapshot()
            assertTrue(store.commitFrozenSnapshot(frozen.id, frozen.revision))
            assertEquals(original, store.readTrack(original.identityKey))
            assertEquals(frozen.revision + 1, store.readPrimaryState()?.revision)
            assertFalse(store.commitFrozenSnapshot(frozen.id, frozen.revision))
            store.releaseSnapshot(frozen.id)
        } finally {
            database.close()
        }
    }

    @Test
    fun generatedSnapshotQueriesSeekDirectlyToThePageBoundary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val captured = ConcurrentLinkedQueue<Pair<String, List<Any?>>>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .setQueryCallback(Dispatchers.Unconfined, RoomDatabase.QueryCallback { sql, args ->
                if (sql.startsWith("SELECT * FROM playback_stat_snapshot_")) captured.add(sql to args.toList())
            }).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            database.openHelper.readableDatabase.query("SELECT sqlite_version()").use {
                assertTrue(it.moveToFirst())
                Log.i("PlaybackSnapshotPlan", "Android SQLite ${it.getString(0)}")
            }
            store.importLegacyAndPromote((0..599).map(::track), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val frozen = store.freezeSnapshot()
            val dao = database.playbackStatsSnapshotDao()
            assertEquals("track|0301", dao.nextTrackPage(frozen.id, "track|0300", 32).first().stat.identityKey)
            dao.nextBucketPage(frozen.id, 100, "track|0300", 32)
            dao.nextBucketIdentityPage(frozen.id, "track|0300", 100, 32)
            val queries = captured.toList()
            assertEquals(3, queries.size)
            queries.forEachIndexed { index, (sql, args) ->
                val plan = database.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args.toTypedArray()).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }.joinToString(" ")
                }
                val boundary = when (index) {
                    1 -> "(day_start_at,identity_key)>"
                    2 -> "(identity_key,day_start_at)>"
                    else -> "identity_key>"
                }
                Log.i("PlaybackSnapshotPlan", plan)
                assertTrue("Generated query must seek its complete boundary: $plan", plan.replace(" ", "").contains(boundary))
                assertFalse("Generated query must preserve indexed ordering: $plan", plan.contains("TEMP B-TREE"))
            }
            store.releaseSnapshot(frozen.id)
        } finally { database.close() }
    }

    @Test
    fun compositePagesTraverseLargeGroupsInBothOrdersWithoutSkippingRecords() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val tracks = (0..599).map(::track)
            val buckets = tracks.map { bucket(it, 1_000) } + (2_000L..2_599L).map { bucket(tracks.first(), it) }
            store.importLegacyAndPromote(tracks, buckets, PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val frozen = store.freezeSnapshot()
            val dao = database.playbackStatsSnapshotDao()
            val byDay = mutableListOf<Pair<Long, String>>()
            var day: Long? = null
            var identity: String? = null
            while (true) {
                val page = dao.bucketPage(frozen.id, day, identity, 32)
                if (page.isEmpty()) break
                byDay.addAll(page.map { it.bucket.dayStartAt to it.bucket.identityKey })
                day = page.last().bucket.dayStartAt
                identity = page.last().bucket.identityKey
            }
            assertEquals(buckets.map { it.dayStartAt to it.identityKey }.sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second }), byDay)
            val byIdentity = mutableListOf<Pair<String, Long>>()
            day = null
            identity = null
            while (true) {
                val page = dao.bucketIdentityPage(frozen.id, identity, day, 32)
                if (page.isEmpty()) break
                byIdentity.addAll(page.map { it.bucket.identityKey to it.bucket.dayStartAt })
                day = page.last().bucket.dayStartAt
                identity = page.last().bucket.identityKey
            }
            assertEquals(buckets.map { it.identityKey to it.dayStartAt }.sortedWith(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }), byIdentity)
            store.releaseSnapshot(frozen.id)
        } finally { database.close() }
    }

    @Test
    fun restartCleanupRemovesPreviousOwnersWithoutDeletingActiveSnapshots() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(0)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val active = store.freezeSnapshot()
            val dao = database.playbackStatsSnapshotDao()
            val orphan = active.copy(id = "abandoned", ownerProcessId = "previous-process")
            database.withTransaction {
                dao.upsertSnapshot(orphan)
                dao.freezeTrack(orphan.id)
                dao.freezeBucket(orphan.id)
                dao.freezeCounter(orphan.id)
                dao.freezeDailyCounter(orphan.id)
            }
            store.cleanupAbandonedSnapshots()
            assertEquals(null, dao.getSnapshot(orphan.id))
            assertTrue(dao.firstTrackPage(orphan.id, 32).isEmpty())
            assertEquals(active, dao.getSnapshot(active.id))
            assertEquals(track(0), store.readFrozenTracks(active.id, null, 32).single())
            store.releaseSnapshot(active.id)
        } finally { database.close() }
    }

    @Test
    fun cancellationAtSuccessfulCommitCleansTheOwnedSnapshotBeforeReturning() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lastCopyExecuted = AtomicBoolean(false)
        val transactionSucceeded = AtomicBoolean(false)
        val cancelledAfterSuccess = AtomicBoolean(false)
        var freezeJob: Job? = null
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .setQueryCallback(Dispatchers.Unconfined, RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("INSERT INTO playback_stat_snapshot_daily_counter")) lastCopyExecuted.set(true)
                if (sql == "TRANSACTION SUCCESSFUL" && lastCopyExecuted.get()) transactionSucceeded.set(true)
                if (sql == "END TRANSACTION" && lastCopyExecuted.compareAndSet(true, false)) {
                    cancelledAfterSuccess.set(transactionSucceeded.get())
                    freezeJob?.cancel()
                }
            }).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(0)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val freezing = async(start = CoroutineStart.LAZY) { store.freezeSnapshot() }
            freezeJob = freezing
            freezing.start()
            assertTrue(runCatching { freezing.await() }.exceptionOrNull() is CancellationException)
            assertTrue(cancelledAfterSuccess.get())
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playback_stats_snapshot").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playback_stat_snapshot_track").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            assertEquals(track(0), store.readTrack(track(0).identityKey))
        } finally { database.close() }
    }

    private fun track(index: Int) = TrackStat(
        id = index.toLong(), name = "song $index", artist = "artist", album = "netease", albumId = 0,
        coverUrl = null, durationMs = 180_000, totalListenMs = 30_000, playCount = 1,
        lastPlayedAt = 200, firstPlayedAt = 100, mediaUri = null, localFilePath = null,
        localFileName = null, customName = null, customArtist = null, customCoverUrl = null,
        identityKey = "track|${index.toString().padStart(4, '0')}"
    )

    private fun bucket(track: TrackStat, day: Long) = PlaybackStatBucket(day, track.id, track.name, track.artist, track.album,
        track.albumId, track.coverUrl, track.durationMs, track.totalListenMs, track.playCount, track.lastPlayedAt,
        track.firstPlayedAt, track.mediaUri, track.localFilePath, track.localFileName, track.customName,
        track.customArtist, track.customCoverUrl, track.identityKey)
}
