package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadProgressWaitingTaskTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lastTaskRetainsSongRowAndActionsThroughoutNetworkAndHostWaits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val track = SongItem(
            id = 99182741L, name = "waiting-task-regression", artist = "test",
            album = "netease", albumId = 0, durationMs = 1000, coverUrl = null
        )
        val store = GlobalDownloadManager.taskStore
        val attempt = store.ensureDownloadTasks(listOf(track)).getValue(track.stableKey())
        try {
            composeRule.setContent {
                MaterialTheme {
                    DownloadProgressScreen(onBack = {}, listState = rememberLazyListState())
                }
            }
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                composeRule.onAllNodesWithText(track.name).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(track.name).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(context.getString(R.string.download_cancel_download))
                .assertIsDisplayed()
            for (stage in listOf(
                AudioDownloadManager.DownloadStage.WAITING_HOST,
                AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP,
                AudioDownloadManager.DownloadStage.WAITING_RETRY
            )) {
                composeRule.runOnIdle {
                    store.restoreProgress(AudioDownloadManager.DownloadProgress(
                        songKey = track.stableKey(), songId = track.id, fileName = "test.mp3",
                        bytesRead = 0L, totalBytes = 0L, speedBytesPerSec = 0L,
                        stage = stage, attemptId = attempt
                    ))
                }
                composeRule.onNodeWithText(track.name).assertIsDisplayed()
            }
            composeRule.runOnIdle { store.applyWaitingNetworkStatus(store.currentTasks()) }
            composeRule.onNodeWithText(track.name).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(context.getString(R.string.download_resume))
                .assertIsDisplayed()
            composeRule.runOnIdle {
                store.updateTaskStatus(track.stableKey(), DownloadStatus.QUEUED, attempt)
            }
            composeRule.onNodeWithText(track.name).assertIsDisplayed()
        } finally {
            store.removeDownloadTask(track.stableKey(), attempt)
        }
    }
}
