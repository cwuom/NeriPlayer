package moe.ouom.neriplayer.core.download.storage.migration.plan

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationModelsTest {

    @Test
    fun `created at confidence trims explicit values and otherwise derives from the source`() {
        assertEquals(
            "EXACT",
            migrationEntry(DownloadedAudioMetadata(createdAtConfidence = " EXACT ")).logicalCreatedAtConfidence()
        )
        assertEquals(
            "EXACT",
            migrationEntry(
                DownloadedAudioMetadata(createdAtConfidence = "  ", createdAtSource = " core_commit ")
            ).logicalCreatedAtConfidence()
        )
        assertEquals(
            "PROVIDER_REPORTED",
            migrationEntry(DownloadedAudioMetadata(sourceCreatedAtMs = 5L)).logicalCreatedAtConfidence()
        )
        assertEquals(
            "INFERRED",
            migrationEntry(metadata = null, lastModifiedMs = 10L).logicalCreatedAtConfidence()
        )
    }

    @Test
    fun `created at confidence is absent without any positive timestamp`() {
        val undated = migrationEntry(
            DownloadedAudioMetadata(createdAtMs = -1L, sourceCreatedAtMs = 0L),
            lastModifiedMs = 0L
        )

        assertNull(undated.logicalCreatedAtMs())
        assertNull(undated.logicalCreatedAtSource())
        assertNull(undated.logicalCreatedAtConfidence())
        assertNull(migrationEntry(metadata = null, lastModifiedMs = 0L).logicalCreatedAtConfidence())
    }

    @Test
    fun `created at sources map onto confidence buckets`() {
        listOf("CORE_COMMIT", "managed_commit", " MIGRATION_LOGICAL ", "filesystem_birth").forEach {
            assertEquals(it, "EXACT", resolveMigrationCreatedAtConfidence(it))
        }
        listOf("MEDIASTORE_DATE_ADDED", "provider_created_at", "PROVIDER_NATIVE").forEach {
            assertEquals(it, "PROVIDER_REPORTED", resolveMigrationCreatedAtConfidence(it))
        }
        listOf(
            "SAF_LAST_MODIFIED",
            "MEDIASTORE_DATE_MODIFIED",
            "mtime",
            "MTIME_FALLBACK",
            "INDEX_PREVIEW",
            "LEGACY_V15",
            "DOWNLOAD_TIME",
            "IMPORT_TIME"
        ).forEach {
            assertEquals(it, "INFERRED", resolveMigrationCreatedAtConfidence(it))
        }
        listOf("SOMETHING_ELSE", "", null).forEach {
            assertEquals("$it", "UNKNOWN", resolveMigrationCreatedAtConfidence(it))
        }
    }

    @Test
    fun `created at metadata rejects non positive timestamps and blank or oversized labels`() {
        assertTrue(isValidMigrationCreatedAtMetadata(null, null, null))
        assertTrue(isValidMigrationCreatedAtMetadata(1L, "x".repeat(64), "y".repeat(32)))

        assertFalse(isValidMigrationCreatedAtMetadata(0L, null, null))
        assertFalse(isValidMigrationCreatedAtMetadata(1L, "  ", null))
        assertFalse(isValidMigrationCreatedAtMetadata(1L, "x".repeat(65), null))
        assertFalse(isValidMigrationCreatedAtMetadata(1L, null, " "))
        assertFalse(isValidMigrationCreatedAtMetadata(1L, null, "y".repeat(33)))
    }

    @Test
    fun `journal treats any recorded source evidence as a complete source scan`() {
        assertFalse(journal().sourceEntriesComplete)
        assertFalse(journal().sourceEntryCountKnown)

        listOf(
            journal(sourceEntryCount = 2),
            journal(sourceEntries = listOf(sourceEntry())),
            journal(deletedSourceAudioCount = 1)
        ).forEach { journal ->
            assertTrue(journal.sourceEntriesComplete)
            assertTrue(journal.sourceEntryCountKnown)
        }
    }

    @Test
    fun `source entry count stays unknown for legacy journals and journals without evidence`() {
        assertFalse(journal(version = 1, sourceEntriesComplete = true).sourceEntryCountKnown)
        assertFalse(journal(sourceEntriesComplete = false).sourceEntryCountKnown)

        assertTrue(journal(sourceEntriesComplete = true).sourceEntryCountKnown)
        assertTrue(journal(sourceEntriesComplete = false, sourceEntryCount = 1).sourceEntryCountKnown)
        assertTrue(
            journal(sourceEntriesComplete = false, sourceEntries = listOf(sourceEntry())).sourceEntryCountKnown
        )
        assertTrue(journal(sourceEntriesComplete = false, deletedSourceAudioCount = 1).sourceEntryCountKnown)
    }

    @Test
    fun `verification progress counts unread source bytes and saturates at the long range`() {
        assertEquals(14L, copied(sourceSize = 10L, targetSize = 4L, digest = null).toVerificationProgressEntry().sizeBytes)
        assertEquals(14L, copied(sourceSize = 10L, targetSize = 4L, digest = " ").toVerificationProgressEntry().sizeBytes)
        assertEquals(
            4L,
            copied(sourceSize = 10L, targetSize = 4L, digest = "a".repeat(64)).toVerificationProgressEntry().sizeBytes
        )
        assertEquals(
            Long.MAX_VALUE,
            copied(sourceSize = Long.MAX_VALUE, targetSize = 1L, digest = null).toVerificationProgressEntry().sizeBytes
        )

        val progress = copied(sourceSize = -5L, targetSize = -1L, digest = null).toVerificationProgressEntry()
        assertEquals(0L, progress.sizeBytes)
        assertEquals("content://target/Song.flac", progress.reference)
        assertEquals("Song.flac", progress.name)
    }

    private fun migrationEntry(
        metadata: DownloadedAudioMetadata?,
        lastModifiedMs: Long = 0L
    ): ManagedMigrationEntry {
        return ManagedMigrationEntry(
            subdirectory = null,
            entry = stored("content://source/Song.flac", sizeBytes = 1L, lastModifiedMs = lastModifiedMs),
            metadata = metadata
        )
    }

    private fun copied(sourceSize: Long, targetSize: Long, digest: String?): CopiedMigrationEntry {
        return CopiedMigrationEntry(
            original = ManagedMigrationEntry(
                subdirectory = null,
                entry = stored("content://source/Song.flac", sizeBytes = sourceSize)
            ),
            copiedEntry = stored("content://target/Song.flac", sizeBytes = targetSize),
            createdNew = true,
            sourceDigest = digest
        )
    }

    private fun stored(reference: String, sizeBytes: Long, lastModifiedMs: Long = 1L): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = "Song.flac",
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs
        )
    }

    private fun sourceEntry(): ManagedMigrationSourceEntry {
        return ManagedMigrationSourceEntry(
            sourceReference = "content://source/Song.flac",
            sourceName = "Song.flac",
            sourceSubdirectory = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }

    private fun journal(
        version: Int = CURRENT_MANAGED_MIGRATION_REPLACEMENT_JOURNAL_VERSION,
        sourceEntryCount: Int = 0,
        sourceEntries: List<ManagedMigrationSourceEntry> = emptyList(),
        deletedSourceAudioCount: Int = 0,
        sourceEntriesComplete: Boolean? = null
    ): ManagedMigrationReplacementJournal {
        val journal = ManagedMigrationReplacementJournal(
            version = version,
            workId = "work-1",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.PLANNED,
            replacements = emptyList(),
            sourceEntryCount = sourceEntryCount,
            sourceEntries = sourceEntries,
            deletedSourceAudioCount = deletedSourceAudioCount
        )
        return sourceEntriesComplete?.let { journal.copy(sourceEntriesComplete = it) } ?: journal
    }
}
