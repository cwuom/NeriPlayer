package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingTabletPortraitLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingTabletPortrait
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingAdaptiveCoverSize
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingPhoneTopActionButtonSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingAdaptiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var logicalViewportWidth = 0f
    private var phoneLandscapeFixture = false

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tabletPortrait800x1280_limitsCoverAndActionsAndExpandsLyrics() {
        renderLayout(800.dp, 1280.dp, NowPlayingControlPlacement.LOWER)

        assertTabletPortraitControlsFit()
        val frame = bounds("viewport")
        val content = bounds("nowPlayingTabletPortraitContent")
        val lyrics = bounds("nowPlayingTabletPortraitLyrics")
        assertEquals(logicalPixels(560.dp), content.width, 1f)
        assertEquals(frame.center.x, content.center.x, 1f)
        assertTrue(bounds("cover").width <= logicalPixels(336.dp) + 1f)
        assertTrue(lyrics.height >= logicalPixels(400.dp))
        assertTrue(bounds("controls").bottom <= lyrics.top)
        assertTrue(bounds("identity").top >= bounds("cover").bottom + logicalPixels(16.dp) - 1f)
        assertSquareCover()
    }

    @Test
    fun tabletPortrait800x1280_bottomControlsKeepProgressBelowIdentity() {
        renderLayout(800.dp, 1280.dp, NowPlayingControlPlacement.BOTTOM)

        assertTabletPortraitControlsFit()
        assertTrue(bounds("progress").bottom <= bounds("nowPlayingTabletPortraitLyrics").top)
        assertTrue(bounds("controls").top >= bounds("nowPlayingTabletPortraitLyrics").bottom)
        assertEquals(logicalPixels(4.dp), bounds("toolbar").top - bounds("controls").bottom, 1f)
    }

    @Test
    fun tabletPortrait800x1280_bottomProgressMovesWithControls() {
        renderLayout(800.dp, 1280.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

        assertTabletPortraitControlsFit()
        assertTrue(bounds("progress").top >= bounds("nowPlayingTabletPortraitLyrics").bottom)
        assertEquals(logicalPixels(10.dp), bounds("controls").top - bounds("progress").bottom, 1f)
        assertEquals(logicalPixels(4.dp), bounds("toolbar").top - bounds("controls").bottom, 1f)
    }

    @Test
    fun tabletPortrait600x960_largeTextAndControlsRetainUsableLyrics() {
        renderLayout(
            600.dp, 960.dp, NowPlayingControlPlacement.LOWER,
            largeControls = true, fontScale = 1.5f, useRealIdentity = true
        )

        assertTabletPortraitControlsFit()
        assertTrue(bounds("cover").width <= logicalPixels(300.dp))
        assertTrue(bounds("nowPlayingTabletPortraitLyrics").height >= logicalPixels(180.dp))
        assertSquareCover()
    }

    @Test
    fun tabletPortrait_withoutCoverLyricsKeepsTheToolbarAtTheBottom() {
        renderLayout(800.dp, 1280.dp, NowPlayingControlPlacement.LOWER, lyricsVisible = false)

        assertTabletPortraitControlsFit()
        composeRule.onNodeWithTag("lyricsContent").assertDoesNotExist()
        assertEquals(bounds("viewport").bottom, bounds("toolbar").bottom, 1f)
        assertTrue(bounds("controls").bottom < bounds("toolbar").top - logicalPixels(100.dp))
    }

    @Test
    fun tablet1280x800_keepsCoverAndControlsInsideThePlayerPane() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.LOWER)

        val frame = bounds("viewport")
        val player = bounds("nowPlayingPlayerPane")
        val lyrics = bounds("nowPlayingLyricPane")
        assertTrue(lyrics.left > player.right)
        assertTrue(lyrics.width > player.width)
        assertEquals(frame.height, lyrics.height, 1f)
        assertControlsFit(frame)
        assertTrue(bounds("controls").bottom < bounds("toolbar").top - logicalPixels(24.dp))
        assertTrue(bounds("cover").top >= bounds("topBar").bottom + logicalPixels(28.dp) - 1f)
        assertTrue(bounds("identity").top >= bounds("cover").bottom + logicalPixels(16.dp) - 1f)
        assertTrue(bounds("cover").width >= logicalPixels(350.dp) - 1f)
        assertSquareCover()
    }

    @Test
    fun tablet1280x650_givesTheCoverMoreHeightAndKeepsLowerControlsAboveTheToolbar() {
        renderLayout(1280.dp, 650.dp, NowPlayingControlPlacement.LOWER)

        val frame = bounds("viewport")
        val player = bounds("nowPlayingPlayerPane")
        val lyrics = bounds("nowPlayingLyricPane")
        assertControlsFit(frame)
        assertTrue(bounds("cover").width >= logicalPixels(220.dp) - 1f)
        assertTrue(bounds("controls").bottom < bounds("toolbar").top - logicalPixels(24.dp))
        assertTrue(lyrics.width > player.width)
        assertEquals(frame.height, lyrics.height, 1f)
        assertSquareCover()
    }

    @Test
    fun tablet1280x800_bottomControlsStayNextToTheToolbar() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM)

        assertControlsFit(bounds("viewport"))
        assertTrue(bounds("controls").top > bounds("progress").bottom + logicalPixels(24.dp))
        assertEquals(logicalPixels(16.dp), bounds("toolbar").top - bounds("controls").bottom, 1f)
    }

    @Test
    fun tablet1280x800_bottomProgressMovesWithControls() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

        assertControlsFit(bounds("viewport"))
        assertTrue(bounds("progress").top > bounds("identity").bottom + logicalPixels(24.dp))
        assertEquals(logicalPixels(12.dp), bounds("controls").top - bounds("progress").bottom, 1f)
    }

    @Test
    fun phone840x360_lowerPreferenceKeepsControlsBelowLyricsInTheRightPane() {
        renderLayout(
            840.dp, 360.dp, NowPlayingControlPlacement.LOWER,
            largeControls = true, smallestScreenWidthDp = 360
        )

        assertControlsFit(bounds("viewport"))
        assertPhoneLandscapeArrangement(180.dp)
        assertTrue(bounds("cover").height >= logicalPixels(220.dp))
        assertTrue(bounds("cover").top >= bounds("topBar").bottom + logicalPixels(8.dp) - 1f)
        assertTrue(bounds("cover").bottom <= bounds("toolbar").top - logicalPixels(8.dp) + 1f)
        assertSquareCover()
    }

    @Test
    fun phone840x360_bottomProgressPreferenceKeepsProgressAboveControlsInTheRightPane() {
        renderLayout(
            840.dp, 360.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS,
            largeControls = true, smallestScreenWidthDp = 360
        )

        assertControlsFit(bounds("viewport"))
        assertPhoneLandscapeArrangement(180.dp)
        assertSquareCover()
    }

    @Test
    fun phone840x360_bottomPreferenceKeepsBothPlaybackSectionsInTheRightFooter() {
        renderLayout(840.dp, 360.dp, NowPlayingControlPlacement.BOTTOM, smallestScreenWidthDp = 360)

        assertControlsFit(bounds("viewport"))
        assertPhoneLandscapeArrangement(180.dp)
    }

    @Test
    fun narrowPhone560x280_keepsTheHeaderAndFooterOutsideTheLyrics() {
        renderLayout(560.dp, 280.dp, NowPlayingControlPlacement.LOWER, smallestScreenWidthDp = 360)

        assertControlsFit(bounds("viewport"))
        assertPhoneLandscapeArrangement(100.dp)
        assertSquareCover()
    }

    @Test
    fun narrowPhone560x280_largeTextDoesNotPushControlsOutsideTheRightPane() {
        renderLayout(
            560.dp, 280.dp, NowPlayingControlPlacement.LOWER,
            largeControls = true, smallestScreenWidthDp = 360,
            fontScale = 1.5f, useRealIdentity = true
        )

        assertControlsFit(bounds("viewport"))
        assertPhoneLandscapeArrangement(72.dp)
        assertTrue(bounds("identity").width >= logicalPixels(120.dp))
    }

    @Test
    fun shortTablet1280x480_keepsAuxiliaryToolbarDespiteCompactHeight() {
        renderLayout(1280.dp, 480.dp, NowPlayingControlPlacement.LOWER, smallestScreenWidthDp = 800)

        assertControlsFit(bounds("viewport"))
        assertTrue(bounds("toolbar").height > 0f)
        assertTrue(bounds("toolbar").top >= bounds("cover").bottom)
        assertSquareCover()
    }

    @Test
    fun shortTablet1280x480_bottomProgressStillUsesTheFullWidthStrip() {
        renderLayout(1280.dp, 480.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS, smallestScreenWidthDp = 800)

        val frame = bounds("viewport")
        val footer = bounds("nowPlayingBottomControls")
        val progress = bounds("progress")
        val controls = bounds("controls")
        assertControlsFit(frame)
        assertEquals(frame.left, footer.left, 1f)
        assertEquals(frame.right, footer.right, 1f)
        assertTrue(progress.right < controls.left)
        assertTrue(progress.top < controls.bottom && controls.top < progress.bottom)
        assertTrue(bounds("toolbar").height > 0f)
    }

    @Test
    fun lyricPaneKeepsTheUnderlyingPlaybackBackgroundVisible() {
        val background = Color(0xFF5C2248)
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.LOWER, background = background)

        val image = composeRule.onNodeWithTag("nowPlayingLyricPane").captureToImage()
        val centerPixel = image.toPixelMap()[image.width / 2, image.height / 2]
        assertEquals(background.toArgb(), centerPixel.toArgb())
    }

    private fun renderLayout(
        width: Dp,
        height: Dp,
        placement: NowPlayingControlPlacement,
        largeControls: Boolean = false,
        smallestScreenWidthDp: Int = 800,
        background: Color = Color.Transparent,
        fontScale: Float = 1f,
        useRealIdentity: Boolean = false,
        lyricsVisible: Boolean = true
    ) {
        logicalViewportWidth = width.value
        phoneLandscapeFixture = isNowPlayingPhoneLandscape(width > height, smallestScreenWidthDp)
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val hostDensity = LocalDensity.current
                    val viewportScale = minOf(maxWidth.value / width.value, maxHeight.value / height.value)
                    CompositionLocalProvider(
                        LocalDensity provides Density(hostDensity.density * viewportScale, fontScale = fontScale)
                    ) {
                        Box(Modifier.requiredSize(width, height).background(background).testTag("viewport")) {
                            if (isNowPlayingTabletPortrait(width > height, smallestScreenWidthDp)) {
                                NowPlayingTabletPortraitLayout(
                                    controlsAtBottom = placement.placesControlsAtBottom,
                                    progressAtBottom = placement.placesProgressAtBottom,
                                    lyricsVisible = lyricsVisible,
                                    topBar = { LayoutSlot("topBar", if (largeControls) 58.dp else 56.dp) },
                                    cover = { modifier, preferredSize ->
                                        BoxWithConstraints(modifier) {
                                            val coverSize = resolveNowPlayingAdaptiveCoverSize(maxWidth, maxHeight, preferredSize)
                                            Box(Modifier.align(Alignment.Center).size(coverSize).testTag("cover"))
                                        }
                                    },
                                    identity = {
                                        if (useRealIdentity) {
                                            Column(Modifier.fillMaxWidth().testTag("identity")) {
                                                Text(
                                                    "平板竖屏上的歌曲标题保持居中和单行显示",
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text("演唱者", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                            }
                                        } else {
                                            LayoutSlot("identity", 52.dp)
                                        }
                                    },
                                    progress = { LayoutSlot("progress", 64.dp) },
                                    controls = { LayoutSlot("controls", if (largeControls) 64.dp else 52.dp) },
                                    toolbar = { LayoutSlot("toolbar", 82.dp) },
                                    lyrics = { Box(Modifier.fillMaxSize().testTag("lyricsContent")) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else NowPlayingWideLayout(
                                controlsAtBottom = placement.placesControlsAtBottom,
                                progressAtBottom = placement.placesProgressAtBottom,
                                topBar = { LayoutSlot("topBar", 56.dp) },
                                cover = { modifier ->
                                    BoxWithConstraints(modifier) {
                                        val coverSize = resolveNowPlayingAdaptiveCoverSize(maxWidth, maxHeight, 420.dp)
                                        Box(Modifier.align(Alignment.Center).size(coverSize).testTag("cover"))
                                    }
                                },
                                identity = { compact ->
                                    if (useRealIdentity) {
                                        Column(Modifier.fillMaxWidth().testTag("identity")) {
                                            Text(
                                                "很长的歌曲名称需要在右侧操作按钮旁保持单行",
                                                style = MaterialTheme.typography.titleLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text("演唱者", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        }
                                    } else {
                                        LayoutSlot("identity", if (compact) 44.dp else 52.dp)
                                    }
                                },
                                progress = { LayoutSlot("progress", 64.dp) },
                                controls = {
                                    val controlHeight = if (phoneLandscapeFixture) 48.dp
                                    else if (largeControls) 64.dp else 52.dp
                                    LayoutSlot("controls", controlHeight)
                                },
                                toolbar = { compact -> LayoutSlot("toolbar", if (compact) 58.dp else 82.dp) },
                                lyrics = { Box(Modifier.fillMaxSize()) },
                                modifier = Modifier.fillMaxSize(),
                                phoneLandscape = phoneLandscapeFixture,
                                phoneTopActions = {
                                    BoxWithConstraints {
                                        val preferredSize = if (largeControls) 58.dp else 48.dp
                                        val buttonSize = resolveNowPlayingPhoneTopActionButtonSize(maxWidth, preferredSize, true)
                                        Box(Modifier.width(buttonSize * 3).height(maxOf(56.dp, preferredSize)).testTag("topActions"))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertControlsFit(frame: Rect) {
        val controlTags = listOf("topBar", "cover", "identity", "progress", "controls", "toolbar", "nowPlayingLyricPane") +
            if (phoneLandscapeFixture) listOf("topActions") else emptyList()
        controlTags.forEach { tag ->
            val child = bounds(tag)
            assertTrue("$tag has no measured size", child.width > 0f && child.height > 0f)
            assertTrue("$tag exceeds the horizontal viewport", child.left >= frame.left - 1f && child.right <= frame.right + 1f)
            assertTrue("$tag exceeds the vertical viewport", child.top >= frame.top - 1f && child.bottom <= frame.bottom + 1f)
        }
    }

    private fun assertTabletPortraitControlsFit() {
        val frame = bounds("viewport")
        listOf("topBar", "cover", "identity", "progress", "controls", "toolbar", "nowPlayingTabletPortraitLyrics").forEach { tag ->
            val child = bounds(tag)
            assertTrue("$tag has no measured size", child.width > 0f && child.height > 0f)
            assertTrue("$tag exceeds the horizontal viewport", child.left >= frame.left - 1f && child.right <= frame.right + 1f)
            assertTrue("$tag exceeds the vertical viewport", child.top >= frame.top - 1f && child.bottom <= frame.bottom + 1f)
        }
        assertTrue(bounds("progress").width <= logicalPixels(440.dp) + 1f)
        assertTrue(bounds("controls").width <= logicalPixels(440.dp) + 1f)
        assertTrue(bounds("toolbar").width <= logicalPixels(400.dp) + 1f)
        composeRule.onNodeWithTag("nowPlayingWideLayout").assertDoesNotExist()
    }

    private fun assertPhoneLandscapeArrangement(minimumLyricHeight: Dp) {
        val frame = bounds("viewport")
        val player = bounds("nowPlayingPlayerPane")
        val details = bounds("nowPlayingDetailsPane")
        val identity = bounds("identity")
        val actions = bounds("topActions")
        val lyrics = bounds("nowPlayingLyricPane")
        val footer = bounds("nowPlayingBottomControls")
        val progress = bounds("progress")
        val controls = bounds("controls")
        assertTrue(details.left > player.right)
        assertTrue(identity.right < actions.left)
        assertEquals(details.right, actions.right, 1f)
        assertTrue(lyrics.top >= maxOf(identity.bottom, actions.bottom))
        assertTrue(lyrics.height >= logicalPixels(minimumLyricHeight) - 1f)
        assertTrue(lyrics.bottom <= footer.top)
        assertEquals(details.left, footer.left, 1f)
        assertEquals(details.right, footer.right, 1f)
        assertEquals(frame.bottom, footer.bottom, 1f)
        assertEquals(progress.bottom, controls.top, 1f)
        assertTrue(progress.left >= details.left && controls.left >= details.left)
        val toolbar = bounds("toolbar")
        assertTrue(toolbar.left >= player.left && toolbar.right <= player.right)
        assertTrue(toolbar.top >= bounds("cover").bottom)
        assertEquals(frame.bottom, toolbar.bottom, 1f)
    }

    private fun assertSquareCover() {
        val cover = bounds("cover")
        assertEquals(cover.width, cover.height, 1f)
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun logicalPixels(dp: Dp): Float = bounds("viewport").width / logicalViewportWidth * dp.value
}

@androidx.compose.runtime.Composable
private fun LayoutSlot(tag: String, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height).testTag(tag))
}
