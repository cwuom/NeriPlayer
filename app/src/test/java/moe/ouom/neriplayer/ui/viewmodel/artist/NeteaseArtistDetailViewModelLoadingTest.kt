package moe.ouom.neriplayer.ui.viewmodel.artist

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NeteaseArtistDetailViewModelLoadingTest {
    private val viewModels = mutableListOf<NeteaseArtistDetailViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load maps artist header songs and albums and skips invalid rows`() = runTest {
        val fixture = fixture()
        fixture.detail = RICH_DETAIL
        fixture.songs = RICH_SONGS
        fixture.albums = RICH_ALBUMS

        fixture.viewModel.start(NeteaseArtistSummary(7L, "Fallback"))
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertFalse(state.loading)
        assertNull(state.error)
        val header = state.header!!
        assertEquals(7L, header.id)
        assertEquals("Rich", header.name)
        assertEquals("https://p1.music.126.net/cover.jpg", header.coverUrl)
        assertEquals("https://p1.music.126.net/avatar.jpg", header.avatarUrl)
        assertEquals("Alias One / Alias Two", header.alias)
        assertEquals("Brief", header.briefDesc)
        assertEquals(12, header.musicSize)
        assertEquals(3, header.albumSize)
        assertEquals(listOf(11L, 13L), state.songs.map { it.id })
        val first = state.songs.first()
        assertEquals("Singer A / Singer B", first.artist)
        assertEquals("${PlayerManager.NETEASE_SOURCE_TAG}Album A", first.album)
        assertEquals(21L, first.albumId)
        assertEquals(180_000L, first.durationMs)
        assertEquals("https://p1.music.126.net/a.jpg", first.coverUrl)
        assertEquals("netease", first.channelId)
        assertEquals("11", first.audioId)
        assertNull(state.songs[1].coverUrl)
        assertEquals("${PlayerManager.NETEASE_SOURCE_TAG}", state.songs[1].album)
        assertEquals(
            listOf(
                AlbumSummary(31L, "Album X", "https://p1.music.126.net/x.jpg", 10),
                AlbumSummary(33L, "Album Y", "", 2)
            ),
            state.albums
        )
        assertTrue(state.songsHasMore)
        assertFalse(state.albumsHasMore)
    }

    @Test
    fun `artist without optional fields falls back to the requested summary`() = runTest {
        val fixture = fixture()
        fixture.detail = """{"code":200}"""
        fixture.songs = """{"code":200}"""
        fixture.albums = """{"code":200}"""

        fixture.viewModel.start(NeteaseArtistSummary(9L, "Requested"))
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        val header = state.header!!
        assertEquals(9L, header.id)
        assertEquals("Requested", header.name)
        assertEquals("", header.coverUrl)
        assertEquals("", header.avatarUrl)
        assertEquals("", header.alias)
        assertEquals(0, header.musicSize)
        assertTrue(state.songs.isEmpty())
        assertTrue(state.albums.isEmpty())
        assertFalse(state.songsHasMore)
    }

    @Test
    fun `API error code is reported through the load failure message`() = runTest {
        val fixture = fixture()
        fixture.detail = """{"code":500}"""

        fixture.viewModel.start(NeteaseArtistSummary(5L, "Broken"))
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("Load failed: API code 500", state.error)
        assertEquals("Broken", state.header!!.name)
    }

    @Test
    fun `exceptions without a message fall back to the exception type`() = runTest {
        val fixture = fixture()
        fixture.detailFailure = IOException()

        fixture.viewModel.start(NeteaseArtistSummary(5L, "Offline"))
        advanceUntilIdle()

        assertEquals("Load failed: IOException", fixture.viewModel.uiState.value.error)
    }

    @Test
    fun `starting the same artist again keeps the loaded content`() = runTest {
        val fixture = fixture()
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        advanceUntilIdle()

        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        advanceUntilIdle()

        verify(fixture.client, times(1)).getArtistDetail(1L)
    }

    @Test
    fun `forced refresh and different artists always reload`() = runTest {
        val fixture = fixture()
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Artist 1"))
        advanceUntilIdle()

        fixture.viewModel.retry()
        advanceUntilIdle()
        fixture.viewModel.start(NeteaseArtistSummary(2L, "Artist 2"))
        advanceUntilIdle()

        verify(fixture.client, times(2)).getArtistDetail(1L)
        verify(fixture.client, times(1)).getArtistDetail(2L)
        assertEquals(2L, fixture.viewModel.uiState.value.header!!.id)
    }

    @Test
    fun `remote artist id mismatch prevents reusing the current header`() = runTest {
        val fixture = fixture()
        fixture.detail = """{"code":200,"artist":{"id":99,"name":"Canonical"}}"""
        fixture.viewModel.start(NeteaseArtistSummary(1L, "Alias id"))
        advanceUntilIdle()

        fixture.viewModel.start(NeteaseArtistSummary(1L, "Alias id"))
        advanceUntilIdle()

        verify(fixture.client, times(2)).getArtistDetail(1L)
    }

    @Test
    fun `artist id zero is never treated as the current artist`() = runTest {
        val fixture = fixture()

        fixture.viewModel.start(NeteaseArtistSummary(0L, "Unknown"))
        advanceUntilIdle()

        verify(fixture.client, times(1)).getArtistDetail(0L)
    }

    @Test
    fun `retry without a header does nothing`() = runTest {
        val fixture = fixture()

        fixture.viewModel.retry()
        advanceUntilIdle()

        verify(fixture.client, never()).getArtistDetail(anyLong())
    }

    @Test
    fun `load more songs appends the next page and advances the offset`() = runTest {
        val fixture = fixture()
        fixture.songs = """{"code":200,"more":true,"songs":[{"id":1,"name":"One"}]}"""
        fixture.viewModel.start(NeteaseArtistSummary(3L, "Artist"))
        advanceUntilIdle()
        fixture.songs = """{"code":200,"more":false,"songs":[{"id":2,"name":"Two"}]}"""

        fixture.viewModel.loadMoreSongs()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(listOf(1L, 2L), state.songs.map { it.id })
        assertFalse(state.songsHasMore)
        assertFalse(state.songsLoadingMore)
        verify(fixture.client).getArtistSongs(3L, "hot", 1, 50)

        fixture.viewModel.loadMoreSongs()
        advanceUntilIdle()
        verify(fixture.client, never()).getArtistSongs(3L, "hot", 2, 50)
    }

    @Test
    fun `load more songs failure clears the loading flag and keeps songs`() = runTest {
        val fixture = fixture()
        fixture.songs = """{"code":200,"more":true,"songs":[{"id":1,"name":"One"}]}"""
        fixture.viewModel.start(NeteaseArtistSummary(3L, "Artist"))
        advanceUntilIdle()
        fixture.songs = """{"code":301}"""

        fixture.viewModel.loadMoreSongs()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(listOf(1L), state.songs.map { it.id })
        assertTrue(state.songsHasMore)
        assertFalse(state.songsLoadingMore)
    }

    @Test
    fun `paging is ignored before an artist is loaded`() = runTest {
        val fixture = fixture()

        fixture.viewModel.loadMoreSongs()
        fixture.viewModel.loadMoreAlbums()
        advanceUntilIdle()

        verify(fixture.client, never()).getArtistSongs(anyLong(), anyString(), anyInt(), anyInt())
        verify(fixture.client, never()).getArtistAlbums(anyLong(), anyInt(), anyInt())
    }

    @Test
    fun `load more albums appends the next page and failure keeps the old page`() = runTest {
        val fixture = fixture()
        fixture.albums = """{"code":200,"more":true,"hotAlbums":[{"id":1,"name":"First"}]}"""
        fixture.viewModel.start(NeteaseArtistSummary(4L, "Artist"))
        advanceUntilIdle()
        fixture.albums = """{"code":200,"more":true,"hotAlbums":[{"id":2,"name":"Second"}]}"""

        fixture.viewModel.loadMoreAlbums()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), fixture.viewModel.uiState.value.albums.map { it.id })
        verify(fixture.client).getArtistAlbums(4L, 1, 30)

        fixture.albums = "not json"
        fixture.viewModel.loadMoreAlbums()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(listOf(1L, 2L), state.albums.map { it.id })
        assertFalse(state.albumsLoadingMore)
        assertTrue(state.albumsHasMore)
        verify(fixture.client).getArtistAlbums(4L, 2, 30)
    }

    @Test
    fun `following prefers avatar and alias for the saved favorite`() = runTest {
        val fixture = fixture()
        fixture.detail = """{"code":200,"artist":{"id":8,"name":"Artist",
            "avatar":"https://img/avatar.jpg","cover":"https://img/cover.jpg",
            "alias":["Alias"],"briefDesc":"Brief","musicSize":40}}"""
        fixture.viewModel.start(NeteaseArtistSummary(8L, "Artist"))
        advanceUntilIdle()

        fixture.viewModel.toggleFollow()
        advanceUntilIdle()

        val saved = fixture.addedFavorites.single()
        assertEquals(8L, saved.id)
        assertEquals("https://img/avatar.jpg", saved.coverUrl)
        assertEquals("Alias", saved.subtitle)
        assertEquals(40, saved.trackCount)
        assertTrue(fixture.viewModel.uiState.value.header!!.followed)
        assertFalse(fixture.viewModel.uiState.value.followUpdating)
    }

    @Test
    fun `following falls back to cover and brief description`() = runTest {
        val fixture = fixture()
        fixture.detail = """{"code":200,"artist":{"id":8,"name":"Artist",
            "cover":"https://img/cover.jpg","briefDesc":"Brief"}}"""
        fixture.viewModel.start(NeteaseArtistSummary(8L, "Artist"))
        advanceUntilIdle()

        fixture.viewModel.toggleFollow()
        advanceUntilIdle()

        val saved = fixture.addedFavorites.single()
        assertEquals("https://img/cover.jpg", saved.coverUrl)
        assertEquals("Brief", saved.subtitle)
    }

    @Test
    fun `following an artist without artwork or description saves null metadata`() = runTest {
        val fixture = fixture()
        fixture.viewModel.start(NeteaseArtistSummary(8L, "Artist"))
        advanceUntilIdle()

        fixture.viewModel.toggleFollow()
        advanceUntilIdle()

        val saved = fixture.addedFavorites.single()
        assertNull(saved.coverUrl)
        assertNull(saved.subtitle)
    }

    @Test
    fun `unsaved follow change reports a failure`() = runTest {
        val fixture = fixture()
        var initializationChecks = 0
        doAnswer {
            initializationChecks += 1
            initializationChecks == 1
        }.`when`(fixture.repository).awaitInitialized()
        fixture.viewModel.start(NeteaseArtistSummary(8L, "Artist"))
        advanceUntilIdle()

        fixture.viewModel.toggleFollow()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals(1, fixture.addedFavorites.size)
        assertEquals("Follow failed: Artist follow change could not be saved", state.error)
        assertFalse(state.followUpdating)
    }

    @Test
    fun `follow request for a replaced artist is dropped before writing`() = runTest {
        val fixture = fixture()
        fixture.viewModel.start(NeteaseArtistSummary(8L, "Artist 8"))
        advanceUntilIdle()

        fixture.viewModel.toggleFollow()
        fixture.viewModel.start(NeteaseArtistSummary(9L, "Artist 9"))
        advanceUntilIdle()

        assertTrue(fixture.addedFavorites.isEmpty())
        assertEquals(9L, fixture.viewModel.uiState.value.header!!.id)
    }

    @Test
    fun `toggle without a header or while updating is ignored`() = runTest {
        val fixture = fixture()

        fixture.viewModel.toggleFollow()
        advanceUntilIdle()
        fixture.viewModel.start(NeteaseArtistSummary(8L, "Artist"))
        advanceUntilIdle()
        fixture.viewModel.toggleFollow()
        fixture.viewModel.toggleFollow()
        advanceUntilIdle()

        assertEquals(1, fixture.addedFavorites.size)
    }

    private suspend fun TestScope.fixture(): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val application = mock(Application::class.java)
        `when`(application.getString(eq(CoreCommonR.string.artist_load_failed), anyString()))
            .thenAnswer { "Load failed: ${it.getArgument<String>(1)}" }
        `when`(application.getString(eq(CoreCommonR.string.error_api_code), anyInt()))
            .thenAnswer { "API code ${it.getArgument<Int>(1)}" }
        `when`(application.getString(eq(CoreCommonR.string.artist_follow_failed), anyString()))
            .thenAnswer { "Follow failed: ${it.getArgument<String>(1)}" }
        val client = mock(NeteaseClient::class.java)
        val favorites = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
        val repository = mock(FavoritePlaylistRepository::class.java)
        val fixture = Fixture(client, repository, favorites)
        `when`(client.getArtistDetail(anyLong())).thenAnswer {
            fixture.detailFailure?.let { failure -> throw failure }
            fixture.detail ?: """{"code":200,"artist":{"id":${it.getArgument<Long>(0)},"name":"Artist"}}"""
        }
        `when`(client.getArtistSongs(anyLong(), anyString(), anyInt(), anyInt()))
            .thenAnswer { fixture.songs }
        `when`(client.getArtistAlbums(anyLong(), anyInt(), anyInt()))
            .thenAnswer { fixture.albums }
        `when`(repository.favorites).thenReturn(favorites)
        `when`(repository.isFavorite(anyLong(), anyString())).thenAnswer {
            val id = it.getArgument<Long>(0)
            favorites.value.any { favorite -> favorite.id == id }
        }
        doAnswer { true }.`when`(repository).awaitInitialized()
        doAnswer { invocation ->
            val saved = FavoritePlaylist(
                id = invocation.getArgument(0),
                name = invocation.getArgument(1),
                coverUrl = invocation.getArgument(2),
                trackCount = invocation.getArgument(3),
                source = invocation.getArgument(4),
                subtitle = invocation.getArgument(7),
                songs = emptyList()
            )
            fixture.addedFavorites += saved
            favorites.value = favorites.value + saved
            Unit
        }.`when`(repository).addFavorite(
            anyLong(),
            anyString(),
            nullable(String::class.java),
            anyInt(),
            eq(FAVORITE_SOURCE_NETEASE_ARTIST) ?: FAVORITE_SOURCE_NETEASE_ARTIST,
            nullable(String::class.java),
            nullable(String::class.java),
            nullable(String::class.java),
            anyList()
        )
        fixture.viewModel = NeteaseArtistDetailViewModel(application, client, repository, dispatcher)
            .also(viewModels::add)
        return fixture
    }

    private class Fixture(
        val client: NeteaseClient,
        val repository: FavoritePlaylistRepository,
        val favorites: MutableStateFlow<List<FavoritePlaylist>>
    ) {
        lateinit var viewModel: NeteaseArtistDetailViewModel
        var detail: String? = null
        var detailFailure: Exception? = null
        var songs: String = """{"code":200,"songs":[]}"""
        var albums: String = """{"code":200,"hotAlbums":[]}"""
        val addedFavorites = mutableListOf<FavoritePlaylist>()
    }

    private companion object {
        val RICH_DETAIL = """
            {"code":200,"data":{"artist":{
              "id":7,"name":"Rich",
              "cover":"http://p1.music.126.net/cover.jpg",
              "avatar":"  ",
              "img1v1Url":"http://p1.music.126.net/avatar.jpg",
              "alias":["Alias One"," ","Alias Two"],
              "briefDesc":"Brief","musicSize":12,"albumSize":3,"followed":true
            }}}
        """.trimIndent()
        val RICH_SONGS = """
            {"code":200,"more":true,"songs":[
              {"id":11,"name":"Song A","ar":[{"id":1,"name":"Singer A"},{"id":2,"name":"Singer B"}],
               "al":{"id":21,"name":"Album A","picUrl":"http://p1.music.126.net/a.jpg"},"dt":180000},
              {"id":0,"name":"Invalid id"},
              {"id":12,"name":"  "},
              "not an object",
              {"id":13,"name":"Song B","dt":1000}
            ]}
        """.trimIndent()
        val RICH_ALBUMS = """
            {"code":200,"more":false,"hotAlbums":[
              {"id":31,"name":"Album X","picUrl":"http://p1.music.126.net/x.jpg","size":10},
              {"id":0,"name":"Invalid id"},
              {"id":32,"name":" "},
              5,
              {"id":33,"name":"Album Y","size":2}
            ]}
        """.trimIndent()
    }
}
