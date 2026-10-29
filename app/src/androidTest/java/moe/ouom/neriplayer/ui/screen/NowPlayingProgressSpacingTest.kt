package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressInfoSegment
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressInfoPresentation
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingProgressSection
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingTabletPortraitLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingWideLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingAdaptiveCoverSize
import moe.ouom.neriplayer.ui.screen.nowplaying.isNowPlayingPhoneLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingVisibleProgressInfoSegments
import moe.ouom.neriplayer.ui.screen.nowplaying.shouldReserveNowPlayingProgressInfoSpace
import moe.ouom.neriplayer.ui.theme.CleanTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingProgressSpacingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var viewportWidth = 0f
    private var songKey by mutableStateOf("spacing-fixture")
    private var informationEnabled by mutableStateOf(true)
    private var information by mutableStateOf(listOf(
        NowPlayingProgressInfoSegment("FLAC"),
        NowPlayingProgressInfoSegment("96 kHz | 24 bit")
    ))

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tablet1280x800_lowerProgressHasRoomAboveInformationAndControls() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.LOWER)

        assertTabletInformationFits()
        assertEquals(pixels(12.dp), bounds("progressRow").top - bounds("identity").bottom, 1f)
        assertEquals(pixels(13.dp), bounds("controls").top - informationRowBounds().bottom, 1f)
        assertTrue(bounds("controls").bottom < bounds("toolbar").top - pixels(24.dp))
        assertTrue(bounds("cover").height >= pixels(350.dp) - 1f)
    }

    @Test
    fun tablet1280x800_bottomControlsRetainTheIndependentProgressPosition() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM)

        assertTabletInformationFits()
        assertEquals(pixels(12.dp), bounds("progressRow").top - bounds("identity").bottom, 1f)
        assertTrue(bounds("controls").top > informationTextBounds().bottom + pixels(24.dp))
        assertEquals(pixels(8.dp), bounds("toolbar").top - bounds("controls").bottom, 1f)
        assertTrue(bounds("cover").height >= pixels(350.dp) - 1f)
    }

    @Test
    fun tablet1280x800_bottomProgressKeepsInformationSeparateFromPlaybackButtons() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

        assertTabletInformationFits()
        assertTrue(bounds("progressRow").top > bounds("identity").bottom + pixels(24.dp))
        assertEquals(pixels(13.dp), bounds("controls").top - informationRowBounds().bottom, 1f)
        assertEquals(pixels(8.dp), bounds("toolbar").top - bounds("controls").bottom, 1f)
        assertTrue(bounds("cover").height >= pixels(350.dp) - 1f)
    }

    @Test
    fun shortTablet1280x480_doesNotMoveProgressIntoTheIdentity() {
        renderLayout(1280.dp, 480.dp, NowPlayingControlPlacement.LOWER)

        assertTabletInformationFits()
        assertEquals(bounds("identity").bottom, bounds("progressRow").top, 1f)
        assertEquals(pixels(5.dp), bounds("controls").top - informationRowBounds().bottom, 1f)
        assertTrue(bounds("nowPlayingLyricPane").height >= pixels(240.dp))
        val cover = bounds("cover")
        val playerPane = bounds("nowPlayingPlayerPane")
        assertEquals("短横屏封面应使用左栏宽度的 72%", playerPane.width * 0.72f, cover.width, 1f)
        assertEquals("短横屏封面应保持正方形", cover.width, cover.height, 1f)
        assertEquals("短横屏封面应在左栏居中", playerPane.center.x, cover.center.x, 1f)
        assertTrue("封面不能挤占顶部操作", cover.top >= bounds("topBar").bottom - 1f)
        assertTrue("封面不能挤占工具栏", cover.bottom <= bounds("toolbar").top + 1f)
    }

    @Test
    fun tablet1280x800_largeTextAndControlsRetainInformationAndCoverSpace() {
        renderLayout(
            1280.dp, 800.dp, NowPlayingControlPlacement.LOWER,
            fontScale = 1.5f, largeControls = true
        )

        assertTabletInformationFits()
        assertEquals(pixels(13.dp), bounds("controls").top - informationRowBounds().bottom, 1f)
        assertTrue(bounds("cover").height >= pixels(320.dp))
        assertTrue(bounds("nowPlayingLyricPane").height >= pixels(700.dp))
    }

    @Test
    fun tablet1280x800_largeTextKeepsBottomControlsSeparateFromInformation() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM,
            fontScale = 1.5f, largeControls = true)

        assertTabletInformationFits()
        assertTrue(bounds("progressRow").top >= bounds("identity").bottom)
        assertTrue(bounds("controls").top > informationTextBounds().bottom + pixels(24.dp))
        assertEquals(pixels(8.dp), bounds("toolbar").top - bounds("controls").bottom, 1f)
    }

    @Test
    fun tablet1280x800_largeTextKeepsBottomProgressClearOfTheTrackIdentity() {
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS,
            fontScale = 1.5f, largeControls = true)

        assertTabletInformationFits()
        assertTrue(bounds("progressRow").top > bounds("identity").bottom + pixels(24.dp))
        assertEquals(pixels(13.dp), bounds("controls").top - informationRowBounds().bottom, 1f)
        assertEquals(pixels(8.dp), bounds("toolbar").top - bounds("controls").bottom, 1f)
    }

    @Test
    fun phone840x360_retainsTheFooterAndHidesAudioInformation() {
        renderLayout(
            840.dp, 360.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS,
            smallestScreenWidthDp = 360
        )

        composeRule.onNodeWithText("FLAC").assertDoesNotExist()
        composeRule.onNodeWithText("96 kHz | 24 bit").assertDoesNotExist()
        assertEquals(bounds("progressRow").bottom, bounds("controls").top, 1f)
        assertEquals(bounds("viewport").bottom, bounds("controls").bottom, 1f)
        assertTrue(bounds("nowPlayingLyricPane").height >= pixels(200.dp) - 1f)
    }

    @Test
    fun tablet800x1280_portraitRetainsItsInformationAndControlSpacing() {
        renderLayout(800.dp, 1280.dp, NowPlayingControlPlacement.LOWER)

        composeRule.onNodeWithText("FLAC").assertIsDisplayed()
        assertEquals(pixels(-6.dp), informationRowBounds().top - bounds("progressRow").bottom, 1f)
        assertEquals(pixels(16.dp), bounds("controls").top - informationRowBounds().bottom, 1f)
        assertTrue(bounds("cover").width <= pixels(336.dp) + 1f)
        assertTrue(bounds("nowPlayingTabletPortraitLyrics").height >= pixels(400.dp))
    }

    @Test
    fun tabletLowerCoverKeepsItsBoundsWhileNextTrackInformationLoads() =
        assertTrackSwitchKeepsCoverBounds(1280.dp, 800.dp, NowPlayingControlPlacement.LOWER)

    @Test
    fun tabletBottomControlsCoverKeepsItsBoundsWhileNextTrackInformationLoads() =
        assertTrackSwitchKeepsCoverBounds(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM)

    @Test
    fun tabletBottomProgressCoverKeepsItsBoundsWhileNextTrackInformationLoads() =
        assertTrackSwitchKeepsCoverBounds(1280.dp, 800.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

    @Test
    fun tabletLargeTextCoverKeepsItsBoundsWhileNextTrackInformationLoads() =
        assertTrackSwitchKeepsCoverBounds(1280.dp, 800.dp, NowPlayingControlPlacement.LOWER, fontScale = 1.5f)

    @Test
    fun cleanTabletChineseInformationKeepsCoverSizeDuringTrackSwitch() {
        information = listOf(NowPlayingProgressInfoSegment("极高", highlighted = true),
            NowPlayingProgressInfoSegment("MP3"), NowPlayingProgressInfoSegment("320 kbps"))
        assertTrackSwitchKeepsCoverBounds(1280.dp, 800.dp, NowPlayingControlPlacement.LOWER,
            fontScale = 1.5f, cleanTheme = true)
    }

    @Test
    fun shortTabletCoverKeepsItsBoundsWhileNextTrackInformationLoads() =
        assertTrackSwitchKeepsCoverBounds(1280.dp, 480.dp, NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)

    @Test
    fun tabletPortraitCoverKeepsItsBoundsWhileNextTrackInformationLoads() =
        assertTrackSwitchKeepsCoverBounds(800.dp, 1280.dp, NowPlayingControlPlacement.LOWER)

    @Test
    fun phoneLandscapeCoverKeepsItsBoundsWhileNextTrackInformationLoads() =
        assertTrackSwitchKeepsCoverBounds(840.dp, 360.dp, NowPlayingControlPlacement.LOWER,
            smallestScreenWidthDp = 360)

    @Test
    fun disabledAudioInformationDoesNotLeaveAnEmptyRow() {
        information = emptyList()
        informationEnabled = false
        renderLayout(1280.dp, 800.dp, NowPlayingControlPlacement.LOWER)
        assertEquals(bounds("progressRow").height, bounds("progressSection").height, 1f)
        composeRule.runOnIdle { informationEnabled = true }
        composeRule.waitForIdle()
        assertTrue(bounds("progressSection").height > bounds("progressRow").height)
        composeRule.runOnIdle { informationEnabled = false }
        composeRule.waitForIdle()
        assertEquals(bounds("progressRow").height, bounds("progressSection").height, 1f)
    }

    private fun assertTrackSwitchKeepsCoverBounds(
        width: Dp,
        height: Dp,
        placement: NowPlayingControlPlacement,
        smallestScreenWidthDp: Int = 800,
        fontScale: Float = 1f,
        cleanTheme: Boolean = false
    ) {
        renderLayout(width, height, placement, smallestScreenWidthDp, fontScale, cleanTheme = cleanTheme)
        val settledCover = bounds("cover")
        val settledProgress = bounds("progressSection")
        repeat(2) { index ->
            composeRule.runOnIdle {
                songKey = "next-track-$index"
                information = emptyList()
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("FLAC").assertDoesNotExist()
            composeRule.onNodeWithText("极高").assertDoesNotExist()
            assertCoverBounds(settledCover)
            assertEquals("加载信息时进度区不能塌陷", settledProgress.height, bounds("progressSection").height, 1f)

            composeRule.runOnIdle {
                information = listOf(NowPlayingProgressInfoSegment("MP3"), NowPlayingProgressInfoSegment("320 kbps"))
            }
            composeRule.waitForIdle()
            assertCoverBounds(settledCover)
            assertEquals(settledProgress.height, bounds("progressSection").height, 1f)
        }
    }

    private fun assertCoverBounds(expected: Rect) {
        val actual = bounds("cover")
        assertEquals("切歌时封面宽度不能变化", expected.width, actual.width, 1f)
        assertEquals("切歌时封面高度不能变化", expected.height, actual.height, 1f)
        assertEquals("切歌时封面位置不能变化", expected.top, actual.top, 1f)
    }

    private fun renderLayout(
        width: Dp,
        height: Dp,
        placement: NowPlayingControlPlacement,
        smallestScreenWidthDp: Int = 800,
        fontScale: Float = 1f,
        largeControls: Boolean = false,
        cleanTheme: Boolean = false
    ) {
        viewportWidth = width.value
        val landscape = width > height
        val phoneLandscape = isNowPlayingPhoneLandscape(landscape, smallestScreenWidthDp)
        composeRule.setContent {
            MaterialTheme(typography = if (cleanTheme) CleanTypography else Typography()) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val hostDensity = LocalDensity.current
                    val viewportScale = minOf(maxWidth.value / width.value, maxHeight.value / height.value)
                    CompositionLocalProvider(
                        LocalDensity provides Density(hostDensity.density * viewportScale, fontScale)
                    ) {
                        val progress: @Composable () -> Unit = {
                            NowPlayingProgressSection(
                                songKey = songKey,
                                durationMs = 60_000L,
                                lyrics = emptyList(),
                                lyricOffsetMs = 0L,
                                isPlaying = false,
                                isPlaybackWaiting = false,
                                playbackSpeed = 1f,
                                progressInfoPresentation = NowPlayingProgressInfoPresentation(
                                    segments = nowPlayingVisibleProgressInfoSegments(information, phoneLandscape),
                                    reserveRowHeight = shouldReserveNowPlayingProgressInfoSpace(
                                        informationEnabled, false, false, 1f, phoneLandscape
                                    )
                                ),
                                seekEnabled = false,
                                activeContentColor = Color.Blue,
                                useWideLandscapeLayout = landscape,
                                onPreviewPositionChange = {},
                                tabletLandscape = landscape && !phoneLandscape,
                                modifier = Modifier.fillMaxWidth().testTag("progressSection"),
                                progressRowModifier = Modifier.testTag("progressRow")
                            )
                        }
                        val controls: @Composable () -> Unit = {
                            ProgressSpacingSlot(
                                "controls", if (phoneLandscape) 48.dp else if (largeControls) 64.dp else 52.dp
                            )
                        }
                        val identity: @Composable () -> Unit = {
                            Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("identity")) {
                                Text("歌曲名称", style = if (landscape && !phoneLandscape) {
                                    MaterialTheme.typography.headlineSmall
                                } else MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text("演唱者", style = if (landscape && !phoneLandscape) {
                                    MaterialTheme.typography.bodyLarge
                                } else MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        }
                        Box(Modifier.requiredSize(width, height).testTag("viewport")) {
                            if (landscape) {
                                NowPlayingWideLayout(
                                    controlsAtBottom = placement.placesControlsAtBottom,
                                    progressAtBottom = placement.placesProgressAtBottom,
                                    topBar = { ProgressSpacingSlot("topBar", 56.dp) },
                                    cover = { modifier ->
                                        BoxWithConstraints(modifier) {
                                            val size = resolveNowPlayingAdaptiveCoverSize(maxWidth, maxHeight, 420.dp)
                                            Box(Modifier.align(Alignment.Center).size(size).testTag("cover"))
                                        }
                                    },
                                    identity = { identity() },
                                    progress = progress,
                                    controls = controls,
                                    toolbar = { compact -> ProgressSpacingSlot("toolbar", if (compact) 58.dp else 82.dp) },
                                    lyrics = { Box(Modifier.fillMaxSize()) },
                                    modifier = Modifier.fillMaxSize(),
                                    phoneLandscape = phoneLandscape,
                                    phoneTopActions = {
                                        Box(Modifier.width(144.dp).height(56.dp).testTag("actions"))
                                    }
                                )
                            } else {
                                NowPlayingTabletPortraitLayout(
                                    controlsAtBottom = placement.placesControlsAtBottom,
                                    progressAtBottom = placement.placesProgressAtBottom,
                                    lyricsVisible = true,
                                    topBar = { ProgressSpacingSlot("topBar", 56.dp) },
                                    cover = { modifier, size ->
                                        Box(modifier, contentAlignment = Alignment.Center) {
                                            Box(Modifier.size(size).testTag("cover"))
                                        }
                                    },
                                    identity = identity,
                                    progress = progress,
                                    controls = controls,
                                    toolbar = { ProgressSpacingSlot("toolbar", 82.dp) },
                                    lyrics = { Box(Modifier.fillMaxSize()) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertTabletInformationFits() {
        composeRule.onNodeWithText("FLAC").assertIsDisplayed()
        composeRule.onNodeWithText("96 kHz | 24 bit").assertIsDisplayed()
        val frame = bounds("viewport")
        listOf(bounds("progressRow"), informationRowBounds(), informationTextBounds(), bounds("controls")).forEach { child ->
            assertTrue(child.left >= frame.left - 1f && child.right <= frame.right + 1f)
            assertTrue(child.top >= frame.top - 1f && child.bottom <= frame.bottom + 1f)
        }
        assertEquals(pixels(-1.dp), informationRowBounds().top - bounds("progressRow").bottom, 1f)
        assertTrue(informationTextBounds().bottom < bounds("controls").top)
    }

    private fun informationRowBounds(): Rect = bounds("nowPlayingProgressInfoRow")

    private fun informationTextBounds(): Rect {
        val codec = composeRule.onNodeWithText("FLAC", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val specification = composeRule.onNodeWithText("96 kHz | 24 bit", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return Rect(
            minOf(codec.left, specification.left), minOf(codec.top, specification.top),
            maxOf(codec.right, specification.right), maxOf(codec.bottom, specification.bottom)
        )
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun pixels(dp: Dp): Float = bounds("viewport").width / viewportWidth * dp.value
}

@Composable
private fun ProgressSpacingSlot(tag: String, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height).testTag(tag))
}
