package moe.ouom.neriplayer.ui.viewmodel.tab

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
