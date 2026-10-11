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
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.util.format.formatDurationSec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BiliVideoRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val video = BiliVideoItem(
        id = 31L,
        bvid = "BV1row",
        title = "Video Title",
        uploader = "Uploader",
        coverUrl = "https://example.invalid/bili.jpg",
        durationSec = 245
    )
    private val songItem = SongItem(
        id = 31L,
        name = "Video Title",
        artist = "Uploader",
        album = "bili",
        albumId = 0L,
        durationMs = 245_000L,
        coverUrl = video.coverUrl
    )
    private val events = mutableListOf<String>()

    @Test
    fun `browse mode row plays on click and runs each menu action once`() {
        val snackbarHostState = SnackbarHostState()
        var isFavorite by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                VideoRow(
                    index = 2,
                    video = video,
                    songItem = songItem,
                    isFavorite = isFavorite,
                    onFavoriteToggle = { item, favorite -> events += "favorite:${item.name}:$favorite" },
                    isCurrentSong = false,
                    animatePlayingIndicator = false,
                    selectionMode = false,
                    selected = false,
                    onToggleSelect = { events += "toggle" },
                    onLongPress = { events += "long" },
                    onClick = { events += "click" },
                    onPlayNext = { events += "next" },
                    onAddToQueueEnd = { events += "end" },
                    snackbarHostState = snackbarHostState,
                    offlineMode = false
                )
            }
        }

        composeRule.onNodeWithText("2").assertExists()
        composeRule.onNodeWithText("Uploader").assertExists()
        composeRule.onNodeWithText(formatDurationSec(245)).assertExists()
        composeRule.onNodeWithContentDescription("Video Title").assertExists()
        composeRule.onNodeWithText("Video Title").performClick()
        composeRule.onNodeWithText("Video Title").performTouchInput { longClick() }

        chooseMenuItem(CoreCommonR.string.local_playlist_play_next)
        chooseMenuItem(CoreCommonR.string.playlist_add_to_end)
        chooseMenuItem(CoreCommonR.string.favorite_add)
        isFavorite = true
        chooseMenuItem(CoreCommonR.string.favorite_remove)
        chooseMenuItem(CoreCommonR.string.action_copy_song_info)

        val copied = context.getString(CoreCommonR.string.toast_copied)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            snackbarHostState.currentSnackbarData?.visuals?.message == copied
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("Video Title-Uploader", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(
            listOf(
                "click",
                "long",
                "next",
                "end",
                "favorite:Video Title:false",
                "favorite:Video Title:true"
            ),
            events
        )
    }

    @Test
    fun `selection mode routes row clicks to toggle and the current video shows the playing indicator`() {
        composeRule.setContent {
            MaterialTheme {
                VideoRow(
                    index = 2,
                    video = video,
                    songItem = songItem,
                    isFavorite = false,
                    onFavoriteToggle = { _, _ -> events += "favorite" },
                    isCurrentSong = true,
                    animatePlayingIndicator = true,
                    selectionMode = true,
                    selected = true,
                    onToggleSelect = { events += "toggle" },
                    onLongPress = { events += "long" },
                    onClick = { events += "click" },
                    onPlayNext = { events += "next" },
                    onAddToQueueEnd = { events += "end" },
                    snackbarHostState = SnackbarHostState(),
                    offlineMode = true
                )
            }
        }

        composeRule.onNodeWithText("2").assertDoesNotExist()
        composeRule.onNodeWithText(formatDurationSec(245)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.common_more_actions))
            .assertDoesNotExist()
        composeRule.onNodeWithText("Video Title").performClick()
        composeRule.onNode(isToggleable()).assertIsOn().performClick()

        assertEquals(listOf("toggle", "toggle"), events)
    }

    private fun chooseMenuItem(labelRes: Int) {
        val label = context.getString(labelRes)
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.common_more_actions))
            .performClick()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(label).assertDoesNotExist()
    }
}
