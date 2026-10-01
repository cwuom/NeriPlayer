package moe.ouom.neriplayer.platform.comments

import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentSort
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CommentMemoryCacheTest {
    private val cache = CommentMemoryCache()
    @Test
    fun `login session participates in cache identity and invalidation covers every session`() {
        val page = CommentPage(emptyList(), 1, 20, 0L, false)
        cache.put("NETEASE", 1L, 1, page, sessionKey = "session-a")
        assertNotNull(cache.get("NETEASE", 1L, 1, sessionKey = "session-a"))
        assertNull(cache.get("NETEASE", 1L, 1, sessionKey = "session-b"))
        assertNull(cache.get("NETEASE", 1L, 1))
        cache.invalidate("NETEASE", 1L)
        assertNull(cache.get("NETEASE", 1L, 1, sessionKey = "session-a"))
    }

    @Test
    fun `sort page size and cursor are independent cache dimensions`() {
        val page = CommentPage(emptyList(), 2, 20, 0L, false)
        cache.put("NETEASE", 1L, 2, page, CommentSort.NEWEST, 20, "123")
        assertNotNull(cache.get("NETEASE", 1L, 2, CommentSort.NEWEST, 20, "123"))
        assertNull(cache.get("NETEASE", 1L, 2, CommentSort.HOT, 20, "123"))
        assertNull(cache.get("NETEASE", 1L, 2, CommentSort.NEWEST, 10, "123"))
        assertNull(cache.get("NETEASE", 1L, 2, CommentSort.NEWEST, 20, "456"))
        cache.invalidate("NETEASE", 1L)
        assertNull(cache.get("NETEASE", 1L, 2, CommentSort.NEWEST, 20, "123"))
    }

    @Before
    fun clearBefore() = cache.clear()

    @After
    fun clearAfter() = cache.clear()

    @Test
    fun `invalidation drops all pages of only the selected platform resource`() {
        val page = CommentPage(emptyList(), 1, 20, 0L, false)
        cache.put("NETEASE", 1L, 1, page)
        cache.put("NETEASE", 1L, 2, page.copy(page = 2))
        cache.put("NETEASE", 10L, 2, page.copy(page = 2))
        cache.put("BILIBILI", 1L, 2, page.copy(page = 2))

        cache.invalidate("NETEASE", 1L)

        assertNull(cache.get("NETEASE", 1L, 1))
        assertNull(cache.get("NETEASE", 1L, 2))
        assertNotNull(cache.get("NETEASE", 10L, 2))
        assertNotNull(cache.get("BILIBILI", 1L, 2))
    }
}
