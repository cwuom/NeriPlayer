package moe.ouom.neriplayer.ui.screen

import java.io.File
import moe.ouom.neriplayer.core.download.DownloadClearVisibility
import moe.ouom.neriplayer.core.download.execution.WAITING_STORAGE_MUTATION_OPERATION_STATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressPagePresentationTest {

    @Test
    fun `clear progress bar animates between durable updates`() {
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/screen/DownloadProgressScreen.kt")
        val summary = source
            .substringAfter("private fun DownloadClearProgressSummary(")
            .substringBefore("@OptIn(ExperimentalMaterial3Api::class)")

        assertTrue(summary.contains("animateFloatAsState("))
        assertTrue(summary.contains("targetValue = progress.displayFraction"))
        assertTrue(summary.contains("progress = { animatedProgressFraction }"))
    }

    @Test
    fun `cleaning clear is not logically complete before provider cleanup`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            totalSteps = 4,
            affectedItemCount = 539,
            totalItemCount = 0
        )

        assertFalse(isLogicalDownloadTaskClearComplete(progress))
    }

    @Test
    fun `purging clear is complete only after every item is accounted for`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.PURGING,
            completedSteps = 4,
            totalSteps = 4,
            affectedItemCount = 539,
            completedItemCount = 12,
            totalItemCount = 12
        )

        assertTrue(isLogicalDownloadTaskClearComplete(progress))
    }

    @Test
    fun `purging clear with residual item is still visible`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.PURGING,
            completedSteps = 4,
            totalSteps = 4,
            affectedItemCount = 539,
            completedItemCount = 11,
            totalItemCount = 12
        )

        assertFalse(isLogicalDownloadTaskClearComplete(progress))
    }

    @Test
    fun `background cleanup keeps its progress card visible after task presentation clears`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            totalSteps = 4,
            affectedItemCount = 539
        )

        assertTrue(
            shouldShowDownloadClearProgressCard(
                clearFenceActive = true,
                progress = progress
            )
        )
        assertFalse(
            shouldShowDownloadClearProgressCard(
                clearFenceActive = false,
                progress = progress
            )
        )
    }

    @Test
    fun `clear presentation keeps the pre-clear task count after task cards are hidden`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.PREPARING,
            completedSteps = 0,
            totalSteps = 4,
            affectedItemCount = 0
        )

        val presented = requireNotNull(
            resolveDownloadClearPresentationProgress(
                progress = progress,
                taskCountHint = 846
            )
        )

        assertEquals(846, presented.affectedItemCount)
        assertEquals(0, presented.completedSteps)
    }

    @Test
    fun `clear presentation never lowers a durable affected item count`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            totalSteps = 4,
            affectedItemCount = 900
        )

        val presented = requireNotNull(
            resolveDownloadClearPresentationProgress(
                progress = progress,
                taskCountHint = 846
            )
        )

        assertEquals(900, presented.affectedItemCount)
    }

    @Test
    fun `active clear fence uses a known task count when progress restore is missing`() {
        val progress = resolveDownloadClearProgressOrFallback(
            progress = null,
            clearFenceActive = true,
            fallbackItemCount = 846
        )

        assertEquals(0, progress?.displayPercentage)
        assertEquals(846, progress?.affectedItemCount)
        assertEquals(0, progress?.totalItemCount)
    }

    @Test
    fun `restored clear progress keeps its item denominator when new tasks are visible`() {
        val progress = resolveDownloadClearProgressOrFallback(
            progress = DownloadClearVisibility.ClearProgress(
                phase = DownloadClearVisibility.ClearPhase.CLEANING,
                completedSteps = 2,
                totalSteps = 4,
                affectedItemCount = 846,
                completedItemCount = 4,
                totalItemCount = 12
            ),
            clearFenceActive = true,
            fallbackItemCount = 3
        )

        assertEquals(12, progress?.totalItemCount)
        assertEquals(4, progress?.completedItemCount)
    }

    @Test
    fun `new generation tasks take priority over old background cleanup`() {
        assertFalse(
            shouldPrioritizeDownloadBackgroundCleanup(
                logicalClearComplete = true,
                hasVisibleTasks = true,
                pendingTaskCount = 1
            )
        )
        assertTrue(
            shouldPrioritizeDownloadBackgroundCleanup(
                logicalClearComplete = true,
                hasVisibleTasks = false,
                pendingTaskCount = 0
            )
        )
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
    fun `active clear still renders a new generation task`() {
        assertEquals(
            DownloadProgressPagePresentation.CONTENT,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                hasVisibleContent = true,
                hasKnownPendingTasks = false,
                isClearing = true,
                isClearPresentationCleared = true
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
                hasUnhydratedDurableTasks = false,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
        assertEquals(
            true,
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                clearFenceActive = true,
                hasUnhydratedDurableTasks = false,
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
                clearFenceActive = false,
                hasUnhydratedDurableTasks = false,
                isClearing = false,
                isClearPresentationCleared = true
            )
        )
    }

    @Test
    fun `completed clear keeps polling while durable fence remains active`() {
        val progress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.PURGING,
            completedSteps = 4,
            totalSteps = 4,
            affectedItemCount = 0,
            completedItemCount = 0,
            totalItemCount = 0
        )

        assertTrue(isLogicalDownloadTaskClearComplete(progress))
        assertFalse(
            isEffectiveDownloadClearInProgress(
                clearFenceActive = true,
                progress = progress
            )
        )
        assertTrue(
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                clearFenceActive = true,
                hasUnhydratedDurableTasks = false,
                isClearing = false,
                isClearPresentationCleared = true
            )
        )
        assertFalse(
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                clearFenceActive = false,
                hasUnhydratedDurableTasks = false,
                isClearing = false,
                isClearPresentationCleared = true
            )
        )
        assertEquals(
            DownloadProgressPagePresentation.EMPTY,
            resolveDownloadProgressPagePresentation(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                hasVisibleContent = false,
                hasKnownPendingTasks = false,
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
                hasUnhydratedDurableTasks = false,
                isClearing = true,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `durable fallback is retried after its in-memory task row settles`() {
        assertTrue(
            hasUnhydratedDurableDownloadTasks(
                activeSongKeys = emptySet(),
                durablePendingSongKeys = setOf("song"),
                explicitResumeSongKeys = emptySet()
            )
        )
        assertFalse(
            hasUnhydratedDurableDownloadTasks(
                activeSongKeys = setOf("song"),
                durablePendingSongKeys = setOf("song"),
                explicitResumeSongKeys = emptySet()
            )
        )
        assertFalse(
            hasUnhydratedDurableDownloadTasks(
                activeSongKeys = emptySet(),
                durablePendingSongKeys = setOf("song"),
                explicitResumeSongKeys = setOf("song")
            )
        )
        assertTrue(
            shouldRecheckDownloadProgressBootstrap(
                initialProbeState = DownloadProgressInitialProbeState.RESOLVED,
                clearFenceActive = false,
                hasUnhydratedDurableTasks = true,
                isClearing = false,
                isClearPresentationCleared = false
            )
        )
    }

    @Test
    fun `download page excludes post core operation states from pending count`() {
        listOf(
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        ).forEach { state ->
            assertFalse(state in DOWNLOAD_PROGRESS_DURABLE_PENDING_OPERATION_STATES)
        }
    }

    @Test
    fun `download page keeps transferable operation states in pending count`() {
        listOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE",
            "RUNNING",
            "COMMITTING",
            WAITING_STORAGE_MUTATION_OPERATION_STATE
        ).forEach { state ->
            assertTrue(state in DOWNLOAD_PROGRESS_DURABLE_PENDING_OPERATION_STATES)
        }
    }

    private fun source(path: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
