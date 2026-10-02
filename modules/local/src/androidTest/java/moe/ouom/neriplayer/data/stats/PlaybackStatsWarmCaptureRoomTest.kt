package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsWarmCaptureRoomTest {
    @Test fun staleReceiptSequenceInvalidatesEvenWhenRevisionAndAllCountersStayTheSame() = runTest {
        val fixture = open()
        try {
            fixture.borrow().playback.close()
            val before = fixture.room.readConfirmedCaptureStamp()
            assertTrue(fixture.room.enqueueDelta("stale", Gson().toJson(fixture.track), 1_000, 1, 1_000, "device", 0))
            val after = fixture.room.readConfirmedCaptureStamp()
            assertEquals(before.state, after.state)
            assertEquals(before.journalSequence + 1, after.journalSequence)
            assertEquals(0L, fixture.room.pendingDeltaCount())
            fixture.borrow().playback.use { assertEquals(1_000L, read(it).single().totalListenMs) }
            assertEquals(2, fixture.copies.get())
        } finally { fixture.close() }
    }

    @Test fun clearAllTablesAndRecreateSameRevisionCannotReuseTheOldDatabaseCapture() = runTest {
        val fixture = open()
        try {
            val existing = fixture.borrow()
            try {
                val before = fixture.room.readConfirmedCaptureStamp()
                withContext(Dispatchers.IO) { fixture.database.clearAllTables() }
                fixture.room.importLegacyAndPromote(listOf(fixture.track.copy(name = "restored song")), emptyList(),
                    PlaybackStatsSyncCounterSnapshot(), 50, 50)
                val after = fixture.room.readConfirmedCaptureStamp()
                assertEquals(before.state, after.state)
                assertEquals(before.journalSequence, after.journalSequence)
                assertNotEquals(before.databaseInstance, after.databaseInstance)
                fixture.borrow().playback.use { assertEquals("restored song", read(it).single().name) }
                assertEquals("song", read(existing.playback).single().name)
                assertEquals(2, fixture.copies.get())
            } finally { existing.playback.close() }
        } finally { fixture.close() }
    }

    @Test fun malformedPendingJournalFailsClosedThenSuccessfulDrainCapturesNewCounters() = runTest {
        val fixture = open()
        try {
            val existing = fixture.borrow()
            try {
                assertTrue(fixture.room.enqueueDelta("pending", "broken", 2_000, 0, 300, "device"))
                assertTrue(runCatching { fixture.borrow() }.exceptionOrNull() is IOException)
                assertEquals(1L, fixture.room.pendingDeltaCount())
                assertEquals("song", read(existing.playback).single().name)
                assertTrue(runCatching { fixture.room.readConfirmedCaptureStamp() }.exceptionOrNull() is IOException)
                withContext(Dispatchers.IO) {
                    fixture.database.openHelper.writableDatabase.execSQL(
                        "UPDATE playback_stats_pending_delta SET track_json = ? WHERE id = ?",
                        arrayOf(Gson().toJson(fixture.track), "pending"))
                }
                fixture.borrow().playback.use { assertEquals(3_000L, read(it).single().totalListenMs) }
                assertEquals(0L, fixture.room.pendingDeltaCount())
                fixture.borrow().playback.close()
                assertEquals(2, fixture.copies.get())
            } finally { existing.playback.close() }
        } finally { fixture.close() }
    }

    @Test fun closedDatabaseCannotBorrowTheCachedFilesAndAnExistingBorrowStillReads() = runTest {
        val fixture = open()
        try {
            val existing = fixture.borrow()
            try {
                fixture.database.close()
                assertTrue(runCatching { fixture.borrow() }.isFailure)
                assertEquals("song", read(existing.playback).single().name)
                existing.playback.close()
                assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
            } finally { existing.playback.close() }
        } finally { fixture.close() }
    }

    @Test fun invalidIncarnationMetadataIsNeverAcknowledgedByAWarmBorrow() = runTest {
        val fixture = open()
        try {
            fixture.borrow().playback.close()
            withContext(Dispatchers.IO) {
                fixture.database.openHelper.writableDatabase.execSQL("UPDATE migration_metadata SET value = 'invalid' WHERE key = ?",
                    arrayOf(PlaybackStatsRoomStore.CAPTURE_DATABASE_INSTANCE_KEY))
            }
            assertTrue(runCatching { fixture.borrow() }.exceptionOrNull() is IOException)
            assertFalse(fixture.directory.listFiles().orEmpty().any())
        } finally { fixture.close() }
    }

    private suspend fun open(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copies = AtomicInteger()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { query, _ ->
                if (query.startsWith("INSERT INTO playback_stat_snapshot_track ")) copies.incrementAndGet()
            }, Executor { it.run() }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = File(context.cacheDir, "warm-stats-${UUID.randomUUID()}")
        val room = PlaybackStatsRoomStore(database)
        val track = TrackStat(7, "song", "artist", "netease", 0, null, 180_000, 1_000, 1, 200, 100,
            null, null, null, null, null, null, "track|7")
        room.importLegacyAndPromote(listOf(track), emptyList(), PlaybackStatsSyncCounterSnapshot(), 50, 50)
        val repository = PlaybackStatsRepository(context, room, scope) { "device" }
        assertTrue(repository.awaitInitialized())
        return Fixture(database, room, repository, scope, directory, copies, track)
    }

    private class Fixture(val database: NeriUserDataDatabase, val room: PlaybackStatsRoomStore,
        val repository: PlaybackStatsRepository, val scope: CoroutineScope, val directory: File,
        val copies: AtomicInteger, val track: TrackStat) {
        private val store = FileSyncPlaybackDatasetStore(directory)
        suspend fun borrow() = repository.borrowSyncCapture(store) { PlaybackStatsCaptureProjection("en", emptySet()) }
        suspend fun close() {
            try { repository.releaseSyncCaptureCache() }
            finally { scope.cancel(); database.close(); check(directory.deleteRecursively()) }
        }
    }

    private suspend fun read(source: SyncPlaybackSource) = source.openTracks().use { cursor ->
        buildList { while (true) { val page = cursor.nextPage(); if (page.isEmpty()) break; addAll(page) } }
    }
}
