package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.REVISION_METADATA_KEY
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsWalReadCaptureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<NeriUserDataDatabase>()

    @After
    fun tearDown() {
        opened.forEach(NeriUserDataDatabase::close)
        listOf(WAL_NAME, TRUNCATE_NAME).forEach(context::deleteDatabase)
    }

    @Test
    fun `wal capture pages every track and bucket with their counter shards`() = runTest {
        val store = promotedStore(WAL_NAME, RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        val capture = PlaybackStatsRoomExportCapture.open(store)
        val tracks = mutableListOf<SyncTrackStat>()
        val buckets = mutableListOf<SyncPlaybackStatBucket>()

        try {
            assertTrue(capture is PlaybackStatsWalReadCapture)
            assertEquals(store.readPrimaryState(), capture.state)
            capture.export(context, { tracks += it }, { buckets += it })
        } finally {
            capture.release()
        }

        assertEquals(listOf("a", "b"), tracks.map { it.identityKey })
        assertEquals(listOf(listOf(shard("phone")), emptyList()), tracks.map { it.counterShards })
        assertEquals(listOf(DAY to "a", DAY to "b", 2 * DAY to "a"), buckets.map { it.dayStartAt to it.identityKey })
        assertEquals(listOf(listOf(shard("tablet")), emptyList(), listOf(shard("phone"))), buckets.map { it.counterShards })
    }

    @Test
    fun `capture keeps reading its snapshot while the primary store moves on`() = runTest {
        val store = promotedStore(WAL_NAME, RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        val capture = PlaybackStatsRoomExportCapture.open(store)
        val tracks = mutableListOf<String>()

        try {
            store.removeTracks(setOf("a"))
            capture.export(context, { page -> tracks += page.map { it.identityKey } }, {}, SyncPlaybackStatMapper.bind(context))
        } finally {
            capture.release()
        }
        capture.release()

        assertEquals(listOf("a", "b"), tracks)
        assertEquals(listOf("b"), store.readIfRoomPrimary()?.stats?.map { it.identityKey })
        expectFailure<IllegalStateException> { capture.export(context, { tracks += "late" }, {}) }
        assertEquals(listOf("a", "b"), tracks)
    }

    @Test
    fun `capture is refused for untrusted metadata or a pending journal`() = runTest {
        val store = promotedStore(WAL_NAME, RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        val sqlite = store.database.openHelper.writableDatabase

        sqlite.execSQL("UPDATE migration_metadata SET value = '-1' WHERE `key` = ?", arrayOf(REVISION_METADATA_KEY))
        val invalid = expectFailure<IOException> { PlaybackStatsRoomExportCapture.open(store) }
        sqlite.execSQL("UPDATE migration_metadata SET value = '3' WHERE `key` = ?", arrayOf(REVISION_METADATA_KEY))
        store.enqueueDelta("event", "{}", 100, 1, playedAt = 5 * DAY, deviceId = "phone")
        val pending = expectFailure<IOException> { PlaybackStatsRoomExportCapture.open(store) }
        sqlite.execSQL("DELETE FROM playback_stats_pending_delta")
        sqlite.execSQL("UPDATE migration_metadata SET value = 'legacy_json' WHERE `key` = ?", arrayOf(CUTOVER_STATE_METADATA_KEY))
        val legacy = expectFailure<IOException> { PlaybackStatsRoomExportCapture.open(store) }

        assertEquals("Invalid playback read capture metadata: $REVISION_METADATA_KEY", invalid.message)
        assertEquals("Playback read capture contains pending journal entries", pending.message)
        assertEquals("Playback read capture requires a trusted primary store", legacy.message)
        sqlite.execSQL("UPDATE migration_metadata SET value = 'room_primary' WHERE `key` = ?", arrayOf(CUTOVER_STATE_METADATA_KEY))
        val retried = PlaybackStatsRoomExportCapture.open(store)
        retried.release()
        assertEquals(3L, retried.state.revision)
    }

    @Test
    fun `databases outside wal mode fall back to a frozen snapshot`() = runTest {
        val store = promotedStore(TRUNCATE_NAME, RoomDatabase.JournalMode.TRUNCATE)
        val capture = PlaybackStatsRoomExportCapture.open(store)
        val tracks = mutableListOf<String>()

        try {
            assertFalse(capture is PlaybackStatsWalReadCapture)
            capture.export(context, { page -> tracks += page.map { it.identityKey } }, {})
        } finally {
            capture.release()
        }

        assertEquals(listOf("a", "b"), tracks)
    }

    @Test
    fun `in memory databases fall back to a frozen snapshot`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        val store = promote(PlaybackStatsRoomStore(database.also(opened::add)))
        val capture = PlaybackStatsRoomExportCapture.open(store)
        val tracks = mutableListOf<String>()

        try {
            assertFalse(capture is PlaybackStatsWalReadCapture)
            assertEquals(store.readPrimaryState(), capture.state)
            capture.export(context, { page -> tracks += page.map { it.identityKey } }, {})
        } finally {
            capture.release()
        }

        assertEquals(listOf("a", "b"), tracks)
    }

    private suspend fun promotedStore(name: String, journalMode: RoomDatabase.JournalMode): PlaybackStatsRoomStore {
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
            .setJournalMode(journalMode).allowMainThreadQueries().build().also(opened::add)
        return promote(PlaybackStatsRoomStore(database))
    }

    private suspend fun promote(store: PlaybackStatsRoomStore): PlaybackStatsRoomStore {
        return store.apply {
            importLegacyAndPromote(
                stats = listOf(stat("a"), stat("b")),
                dailyStats = listOf(bucket(DAY, "a"), bucket(DAY, "b"), bucket(2 * DAY, "a")),
                counterSnapshot = PlaybackStatsSyncCounterSnapshot(
                    trackShardsByIdentity = mapOf("a" to listOf(shard("phone"))),
                    dailyShardsByBucketKey = mapOf(
                        PlaybackStatsSyncCounterSnapshot.dailyCounterKey(DAY, "a") to listOf(shard("tablet")),
                        PlaybackStatsSyncCounterSnapshot.dailyCounterKey(2 * DAY, "a") to listOf(shard("phone"))
                    )
                ),
                counterEpochStartedAt = 0,
                clearedAt = 0,
                now = 10
            )
        }
    }

    private companion object {
        const val WAL_NAME = "wal-capture.db"
        const val TRUNCATE_NAME = "truncate-capture.db"
        const val DAY = 86_400_000L

        fun stat(key: String) = TrackStat(
            7, "song $key", "artist", "album", 3, null, 180_000, 1_000, 2, 300, 100,
            "https://music.example/$key", null, null, null, null, null, key
        )

        fun bucket(day: Long, key: String) = PlaybackStatBucket(
            day, 7, "song $key", "artist", "album", 3, null, 180_000, 1_000, 1, day + 10, day + 5,
            "https://music.example/$key", null, null, null, null, null, key
        )

        fun shard(device: String) = SyncPlaybackCounterShard(device, 0, 1_000, 2, 100, 300)
    }
}
