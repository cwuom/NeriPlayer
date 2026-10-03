package moe.ouom.neriplayer.data.sync.store.state

import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncLegacyLyricOptimizationStoreTest {
    @Test fun `default preserves lyrics and explicit choices survive restart with mutation barriers`() {
        val prefs = MemorySyncPreferences()
        val store = store(prefs)
        assertFalse(store.isEnabled())
        store.setEnabled(false)
        assertEquals(0L, SyncMutationVersionStore(prefs.preferences).getSyncMutationVersion())
        store.setEnabled(true)
        assertTrue(store(prefs.restart()).isEnabled())
        assertEquals(1L, SyncMutationVersionStore(prefs.preferences).getSyncMutationVersion())
        store.setEnabled(true)
        assertEquals(1L, SyncMutationVersionStore(prefs.preferences).getSyncMutationVersion())
        store.setEnabled(false)
        assertFalse(store(prefs.restart()).isEnabled())
        assertEquals(2L, SyncMutationVersionStore(prefs.preferences).getSyncMutationVersion())
    }

    @Test fun `failed confirmation cannot authorize discarding old lyrics`() {
        val prefs = MemorySyncPreferences()
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store(prefs).isEnabled() }
        assertFalse(store(prefs.restart()).isEnabled())
    }

    @Test fun `failed save rolls optimistic memory back before another storage instance reads it`() {
        val prefs = MemorySyncPreferences()
        prefs.failCommitNumber = 2
        val store = store(prefs)
        assertThrows(IllegalStateException::class.java) { store.setEnabled(true) }
        assertFalse(store(prefs).isEnabled())
        assertFalse(store(prefs.restart()).isEnabled())
    }

    private fun store(prefs: MemorySyncPreferences) =
        SyncLegacyLyricOptimizationStore(prefs.preferences, SyncDeletionStateStorage(prefs.preferences))
}
