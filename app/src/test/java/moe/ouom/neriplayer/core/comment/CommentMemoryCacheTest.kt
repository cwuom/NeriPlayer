package moe.ouom.neriplayer.core.comment

import moe.ouom.neriplayer.core.comment.model.CommentPage
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CommentMemoryCacheTest {
    @Before
    fun clearBefore() = CommentMemoryCache.clear()

    @After
    fun clearAfter() = CommentMemoryCache.clear()

    @Test
    fun `invalidation drops all pages of only the selected platform resource`() {
        val page = CommentPage(emptyList(), 1, 20, 0L, false)
        CommentMemoryCache.put("NETEASE", 1L, 1, page)
        CommentMemoryCache.put("NETEASE", 1L, 2, page.copy(page = 2))
        CommentMemoryCache.put("NETEASE", 10L, 2, page.copy(page = 2))
        CommentMemoryCache.put("BILIBILI", 1L, 2, page.copy(page = 2))

        CommentMemoryCache.invalidate("NETEASE", 1L)

        assertNull(CommentMemoryCache.get("NETEASE", 1L, 1))
        assertNull(CommentMemoryCache.get("NETEASE", 1L, 2))
        assertNotNull(CommentMemoryCache.get("NETEASE", 10L, 2))
        assertNotNull(CommentMemoryCache.get("BILIBILI", 1L, 2))
    }
}
