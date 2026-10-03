package moe.ouom.neriplayer.data.sync.store.state

import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.*
import org.junit.Test

class SyncLegacyLyricArchiveReceiptStoreTest {
    private val namespace = "a".repeat(64)
    private val source = "1".repeat(64)

    @Test fun `archive recovery receipts are durable and isolated by target and source`() {
        val prefs = MemorySyncPreferences()
        val store = SyncLegacyLyricArchiveReceiptStore(prefs.preferences, SyncDeletionStateStorage(prefs.preferences))
        assertFalse(store.isCompleted(namespace, source))
        store.markCompleted(namespace, source)
        val restarted = prefs.restart()
        val reopened = SyncLegacyLyricArchiveReceiptStore(restarted.preferences, SyncDeletionStateStorage(restarted.preferences))
        assertTrue(reopened.isCompleted(namespace, source))
        assertFalse(reopened.isCompleted("b".repeat(64), source))
        assertFalse(reopened.isCompleted(namespace, "2".repeat(64)))
    }

    @Test fun `failed receipt write and failed confirmation cannot trust optimistic preference memory`() {
        val prefs = MemorySyncPreferences()
        val store = SyncLegacyLyricArchiveReceiptStore(prefs.preferences, SyncDeletionStateStorage(prefs.preferences))
        prefs.failNextCommit = true
        assertTrue(runCatching { store.markCompleted(namespace, source) }.isFailure)
        prefs.failNextCommit = true
        assertTrue(runCatching { store.isCompleted(namespace, source) }.isFailure)
        val restarted = prefs.restart()
        assertFalse(SyncLegacyLyricArchiveReceiptStore(restarted.preferences, SyncDeletionStateStorage(restarted.preferences)).isCompleted(namespace, source))
        store.markCompleted(namespace, source)
        assertTrue(store.isCompleted(namespace, source))
    }

    @Test fun `invalid receipt identifiers cannot construct shared preference keys`() {
        val prefs = MemorySyncPreferences()
        val store = SyncLegacyLyricArchiveReceiptStore(prefs.preferences, SyncDeletionStateStorage(prefs.preferences))
        assertTrue(runCatching { store.markCompleted("../other", source) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { store.isCompleted(namespace, "bad") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(prefs.values.isEmpty())
    }
}
