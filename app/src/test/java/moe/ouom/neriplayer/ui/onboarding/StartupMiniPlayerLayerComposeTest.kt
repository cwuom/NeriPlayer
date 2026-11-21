package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.screen.nowplaying.LocalNowPlayingPlaybackSource
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingPlaybackSource
import moe.ouom.neriplayer.ui.screen.nowplaying.PlayerManagerNowPlayingPlaybackSource
import moe.ouom.neriplayer.ui.theme.NeriDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class StartupMiniPlayerLayerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var expanded by mutableStateOf(false)

    @Test
    fun `now playing reads the real player unless onboarding swaps the source`() {
        lateinit var resolved: NowPlayingPlaybackSource
        composeRule.setContent { resolved = LocalNowPlayingPlaybackSource.current }
        composeRule.runOnIdle { assertSame(PlayerManagerNowPlayingPlaybackSource, resolved) }
    }

    @Test
    fun `floating mini player is rounded on all four corners`() {
        lateinit var expected: Shape
        composeRule.setContent {
            expected = NeriDesign.tokens.miniPlayerShape
            val anchor = remember { StartupMiniPlayerAnchor() }
            val source = rememberStartupPreviewPlaybackSource(buildStartupPreviewLyrics(listOf("a")), cover = null)
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    StartupMiniPlayerSlot(visible = true, anchor = anchor)
                }
                StartupFloatingMiniPlayerLayer(true, false, null, source, anchor, onExpand = {})
            }
        }
        composeRule.waitForIdle()
        // 应用里贴底栏的 Material 迷你播放器只圆上角, 引导里悬浮时玻璃层也必须用完整圆角
        composeRule.onNode(
            hasAnyAncestor(hasTestTag(StartupMiniPlayerTag)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Shape, expected),
            useUnmergedTree = true
        ).assertExists()
    }

    @Test
    fun `floating mini player sits on its slot outside the glass layer and expands on tap`() {
        var expandRequests = 0
        composeRule.setContent {
            val anchor = remember { StartupMiniPlayerAnchor() }
            val lyrics = buildStartupPreviewLyrics(startupPreviewLyricTexts())
            val source = rememberStartupPreviewPlaybackSource(lyrics, cover = null)
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) {
                    Box(modifier = Modifier.testTag(SLOT_TAG)) {
                        StartupMiniPlayerSlot(visible = !expanded, anchor = anchor)
                    }
                }
                StartupFloatingMiniPlayerLayer(
                    visible = true,
                    expanded = expanded,
                    cover = null,
                    source = source,
                    anchor = anchor,
                    onExpand = {
                        expandRequests += 1
                        expanded = true
                    }
                )
            }
        }
        composeRule.waitForIdle()
        val slot = composeRule.onNodeWithTag(SLOT_TAG).getBoundsInRoot()
        val player = composeRule.onNodeWithTag(StartupMiniPlayerTag).getBoundsInRoot()
        // 迷你播放器整体落在占位里, 下方留出与按钮之间的间距
        assertTrue("迷你播放器顶边不能越过占位: $player / $slot", player.top >= slot.top - 1.dp)
        assertTrue("迷你播放器底边不能压到按钮: $player / $slot", player.bottom <= slot.bottom + 1.dp)
        assertEquals(slot.left.value, player.left.value, 1f)
        assertEquals(slot.right.value, player.right.value, 1f)

        composeRule.onNodeWithTag(StartupMiniPlayerTag).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(1, expandRequests)
        composeRule.onNodeWithTag(StartupMiniPlayerTag).assertDoesNotExist()
    }

    private companion object {
        const val SLOT_TAG = "miniPlayerSlot"
    }
}
