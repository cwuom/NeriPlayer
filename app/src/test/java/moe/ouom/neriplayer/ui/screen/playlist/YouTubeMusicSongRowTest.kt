package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
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
class YouTubeMusicSongRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val song = SongItem(
        id = 7L,
        name = "Track",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 185_000L,
        coverUrl = "https://example.invalid/yt.jpg"
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
    fun `menu actions run once each and close the menu`() {
        val snackbarHostState = SnackbarHostState()
        var isFavorite by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                YouTubeMusicSongRow(
                    index = 4,
                    song = song,
                    isCurrentSong = false,
                    animatePlayingIndicator = false,
                    isFavorite = isFavorite,
                    onFavoriteToggle = { item, favorite -> events += "favorite:${item.name}:$favorite" },
                    snackbarHostState = snackbarHostState,
                    selectionMode = false,
                    selected = false,
                    onToggleSelect = { events += "toggle" },
                    onLongPress = { events += "long" },
                    onClick = { events += "click" },
                    onPlayNext = { events += "next" },
                    onAddToQueueEnd = { events += "end" },
                    onDownload = { events += "download" },
                    offlineMode = false
                )
            }
        }

        composeRule.onNodeWithText("4").assertExists()
        composeRule.onNodeWithText("Artist · Album").assertExists()
        composeRule.onNodeWithText(formatDuration(185_000L)).assertExists()
        composeRule.onNodeWithContentDescription("Track").assertExists()
        composeRule.onNodeWithText("Track").performClick()
        composeRule.onNodeWithText("Track").performTouchInput { longClick() }

        chooseMenuItem(CoreCommonR.string.local_playlist_play_next)
        chooseMenuItem(CoreCommonR.string.playlist_add_to_end)
        chooseMenuItem(CoreCommonR.string.favorite_add)
        isFavorite = true
        chooseMenuItem(CoreCommonR.string.favorite_remove)
        chooseMenuItem(CoreCommonR.string.download_to_local)
        chooseMenuItem(CoreCommonR.string.action_copy_song_info)

        val copied = context.getString(CoreCommonR.string.toast_copied)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            snackbarHostState.currentSnackbarData?.visuals?.message == copied
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("Track-Artist", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(
            listOf(
                "click",
                "long",
                "next",
                "end",
                "favorite:Track:false",
                "favorite:Track:true",
                "download"
            ),
            events
        )
    }

    @Test
    fun `selection mode shows a checkbox and the current song shows the playing indicator`() {
        composeRule.setContent {
            MaterialTheme {
                YouTubeMusicSongRow(
                    index = 4,
                    song = song.copy(id = 8L, coverUrl = null, artist = "", album = "Album"),
                    isCurrentSong = true,
                    animatePlayingIndicator = true,
                    isFavorite = false,
                    onFavoriteToggle = { _, _ -> events += "favorite" },
                    snackbarHostState = SnackbarHostState(),
                    selectionMode = true,
                    selected = true,
                    onToggleSelect = { events += "toggle" },
                    onLongPress = { events += "long" },
                    onClick = { events += "click" },
                    onPlayNext = { events += "next" },
                    onAddToQueueEnd = { events += "end" },
                    onDownload = { events += "download" },
                    offlineMode = true
                )
            }
        }

        composeRule.onNodeWithText("4").assertDoesNotExist()
        composeRule.onNodeWithText(formatDuration(185_000L)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Track").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.common_more_actions))
            .assertDoesNotExist()
        composeRule.onNodeWithText("Album").assertExists()
        composeRule.onNode(isToggleable()).assertIsOn().performClick()
        composeRule.onNodeWithText("Track").performClick()

        assertEquals(listOf("toggle", "click"), events)
    }

    @Test
    fun `subtitle joins only non blank artist and album`() {
        assertEquals("Artist · Album", youTubeMusicSongSubtitle("Artist", "Album"))
        assertEquals("Artist", youTubeMusicSongSubtitle("Artist", " "))
        assertEquals("Album", youTubeMusicSongSubtitle("", "Album"))
        assertEquals("", youTubeMusicSongSubtitle(" ", ""))
    }

    private fun chooseMenuItem(labelRes: Int) {
        val label = context.getString(labelRes)
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.common_more_actions))
            .performClick()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(label).assertDoesNotExist()
    }
}
