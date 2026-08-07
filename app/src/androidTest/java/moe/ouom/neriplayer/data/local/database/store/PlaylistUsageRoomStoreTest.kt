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
}
