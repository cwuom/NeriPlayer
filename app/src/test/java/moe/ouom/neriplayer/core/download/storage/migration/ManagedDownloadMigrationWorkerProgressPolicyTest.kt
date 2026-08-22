package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationWorkerProgressPolicyTest {
    @Test
    fun `first progress is published immediately`() {
        val progress = progress(0.1f)

        assertTrue(
            shouldPublishMigrationProgress(
                progress = progress,
                nowMs = 0L,
                state = MigrationProgressThrottleState(),
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    @Test
    fun `same stage and percent are throttled`() {
        val progress = progress(0.10f)
        val state = updateMigrationProgressThrottleState(progress, nowMs = 1_000L)

        assertFalse(
            shouldPublishMigrationProgress(
                progress = progress.copy(currentFileName = "next.mp3"),
                nowMs = 1_100L,
                state = state,
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    @Test
    fun `stage change is published without waiting for interval`() {
        val state = updateMigrationProgressThrottleState(progress(0.10f), nowMs = 1_000L)

        assertTrue(
            shouldPublishMigrationProgress(
                progress = progress(
                    fraction = 0.10f,
                    stage = ManagedDownloadStorage.MigrationStage.REWRITING_METADATA
                ),
                nowMs = 1_100L,
                state = state,
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    @Test
    fun `one percent change is published as a useful persisted update`() {
        val state = updateMigrationProgressThrottleState(progress(0.10f), nowMs = 1_000L)

        assertTrue(
            shouldPublishMigrationProgress(
                progress = progress(0.11f),
                nowMs = 1_100L,
                state = state,
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    private fun progress(
        fraction: Float,
        stage: ManagedDownloadStorage.MigrationStage =
            ManagedDownloadStorage.MigrationStage.COPYING
    ): ManagedDownloadStorage.MigrationProgress {
        return ManagedDownloadStorage.MigrationProgress(
            stage = stage,
            totalFiles = 100,
            processedFiles = (fraction * 100).toInt(),
            copiedFiles = (fraction * 100).toInt(),
            copiedBytes = 0L,
            totalBytes = 0L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0
        )
    }
}
