package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreSearchHistoryDisplayTest {
    @Test
    fun `search history display keeps recent entries in stable order`() {
        val history = listOf("你好", "哈哈", "晴天")

        assertEquals(history, filteredExploreSearchHistory(history))
    }

    @Test
    fun `search history display caps visible entries at fifteen`() {
        val history = (1..20).map { "history$it" }

        assertEquals((1..15).map { "history$it" }, filteredExploreSearchHistory(history))
    }

    @Test
    fun `search history hides after content leaves the top`() {
        val history = listOf("你好")

        assertEquals(true, shouldShowExploreSearchHistory(history, contentScrolled = false))
        assertEquals(false, shouldShowExploreSearchHistory(history, contentScrolled = true))
        assertEquals(false, shouldShowExploreSearchHistory(emptyList(), contentScrolled = false))
    }

    @Test
    fun `netease search type bar hides after content leaves the top`() {
        assertTrue(
            shouldShowExploreNeteaseSearchTypeBar(
                selectedSearchSource = SearchSource.NETEASE,
                contentScrolled = false
            )
        )
        assertFalse(
            shouldShowExploreNeteaseSearchTypeBar(
                selectedSearchSource = SearchSource.NETEASE,
                contentScrolled = true
            )
        )
    }

    @Test
    fun `netease search type bar stays hidden for other sources`() {
        assertFalse(
            shouldShowExploreNeteaseSearchTypeBar(
                selectedSearchSource = SearchSource.BILIBILI,
                contentScrolled = false
            )
        )
        assertFalse(
            shouldShowExploreNeteaseSearchTypeBar(
                selectedSearchSource = SearchSource.BILIBILI,
                contentScrolled = true
            )
        )
    }

    @Test
    fun `youtube search type bar follows the selected source and scroll state`() {
        assertTrue(
            shouldShowExploreYouTubeSearchTypeBar(
                selectedSearchSource = SearchSource.YOUTUBE_MUSIC,
                contentScrolled = false
            )
        )
        assertFalse(
            shouldShowExploreYouTubeSearchTypeBar(
                selectedSearchSource = SearchSource.YOUTUBE_MUSIC,
                contentScrolled = true
            )
        )
        assertFalse(
            shouldShowExploreYouTubeSearchTypeBar(
                selectedSearchSource = SearchSource.NETEASE,
                contentScrolled = false
            )
        )
    }
}
