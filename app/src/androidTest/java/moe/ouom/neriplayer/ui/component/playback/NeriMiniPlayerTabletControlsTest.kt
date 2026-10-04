package moe.ouom.neriplayer.ui.component.playback

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriMiniPlayerTabletControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val viewportWidth = mutableStateOf(800.dp)
    private var pixelsPerDp = 1f
    private var previousCalls = 0
    private var playPauseCalls = 0
    private var nextCalls = 0
    private var expandCalls = 0

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tabletPortraitControlsHaveDescriptionsAndDoNotExpandPlayer() {
        render(800.dp, 1280.dp, smallestScreenWidthDp = 800)
        assertTabletControlsFit()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.player_previous))
            .assertIsEnabled().performTouchInput { click() }
        composeRule.onNodeWithTag(PlayPauseTag).performTouchInput { click() }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.player_next))
            .assertIsEnabled().performTouchInput { click() }

        composeRule.runOnIdle {
            assertEquals(1, previousCalls)
            assertEquals(1, playPauseCalls)
            assertEquals(1, nextCalls)
            assertEquals(0, expandCalls)
        }
        composeRule.onNodeWithTag(MetadataTag, useUnmergedTree = true).performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, expandCalls) }
    }

    @Test
    fun tabletLandscapeControlsKeepMetadataAndContainedHeight() {
        render(1280.dp, 800.dp, smallestScreenWidthDp = 800)
        assertTabletControlsFit()
    }

    @Test
    fun compactTabletWindowWithLargeFontKeepsTextAndTouchTargets() {
        render(400.dp, 640.dp, smallestScreenWidthDp = 800, fontScale = 2f)
        assertTabletControlsFit()
        assertMetadataVisible()
    }

    @Test
    fun narrowTabletFallsBackAndKeepsSwipeNavigationAcrossResize() {
        render(360.dp, 640.dp, smallestScreenWidthDp = 800)
        assertSingleControlLayout()
        composeRule.onNodeWithTag(MiniPlayerTag).performTouchInput { swipeLeft() }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MiniPlayerTag).performTouchInput { swipeRight() }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, previousCalls)
            assertEquals(1, nextCalls)
            assertEquals(0, expandCalls)
            viewportWidth.value = 800.dp
        }
        assertTabletControlsFit()
        composeRule.onNodeWithTag(NextTag).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(2, nextCalls)
            assertEquals(0, expandCalls)
        }
    }

    @Test
    fun phonePortraitKeepsExistingSingleControl() {
        render(360.dp, 800.dp, smallestScreenWidthDp = 360)
        assertSingleControlLayout()
    }

    @Test
    fun phoneLandscapeKeepsExistingSingleControl() {
        render(840.dp, 360.dp, smallestScreenWidthDp = 360)
        assertSingleControlLayout()
    }

    @Test
    fun disabledPlayPauseDoesNotDisableExistingSkipCallbacks() {
        render(800.dp, 1280.dp, smallestScreenWidthDp = 800, playPauseEnabled = false)
        composeRule.onNodeWithTag(PlayPauseTag).assertIsNotEnabled()
        composeRule.onNodeWithTag(PreviousTag).assertIsEnabled().performTouchInput { click() }
        composeRule.onNodeWithTag(NextTag).assertIsEnabled().performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(1, previousCalls)
            assertEquals(1, nextCalls)
            assertEquals(0, playPauseCalls)
            assertEquals(0, expandCalls)
        }
    }

    private fun render(
        width: Dp,
        height: Dp,
        smallestScreenWidthDp: Int,
        fontScale: Float = 1f,
        playPauseEnabled: Boolean = true
    ) {
        viewportWidth.value = width
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val hostDensity = LocalDensity.current
                    val logicalWidth = viewportWidth.value
                    val scale = minOf(maxWidth.value / logicalWidth.value, maxHeight.value / height.value)
                    val density = Density(hostDensity.density * scale, fontScale)
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        this.smallestScreenWidthDp = smallestScreenWidthDp
                        screenWidthDp = logicalWidth.value.toInt()
                        screenHeightDp = height.value.toInt()
                        orientation = if (logicalWidth > height) Configuration.ORIENTATION_LANDSCAPE
                        else Configuration.ORIENTATION_PORTRAIT
                    }
                    CompositionLocalProvider(
                        LocalDensity provides density,
                        LocalConfiguration provides configuration
                    ) {
                        SideEffect { pixelsPerDp = density.density }
                        Box(Modifier.requiredSize(logicalWidth, height)) {
                            NeriMiniPlayer(
                                title = Title,
                                artist = Artist,
                                coverUrl = null,
                                isPlaying = false,
                                playPauseEnabled = playPauseEnabled,
                                modifier = Modifier.testTag(MiniPlayerTag),
                                onPlayPause = { playPauseCalls++ },
                                onPrevious = { previousCalls++ },
                                onNext = { nextCalls++ },
                                onExpand = { expandCalls++ },
                                enableBlur = false
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertSingleControlLayout() {
        composeRule.onNodeWithTag(PreviousTag).assertDoesNotExist()
        composeRule.onNodeWithTag(NextTag).assertDoesNotExist()
        composeRule.onNodeWithTag(PlayPauseTag).assertIsDisplayed().performTouchInput { click() }
        assertMetadataVisible()
        assertTrue(bounds(MetadataTag).width >= 144f * pixelsPerDp - 1f)
        composeRule.runOnIdle {
            assertEquals(1, playPauseCalls)
            assertEquals(0, expandCalls)
        }
    }

    private fun assertTabletControlsFit() {
        composeRule.onNodeWithTag(PreviousTag).assertIsDisplayed()
        composeRule.onNodeWithTag(PlayPauseTag).assertIsDisplayed()
        composeRule.onNodeWithTag(NextTag).assertIsDisplayed()
        assertMetadataVisible()
        val player = bounds(MiniPlayerTag)
        val metadata = bounds(MetadataTag)
        val previous = bounds(PreviousTag)
        val playPause = bounds(PlayPauseTag)
        val next = bounds(NextTag)
        assertEquals(64f * pixelsPerDp, player.height, 1f)
        assertTrue("歌曲文字空间不足: $metadata", metadata.width >= 144f * pixelsPerDp - 1f)
        assertTrue(metadata.right <= previous.left)
        assertTrue(previous.right <= playPause.left + 1f)
        assertTrue(playPause.right <= next.left + 1f)
        listOf(previous, playPause, next).forEach { control ->
            assertEquals(48f * pixelsPerDp, control.width, 1f)
            assertEquals(48f * pixelsPerDp, control.height, 1f)
            assertTrue("播放控件越过 MiniPlayer 边界: $control/$player", control.left >= player.left &&
                control.right <= player.right && control.top >= player.top && control.bottom <= player.bottom)
        }
    }

    private fun assertMetadataVisible() {
        composeRule.onNodeWithText(Title, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(Artist, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private companion object {
        const val MiniPlayerTag = "miniPlayerTabletFixture"
        const val MetadataTag = "miniPlayerMetadata"
        const val PreviousTag = "miniPlayerPrevious"
        const val PlayPauseTag = "miniPlayerPlayPause"
        const val NextTag = "miniPlayerNext"
        const val Title = "这是一首很长的歌曲名称，仍应保留可读的文字空间"
        const val Artist = "演唱者名称"
    }
}
