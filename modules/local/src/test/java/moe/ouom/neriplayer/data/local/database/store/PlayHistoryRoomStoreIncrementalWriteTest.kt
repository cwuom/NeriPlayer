package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.PlayHistoryDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlayHistoryEntity
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn

class PlayHistoryRoomStoreIncrementalWriteTest {
    private val room = InlineTransactionDatabase()
    private val history = InMemoryPlayHistoryDao()
    private val store = PlayHistoryRoomStore(room.database)

    init {
        doReturn(history).`when`(room.database).playHistoryDao()
    }

    @Test
    fun `history is served from room only after the legacy import promoted it`() = runTest {
        assertNull(store.readIfRoomPrimary())
        store.markLegacyJsonPrimary(now = 4)
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, 4), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertNull(store.readIfRoomPrimary())

        val result = store.importLegacyAndPromote(listOf(fileEntry, streamEntry), now = 9)

        assertEquals(PlayHistoryRoomImportResult(PlayHistoryRoomImportStatus.IMPORTED, 2), result)
        assertEquals(listOf(streamEntry, fileEntry), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 9), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])
    }

    @Test
    fun `incremental writes upsert changed entries and delete dropped identities`() = runTest {
        store.replaceAll(listOf(fileEntry, streamEntry, bareEntry), now = 1)
        history.upserts.clear()
        val resumed = streamEntry.copy(resumePositionMs = 30_000, playedAt = 500)
        val added = bareEntry.copy(id = 4, name = "Added", playedAt = 600)

        store.writeIncremental(listOf(fileEntry, streamEntry, bareEntry), listOf(fileEntry, resumed, added), now = 2)

        assertEquals(listOf(listOf("2|Album|https://music.example/2", "4|Album|")), history.upserts)
        assertEquals(listOf(listOf("3|Album|")), history.deletions)
        assertEquals(listOf(added, resumed, fileEntry), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 2), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
    }

    @Test
    fun `an unchanged history only refreshes the cutover marker and clear empties the table`() = runTest {
        store.replaceAll(listOf(fileEntry), now = 1)
        history.upserts.clear()

        store.writeIncremental(listOf(fileEntry), listOf(fileEntry), now = 2)

        assertTrue(history.upserts.isEmpty() && history.deletions.isEmpty())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 2), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertEquals(listOf("/storage/emulated/0/Music/one.flac"), history.getAll().map { it.identityMediaUri })

        store.clear(now = 3)

        assertEquals(emptyList<PlayedEntry>(), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 3), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
    }

    private companion object {
        val fileEntry = PlayedEntry(
            id = 1,
            name = "One",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/1",
            localFileName = "one.flac",
            localFilePath = "/storage/emulated/0/Music/one.flac",
            playedAt = 100
        )
        val streamEntry = PlayedEntry(
            id = 2,
            name = "Two",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000,
            coverUrl = "https://img.example/2.jpg",
            mediaUri = "https://music.example/2",
            playedAt = 200
        )
        val bareEntry = PlayedEntry(
            id = 3,
            name = "Three",
            artist = "Artist",
            album = "Album",
            durationMs = 90_000,
            coverUrl = null,
            playedAt = 50
        )
    }
}

private class InMemoryPlayHistoryDao : PlayHistoryDao {
    private val rows = linkedMapOf<String, PlayHistoryEntity>()
    val upserts = mutableListOf<List<String>>()
    val deletions = mutableListOf<List<String>>()

    override suspend fun getAll() =
        rows.values.sortedWith(compareByDescending<PlayHistoryEntity> { it.playedAt }.thenBy { it.identityKey })

    override suspend fun upsert(entries: List<PlayHistoryEntity>) {
        upserts.add(entries.map { it.identityKey })
        entries.forEach { rows[it.identityKey] = it }
    }

    override suspend fun deleteByIdentityKeys(identityKeys: List<String>) {
        deletions.add(identityKeys)
        identityKeys.forEach(rows::remove)
    }

    override suspend fun deleteAll() = rows.clear()
}
