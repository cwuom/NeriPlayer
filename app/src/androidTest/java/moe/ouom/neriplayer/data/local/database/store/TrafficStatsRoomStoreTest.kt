package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.traffic.TrafficStatsBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrafficStatsRoomStoreTest {
    @Test
    fun trafficBucketsRoundTripAndIncrementalDelete() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val bucket = TrafficStatsBucket(
                dayStartAt = 86_400_000L,
                wifiBytes = 10L,
                playbackNetworkBytes = 10L,
                requestCount = 1
            )
            val store = TrafficStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(bucket))
            store.importLegacyAndPromote(emptyList())
            assertNotNull(store.readIfRoomPrimary())
            assertEquals(listOf(bucket), store.readIfRoomPrimary())

            store.writeIncremental(
                previous = listOf(bucket),
                next = emptyList()
            )
            assertEquals(emptyList<TrafficStatsBucket>(), store.readIfRoomPrimary())
        } finally {
            database.close()
        }
    }
}
