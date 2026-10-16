package moe.ouom.neriplayer.ui.screen

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.activity.AppUiDensityRoot
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingTabletHighDensityLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private var unscaledDensity = 0f
    private var playerDensity = 0f
    private var playerFontScale = 0f
    private var logicalWindow = DpSize.Unspecified
    private var backCallbacks = 0

    @Before
    fun setUp() = assumeComposeHostAvailable()

    @Test
    fun narrowTabletLandscapeAtTwoTimesKeepsPlaybackAndToolbarActionsReachable() =
        assertActionsReachable(width = 800, height = 600)

    @Test
    fun tabletLandscapeJustBelowTheOldWideThresholdKeepsItsActionsReachable() =
        assertActionsReachable(width = 958, height = 600)

    @Test
    fun tabletLandscapeAtTheOldWideThresholdKeepsItsActionsReachable() =
        assertActionsReachable(width = 960, height = 600)

    @Test
    fun narrowTabletLandscapeAtTwoTimesKeepsLargeFontAndActionsInsideTheViewport() =
        assertActionsReachable(width = 800, height = 600, fontScale = 1.5f)

    private fun assertActionsReachable(width: Int, height: Int, fontScale: Float = 1f) =
        UiFailureDiagnostics.onFailure("now-playing-tablet-2x-${width}x$height-font-$fontScale") {
            render(width, height, fontScale)
            composeRule.runOnIdle {
                assertEquals(unscaledDensity * 2f, playerDensity, 0.001f)
                assertEquals(fontScale, playerFontScale, 0.001f)
                assertEquals(width / 2f, logicalWindow.width.value, 1f)
                assertEquals(height / 2f, logicalWindow.height.value, 1f)
            }
            val viewport = composeRule.onNodeWithTag(ViewportTag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            Log.i(DiagnosticsTag, "raw=${width}x$height sw=600 effective=$logicalWindow viewport=$viewport " +
                "density=$playerDensity base=$unscaledDensity fontScale=$playerFontScale")

            listOf(CoreCommonR.string.player_previous, CoreCommonR.string.player_next,
                CoreCommonR.string.player_shuffle, CoreCommonR.string.player_repeat).forEach { resource ->
                assertFullyVisible(button(resource), viewport)
            }
            val transport = hasContentDescription(label(CoreCommonR.string.player_play)) or
                hasContentDescription(label(CoreCommonR.string.player_pause)) or
                hasContentDescription(label(CoreCommonR.string.player_restore_volume)) or
                hasContentDescription(label(CoreCommonR.string.player_waiting))
            assertFullyVisible(composeRule.onNode(transport and hasClickAction())
                .assertHasClickAction().assertIsEnabled(), viewport)
            listOf(CoreCommonR.string.playlist_queue, CoreCommonR.string.sleep_timer_short).forEach { resource ->
                assertFullyVisible(button(resource), viewport)
            }

            // 只打开并关闭本地对话框，不触发播放、队列或设置写入
            button(CoreCommonR.string.sleep_timer_short).performTouchInput { click() }
            composeRule.onNodeWithText(label(CoreCommonR.string.sleep_timer_title)).assertIsDisplayed()
            composeRule.onNodeWithText(label(CoreCommonR.string.sleep_timer_close))
                .assertIsDisplayed().performTouchInput { click() }
            val back = button(CoreCommonR.string.action_back)
            assertFullyVisible(back, viewport)
            back.performTouchInput { click() }
            composeRule.runOnIdle { assertEquals(1, backCallbacks) }
        }

    private fun render(width: Int, height: Int, fontScale: Float) {
        composeRule.setContent {
            val configuration = LocalConfiguration.current
            val tablet = remember(configuration, width, height, fontScale) {
                Configuration(configuration).apply {
                    screenWidthDp = width
                    screenHeightDp = height
                    smallestScreenWidthDp = 600
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    uiMode = Configuration.UI_MODE_TYPE_NORMAL
                    this.fontScale = fontScale
                }
            }
            CompositionLocalProvider(LocalConfiguration provides tablet) {
                FittedTestViewport(width.dp, height.dp, fontScale = fontScale) {
                    val baseDensity = LocalDensity.current
                    val actualWindow = LocalWindowInfo.current
                    val simulatedSize = with(baseDensity) { IntSize(width.dp.roundToPx(), height.dp.roundToPx()) }
                    val window = remember(actualWindow, simulatedSize, width, height) {
                        object : WindowInfo by actualWindow {
                            override val containerSize = simulatedSize
                            override val containerDpSize = DpSize(width.dp, height.dp)
                        }
                    }
                    CompositionLocalProvider(LocalWindowInfo provides window) {
                        AppUiDensityRoot(2f) {
                            val density = LocalDensity.current
                            SideEffect {
                                unscaledDensity = baseDensity.density
                                playerDensity = density.density
                                playerFontScale = density.fontScale
                                logicalWindow = with(density) {
                                    DpSize(window.containerSize.width.toDp(), window.containerSize.height.toDp())
                                }
                            }
                            NeriTheme(
                                followSystemDark = false,
                                forceDark = false,
                                dynamicColor = false,
                                seedColorHex = "6750A4",
                                uiStyle = UiStyle.Clean
                            ) {
                                Box(Modifier.fillMaxSize().testTag(ViewportTag)) {
                                    NowPlayingScreen(
                                        onNavigateUp = { backCallbacks++ },
                                        showLyricsScreen = false,
                                        onShowLyricsScreenChange = {},
                                        onEnterAlbum = {},
                                        lyricBlurEnabled = false,
                                        lyricBlurAmount = 2.5f,
                                        lyricFontScales = LyricFontScales(1f, 1f, 1f, 1f),
                                        onLyricFontScaleChange = { _, _ -> },
                                        advancedLyricsEnabled = false,
                                        offlineMode = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun button(resource: Int): SemanticsNodeInteraction = composeRule.onNode(
        hasContentDescription(label(resource)) and hasClickAction()
    ).assertHasClickAction().assertIsEnabled()

    private fun assertFullyVisible(node: SemanticsNodeInteraction, viewport: androidx.compose.ui.unit.DpRect) {
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("操作必须有非零宽高: bounds=$bounds viewport=$viewport",
            bounds.right - bounds.left > 0.dp && bounds.bottom - bounds.top > 0.dp)
        assertTrue("操作必须完整位于有效视窗: bounds=$bounds viewport=$viewport",
            bounds.left >= viewport.left - 1.dp && bounds.right <= viewport.right + 1.dp &&
                bounds.top >= viewport.top - 1.dp && bounds.bottom <= viewport.bottom + 1.dp)
    }

    private fun label(resource: Int): String = composeRule.activity.getString(resource)

    private companion object {
        const val ViewportTag = "now-playing-tablet-density-viewport"
        const val DiagnosticsTag = "NowPlayingTabletDpi"
    }
}
