package moe.ouom.neriplayer.ui

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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

    private fun render(smallestScreenWidthDp: Int, widthDp: Int) {
        smallestWidth.intValue = smallestScreenWidthDp
        windowWidth.intValue = widthDp
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                this.smallestScreenWidthDp = smallestWidth.intValue
                screenWidthDp = windowWidth.intValue
                screenHeightDp = 390
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalSharedCoverFlight provides flight
            ) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { viewportLayout = describeLayout(it) }) {
                AppNowPlayingOverlay(
                    visible = visible.value,
                    cover = NowPlayingOverlayCover(null, null, null, 0),
                    queueFlow = queue,
                    theme = NowPlayingOverlayTheme(
                        false, null, "6750A4", PaletteStyle.TonalSpot,
                        ColorSpec.SpecVersion.Default, uiStyle = UiStyle.Clean
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
