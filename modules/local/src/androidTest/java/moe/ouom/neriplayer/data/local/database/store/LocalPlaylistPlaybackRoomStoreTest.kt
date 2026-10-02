package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistPlaybackRoomStoreTest {
    @Test
    fun replayedPlaylistEventDoesNotIncrementAfterRoomAndRepositoryReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-playlist-event-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        fun repository(store: LocalPlaylistPlaybackRoomStore): LocalPlaylistPlaybackStatsRepository {
            val constructor = LocalPlaylistPlaybackStatsRepository::class.java.getDeclaredConstructor(Context::class.java, LocalPlaylistPlaybackRoomStore::class.java)
            constructor.isAccessible = true
            return constructor.newInstance(context, store)
        }
        var database = open()
        try {
            var store = LocalPlaylistPlaybackRoomStore(database)
            store.importLegacyAndPromote(listOf(LocalPlaylistPlaybackStat(42, totalPlayCount = 1, firstPlayedAt = 100, lastPlayedAt = 100)))
            repository(store).recordPlayNow(42, 200, "play-event")
            database.close()
            database = open()
            store = LocalPlaylistPlaybackRoomStore(database)
            val reopened = repository(store)
            reopened.recordPlayNow(42, 200, "play-event")
            assertEquals(2L, reopened.syncSnapshot().stats.single().totalPlayCount)
            assertEquals(2L, store.readIfRoomPrimary()?.single()?.totalPlayCount)
            val conflicting = repository(store)
            assertTrue(runCatching { conflicting.recordPlayNow(42, 201, "play-event") }.isFailure)
            assertTrue(conflicting.awaitInitialized())
            assertEquals(2L, conflicting.syncSnapshot().stats.single().totalPlayCount)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun playlistEventReceiptFailureRollsBackTheIncrementAndRetryCommitsBoth() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = LocalPlaylistPlaybackRoomStore(database)
            val before = listOf(LocalPlaylistPlaybackStat(42, totalPlayCount = 1, firstPlayedAt = 100, lastPlayedAt = 100))
            val next = listOf(before.single().copy(totalPlayCount = 2, lastPlayedAt = 200))
            store.importLegacyAndPromote(before)
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_playlist_receipt BEFORE INSERT ON playback_stats_event_receipt BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END")
            assertTrue(runCatching { store.writeIncrementalOnce(before, next, "event", "payload", 200) }.isFailure)
            assertEquals(before, store.readIfRoomPrimary())
            assertEquals(null, database.playbackStatsSnapshotDao().receipt("local-playlist-play:event"))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_playlist_receipt")
            assertTrue(store.writeIncrementalOnce(before, next, "event", "payload", 200))
            assertEquals(next, store.readIfRoomPrimary())
            assertTrue(database.playbackStatsSnapshotDao().receipt("local-playlist-play:event") != null)
        } finally { database.close() }
    }

    @Test
    fun playbackStatsAndDailyBucketsRoundTrip() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val stat = LocalPlaylistPlaybackStat(
                playlistId = 42L,
                totalPlayCount = 5L,
                firstPlayedAt = 100L,
                lastPlayedAt = 200L,
                counterBasePlayCount = 1L,
                counterShards = listOf(
                    SyncPlaybackCounterShard(
                        deviceId = "device-a",
                        playCount = 4,
                        firstPlayedAt = 100L,
                        lastPlayedAt = 200L
                    )
                ),
                dailyPlayBuckets = listOf(
                    LocalPlaylistPlayBucket(
                        dayStartAt = 86_400_000L,
                        playCount = 5L,
                        firstPlayedAt = 100L,
                        lastPlayedAt = 200L,
                        counterShards = listOf(
                            SyncPlaybackCounterShard(
                                deviceId = "device-a",
                                playCount = 4,
                                firstPlayedAt = 100L,
                                lastPlayedAt = 200L
                            )
                        )
                    )
                )
            )
            val store = LocalPlaylistPlaybackRoomStore(database)
            store.importLegacyAndPromote(listOf(stat))

            assertEquals(listOf(stat), store.readIfRoomPrimary())
            assertEquals(
                2,
                database.localPlaylistPlaybackDao().getCounterShards().size
            )
        } finally {
            database.close()
        }
    }
}
