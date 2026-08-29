package moe.ouom.neriplayer.core.download.storage.migration

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationRecoveryTest {
    @Test
    fun `persisted manifest uses direct receipt validation after restart`() {
        assertTrue(shouldUseDirectMigrationReceiptValidation(true, 1))
        assertFalse(shouldUseDirectMigrationReceiptValidation(true, 0))
        assertFalse(shouldUseDirectMigrationReceiptValidation(false, 3))
    }

    @Test
    fun `tree target receipt recovery uses direct probes before layout fallback`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val recovery = source
            .substringAfter("private suspend fun buildMigrationTargetIndexFromReceipts(")
            .substringBefore("private suspend fun statMigrationReceiptTarget(")
        val directProbe = recovery.substringBefore("val snapshot =")
        val snapshotValidation = recovery.substringAfter("val snapshot =")

        assertTrue("receipt recovery must use direct target probes", directProbe.contains(
            "statMigrationReceiptTarget("
        ))
        assertTrue(
            "a failed target probe must fall back to the managed layout scan",
            snapshotValidation.contains("refreshManagedMigrationEntries(")
        )
        assertTrue(
            "snapshot validation must preserve the receipt subdirectory",
            snapshotValidation.contains("snapshot.entryFor(entry.subdirectory, receipt.targetEntry)")
        )
    }

    @Test
    fun `receipt recovery cannot skip SAF layout validation`() {
        val storage = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val finalizer = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationFinalizer.kt"
        ).readText()

        assertFalse(storage.contains("skipSafLayoutValidation"))
        assertFalse(finalizer.contains("skipSafLayoutValidation"))
    }

    @Test
    fun `tree root accepts standard child document ids but not sibling ids`() {
        assertTrue(
            isMigrationDocumentIdWithinTree(
                treeDocumentId = "primary:Music",
                documentId = "primary:Music/song.mp3"
            )
        )
        assertTrue(
            isMigrationDocumentIdWithinTree(
                treeDocumentId = "opaque-root",
                documentId = "opaque-root/child/token"
            )
        )
        assertFalse(
            isMigrationDocumentIdWithinTree(
                treeDocumentId = "primary:Music",
                documentId = "primary:MusicBackup/song.mp3"
            )
        )
    }

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
        assertEquals(1, reconciled.deletedSourceAudioCount)

        val retried = reconcileMigrationSourceManifest(reconciled, listOf(current))
        assertEquals(1, retried.deletedSourceAudioCount)
    }

    @Test
    fun `changed provider reference with the same source fingerprint is not counted as deletion`() {
        val journal = journalFor(
            replacement = replacementFor(groupIdentity = "stable:song")
        ).copy(
            sourceEntryCount = 1,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = "content://provider/tree-old/document/audio",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 10L,
                    lastModifiedMs = 11L
                )
            )
        )
        val current = ManagedMigrationEntry(
            subdirectory = null,
            entry = ManagedDownloadStorage.StoredEntry(
                name = "track.mp3",
                reference = "content://provider/tree-new/document/audio",
                mediaUri = "content://provider/tree-new/document/audio",
                localFilePath = null,
                sizeBytes = 10L,
                lastModifiedMs = 11L
            )
        )

        val reconciled = reconcileMigrationSourceManifest(journal, listOf(current))

        assertEquals(0, reconciled.deletedSourceAudioCount)
        assertEquals(listOf(current.entry.reference), reconciled.sourceEntries.map(
            ManagedMigrationSourceEntry::sourceReference
        ))
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
    fun `source count includes distinct cleanup receipt references`() {
        val source = sourceManifestEntry(copyReceipt())
        val retained = cleanupReceipt(sourceReference = "content://source/retained")

        assertEquals(
            2,
            migrationSourceEntryCount(
                sourceEntries = listOf(source),
                cleanupReceipts = listOf(retained)
            )
        )
    }

    @Test
    fun `deleted source entries are removed from unfinished journal`() {
        val deletedReference = "content://source/track"
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            sourceEntryCount = 2,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = deletedReference,
                    sourceName = "deleted.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 4L,
                    lastModifiedMs = 1L
                ),
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/remaining",
                    sourceName = "remaining.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 4L,
                    lastModifiedMs = 1L
                )
            )
        )

        val updated = removeDeletedMigrationSources(journal, listOf(deletedReference))

        assertEquals(1, updated.sourceEntryCount)
        assertEquals(
            listOf("content://source/remaining"),
            updated.sourceEntries.map(ManagedMigrationSourceEntry::sourceReference)
        )
        assertTrue(updated.replacements.isEmpty())
        assertEquals(1, updated.deletedSourceAudioCount)
    }

    @Test
    fun `deleted metadata and sidecar entries do not lower audio minimum`() {
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            sourceEntryCount = 2,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/audio",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 4L,
                    lastModifiedMs = 1L
                ),
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/metadata",
                    sourceName = "track.npmeta.json",
                    sourceSubdirectory = null,
                    sizeBytes = 4L,
                    lastModifiedMs = 1L
                )
            )
        )

        val updated = removeDeletedMigrationSources(
            journal,
            listOf("content://source/metadata")
        )

        assertEquals(0, updated.deletedSourceAudioCount)
        assertEquals(1, updated.sourceEntryCount)
    }

    @Test
    fun `all deleted audio sources keep an empty manifest count known`() {
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            sourceEntryCount = 1,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/audio",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 4L,
                    lastModifiedMs = 1L
                )
            )
        )

        val updated = removeDeletedMigrationSources(
            journal,
            listOf("content://source/audio")
        )

        assertTrue(updated.sourceEntries.isEmpty())
        assertEquals(0, updated.sourceEntryCount)
        assertEquals(1, updated.deletedSourceAudioCount)
        assertTrue(updated.sourceEntryCountKnown)
    }

    @Test
    fun `deleted source does not remove an already verified receipt`() {
        val receipt = cleanupReceipt()
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            sourceEntryCount = 1,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = receipt.sourceReference,
                    sourceName = receipt.sourceName,
                    sourceSubdirectory = receipt.sourceSubdirectory,
                    sizeBytes = 10L,
                    lastModifiedMs = 1L
                )
            ),
            cleanupReceipts = listOf(receipt)
        )

        val updated = removeDeletedMigrationSources(journal, listOf(receipt.sourceReference))

        assertEquals(journal, updated)
    }

    @Test
    fun `copy receipts with conflicting identities are retryable`() {
        val first = copyReceipt()
        val conflict = first.copy(targetEntry = first.targetEntry.copy(
            reference = "content://target/other",
            mediaUri = "content://target/other"
        ))

        val failure = assertThrows(ManagedDownloadMigrationException::class.java) {
            mergePersistedMigrationCopyReceipts(listOf(listOf(first), listOf(conflict)))
        }

        assertTrue(failure.retryable)
    }

    @Test
    fun `current work copy receipt supersedes a conflicting older checkpoint receipt`() {
        val stale = copyReceipt(sourceReference = "content://source/current")
        val refreshed = stale.copy(
            targetEntry = stale.targetEntry.copy(
                reference = "content://target/refreshed",
                mediaUri = "content://target/refreshed"
            )
        )
        val unrelated = copyReceipt(sourceReference = "content://source/other")

        val merged = mergePersistedMigrationCopyReceipts(
            current = listOf(refreshed),
            checkpoints = listOf(listOf(stale), listOf(unrelated))
        )

        assertEquals(refreshed, merged[refreshed.sourceReference])
        assertEquals(unrelated, merged[unrelated.sourceReference])
        assertEquals(2, merged.size)
    }

    @Test
    fun `local replacement target with matching fingerprint and changed digest is restored`() {
        val expected = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "/target/track.mp3",
            mediaUri = "file:///target/track.mp3",
            localFilePath = "/target/track.mp3",
            sizeBytes = 20L,
            lastModifiedMs = 20L
        )
        val backup = ManagedDownloadStorage.StoredEntry(
            name = ".np-migration-backup-old",
            reference = "/target/.np-migration-backup-old",
            mediaUri = "file:///target/.np-migration-backup-old",
            localFilePath = "/target/.np-migration-backup-old",
            sizeBytes = 12L,
            lastModifiedMs = 11L
        )
        val restored = backup.copy(
            name = expected.name,
            reference = expected.reference,
            mediaUri = expected.mediaUri,
            localFilePath = expected.localFilePath
        )

        assertTrue(
            ManagedDownloadStorage.isRestoredMigrationReplacementTarget(
                expectedTarget = expected,
                actualTarget = restored.copy(sizeBytes = backup.sizeBytes),
                replacementBackup = backup,
                targetDigest = "b".repeat(64),
                expectedTargetDigest = "a".repeat(64)
            )
        )
    }

    @Test
    fun `restored replacement target rejects changed backup fingerprint`() {
        val expected = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "/target/track.mp3",
            mediaUri = "file:///target/track.mp3",
            localFilePath = "/target/track.mp3",
            sizeBytes = 20L,
            lastModifiedMs = 20L
        )
        val backup = ManagedDownloadStorage.StoredEntry(
            name = ".np-migration-backup-old",
            reference = "/target/.np-migration-backup-old",
            mediaUri = "file:///target/.np-migration-backup-old",
            localFilePath = "/target/.np-migration-backup-old",
            sizeBytes = 12L,
            lastModifiedMs = 11L
        )
        val changed = backup.copy(
            name = expected.name,
            reference = expected.reference,
            mediaUri = expected.mediaUri,
            localFilePath = expected.localFilePath,
            lastModifiedMs = 99L
        )

        assertFalse(
            ManagedDownloadStorage.isRestoredMigrationReplacementTarget(
                expectedTarget = expected,
                actualTarget = changed,
                replacementBackup = backup,
                targetDigest = "b".repeat(64),
                expectedTargetDigest = "a".repeat(64)
            )
        )
    }

    @Test
    fun `restored replacement target rejects content matching the new target`() {
        val expected = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "/target/track.mp3",
            mediaUri = "file:///target/track.mp3",
            localFilePath = "/target/track.mp3",
            sizeBytes = 20L,
            lastModifiedMs = 20L
        )
        val backup = ManagedDownloadStorage.StoredEntry(
            name = ".np-migration-backup-old",
            reference = "/target/.np-migration-backup-old",
            mediaUri = "file:///target/.np-migration-backup-old",
            localFilePath = "/target/.np-migration-backup-old",
            sizeBytes = 12L,
            lastModifiedMs = 11L
        )
        val restored = backup.copy(
            name = expected.name,
            reference = expected.reference,
            mediaUri = expected.mediaUri,
            localFilePath = expected.localFilePath
        )

        assertFalse(
            ManagedDownloadStorage.isRestoredMigrationReplacementTarget(
                expectedTarget = expected,
                actualTarget = restored,
                replacementBackup = backup,
                targetDigest = "a".repeat(64),
                expectedTargetDigest = "a".repeat(64)
            )
        )
    }

    @Test
    fun `SAF replacement target requires the original backup identity`() {
        val expected = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://provider/tree/root/document/new-target",
            mediaUri = "content://provider/tree/root/document/new-target",
            localFilePath = null,
            sizeBytes = 20L,
            lastModifiedMs = 20L
        )
        val backup = ManagedDownloadStorage.StoredEntry(
            name = ".np-migration-backup-old",
            reference = "content://provider/tree/root/document/old-target",
            mediaUri = "content://provider/tree/root/document/old-target",
            localFilePath = null,
            sizeBytes = 12L,
            lastModifiedMs = 11L
        )
        val foreign = backup.copy(
            name = expected.name,
            reference = "content://provider/tree/root/document/foreign",
            mediaUri = "content://provider/tree/root/document/foreign"
        )

        assertFalse(
            ManagedDownloadStorage.isRestoredMigrationReplacementTarget(
                expectedTarget = expected,
                actualTarget = foreign,
                replacementBackup = backup,
                targetDigest = "b".repeat(64),
                expectedTargetDigest = "a".repeat(64)
            )
        )
    }

    @Test
    fun `SAF replacement target accepts the backup document after rename`() {
        val expected = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://provider/tree/root/document/new-target",
            mediaUri = "content://provider/tree/root/document/new-target",
            localFilePath = null,
            sizeBytes = 20L,
            lastModifiedMs = 20L
        )
        val backup = ManagedDownloadStorage.StoredEntry(
            name = ".np-migration-backup-old",
            reference = "content://provider/tree/root/document/old-target",
            mediaUri = "content://provider/tree/root/document/old-target",
            localFilePath = null,
            sizeBytes = 12L,
            lastModifiedMs = 11L
        )
        val restored = backup.copy(name = expected.name)

        assertTrue(
            ManagedDownloadStorage.isRestoredMigrationReplacementTarget(
                expectedTarget = expected,
                actualTarget = restored,
                replacementBackup = backup
            )
        )
    }

    @Test
    fun `current copy receipts replace stale persisted rows for recovery`() {
        val stale = copyReceipt(sourceReference = "content://source/current")
        val refreshed = stale.copy(
            targetEntry = stale.targetEntry.copy(
                reference = "content://target/refreshed",
                mediaUri = "content://target/refreshed"
            )
        )
        val unrelated = copyReceipt(sourceReference = "content://source/other")

        val merged = ManagedDownloadStorage.mergeMigrationCopyReceiptsForRecovery(
            persisted = mapOf(
                stale.sourceReference to stale,
                unrelated.sourceReference to unrelated
            ),
            current = listOf(refreshed)
        )

        assertEquals(refreshed, merged[refreshed.sourceReference])
        assertEquals(unrelated, merged[unrelated.sourceReference])
        assertEquals(2, merged.size)
    }

    @Test
    fun `missing newly created copy receipt becomes a rollback candidate`() {
        val receipt = copyReceipt(
            sourceReference = "content://source/new",
            createdNew = true,
            sourceAuthoritative = true
        )
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            replacements = emptyList(),
            sourceEntryCount = 1,
            sourceEntries = listOf(sourceManifestEntry(receipt))
        )

        val recovery = planDeletedSourceCopyReceiptRecovery(
            journal = journal,
            currentSourceReferences = emptyList(),
            copyReceipts = mapOf(receipt.sourceReference to receipt)
        )

        assertEquals(listOf(receipt), recovery.rollbackCandidates)
        assertTrue(recovery.promoteCandidates.isEmpty())
        assertTrue(recovery.preserveCandidates.isEmpty())
        assertEquals(1, recovery.deletedSourceAudioCount)
        assertTrue(recovery.journal.sourceEntries.isEmpty())
        assertEquals(0, recovery.journal.sourceEntryCount)
    }

    @Test
    fun `replacement backup rolls back while source authoritative target can be promoted`() {
        val replacementReceipt = copyReceipt(
            sourceReference = "content://source/replacement",
            createdNew = false,
            sourceAuthoritative = true,
            replacementBackup = backupEntry("track-old.mp3")
        )
        val promoteReceipt = copyReceipt(
            sourceReference = "content://source/promote",
            createdNew = false,
            sourceAuthoritative = true
        )
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            replacements = emptyList(),
            sourceEntryCount = 2,
            sourceEntries = listOf(
                sourceManifestEntry(replacementReceipt),
                sourceManifestEntry(promoteReceipt)
            )
        )

        val recovery = planDeletedSourceCopyReceiptRecovery(
            journal = journal,
            currentSourceReferences = emptyList(),
            copyReceipts = listOf(replacementReceipt, promoteReceipt)
        )

        assertEquals(listOf(promoteReceipt), recovery.promoteCandidates)
        assertEquals(listOf(replacementReceipt), recovery.rollbackCandidates)
        assertTrue(recovery.preserveCandidates.isEmpty())
        assertEquals(2, recovery.deletedSourceAudioCount)
    }

    @Test
    fun `preexisting target without backup is preserved when source is missing`() {
        val receipt = copyReceipt(
            sourceReference = "content://source/existing",
            createdNew = false,
            sourceAuthoritative = false
        )
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            replacements = emptyList(),
            sourceEntryCount = 1,
            sourceEntries = listOf(sourceManifestEntry(receipt))
        )

        val recovery = planDeletedSourceCopyReceiptRecovery(
            journal = journal,
            currentSourceReferences = emptyList(),
            copyReceipts = listOf(receipt)
        )

        assertEquals(listOf(receipt), recovery.preserveCandidates)
        assertTrue(recovery.promoteCandidates.isEmpty())
        assertTrue(recovery.rollbackCandidates.isEmpty())
        assertEquals(1, recovery.deletedSourceAudioCount)
        assertTrue(recovery.journal.sourceEntries.isEmpty())
    }

    @Test
    fun `existing cleanup receipt is not processed again`() {
        val receipt = copyReceipt(
            sourceReference = "content://source/verified",
            createdNew = true,
            sourceAuthoritative = true
        )
        val cleanup = cleanupReceipt(sourceReference = receipt.sourceReference)
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            replacements = emptyList(),
            sourceEntryCount = 1,
            sourceEntries = listOf(sourceManifestEntry(receipt)),
            cleanupReceipts = listOf(cleanup),
            deletedSourceAudioCount = 4
        )

        val recovery = planDeletedSourceCopyReceiptRecovery(
            journal = journal,
            currentSourceReferences = emptyList(),
            copyReceipts = mapOf(receipt.sourceReference to receipt)
        )

        assertTrue(recovery.promoteCandidates.isEmpty())
        assertTrue(recovery.rollbackCandidates.isEmpty())
        assertTrue(recovery.preserveCandidates.isEmpty())
        assertEquals(4, recovery.deletedSourceAudioCount)
        assertEquals(journal, recovery.journal)
    }

    @Test
    fun `copy receipt outside the active journal is ignored`() {
        val unrelatedReceipt = copyReceipt(
            sourceReference = "content://source/older-work",
            createdNew = true,
            sourceAuthoritative = true
        )
        val journal = journalFor(replacementFor(groupIdentity = "stable:song")).copy(
            sourceEntryCount = 1,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/track",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 12L,
                    lastModifiedMs = 1L
                )
            )
        )

        val recovery = planDeletedSourceCopyReceiptRecovery(
            journal = journal,
            currentSourceReferences = emptyList(),
            copyReceipts = listOf(unrelatedReceipt)
        )

        assertEquals(journal, recovery.journal)
        assertTrue(recovery.promoteCandidates.isEmpty())
        assertTrue(recovery.rollbackCandidates.isEmpty())
        assertTrue(recovery.preserveCandidates.isEmpty())
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

    private fun copyReceipt(
        sourceReference: String = "content://source/track",
        createdNew: Boolean = true,
        sourceAuthoritative: Boolean = true,
        replacementBackup: ManagedDownloadStorage.StoredEntry? = null
    ): ManagedMigrationCopyReceipt {
        val source = sourceEntry()
        val target = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://target/track",
            mediaUri = "content://target/track",
            localFilePath = null,
            sizeBytes = 12L,
            lastModifiedMs = 2L
        )
        return ManagedMigrationCopyReceipt(
            sourceReference = sourceReference,
            sourceName = source.name,
            sourceSubdirectory = null,
            sourceSizeBytes = source.sizeBytes,
            sourceLastModifiedMs = source.lastModifiedMs,
            targetEntry = target,
            sourceDigest = "a".repeat(64),
            verifiedTargetDigest = "a".repeat(64),
            createdNew = createdNew,
            sourceAuthoritative = sourceAuthoritative,
            replacementBackup = replacementBackup
        )
    }

    private fun sourceManifestEntry(
        receipt: ManagedMigrationCopyReceipt
    ): ManagedMigrationSourceEntry {
        return ManagedMigrationSourceEntry(
            sourceReference = receipt.sourceReference,
            sourceName = receipt.sourceName,
            sourceSubdirectory = receipt.sourceSubdirectory,
            sizeBytes = receipt.sourceSizeBytes,
            lastModifiedMs = receipt.sourceLastModifiedMs
        )
    }

    private fun backupEntry(name: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "content://target/$name",
            mediaUri = "content://target/$name",
            localFilePath = null,
            sizeBytes = 8L,
            lastModifiedMs = 1L
        )
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
