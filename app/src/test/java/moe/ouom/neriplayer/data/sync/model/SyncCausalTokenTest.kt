package moe.ouom.neriplayer.data.sync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncCausalTokenTest {
    @Test
    fun `normalization removes duplicates and sorts deterministically`() {
        val tokens = listOf(
            SyncCausalToken("device-b", 2L),
            SyncCausalToken("device-a", 2L),
            SyncCausalToken("device-a", 1L),
            SyncCausalToken("device-b", 2L)
        )

        assertEquals(
            listOf(
                SyncCausalToken("device-a", 1L),
                SyncCausalToken("device-a", 2L),
                SyncCausalToken("device-b", 2L)
            ),
            tokens.normalizedSyncCausalTokens()
        )
    }

    @Test
    fun `token rejects invalid identity and counter`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncCausalToken(" ", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncCausalToken("device", 0L)
        }
    }
}
