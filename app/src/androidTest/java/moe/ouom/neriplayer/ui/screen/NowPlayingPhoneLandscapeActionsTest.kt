package moe.ouom.neriplayer.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverActionToolbar
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarStatus
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingCoverToolbarLayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
class NowPlayingPhoneLandscapeActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val clickedActions = mutableListOf<String>()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun phoneLandscapeRetainsEveryAuxiliaryActionWithoutShrinkingTheLyricsFooter() {
        render(280.dp)
        clickAuxiliaryActions()
        composeRule.onNodeWithTag("nowPlayingBottomControls").assertIsDisplayed()
    }

    @Test
    fun shortPhoneLandscapeCanScrollToEveryAuxiliaryActionAndThePlaybackFooter() {
        render(180.dp)
        clickAuxiliaryActions(scrollToActions = true)
        composeRule.onNodeWithTag("nowPlayingBottomControls")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun normalPhoneLandscapePreservesTheParentSwipeDownToCloseGesture() {
        var exitRequested = false
        render(280.dp) { exitRequested = true }
        composeRule.onNodeWithTag("phonePlayer").performTouchInput {
            down(Offset(center.x, height * 0.1f))
            moveBy(Offset(0f, height * 0.3f))
            moveBy(Offset(0f, height * 0.3f))
            up()
        }
        composeRule.runOnIdle { assertTrue("下滑应交给播放页的退出手势", exitRequested) }
    }

    private fun clickAuxiliaryActions(scrollToActions: Boolean = false) {
        val actions = composeRule.onAllNodes(
            hasClickAction() and hasAnyAncestor(hasTestTag("nowPlayingPhoneAuxiliaryActions"))
        )
        actions.assertCountEquals(5)
        repeat(5) { index ->
            val action = actions[index]
            if (scrollToActions) action.performScrollTo()
            action.assertIsDisplayed().performTouchInput { click(center) }
        }
        composeRule.runOnIdle {
            assertEquals(listOf("queue", "timer", "volume", "lyrics", "add"), clickedActions)
        }
    }

    private fun render(height: Dp, onNavigateUp: () -> Unit = {}) {
        val actions = NowPlayingCoverToolbarActions(
            onQueue = { clickedActions += "queue" },
            onSleepTimer = { clickedActions += "timer" },
            onVolume = { clickedActions += "volume" },
            onLyrics = { clickedActions += "lyrics" },
            onAddToPlaylist = { clickedActions += "add" }
        )
        composeRule.setContent {
            MaterialTheme {
                FittedTestViewport(560.dp, height) {
                    SharedTransitionLayout {
                        val sharedTransitionScope = this
                        AnimatedContent(targetState = false, label = "phone_actions_fixture") { lyricsShowing ->
                            NowPlayingWideLayout(
                                controlsAtBottom = false,
                                progressAtBottom = false,
                                topBar = { Box(Modifier.fillMaxWidth().height(56.dp)) },
                                cover = { modifier -> Box(modifier) },
                                identity = { Box(Modifier.fillMaxWidth().height(44.dp)) },
                                progress = { Box(Modifier.fillMaxWidth().height(64.dp)) },
                                controls = { Box(Modifier.fillMaxWidth().height(48.dp)) },
                                toolbar = { compact ->
                                    NowPlayingCoverActionToolbar(
                                        spec = resolveNowPlayingCoverToolbarLayoutSpec(
                                            wideLandscape = true,
                                            compactPortrait = false,
                                            dockEnabled = true,
                                            iconSize = 20.dp,
                                            minimumTouchTarget = 48.dp,
                                            compactHeight = compact,
                                            lyricsAdjustBehavior = false
                                        ),
                                        status = NowPlayingCoverToolbarStatus(false, true, lyricsShowing, Color.Magenta),
                                        actions = actions,
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = this
                                    )
                                },
                                lyrics = { Box(Modifier.fillMaxSize()) },
                                modifier = Modifier.fillMaxSize().testTag("phonePlayer").pointerInput(Unit) {
                                    detectVerticalDragGestures { _, dragAmount ->
                                        if (dragAmount > 60) onNavigateUp()
                                    }
                                },
                                phoneLandscape = true,
                                phoneTopActions = { Box(Modifier.height(56.dp)) }
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
