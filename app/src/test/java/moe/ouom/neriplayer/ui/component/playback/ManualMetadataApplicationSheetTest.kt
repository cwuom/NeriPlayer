package moe.ouom.neriplayer.ui.component.playback

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyStage
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsPage
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsSheetOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.MoreOptionsSheetSurface
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.ManualMetadataApplicationOwner
import moe.ouom.neriplayer.ui.viewmodel.ManualSearchState
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ManualMetadataApplicationSheetTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val application = ManualMetadataApplicationOwner()
    private var complete: (Boolean) -> Unit = {}
    private var dismissals = 0
    private var completionRequests = 0

    @Test
    fun `cancelling preparation keeps the search open and rejects late success`() {
        setSheet(SongMetadataApplyStage.FETCHING_LYRICS)
        composeRule.onNodeWithTag("metadata-application-cancel").performClick()
        composeRule.runOnIdle { complete(true) }

        composeRule.onNodeWithTag("metadata-search-results").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun `applying blocks closing and failure leaves the sheet available`() {
        setSheet(SongMetadataApplyStage.APPLYING)
        composeRule.onNodeWithTag("metadata-test-close").performClick()
        composeRule.onNodeWithTag("metadata-application-cancel").assertIsNotEnabled()
        composeRule.onNodeWithTag("metadata-stage-progress").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, dismissals)
            complete(false)
        }

        composeRule.onNodeWithTag("metadata-application-failure").assertIsDisplayed()
        composeRule.onNodeWithTag("metadata-search-results").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun `successful application renders completion before closing`() {
        setSheet(SongMetadataApplyStage.APPLYING)
        composeRule.mainClock.autoAdvance = false
        val runningBounds = composeRule.onNodeWithTag("metadata-application-progress").getUnclippedBoundsInRoot()
        composeRule.runOnIdle { complete(true) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("metadata-stage-progress").assertIsDisplayed()
        val completedBounds = composeRule.onNodeWithTag("metadata-application-progress").getUnclippedBoundsInRoot()
        assertEquals("completion must not collapse the progress card",
            (runningBounds.bottom - runningBounds.top).value, (completedBounds.bottom - completedBounds.top).value, 0.5f)
        composeRule.mainClock.advanceTimeBy(240L)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, dismissals) }
        repeat(80) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals("completion must request closing once", 1, completionRequests)
            assertEquals("sheet hide must finish after the completion presentation", 1, dismissals)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setSheet(stage: SongMetadataApplyStage) {
        application.start { progress, completion ->
            complete = completion
            progress(stage)
            Job()
        }
        composeRule.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                val state by application.state.collectAsState()
                val scope = rememberCoroutineScope()
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val owner = remember {
                    MoreOptionsSheetOwner(scope, sheet::hide, sheet::expand, MoreOptionsPage.SEARCH) { dismissals++ }
                }
                val applying = state.isRunning
                SideEffect { owner.setMetadataApplying(applying) }
                MoreOptionsSheetSurface(owner, sheet, remember { SnackbarHostState() }) {
                    Column(Modifier.padding(16.dp)) {
                        if (state.failed) Text("Apply failed", Modifier.testTag("metadata-application-failure"))
                        MetadataSearchResults(
                            state = ManualSearchState(searchResults = listOf(SongSearchInfo(
                                id = "fixture", songName = "Synthetic song", singer = "Synthetic artist",
                                duration = "03:00", albumName = "Synthetic album", coverUrl = null,
                                source = MusicPlatform.CLOUD_MUSIC
                            ))),
                            offlineMode = true, enabled = !state.isRunning, isWaitingForPlayback = false,
                            application = state, onCancelApplication = { application.cancel() },
                            onSongSelected = {}, requestKey = "fixture", onApplied = {
                                completionRequests++
                                owner.dismiss()
                            }
                        )
                        HapticTextButton(onClick = owner::dismiss, modifier = Modifier.testTag("metadata-test-close")) {
                            Text("Close")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
