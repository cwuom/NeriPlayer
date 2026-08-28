package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationRecoveryTest {
    @Test
    fun `active journal defers when source root is unavailable`() {
        assertTrue(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.PLANNED,
                sourceRootAvailable = false,
                sourceEntriesEmpty = false
            )
        )
    }

    @Test
    fun `active journal defers when source scan is empty`() {
        assertTrue(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
                sourceRootAvailable = true,
                sourceEntriesEmpty = true
            )
        )
    }

    @Test
    fun `legacy active journal defers even when a later source scan has entries`() {
        assertTrue(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
                sourceRootAvailable = true,
                sourceEntriesEmpty = false,
                sourceEntryCountKnown = false
            )
        )
    }

    @Test
    fun `partial source scan defers until all cleanup receipts are complete`() {
        assertTrue(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
                sourceRootAvailable = true,
                sourceEntriesEmpty = false,
                sourceEntryCountKnown = true,
                sourceEntriesIncomplete = true,
                cleanupReceiptComplete = false
            )
        )
        assertFalse(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
                sourceRootAvailable = true,
                sourceEntriesEmpty = false,
                sourceEntryCountKnown = true,
                sourceEntriesIncomplete = true,
                cleanupReceiptComplete = true
            )
        )
    }

    @Test
    fun `committed journal does not require source re-read`() {
        assertFalse(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
                sourceRootAvailable = false,
                sourceEntriesEmpty = true
            )
        )
    }

    @Test
    fun `complete cleanup receipt allows an empty source scan to resume`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(
            sourceEntryCount = 1,
            cleanupReceipts = listOf(cleanupReceipt())
        )

        assertFalse(
            shouldRetryActiveMigrationJournal(
                phase = journal.phase,
                sourceRootAvailable = true,
                sourceEntriesEmpty = true,
                cleanupReceiptComplete = hasCompleteMigrationCleanupReceipts(journal)
            )
        )
        assertTrue(hasCompleteMigrationCleanupReceipts(journal))
        assertFalse(journal.legacyUnknownCount)
    }

    @Test
    fun `complete source scan drops a user deleted entry but keeps verified receipts`() {
        val receipt = cleanupReceipt()
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(
            sourceEntryCount = 2,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = receipt.sourceReference,
                    sourceName = receipt.sourceName,
                    sourceSubdirectory = receipt.sourceSubdirectory,
                    sizeBytes = 10L,
                    lastModifiedMs = 1L
                ),
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/deleted",
                    sourceName = "deleted.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 10L,
                    lastModifiedMs = 1L
                )
            ),
            cleanupReceipts = listOf(receipt)
        )
        val current = ManagedMigrationEntry(
            subdirectory = null,
            entry = ManagedDownloadStorage.StoredEntry(
                name = "remaining.mp3",
                reference = "content://source/remaining",
                mediaUri = "content://source/remaining",
                localFilePath = null,
                sizeBytes = 8L,
                lastModifiedMs = 2L
            )
        )

        val reconciled = reconcileMigrationSourceManifest(journal, listOf(current))

        assertEquals(
            setOf(receipt.sourceReference, current.entry.reference),
            reconciled.sourceEntries.map(ManagedMigrationSourceEntry::sourceReference).toSet()
        )
        assertEquals(2, reconciled.sourceEntryCount)
    }

    @Test
    fun `active v1 journal without receipts is marked as unknown`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(
            version = 1,
            sourceEntryCount = 3
        )

        assertTrue(journal.legacyUnknownCount)
    }

    @Test
    fun `active v1 journal without a source count is marked as unknown`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(version = 1)

        assertTrue(journal.legacyUnknownCount)
    }

    @Test
    fun `legacy journal upgrades after a complete source scan`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(version = 1)

        val upgraded = upgradeLegacyMigrationReplacementJournal(
            journal = journal,
            sourceEntryCount = 4
        )

        assertEquals(CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION, upgraded.version)
        assertEquals(4, upgraded.sourceEntryCount)
        assertTrue(upgraded.cleanupReceipts.isEmpty())
        assertFalse(upgraded.legacyUnknownCount)
    }

    @Test
    fun `committed v1 journal is not marked as unknown`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(
            version = 1,
            phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
        )

        assertFalse(journal.legacyUnknownCount)
    }

    @Test
    fun `incomplete cleanup receipts cannot complete a journal`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(
            sourceEntryCount = 2,
            cleanupReceipts = listOf(cleanupReceipt())
        )

        assertFalse(hasCompleteMigrationCleanupReceipts(journal))
    }

    @Test
    fun `persisted and current receipts preserve the original source count`() {
        val oldReceipt = cleanupReceipt(sourceReference = "content://source/old")
        val currentReceipt = cleanupReceipt(sourceReference = "content://source/current")
        val merged = mergePersistedMigrationCleanupReceipts(
            persisted = listOf(oldReceipt),
            current = listOf(oldReceipt, currentReceipt)
        )

        assertEquals(2, merged.size)
        assertEquals(
            setOf(oldReceipt.sourceReference, currentReceipt.sourceReference),
            merged.map(ManagedMigrationCleanupReceipt::sourceReference).toSet()
        )
    }

    @Test
    fun `duplicate current receipt cannot replace persisted target identity`() {
        val persisted = cleanupReceipt()
        val current = persisted.copy(
            targetEntry = persisted.targetEntry.copy(
                reference = "content://target/replaced",
                mediaUri = "content://target/replaced"
            )
        )

        val merged = mergePersistedMigrationCleanupReceipts(
            persisted = listOf(persisted),
            current = listOf(current)
        )

        assertEquals(persisted.targetEntry.reference, merged.single().targetEntry.reference)
    }

    @Test
    fun `target names merge checkpoints and replacement names deterministically`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        )

        val merged = mergePersistedMigrationTargetNames(
            listOf(
                mapOf("source:cover" to "cover.jpg"),
                mapOf("content://source/track" to "track.mp3"),
                persistedMigrationJournalTargetNames(journal)
            )
        )

        assertEquals(
            mapOf(
                "source:cover" to "cover.jpg",
                "content://source/track" to "track.mp3"
            ),
            merged
        )
    }

    @Test
    fun `conflicting target name checkpoints are retryable`() {
        val failure = assertThrows(ManagedDownloadMigrationException::class.java) {
            mergePersistedMigrationTargetNames(
                listOf(
                    mapOf("source:track" to "track.mp3"),
                    mapOf("source:track" to "track (1).mp3")
                )
            )
        }

        assertTrue(failure.retryable)
    }

    @Test
    fun `replacement identity mismatch is retryable instead of being dropped`() {
        val source = sourceEntry()
        val generated = ManagedMigrationNamePlan(
            targetNamesByReference = mapOf(source.reference to "track.mp3"),
            replacementPlansByReference = mapOf(
                source.reference to replacementFor(groupIdentity = "stable:current")
            )
        )
        val persisted = journalFor(
            replacement = replacementFor(groupIdentity = "stable:persisted")
        )

        val failure = assertThrows(ManagedDownloadMigrationException::class.java) {
            mergePersistedMigrationReplacementPlan(generated, persisted)
        }

        assertTrue(failure.retryable)
    }

    @Test
    fun `matching replacement restores persisted backup name`() {
        val source = sourceEntry()
        val generatedReplacement = replacementFor(
            groupIdentity = "stable:song",
            backupName = ".np-migration-backup-new"
        )
        val persistedReplacement = generatedReplacement.copy(
            backupName = ".np-migration-backup-persisted"
        )
        val generated = ManagedMigrationNamePlan(
            targetNamesByReference = mapOf(source.reference to generatedReplacement.targetName),
            replacementPlansByReference = mapOf(source.reference to generatedReplacement)
        )
        val merged = mergePersistedMigrationReplacementPlan(
            generatedPlan = generated,
            persistedJournal = journalFor(persistedReplacement)
        )

        assertEquals(
            ".np-migration-backup-persisted",
            merged.replacementFor(ManagedMigrationEntryRef(null, source))?.backupName
        )
    }

    @Test
    fun `replacement missing from current source is retained as retryable ambiguity`() {
        val generated = ManagedMigrationNamePlan(targetNamesByReference = emptyMap())
        val failure = assertThrows(ManagedDownloadMigrationException::class.java) {
            mergePersistedMigrationReplacementPlan(
                generatedPlan = generated,
                persistedJournal = journalFor(replacementFor(groupIdentity = "stable:song"))
            )
        }

        assertTrue(failure.retryable)
    }

    @Test
    fun `replacement missing after receipt is retained for idempotent commit`() {
        val replacement = replacementFor(groupIdentity = "stable:song")
        val persisted = journalFor(replacement).copy(
            sourceEntryCount = 1,
            cleanupReceipts = listOf(cleanupReceipt())
        )

        val merged = mergePersistedMigrationReplacementPlan(
            generatedPlan = ManagedMigrationNamePlan(targetNamesByReference = emptyMap()),
            persistedJournal = persisted
        )

        assertEquals(replacement, merged.replacementPlansByReference[replacement.sourceReference])
    }

    private fun journalFor(
        replacement: ManagedMigrationReplacementPlan
    ): ManagedMigrationReplacementJournal {
        return ManagedMigrationReplacementJournal(
            workId = "work-1",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.PLANNED,
            replacements = listOf(replacement)
        )
    }

    private fun replacementFor(
        groupIdentity: String,
        backupName: String = ".np-migration-backup-old"
    ): ManagedMigrationReplacementPlan {
        val source = sourceEntry()
        val target = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://target/track",
            mediaUri = "content://target/track",
            localFilePath = null,
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )
        return ManagedMigrationReplacementPlan(
            sourceReference = source.reference,
            groupIdentity = groupIdentity,
            subdirectory = null,
            targetName = target.name,
            targetEntry = target,
            backupName = backupName
        )
    }

    private fun cleanupReceipt(
        sourceReference: String = "content://source/track"
    ): ManagedMigrationCleanupReceipt {
        val target = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://target/track",
            mediaUri = "content://target/track",
            localFilePath = null,
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )
        return ManagedMigrationCleanupReceipt(
            sourceReference = sourceReference,
            sourceName = "track.mp3",
            sourceSubdirectory = null,
            targetEntry = target,
            targetDigest = "a".repeat(64)
        )
    }

    private fun sourceEntry(): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://source/track",
            mediaUri = "content://source/track",
            localFilePath = null,
            sizeBytes = 12L,
            lastModifiedMs = 1L
        )
    }
}
