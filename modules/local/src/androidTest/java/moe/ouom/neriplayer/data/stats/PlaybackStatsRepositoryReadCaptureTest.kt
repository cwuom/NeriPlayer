package moe.ouom.neriplayer.data.stats

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSink
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsRepositoryReadCaptureTest {
    @Test
    fun runtimeCaptureBarrierCommitsIntoThePrimaryStoreBeforeBackupIsFrozen() = runTest {
        val fixture = open(RoomDatabase.JournalMode.AUTOMATIC, inMemory = true)
        val original = fixture.tracks.first()
        val song = SongItem(original.id, original.name, original.artist, original.album, original.albumId,
            original.durationMs, original.coverUrl)
        val track = original.copy(identityKey = song.stableKey())
        val eventId = UUID.randomUUID().toString()
        var calls = 0
        try {
            fixture.store.replaceAll(listOf(track), listOf(bucket(track)), counters(listOf(track), fixture.shard), 0, 0)
            PlaybackStatsCaptureBarrier.install {
                calls++
                fixture.repository.recordListenDeltaNow(
                    song = song,
                    listenedMs = 1_000, playCountIncrement = 1, scheduleSync = false,
                    eventId = eventId, playedAt = 300, observedClearedAt = 0
                )
                fixture.repository.flushPendingWrites()
            }
            repeat(2) {
                val output = StringWriter()
                withContext(Dispatchers.IO) {
                    withTimeout(10_000) {
                        JsonWriter(output).use { writer ->
                            writer.beginObject()
                            fixture.repository.writeBackupStatistics(writer)
                            writer.endObject()
                        }
                    }
                }
                val exported = Gson().fromJson(output.toString(), JsonObject::class.java)
                    .getAsJsonArray("playbackStats").single { row ->
                        row.asJsonObject.get("identityKey").asString == track.identityKey
                    }.asJsonObject
                assertEquals(2_000L, exported.get("totalListenMs").asLong)
                assertEquals(2, exported.get("playCount").asInt)
                assertEquals(2_000L, fixture.store.readTrack(track.identityKey)?.totalListenMs)
                fixture.assertNoFrozenRows()
            }
            assertEquals(2, calls)
        } finally { PlaybackStatsCaptureBarrier.install {}; fixture.close() }
    }

    @Test
    fun walCaptureAvoidsFullCopiesAndKeepsFourFamiliesWhileWriterCommits() = runTest {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val fixture = open(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        try {
            assertEquals("wal", fixture.journalMode())
            fixture.rejectFullCopies()
            verifyConcurrentCapture(fixture, expectsCopies = false)
        } finally { fixture.close() }
    }

    @Test
    fun backupExportUsesWalReadCaptureWithoutFullCopies() = runTest {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val fixture = open(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        try {
            fixture.rejectFullCopies()
            val output = StringWriter()
            val captured = JsonWriter(output).use { writer ->
                writer.beginObject()
                val state = fixture.repository.writeBackupStatistics(writer)
                writer.endObject()
                state
            }
            val backup = Gson().fromJson(output.toString(), JsonObject::class.java)
            val tracks = backup.getAsJsonArray("playbackStats")
            val buckets = backup.getAsJsonArray("playbackStatBuckets")
            assertEquals(fixture.tracks.size, tracks.size())
            assertEquals(fixture.tracks.size, buckets.size())
            for (rows in listOf(tracks, buckets)) for (record in rows) {
                assertEquals(1_000L, record.asJsonObject.get("totalListenMs").asLong)
                assertEquals(1_000L, record.asJsonObject.getAsJsonArray("counterShards").single().asJsonObject.get("totalListenMs").asLong)
            }
            assertEquals(checkNotNull(fixture.store.readPrimaryState()).revision, captured.revision)
            assertEquals(0, fixture.copies.get())
            fixture.assertNoFrozenRows()
        } finally { fixture.close() }
    }

    @Test
    fun truncateDatabaseFallsBackToConsistentFrozenPages() = runTest {
        val fixture = open(RoomDatabase.JournalMode.TRUNCATE)
        try {
            assertEquals("truncate", fixture.journalMode())
            verifyConcurrentCapture(fixture, expectsCopies = true)
        } finally { fixture.close() }
    }

    @Test
    fun inMemoryDatabaseFallsBackToConsistentFrozenPages() = runTest {
        val fixture = open(RoomDatabase.JournalMode.AUTOMATIC, inMemory = true)
        try { verifyConcurrentCapture(fixture, expectsCopies = true) }
        finally { fixture.close() }
    }

    @Test
    fun olderAndroidUsesTheConsistentFrozenFallbackForWal() = runTest {
        assumeTrue(Build.VERSION.SDK_INT < 35)
        val fixture = open(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        try { verifyConcurrentCapture(fixture, expectsCopies = true) }
        finally { fixture.close() }
    }

    @Test
    fun cancelledWalCaptureReleasesItsReaderBeforeRoomCheckpointAndFurtherWrites() = runTest {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val fixture = open(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        val entered = CompletableDeferred<Unit>()
        val sink = RecordingSink {
            entered.complete(Unit)
            awaitCancellation()
        }
        val capture = async(Dispatchers.IO) { fixture.repository.captureSyncSnapshot(sink) }
        try {
            withContext(Dispatchers.IO) { withTimeout(10_000) { entered.await() } }
            withContext(Dispatchers.IO) { withTimeout(10_000) { capture.cancelAndJoin() } }
            fixture.assertNoFrozenRows()
            withContext(Dispatchers.IO) { withTimeout(10_000) { fixture.replacePrimary() } }
            fixture.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("No capture reader may hold WAL frames after cancellation", 0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
            }
        } finally { capture.cancelAndJoin(); fixture.close() }
    }

    private suspend fun verifyConcurrentCapture(fixture: Fixture, expectsCopies: Boolean) {
        val revision = checkNotNull(fixture.store.readPrimaryState()).revision
        var committed = false
        val sink = RecordingSink {
            if (!committed) {
                withContext(Dispatchers.IO) { withTimeout(10_000) { fixture.replacePrimary() } }
                committed = true
            }
        }
        val captured = fixture.repository.captureSyncSnapshot(sink)
        assertTrue("The writer must commit while the snapshot is being consumed", committed)
        assertEquals(fixture.tracks.map { SyncPlaybackStatMapper.fromTrackStat(it, listOf(fixture.shard)) }, sink.tracks)
        assertEquals(fixture.tracks.map { SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket(it), listOf(fixture.shard)) }, sink.buckets)
        assertEquals(revision, captured.revision)
        assertEquals(revision + 1, fixture.store.readPrimaryState()?.revision)
        assertEquals(if (expectsCopies) 1 else 0, fixture.copies.get())
        assertFalse(fixture.repository.checkCapturedRevision(captured.revision))
        sink.seal().use { assertFalse(fixture.repository.applySyncSnapshot(it, captured.clearedAt, captured.revision)) }
        assertEquals(9_000L, fixture.store.readTrack(fixture.tracks.first().identityKey)?.totalListenMs)
        fixture.assertNoFrozenRows()
    }

    private suspend fun open(mode: RoomDatabase.JournalMode, inMemory: Boolean = false): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = if (inMemory) null else "stats-repository-read-${UUID.randomUUID()}.db"
        val copies = AtomicInteger()
        val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            else Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
        val database = builder.setJournalMode(mode)
            .setQueryCallback(Dispatchers.Unconfined, RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("INSERT INTO playback_stat_snapshot_track ")) copies.incrementAndGet()
            }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val tracks = (0..599).map { id -> TrackStat(id.toLong(), "song", "artist", "netease", 0, null,
                180_000, 1_000, 1, 200, 100, null, null, null, null, null, null, "track|${id.toString().padStart(4, '0')}") }
            val shard = SyncPlaybackCounterShard("actor", 0, 1_000, 1, 100, 200)
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(tracks, tracks.map(::bucket), counters(tracks, shard), 0, 0)
            val repository = PlaybackStatsRepository(context, store, scope) { "actor" }
            assertTrue(repository.awaitInitialized())
            return Fixture(context, name, database, store, repository, scope, tracks, shard, copies)
        } catch (error: Exception) {
            scope.cancel()
            database.close()
            name?.let { context.deleteDatabase(it) }
            throw error
        }
    }

    private inner class Fixture(val context: Context, val name: String?, val database: NeriUserDataDatabase,
        val store: PlaybackStatsRoomStore, val repository: PlaybackStatsRepository, val scope: CoroutineScope,
        val tracks: List<TrackStat>, val shard: SyncPlaybackCounterShard, val copies: AtomicInteger) {
        suspend fun replacePrimary() {
            val changed = tracks.first().copy(totalListenMs = 9_000, playCount = 9)
            store.replaceAll(listOf(changed), listOf(bucket(changed)),
                counters(listOf(changed), shard.copy(totalListenMs = 9_000, playCount = 9)), 0, 0)
        }
        fun journalMode(): String = database.openHelper.writableDatabase.query("PRAGMA journal_mode").use {
            check(it.moveToFirst()); it.getString(0)
        }
        fun rejectFullCopies() {
            for (family in FROZEN_FAMILIES) database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_copy_$family BEFORE INSERT ON $family BEGIN SELECT RAISE(ABORT, 'full snapshot copy forbidden'); END")
        }
        fun assertNoFrozenRows() {
            for (family in FROZEN_FAMILIES + "playback_stats_snapshot") database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $family").use {
                assertTrue(it.moveToFirst()); assertEquals(family, 0L, it.getLong(0))
            }
        }
        fun close() {
            scope.cancel()
            database.close()
            name?.let { assertTrue(context.deleteDatabase(it)) }
        }
    }

    private class RecordingSink(private val onTracks: suspend () -> Unit = {}) : SyncPlaybackSink {
        val tracks = mutableListOf<SyncTrackStat>()
        val buckets = mutableListOf<SyncPlaybackStatBucket>()
        override suspend fun appendTracks(page: List<SyncTrackStat>) { tracks.addAll(page); if (page.isNotEmpty()) onTracks() }
        override suspend fun appendBuckets(page: List<SyncPlaybackStatBucket>) { buckets.addAll(page) }
        override suspend fun seal() = object : SyncPlaybackSource {
            override fun openTracks() = cursor(tracks.toList())
            override fun openBuckets(order: SyncPlaybackBucketOrder) = cursor(buckets.toList())
            override fun close() = Unit
        }
        override fun close() = Unit
        private fun <T> cursor(rows: List<T>) = object : SyncPlaybackCursor<T> {
            var offset = 0
            override suspend fun nextPage(): List<T> {
                val end = minOf(offset + 256, rows.size)
                return rows.subList(offset, end).also { offset = end }
            }
            override fun close() = Unit
        }
    }

    private fun bucket(row: TrackStat) = PlaybackStatBucket(0, row.id, row.name, row.artist, row.album, row.albumId,
        row.coverUrl, row.durationMs, row.totalListenMs, row.playCount, row.lastPlayedAt, row.firstPlayedAt,
        row.mediaUri, row.localFilePath, row.localFileName, row.customName, row.customArtist, row.customCoverUrl, row.identityKey)

    private fun counters(rows: List<TrackStat>, shard: SyncPlaybackCounterShard) = PlaybackStatsSyncCounterSnapshot(
        rows.associate { it.identityKey to listOf(shard) }, rows.associate { "0|${it.identityKey}" to listOf(shard) })

    private companion object {
        val FROZEN_FAMILIES = listOf("playback_stat_snapshot_track", "playback_stat_snapshot_bucket",
            "playback_stat_snapshot_counter", "playback_stat_snapshot_daily_counter")
    }
}
