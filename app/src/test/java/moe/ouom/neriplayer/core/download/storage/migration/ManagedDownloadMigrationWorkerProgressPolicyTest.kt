package moe.ouom.neriplayer.core.download.storage.migration

import androidx.work.workDataOf
import java.io.IOException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.ManagedLibraryRefreshPreserveReason
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationWorkerProgressPolicyTest {
    @Test
    fun `transient provider and IO failures retry within the bounded budget`() {
        assertTrue(
            shouldRetryMigrationFailure(
                error = ManagedDownloadMigrationException.transient("provider unavailable"),
                runAttemptCount = 1,
                maxRetryAttempts = 2
            )
        )
        assertTrue(
            shouldRetryMigrationFailure(
                error = IOException("provider interrupted"),
                runAttemptCount = 0,
                maxRetryAttempts = 2
            )
        )
        assertTrue(
            shouldRetryMigrationFailure(
                error = ManagedDownloadRootProviderException(
                    "content://provider/tree/root",
                    IOException("provider interrupted")
                ),
                runAttemptCount = 0,
                maxRetryAttempts = 2
            )
        )
        assertFalse(
            shouldRetryMigrationFailure(
                error = IOException("provider interrupted"),
                runAttemptCount = 2,
                maxRetryAttempts = 2
            )
        )
        assertFalse(
            shouldRetryMigrationFailure(
                error = ManagedDownloadMigrationException.permanent("permission revoked"),
                runAttemptCount = 0,
                maxRetryAttempts = 2
            )
        )
    }

    @Test
    fun `final scan must publish before migration can complete`() {
        assertFalse(
            shouldRetryAfterMigrationFinalScan(
                ManagedLibraryRefreshOutcome.Published(
                    rootKey = "content://provider/tree/target",
                    songCount = 1_000
                )
            )
        )
        assertTrue(
            shouldRetryAfterMigrationFinalScan(
                ManagedLibraryRefreshOutcome.Preserved(
                    ManagedLibraryRefreshPreserveReason.INCOMPLETE_ROOT_ENUMERATION
                )
            )
        )
        assertTrue(
            shouldRetryAfterMigrationFinalScan(
                ManagedLibraryRefreshOutcome.Failed("provider unavailable")
            )
        )
    }

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

    @Test
    fun `work data round trip preserves every migration progress counter`() {
        ManagedDownloadStorage.MigrationStage.entries.forEach { stage ->
            val progress = ManagedDownloadStorage.MigrationProgress(
                stage = stage,
                totalFiles = 41,
                processedFiles = 23,
                copiedFiles = 19,
                copiedBytes = 8_765_432_109L,
                totalBytes = 9_876_543_210L,
                metadataFilesProcessed = 3,
                metadataFilesTotal = 7,
                cleanupFilesProcessed = 5,
                cleanupFilesTotal = 11,
                currentFileName = "slow-saf-track.mp3",
                verificationFilesProcessed = 13,
                verificationFilesTotal = 17,
                verifiedBytes = 6_543_210_987L,
                verificationBytesTotal = 7_654_321_098L
            )

            assertEquals(
                progress,
                migrationProgressFromWorkData(migrationProgressToWorkData(progress))
            )
        }
        listOf<String?>(null, "").forEach { currentFileName ->
            val progress = progress(0.5f).copy(currentFileName = currentFileName)
            assertEquals(
                progress,
                migrationProgressFromWorkData(migrationProgressToWorkData(progress))
            )
        }
    }

    @Test
    fun `legacy work data remains readable without detailed counters`() {
        val legacyData = workDataOf(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE to
                ManagedDownloadStorage.MigrationStage.CLEANING_UP.name,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_FRACTION to 0.97f,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_PROCESSED_FILES to 8,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_FILES to 10,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_CURRENT_FILE to "legacy.mp3"
        )

        val restored = migrationProgressFromWorkData(legacyData)

        assertEquals(ManagedDownloadStorage.MigrationStage.CLEANING_UP, restored?.stage)
        assertEquals(8, restored?.processedFiles)
        assertEquals(10, restored?.cleanupFilesTotal)
        assertEquals(5, restored?.cleanupFilesProcessed)
        assertEquals("legacy.mp3", restored?.currentFileName)
    }

    @Test
    fun `verifying work data without detailed counters uses safe legacy defaults`() {
        val legacyData = workDataOf(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE to
                ManagedDownloadStorage.MigrationStage.VERIFYING.name,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_FRACTION to 0.945f,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_PROCESSED_FILES to 10,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_FILES to 10,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_CURRENT_FILE to "verify.mp3"
        )

        val restored = migrationProgressFromWorkData(legacyData)

        assertEquals(ManagedDownloadStorage.MigrationStage.VERIFYING, restored?.stage)
        assertEquals(5, restored?.verificationFilesProcessed)
        assertEquals(10, restored?.verificationFilesTotal)
        assertEquals(0L, restored?.verifiedBytes)
        assertEquals(0L, restored?.verificationBytesTotal)
    }

    @Test
    fun `unknown persisted migration stage is ignored`() {
        val data = workDataOf(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE to "REMOVED_STAGE"
        )

        assertNull(migrationProgressFromWorkData(data))
    }

    @Test
    fun `discovered source audio count overrides an empty UI lower bound`() {
        assertEquals(7, resolveMinimumMigrationAudioCount(0, 7))
        assertEquals(9, resolveMinimumMigrationAudioCount(9, 7))
        assertEquals(0, resolveMinimumMigrationAudioCount(-1, -1))
    }

    @Test
    fun `reused target advances copied bytes by the verified source size`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 1,
            totalBytes = 4_096L,
            metadataFilesTotal = 0,
            onProgress = updates::add
        )
        val reusedEntry = ManagedMigrationProgressEntry(
            reference = "content://provider/source/audio",
            name = "audio.mp3",
            sizeBytes = 4_096L
        )

        tracker.startCopy(reusedEntry)
        tracker.completeCopy(reusedEntry)
        tracker.finishAll()

        assertEquals(1, updates.last().copiedFiles)
        assertEquals(4_096L, updates.last().copiedBytes)
        assertEquals(1f, updates.last().fraction)
    }

    @Test
    fun `verification byte progress stays monotonic through cleanup`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        var nowMs = 0L
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 1,
            totalBytes = 100L,
            metadataFilesTotal = 1,
            onProgress = updates::add,
            nowMs = { nowMs }
        )
        val copyEntry = ManagedMigrationProgressEntry("source", "audio.mp3", 100L)
        val verificationEntry = ManagedMigrationProgressEntry("target", "audio.mp3", 200L)

        fun advance(action: () -> Unit) {
            nowMs += 300L
            action()
        }

        tracker.startPreparing(copyEntry.name)
        advance { tracker.startCopy(copyEntry) }
        advance { tracker.onCopyProgress(copyEntry, 50L) }
        advance { tracker.onCopyProgress(copyEntry, 20L) }
        advance { tracker.completeCopy(copyEntry) }
        advance { tracker.startRewrite(copyEntry.name) }
        advance { tracker.finishRewrite(copyEntry.name) }
        advance { tracker.startVerification(listOf(verificationEntry)) }
        advance { tracker.startVerificationEntry(verificationEntry) }
        advance { tracker.onVerificationProgress(verificationEntry, 50L) }
        advance { tracker.onVerificationProgress(verificationEntry, 20L) }
        advance { tracker.onVerificationProgress(verificationEntry, 150L) }
        advance { tracker.finishVerification(verificationEntry) }
        advance { tracker.startCleanup(totalEntries = 1, fileName = copyEntry.name) }
        advance { tracker.finishCleanup(copyEntry.name) }
        advance { tracker.finishAll() }

        assertTrue(updates.zipWithNext().all { (before, after) ->
            after.fraction >= before.fraction
        })
        val verificationProgress = updates.last { progress ->
            progress.stage == ManagedDownloadStorage.MigrationStage.VERIFYING
        }
        assertEquals(1, verificationProgress.verificationFilesProcessed)
        assertEquals(1, verificationProgress.verificationFilesTotal)
        assertEquals(200L, verificationProgress.verifiedBytes)
        assertEquals(200L, verificationProgress.verificationBytesTotal)
    }

    @Test
    fun `transient cleanup failure remains durable work instead of succeeding`() {
        assertEquals(
            MigrationCleanupWorkDecision.RETRY,
            migrationCleanupWorkDecision(
                ManagedDownloadStorage.MigrationResult(
                    movedFiles = 4,
                    skippedFiles = 0,
                    cleanupFailedFiles = 1,
                    cleanupRetryableFailedFiles = 1
                )
            )
        )
        assertEquals(
            MigrationCleanupWorkDecision.FAILURE,
            migrationCleanupWorkDecision(
                ManagedDownloadStorage.MigrationResult(
                    movedFiles = 4,
                    skippedFiles = 0,
                    cleanupFailedFiles = 1,
                    cleanupRetryableFailedFiles = 0
                )
            )
        )
        assertEquals(
            MigrationCleanupWorkDecision.COMPLETE,
            migrationCleanupWorkDecision(
                ManagedDownloadStorage.MigrationResult(
                    movedFiles = 4,
                    skippedFiles = 0
                )
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
