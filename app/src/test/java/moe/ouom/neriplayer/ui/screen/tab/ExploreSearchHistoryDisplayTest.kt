package moe.ouom.neriplayer.ui.screen.tab

import org.junit.Assert.assertEquals
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
}
