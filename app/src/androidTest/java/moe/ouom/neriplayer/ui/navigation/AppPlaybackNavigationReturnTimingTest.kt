package moe.ouom.neriplayer.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.playback.visual.AppNowPlayingOverlay
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPlaybackNavigationReturnTimingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val playbackOpen = mutableStateOf(false)
    private val overlayMounted = mutableStateOf(false)
    private val queue = MutableStateFlow<List<SongItem>>(emptyList())
    private var miniPlayerExpansionCount = 0

    @Before
    fun prepareHost() {
        assumeComposeHostAvailable()
        assertTrue("测试 Application 应已初始化播放器依赖", AppContainer.isInitialized())
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun phoneNavigationStartsReturningWhilePlayerOverlayIsStillMounted() {
        UiFailureDiagnostics.onFailure("phone-playback-navigation-return") {
            render(smallestScreenWidthDp = 360, widthDp = 360, heightDp = 780)
            val initialTabs = rawBounds(BottomNavigationTag)
            val initialMini = rawBounds(MiniPlayerTag)
            openFromMiniPlayer()
            val hiddenTabs = rawBounds(BottomNavigationTag)
            assertTrue("打开播放器应将底栏移出原位置", hiddenTabs.top > initialTabs.top + PixelTolerance)

            composeRule.runOnIdle { playbackOpen.value = false }
            advanceBy(EarlyReturnTimeMs)

            assertOverlayMounted("手机退出 128ms 时播放器仍应处于退出动画")
            val returningTabs = rawBounds(BottomNavigationTag)
            assertTrue(
                "手机底栏应在播放器退出期间上移，而不是等浮层卸载: hidden=$hiddenTabs returning=$returningTabs",
                returningTabs.top < hiddenTabs.top - PixelTolerance
            )
            assertTrue("128ms 时底栏仍应处于恢复动画", returningTabs.top > initialTabs.top + PixelTolerance)
            composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsDisplayed()

            advanceBy(CompleteReturnTimeMs - EarlyReturnTimeMs)

            composeRule.runOnIdle { assertFalse("320ms 时播放器退出动画应已结束", overlayMounted.value) }
            assertRestored(initialTabs, initialMini)
        }
    }

    @Test
    fun tabletNavigationKeepsWaitingForOverlayDisposalBeforeReturning() {
        UiFailureDiagnostics.onFailure("tablet-playback-navigation-return") {
            // 使用竖屏平板底栏，避免侧栏规则改变被测动画
            render(smallestScreenWidthDp = 800, widthDp = 800, heightDp = 1280)
            val initialTabs = rawBounds(BottomNavigationTag)
            val initialMini = rawBounds(MiniPlayerTag)
            openFromMiniPlayer()
            val hiddenTabs = rawBounds(BottomNavigationTag)

            composeRule.runOnIdle { playbackOpen.value = false }
            advanceBy(EarlyReturnTimeMs)

            assertOverlayMounted("平板退出期间应继续抑制底栏和 MiniPlayer")
            assertEquals("平板浮层仍挂载时不应开始恢复底栏", hiddenTabs.top, rawBounds(BottomNavigationTag).top, PixelTolerance)
            composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertDoesNotExist()

            advanceBy(CompleteReturnTimeMs - EarlyReturnTimeMs)

            composeRule.runOnIdle { assertFalse("320ms 时平板浮层应已卸载", overlayMounted.value) }
            val returningTabs = rawBounds(BottomNavigationTag)
            assertTrue("平板应保留原恢复时序", returningTabs.top > initialTabs.top + PixelTolerance)

            advanceBy(400L)
            assertRestored(initialTabs, initialMini)
        }
    }

    private fun render(smallestScreenWidthDp: Int, widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                this.smallestScreenWidthDp = smallestScreenWidthDp
                screenWidthDp = widthDp
                screenHeightDp = heightDp
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    FittedTestViewport(width = widthDp.dp, height = heightDp.dp) {
                        Box(Modifier.fillMaxSize().testTag(ViewportTag)) {
                            AppNavigationScaffold(
                                bottomBar = AppBottomBarPresentation(
                                    items = listOf(
                                        Destinations.Home to Icons.Outlined.Home,
                                        Destinations.Library to Icons.Outlined.LibraryMusic
                                    ),
                                    currentDestination = null,
                                    showNowPlaying = shouldSuppressPlaybackNavigation(
                                        playbackOpen = playbackOpen.value,
                                        overlayMounted = overlayMounted.value,
                                        smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp
                                    ),
                                    offlineMode = true,
                                    alwaysUseNewTabStyle = false,
                                    backgroundImageUri = null
                                ),
                                miniPlayer = AppMiniPlayerPresentation(
                                    song = FixtureSong,
                                    coverUrl = null,
                                    visualCoverUrl = null,
                                    songVisualKey = null,
                                    visualCoverSongKey = null,
                                    enableBlur = false
                                ),
                                baseBlurRequested = false,
                                snackbarHostState = remember { SnackbarHostState() },
                                onMainTabSelected = {},
                                onExpandNowPlaying = {
                                    miniPlayerExpansionCount++
                                    playbackOpen.value = true
                                }
                            ) {
                                Box(Modifier.fillMaxSize().background(Color(0xFFE5E7ED)))
                            }
                            AppNowPlayingOverlay(
                                visible = playbackOpen.value,
                                cover = NowPlayingOverlayCover(null, null, null, 0),
                                queueFlow = queue,
                                theme = NowPlayingOverlayTheme(
                                    false, null, "6750A4", PaletteStyle.TonalSpot,
                                    ColorSpec.SpecVersion.Default
                                ),
                                background = NowPlayingOverlayBackground(false, 0f, 0f, false, true),
                                onVisibilityChanged = { overlayMounted.value = it },
                                onClose = { playbackOpen.value = false }
                            ) {
                                Box(Modifier.fillMaxSize().background(Color(0xFF212936)).testTag(PlayerContentTag)) {
                                    Text("本地播放器动画测试")
                                }
                            }
                        }
                    }
                }
            }
        }
        advanceBy(400L)
        composeRule.onNodeWithTag(BottomNavigationTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun openFromMiniPlayer() {
        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).performTouchInput {
            click(Offset(width * 0.04f, center.y))
        }
        advanceBy(400L)
        composeRule.runOnIdle {
            assertEquals("应由真实 MiniPlayer 点击打开播放浮层", 1, miniPlayerExpansionCount)
            assertTrue(playbackOpen.value)
            assertTrue(overlayMounted.value)
        }
        composeRule.onNodeWithTag(PlayerContentTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun advanceBy(milliseconds: Long) {
        composeRule.mainClock.advanceTimeBy(milliseconds)
        composeRule.waitForIdle()
    }

    private fun assertOverlayMounted(message: String) {
        composeRule.runOnIdle { assertTrue(message, overlayMounted.value) }
        composeRule.onNodeWithTag(PlayerContentTag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertRestored(initialTabs: Rect, initialMini: Rect) {
        composeRule.onNodeWithTag(BottomNavigationTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsDisplayed()
        val restoredTabs = rawBounds(BottomNavigationTag)
        val restoredMini = rawBounds(MiniPlayerTag)
        assertEquals("底栏应已回到原位置，无第二段恢复动画", initialTabs.top, restoredTabs.top, PixelTolerance)
        assertEquals("底栏实际高度应保持", initialTabs.height, restoredTabs.height, PixelTolerance)
        assertEquals("MiniPlayer 应已回到原位置", initialMini.top, restoredMini.top, PixelTolerance)
        assertEquals("MiniPlayer 底边应恢复", initialMini.bottom, restoredMini.bottom, PixelTolerance)
    }

    private fun rawBounds(tag: String): Rect {
        // 浮层退出期间底栏仍可被裁剪，使用未裁剪的真实像素坐标判断位移
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val origin = node.positionInRoot
        return Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
    }

    private companion object {
        const val BottomNavigationTag = "appBottomNavigation"
        const val MiniPlayerTag = "appMiniPlayer"
        const val PlayerContentTag = "navigationReturnPlayerContent"
        const val ViewportTag = "navigationReturnViewport"
        const val EarlyReturnTimeMs = 128L
        const val CompleteReturnTimeMs = 320L
        const val PixelTolerance = 1f
        val FixtureSong = SongItem(
            id = -49_300L,
            name = "Navigation return local fixture",
            artist = "Synthetic artist",
            album = "Local UI fixture",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null
        )
    }
}
