package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicServerBrowseUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireComposeHost() = assumeComposeHostAvailable()

    @Test
    fun cleanSearchSubmitsAndClears() = verifySearch(UiStyle.Clean)

    @Test
    fun materialSearchSubmitsAndClears() = verifySearch(UiStyle.Material)

    @Test
    fun cleanBatchMenuDispatchesDownload() = verifyBatchDownload(UiStyle.Clean)

    @Test
    fun materialBatchMenuDispatchesDownload() = verifyBatchDownload(UiStyle.Material)

    @Test
    fun downloadedSongOffersDownloadManager() {
        var opened = 0
        var showManager by mutableStateOf(false)
        val tasksBefore = GlobalDownloadManager.downloadTasks.value.filter { it.song.audioId == serverSong.audioId }
        render(UiStyle.Clean) {
            MusicServerSongRow(
                song = serverSong, index = 1, current = false, playing = false, favorite = false,
                favoriteBusy = false, selectionMode = false, selected = false, downloaded = true,
                onClick = {}, onSelect = {}, onFavorite = {}, onAddToPlaylist = {},
                onPlayNext = {}, onAddToQueue = {}, onDownload = { opened++; showManager = true }
            )
            if (showManager) BatchDownloadManagerSheet(emptyList(), onDismiss = { showManager = false })
        }
        composeRule.onNodeWithContentDescription(text(CoreCommonR.string.cd_more_actions)).performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.download_to_local)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.download_manager)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.download_manager)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, opened)
            assertEquals(tasksBefore, GlobalDownloadManager.downloadTasks.value.filter { it.song.audioId == serverSong.audioId })
        }
    }

    @Test
    fun songLongPressStillEntersSelection() {
        var selected by mutableStateOf(false)
        var selections = 0
        render(UiStyle.Material) {
            MusicServerSongRow(
                song = serverSong, index = 1, current = false, playing = false, favorite = false,
                favoriteBusy = false, selectionMode = selected, selected = selected, downloaded = false,
                onClick = {}, onSelect = { selected = true; selections++ }, onFavorite = {},
                onAddToPlaylist = {}, onPlayNext = {}, onAddToQueue = {}, onDownload = {}
            )
        }
        composeRule.onNodeWithText(serverSong.name).performTouchInput { longClick() }
        composeRule.onNode(isToggleable()).assertIsOn()
        composeRule.onNodeWithContentDescription(text(CoreCommonR.string.cd_more_actions)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, selections) }
    }

    private fun verifySearch(style: UiStyle) {
        var query by mutableStateOf("")
        var submitted: String? = null
        var cleared = 0
        render(style) {
            ServerSearchField(query, text(CoreCommonR.string.server_search_songs_hint),
                onQueryChange = { query = it }, onSearch = { submitted = query },
                onClear = { query = ""; cleared++ })
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("night")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.runOnIdle { assertEquals("night", submitted) }
        val clearLabel = if (style == UiStyle.Clean) CoreCommonR.string.action_clear
            else CoreCommonR.string.settings_search_clear_query
        composeRule.onNodeWithContentDescription(text(clearLabel)).performClick()
        composeRule.runOnIdle {
            assertEquals("", query)
            assertEquals(1, cleared)
        }
    }

    private fun verifyBatchDownload(style: UiStyle) {
        var downloads = 0
        val song = SongItem(1L, "Server song", "Artist", "Album", 0L, 180_000L, null)
        render(style) {
            ServerSongActions(listOf(song), 0, false, false, false,
                onPlay = {}, onAddToPlaylist = {}, onDownload = { downloads++ },
                onSelection = {}, onSelectAll = {})
        }
        composeRule.onNodeWithContentDescription(text(CoreCommonR.string.cd_more_actions)).performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.download_to_local))
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, downloads) }
    }

    private fun render(style: UiStyle, content: @Composable () -> Unit) {
        composeRule.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                Box(Modifier.width(320.dp)) { content() }
            }
        }
    }

    private fun text(resource: Int): String = context.getString(resource)

    private val serverSong = ServerSongRef("00000000-0000-0000-0000-000000000511", "menu-fixture").let { ref ->
        SongItem(ref.numericId, "A long server song title", "Server artist", "Server album", 0L, 180_000L, null,
            mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
    }
}
