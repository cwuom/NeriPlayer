package moe.ouom.neriplayer.ui

import android.content.res.Configuration
import android.util.Log
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
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
class AppNowPlayingDeviceTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val visible = mutableStateOf(false)
    private val smallestWidth = mutableIntStateOf(800)
    private val windowWidth = mutableIntStateOf(800)
    private val flight = SharedCoverFlightState()
    private val queue = MutableStateFlow<List<SongItem>>(emptyList())
    private var viewportLayout = "not placed"
    private var playerLayout = "not placed"
    private lateinit var backDispatcher: OnBackPressedDispatcher

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun cleanTabletUsesTheDefaultTransitionWhenRapidlyReopened() {
        render(smallestScreenWidthDp = 800, widthDp = 800)
        composeRule.runOnIdle { visible.value = true }
        advance(64)
        composeRule.runOnIdle { assertFalse("平板开场不应交给容器形变", flight.linked) }
        composeRule.runOnIdle { visible.value = false }
        advance(64)
        composeRule.runOnIdle { visible.value = true }
        advance(400)
        assertPlayerDisplayed("tablet-reopened")
        composeRule.runOnIdle {
            assertFalse(flight.linked)
            assertEquals(0f, flight.nowPlayingMorphProgress, 0f)
            visible.value = false
        }
        advance(400)
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun materialTabletOnlySlidesVerticallyAndKeepsTheClassicTransitionWhenReopened() {
        render(smallestScreenWidthDp = 800, widthDp = 1280, style = UiStyle.Material)
        composeRule.runOnIdle { visible.value = true }
        advance(128)
        assertClassicTabletFrame(inMotion = true)
        composeRule.runOnIdle { visible.value = false }
        advance(64)
        composeRule.runOnIdle { visible.value = true }
        advance(1_500)
        assertPlayerDisplayed("material-tablet-reopened")
        assertClassicTabletFrame(inMotion = false)
        composeRule.runOnIdle { backDispatcher.onBackPressed() }
        advance(128)
        assertClassicTabletFrame(inMotion = true)
        advance(192)
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun materialPhoneUsesTheContainerMorphAndReleasesItAfterRapidReopen() {
        render(smallestScreenWidthDp = 390, widthDp = 844, style = UiStyle.Material)
        composeRule.runOnIdle { visible.value = true }
        advance(128)
        composeRule.runOnIdle {
            assertTrue("Material You 手机应与简洁主题共用容器展开", flight.linked)
            assertTrue("开场应仍连续展开: progress=${flight.nowPlayingMorphProgress}",
                flight.nowPlayingMorphProgress > 0f && flight.nowPlayingMorphProgress < 1f)
            assertFalse("容器展开不能同时启动旧封面动画", flight.flying)
            visible.value = false
        }
        advance(64)
        composeRule.runOnIdle { visible.value = true }
        advance(1_500)
        assertPlayerDisplayed("material-phone-reopened")
        composeRule.runOnIdle {
            assertFalse(flight.linked)
            assertFalse(flight.flying)
            assertEquals(1f, flight.nowPlayingMorphProgress, 0.001f)
            visible.value = false
        }
        advance(320)
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle {
            assertFalse(flight.linked)
            assertEquals(0f, flight.nowPlayingMorphProgress, 0f)
        }
    }

    @Test
    fun materialPhoneCancelsBackPreviewAndCanReopenDuringACommittedClose() {
        val previewProgress = 0.6f
        render(smallestScreenWidthDp = 390, widthDp = 844, style = UiStyle.Material)
        composeRule.runOnIdle { visible.value = true }
        advance(1_500)
        composeRule.runOnIdle { backDispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { backDispatcher.dispatchOnBackProgressed(backEvent(previewProgress)) }
        advance(64)
        composeRule.runOnIdle {
            // 慢起曲线将 0.6 的手势映射到约 0.43882，再推动 40% 的关闭预览
            assertEquals(0.82447f, flight.nowPlayingMorphProgress, 0.001f)
            assertTrue("预测返回预览应比线性映射慢起",
                flight.nowPlayingMorphProgress > 1f - previewProgress * 0.4f)
            assertTrue(flight.linked)
            backDispatcher.dispatchOnBackCancelled()
        }
        advance(320)
        composeRule.runOnIdle {
            assertTrue(visible.value)
            assertEquals(1f, flight.nowPlayingMorphProgress, 0.001f)
            assertFalse(flight.linked)
        }
        composeRule.runOnIdle { backDispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { backDispatcher.dispatchOnBackProgressed(backEvent(previewProgress)) }
        advance(64)
        composeRule.runOnIdle { backDispatcher.onBackPressed() }
        advance(64)
        composeRule.runOnIdle {
            assertFalse(visible.value)
            visible.value = true
        }
        advance(1_500)
        assertPlayerDisplayed("material-phone-back-reopened")
        composeRule.runOnIdle {
            assertEquals(1f, flight.nowPlayingMorphProgress, 0.001f)
            assertFalse(flight.linked)
            backDispatcher.onBackPressed()
        }
        advance(320)
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(flight.linked) }
    }

    @Test
    fun cleanLandscapePhoneKeepsItsContainerMorph() {
        render(smallestScreenWidthDp = 390, widthDp = 844)
        composeRule.runOnIdle { visible.value = true }
        advance(64)
        composeRule.runOnIdle { assertTrue("手机横屏仍按 smallest width 选择容器形变", flight.linked) }
        advance(1_500)
        assertPlayerDisplayed("landscape-phone-open")
        composeRule.runOnIdle { assertFalse(flight.linked) }
    }

    @Test
    fun crossingTheTabletBoundaryDisposesTheActiveMorphEvenInANarrowWindow() {
        render(smallestScreenWidthDp = 599, widthDp = 599)
        composeRule.runOnIdle { visible.value = true }
        advance(64)
        composeRule.runOnIdle {
            assertTrue(flight.linked)
            smallestWidth.intValue = 600
            windowWidth.intValue = 320
        }
        advance(400)
        assertPlayerDisplayed("tablet-boundary")
        composeRule.runOnIdle {
            assertFalse("退出手机形变分支后必须释放共享封面", flight.linked)
            assertEquals(0f, flight.nowPlayingMorphProgress, 0f)
            visible.value = false
        }
        advance(400)
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun render(smallestScreenWidthDp: Int, widthDp: Int, style: UiStyle = UiStyle.Clean) {
        smallestWidth.intValue = smallestScreenWidthDp
        windowWidth.intValue = widthDp
        composeRule.setContent {
            val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            SideEffect { backDispatcher = dispatcher }
            val configuration = Configuration(LocalConfiguration.current).apply {
                this.smallestScreenWidthDp = smallestWidth.intValue
                screenWidthDp = windowWidth.intValue
                screenHeightDp = 390
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalSharedCoverFlight provides flight,
                LocalPredictiveBackPreviewEnabled provides true
            ) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { viewportLayout = describeLayout(it) }) {
                AppNowPlayingOverlay(
                    visible = visible.value,
                    cover = NowPlayingOverlayCover(null, null, null, 0),
                    queueFlow = queue,
                    theme = NowPlayingOverlayTheme(
                        false, null, "6750A4", PaletteStyle.TonalSpot,
                        ColorSpec.SpecVersion.Default, uiStyle = style
                    ),
                    background = NowPlayingOverlayBackground(false, 0f, 0f, false, true),
                    onVisibilityChanged = {},
                    onClose = { visible.value = false }
                ) {
                    Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag(PlayerContent)
                        .onGloballyPositioned { playerLayout = describeLayout(it) })
                }
                }
            }
        }
        advance(32)
    }

    private fun advance(milliseconds: Long) {
        composeRule.mainClock.advanceTimeBy(milliseconds)
        composeRule.waitForIdle()
    }

    private fun backEvent(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    private fun assertClassicTabletFrame(inMotion: Boolean) {
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val player = composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("经典转场不应横向移动", root.positionInRoot.x, player.positionInRoot.x, 1f)
        assertEquals("经典转场不应缩放页面宽度", root.size.width, player.size.width)
        assertEquals("经典转场不应缩放页面高度", root.size.height, player.size.height)
        val offsetY = player.positionInRoot.y - root.positionInRoot.y
        if (inMotion) {
            assertTrue("开关中途仍应保留纵向滑动: y=$offsetY", offsetY > 0f && offsetY < root.size.height)
        } else {
            assertEquals(0f, offsetY, 1f)
        }
        composeRule.runOnIdle {
            assertFalse(flight.linked)
            assertFalse(flight.flying)
            assertFalse(flight.hidesCovers)
            assertEquals(0f, flight.nowPlayingMorphProgress, 0f)
        }
    }

    private fun assertPlayerDisplayed(stage: String) {
        try {
            // 遮挡底层触摸的 clickable 会合并子语义，内容标签只保留在未合并的树中
            composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertIsDisplayed()
        } catch (failure: AssertionError) {
            Log.e(DiagnosticsTag, "$stage time=${composeRule.mainClock.currentTime} visible=${visible.value} " +
                "linked=${flight.linked} morph=${flight.nowPlayingMorphProgress} " +
                "smallestWidth=${smallestWidth.intValue} windowWidth=${windowWidth.intValue} " +
                "viewport=[$viewportLayout] player=[$playerLayout]")
            runCatching {
                val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
                val player = composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).fetchSemanticsNode()
                Log.e(DiagnosticsTag, "root bounds=${root.boundsInRoot} window=${root.boundsInWindow} " +
                    "placed=${root.layoutInfo.isPlaced}; player bounds=${player.boundsInRoot} " +
                    "window=${player.boundsInWindow} placed=${player.layoutInfo.isPlaced}")
                composeRule.onRoot(useUnmergedTree = true).printToLog(DiagnosticsTag)
            }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            UiFailureDiagnostics.record("now-playing-transition-$stage", failure)
            throw failure
        }
    }

    private fun describeLayout(coordinates: LayoutCoordinates): String =
        "size=${coordinates.size} attached=${coordinates.isAttached} root=${coordinates.positionInRoot()} " +
            "window=${coordinates.positionInWindow()} boundsRoot=${coordinates.boundsInRoot()} " +
            "boundsWindow=${coordinates.boundsInWindow()} parentSize=${coordinates.parentLayoutCoordinates?.size}"

    private companion object {
        const val PlayerContent = "deviceTransitionPlayer"
        const val DiagnosticsTag = "NowPlayingTransition"
    }
}
