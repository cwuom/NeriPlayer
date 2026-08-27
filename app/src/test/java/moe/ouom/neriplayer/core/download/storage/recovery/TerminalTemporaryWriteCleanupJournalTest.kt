package moe.ouom.neriplayer.core.download.storage.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTemporaryWriteCleanupJournalTest {
    @Test
    fun `journal preserves terminal targets for each captured root`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val oldRoot = fileRoot("/storage/emulated/0/OldDownloads")
        val newRoot = treeRoot("content://documents/tree/new-downloads")

        assertTrue(journal.enqueue(oldRoot, listOf("old.mp3", "old.mp3")))
        assertTrue(journal.enqueue(newRoot, listOf("new.mp3")))

        assertEquals(
            listOf(
                oldRoot to listOf("old.mp3"),
                newRoot to listOf("new.mp3")
            ),
            journal.availableEntries().map { entry -> entry.root to entry.targetNames }
        )
    }

    @Test
    fun `consuming an obsolete snapshot retains targets enqueued while cleanup was running`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = fileRoot("/storage/emulated/0/Downloads")

        assertTrue(journal.enqueue(root, listOf("first.mp3")))
        val firstSnapshot = journal.availableEntries().single()
        assertTrue(journal.enqueue(root, listOf("second.mp3")))

        assertFalse(journal.consume(firstSnapshot))
        assertEquals(
            listOf(
                "first.mp3",
                "second.mp3"
            ),
            journal.availableEntries().single().targetNames
        )
    }

    @Test
    fun `consuming an obsolete snapshot retains a re-enqueued identical target`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = fileRoot("/storage/emulated/0/Downloads")

        assertTrue(journal.enqueue(root, listOf("same.mp3")))
        val firstSnapshot = journal.availableEntries().single()
        assertTrue(journal.enqueue(root, listOf("same.mp3")))

        assertFalse(journal.consume(firstSnapshot))
        val currentSnapshot = journal.availableEntries().single()
        assertEquals(listOf("same.mp3"), currentSnapshot.targetNames)
        assertTrue(journal.consume(currentSnapshot))
        assertTrue(journal.availableEntries().isEmpty())
    }

    @Test
    fun `prepared finalization is not terminal until promotion completion is persisted`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = fileRoot("/storage/emulated/0/Downloads")

        val preparation = requireNotNull(
            journal.prepareFinalization(
                root = root,
                pendingAudioName = "song.mp3.npdl_pending.operation.pending",
                finalAudioName = "song.mp3",
                expectedOperationId = "operation",
                targetNames = listOf("song.mp3", "song.mp3.npmeta.pending.json")
            )
        )

        assertTrue(journal.availableEntries().isEmpty())
        assertEquals(
            listOf(preparation),
            journal.availablePreparations()
        )

        assertTrue(journal.completeFinalization(preparation))
        assertTrue(journal.availablePreparations().isEmpty())
        assertEquals(
            listOf("song.mp3", "song.mp3.npmeta.pending.json"),
            journal.availableEntries().single().targetNames
        )
    }

    @Test
    fun `failed finalization completion retains the durable preparation`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = fileRoot("/storage/emulated/0/Downloads")
        val preparation = requireNotNull(
            journal.prepareFinalization(
                root = root,
                pendingAudioName = "song.mp3.npdl_pending.operation.pending",
                finalAudioName = "song.mp3",
                expectedOperationId = "operation",
                targetNames = listOf("song.mp3")
            )
        )
        store.writesEnabled = false

        assertFalse(journal.completeFinalization(preparation))
        assertTrue(journal.availableEntries().isEmpty())
        assertEquals(listOf(preparation), journal.availablePreparations())
    }

    @Test
    fun `prepared finalization persists a token when legacy metadata has no operation id`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = fileRoot("/storage/emulated/0/Downloads")

        requireNotNull(
            journal.prepareFinalization(
                root = root,
                pendingAudioName = "song.mp3.npdl_pending.legacy.pending",
                finalAudioName = "song.mp3",
                expectedOperationId = null,
                targetNames = listOf("song.mp3"),
                expectedFinalizationToken = "finalization-token"
            )
        )

        val restored = TerminalTemporaryWriteCleanupJournal(store)
            .availablePreparations()
            .single()
        assertEquals(null, restored.expectedOperationId)
        assertEquals("finalization-token", restored.expectedFinalizationToken)
    }

    @Test
    fun `failed write and unreadable payload are retained instead of replaced`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = fileRoot("/storage/emulated/0/Downloads")

        assertTrue(journal.enqueue(root, listOf("stable.mp3")))
        store.writesEnabled = false
        assertFalse(journal.enqueue(root, listOf("not-persisted.mp3")))
        store.writesEnabled = true
        assertEquals(listOf("stable.mp3"), journal.availableEntries().single().targetNames)

        store.payload = "not-json"
        assertTrue(journal.snapshot() is TerminalTemporaryWriteCleanupJournalSnapshot.Unavailable)
        assertFalse(journal.enqueue(root, listOf("must-not-overwrite.mp3")))
        assertEquals("not-json", store.payload)
    }

    @Test
    fun `failed read never treats the journal as empty or overwrites it`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = fileRoot("/storage/emulated/0/Downloads")

        assertTrue(journal.enqueue(root, listOf("stable.mp3")))
        val preservedPayload = requireNotNull(store.payload)
        store.readsEnabled = false

        assertFalse(journal.enqueue(root, listOf("must-not-overwrite.mp3")))
        assertTrue(journal.snapshot() is TerminalTemporaryWriteCleanupJournalSnapshot.Unavailable)
        assertEquals(preservedPayload, store.payload)
    }

    @Test
    fun `journal keeps a thousand safe targets in one root record`() {
        val store = InMemoryJournalStore()
        val journal = TerminalTemporaryWriteCleanupJournal(store)
        val root = treeRoot("content://documents/tree/bulk-downloads")
        val targets = (1..1_000).map { index -> "song-$index.mp3" }

        assertTrue(journal.enqueue(root, targets + listOf("../legacy.pending", "")))

        val entry = journal.availableEntries().single()
        assertEquals(root, entry.root)
        assertEquals(targets.size, entry.targetNames.size)
        assertTrue(entry.targetNames.containsAll(targets))
    }

    private fun TerminalTemporaryWriteCleanupJournal.availableEntries(): List<
        TerminalTemporaryWriteCleanupJournalEntry
    > {
        val snapshot = snapshot()
        assertTrue(snapshot is TerminalTemporaryWriteCleanupJournalSnapshot.Available)
        return (snapshot as TerminalTemporaryWriteCleanupJournalSnapshot.Available).entries
    }

    private fun TerminalTemporaryWriteCleanupJournal.availablePreparations(): List<
        TerminalTemporaryWriteCleanupFinalizationPreparation
    > {
        val snapshot = preparationSnapshot()
        assertTrue(snapshot is TerminalTemporaryWriteCleanupPreparationSnapshot.Available)
        return (snapshot as TerminalTemporaryWriteCleanupPreparationSnapshot.Available).entries
    }

    private fun fileRoot(identity: String) = TerminalTemporaryWriteCleanupRoot(
        type = TerminalTemporaryWriteCleanupRootType.FILE,
        identity = identity
    )

    private fun treeRoot(identity: String) = TerminalTemporaryWriteCleanupRoot(
        type = TerminalTemporaryWriteCleanupRootType.TREE,
        identity = identity
    )

    private class InMemoryJournalStore : TerminalTemporaryWriteCleanupJournalStore {
        var payload: String? = null
        var writesEnabled = true
        var readsEnabled = true

        override fun read(): String? {
            check(readsEnabled)
            return payload
        }

        override fun write(payload: String?): Boolean {
            if (!writesEnabled) {
                return false
            }
            this.payload = payload
            return true
        }
    }
}
