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
