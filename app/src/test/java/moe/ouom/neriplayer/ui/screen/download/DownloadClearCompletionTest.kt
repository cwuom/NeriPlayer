package moe.ouom.neriplayer.ui.screen.download

import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearPhase
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadClearCompletionTest {

    private val finishedPurge = ClearProgress(
        phase = ClearPhase.PURGING,
        completedSteps = 4,
        totalSteps = 4,
        affectedItemCount = 3,
        completedItemCount = 3,
        totalItemCount = 3
    )

    @Test
    fun `missing progress or an earlier phase is never complete`() {
        assertFalse(isLogicalDownloadTaskClearComplete(null))
        assertFalse(isLogicalDownloadTaskClearComplete(finishedPurge.copy(phase = ClearPhase.CLEANING)))
    }

    @Test
    fun `purge is incomplete until every step has finished`() {
        assertFalse(isLogicalDownloadTaskClearComplete(finishedPurge.copy(completedSteps = 0, totalSteps = 0)))
        assertFalse(isLogicalDownloadTaskClearComplete(finishedPurge.copy(completedSteps = 3)))
    }

    @Test
    fun `purge with failed items is not complete`() {
        assertFalse(isLogicalDownloadTaskClearComplete(finishedPurge.copy(failedItemCount = 1)))
    }

    @Test
    fun `purge completes once every item is processed or none were tracked`() {
        assertTrue(isLogicalDownloadTaskClearComplete(finishedPurge))
        assertTrue(isLogicalDownloadTaskClearComplete(finishedPurge.copy(completedItemCount = 0, totalItemCount = 0)))
        assertTrue(isLogicalDownloadTaskClearComplete(finishedPurge.copy(completedItemCount = 0, totalItemCount = -2)))
        assertFalse(isLogicalDownloadTaskClearComplete(finishedPurge.copy(completedItemCount = 2)))
    }

    @Test
    fun `clearing without visible content shows the clearing page`() {
        assertEquals(
            DownloadProgressPagePresentation.CLEARING,
            presentation(isClearing = true, hasVisibleContent = false, hasKnownPendingTasks = true)
        )
    }

    @Test
    fun `visible content or known pending tasks show the content page`() {
        assertEquals(
            DownloadProgressPagePresentation.CONTENT,
            presentation(isClearing = true, hasVisibleContent = true)
        )
        assertEquals(
            DownloadProgressPagePresentation.CONTENT,
            presentation(hasKnownPendingTasks = true)
        )
    }

    @Test
    fun `finished clear presentation shows the empty page`() {
        assertEquals(
            DownloadProgressPagePresentation.EMPTY,
            presentation(isClearPresentationCleared = true)
        )
    }

    @Test
    fun `idle page follows the initial probe result`() {
        assertEquals(
            DownloadProgressPagePresentation.LOADING,
            presentation(initialProbeState = DownloadProgressInitialProbeState.LOADING)
        )
        assertEquals(
            DownloadProgressPagePresentation.EMPTY,
            presentation(initialProbeState = DownloadProgressInitialProbeState.RESOLVED)
        )
        assertEquals(
            DownloadProgressPagePresentation.UNAVAILABLE,
            presentation(initialProbeState = DownloadProgressInitialProbeState.UNAVAILABLE)
        )
    }

    private fun presentation(
        initialProbeState: DownloadProgressInitialProbeState = DownloadProgressInitialProbeState.RESOLVED,
        hasVisibleContent: Boolean = false,
        hasKnownPendingTasks: Boolean = false,
        isClearing: Boolean = false,
        isClearPresentationCleared: Boolean = false
    ) = resolveDownloadProgressPagePresentation(
        initialProbeState = initialProbeState,
        hasVisibleContent = hasVisibleContent,
        hasKnownPendingTasks = hasKnownPendingTasks,
        isClearing = isClearing,
        isClearPresentationCleared = isClearPresentationCleared
    )
}
