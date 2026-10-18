package moe.ouom.neriplayer.ui

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.navigation.AppBottomBarPresentation
import moe.ouom.neriplayer.ui.navigation.AppMiniPlayerPresentation
import moe.ouom.neriplayer.ui.navigation.AppNavigationScaffold
import moe.ouom.neriplayer.ui.navigation.LocalBottomTabBarBoundsInRoot
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerBoundsInRoot
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-sw360dp-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNavigationScaffoldComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private var showNowPlaying by mutableStateOf(false)
    private val selectedRoutes = mutableListOf<String>()
    private lateinit var composeView: View
    private var observedImeVisible = false
    private var observedPlaybackVisible = false
    private var observedLayoutInsets = BottomBarLayoutInsets(0.dp, 0.dp, 0.dp)
    private var observedMiniPlayerHeight = 0.dp
    private var observedTabBarBounds: Rect? = null
    private var observedMiniPlayerBounds: Rect? = null
    private var observedDensity = 1f
    private var expandRequests = 0
    private val barFrames = mutableListOf<BarFrame>()

    @Test
    fun playbackOverlayHidesTheBottomBarAndClosingRestoresTabClicks() {
        showScaffold(UiStyle.Material, hasSong = true)
        val bar = composeRule.onNodeWithTag("appBottomNavigation")
        bar.assertIsDisplayed()
        clickTab(Destinations.Library)
        composeRule.waitForIdle()
        val visibleBounds = recordBarFrame("visible").bounds
        val visibleLayout = recordLayoutFrame()
        assertValidDragTargets("播放页打开前", visibleLayout, hasSong = true)

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { showNowPlaying = true }
        val exiting = advanceBarFrames(7, "exiting")
        composeRule.runOnIdle { assertTrue("播放页状态尚未提交: $barFrames", observedPlaybackVisible) }
        assertHiddenDragTargets("播放页覆盖开始后", recordLayoutFrame())
        bar.assertIsDisplayed()
        assertTrue(
            "底栏应随播放页开场向下退出，且中间态尚未完全移出: $barFrames",
            exiting.bounds.top > visibleBounds.top && exiting.bounds.top < visibleBounds.bottom
        )
        advanceBarFrames(10, "hidden")
        bar.assertIsNotDisplayed()
        assertSamePageLayout("播放页覆盖期间不能移除页面底部预留", visibleLayout, recordLayoutFrame())
        assertHiddenDragTargets("播放页覆盖期间", recordLayoutFrame())

        composeRule.runOnIdle { showNowPlaying = false }
        val entering = advanceBarFrames(7, "entering")
        composeRule.runOnIdle { assertEquals("关闭状态尚未提交: $barFrames", false, observedPlaybackVisible) }
        bar.assertIsDisplayed()
        assertNullableBoundsEqual("播放页退出时底栏拖拽目标应恢复原槽位",
            visibleLayout.tabBarBounds, recordLayoutFrame().tabBarBounds)
        assertTrue(
            "恢复过程应保留向上进入的中间态: $barFrames",
            entering.bounds.top > visibleBounds.top && entering.bounds.top < visibleBounds.bottom
        )
        val restored = advanceBarFrames(13, "restored")
        assertEquals("底栏未回到原位: $barFrames", visibleBounds.top, restored.bounds.top, 0.5f)
        assertSamePageLayout("播放页关闭后应保留原页面边界", visibleLayout, recordLayoutFrame())
        assertSameDragTargets("播放页关闭后应恢复原拖拽目标", visibleLayout, recordLayoutFrame())
        composeRule.mainClock.autoAdvance = true
        clickTab(Destinations.Home)

        composeRule.runOnIdle {
            assertEquals(listOf(Destinations.Library.route, Destinations.Home.route), selectedRoutes)
        }
    }

    @Test
    fun cleanFloatingBottomBarYieldsToRealImeInsetsAndRecoversItsNavigation() {
        showScaffold(UiStyle.Clean)
        val bar = composeRule.onNodeWithTag("appBottomNavigation")
        bar.assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false

        dispatchImeVisibility(true)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(260L)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue("真实IME insets必须到达Compose", observedImeVisible) }
        bar.assertIsNotDisplayed()

        dispatchImeVisibility(false)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(320L)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, observedImeVisible) }
        bar.assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
        clickTab(Destinations.Library)
        composeRule.runOnIdle { assertEquals(listOf(Destinations.Library.route), selectedRoutes) }
    }

    @Test
    fun cleanImeReleasesOpaquePageReserveWithoutASong() {
        assertImeLayoutContract(UiStyle.Clean, hasSong = false, baseBlurRequested = false)
    }

    @Test
    fun cleanImeReleasesOpaquePageAndMiniPlayerReserves() {
        assertImeLayoutContract(UiStyle.Clean, hasSong = true, baseBlurRequested = false)
    }

    @Test
    fun cleanImeReleasesBlurredPageReserveWithoutASong() {
        assertImeLayoutContract(UiStyle.Clean, hasSong = false, baseBlurRequested = true)
    }

    @Test
    fun cleanImeReleasesBlurredPageAndMiniPlayerReserves() {
        assertImeLayoutContract(UiStyle.Clean, hasSong = true, baseBlurRequested = true)
    }

    @Test
    fun materialImeKeepsItsPageReservesAndChromeInteractive() {
        assertImeLayoutContract(UiStyle.Material, hasSong = true, baseBlurRequested = false)
    }

    private fun assertImeLayoutContract(style: UiStyle, hasSong: Boolean, baseBlurRequested: Boolean) {
        showScaffold(style, hasSong, baseBlurRequested)
        dispatchImeVisibility(false)
        composeRule.waitForIdle()
        val bar = composeRule.onNodeWithTag("appBottomNavigation")
        bar.assertIsDisplayed()
        if (hasSong) composeRule.onNodeWithTag("appMiniPlayer").assertIsDisplayed()
        val visible = recordLayoutFrame()
        assertVisiblePageReserve(visible, style, hasSong, baseBlurRequested)
        assertValidDragTargets("IME打开前", visible, hasSong)

        composeRule.mainClock.autoAdvance = false
        lateinit var closingStart: LayoutFrame
        val withIme = try {
            dispatchImeVisibility(true)
            advanceVisibilityAnimation(260L)
            composeRule.runOnIdle { assertTrue("真实IME insets必须到达Compose", observedImeVisible) }
            if (style == UiStyle.Clean) {
                bar.assertIsNotDisplayed()
                if (hasSong) composeRule.onNodeWithTag("appMiniPlayer").assertIsNotDisplayed()
            } else {
                bar.assertIsDisplayed()
                composeRule.onNodeWithTag("appMiniPlayer").assertIsDisplayed()
                clickTab(Destinations.Home)
                clickMiniPlayerMetadata()
            }
            recordLayoutFrame()
        } finally {
            dispatchImeVisibility(false)
            // IME变化和Scaffold重新测量后，再推进一帧将布局回报传播到拖拽Local
            repeat(3) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            closingStart = recordLayoutFrame()
            composeRule.mainClock.advanceTimeBy(320L)
            composeRule.waitForIdle()
            composeRule.mainClock.autoAdvance = true
        }

        composeRule.runOnIdle { assertEquals(false, observedImeVisible) }
        bar.assertIsDisplayed()
        if (hasSong) {
            composeRule.onNodeWithTag("appMiniPlayer").assertIsDisplayed()
            clickMiniPlayerMetadata()
        }
        clickTab(Destinations.Library)
        assertTrue("布局回报采样必须仍处于入场早期", closingStart.clockMs - withIme.clockMs <= 48L)
        assertSamePageLayout("IME关闭初期完成布局回报后应恢复页面预留", visible, closingStart)
        assertValidDragTargets("IME关闭初期完成布局回报后", closingStart, hasSong)
        assertNullableBoundsEqual("IME关闭初期完成布局回报后应恢复原底栏目标",
            visible.tabBarBounds, closingStart.tabBarBounds)
        val restored = recordLayoutFrame()
        assertSamePageLayout("关闭IME后应恢复原页面预留和实际边界", visible, restored)
        assertSameDragTargets("关闭IME后应恢复原拖拽目标", visible, restored)
        assertValidDragTargets("IME关闭动画完成后", restored, hasSong)
        composeRule.runOnIdle {
            val routes = if (style == UiStyle.Material) {
                listOf(Destinations.Home.route, Destinations.Library.route)
            } else listOf(Destinations.Library.route)
            assertEquals(routes, selectedRoutes)
            assertEquals(if (!hasSong) 0 else if (style == UiStyle.Material) 2 else 1, expandRequests)
        }

        if (style == UiStyle.Clean) {
            assertHiddenDragTargets("Clean IME期间", withIme)
            assertEquals("隐藏的Clean chrome不能继续裁掉Scaffold内容槽: $withIme", 0.dp,
                withIme.insets.navContentBottomPadding)
            assertEquals("隐藏的Clean chrome不能继续预留页面底部: $withIme", 0.dp,
                withIme.insets.screenBottomInset)
            assertEquals("IME期间MiniPlayer不能继续占用底栏预留: $withIme", 0.dp,
                withIme.insets.miniPlayerBottomPadding)
            assertEquals("页面读取的LocalMiniPlayerHeight应同步释放: $withIme", 0.dp,
                withIme.miniPlayerHeight)
            assertBoundsEqual("IME期间内容槽应使用宿主全部可用高度", withIme.hostBounds, withIme.slotBounds)
            assertBoundsEqual("IME期间页面不能留下底栏或MiniPlayer空白", withIme.hostBounds, withIme.pageBounds)
        } else {
            assertSamePageLayout("Material IME不应改变既有chrome布局", visible, withIme)
            assertSameDragTargets("Material IME不应改变既有拖拽目标", visible, withIme)
        }
    }

    private fun showScaffold(style: UiStyle, hasSong: Boolean = false, baseBlurRequested: Boolean = false) {
        composeRule.setContent {
            val view = LocalView.current
            val imeVisible = WindowInsets.isImeVisible
            val playbackVisible = showNowPlaying
            SideEffect {
                composeView = view
                observedImeVisible = imeVisible
                observedPlaybackVisible = playbackVisible
            }
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = style,
                animateColors = false
            ) {
                Box(Modifier.fillMaxSize().testTag("scaffoldHost")) {
                    AppNavigationScaffold(
                        bottomBar = AppBottomBarPresentation(
                            items = listOf(Destinations.Home to Icons.Outlined.Home,
                                Destinations.Library to Icons.Outlined.LibraryMusic),
                            currentDestination = null,
                            showNowPlaying = playbackVisible,
                            offlineMode = false,
                            alwaysUseNewTabStyle = false,
                            backgroundImageUri = null
                        ),
                        miniPlayer = AppMiniPlayerPresentation(
                            if (hasSong) SongItem(7L, "IME fixture", "Artist", "Album", 1L, 60_000L, null) else null,
                            null, null, "ime-fixture", "ime-fixture", false
                        ),
                        baseBlurRequested = baseBlurRequested,
                        snackbarHostState = remember { SnackbarHostState() },
                        onMainTabSelected = selectedRoutes::add,
                        onExpandNowPlaying = { expandRequests++ }
                    ) { layoutInsets ->
                        val miniPlayerHeight = LocalMiniPlayerHeight.current
                        val tabBarBounds = LocalBottomTabBarBoundsInRoot.current
                        val miniPlayerBounds = LocalMiniPlayerBoundsInRoot.current
                        val density = LocalDensity.current.density
                        SideEffect {
                            observedLayoutInsets = layoutInsets
                            observedMiniPlayerHeight = miniPlayerHeight
                            observedTabBarBounds = tabBarBounds
                            observedMiniPlayerBounds = miniPlayerBounds
                            observedDensity = density
                        }
                        Box(Modifier.fillMaxSize().testTag("scaffoldContentSlot")) {
                            // 页面实际消费这个预留，不能只检查Scaffold传出的数值
                            Box(Modifier.fillMaxSize().padding(bottom = miniPlayerHeight)) {
                                Box(Modifier.fillMaxSize().testTag("availablePageContent"))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun clickTab(destination: Destinations) {
        composeRule.onNodeWithText(application.getString(destination.labelResId)).performClick()
    }

    private fun clickMiniPlayerMetadata() {
        composeRule.onNodeWithTag("miniPlayerMetadata", useUnmergedTree = true).performClick()
    }

    private data class LayoutFrame(
        val insets: BottomBarLayoutInsets,
        val miniPlayerHeight: Dp,
        val tabBarBounds: Rect?,
        val miniPlayerBounds: Rect?,
        val miniPlayerCapsuleBounds: Rect?,
        val hostBounds: Rect,
        val slotBounds: Rect,
        val pageBounds: Rect,
        val density: Float,
        val clockMs: Long
    )

    private fun recordLayoutFrame(): LayoutFrame {
        val host = composeRule.onNodeWithTag("scaffoldHost").fetchSemanticsNode()
        val slot = composeRule.onNodeWithTag("scaffoldContentSlot").fetchSemanticsNode()
        val page = composeRule.onNodeWithTag("availablePageContent").fetchSemanticsNode()
        val miniPlayer = composeRule.onAllNodesWithTag("appMiniPlayer").fetchSemanticsNodes().singleOrNull()
        // 查询节点会同步Android布局，之后在同一次主线程读取中采集状态和坐标
        return composeRule.runOnIdle {
            LayoutFrame(
                insets = observedLayoutInsets,
                miniPlayerHeight = observedMiniPlayerHeight,
                tabBarBounds = observedTabBarBounds,
                miniPlayerBounds = observedMiniPlayerBounds,
                miniPlayerCapsuleBounds = miniPlayer?.boundsInRoot,
                hostBounds = host.boundsInRoot,
                slotBounds = slot.boundsInRoot,
                pageBounds = page.boundsInRoot,
                density = observedDensity,
                clockMs = composeRule.mainClock.currentTime
            )
        }
    }

    private fun assertVisiblePageReserve(frame: LayoutFrame, style: UiStyle, hasSong: Boolean, blur: Boolean) {
        val expectedMiniPlayerHeight = if (hasSong) 64.dp else 0.dp
        val bottomBarInset = if (blur) frame.insets.miniPlayerBottomPadding else frame.insets.navContentBottomPadding
        assertTrue("显示中的底栏必须实际占用空间: $frame", bottomBarInset > 0.dp)
        if (style == UiStyle.Clean) assertEquals("无系统导航栏insets的Clean底栏为64dp加16dp边距", 80.dp, bottomBarInset)
        assertEquals(if (blur) 0.dp else bottomBarInset, frame.insets.navContentBottomPadding)
        assertEquals(expectedMiniPlayerHeight + if (blur) bottomBarInset else 0.dp, frame.insets.screenBottomInset)
        assertEquals(if (blur) bottomBarInset else 0.dp, frame.insets.miniPlayerBottomPadding)
        assertEquals(frame.insets.screenBottomInset, frame.miniPlayerHeight)
        assertEquals("内容槽边界必须反映实际底栏裁切: $frame",
            frame.insets.navContentBottomPadding.value * frame.density,
            frame.hostBounds.bottom - frame.slotBounds.bottom, 1f)
        assertEquals("页面边界必须反映实际LocalMiniPlayerHeight预留: $frame",
            frame.miniPlayerHeight.value * frame.density,
            frame.slotBounds.bottom - frame.pageBounds.bottom, 1f)
    }

    private fun assertSamePageLayout(message: String, expected: LayoutFrame, actual: LayoutFrame) {
        assertEquals(message, expected.insets, actual.insets)
        assertEquals(message, expected.miniPlayerHeight, actual.miniPlayerHeight)
        assertBoundsEqual(message, expected.hostBounds, actual.hostBounds)
        assertBoundsEqual(message, expected.slotBounds, actual.slotBounds)
        assertBoundsEqual(message, expected.pageBounds, actual.pageBounds)
    }

    private fun assertValidDragTargets(message: String, frame: LayoutFrame, hasSong: Boolean) {
        assertTrue("$message 必须有有效底栏拖拽目标: $frame",
            frame.tabBarBounds?.isEmpty == false)
        if (hasSong) {
            assertTrue("$message 必须有有效MiniPlayer拖拽目标: $frame",
                frame.miniPlayerBounds?.isEmpty == false)
            assertTrue("$message 必须有实际可见的MiniPlayer胶囊: $frame",
                frame.miniPlayerCapsuleBounds?.isEmpty == false)
            // 拖拽回调在外层，胶囊语义边界位于内部横向和底部padding之后
            assertContainsBounds("$message MiniPlayer目标必须包含完整可见胶囊",
                checkNotNull(frame.miniPlayerBounds), checkNotNull(frame.miniPlayerCapsuleBounds))
            assertContainsBounds("$message MiniPlayer目标不能超出实际内容槽",
                frame.slotBounds, checkNotNull(frame.miniPlayerBounds))
        } else assertNull("$message 无歌不能产生MiniPlayer拖拽目标", frame.miniPlayerBounds)
    }

    private fun assertContainsBounds(message: String, outer: Rect, inner: Rect) {
        assertTrue("$message: outer=$outer, inner=$inner",
            outer.left <= inner.left + 0.5f && outer.top <= inner.top + 0.5f &&
                outer.right + 0.5f >= inner.right && outer.bottom + 0.5f >= inner.bottom)
    }

    private fun assertHiddenDragTargets(message: String, frame: LayoutFrame) {
        assertNull("$message 隐藏底栏不能继续接受拖拽", frame.tabBarBounds)
        assertNull("$message 隐藏MiniPlayer不能继续接受拖拽", frame.miniPlayerBounds)
    }

    private fun assertSameDragTargets(message: String, expected: LayoutFrame, actual: LayoutFrame) {
        assertNullableBoundsEqual(message, expected.tabBarBounds, actual.tabBarBounds)
        assertNullableBoundsEqual(message, expected.miniPlayerBounds, actual.miniPlayerBounds)
    }

    private fun assertNullableBoundsEqual(message: String, expected: Rect?, actual: Rect?) {
        if (expected == null) {
            assertNull(message, actual)
        } else {
            assertNotNull(message, actual)
            assertBoundsEqual(message, expected, checkNotNull(actual))
        }
    }

    private fun assertBoundsEqual(message: String, expected: Rect, actual: Rect) {
        assertEquals(message, expected.left, actual.left, 0.5f)
        assertEquals(message, expected.top, actual.top, 0.5f)
        assertEquals(message, expected.right, actual.right, 0.5f)
        assertEquals(message, expected.bottom, actual.bottom, 0.5f)
    }

    private fun advanceVisibilityAnimation(durationMs: Long) {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(durationMs)
        composeRule.waitForIdle()
    }

    private data class BarFrame(
        val stage: String,
        val clockMs: Long,
        val bounds: Rect,
        val size: IntSize,
        val hostSize: IntSize,
        val playbackVisible: Boolean
    )

    private fun recordBarFrame(stage: String): BarFrame {
        val node = composeRule.onNodeWithTag("appBottomNavigation").fetchSemanticsNode()
        return BarFrame(
            stage,
            composeRule.mainClock.currentTime,
            node.boundsInRoot,
            node.size,
            IntSize(composeView.width, composeView.height),
            observedPlaybackVisible
        ).also(barFrames::add)
    }

    private fun advanceBarFrames(count: Int, stage: String): BarFrame {
        repeat(count) { index ->
            composeRule.mainClock.advanceTimeByFrame()
            // 动画时钟不负责 Android 的绘制与层变换，每帧等待宿主同步后再读取边界
            composeRule.waitForIdle()
            recordBarFrame("$stage-$index")
        }
        return barFrames.last()
    }

    private fun dispatchImeVisibility(visible: Boolean) = composeRule.runOnIdle {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), if (visible) Insets.of(0, 0, 0, 300) else Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.ime(), visible)
            .build()
        ViewCompat.dispatchApplyWindowInsets(composeView, insets)
    }
}
