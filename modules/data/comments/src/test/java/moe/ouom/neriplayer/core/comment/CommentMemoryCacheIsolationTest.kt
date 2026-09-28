package moe.ouom.neriplayer.core.comment

import moe.ouom.neriplayer.core.comment.model.CommentPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentMemoryCacheIsolationTest {
    private val page = CommentPage(emptyList(), 1, 20, 0L, false)

    @Test
    fun `separate cache owners cannot read or clear each others pages`() {
        val first = CommentMemoryCache()
        val second = CommentMemoryCache()
        first.put("NETEASE", 1L, 1, page)
        assertNull(second.get("NETEASE", 1L, 1))
        second.clear()
        assertEquals(page, first.get("NETEASE", 1L, 1))
    }

    @Test
    fun `expiry preserves the existing inclusive five minute boundary`() {
        var now = 1000L
        val cache = CommentMemoryCache { now }
        cache.put("NETEASE", 1L, 1, page)
        now += 5 * 60 * 1000L
        assertEquals(page, cache.get("NETEASE", 1L, 1))
        now += 1L
        assertNull(cache.get("NETEASE", 1L, 1))
    }

    @Test
    fun `capacity evicts least recently read page within its owner`() {
        val cache = CommentMemoryCache()
        for (id in 1L..48L) cache.put("NETEASE", id, 1, page)
        assertEquals(page, cache.get("NETEASE", 1L, 1))
        cache.put("NETEASE", 49L, 1, page)
        assertNull(cache.get("NETEASE", 2L, 1))
        assertEquals(page, cache.get("NETEASE", 1L, 1))
        assertEquals(page, cache.get("NETEASE", 49L, 1))
    }
}
