package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.CoverUrlMappingDao
import moe.ouom.neriplayer.data.local.database.entity.CoverUrlMappingEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.store.CoverUrlMappingRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.CoverUrlMappingRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.CoverUrlMappingRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.local.database.store.CoverUrlMappingRoomStore.Companion.ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn

class CoverUrlMappingRoomStoreCutoverTest {
    private val room = InlineTransactionDatabase()
    private val mappings = InMemoryCoverUrlMappingDao()
    private val store = CoverUrlMappingRoomStore(room.database)

    init {
        doReturn(mappings).`when`(room.database).coverUrlMappingDao()
    }

    @Test
    fun `mappings are only read from room after an import promoted it`() = runTest {
        mappings.upsert(CoverUrlMappingEntity("content://stale", "https://img.example/stale.jpg", 1))
        assertNull(store.readIfRoomPrimary())
        room.metadata.put(CUTOVER_STATE_METADATA_KEY, "legacy_json")
        assertNull(store.readIfRoomPrimary())

        store.importLegacyAndPromote(mapOf("content://a" to "https://img.example/a.jpg"), cleanupEligible = true, now = 7)

        assertEquals(mapOf("content://a" to "https://img.example/a.jpg"), store.readIfRoomPrimary())
        assertEquals(listOf(CoverUrlMappingEntity("content://a", "https://img.example/a.jpg", 7)), mappings.getAll())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 7), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 7), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])

        store.importLegacyAndPromote(mapOf("content://b" to "https://img.example/b.jpg"), cleanupEligible = false, now = 8)

        assertEquals(ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))
        assertEquals(mapOf("content://b" to "https://img.example/b.jpg"), store.readIfRoomPrimary())
    }

    @Test
    fun `upserts promote room but keep a failed legacy import blocked from cleanup`() = runTest {
        store.upsert("content://a", "https://img.example/a.jpg", cleanupEligible = true, now = 3)
        assertEquals(ROOM_PRIMARY_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))

        store.upsert("content://a", "https://img.example/a2.jpg", cleanupEligible = false, now = 4)
        assertEquals(ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))

        store.upsert("content://b", "https://img.example/b.jpg", cleanupEligible = true, now = 5)

        assertEquals(ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 5), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])
        assertEquals(
            listOf(
                CoverUrlMappingEntity("content://a", "https://img.example/a2.jpg", 4),
                CoverUrlMappingEntity("content://b", "https://img.example/b.jpg", 5)
            ),
            mappings.getAll()
        )
        assertEquals(listOf("begin", "commit", "end").let { it + it + it }, room.transactionLog)
    }

    @Test
    fun `deletes drop blank urls and skip the transaction when nothing is left`() = runTest {
        mappings.upsert(
            listOf(
                CoverUrlMappingEntity("content://a", "https://img.example/a.jpg", 1),
                CoverUrlMappingEntity("content://b", "https://img.example/b.jpg", 1),
                CoverUrlMappingEntity("content://c", "https://img.example/c.jpg", 1)
            )
        )

        store.delete(listOf("", "  "), cleanupEligible = true, now = 2)
        assertTrue(room.transactionLog.isEmpty() && room.metadata.rows.isEmpty())

        store.delete(listOf("content://a", " "), cleanupEligible = true, now = 3)
        assertEquals(ROOM_PRIMARY_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))
        store.delete(listOf("content://b"), cleanupEligible = false, now = 4)
        assertEquals(ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))
        store.delete(listOf("content://missing"), cleanupEligible = true, now = 5)

        assertEquals(ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))
        assertEquals(listOf(CoverUrlMappingEntity("content://c", "https://img.example/c.jpg", 1)), mappings.getAll())
        assertEquals(listOf(listOf("content://a"), listOf("content://b"), listOf("content://missing")), mappings.deletions)
    }
}

private class InMemoryCoverUrlMappingDao : CoverUrlMappingDao {
    private val rows = linkedMapOf<String, CoverUrlMappingEntity>()
    val deletions = mutableListOf<List<String>>()

    override suspend fun getAll() = rows.values.toList()

    override suspend fun upsert(entity: CoverUrlMappingEntity) {
        rows[entity.localUrl] = entity
    }

    override suspend fun upsert(entities: List<CoverUrlMappingEntity>) {
        entities.forEach { rows[it.localUrl] = it }
    }

    override suspend fun delete(localUrls: List<String>) {
        deletions.add(localUrls)
        localUrls.forEach(rows::remove)
    }

    override suspend fun deleteAll() = rows.clear()
}
