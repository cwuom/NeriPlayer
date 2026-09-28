package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.downloadDirectoryMigrationBytes
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.downloadDirectoryMigrationDialogPresentation
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.downloadDirectoryMigrationStageId
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.managedLibraryProcessingCardPresentation
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.managedLibraryProcessingCount
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.managedLibraryProcessingFraction
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.managedLibraryProcessingStageId
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.managedLibraryProcessingTitleId
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.migrationStageLabelId
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.visibleProcessingFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDownloadDirectoryPresentationTest {
    @Test
    fun `current file text ignores absent and blank names`() {
        assertNull(visibleProcessingFileName(null))
        assertNull(visibleProcessingFileName("  "))
        assertEquals("song.flac", visibleProcessingFileName("song.flac"))
    }

    @Test
    fun `processing title and stage follow the work reason and phase`() {
        assertEquals(
            R.string.managed_library_processing_upgrade_title,
            managedLibraryProcessingTitleId(ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE)
        )
        assertEquals(
            R.string.managed_library_processing_directory_title,
            managedLibraryProcessingTitleId(ManagedLibraryProcessingReason.DIRECTORY_CHANGE)
        )
        assertEquals(R.string.settings_download_directory_migrating,
            managedLibraryProcessingTitleId(null)
        )
        assertEquals(
            R.string.managed_library_processing_upgrade_title,
            managedLibraryProcessingStageId(null, ManagedLibraryProcessingPhase.UPGRADING_DATABASE)
        )
        assertEquals(
            R.string.settings_download_directory_preparing,
            managedLibraryProcessingStageId(null, ManagedLibraryProcessingPhase.REBUILDING_INDEX)
        )
        assertEquals(
            R.string.managed_library_processing_retry,
            managedLibraryProcessingStageId(null, ManagedLibraryProcessingPhase.WAITING_FOR_RETRY)
        )
        assertEquals(
            R.string.settings_download_directory_migrating_desc,
            managedLibraryProcessingStageId(null, null)
        )
    }

    @Test
    fun `migration stages retain their distinct labels`() {
        val labels = mapOf(
            ManagedDownloadStorage.MigrationStage.PREPARING to R.string.settings_download_directory_migrating_stage_preparing,
            ManagedDownloadStorage.MigrationStage.COPYING to R.string.settings_download_directory_migrating_stage_copying,
            ManagedDownloadStorage.MigrationStage.REWRITING_METADATA to R.string.settings_download_directory_migrating_stage_rewriting,
            ManagedDownloadStorage.MigrationStage.VERIFYING to R.string.settings_download_directory_migrating_stage_verifying,
            ManagedDownloadStorage.MigrationStage.CLEANING_UP to R.string.settings_download_directory_migrating_stage_cleanup,
            ManagedDownloadStorage.MigrationStage.FINALIZING to R.string.settings_download_directory_migrating
        )
        labels.forEach { (stage, label) ->
            assertEquals(label, migrationStageLabelId(stage))
            assertEquals(label, managedLibraryProcessingStageId(stage, null))
            assertEquals(label, downloadDirectoryMigrationStageId(stage))
        }
        assertEquals(
            R.string.settings_download_directory_migrating_desc,
            downloadDirectoryMigrationStageId(null)
        )
    }

    @Test
    fun `processing count prefers stage progress and falls back to shared state`() {
        val state = ManagedLibraryProcessingState.Running(
            operationId = "op",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
            processed = 3,
            total = 10
        )
        assertEquals(3 to 10, managedLibraryProcessingCount(state, null))
        assertEquals(5 to 10, managedLibraryProcessingCount(state, progress(copiedFiles = 5)))
        assertEquals(0.3f, managedLibraryProcessingFraction(null, 3 to 10))
        assertNull(managedLibraryProcessingCount(ManagedLibraryProcessingState.Idle, null))
        assertNull(managedLibraryProcessingFraction(null, null))
    }

    @Test
    fun `processing count uses overall progress when stage has no count and clamps stale shared counters`() {
        val state = ManagedLibraryProcessingState.Running(
            operationId = "op",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX,
            processed = -3,
            total = 8
        )
        val verifying = progress(
            stage = ManagedDownloadStorage.MigrationStage.VERIFYING,
            copiedFiles = 4
        )
        assertEquals(4 to 10, managedLibraryProcessingCount(state, verifying))
        assertEquals(0 to 8, managedLibraryProcessingCount(state, verifying.copy(totalFiles = 0)))
        assertNull(managedLibraryProcessingCount(state.copy(total = 0), null))
    }

    @Test
    fun `processing card presentation keeps running and retry semantics distinct`() {
        val running = ManagedLibraryProcessingState.Running(
            operationId = "op",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        val progress = progress(copiedFiles = 4).copy(currentFileName = "song.flac")
        val active = managedLibraryProcessingCardPresentation(running, progress)
        val retry = managedLibraryProcessingCardPresentation(
            ManagedLibraryProcessingState.WaitingForRetry(
                operationId = "op", reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE
            ),
            null
        )

        assertEquals(R.string.managed_library_processing_directory_title, active.titleId)
        assertEquals(R.string.settings_download_directory_migrating_stage_copying, active.stageId)
        assertEquals(4 to 10, active.count)
        assertEquals("song.flac", active.currentFileName)
        assertEquals(R.string.managed_library_processing_retry, retry.descriptionId)
        assertNull(retry.currentFileName)
    }

    @Test
    fun `byte labels use verification counters only during verification`() {
        val copying = downloadDirectoryMigrationBytes(progress(copiedBytes = 40, totalBytes = 100))
        assertEquals(R.string.settings_download_directory_migrating_progress_bytes, copying?.messageId)
        assertEquals(40L, copying?.processedBytes)
        assertEquals(100L, copying?.totalBytes)

        val verifying = downloadDirectoryMigrationBytes(
            progress(
                stage = ManagedDownloadStorage.MigrationStage.VERIFYING, verifiedBytes = 12,
                verificationBytesTotal = 20, totalBytes = 100
            )
        )
        assertEquals(R.string.settings_download_directory_migrating_verification_progress_bytes, verifying?.messageId)
        assertEquals(12L, verifying?.processedBytes)
        assertEquals(20L, verifying?.totalBytes)
        assertNull(downloadDirectoryMigrationBytes(progress()))
        assertNull(downloadDirectoryMigrationBytes(null))
        assertNull(
            downloadDirectoryMigrationBytes(
                progress(
                    stage = ManagedDownloadStorage.MigrationStage.VERIFYING
                )
            )
        )
        assertEquals(0L, downloadDirectoryMigrationBytes(
            progress(
                stage = ManagedDownloadStorage.MigrationStage.VERIFYING,
                verifiedBytes = -2,
                verificationBytesTotal = 20
            )
        )?.processedBytes)
    }

    @Test
    fun `migration dialog keeps an idle fallback and exposes bounded active progress`() {
        val idle = downloadDirectoryMigrationDialogPresentation(null)
        assertNull(idle.stage)
        assertEquals(0f, idle.fraction)
        assertNull(idle.currentFileName)

        val active = progress(copiedFiles = 5).copy(currentFileName = "song.flac")
        val presentation = downloadDirectoryMigrationDialogPresentation(active)
        assertEquals(ManagedDownloadStorage.MigrationStage.COPYING, presentation.stage)
        assertEquals(active.fraction, presentation.fraction)
        assertEquals("song.flac", presentation.currentFileName)
    }

    private fun progress(
        stage: ManagedDownloadStorage.MigrationStage = ManagedDownloadStorage.MigrationStage.COPYING,
        copiedFiles: Int = 0,
        copiedBytes: Long = 0,
        totalBytes: Long = 0,
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
        metadataFilesTotal = 10,
        cleanupFilesProcessed = 0,
        cleanupFilesTotal = 10,
        verifiedBytes = verifiedBytes,
        verificationBytesTotal = verificationBytesTotal
    )
}
