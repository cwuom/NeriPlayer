package moe.ouom.neriplayer.data.sync.github

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTimestampMergePolicyTest {
    @Test
    fun `missing legacy timestamp does not replace valid creation time`() {
        assertEquals(123L, mergePositiveTimestamp(123L, 0L))
        assertEquals(123L, mergePositiveTimestamp(0L, 123L))
    }

    @Test
    fun `earlier positive creation time wins`() {
        assertEquals(100L, mergePositiveTimestamp(100L, 200L))
    }
}
