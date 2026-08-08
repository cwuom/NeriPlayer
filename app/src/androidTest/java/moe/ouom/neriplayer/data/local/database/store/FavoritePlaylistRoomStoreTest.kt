package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoritePlaylistRoomStoreTest {
    @Test
    fun favoritePlaylistAndSongOrderRoundTrip() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val favorite = FavoritePlaylist(
                id = 42L,
                name = "Favorites",
                coverUrl = "https://example.invalid/cover.jpg",
                trackCount = 2,
                source = "netease",
                browseId = "browse-42",
                playlistId = "remote-42",
                subtitle = "subtitle",
                songs = listOf(
                    testSong(1L, "First"),
                    testSong(2L, "Second")
                ),
                addedTime = 10L,
                sortOrder = 20L,
                modifiedAt = 30L
            )
            val store = FavoritePlaylistRoomStore(database)
            store.importLegacyAndPromote(listOf(favorite))
            store.importLegacyAndPromote(emptyList())

            val snapshot = store.readIfRoomPrimary()
            assertNotNull(snapshot)
            assertEquals(listOf(favorite), snapshot)
            assertEquals(
                2,
                database.favoritePlaylistDao().getSongs().size
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun deletedFavoriteKeepsTombstoneWithoutSongs() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val favorite = FavoritePlaylist(
                id = 7L,
                name = "Removed",
                coverUrl = null,
                trackCount = 0,
                source = "bili",
                songs = emptyList(),
                addedTime = 1L,
                sortOrder = 2L,
                modifiedAt = 3L,
                isDeleted = true
            )
            val store = FavoritePlaylistRoomStore(database)
            store.importLegacyAndPromote(listOf(favorite))

            assertEquals(listOf(favorite), store.readIfRoomPrimary())
            assertEquals(0, database.favoritePlaylistDao().getSongs().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun incrementalWritePreservesUnchangedFavoritesAndReplacesChangedSongs() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val first = favorite(
                id = 1L,
                name = "First",
                songs = listOf(testSong(1L, "First song"))
            )
            val second = favorite(
                id = 2L,
                name = "Second",
                songs = listOf(testSong(2L, "Second song"))
            )
            val store = FavoritePlaylistRoomStore(database)
            store.replaceAll(listOf(first, second))

            val updatedFirst = first.copy(
                name = "First updated",
                songs = listOf(testSong(3L, "Replacement song")),
                modifiedAt = 20L
            )
            store.writeIncremental(
                previous = listOf(first, second),
                next = listOf(updatedFirst, second)
            )

            assertEquals(
                listOf(updatedFirst, second),
                store.readIfRoomPrimary()
            )
            assertEquals(2, database.favoritePlaylistDao().getSongs().size)

            store.writeIncremental(
                previous = listOf(updatedFirst, second),
                next = listOf(updatedFirst)
            )

            assertEquals(listOf(updatedFirst), store.readIfRoomPrimary())
            assertEquals(1, database.favoritePlaylistDao().getSongs().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun staleLegacyMarkerPromotesExistingRoomDataWithoutReplacingIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val favorite = favorite(
                id = 42L,
                name = "Existing Room Favorite",
                songs = listOf(testSong(1L, "Existing song"))
            )
            val store = FavoritePlaylistRoomStore(database)
            store.replaceAll(listOf(favorite), now = 10L)
            database.syncMetadataDao().upsertMigrationMetadata(
                MigrationMetadataEntity(
                    key = FavoritePlaylistRoomStore.CUTOVER_STATE_METADATA_KEY,
                    value = "legacy_json",
                    updatedAt = 20L
                )
            )

            assertNull(store.readIfRoomPrimary())
            assertEquals(
                listOf(favorite),
                store.promoteExistingAndRead(now = 30L)
            )
            assertEquals(listOf(favorite), store.readIfRoomPrimary())
            assertEquals(
                FavoritePlaylistRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(FavoritePlaylistRoomStore.CUTOVER_STATE_METADATA_KEY)
                    ?.value
            )
        } finally {
            database.close()
        }
    }

    private fun favorite(
        id: Long,
        name: String,
        songs: List<SongItem>
    ): FavoritePlaylist {
        return FavoritePlaylist(
            id = id,
            name = name,
            coverUrl = null,
            trackCount = songs.size,
            source = "netease",
            songs = songs,
            addedTime = id,
            sortOrder = 100L - id,
            modifiedAt = id
        )
    }

    private fun testSong(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = 3L,
            durationMs = 180_000L,
            coverUrl = "https://example.invalid/$id.jpg",
            mediaUri = "https://example.invalid/$id.mp3",
            matchedLyric = "lyric",
            customName = "custom-$name"
        )
    }
}
