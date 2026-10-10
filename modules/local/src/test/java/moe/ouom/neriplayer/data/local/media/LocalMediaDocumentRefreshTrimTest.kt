package moe.ouom.neriplayer.data.local.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocalMediaDocumentRefreshTrimTest {
    private val counters get() = LocalMediaSupport.consecutiveEmptyDocumentRefreshes

    @Before
    @After
    fun clearRefreshCounters() {
        counters.clear()
    }

    @Test
    fun `counters within the cache limit are left alone`() {
        fill(LocalMediaSupport.CONSECUTIVE_EMPTY_REFRESH_CACHE_LIMIT)
        val before = HashMap(counters)

        LocalMediaSupport.trimConsecutiveEmptyDocumentRefreshes(keepKey = "tree|0")

        assertEquals(before, HashMap(counters))
    }

    @Test
    fun `excess counters are trimmed back to the limit without dropping the kept key`() {
        val limit = LocalMediaSupport.CONSECUTIVE_EMPTY_REFRESH_CACHE_LIMIT
        fill(limit + 3)
        val kept = counters.keys.first()
        val keptCount = counters.getValue(kept)

        LocalMediaSupport.trimConsecutiveEmptyDocumentRefreshes(keepKey = kept)

        assertEquals(limit, counters.size)
        assertEquals(keptCount, counters[kept])
    }

    private fun fill(count: Int) {
        repeat(count) { index -> counters["tree|$index"] = index % 5 + 1 }
    }
}
