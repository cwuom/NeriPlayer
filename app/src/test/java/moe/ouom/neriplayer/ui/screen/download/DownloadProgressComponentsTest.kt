package moe.ouom.neriplayer.ui.screen.download

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearPhase
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearProgress
import moe.ouom.neriplayer.core.download.presentation.formatDownloadTransferProgress
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadFailureReason
import moe.ouom.neriplayer.data.model.download.DownloadProgress
import moe.ouom.neriplayer.data.model.download.DownloadStage
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
class DownloadProgressComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val song = SongItem(
        id = 7L,
        name = "Queued Song",
        artist = "Queue Artist",
        album = "",
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null
    )

    private val events = mutableListOf<String>()
    private var shownTask by mutableStateOf(task(DownloadStatus.QUEUED))
    private var actionsEnabled by mutableStateOf(true)

    @Test
    fun `pending tasks follow their stage and offer resume only while waiting to retry`() {
        setTaskItem()

        composeRule.onNodeWithText("Queued Song").assertExists()
        composeRule.onNodeWithText("Queue Artist").assertExists()
        assertText(CoreCommonR.string.download_queued_status)
        assertNoAction(CoreCommonR.string.download_resume)
        action(CoreCommonR.string.download_cancel_download).performClick()

        shownTask = task(DownloadStatus.QUEUED, progress(DownloadStage.RESOLVING_SOURCE))
        assertText(CoreCommonR.string.download_resolving_source)

        val cleanup = progress(DownloadStage.WAITING_DELETE_CLEANUP)
        shownTask = task(DownloadStatus.WAITING_NETWORK, cleanup)
        assertText(CoreCommonR.string.download_waiting_delete_cleanup)
        composeRule.onNodeWithText(formatDownloadTransferProgress(cleanup, showSpeed = false)).assertExists()
        action(CoreCommonR.string.download_resume).performClick()

        shownTask = task(DownloadStatus.WAITING_NETWORK, progress(DownloadStage.RESOLVING_SOURCE))
        assertText(CoreCommonR.string.download_waiting_network_recovery)

        shownTask = task(DownloadStatus.WAITING_NETWORK)
        assertText(CoreCommonR.string.download_waiting_network_recovery)
        composeRule.onNodeWithText(formatDownloadTransferProgress(cleanup, showSpeed = false)).assertDoesNotExist()

        assertEquals(listOf("cancel:QUEUED", "resume:WAITING_NETWORK"), events)
    }

    @Test
    fun `downloading tasks show host waits retries finalizing stages and transfer progress`() {
        shownTask = task(DownloadStatus.DOWNLOADING)
        setTaskItem()

        assertText(CoreCommonR.string.download_waiting_host)
        assertNoAction(CoreCommonR.string.download_resume)

        val retry = progress(DownloadStage.WAITING_RETRY)
        shownTask = task(DownloadStatus.DOWNLOADING, retry)
        assertText(CoreCommonR.string.download_waiting_retry)
        composeRule.onNodeWithText(formatDownloadTransferProgress(retry, showSpeed = false)).assertExists()
        action(CoreCommonR.string.download_resume).performClick()

        shownTask = task(DownloadStatus.DOWNLOADING, progress(DownloadStage.FINALIZING))
        assertText(CoreCommonR.string.download_finalizing)
        action(CoreCommonR.string.download_cancel_download).performClick()

        val verifying = progress(DownloadStage.VERIFYING_AUDIO)
        shownTask = task(DownloadStatus.DOWNLOADING, verifying)
        assertText(CoreCommonR.string.download_verifying_audio)
        composeRule.onNodeWithText(formatDownloadTransferProgress(verifying, showSpeed = false)).assertExists()

        val transferring = progress(DownloadStage.TRANSFERRING)
        shownTask = task(DownloadStatus.DOWNLOADING, transferring)
        composeRule.onNodeWithText(formatDownloadTransferProgress(transferring)).assertExists()

        val unknownLength = progress(DownloadStage.TRANSFERRING, totalBytes = 0L)
        shownTask = task(DownloadStatus.DOWNLOADING, unknownLength)
        composeRule.onNodeWithText(formatDownloadTransferProgress(unknownLength)).assertExists()

        actionsEnabled = false
        action(CoreCommonR.string.download_finalizing).assertIsNotEnabled()
        assertNoAction(CoreCommonR.string.download_cancel_download)

        assertEquals(listOf("resume:DOWNLOADING", "cancel:DOWNLOADING"), events)
    }

    @Test
    fun `finished tasks show their outcome and offer download again only after failure or cancel`() {
        shownTask = task(DownloadStatus.COMPLETED)
        setTaskItem()

        assertText(CoreCommonR.string.download_completed)
        assertNoAction(CoreCommonR.string.download_to_local)
        assertNoAction(CoreCommonR.string.download_cancel_download)

        shownTask = task(DownloadStatus.FAILED, failureReason = DownloadFailureReason.PREVIEW_ONLY)
        assertText(CoreCommonR.string.download_failed_preview_only)
        action(CoreCommonR.string.download_to_local).performClick()

        shownTask = task(DownloadStatus.FAILED)
        assertText(CoreCommonR.string.download_failed)

        shownTask = task(DownloadStatus.CANCELLED)
        assertText(CoreCommonR.string.download_cancelled_status)
        action(CoreCommonR.string.download_to_local).performClick()

        actionsEnabled = false
        action(CoreCommonR.string.download_to_local).assertIsNotEnabled()

        assertEquals(listOf("resume:FAILED", "resume:CANCELLED"), events)
    }

    @Test
    fun `empty content shows clear progress only while clearing or cleaning in background`() {
        var isClearing by mutableStateOf(false)
        var clearProgress by mutableStateOf<ClearProgress?>(null)
        var backgroundCleanup by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                DownloadProgressEmptyContent(
                    isClearing = isClearing,
                    clearProgress = clearProgress,
                    showBackgroundCleanup = backgroundCleanup
                )
            }
        }

        assertText(CoreCommonR.string.download_no_tasks)
        isClearing = true
        assertText(CoreCommonR.string.download_clearing_tasks)

        val cancelling = ClearProgress(
            phase = ClearPhase.CANCELLING,
            completedSteps = 1,
            totalSteps = 4,
            affectedItemCount = 3,
            completedItemCount = 1,
            totalItemCount = 3
        )
        clearProgress = cancelling
        assertText(CoreCommonR.string.download_clearing_tasks)
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                CoreCommonR.plurals.download_clearing_tasks_with_progress,
                3,
                cancelling.displayPercentage,
                3
            ) + " · " + string(CoreCommonR.string.download_clear_phase_cancelling)
        ).assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.download_clear_stage_progress, 1, 4))
            .assertExists()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(CoreCommonR.plurals.download_clear_item_progress, 3, 1, 3)
        ).assertExists()

        isClearing = false
        assertText(CoreCommonR.string.download_no_tasks)

        backgroundCleanup = true
        clearProgress = ClearProgress(phase = ClearPhase.CLEANING, completedSteps = 0, totalSteps = 2, affectedItemCount = 0)
        assertText(CoreCommonR.string.download_clear_background_cleanup)
        assertText(CoreCommonR.string.download_clear_item_progress_scanning)

        clearProgress = ClearProgress(phase = ClearPhase.PURGING, completedSteps = 2, totalSteps = 2, affectedItemCount = 0)
        assertText(CoreCommonR.string.download_clear_item_progress_empty)
    }

    @Test
    fun `status and clear helpers map every state to its label`() {
        assertEquals(CoreCommonR.string.download_queued_status, downloadTaskStatusLabelRes(task(DownloadStatus.QUEUED)))
        assertEquals(
            CoreCommonR.string.download_queued_status,
            downloadTaskStatusLabelRes(task(DownloadStatus.QUEUED, progress(DownloadStage.TRANSFERRING)))
        )
        assertEquals(
            CoreCommonR.string.download_waiting_network_recovery,
            downloadTaskStatusLabelRes(task(DownloadStatus.WAITING_NETWORK, progress(DownloadStage.WAITING_RETRY)))
        )
        assertNull(downloadTaskStatusLabelRes(task(DownloadStatus.DOWNLOADING, progress(DownloadStage.TRANSFERRING))))
        assertEquals(
            CoreCommonR.string.download_failed_source_unavailable,
            downloadTaskStatusLabelRes(task(DownloadStatus.FAILED, failureReason = DownloadFailureReason.SOURCE_UNAVAILABLE))
        )

        assertTrue(isDownloadTaskWaitingToRetry(task(DownloadStatus.WAITING_NETWORK)))
        assertTrue(isDownloadTaskWaitingToRetry(task(DownloadStatus.DOWNLOADING, progress(DownloadStage.WAITING_RETRY))))
        assertFalse(isDownloadTaskWaitingToRetry(task(DownloadStatus.DOWNLOADING)))
        assertFalse(isDownloadTaskWaitingToRetry(task(DownloadStatus.QUEUED, progress(DownloadStage.TRANSFERRING))))

        assertEquals(
            listOf(
                CoreCommonR.string.download_clear_phase_preparing,
                CoreCommonR.string.download_clear_phase_cancelling,
                CoreCommonR.string.download_clear_phase_cleaning,
                CoreCommonR.string.download_clear_phase_purging
            ),
            ClearPhase.entries.map(::downloadClearPhaseLabelRes)
        )
        assertEquals(
            CoreCommonR.string.download_clear_item_progress_pending,
            downloadClearUncountedItemProgressRes(ClearProgress(ClearPhase.PURGING, 1, 2, 0))
        )
        assertEquals(
            CoreCommonR.string.download_clear_item_progress_pending,
            downloadClearUncountedItemProgressRes(ClearProgress(ClearPhase.PREPARING, 0, 2, 0))
        )
    }

    private fun setTaskItem() {
        composeRule.setContent {
            MaterialTheme {
                DownloadTaskItem(
                    task = shownTask,
                    onCancel = { events += "cancel:${shownTask.status}" },
                    onResume = { events += "resume:${shownTask.status}" },
                    actionsEnabled = actionsEnabled
                )
            }
        }
    }

    private fun task(
        status: DownloadStatus,
        progress: DownloadProgress? = null,
        failureReason: DownloadFailureReason? = null
    ) = DownloadTask(song = song, progress = progress, status = status, failureReason = failureReason)

    private fun progress(stage: DownloadStage, totalBytes: Long = 1024L * 1024L) = DownloadProgress(
        songKey = "song-7",
        songId = 7L,
        fileName = "song-7.flac",
        bytesRead = 512L * 1024L,
        totalBytes = totalBytes,
        speedBytesPerSec = 2048L,
        stage = stage
    )

    private fun action(id: Int) = composeRule.onNodeWithContentDescription(string(id))

    private fun assertNoAction(id: Int) {
        composeRule.onNodeWithContentDescription(string(id)).assertDoesNotExist()
    }

    private fun assertText(id: Int) {
        composeRule.onNodeWithText(string(id)).assertExists()
    }

    private fun string(id: Int): String = context.getString(id)
}
