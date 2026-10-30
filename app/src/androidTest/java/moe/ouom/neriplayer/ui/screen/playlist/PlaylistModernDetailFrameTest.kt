package moe.ouom.neriplayer.ui.screen.playlist

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailFrame
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailHeader
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailSearch
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernSongRow
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.common.R as CoreCommonR

@RunWith(AndroidJUnit4::class)
class PlaylistModernDetailFrameTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun cleanFrameKeepsLocalAndServerRowGeometryAndSelection() = checkFrame(UiStyle.Clean)

    @Test fun materialFrameKeepsLocalAndServerRowGeometryAndSelection() = checkFrame(UiStyle.Material)

    private fun checkFrame(style: UiStyle) {
        val useServerSong = mutableStateOf(false)
        val local = SongItem(1L, SONG, "Artist", "Album", 0L, 60_000L, null)
        val ref = ServerSongRef("00000000-0000-0000-0000-000000000001", "same/song")
        val server = local.copy(
            id = ref.numericId, channelId = ServerSongRef.CHANNEL,
            audioId = ref.audioId, mediaUri = ref.mediaUri
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                NeriTheme(
                    followSystemDark = false, forceDark = false, dynamicColor = false,
                    seedColorHex = "885522", uiStyle = style, animateColors = false
                ) {
                    var showSearch by remember { mutableStateOf(false) }
                    var query by remember { mutableStateOf("") }
                    var selected by remember { mutableStateOf(false) }
                    var headerFocus by remember { mutableStateOf(false) }
                    var dockedFocus by remember { mutableStateOf(false) }
                    val input = rememberPlaylistSearchInputState(query, { query = it })
                    val focus = remember { FocusRequester() }
                    Box(Modifier.width(340.dp).fillMaxSize()) {
                        PlaylistModernDetailFrame(
                            header = PlaylistModernDetailHeader("album", "header", "Album", null, "2 min · 1", true, false),
                            search = PlaylistModernDetailSearch(
                                showSearch, { showSearch = it }, query, { query = it }, input, focus,
                                false, headerFocus, { headerFocus = it }, dockedFocus, { dockedFocus = it }
                            ),
                            listState = rememberLazyListState(), snackbarHostState = remember { SnackbarHostState() },
                            onBack = {}, selectionCount = if (selected) 1 else null,
                            onExitSelection = { selected = false }, onLocatePlaying = null,
                            playbackActions = {
                                PlaylistModernPlaybackActions(
                                    1, false, 0, onPlayInOrder = {}, onShufflePlay = {},
                                    onToggleShuffle = {}, onCycleRepeatMode = {}, onExportToLocalPlaylist = {}
                                )
                            },
                            content = {
                                item(key = "song") {
                                    PlaylistModernSongRow(
                                        if (useServerSong.value) server else local, 0, null, true,
                                        selected, selected, false, false, false, 0,
                                        onClick = {}, onLongClick = { selected = true },
                                        onToggleSelection = { selected = !selected },
                                        modifier = Modifier.testTag("song-row")
                                    ) { Spacer(Modifier.size(40.dp)) }
                                }
                            }
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText(SONG, useUnmergedTree = true).assertIsDisplayed()
        val localRow = composeRule.onNodeWithTag("song-row").getUnclippedBoundsInRoot()
        val localTitle = composeRule.onNodeWithText(SONG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        composeRule.runOnIdle { useServerSong.value = true }
        val serverRow = composeRule.onNodeWithTag("song-row").getUnclippedBoundsInRoot()
        val serverTitle = composeRule.onNodeWithText(SONG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(localRow, serverRow)
        assertEquals(localTitle, serverTitle)
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_search_songs)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SONG, useUnmergedTree = true).assertIsDisplayed()
        val searchedRow = composeRule.onNodeWithTag("song-row").getUnclippedBoundsInRoot()
        assertTrue("search must leave a visible song viewport", searchedRow.bottom - searchedRow.top > 0.dp)
        composeRule.onNodeWithTag("song-row").performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_exit_select)).assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_search_songs)).assertIsDisplayed()
    }

    private companion object { const val SONG = "Shared playlist row" }
}
