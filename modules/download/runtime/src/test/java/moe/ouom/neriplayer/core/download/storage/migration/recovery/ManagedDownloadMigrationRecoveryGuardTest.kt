package moe.ouom.neriplayer.core.download.storage.migration.recovery

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationCleanupReceipt
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationNamePlan
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationSourceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationRecoveryGuardTest {

    @Test
    fun `source entry count ignores blank references and counts each reference once`() {
        assertEquals(0, migrationSourceEntryCount(emptyList(), emptyList()))
        assertEquals(
            2,
            migrationSourceEntryCount(
                sourceEntries = listOf(sourceEntry(" content://source/a "), sourceEntry("  "), sourceEntry("content://source/a")),
                cleanupReceipts = listOf(cleanupReceipt(" content://source/b "), cleanupReceipt(""), cleanupReceipt("content://source/a"))
            )
        )
    }

    @Test
    fun `journal without an active phase is never retried`() {
        assertFalse(
            shouldRetryActiveMigrationJournal(
                phase = null,
                sourceRootAvailable = false,
                sourceEntriesEmpty = true
            )
        )
        assertFalse(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
                sourceRootAvailable = false,
                sourceEntriesEmpty = true
            )
        )
        assertTrue(
            shouldRetryActiveMigrationJournal(
                phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
                sourceRootAvailable = false,
                sourceEntriesEmpty = true
            )
        )
    }

    @Test
    fun `document ids must be non blank and inside the tree`() {
        assertFalse(isMigrationDocumentIdWithinTree("  ", "primary:Music"))
        assertFalse(isMigrationDocumentIdWithinTree("primary:Music", " "))
        assertFalse(isMigrationDocumentIdWithinTree("primary:Music", "primary:MusicBackup"))

        assertTrue(isMigrationDocumentIdWithinTree(" primary:Music ", "primary:Music"))
        assertTrue(isMigrationDocumentIdWithinTree("primary:Music", " primary:Music/Covers/a.jpg "))
    }

    @Test
    fun `target digest is reusable only for matching positive fingerprints`() {
        assertTrue(canReuseMigrationTargetDigest(10L, 10L, 5L, 5L))

        assertFalse(canReuseMigrationTargetDigest(0L, 0L, 5L, 5L))
        assertFalse(canReuseMigrationTargetDigest(10L, 0L, 5L, 5L))
        assertFalse(canReuseMigrationTargetDigest(10L, 11L, 5L, 5L))
        assertFalse(canReuseMigrationTargetDigest(10L, 10L, 0L, 5L))
        assertFalse(canReuseMigrationTargetDigest(10L, 10L, 5L, 0L))
        assertFalse(canReuseMigrationTargetDigest(10L, 10L, 5L, 6L))
    }

    @Test
    fun `cleanup receipt merge rejects duplicates that disagree on the verified target`() {
        val persisted = cleanupReceipt()
        listOf(
            persisted.copy(sourceName = "other.mp3"),
            persisted.copy(sourceSubdirectory = "Covers"),
            persisted.copy(targetEntry = persisted.targetEntry.copy(name = "other.mp3")),
            persisted.copy(targetDigest = "b".repeat(64))
        ).forEach { conflicting ->
            val failure = assertThrows(ManagedDownloadMigrationException::class.java) {
                mergePersistedMigrationCleanupReceipts(listOf(persisted), listOf(conflicting))
            }
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun `cleanup receipt merge validates entries and sorts them deterministically`() {
        val lyric = cleanupReceipt("content://source/3", subdirectory = "Lyrics", targetName = "b.lrc")
        val laterRoot = cleanupReceipt("content://source/1", targetName = "z.mp3")
        val earlierRoot = cleanupReceipt("content://source/2", targetName = "a.mp3")
        val cover = cleanupReceipt("content://source/4", subdirectory = "Covers", targetName = "c.jpg")

        assertEquals(
            listOf(earlierRoot, laterRoot, cover, lyric),
            mergePersistedMigrationCleanupReceipts(listOf(lyric, laterRoot), listOf(earlierRoot, cover))
        )
        listOf(
            cleanupReceipt("  "),
            cleanupReceipt().copy(targetDigest = "a".repeat(63)),
            cleanupReceipt().copy(targetDigest = "g".repeat(64)),
            cleanupReceipt(subdirectory = "Elsewhere")
        ).forEach { invalid ->
            assertThrows(ManagedDownloadMigrationException::class.java) {
                mergePersistedMigrationCleanupReceipts(emptyList(), listOf(invalid))
            }
        }
    }

    @Test
    fun `replacement merge adopts persisted plans the generated plan did not recreate`() {
        val persisted = replacement()

        val merged = mergePersistedMigrationReplacementPlan(
            generatedPlan = ManagedMigrationNamePlan(targetNamesByReference = mapOf(SOURCE to "track.mp3")),
            persistedJournal = journalFor(persisted)
        )

        assertEquals(mapOf(SOURCE to persisted), merged.replacementPlansByReference)
        assertEquals(mapOf(SOURCE to "track.mp3"), merged.targetNamesByReference)
    }

    @Test
    fun `replacement merge rejects duplicate sources and changed targets`() {
        val persisted = replacement()
        val generated = ManagedMigrationNamePlan(targetNamesByReference = mapOf(SOURCE to "track.mp3"))
        listOf(
            generated to journalFor(persisted, persisted.copy(sourceReference = " $SOURCE ")),
            ManagedMigrationNamePlan(targetNamesByReference = mapOf(SOURCE to "track (1).mp3")) to journalFor(persisted),
            generated.withReplacement(persisted.copy(targetName = "track (1).mp3")) to journalFor(persisted),
            generated.withReplacement(persisted.copy(subdirectory = "Covers")) to journalFor(persisted)
        ).forEach { (generatedPlan, journal) ->
            val failure = assertThrows(ManagedDownloadMigrationException::class.java) {
                mergePersistedMigrationReplacementPlan(generatedPlan, journal)
            }
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun `orphan selection ignores blank references and trims explicit ones`() {
        val replacement = replacement()
        val journal = journalFor(replacement)

        assertEquals(
            emptyList<ManagedMigrationReplacementPlan>(),
            selectOrphanedMigrationReplacementPlans(journal, listOf("  ", ""), emptyList())
        )
        assertEquals(
            listOf(replacement),
            selectOrphanedMigrationReplacementPlans(journal, listOf(" $SOURCE ", ""), listOf("  "))
        )
        assertEquals(
            emptyList<ManagedMigrationReplacementPlan>(),
            selectOrphanedMigrationReplacementPlans(
                journal.copy(cleanupReceipts = listOf(cleanupReceipt())),
                listOf(SOURCE),
                emptyList()
            )
        )
    }

    @Test
    fun `migration plan names must be a single safe path segment`() {
        listOf("  ", ".", "..", "nested/track.mp3", "nested\\track.mp3").forEach { name ->
            assertFalse(name, isSafeMigrationPlanName(name))
        }
        listOf("track.mp3", ".np-migration-backup-old", "...").forEach { name ->
            assertTrue(name, isSafeMigrationPlanName(name))
        }
    }

    private fun ManagedMigrationNamePlan.withReplacement(
        replacement: ManagedMigrationReplacementPlan
    ): ManagedMigrationNamePlan {
        return copy(replacementPlansByReference = mapOf(replacement.sourceReference to replacement))
    }

    private fun journalFor(vararg replacements: ManagedMigrationReplacementPlan): ManagedMigrationReplacementJournal {
        return ManagedMigrationReplacementJournal(
            workId = "work-1",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.PLANNED,
            replacements = replacements.toList()
        )
    }

    private fun replacement(): ManagedMigrationReplacementPlan {
        val target = targetEntry("track.mp3")
        return ManagedMigrationReplacementPlan(
            sourceReference = SOURCE,
            groupIdentity = "stable:song",
            subdirectory = null,
            targetName = target.name,
            targetEntry = target,
            backupName = ".np-migration-backup-old"
        )
    }

    private fun cleanupReceipt(
        sourceReference: String = SOURCE,
        subdirectory: String? = null,
        targetName: String = "track.mp3"
    ): ManagedMigrationCleanupReceipt {
        return ManagedMigrationCleanupReceipt(
            sourceReference = sourceReference,
            sourceName = "track.mp3",
            sourceSubdirectory = subdirectory,
            targetEntry = targetEntry(targetName),
            targetDigest = "a".repeat(64)
        )
    }

    private fun sourceEntry(reference: String): ManagedMigrationSourceEntry {
        return ManagedMigrationSourceEntry(
            sourceReference = reference,
            sourceName = "track.mp3",
            sourceSubdirectory = null,
            sizeBytes = 12L,
            lastModifiedMs = 1L
        )
    }

    private fun targetEntry(name: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "content://target/$name",
            mediaUri = "content://target/$name",
            localFilePath = null,
            sizeBytes = 10L,
            lastModifiedMs = 1L
        )
    }

    private companion object {
        const val SOURCE = "content://source/track"
    }
}
