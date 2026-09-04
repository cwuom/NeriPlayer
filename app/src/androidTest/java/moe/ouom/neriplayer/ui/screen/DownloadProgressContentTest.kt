package moe.ouom.neriplayer.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadProgressContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun waitingNetworkTaskKeepsItsNetworkStatusWhenProgressIsRetained() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Test song",
                artist = "Test artist",
                album = "Test album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = AudioDownloadManager.DownloadProgress(
                songKey = "test-song",
                songId = 1L,
                fileName = "test-song.mp3",
                bytesRead = 2L * 1024L * 1024L,
                totalBytes = 8L * 1024L * 1024L,
                speedBytesPerSec = 1024L * 1024L
            ),
            status = DownloadStatus.WAITING_NETWORK,
            attemptId = 1L
        )

        composeRule.setContent {
            MaterialTheme {
                DownloadProgressContent(task)
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.download_waiting_network_recovery)
        ).assertExists()
    }
}
