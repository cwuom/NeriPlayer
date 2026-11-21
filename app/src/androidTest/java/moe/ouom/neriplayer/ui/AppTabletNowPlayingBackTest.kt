package moe.ouom.neriplayer.ui

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.component.common.PredictiveDismissState
import moe.ouom.neriplayer.ui.component.common.rememberPredictiveDismissState
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.playback.visual.AppNowPlayingOverlay
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppTabletNowPlayingBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val visible = mutableStateOf(false)
    private val previewEnabled = mutableStateOf(true)
    private val landscape = mutableStateOf(false)
    private val uiStyle = mutableStateOf(UiStyle.Clean)
    private val flight = SharedCoverFlightState()
    private val queue = MutableStateFlow<List<SongItem>>(emptyList())
    private val visibilityEvents = mutableListOf<Boolean>()
    private var closeRequests = 0
    private lateinit var dismiss: PredictiveDismissState

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                smallestScreenWidthDp = 800
                screenWidthDp = if (landscape.value) 1280 else 800
                screenHeightDp = if (landscape.value) 800 else 1280
                orientation = if (landscape.value) Configuration.ORIENTATION_LANDSCAPE
                    else Configuration.ORIENTATION_PORTRAIT
            }
            dismiss = rememberPredictiveDismissState()
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalSharedCoverFlight provides flight,
                LocalPredictiveBackPreviewEnabled provides previewEnabled.value
            ) {
                Box(Modifier.fillMaxSize()) {
                    AppNowPlayingOverlay(
                        visible = visible.value,
                        cover = NowPlayingOverlayCover(null, null, null, 0),
                        queueFlow = queue,
                        theme = NowPlayingOverlayTheme(
                            false, null, "6750A4", PaletteStyle.TonalSpot,
                            ColorSpec.SpecVersion.Default, uiStyle = uiStyle.value
                        ),
                        background = NowPlayingOverlayBackground(false, 0f, 0f, false, true),
                        onVisibilityChanged = { visibilityEvents.add(it) },
                        onClose = {
                            closeRequests += 1
                            visible.value = false
                        },
                        dismissState = dismiss
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag(PlayerContent))
                    }
                }
            }
        }
        advance(32)
    }

    @Test
    fun ordinarySystemBackClosesAndUnmountsTheTabletPlayer() {
        openPlayer()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(64)
        composeRule.runOnIdle {
            assertFalse(visible.value)
            assertEquals(1, closeRequests)
        }
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertExists()
        advance(400)
        assertClosed()
    }

    @Test
    fun reversedAndCancelledPredictiveBackRestoresTheTabletBeforeOrdinaryClose() {
        openPlayer()
        val original = playerBounds()
        val originalWidth = playerWidth()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.7f)) }
        advance(32)
        val previewWidth = playerWidth()
        composeRule.runOnIdle {
            assertEquals(0.7f, dismiss.progress, 0.001f)
            assertTrue(visible.value)
            assertEquals(0, closeRequests)
        }
        assertTrue("平板预测返回应实际缩小播放器", previewWidth < originalWidth * 0.98f)
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertIsDisplayed()

        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.2f)) }
        advance(32)
        composeRule.runOnIdle { assertEquals(0.2f, dismiss.progress, 0.001f) }
        assertTrue("反向拖回时播放器应恢复尺寸", playerWidth() > previewWidth)
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advance(400)
        assertOpen()
        assertEquals(originalWidth, playerWidth(), 1f)
        assertEquals(original.left, playerBounds().left, 1f)
        assertEquals(original.top, playerBounds().top, 1f)
        composeRule.runOnIdle {
            assertEquals(0, closeRequests)
            assertEquals(listOf(true), visibilityEvents)
        }

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        advance(400)
        assertClosed()
    }

    @Test
    fun disabledPreviewKeepsTheTabletFullSizeAndOneBackClosesIt() {
        composeRule.runOnIdle { previewEnabled.value = false }
        openPlayer()
        val originalWidth = playerWidth()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.8f)) }
        advance(32)

        assertEquals(originalWidth, playerWidth(), 1f)
        composeRule.runOnIdle {
            assertEquals(0f, dismiss.progress, 0f)
            assertEquals(0, closeRequests)
            dispatcher.onBackPressed()
        }
        advance(400)
        assertClosed()
    }

    @Test
    fun disabledPreviewClosesWithOneSystemEdgeSwipe() {
        assumeTrue(
            "需要系统手势导航",
            Settings.Secure.getInt(composeRule.activity.contentResolver, "navigation_mode", -1) == 2
        )
        composeRule.runOnIdle { previewEnabled.value = false }
        openPlayer()
        val (width, height) = composeRule.runOnIdle {
            composeRule.activity.window.decorView.let { it.width to it.height }
        }
        composeRule.mainClock.autoAdvance = true
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(
                "input swipe ${width - 1} ${height / 2} ${width / 2} ${height / 2} 350"
            )
        ).use { it.readBytes() }
        composeRule.waitUntil(timeoutMillis = 5_000) { !visible.value }
        composeRule.waitForIdle()
        assertClosed()
    }

    @Test
    fun toolbarVisibilityChangeKeepsTheTabletMountedUntilExitCompletes() {
        openPlayer()
        val original = playerBounds()
        composeRule.runOnIdle { visible.value = false }
        advance(64)
        assertTrue(playerBounds().top > original.top)
        composeRule.runOnIdle { assertEquals(listOf(true), visibilityEvents) }
        advance(400)
        assertClosed(expectedCloseRequests = 0)
    }

    @Test
    fun committedPredictiveBackContinuesFromThePreviewAndSettlesWhileSlidingDown() {
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        for (isLandscape in listOf(false, true)) {
            for (style in listOf(UiStyle.Material, UiStyle.Clean)) {
                for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                    for (progress in listOf(0.2f, 0.7f)) {
                        composeRule.runOnIdle {
                            landscape.value = isLandscape
                            uiStyle.value = style
                            visibilityEvents.clear()
                            closeRequests = 0
                        }
                        openPlayer()
                        val original = playerBounds()
                        composeRule.runOnIdle {
                            dispatcher.dispatchOnBackStarted(backEvent(0f, edge))
                            dispatcher.dispatchOnBackProgressed(backEvent(progress, edge))
                        }
                        advance(32)
                        val preview = playerBounds()
                        composeRule.runOnIdle { dispatcher.onBackPressed() }
                        advance(16)
                        val committed = playerBounds()
                        assertEquals(preview.left, committed.left, 1f)
                        assertEquals(preview.top, committed.top, 1f)
                        assertEquals(preview.width, committed.width, 1f)
                        var previous = committed
                        repeat(3) {
                            advance(32)
                            val current = playerBounds()
                            assertTrue("退出应持续向下移动", current.top > previous.top)
                            assertTrue("退出时缩放应恢复", current.width > previous.width)
                            assertTrue(
                                "退出时横向偏移应回正",
                                kotlin.math.abs(current.center.x - original.center.x) <
                                    kotlin.math.abs(previous.center.x - original.center.x)
                            )
                            previous = current
                        }
                        advance(400)
                        assertClosed()
                    }
                }
            }
        }
    }

    @Test
    fun reopeningDuringThePredictiveBackExitKeepsTheTabletMounted() {
        openPlayer()
        composeRule.runOnIdle {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.7f))
        }
        advance(32)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(64)
        val closing = playerBounds()
        composeRule.runOnIdle { visible.value = true }
        advance(16)
        val reopened = playerBounds()
        assertEquals(closing.left, reopened.left, 1f)
        assertEquals(closing.top, reopened.top, 1f)
        assertEquals(closing.width, reopened.width, 1f)
        advance(400)
        assertOpen()
        composeRule.runOnIdle { assertEquals(listOf(true), visibilityEvents) }
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(400)
        assertClosed(expectedCloseRequests = 2)
    }

    private fun openPlayer() {
        composeRule.runOnIdle { visible.value = true }
        advance(400)
        assertOpen()
    }

    private fun assertOpen() {
        // 遮挡底层触摸的 clickable 会合并子语义，内容标签只保留在未合并的树中
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(visible.value)
            assertEquals(0f, dismiss.progress, 0.001f)
            assertDefaultTransition()
        }
    }

    private fun assertClosed(expectedCloseRequests: Int = 1) {
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle {
            assertFalse(visible.value)
            assertEquals(expectedCloseRequests, closeRequests)
            assertEquals(listOf(true, false), visibilityEvents)
            assertDefaultTransition()
        }
    }

    private fun advance(milliseconds: Long) {
        val deadline = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertDefaultTransition() }
        }
    }

    private fun assertDefaultTransition() {
        assertFalse("平板默认转场不应持有共享封面形变", flight.linked)
        assertEquals("平板逐帧不应推动手机容器形变进度", 0f, flight.nowPlayingMorphProgress, 0f)
    }

    private fun playerWidth(): Float =
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width

    private fun playerBounds(): Rect {
        val coordinates = composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true)
            .fetchSemanticsNode().layoutInfo.coordinates
        return composeRule.runOnIdle {
            coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
        }
    }

    private fun backEvent(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) =
        BackEventCompat(0f, 0f, progress, edge)

    private companion object {
        const val PlayerContent = "tablet-back-player"
    }
}
