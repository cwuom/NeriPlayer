package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreScreenYouTubeGateTest {

    @Test
    fun `search sources exclude YouTube when disabled`() {
        val sources = exploreSearchSourceDisplayOrder(
            isInternational = true,
            youtubeEnabled = false
        )

        assertFalse(sources.contains(SearchSource.YOUTUBE_MUSIC))
        assertEquals(
            listOf(
                SearchSource.NETEASE,
                SearchSource.BILIBILI,
                SearchSource.LINK_RECOGNITION
            ),
            sources
        )
    }

    @Test
    fun `international search keeps YouTube first when enabled`() {
        val sources = exploreSearchSourceDisplayOrder(
            isInternational = true,
            youtubeEnabled = true
        )

        assertEquals(SearchSource.YOUTUBE_MUSIC, sources.first())
        assertEquals(SearchSource.LINK_RECOGNITION, sources.last())
    }

    @Test
    fun `entering or leaving link recognition clears incompatible query`() {
        assertTrue(
            shouldClearExploreSearchQuery(
                previous = SearchSource.NETEASE,
                current = SearchSource.LINK_RECOGNITION
            )
        )
        assertTrue(
            shouldClearExploreSearchQuery(
                previous = SearchSource.LINK_RECOGNITION,
                current = SearchSource.BILIBILI
            )
        )
        assertFalse(
            shouldClearExploreSearchQuery(
                previous = SearchSource.NETEASE,
                current = SearchSource.BILIBILI
            )
        )
    }

    @Test
    fun `same search context does not reset result scroll`() {
        val contextKey = exploreSearchScrollContextKey(
            keyword = "  hanezeve  ",
            source = SearchSource.NETEASE,
            neteaseSearchType = NeteaseExploreSearchType.PLAYLIST
        )

        assertEquals(
            "NETEASE|PLAYLIST|hanezeve",
            contextKey
        )
        assertFalse(shouldResetExploreSearchScroll(contextKey, contextKey))
    }

    @Test
    fun `changed search context resets result scroll`() {
        val previousContextKey = exploreSearchScrollContextKey(
            keyword = "hanezeve",
            source = SearchSource.NETEASE,
            neteaseSearchType = NeteaseExploreSearchType.PLAYLIST
        )
        val changedTypeKey = exploreSearchScrollContextKey(
            keyword = "hanezeve",
            source = SearchSource.NETEASE,
            neteaseSearchType = NeteaseExploreSearchType.ARTIST
        )
        val changedSourceKey = exploreSearchScrollContextKey(
            keyword = "hanezeve",
            source = SearchSource.BILIBILI,
            neteaseSearchType = NeteaseExploreSearchType.PLAYLIST
        )

        assertTrue(shouldResetExploreSearchScroll(previousContextKey, changedTypeKey))
        assertTrue(shouldResetExploreSearchScroll(previousContextKey, changedSourceKey))
        assertFalse(shouldResetExploreSearchScroll(previousContextKey, null))
    }
}
