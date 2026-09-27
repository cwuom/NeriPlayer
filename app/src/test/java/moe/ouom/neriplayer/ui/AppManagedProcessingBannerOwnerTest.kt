package moe.ouom.neriplayer.ui

import androidx.compose.runtime.mutableStateOf
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManagedProcessingBannerOwnerTest {
    @Test
    fun exitKeepsLastContentForAnimationWithoutKeepingBannerInteractive() {
        val owner = AppManagedProcessingBannerOwner(mutableStateOf(false))
        val active = ManagedLibraryProcessingState.Running(
            operationId = "migration-1",
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )
        val progress = ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.COPYING,
            totalFiles = 4,
            processedFiles = 2,
            copiedFiles = 2,
            copiedBytes = 512L,
            totalBytes = 1024L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0
        )

        owner.observe(active, progress)
        assertTrue(owner.presentation(active, progress).active)
        owner.observe(ManagedLibraryProcessingState.Idle, null)
        val exiting = owner.presentation(ManagedLibraryProcessingState.Idle, null)
        assertFalse(exiting.active)
        assertFalse(exiting.visible)
        assertFalse(exiting.revealGestureEnabled)
        assertSame(active, exiting.state)
        assertSame(progress, exiting.progress)
    }

    @Test
    fun newOperationReopensCollapsedBanner() {
        val owner = AppManagedProcessingBannerOwner(mutableStateOf(false))
        owner.collapse(true)
        assertTrue(owner.presentation(ManagedLibraryProcessingState.Idle, null).collapsed)
        assertFalse(owner.presentation(ManagedLibraryProcessingState.Idle, null).revealGestureEnabled)

        val next = ManagedLibraryProcessingState.Running(
            operationId = "migration-2",
            reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
            phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE
        )
        owner.onOperationChanged(next)
        assertFalse(owner.presentation(next, null).collapsed)
        owner.collapse(true)
        assertTrue(owner.presentation(next, null).revealGestureEnabled)
        owner.expand()
        assertEquals(false, owner.presentation(next, null).collapsed)
        assertTrue(owner.presentation(next, null).visible)
    }
}
