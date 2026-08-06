package moe.ouom.neriplayer.data.sync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBiliVideoSkipModelsTest {
    @Test
    fun `concurrent edits merge intervals but a newer tombstone wins`() {
        val local = rule(startMs = 10_000L, endMs = 20_000L, modifiedAt = 100L)
        val remote = rule(startMs = 30_000L, endMs = 40_000L, modifiedAt = 100L)

        val concurrentMerged = SyncBiliVideoSkipMergePolicy.merge(
            local = listOf(local),
            remote = listOf(remote)
        ).single()

        assertFalse(concurrentMerged.isDeleted)
        assertEquals(
            listOf(
                SyncBiliVideoSkipInterval(10_000L, 20_000L),
                SyncBiliVideoSkipInterval(30_000L, 40_000L)
            ),
            concurrentMerged.intervals
        )

        val deleted = SyncBiliVideoSkipMergePolicy.merge(
            local = listOf(concurrentMerged),
            remote = listOf(
                rule(
                    startMs = 0L,
                    endMs = 0L,
                    modifiedAt = 101L,
                    isDeleted = true
                )
            )
        ).single()

        assertTrue(deleted.isDeleted)
        assertEquals(emptyList<SyncBiliVideoSkipInterval>(), deleted.intervals)
    }

    @Test
    fun `change detection ignores timestamp only updates`() {
        val earlier = rule(startMs = 10_000L, endMs = 20_000L, modifiedAt = 100L)
        val later = earlier.copy(modifiedAt = 200L)

        assertTrue(SyncBiliVideoSkipMergePolicy.same(listOf(earlier), listOf(later)))
    }

    private fun rule(
        startMs: Long,
        endMs: Long,
        modifiedAt: Long,
        isDeleted: Boolean = false
    ): SyncBiliVideoSkipRule {
        return SyncBiliVideoSkipRule(
            bvid = "BV1test",
            cid = 42L,
            intervals = if (isDeleted) {
                emptyList()
            } else {
                listOf(SyncBiliVideoSkipInterval(startMs, endMs))
            },
            modifiedAt = modifiedAt,
            isDeleted = isDeleted
        )
    }
}
