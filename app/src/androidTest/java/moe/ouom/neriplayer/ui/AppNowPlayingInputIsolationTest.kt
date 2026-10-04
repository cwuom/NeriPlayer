package moe.ouom.neriplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import moe.ouom.neriplayer.ui.playback.visual.AppNowPlayingOverlay
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsSearchField
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNowPlayingInputIsolationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val playerVisible = mutableStateOf(false)
    private val queue = MutableStateFlow<List<SongItem>>(emptyList())
    private val selectionMenu = RecordingSelectionMenu()

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    var query by remember { mutableStateOf("lyrics") }
                    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides selectionMenu) {
                        SettingsSearchField(query = query, onQueryChange = { query = it })
                    }
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        NeriMiniPlayer(
                            title = "Open player",
                            artist = "Test artist",
                            coverUrl = null,
                            isPlaying = false,
                            enableBlur = false,
                            onPlayPause = {},
                            onPrevious = {},
                            onNext = {},
                            onExpand = { playerVisible.value = true }
                        )
                    }
                    AppNowPlayingOverlay(
                        visible = playerVisible.value,
                        cover = NowPlayingOverlayCover(null, null, null, 0),
                        queueFlow = queue,
                        theme = NowPlayingOverlayTheme(
                            false, null, "6750A4", PaletteStyle.TonalSpot,
                            ColorSpec.SpecVersion.Default
                        ),
                        background = NowPlayingOverlayBackground(false, 0f, 0f, false, true),
                        onVisibilityChanged = {},
                        onClose = { playerVisible.value = false }
                    ) {
                        Text("Player content", modifier = Modifier.testTag("player_content"))
                    }
                }
            }
        }
    }

    @Test
    fun openingMiniPlayerDismissesSearchSelectionAndPreservesQuery() {
        selectSearchText()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        waitForSelectionMenu()

        composeRule.onNodeWithText("Open player").performClick()
        settlePlayerTransition()
        composeRule.onNodeWithTag("player_content", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        composeRule.waitUntil(5_000) { !selectionMenu.shown }

        composeRule.runOnIdle { playerVisible.value = false }
        settlePlayerTransition()
        composeRule.onNodeWithText("lyrics").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun openingFromAnotherEntryClearsCapturedInputFocus() {
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.runOnIdle { playerVisible.value = true }
        settlePlayerTransition()
        composeRule.onNodeWithTag("player_content", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun hiddenPlayerLeavesSearchSelectionAvailable() {
        selectSearchText()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        waitForSelectionMenu()
        composeRule.runOnIdle { assertEquals(false, playerVisible.value) }
    }

    private fun waitForSelectionMenu() {
        composeRule.waitUntil(5_000) {
            val selection = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
                .config[SemanticsProperties.TextSelectionRange]
            !selection.collapsed && selectionMenu.shown
        }
    }

    private fun selectSearchText() {
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            longClick(Offset(12f, center.y))
        }
    }

    private fun settlePlayerTransition() {
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
    }

    private class RecordingSelectionMenu : TextContextMenuProvider {
        var shown by mutableStateOf(false)
            private set

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = true
            try {
                awaitCancellation()
            } finally {
                shown = false
            }
        }
    }
}
