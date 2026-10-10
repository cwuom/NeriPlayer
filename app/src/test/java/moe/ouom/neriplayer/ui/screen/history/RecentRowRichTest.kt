package moe.ouom.neriplayer.ui.screen.history

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.crash.ExceptionHandler
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.format.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RecentRowRichTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val remote = SongItem(
        id = 1L,
        name = "Remote Song",
        artist = "Remote Artist",
        album = "Remote Album",
        albumId = 5L,
        durationMs = 125_000L,
        coverUrl = "https://example.invalid/remote.jpg"
    )
    private val local = SongItem(
        id = 2L,
        name = "Tagged Title",
        artist = "",
        album = "",
        albumId = 0L,
        durationMs = 61_000L,
        coverUrl = null,
        localFilePath = "/music/track.flac",
        localFileName = "track.flac"
    )
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        LocalMediaHostAccess.bind(
            downloads = AndroidLocalMediaDownloads,
            covers = AndroidLocalMediaCovers,
            crashLogs = CrashLogCleanup(ExceptionHandler::clearCrashLogs)
        )
    }

    @Test
    fun `row switches between index playing indicator and checkbox and routes clicks by mode`() {
        var song by mutableStateOf(remote)
        var selectionMode by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var isCurrentSong by mutableStateOf(false)
        var isPlaying by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                RecentRowRich(
                    index = 3,
                    song = song,
                    downloadPresenceVersion = 0,
                    selectionMode = selectionMode,
                    selected = selected,
                    isCurrentSong = isCurrentSong,
                    isPlaying = isPlaying,
                    onToggleSelect = { events += "toggle" },
                    onLongPress = { events += "long" },
                    onClick = { events += "click" },
                    moreMenu = { Text("More actions") },
                    offlineMode = false
                )
            }
        }

        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithText("More actions").assertExists()
        composeRule.onNodeWithText("Remote Artist · ${formatDuration(125_000L)}").assertExists()
        composeRule.onNodeWithText("Remote Song").performClick()
        composeRule.onNodeWithText("Remote Song").performTouchInput { longClick() }

        isCurrentSong = true
        composeRule.onNodeWithText("3").assertDoesNotExist()
        isPlaying = true
        composeRule.onNodeWithText("Remote Song").assertExists()

        selectionMode = true
        composeRule.onNodeWithText("Remote Song").performClick()
        composeRule.onNode(isToggleable()).performClick()
        selected = true
        composeRule.onNode(isToggleable()).assertIsOn()

        song = local
        composeRule.onNodeWithText("track.flac").assertExists()
        composeRule.onNodeWithText("Tagged Title · ${formatDuration(61_000L)}").assertExists()

        assertEquals(listOf("click", "long", "toggle", "toggle"), events)
    }

    @Test
    fun `local rows prefer the file name and keep the tag title as subtitle`() {
        assertEquals("Remote Song", recentSongPrimaryTitle(remote))
        assertEquals("Remote Song", recentSongPrimaryTitle(remote.copy(localFileName = "ignored.mp3")))
        assertEquals("track.flac", recentSongPrimaryTitle(local))
        assertEquals("Tagged Title", recentSongPrimaryTitle(local.copy(localFileName = " ")))

        val duration = formatDuration(61_000L)
        assertEquals("Tagged Title · $duration", recentSongSecondaryText(local, "track.flac"))
        assertEquals(duration, recentSongSecondaryText(local, "Tagged Title"))
        assertEquals(
            "Local Artist · $duration",
            recentSongSecondaryText(local.copy(name = " ", artist = "Local Artist"), "track.flac")
        )
        assertEquals(
            "Remote Artist · ${formatDuration(125_000L)}",
            recentSongSecondaryText(remote, "Remote Song")
        )
    }
}
