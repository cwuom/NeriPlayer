package moe.ouom.neriplayer.data.playlist.favorite

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.FavoritePlaylistRoomStore
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException

class FavoritePlaylistRepositoryRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed Room load preserves primary and stale JSON until a successful retry`() = runTest {
        val primary = listOf(favorite(1, "Room favorite"))
        var stored = primary
        var readFails = true
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (readFails) throw IOException("Room read unavailable")
            stored
        }
        doAnswer { invocation ->
            stored = invocation.getArgument(0)
            Unit
        }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        val json = File(temporaryFolder.root, "favorite_playlists.json")
        json.writeText("[]")
        val repository = repository(room, backgroundScope)

        assertFalse(repository.awaitInitialized())
        assertEquals(primary, stored)
        assertEquals("[]", json.readText())
        assertFalse(repository.replaceFavoritesFromSyncIfUnchanged(emptyList(), 0L))
        assertTrue(runCatching { repository.getSyncSnapshots() }.exceptionOrNull() is IOException)
        assertEquals(primary, stored)

        readFails = false
        assertTrue(repository.awaitInitialized())
        assertEquals(primary, repository.favorites.value)
        assertEquals(primary, repository.getSyncSnapshots())
        assertEquals(primary, stored)
    }

    @Test
    fun `corrupt legacy JSON is preserved and can be repaired without restarting`() = runTest {
        var stored: List<FavoritePlaylist>? = null
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { stored }
        doAnswer { invocation ->
            stored = invocation.getArgument(0)
            Unit
        }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        val json = File(temporaryFolder.root, "favorite_playlists.json")
        json.writeText("{broken")
        val repository = repository(room, backgroundScope)

        assertFalse(repository.awaitInitialized())
        assertEquals(null, stored)
        assertEquals("{broken", json.readText())
        json.writeText("[]")
        assertTrue(repository.awaitInitialized())
        assertEquals(emptyList<FavoritePlaylist>(), stored)
        assertEquals(emptyList<FavoritePlaylist>(), repository.getSyncSnapshots())
    }

    @Test
    fun `first install with no JSON can promote an empty collection`() = runTest {
        var stored: List<FavoritePlaylist>? = null
        val room = mock(FavoritePlaylistRoomStore::class.java)
        doAnswer { invocation ->
            stored = invocation.getArgument(0)
            Unit
        }.`when`(room).importLegacyAndPromote(anyList(), anyLong())

        val repository = repository(room, backgroundScope)

        assertTrue(repository.awaitInitialized())
        assertEquals(emptyList<FavoritePlaylist>(), stored)
        assertEquals(emptyList<FavoritePlaylist>(), repository.getSyncSnapshots())
    }

    @Test
    fun `constructor does not read Room before the IO initialization runs`() = runTest {
        var readStarted = false
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer {
            readStarted = true
            listOf(favorite(1, "Room favorite"))
        }
        val repository = repository(room, backgroundScope)

        assertFalse(readStarted)
        assertTrue(runCatching { repository.getSyncSnapshots() }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        assertEquals("Room favorite", repository.favorites.value.single().name)
    }

    @Test
    fun `a fully loaded snapshot can still fall back to JSON on Room write failure`() = runTest {
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(favorite(1, "Before")))
        doAnswer { throw IOException("Room write unavailable") }.`when`(room)
            .writeIncremental(anyList(), anyList(), anyLong())
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())

        assertTrue(repository.replaceFavoritesFromSyncIfUnchanged(listOf(favorite(1, "After")), 0))

        assertEquals("After", repository.getSyncSnapshots().single().name)
        assertTrue(File(temporaryFolder.root, "favorite_playlists.json").readText().contains("After"))
    }

    @Test
    fun `failed fallback marker cannot report a successful synchronized replacement`() = runTest {
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(favorite(1, "Before")))
        doAnswer { throw IOException("Room write unavailable") }.`when`(room)
            .writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { throw IOException("Room metadata unavailable") }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())

        assertFalse(repository.replaceFavoritesFromSyncIfUnchanged(listOf(favorite(1, "After")), 0))
        assertTrue(runCatching { repository.getSyncSnapshots() }.exceptionOrNull() is IOException)
    }

    @Test
    fun `follow import persists the whole batch and leaves existing collections untouched`() = runTest {
        val original = favorite(1, "Existing playlist")
        var stored = listOf(original)
        var writes = 0
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { stored }
        doAnswer { invocation ->
            stored = invocation.getArgument(1)
            writes += 1
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        val repository = repository(room, backgroundScope)

        val added = repository.mergeFollowedArtists(
            FAVORITE_SOURCE_NETEASE_ARTIST,
            listOf(FavoriteArtist(1L, "Artist with same numeric ID"), FavoriteArtist(2L, "Second artist"), FavoriteArtist(2L, "Duplicate")),
            importStartedAt = 100L
        )

        assertEquals(2, added)
        assertEquals(1, writes)
        assertEquals(original, stored.single { it.source == "netease" })
        assertEquals(stored, repository.getSyncSnapshots())
        assertEquals(0, repository.mergeFollowedArtists(
            FAVORITE_SOURCE_NETEASE_ARTIST,
            listOf(FavoriteArtist(1L, "Already followed"), FavoriteArtist(2L, "Already followed")),
            importStartedAt = 100L
        ))
        assertEquals(1, writes)
    }

    @Test
    fun `follow import cannot replace unreadable primary data`() = runTest {
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("Room unavailable") }
        val json = File(temporaryFolder.root, "favorite_playlists.json")
        json.writeText("[]")
        val repository = repository(room, backgroundScope)

        val result = runCatching {
            repository.mergeFollowedArtists(FAVORITE_SOURCE_NETEASE_ARTIST, listOf(FavoriteArtist(1L, "Artist")), 100L)
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(repository.favorites.value.isEmpty())
        assertEquals("[]", json.readText())
    }

    @Test
    fun `failed follow import keeps visible records and requires the primary snapshot to be confirmed again`() = runTest {
        val original = listOf(favorite(1, "Before"))
        val room = mock(FavoritePlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(original)
        doAnswer { throw IOException("Room write unavailable") }.`when`(room)
            .writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { throw IOException("Room metadata unavailable") }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room, backgroundScope)

        val result = runCatching {
            repository.mergeFollowedArtists(FAVORITE_SOURCE_NETEASE_ARTIST, listOf(FavoriteArtist(2L, "Artist")), 100L)
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(original, repository.favorites.value)
        assertTrue(runCatching { repository.getSyncSnapshots() }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        assertEquals(original, repository.getSyncSnapshots())
    }

    @Test
    fun `committed then cancelled follow import recovers the entire batch before later writes`() = runTest {
        val original = listOf(favorite(1L, "Before"))
        val primary = temporaryFolder.newFile("room-favorites.json")
        primary.writeText(Gson().toJson(original))
        val room = committingThenCancellingStore(primary)
        val repository = repository(room, backgroundScope)

        val result = runCatching {
            repository.mergeFollowedArtists(
                FAVORITE_SOURCE_NETEASE_ARTIST,
                listOf(FavoriteArtist(2L, "Imported 2"), FavoriteArtist(3L, "Imported 3")),
                100L
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(setOf(1L, 2L, 3L), readPrimary(primary).map { it.id }.toSet())
        assertEquals(original, repository.favorites.value)
        assertTrue(runCatching { repository.getSyncSnapshots() }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        assertEquals(readPrimary(primary).toSet(), repository.getSyncSnapshots().toSet())

        repository.removeFavorite(2L, FAVORITE_SOURCE_NETEASE_ARTIST)

        assertEquals(setOf(1L, 3L), repository.favorites.value.map { it.id }.toSet())
        assertTrue(readPrimary(primary).single { it.id == 2L }.isDeleted)
        assertFalse(readPrimary(primary).single { it.id == 3L }.isDeleted)
        assertEquals(readPrimary(primary).toSet(), repository.getSyncSnapshots().toSet())
    }

    private suspend fun committingThenCancellingStore(primary: File): FavoritePlaylistRoomStore {
        val room = mock(FavoritePlaylistRoomStore::class.java)
        var cancelAfterCommit = true
        `when`(room.readIfRoomPrimary()).thenAnswer { readPrimary(primary) }
        doAnswer { invocation ->
            val previous = invocation.getArgument<List<FavoritePlaylist>>(0).associateBy { it.id to it.source }
            val next = invocation.getArgument<List<FavoritePlaylist>>(1).associateBy { it.id to it.source }
            val stored = readPrimary(primary).associateBy { it.id to it.source }.toMutableMap()
            (previous.keys - next.keys).forEach(stored::remove)
            next.forEach { (key, favorite) ->
                if (previous[key] != favorite) stored[key] = favorite
            }
            primary.writeText(Gson().toJson(stored.values.toList()))
            if (cancelAfterCommit) {
                cancelAfterCommit = false
                throw CancellationException("Room batch committed before cancellation")
            }
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        return room
    }

    private fun readPrimary(primary: File): List<FavoritePlaylist> = Gson().fromJson(
        primary.readText(),
        object : TypeToken<List<FavoritePlaylist>>() {}.type
    )

    private suspend fun repository(room: FavoritePlaylistRoomStore, scope: CoroutineScope): FavoritePlaylistRepository {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        val syncStorage = mock(SecureTokenStorage::class.java)
        doAnswer { invocation ->
            val writeSnapshot = invocation.getArgument<() -> Boolean>(0)
            if (writeSnapshot()) {
                runBlocking { room.markLegacyJsonPrimary() }
                true
            } else {
                false
            }
        }.`when`(room).commitLegacyFallback(any<() -> Boolean>() ?: { false })
        return FavoritePlaylistRepository(context, room, syncStorage, scope)
    }

    private fun favorite(id: Long, name: String) = FavoritePlaylist(
        id = id,
        name = name,
        coverUrl = null,
        trackCount = 0,
        source = "netease",
        songs = emptyList(),
        addedTime = 10,
        sortOrder = 10,
        modifiedAt = 10
    )
}
