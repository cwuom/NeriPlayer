package moe.ouom.neriplayer.data.playlist.favorite

import android.content.Context
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
