package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
class NeteaseCollectionSongRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun `browse mode row plays on click and runs each menu action once`() {
        val snackbarHostState = SnackbarHostState()
        var isFavorite by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                SongRow(
                    index = 4,
                    song = song(id = 21L, coverUrl = "https://example.invalid/netease.jpg"),
                    isCurrentSong = false,
                    animatePlayingIndicator = false,
                    isFavorite = isFavorite,
                    onFavoriteToggle = { item, favorite -> events += "favorite:${item.name}:$favorite" },
                    showCover = true,
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
        chooseMenuItem(CoreCommonR.string.action_copy_song_info)

        val copied = context.getString(CoreCommonR.string.toast_copied)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            snackbarHostState.currentSnackbarData?.visuals?.message == copied
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("Track-Artist", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(
            listOf("click", "long", "next", "end", "favorite:Track:false", "favorite:Track:true"),
            events
        )
    }

    @Test
    fun `selection mode routes row clicks to toggle and hides cover index duration and menu`() {
        composeRule.setContent {
            MaterialTheme {
                SongRow(
                    index = 4,
                    song = song(id = 22L, coverUrl = "https://example.invalid/hidden.jpg"),
                    isCurrentSong = true,
                    animatePlayingIndicator = true,
                    isFavorite = false,
                    onFavoriteToggle = { _, _ -> events += "favorite" },
                    showCover = false,
                    selectionMode = true,
                    selected = false,
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

        composeRule.onNodeWithText("4").assertDoesNotExist()
        composeRule.onNodeWithText(formatDuration(185_000L)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Track").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions))
            .assertDoesNotExist()
        composeRule.onNodeWithText("Track").performClick()
        composeRule.onNode(isToggleable()).assertIsOff().performClick()

        assertEquals(listOf("toggle", "toggle"), events)
    }

    @Test
    fun `enabled cover slot appears only once the song has a non blank cover url`() {
        var current by mutableStateOf(song(id = 23L, coverUrl = null))
        composeRule.setContent {
            MaterialTheme {
                SongRow(
                    index = 1,
                    song = current,
                    isCurrentSong = false,
                    animatePlayingIndicator = false,
                    isFavorite = false,
                    onFavoriteToggle = { _, _ -> },
                    showCover = true,
                    selectionMode = false,
                    selected = false,
                    onToggleSelect = {},
                    onLongPress = {},
                    onClick = {},
                    onPlayNext = {},
                    onAddToQueueEnd = {},
                    snackbarHostState = SnackbarHostState(),
                    offlineMode = false
                )
            }
        }

        composeRule.onNodeWithContentDescription("Track").assertDoesNotExist()
        current = song(id = 24L, coverUrl = " ")
        composeRule.onNodeWithContentDescription("Track").assertDoesNotExist()
        current = song(id = 25L, coverUrl = "https://example.invalid/late.jpg")
        composeRule.onNodeWithContentDescription("Track").assertExists()
    }

    @Test
    fun `subtitle strips the netease tag and always keeps the album slot`() {
        assertEquals("Artist · Album", neteaseSongSubtitle("Artist", "Album"))
        assertEquals("Artist · Cloud", neteaseSongSubtitle("Artist", "NeteaseCloud"))
        assertEquals("Artist · ", neteaseSongSubtitle("Artist", " "))
        assertEquals("Album", neteaseSongSubtitle(" ", "Album"))
        assertEquals("", neteaseSongSubtitle("", ""))
    }

    @Test
    fun `playing indicator keeps three bars in a twelve dp lane with and without animation`() {
        var animate by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    PlayingIndicator(
                        modifier = Modifier.testTag("explicit"),
                        color = Color.Red,
                        animate = animate
                    )
                    Box(Modifier.testTag("default")) {
                        PlayingIndicator()
                    }
                }
            }
        }

        composeRule.onNodeWithTag("explicit").assertWidthIsEqualTo(11.dp).assertHeightIsEqualTo(12.dp)
        composeRule.onNodeWithTag("default").assertWidthIsEqualTo(11.dp).assertHeightIsEqualTo(12.dp)
        animate = false
        composeRule.onNodeWithTag("explicit").assertWidthIsEqualTo(11.dp).assertHeightIsEqualTo(12.dp)
    }

    private fun song(id: Long, coverUrl: String?) = SongItem(
        id = id,
        name = "Track",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 185_000L,
        coverUrl = coverUrl
    )

    private fun chooseMenuItem(labelRes: Int) {
        val label = context.getString(labelRes)
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions))
            .performClick()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(label).assertDoesNotExist()
    }
}
