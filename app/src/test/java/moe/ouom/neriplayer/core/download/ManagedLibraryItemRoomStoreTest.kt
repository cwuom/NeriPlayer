package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryItemRoomStoreTest {
    @Test
    fun `only finalized managed library states restore previews`() {
        listOf("CORE_COMMITTED", "ASSETS_ENRICHING", "DEGRADED_COMPLETE").forEach { state ->
            assertFalse(state, shouldRestoreManagedLibraryItem(state))
        }
        listOf("FINALIZED", "COMPLETE", "COMPLETED").forEach { state ->
            assertTrue(state, shouldRestoreManagedLibraryItem(state))
        }
    }
}
