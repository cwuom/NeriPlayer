package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreViewModelYouTubeGateTest {

    private val result = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        coverUrl = "",
        durationMs = 1_000L
    )

    @Test
    fun `valid Netease auth keeps search available`() {
        assertTrue(isNeteaseExploreSearchAvailable(SavedCookieAuthState.Valid))
    }

    @Test
    fun `missing Netease auth blocks search`() {
        assertFalse(isNeteaseExploreSearchAvailable(SavedCookieAuthState.Missing))
    }

    @Test
    fun `missing Netease auth clears stale paginated results`() {
        val state = ExploreUiState(
            selectedSearchSource = SearchSource.NETEASE,
            searching = false,
            searchResults = listOf(result),
            searchItems = listOf(ExploreSearchResult.Song(result)),
            searchHasMore = true,
            searchLoadingMore = true,
            searchLoadMoreError = "temporary error",
            searchPage = 3,
            searchKeyword = "song",
            searchDisplayQuery = "song"
        )

        val blocked = state.withNeteaseAuthRequired("login required")

        assertFalse(blocked.searching)
        assertEquals("login required", blocked.searchError)
        assertTrue(blocked.searchResults.isEmpty())
        assertTrue(blocked.searchItems.isEmpty())
        assertFalse(blocked.searchHasMore)
        assertFalse(blocked.searchLoadingMore)
        assertNull(blocked.searchLoadMoreError)
        assertEquals(0, blocked.searchPage)
        assertEquals("song", blocked.searchKeyword)
        assertEquals("song", blocked.searchDisplayQuery)
    }

    @Test
    fun `disabling YouTube preserves another source search state`() {
        val state = ExploreUiState(
            selectedSearchSource = SearchSource.BILIBILI,
            searching = true,
            searchResults = listOf(result),
            searchError = "existing error",
            ytMusicPlaylistsLoading = true,
            ytMusicPlaylistsError = "YouTube error"
        )

        val disabled = state.withYouTubeDisabled()

        assertEquals(SearchSource.BILIBILI, disabled.selectedSearchSource)
        assertTrue(disabled.searching)
        assertEquals(listOf(result), disabled.searchResults)
        assertEquals("existing error", disabled.searchError)
        assertFalse(disabled.ytMusicPlaylistsLoading)
        assertNull(disabled.ytMusicPlaylistsError)
    }

    @Test
    fun `disabling selected YouTube source resets search state`() {
        val state = ExploreUiState(
            selectedSearchSource = SearchSource.YOUTUBE_MUSIC,
            searching = true,
            searchResults = listOf(result),
            searchError = "YouTube error"
        )

        val disabled = state.withYouTubeDisabled()

        assertEquals(SearchSource.NETEASE, disabled.selectedSearchSource)
        assertFalse(disabled.searching)
        assertTrue(disabled.searchResults.isEmpty())
        assertNull(disabled.searchError)
    }
}
