package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryNeteaseRefreshOwnerTest {
    @Test
    fun `refresh stays active until albums finish after playlists`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val albums = CompletableDeferred<List<AlbumSummary>>()
        val owner = LibraryNeteaseRefreshOwner(
            scope = backgroundScope,
            state = state,
            loadUserId = { 7L },
            loadPlaylists = { listOf(playlist(11L)) },
            loadAlbums = { albums.await() }
        )

        owner.refresh()
        assertTrue(state.value.neteaseRefreshing)
        testScheduler.runCurrent()
        assertTrue(state.value.neteaseRefreshing)

        albums.complete(listOf(album(21L)))
        testScheduler.runCurrent()

        assertFalse(state.value.neteaseRefreshing)
        assertEquals(listOf(11L), state.value.neteasePlaylists.map { it.id })
        assertEquals(listOf(21L), state.value.neteaseAlbums.map { it.id })
        assertNull(state.value.neteaseError)
    }

    @Test
    fun `refresh stays active until playlists finish after albums`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val playlists = CompletableDeferred<List<PlaylistSummary>>()
        val owner = LibraryNeteaseRefreshOwner(
            scope = backgroundScope,
            state = state,
            loadUserId = { 7L },
            loadPlaylists = { playlists.await() },
            loadAlbums = { listOf(album(21L)) }
        )

        owner.refresh()
        testScheduler.runCurrent()
        assertTrue(state.value.neteaseRefreshing)

        playlists.complete(listOf(playlist(11L)))
        testScheduler.runCurrent()

        assertFalse(state.value.neteaseRefreshing)
        assertEquals(listOf(11L), state.value.neteasePlaylists.map { it.id })
        assertEquals(listOf(21L), state.value.neteaseAlbums.map { it.id })
    }

    @Test
    fun `repeated refreshes share one request pair and completed refresh can run again`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val playlists = CompletableDeferred<List<PlaylistSummary>>()
        val albums = CompletableDeferred<List<AlbumSummary>>()
        var userLoads = 0
        var playlistLoads = 0
        var albumLoads = 0
        val owner = LibraryNeteaseRefreshOwner(
            scope = backgroundScope,
            state = state,
            loadUserId = { userLoads++; 7L },
            loadPlaylists = { userId ->
                assertEquals(7L, userId)
                playlistLoads++
                playlists.await()
            },
            loadAlbums = { userId ->
                assertEquals(7L, userId)
                albumLoads++
                albums.await()
            }
        )

        owner.refresh()
        owner.refresh()
        testScheduler.runCurrent()
        owner.refresh()
        testScheduler.runCurrent()

        assertTrue(state.value.neteaseRefreshing)
        assertEquals(1, userLoads)
        assertEquals(1, playlistLoads)
        assertEquals(1, albumLoads)

        playlists.complete(listOf(playlist(11L)))
        albums.complete(listOf(album(21L)))
        testScheduler.runCurrent()
        assertFalse(state.value.neteaseRefreshing)

        owner.refresh()
        testScheduler.runCurrent()

        assertEquals(2, userLoads)
        assertEquals(2, playlistLoads)
        assertEquals(2, albumLoads)
        assertFalse(state.value.neteaseRefreshing)
        assertEquals(listOf(11L), state.value.neteasePlaylists.map { it.id })
        assertEquals(listOf(21L), state.value.neteaseAlbums.map { it.id })
    }

    @Test
    fun `one failed request keeps cached data and waits for the other result`() = runTest {
        val state = MutableStateFlow(LibraryUiState(neteasePlaylists = listOf(playlist(9L))))
        val albums = CompletableDeferred<List<AlbumSummary>>()
        val owner = LibraryNeteaseRefreshOwner(
            scope = backgroundScope,
            state = state,
            loadUserId = { 7L },
            loadPlaylists = { throw IOException("playlists unavailable") },
            loadAlbums = { albums.await() }
        )

        owner.refresh()
        testScheduler.runCurrent()
        assertTrue(state.value.neteaseRefreshing)

        albums.complete(listOf(album(21L)))
        testScheduler.runCurrent()

        assertFalse(state.value.neteaseRefreshing)
        assertEquals(listOf(9L), state.value.neteasePlaylists.map { it.id })
        assertEquals(listOf(21L), state.value.neteaseAlbums.map { it.id })
        assertEquals("playlists unavailable", state.value.neteaseError)
    }

    @Test
    fun `user lookup failure releases refresh and a later attempt clears the error`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        var lookupFails = true
        var playlistLoads = 0
        var albumLoads = 0
        val owner = LibraryNeteaseRefreshOwner(
            scope = backgroundScope,
            state = state,
            loadUserId = { if (lookupFails) throw IOException("user unavailable") else 7L },
            loadPlaylists = { playlistLoads++; listOf(playlist(11L)) },
            loadAlbums = { albumLoads++; listOf(album(21L)) }
        )

        owner.refresh()
        testScheduler.runCurrent()

        assertFalse(state.value.neteaseRefreshing)
        assertEquals("user unavailable", state.value.neteaseError)
        assertEquals(0, playlistLoads)
        assertEquals(0, albumLoads)

        lookupFails = false
        owner.refresh()
        testScheduler.runCurrent()

        assertFalse(state.value.neteaseRefreshing)
        assertNull(state.value.neteaseError)
        assertEquals(listOf(11L), state.value.neteasePlaylists.map { it.id })
        assertEquals(listOf(21L), state.value.neteaseAlbums.map { it.id })
    }

    @Test
    fun `cancelled refresh cannot end or overwrite its replacement`() = runTest {
        val state = MutableStateFlow(LibraryUiState())
        val oldPlaylists = CompletableDeferred<List<PlaylistSummary>>()
        val oldAlbums = CompletableDeferred<List<AlbumSummary>>()
        val newPlaylists = CompletableDeferred<List<PlaylistSummary>>()
        val newAlbums = CompletableDeferred<List<AlbumSummary>>()
        var userLoads = 0
        val owner = LibraryNeteaseRefreshOwner(
            scope = backgroundScope,
            state = state,
            loadUserId = { userLoads++; userLoads.toLong() },
            loadPlaylists = { if (it == 1L) oldPlaylists.await() else newPlaylists.await() },
            loadAlbums = { if (it == 1L) oldAlbums.await() else newAlbums.await() }
        )

        owner.refresh()
        testScheduler.runCurrent()
        owner.cancel()
        assertFalse(state.value.neteaseRefreshing)
        owner.refresh()
        testScheduler.runCurrent()
        assertTrue(state.value.neteaseRefreshing)

        oldPlaylists.complete(listOf(playlist(1L)))
        oldAlbums.complete(listOf(album(2L)))
        testScheduler.runCurrent()

        assertTrue(state.value.neteaseRefreshing)
        assertTrue(state.value.neteasePlaylists.isEmpty())
        assertTrue(state.value.neteaseAlbums.isEmpty())

        newPlaylists.complete(listOf(playlist(11L)))
        newAlbums.complete(listOf(album(21L)))
        testScheduler.runCurrent()

        assertFalse(state.value.neteaseRefreshing)
        assertEquals(listOf(11L), state.value.neteasePlaylists.map { it.id })
        assertEquals(listOf(21L), state.value.neteaseAlbums.map { it.id })
    }

    private fun playlist(id: Long) = PlaylistSummary(id, "Playlist $id", "", 0L, 1)

    private fun album(id: Long) = AlbumSummary(id, "Album $id", "", 1)
}
