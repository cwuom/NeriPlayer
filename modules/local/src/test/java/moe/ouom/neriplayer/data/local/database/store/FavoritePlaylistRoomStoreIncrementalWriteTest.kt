package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.FavoritePlaylistDao
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistSongEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.store.FavoritePlaylistRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.FavoritePlaylistRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.FavoritePlaylistRoomStore.Companion.LEGACY_JSON_STATE
import moe.ouom.neriplayer.data.local.database.store.FavoritePlaylistRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn

class FavoritePlaylistRoomStoreIncrementalWriteTest {
    private val room = InlineTransactionDatabase()
    private val favorites = InMemoryFavoritePlaylistDao()
    private val store = FavoritePlaylistRoomStore(room.database)

    init {
        doReturn(favorites).`when`(room.database).favoritePlaylistDao()
    }

    @Test
    fun `an import keeps the newest snapshot of each favorite and reads songs back in order`() = runTest {
        assertNull(store.readIfRoomPrimary())
        store.markLegacyJsonPrimary(now = 1)
        assertNull(store.readIfRoomPrimary())
        val olderMix = mix.copy(name = "Old mix", trackCount = 1, songs = listOf(first), modifiedAt = 20)

        store.importLegacyAndPromote(listOf(olderMix, mix, bili), now = 5)

        assertEquals(listOf(bili, mix), store.readIfRoomPrimary())
        assertEquals(
            listOf(1L to 0, 1L to 1),
            favorites.getSongs().map { it.playlistId to it.displayPosition }
        )
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 5), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 5), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])
    }

    @Test
    fun `incremental writes skip unchanged favorites and rewrite only changed ones`() = runTest {
        store.replaceAll(listOf(mix, bili), now = 1)
        room.transactionLog.clear()

        store.writeIncremental(listOf(mix, bili), listOf(mix, bili), now = 2)
        assertTrue(room.transactionLog.isEmpty())

        val trimmed = mix.copy(trackCount = 1, songs = listOf(second), modifiedAt = 50)
        val added = FavoritePlaylist(
            id = 3,
            name = "Liked",
            coverUrl = null,
            trackCount = 1,
            source = "youtubeMusic",
            songs = listOf(first),
            addedTime = 60
        )
        store.writeIncremental(listOf(mix, bili), listOf(trimmed, added), now = 3)

        assertEquals(listOf("playlist bili:7", "songs netease:1", "songs youtubeMusic:3"), favorites.deletions)
        assertEquals(listOf(added, trimmed), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 3), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
    }

    @Test
    fun `a legacy fallback is only marked once its snapshot was written`() = runTest {
        assertFalse(store.commitLegacyFallback { false })
        assertNull(room.metadata.value(CUTOVER_STATE_METADATA_KEY))

        assertTrue(store.commitLegacyFallback { true })
        assertEquals(LEGACY_JSON_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))
    }

    private companion object {
        val first = SongItem(
            id = 11,
            name = "First",
            artist = "Artist",
            album = "Album",
            albumId = 5,
            durationMs = 180_000,
            coverUrl = "https://img.example/11.jpg"
        )
        val second = SongItem(
            id = 12,
            name = "Second",
            artist = "Artist",
            album = "Album",
            albumId = 5,
            durationMs = 200_000,
            coverUrl = null,
            mediaUri = "https://music.example/12"
        )
        val mix = FavoritePlaylist(
            id = 1,
            name = "Mix",
            coverUrl = "https://img.example/mix.jpg",
            trackCount = 2,
            source = "netease",
            songs = listOf(first, second),
            addedTime = 10,
            modifiedAt = 40
        )
        val bili = FavoritePlaylist(
            id = 7,
            name = "Bili",
            coverUrl = null,
            trackCount = 0,
            source = "bili",
            browseId = "browse-7",
            playlistId = "PL7",
            subtitle = "Uploader",
            songs = emptyList(),
            addedTime = 30,
            isDeleted = true
        )
    }
}

private class InMemoryFavoritePlaylistDao : FavoritePlaylistDao {
    private val playlists = linkedMapOf<Pair<Long, String>, FavoritePlaylistEntity>()
    private val songs = linkedMapOf<Triple<Long, String, Int>, FavoritePlaylistSongEntity>()
    val deletions = mutableListOf<String>()

    override suspend fun getPlaylists() = playlists.values.sortedWith(
        compareByDescending<FavoritePlaylistEntity> { it.sortOrder }
            .thenByDescending { it.modifiedAt }
            .thenBy { it.source }
            .thenBy { it.playlistId }
    )

    override suspend fun getSongs() =
        songs.values.sortedWith(compareBy({ it.playlistId }, { it.source }, { it.displayPosition }))

    override suspend fun upsertPlaylists(playlists: List<FavoritePlaylistEntity>) {
        playlists.forEach { this.playlists[it.playlistId to it.source] = it }
    }

    override suspend fun upsertSongs(songs: List<FavoritePlaylistSongEntity>) {
        songs.forEach { this.songs[Triple(it.playlistId, it.source, it.displayPosition)] = it }
    }

    override suspend fun deleteAllSongs() = songs.clear()

    override suspend fun deleteAllPlaylists() = playlists.clear()

    override suspend fun deleteSongs(playlistId: Long, source: String) {
        deletions.add("songs $source:$playlistId")
        songs.keys.removeAll { it.first == playlistId && it.second == source }
    }

    override suspend fun deletePlaylist(playlistId: Long, source: String) {
        deletions.add("playlist $source:$playlistId")
        playlists.remove(playlistId to source)
    }
}
