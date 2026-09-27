package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppStatusBannerPolicyTest {
    @Test
    fun collapseDragAccumulatesOnceAndResetsForTheNextGesture() {
        val tracker = ManagedProcessingCollapseDragTracker(thresholdPx = 24f)
        assertEquals(false, tracker.addDrag(-10f))
        assertEquals(false, tracker.addDrag(-13f))
        assertEquals(true, tracker.addDrag(-1f))
        assertEquals(false, tracker.addDrag(-30f))
        tracker.reset()
        assertEquals(false, tracker.addDrag(3f))
        assertEquals(true, tracker.addDrag(-27f))
    }

    @Test
    fun revealDragAccumulatesOnlyTheStartingPointerUntilThresholdOrRelease() {
        val tracker = ManagedProcessingRevealDragTracker(
            startX = 0f,
            startY = 40f,
            edgePx = 96f,
            thresholdPx = 24f
        )
        assertEquals(false, tracker.onMotion(3f, 50f, pressed = true))
        assertEquals(true, tracker.active)
        assertEquals(true, tracker.onMotion(3f, 64f, pressed = true))
        assertEquals(false, tracker.active)

        val released = ManagedProcessingRevealDragTracker(0f, 40f, 96f, 24f)
        assertEquals(false, released.onMotion(0f, 63f, pressed = false))
        assertEquals(false, released.active)
        val outsideEdge = ManagedProcessingRevealDragTracker(0f, 97f, 96f, 24f)
        assertEquals(false, outsideEdge.onMotion(0f, 130f, pressed = true))
        outsideEdge.cancel()
        assertEquals(false, outsideEdge.active)
    }

    @Test
    fun titleAndMigrationStageLabelsCoverTheActivePhases() {
        assertNull(managedProcessingTitleResource(null))
        assertEquals(
            R.string.managed_library_processing_upgrade_title,
            managedProcessingTitleResource(ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE)
        )
        assertEquals(
            R.string.managed_library_processing_directory_title,
            managedProcessingTitleResource(ManagedLibraryProcessingReason.DIRECTORY_CHANGE)
        )

        val labels = mapOf(
            ManagedDownloadStorage.MigrationStage.PREPARING to
                R.string.settings_download_directory_migrating_stage_preparing,
            ManagedDownloadStorage.MigrationStage.COPYING to
                R.string.settings_download_directory_migrating_stage_copying,
            ManagedDownloadStorage.MigrationStage.REWRITING_METADATA to
                R.string.settings_download_directory_migrating_stage_rewriting,
            ManagedDownloadStorage.MigrationStage.VERIFYING to
                R.string.settings_download_directory_migrating_stage_verifying,
            ManagedDownloadStorage.MigrationStage.CLEANING_UP to
                R.string.settings_download_directory_migrating_stage_cleanup,
            ManagedDownloadStorage.MigrationStage.FINALIZING to
                R.string.settings_download_directory_migrating
        )
        assertNull(managedProcessingStageResource(null))
        labels.forEach { (stage, label) ->
            assertEquals(label, managedProcessingStageResource(stage))
        }
        assertEquals(HiddenManagedProcessingRow, managedProcessingStageRow(null))
        assertEquals(
            ManagedProcessingStageRow(R.string.settings_download_directory_migrating_stage_copying),
            managedProcessingStageRow(migration(ManagedDownloadStorage.MigrationStage.COPYING))
        )
        assertEquals(HiddenManagedProcessingRow, managedProcessingFileRow(null))
        val copying = migration(ManagedDownloadStorage.MigrationStage.COPYING)
        assertEquals(HiddenManagedProcessingRow, managedProcessingFileRow(copying))
        assertEquals(HiddenManagedProcessingRow, managedProcessingFileRow(copying.copy(currentFileName = " ")))
        assertEquals(
            ManagedProcessingFileRow("track.flac"),
            managedProcessingFileRow(copying.copy(currentFileName = "track.flac"))
        )
    }

    @Test
    fun migrationStageProgressOverridesTheFallbackCountWithoutResettingTheOverallFraction() {
        val state = running(processed = 7, total = 10)
        val copying = migration(
            stage = ManagedDownloadStorage.MigrationStage.COPYING,
            copiedFiles = 2,
            copiedBytes = 400,
            totalBytes = 1_000
        )
        assertEquals(ManagedProcessingCount(2, 10), managedProcessingCount(state, copying))
        assertEquals(copying.fraction, managedProcessingFraction(managedProcessingCount(state, copying), copying))
        assertEquals(
            ManagedProcessingBytes(
                R.string.settings_download_directory_migrating_progress_bytes,
                400,
                1_000
            ),
            managedProcessingBytes(copying)
        )

        val verifying = migration(
            stage = ManagedDownloadStorage.MigrationStage.VERIFYING,
            verificationFilesProcessed = 1,
            verificationFilesTotal = 4,
            verifiedBytes = 300,
            verificationBytesTotal = 1_000
        )
        assertEquals(ManagedProcessingCount(1, 4), managedProcessingCount(state, verifying))
        assertEquals(verifying.fraction, managedProcessingFraction(managedProcessingCount(state, verifying), verifying))
        assertEquals(
            ManagedProcessingBytes(
                R.string.settings_download_directory_migrating_verification_progress_bytes,
                300,
                1_000
            ),
            managedProcessingBytes(verifying)
        )
    }

    @Test
    fun absentMigrationUsesCoordinatorProgressAndUnknownTotalsStayIndeterminate() {
        val state = running(processed = 7, total = 10)
        assertEquals(ManagedProcessingCount(7, 10), managedProcessingCount(state, null))
        assertEquals(0.7f, managedProcessingFraction(managedProcessingCount(state, null), null)!!, 0.0001f)
        assertNull(managedProcessingCount(running(processed = 2, total = null), null))
        assertNull(managedProcessingCount(running(processed = 2, total = 0), null))
        assertNull(managedProcessingCount(running(processed = 2, total = -1), null))
        assertNull(managedProcessingFraction(null, null))
        assertEquals(IndeterminateManagedProcessingIndicator, managedProcessingIndicatorKind(null))
        assertEquals(DeterminateManagedProcessingIndicator, managedProcessingIndicatorKind(0.5f))
        assertNull(managedProcessingBytes(null))
        assertNull(managedProcessingBytes(migration(stage = ManagedDownloadStorage.MigrationStage.PREPARING)))
        assertNull(
            managedProcessingBytes(
                migration(stage = ManagedDownloadStorage.MigrationStage.COPYING, totalBytes = 0)
            )
        )
    }

    private fun running(processed: Int?, total: Int?) = ManagedLibraryProcessingState.Running(
        operationId = "migration",
        reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
        phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
        processed = processed,
        total = total
    )

    private fun migration(
        stage: ManagedDownloadStorage.MigrationStage,
        copiedFiles: Int = 0,
        copiedBytes: Long = 0,
        totalBytes: Long = 0,
        verificationFilesProcessed: Int = 0,
        verificationFilesTotal: Int = 0,
        verifiedBytes: Long = 0,
        verificationBytesTotal: Long = 0
    ) = ManagedDownloadStorage.MigrationProgress(
        stage = stage,
        totalFiles = 10,
        processedFiles = copiedFiles,
        copiedFiles = copiedFiles,
        copiedBytes = copiedBytes,
        totalBytes = totalBytes,
        metadataFilesProcessed = 0,
        metadataFilesTotal = 0,
        cleanupFilesProcessed = 0,
        cleanupFilesTotal = 0,
        verificationFilesProcessed = verificationFilesProcessed,
        verificationFilesTotal = verificationFilesTotal,
        verifiedBytes = verifiedBytes,
        verificationBytesTotal = verificationBytesTotal
    )
}
