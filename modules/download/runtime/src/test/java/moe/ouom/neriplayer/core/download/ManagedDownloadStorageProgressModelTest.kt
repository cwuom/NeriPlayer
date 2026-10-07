package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageProgressModelTest {
    @Test
    fun `startup recovery reports work only when entries were cleaned or failed`() {
        assertFalse(ManagedDownloadStorage.StartupRecoveryResult().hasRecoveredEntries)
        assertFalse(
            ManagedDownloadStorage.StartupRecoveryResult(protectedCount = 3, externalSignalRequiredCount = 1)
                .hasRecoveredEntries
        )
        assertTrue(ManagedDownloadStorage.StartupRecoveryResult(cleanedCount = 1).hasRecoveredEntries)
        assertTrue(ManagedDownloadStorage.StartupRecoveryResult(failedCount = 2).hasRecoveredEntries)
    }

    @Test
    fun `only strong quoted etags can validate a resumed range`() {
        assertEquals("\"v1\"", fingerprint(" \"v1\" ").validator)
        listOf(null, "", "\"", "W/\"v1\"", "w/\"v1\"", "v1", "\"v1", "v1\"").forEach { etag ->
            assertNull(etag, fingerprint(etag).validator)
        }
    }

    @Test
    fun `migration stage progress reads the counters of the active stage`() {
        val progress = ManagedDownloadStorage.MigrationProgress(
            stage = MigrationStage.PREPARING,
            totalFiles = 10,
            processedFiles = 4,
            copiedFiles = 3,
            copiedBytes = 30L,
            totalBytes = 100L,
            metadataFilesProcessed = 2,
            metadataFilesTotal = 5,
            cleanupFilesProcessed = 1,
            cleanupFilesTotal = 6,
            verificationFilesProcessed = 7,
            verificationFilesTotal = 8
        )
        val expectedCounters = mapOf(
            MigrationStage.PREPARING to (0 to 10),
            MigrationStage.COPYING to (3 to 10),
            MigrationStage.REWRITING_METADATA to (2 to 5),
            MigrationStage.VERIFYING to (7 to 8),
            MigrationStage.CLEANING_UP to (1 to 6),
            MigrationStage.FINALIZING to (10 to 10)
        )

        assertEquals(MigrationStage.entries.toSet(), expectedCounters.keys)
        expectedCounters.forEach { (stage, counters) ->
            val staged = progress.copy(stage = stage)
            assertEquals(stage.name, counters, staged.stageProcessed to staged.stageTotal)
        }
    }

    private fun fingerprint(etag: String?) = ManagedDownloadStorage.WorkingResumeFingerprint(
        sourceUrl = "https://cdn.example/song.flac",
        etag = etag
    )
}
