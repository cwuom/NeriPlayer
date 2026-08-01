package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreSearchPaginationTest {

    @Test
    fun `merge keeps existing order and removes duplicate stable keys`() {
        val first = ExploreSearchResult.Notice(title = "first", message = "one")
        val second = ExploreSearchResult.Notice(title = "second", message = "two")

        assertEquals(
            listOf(first, second),
            mergeExploreSearchResults(
                existing = listOf(first),
                incoming = listOf(first, second)
            )
        )
    }

    @Test
    fun `known total count controls whether another page exists`() {
        assertTrue(
            hasMoreExploreSearchResults(
                totalCount = 61,
                loadedCount = 60,
                pageItemCount = 30,
                pageSize = 30
            )
        )
        assertFalse(
            hasMoreExploreSearchResults(
                totalCount = 60,
                loadedCount = 60,
                pageItemCount = 30,
                pageSize = 30
            )
        )
    }

    @Test
    fun `page size fallback handles APIs without a total count`() {
        assertTrue(
            hasMoreExploreSearchResults(
                totalCount = null,
                loadedCount = 30,
                pageItemCount = 30,
                pageSize = 30
            )
        )
        assertFalse(
            hasMoreExploreSearchResults(
                totalCount = null,
                loadedCount = 29,
                pageItemCount = 29,
                pageSize = 30
            )
        )
    }

    @Test
    fun `scroll threshold prefetches next page near list end`() {
        assertFalse(
            shouldLoadExploreSearchMore(
                resultCount = 30,
                lastVisibleItemIndex = 23,
                hasMore = true,
                searching = false,
                loadingMore = false
            )
        )
        assertTrue(
            shouldLoadExploreSearchMore(
                resultCount = 30,
                lastVisibleItemIndex = 24,
                hasMore = true,
                searching = false,
                loadingMore = false
            )
        )
    }

    @Test
    fun `load more failure waits for explicit retry`() {
        assertFalse(
            shouldLoadExploreSearchMore(
                resultCount = 30,
                lastVisibleItemIndex = 29,
                hasMore = true,
                searching = false,
                loadingMore = false,
                loadMoreFailed = true
            )
        )
    }

    @Test
    fun `youtube linked song has deterministic thumbnail fallback`() {
        assertEquals(
            "https://i.ytimg.com/vi/OvLE3VQ18UY/hqdefault.jpg",
            youtubeMusicThumbnailUrl("OvLE3VQ18UY")
        )
    }
}
