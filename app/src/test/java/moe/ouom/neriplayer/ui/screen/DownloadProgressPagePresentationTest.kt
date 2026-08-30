package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.core.download.DownloadClearVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressPagePresentationTest {

    @Test
    fun `detached clear is considered logically complete before provider cleanup`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            totalSteps = 4,
            affectedItemCount = 539,
            totalItemCount = 0
        )

        assertTrue(isLogicalDownloadTaskClearComplete(progress))
    }

    @Test
    fun `clear with unresolved artifacts remains visible until cleanup settles`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            totalSteps = 4,
            affectedItemCount = 539,
            failedItemCount = 1,
            totalItemCount = 1
        )

        assertFalse(isLogicalDownloadTaskClearComplete(progress))
    }

    @Test
    fun `cold task store keeps progress page loading instead of showing empty`() {
        assertEquals(
            DownloadProgressPagePresentation.LOADING,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.LOADING,
                hasVisibleContent = false,
                hasKnownPendingTasks = false,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `durable pending work renders a summary before task hydration`() {
        assertEquals(
            DownloadProgressPagePresentation.CONTENT,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                hasVisibleContent = false,
                hasKnownPendingTasks = true,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `confirmed empty probe renders the normal empty state`() {
        assertEquals(
            DownloadProgressPagePresentation.EMPTY,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                hasVisibleContent = false,
                hasKnownPendingTasks = false,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `active clear keeps determinate progress over a stale durable snapshot`() {
        assertEquals(
            DownloadProgressPagePresentation.CLEARING,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                hasVisibleContent = false,
                hasKnownPendingTasks = true,
                isClearing = true,
                isClearPresentationCleared = true
            )
        )
    }

    @Test
    fun `active clear fence renders clearing instead of loading indefinitely`() {
        assertEquals(
            DownloadProgressPagePresentation.CLEARING,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.LOADING,
                hasVisibleContent = false,
                hasKnownPendingTasks = false,
                isClearing = true,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `unavailable storage probe never falls through to no tasks`() {
        assertEquals(
            DownloadProgressPagePresentation.UNAVAILABLE,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.UNAVAILABLE,
                hasVisibleContent = false,
                hasKnownPendingTasks = false,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `unavailable probe and active fence both schedule a recheck`() {
        assertEquals(
            true,
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.UNAVAILABLE,
                clearFenceActive = false,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
        assertEquals(
            true,
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                clearFenceActive = true,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `cleared presentation stops bootstrap retries`() {
        assertEquals(
            false,
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.UNAVAILABLE,
                clearFenceActive = true,
                isClearing = false,
                isClearPresentationCleared = true
            )
        )
    }

    @Test
    fun `in-memory clear stops duplicate bootstrap retries`() {
        assertEquals(
            false,
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.UNAVAILABLE,
                clearFenceActive = true,
                isClearing = true,
                isClearPresentationCleared = false
            )
        )
    }
}
