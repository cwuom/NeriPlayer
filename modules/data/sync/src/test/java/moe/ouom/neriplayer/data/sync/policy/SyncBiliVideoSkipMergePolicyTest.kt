package moe.ouom.neriplayer.data.sync.policy

import moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipInterval
import moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBiliVideoSkipMergePolicyTest {
    private val active = SyncBiliVideoSkipRule("BV1xx411c7mD", 1, listOf(SyncBiliVideoSkipInterval(100, 200)), 10)

    @Test
    fun `newer edits and deletions win while timestamp ties retain active intervals`() {
        val deleted = active.copy(isDeleted = true, intervals = emptyList())
        for ((left, right, expected) in listOf(
            Triple(active.copy(modifiedAt = 20), deleted, active.copy(modifiedAt = 20)),
            Triple(active, deleted.copy(modifiedAt = 20), deleted.copy(modifiedAt = 20)),
            Triple(deleted, deleted, deleted), Triple(deleted, active, active), Triple(active, deleted, active)
        )) assertEquals(listOf(expected), SyncBiliVideoSkipMergePolicy.merge(listOf(left), listOf(right)))
        val second = active.copy(intervals = listOf(SyncBiliVideoSkipInterval(300, 400)))
        val merged = SyncBiliVideoSkipMergePolicy.merge(listOf(active), listOf(second))
        assertEquals(listOf(SyncBiliVideoSkipInterval(100, 200), SyncBiliVideoSkipInterval(300, 400)), merged.single().intervals)
        assertEquals(merged, SyncBiliVideoSkipMergePolicy.merge(listOf(second), listOf(active)))
    }

    @Test
    fun `normalization discards invalid active rules but keeps empty deletion records`() {
        assertTrue(SyncBiliVideoSkipMergePolicy.sanitize(listOf(active.copy(bvid = ""), active.copy(intervals = emptyList()))).isEmpty())
        assertEquals(listOf(active.copy(isDeleted = true, intervals = emptyList(), modifiedAt = 0)),
            SyncBiliVideoSkipMergePolicy.sanitize(listOf(active.copy(isDeleted = true, modifiedAt = -1))))
        assertTrue(SyncBiliVideoSkipMergePolicy.same(listOf(active), listOf(active.copy(modifiedAt = 20))))
        for (different in listOf(active.copy(cid = 2), active.copy(bvid = "BV17x411w7KC"), active.copy(isDeleted = true),
            active.copy(intervals = listOf(SyncBiliVideoSkipInterval(300, 400))))) {
            assertFalse(SyncBiliVideoSkipMergePolicy.same(listOf(active), listOf(different)))
        }
        assertFalse(SyncBiliVideoSkipMergePolicy.same(emptyList(), listOf(active)))
    }
}
