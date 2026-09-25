package moe.ouom.neriplayer.core.download.storage.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class SafParentDocumentCacheTest {
    @Test
    fun `successful parent probe is reused until validation interval`() {
        var nowMs = 100L
        var loads = 0
        val cache = SafParentDocumentCache<String>(
            validateIntervalMs = 50L,
            nowMs = { nowMs }
        )

        fun load(): String {
            loads += 1
            return "parent-$loads"
        }

        assertEquals("parent-1", cache.getOrLoad("root", ::load) { true })
        assertEquals("parent-1", cache.getOrLoad("root", ::load) { true })
        assertEquals(1, loads)

        nowMs += 51L
        assertEquals("parent-2", cache.getOrLoad("root", ::load) { true })
        assertEquals(2, loads)
    }

    @Test
    fun `uncacheable probe is never retained and invalidation forces reload`() {
        var loads = 0
        val cache = SafParentDocumentCache<String>(nowMs = { 100L })

        fun load(): String {
            loads += 1
            return "value-$loads"
        }

        assertEquals("value-1", cache.getOrLoad("root", ::load) { false })
        assertEquals("value-2", cache.getOrLoad("root", ::load) { false })
        assertEquals(2, loads)

        assertEquals("value-3", cache.getOrLoad("root", ::load) { true })
        cache.invalidate("root")
        assertEquals("value-4", cache.getOrLoad("root", ::load) { true })
        assertEquals(4, loads)
    }
}
