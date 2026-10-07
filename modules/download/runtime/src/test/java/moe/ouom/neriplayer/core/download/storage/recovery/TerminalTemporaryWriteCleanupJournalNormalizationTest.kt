package moe.ouom.neriplayer.core.download.storage.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTemporaryWriteCleanupJournalNormalizationTest {
    private val store = InMemoryJournalStore()
    private val journal = TerminalTemporaryWriteCleanupJournal(store)

    @Test
    fun `blank roots and unsafe target names are never journaled`() {
        assertFalse(journal.enqueue(fileRoot("   "), listOf("song.mp3")))
        assertTrue(
            journal.enqueue(fileRoot("/downloads"), listOf(".", "..", "Music/song.mp3", "Music\\song.mp3", "  "))
        )
        assertNull(store.payload)

        assertTrue(journal.enqueue(fileRoot(" /downloads "), listOf(" song.mp3 ")))

        val entry = entries().single()
        assertEquals(fileRoot("/downloads"), entry.root)
        assertEquals(listOf("song.mp3"), entry.targetNames)
    }

    @Test
    fun `temporary write owners are trimmed and blank owners are dropped`() {
        assertTrue(
            journal.enqueueTargets(
                treeRoot("content://tree/downloads"),
                listOf(
                    TerminalTemporaryWriteCleanupTarget("a.mp3", temporaryWriteOwnerName = " owner-a "),
                    TerminalTemporaryWriteCleanupTarget("b.mp3", temporaryWriteOwnerName = "   "),
                    TerminalTemporaryWriteCleanupTarget("c.mp3")
                )
            )
        )

        assertEquals(
            setOf(
                TerminalTemporaryWriteCleanupTarget("a.mp3", "owner-a"),
                TerminalTemporaryWriteCleanupTarget("b.mp3"),
                TerminalTemporaryWriteCleanupTarget("c.mp3")
            ),
            entries().single().targets.toSet()
        )
    }

    @Test
    fun `current entry lookup requires the same normalized root and target set`() {
        val root = treeRoot("content://tree/downloads")
        assertTrue(journal.enqueue(root, listOf("a.mp3", "b.mp3")))
        val current = entries().single()

        assertEquals(
            current,
            journal.currentEntryIfTargetsMatch(
                current.copy(
                    root = treeRoot(" content://tree/downloads "),
                    targets = listOf(TerminalTemporaryWriteCleanupTarget("b.mp3"), TerminalTemporaryWriteCleanupTarget(" a.mp3 "))
                )
            )
        )
        assertNull(journal.currentEntryIfTargetsMatch(current.copy(targets = listOf(TerminalTemporaryWriteCleanupTarget("a.mp3")))))
        assertNull(journal.currentEntryIfTargetsMatch(current.copy(root = treeRoot("content://tree/other"))))
        assertNull(journal.currentEntryIfTargetsMatch(current.copy(root = treeRoot("  "))))
        assertNull(journal.currentEntryIfTargetsMatch(current.copy(targets = listOf(TerminalTemporaryWriteCleanupTarget("..")))))

        store.readsEnabled = false
        assertNull(journal.currentEntryIfTargetsMatch(current))
    }

    @Test
    fun `completing one preparation keeps preparations for other roots and pending names`() {
        val otherRoot = fileRoot("/a-downloads")
        val root = fileRoot("/b-downloads")
        val other = prepare(otherRoot, "x.pending", "x.mp3")
        val sibling = prepare(root, "a.pending", "a.mp3")
        val completed = prepare(root, "b.pending", "b.mp3")

        assertTrue(journal.completeFinalization(completed))

        assertEquals(listOf(other, sibling), preparations())
        assertEquals(listOf(root to listOf("b.mp3")), entries().map { it.root to it.targetNames })
    }

    @Test
    fun `invalid or unreadable preparations are not completed`() {
        val preparation = prepare(fileRoot("/downloads"), "song.pending", "song.mp3")

        assertFalse(journal.completeFinalization(preparation.copy(root = fileRoot("  "))))
        assertFalse(journal.completeFinalization(preparation.copy(pendingAudioName = "../song.pending")))
        assertFalse(journal.completeFinalization(preparation.copy(generationId = "  ")))
        assertFalse(
            journal.completeFinalization(preparation.copy(targets = listOf(TerminalTemporaryWriteCleanupTarget(".."))))
        )
        store.readsEnabled = false
        assertFalse(journal.completeFinalization(preparation))

        store.readsEnabled = true
        assertEquals(listOf(preparation), preparations())
        assertTrue(entries().isEmpty())
    }

    private fun prepare(
        root: TerminalTemporaryWriteCleanupRoot,
        pendingAudioName: String,
        finalAudioName: String
    ): TerminalTemporaryWriteCleanupFinalizationPreparation {
        return requireNotNull(
            journal.prepareFinalization(
                root = root,
                pendingAudioName = pendingAudioName,
                finalAudioName = finalAudioName,
                expectedOperationId = null,
                targetNames = listOf(finalAudioName)
            )
        )
    }

    private fun entries(): List<TerminalTemporaryWriteCleanupJournalEntry> {
        return (journal.snapshot() as TerminalTemporaryWriteCleanupJournalSnapshot.Available).entries
    }

    private fun preparations(): List<TerminalTemporaryWriteCleanupFinalizationPreparation> {
        return (journal.preparationSnapshot() as TerminalTemporaryWriteCleanupPreparationSnapshot.Available).entries
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
        var readsEnabled = true

        override fun read(): String? {
            check(readsEnabled)
            return payload
        }

        override fun write(payload: String?): Boolean {
            this.payload = payload
            return true
        }
    }
}
