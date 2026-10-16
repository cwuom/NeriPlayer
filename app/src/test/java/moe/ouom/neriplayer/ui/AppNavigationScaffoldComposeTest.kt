package moe.ouom.neriplayer.ui

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.navigation.AppBottomBarPresentation
import moe.ouom.neriplayer.ui.navigation.AppMiniPlayerPresentation
import moe.ouom.neriplayer.ui.navigation.AppNavigationScaffold
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
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
    private val barFrames = mutableListOf<BarFrame>()

    @Test
    fun playbackOverlayHidesTheBottomBarAndClosingRestoresTabClicks() {
        showScaffold(UiStyle.Material)
        val bar = composeRule.onNodeWithTag("appBottomNavigation")
        bar.assertIsDisplayed()
        clickTab(Destinations.Library)
        composeRule.waitForIdle()
        val visibleBounds = recordBarFrame("visible").bounds

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { showNowPlaying = true }
        val exiting = advanceBarFrames(7, "exiting")
        composeRule.runOnIdle { assertTrue("播放页状态尚未提交: $barFrames", observedPlaybackVisible) }
        bar.assertIsDisplayed()
        assertTrue(
            "底栏应随播放页开场向下退出，且中间态尚未完全移出: $barFrames",
            exiting.bounds.top > visibleBounds.top && exiting.bounds.top < visibleBounds.bottom
        )
        advanceBarFrames(10, "hidden")
        bar.assertIsNotDisplayed()

        composeRule.runOnIdle { showNowPlaying = false }
        val entering = advanceBarFrames(7, "entering")
        composeRule.runOnIdle { assertEquals("关闭状态尚未提交: $barFrames", false, observedPlaybackVisible) }
        bar.assertIsDisplayed()
        assertTrue(
            "恢复过程应保留向上进入的中间态: $barFrames",
            entering.bounds.top > visibleBounds.top && entering.bounds.top < visibleBounds.bottom
        )
        val restored = advanceBarFrames(13, "restored")
        assertEquals("底栏未回到原位: $barFrames", visibleBounds.top, restored.bounds.top, 0.5f)
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

    private fun showScaffold(style: UiStyle) {
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
                    miniPlayer = AppMiniPlayerPresentation(null, null, null, null, null, false),
                    baseBlurRequested = false,
                    snackbarHostState = remember { SnackbarHostState() },
                    onMainTabSelected = selectedRoutes::add,
                    onExpandNowPlaying = {}
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun clickTab(destination: Destinations) {
        composeRule.onNodeWithText(application.getString(destination.labelResId)).performClick()
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
