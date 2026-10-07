package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.stats.TrafficStatsDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.TrafficStatsBucketEntity
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.model.traffic.TrafficStatsBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn

class TrafficStatsRoomStoreIncrementalWriteTest {
    private val room = InlineTransactionDatabase()
    private val traffic = InMemoryTrafficStatsDao()
    private val store = TrafficStatsRoomStore(room.database)

    init {
        doReturn(traffic).`when`(room.database).trafficStatsDao()
    }

    @Test
    fun `buckets are read from room only once an import promoted it`() = runTest {
        assertNull(store.readIfRoomPrimary())
        store.markLegacyJsonPrimary(now = 3)
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, 3), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertNull(store.readIfRoomPrimary())

        store.importLegacyAndPromote(listOf(secondDay, firstDay), now = 4)

        assertEquals(listOf(firstDay, secondDay), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 4), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 4), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])
    }

    @Test
    fun `incremental writes only replace the days that changed`() = runTest {
        val thirdDay = TrafficStatsBucket(dayStartAt = 3 * DAY, mobileBytes = 70, requestCount = 1)
        store.replaceAll(listOf(firstDay, secondDay), now = 1)
        val grown = secondDay.copy(wifiBytes = 900, requestCount = 6)

        store.writeIncremental(listOf(firstDay, secondDay), listOf(grown, thirdDay), now = 2)

        assertEquals(listOf(listOf(DAY, 2 * DAY, 3 * DAY)), traffic.deletions)
        assertEquals(listOf(grown, thirdDay), store.readIfRoomPrimary())
        assertEquals(
            TrafficStatsBucketEntity(2 * DAY, 900, 20, 0, 300, 40, 50, 6, 2),
            traffic.getAll().first()
        )
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 2), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
    }

    @Test
    fun `clearing drops every bucket but keeps room primary`() = runTest {
        store.replaceAll(listOf(firstDay), now = 1)

        store.clear(now = 5)

        assertEquals(emptyList<TrafficStatsBucket>(), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 5), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertEquals(listOf("begin", "commit", "end", "begin", "commit", "end"), room.transactionLog)
    }

    private companion object {
        const val DAY = 86_400_000L
        val firstDay = TrafficStatsBucket(dayStartAt = DAY, wifiBytes = 100, mobileBytes = 10, playbackNetworkBytes = 80, requestCount = 2)
        val secondDay = TrafficStatsBucket(
            dayStartAt = 2 * DAY,
            wifiBytes = 500,
            mobileBytes = 20,
            playbackNetworkBytes = 300,
            downloadNetworkBytes = 40,
            cacheHitBytes = 50,
            requestCount = 4,
            cacheHitCount = 2
        )
    }
}

private class InMemoryTrafficStatsDao : TrafficStatsDao {
    private val rows = linkedMapOf<Long, TrafficStatsBucketEntity>()
    val deletions = mutableListOf<List<Long>>()

    override suspend fun getAll() = rows.values.sortedBy { it.dayStartAt }

    override suspend fun upsert(buckets: List<TrafficStatsBucketEntity>) {
        buckets.forEach { rows[it.dayStartAt] = it }
    }

    override suspend fun delete(dayStartAt: List<Long>) {
        deletions.add(dayStartAt)
        dayStartAt.forEach(rows::remove)
    }

    override suspend fun deleteAll() = rows.clear()
}
