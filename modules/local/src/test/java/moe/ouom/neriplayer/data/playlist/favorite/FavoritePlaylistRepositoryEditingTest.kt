package moe.ouom.neriplayer.data.playlist.favorite

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.FavoritePlaylistRoomStore
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FavoritePlaylistRepositoryEditingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val syncStorage: SecureTokenStorage = mock(SecureTokenStorage::class.java)
    private val primary: File
        get() = File(temporaryFolder.root, "favorite_playlists.json")

    @Test
    fun `removing a favorite stores a tombstone and repeated removals change nothing`() = runTest {
        val gym = favorite(2L, "Gym", sortOrder = 20L)
        val repository = repository(
            backgroundScope,
            favorite(1L, "Road trip", sortOrder = 30L),
            gym,
            favorite(1L, "QQ road trip", sortOrder = 10L, source = "qq")
        )

        repository.removeFavorite(1L, "netease")
        val afterRemoval = primary.readText()
        repository.removeFavorite(1L, "netease")
        repository.removeFavorite(9L, "netease")

        assertFalse(repository.isFavorite(1L, "netease"))
        assertNull(repository.getFavorite(1L, "netease"))
        assertTrue(repository.isFavorite(1L, "qq"))
        assertEquals(gym, repository.getFavorite(2L, "netease"))
        assertEquals(afterRemoval, primary.readText())
        val tombstone = stored().single { it.id == 1L && it.source == "netease" }
        assertEquals(Triple(true, 0, 30L), Triple(tombstone.isDeleted, tombstone.trackCount, tombstone.sortOrder))
        verify(syncStorage, times(1)).markSyncMutation()
    }

    @Test
    fun `reordering moves listed favorites first and leaves tombstones alone`() = runTest {
        val removed = favorite(4L, "Removed", sortOrder = 5L).copy(isDeleted = true, trackCount = 0)
        val repository = repository(
            backgroundScope,
            favorite(1L, "Road trip", sortOrder = 30L),
            favorite(2L, "Gym", sortOrder = 20L),
            favorite(3L, "Chill", sortOrder = 10L),
            removed
        )

        repository.reorderFavorites(listOf("netease:3", "netease:1", "netease:3", "netease:9"))

        assertEquals(listOf(3L, 1L, 2L), repository.favorites.value.map(FavoritePlaylist::id))
        assertEquals(
            listOf(3L, 1L, 2L),
            stored().filterNot(FavoritePlaylist::isDeleted).sortedByDescending(FavoritePlaylist::sortOrder).map(FavoritePlaylist::id)
        )
        assertEquals(removed, stored().single(FavoritePlaylist::isDeleted))
    }

    @Test
    fun `reordering changes nothing without visible favorites or before a successful load`() = runTest {
        val empty = repository(backgroundScope)
        empty.reorderFavorites(listOf("netease:1"))
        assertTrue(empty.favorites.value.isEmpty())
        assertFalse(primary.exists())

        primary.writeText("{broken")
        val broken = repository(backgroundScope, loaded = false)
        broken.reorderFavorites(listOf("netease:1"))

        assertEquals("{broken", primary.readText())
        verify(syncStorage, never()).markSyncMutation()
    }

    @Test
    fun `sync replacements are stored without marking a local mutation`() = runTest {
        val repository = repository(backgroundScope, favorite(1L, "Road trip", sortOrder = 30L))

        repository.replaceFavoritesFromSync(
            listOf(favorite(2L, "Gym", sortOrder = 20L), favorite(3L, "Chill", sortOrder = 40L))
        )

        assertEquals(listOf(3L, 2L), repository.favorites.value.map(FavoritePlaylist::id))
        assertEquals(setOf(2L, 3L), stored().map(FavoritePlaylist::id).toSet())
        verify(syncStorage, never()).markSyncMutation()
    }

    @Test
    fun `sync replacements fail while the favorites cannot be loaded`() = runTest {
        primary.writeText("{broken")
        val repository = repository(backgroundScope, loaded = false)

        val failure = runCatching {
            repository.replaceFavoritesFromSync(listOf(favorite(2L, "Gym", sortOrder = 20L)))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("收藏歌单同步数据未能保存", failure?.message)
        assertEquals("{broken", primary.readText())
    }

    // Room never takes over here, so every write goes through the legacy JSON fallback
    private suspend fun repository(
        scope: CoroutineScope,
        vararg seed: FavoritePlaylist,
        loaded: Boolean = true
    ): FavoritePlaylistRepository {
        if (seed.isNotEmpty()) primary.writeText(Gson().toJson(seed.toList()))
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        val room = mock(FavoritePlaylistRoomStore::class.java)
        doAnswer { throw IOException("Room unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer { invocation -> invocation.getArgument<() -> Boolean>(0)() }
            .`when`(room).commitLegacyFallback(any<() -> Boolean>() ?: { false })
        return FavoritePlaylistRepository(context, room, syncStorage, scope).also { repository ->
            assertEquals(loaded, repository.awaitInitialized())
        }
    }

    private fun stored(): List<FavoritePlaylist> = Gson().fromJson(
        primary.readText(),
        object : TypeToken<List<FavoritePlaylist>>() {}.type
    )

    private fun favorite(id: Long, name: String, sortOrder: Long, source: String = "netease") = FavoritePlaylist(
        id = id,
        name = name,
        coverUrl = null,
        trackCount = 3,
        source = source,
        songs = emptyList(),
        addedTime = 10L,
        sortOrder = sortOrder,
        modifiedAt = 10L
    )
}
