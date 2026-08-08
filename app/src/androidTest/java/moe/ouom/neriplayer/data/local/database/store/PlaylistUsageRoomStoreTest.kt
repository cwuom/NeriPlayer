package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistUsageRoomStoreTest {
    @Test
    fun usageEntryAndCounterShardRoundTrip() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val entry = UsageEntry(
                id = 7L,
                name = "playlist",
                picUrl = "cover",
                trackCount = 3,
                source = "bili",
                lastOpened = 200L,
                openCount = 4,
                firstOpened = 100L,
                counterBaseOpenCount = 1L,
                counterShards = listOf(
                    SyncPlaybackCounterShard(
                        deviceId = "device-a",
                        epochStartedAt = 0L,
                        playCount = 3,
                        firstPlayedAt = 100L,
                        lastPlayedAt = 200L
                    )
                ),
                subtype = "COLLECTION",
                subtitle = "UP 主"
            )
            val store = PlaylistUsageRoomStore(database)
            store.importLegacyAndPromote(listOf(entry))
            store.importLegacyAndPromote(emptyList())

            assertEquals(listOf(entry), store.readIfRoomPrimary())
            assertEquals(
                1,
                database.playlistUsageDao().getCounterShards().size
            )
            assertTrue(
                database.syncMetadataDao().getMigrationMetadata(
                    PlaylistUsageRoomStore.CUTOVER_STATE_METADATA_KEY
                ) != null
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun incrementalWriteUpdatesOnlyChangedUsageKeys() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val first = UsageEntry(
                id = 1L,
                name = "first",
                picUrl = null,
                trackCount = 1,
                source = "netease",
                lastOpened = 100L,
                openCount = 1
            )
            val second = UsageEntry(
                id = 2L,
                name = "second",
                picUrl = "cover",
                trackCount = 2,
                source = "bili",
                lastOpened = 200L,
                openCount = 2,
                counterShards = listOf(
                    SyncPlaybackCounterShard(
                        deviceId = "device-a",
                        epochStartedAt = 0L,
                        playCount = 2,
                        firstPlayedAt = 100L,
                        lastPlayedAt = 200L
                    )
                )
            )
            val store = PlaylistUsageRoomStore(database)
            store.replaceAll(listOf(first, second))

            val updatedFirst = first.copy(
                name = "first updated",
                lastOpened = 300L,
                openCount = 3
            )
            store.writeIncremental(
                previous = listOf(first, second),
                next = listOf(updatedFirst, second)
            )

            assertEquals(
                listOf(updatedFirst, second),
                store.readIfRoomPrimary()
            )
            assertEquals(
                1,
                database.playlistUsageDao().getCounterShards().size
            )

            store.writeIncremental(
                previous = listOf(updatedFirst, second),
                next = listOf(updatedFirst)
            )

            assertEquals(listOf(updatedFirst), store.readIfRoomPrimary())
            assertTrue(database.playlistUsageDao().getCounterShards().isEmpty())
        } finally {
            database.close()
        }
    }
}
