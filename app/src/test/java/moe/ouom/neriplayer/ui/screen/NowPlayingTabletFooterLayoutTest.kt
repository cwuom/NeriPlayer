package moe.ouom.neriplayer.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1000dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingTabletFooterLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomProgressAndControlsAlignWithTheirRespectiveUpperPanes() {
        render(NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

        val player = bounds("nowPlayingPlayerPane")
        val lyrics = bounds("nowPlayingLyricPane")
        val progress = bounds("progress")
        val controls = bounds("controls")
        assertAllSlotsFit()
        assertEquals("进度区应沿用封面栏的左边界", player.left, progress.left, 1f)
        assertEquals("进度区应沿用封面栏的右边界", player.right, progress.right, 1f)
        assertEquals(player.center.x, bounds("cover").center.x, 1f)
        assertEquals(bounds("cover").center.x, progress.center.x, 1f)
        assertEquals(bounds("toolbar").center.x, progress.center.x, 1f)
        assertEquals("播放控制应在歌词栏正下方", lyrics.center.x, controls.center.x, 1f)
        assertTrue(progress.right < controls.left)
        assertTrue(progress.top < controls.bottom && controls.top < progress.bottom)
    }

    @Test
    fun onlyBottomControlsKeepTheirFullWidthFooter() {
        render(NowPlayingControlPlacement.BOTTOM)

        val frame = bounds("viewport")
        val footer = bounds("nowPlayingBottomControls")
        val controls = bounds("controls")
        assertAllSlotsFit()
        assertEquals(frame.left, footer.left, 1f)
        assertEquals(frame.right, footer.right, 1f)
        assertEquals(footer.left, controls.left, 1f)
        assertEquals(footer.right, controls.right, 1f)
        assertEquals(bounds("nowPlayingLyricPane").center.x, bounds("progress").center.x, 1f)
        assertTrue(bounds("progress").bottom <= controls.top)
    }

    @Test
    fun rtlBottomProgressAndControlsFollowTheMirroredUpperPanes() {
        render(NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS, LayoutDirection.Rtl)

        val player = bounds("nowPlayingPlayerPane")
        val lyrics = bounds("nowPlayingLyricPane")
        val progress = bounds("progress")
        val controls = bounds("controls")
        assertAllSlotsFit()
        assertTrue("RTL 的播放栏应处于逻辑起始侧", player.left > lyrics.right)
        assertEquals(player.left, progress.left, 1f)
        assertEquals(player.right, progress.right, 1f)
        assertEquals(bounds("cover").center.x, progress.center.x, 1f)
        assertEquals(bounds("toolbar").center.x, progress.center.x, 1f)
        assertEquals(lyrics.left, controls.left, 1f)
        assertEquals(lyrics.right, controls.right, 1f)
    }

    @Test
    fun lowerControlsRemainWithTheProgressInTheLyricsPane() {
        render(NowPlayingControlPlacement.LOWER)

        composeRule.onNodeWithTag("nowPlayingBottomControls").assertDoesNotExist()
        assertAllSlotsFit()
        val progress = bounds("progress")
        val controls = bounds("controls")
        val lyrics = bounds("nowPlayingLyricPane")
        assertEquals(lyrics.center.x, progress.center.x, 1f)
        assertEquals(lyrics.center.x, controls.center.x, 1f)
        assertTrue(progress.left > bounds("nowPlayingPlayerPane").right)
        assertTrue(progress.bottom <= controls.top)
        assertTrue(controls.bottom <= lyrics.top)
    }

    private fun render(
        placement: NowPlayingControlPlacement,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr
    ) {
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val baseDensity = LocalDensity.current
                    val viewportScale = minOf(maxWidth.value / 1000f, maxHeight.value / 500f)
                    CompositionLocalProvider(
                        LocalDensity provides Density(baseDensity.density * viewportScale, baseDensity.fontScale),
                        LocalLayoutDirection provides layoutDirection
                    ) {
                        Box(Modifier.requiredSize(1000.dp, 500.dp).testTag("viewport")) {
                            NowPlayingWideLayout(
                                controlsAtBottom = placement.placesControlsAtBottom,
                                progressAtBottom = placement.placesProgressAtBottom,
                                topBar = { Slot("topBar", 56.dp) },
                                cover = { modifier ->
                                    BoxWithConstraints(modifier) {
                                        Box(Modifier.align(Alignment.Center)
                                            .size(minOf(maxWidth, maxHeight, 420.dp)).testTag("cover"))
                                    }
                                },
                                identity = { Slot("identity", 44.dp) },
                                progress = { Slot("progress", 64.dp) },
                                controls = { Slot("controls", 52.dp) },
                                toolbar = { Slot("toolbar", 58.dp) },
                                lyrics = { Box(Modifier.fillMaxSize()) },
                                modifier = Modifier.fillMaxSize(),
                                phoneLandscape = false,
                                phoneTopActions = {}
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertAllSlotsFit() {
        val frame = bounds("viewport")
        listOf("topBar", "cover", "identity", "progress", "controls", "toolbar", "nowPlayingLyricPane")
            .forEach { tag ->
                val child = bounds(tag)
                assertTrue("$tag 应有实际测量尺寸: $child", child.width > 0f && child.height > 0f)
                assertTrue("$tag 应完整位于视窗内: child=$child frame=$frame",
                    child.left >= frame.left - 1f && child.right <= frame.right + 1f &&
                        child.top >= frame.top - 1f && child.bottom <= frame.bottom + 1f)
            }
    }

    private fun bounds(tag: String): Rect {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val origin = node.positionInRoot
        return Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
    }

    @Composable
    private fun Slot(tag: String, height: Dp) {
        Box(Modifier.fillMaxWidth().height(height).testTag(tag))
    }
}
