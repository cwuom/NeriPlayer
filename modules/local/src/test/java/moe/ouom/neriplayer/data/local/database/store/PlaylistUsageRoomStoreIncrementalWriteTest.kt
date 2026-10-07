package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaylistUsageDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaylistUsageCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaylistUsageEntity
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn

class PlaylistUsageRoomStoreIncrementalWriteTest {
    private val room = InlineTransactionDatabase()
    private val usage = InMemoryPlaylistUsageDao()
    private val store = PlaylistUsageRoomStore(room.database)

    init {
        doReturn(usage).`when`(room.database).playlistUsageDao()
    }

    @Test
    fun `usage is read from room with shards and deletion proofs once an import promoted it`() = runTest {
        assertNull(store.readIfRoomPrimary())
        store.markLegacyJsonPrimary(now = 2)
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, 2), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertNull(store.readIfRoomPrimary())

        store.importLegacyAndPromote(listOf(album, artist), now = 6)

        assertEquals(listOf(artist, album), store.readIfRoomPrimary())
        assertEquals(
            listOf(
                PlaylistUsageCounterShardEntity("local:2:artist", "phone", 1, 2, 10, 300),
                PlaylistUsageCounterShardEntity("local:2:artist", "tablet", 4, 1, 250, 250)
            ),
            usage.getCounterShards()
        )
        assertEquals("""[{"deviceId":"phone","counter":3}]""", usage.getEntries().first().usageDeletionTokensJson)
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 6), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])
    }

    @Test
    fun `incremental writes replace shards of changed entries and delete removed ones`() = runTest {
        val removed = UsageEntry(id = 3, name = "Old", picUrl = null, trackCount = 1, source = "bili", lastOpened = 50, openCount = 1)
        store.replaceAll(listOf(album, artist, removed), now = 1)
        val reopened = artist.copy(
            lastOpened = 700,
            openCount = 4,
            counterShards = listOf(SyncPlaybackCounterShard("phone", 1, 0, 3, 10, 700))
        )
        val added = UsageEntry(id = 9, name = "New", picUrl = null, trackCount = 2, source = "netease", lastOpened = 800, openCount = 1)

        store.writeIncremental(listOf(album, artist, removed), listOf(album, reopened, added), now = 2)

        assertEquals(listOf("shards[bili:3]", "entries[bili:3]", "shards[local:2:artist, netease:9]"), usage.deletions)
        assertEquals(listOf(added, reopened, album), store.readIfRoomPrimary())
        assertEquals(
            listOf(PlaylistUsageCounterShardEntity("local:2:artist", "phone", 1, 3, 10, 700)),
            usage.getCounterShards()
        )
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 2), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
    }

    private companion object {
        val album = UsageEntry(
            id = 1,
            name = "Album",
            picUrl = "https://img.example/album.jpg",
            trackCount = 12,
            source = "neteaseAlbum",
            lastOpened = 100,
            openCount = 2,
            firstOpened = 40
        )
        val artist = UsageEntry(
            id = 2,
            name = "Artist",
            picUrl = null,
            trackCount = 30,
            source = "local",
            lastOpened = 300,
            openCount = 3,
            firstOpened = 10,
            counterBaseOpenCount = 0,
            counterShards = listOf(
                SyncPlaybackCounterShard("phone", 1, 0, 2, 10, 300),
                SyncPlaybackCounterShard("tablet", 4, 0, 1, 250, 250)
            ),
            subtype = "artist",
            observedDeletionTokens = listOf(SyncCausalToken("phone", 3))
        )
    }
}

private class InMemoryPlaylistUsageDao : PlaylistUsageDao {
    private val entries = linkedMapOf<String, PlaylistUsageEntity>()
    private val shards = linkedMapOf<List<Any>, PlaylistUsageCounterShardEntity>()
    val deletions = mutableListOf<String>()

    override suspend fun getEntries() =
        entries.values.sortedWith(compareByDescending<PlaylistUsageEntity> { it.lastOpened }.thenBy { it.usageKey })

    override suspend fun getCounterShards() = shards.values.sortedWith(compareBy({ it.usageKey }, { it.deviceId }))

    override suspend fun upsertEntries(entries: List<PlaylistUsageEntity>) {
        entries.forEach { this.entries[it.usageKey] = it }
    }

    override suspend fun upsertCounterShards(shards: List<PlaylistUsageCounterShardEntity>) {
        shards.forEach { this.shards[listOf(it.usageKey, it.deviceId, it.epochStartedAt)] = it }
    }

    override suspend fun deleteEntries(usageKeys: List<String>) {
        deletions.add("entries$usageKeys")
        entries.keys.removeAll(usageKeys.toSet())
    }

    override suspend fun deleteCounterShards(usageKeys: List<String>) {
        deletions.add("shards$usageKeys")
        shards.values.removeAll { it.usageKey in usageKeys }
    }

    override suspend fun deleteAllCounterShards() = shards.clear()

    override suspend fun deleteAllEntries() = entries.clear()
}
