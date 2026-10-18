package moe.ouom.neriplayer.ui

import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class AppTabletNowPlayingBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val visible = mutableStateOf(false)
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
                screenWidthDp = 800
                screenHeightDp = 1280
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            dismiss = rememberPredictiveDismissState()
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalSharedCoverFlight provides flight
            ) {
                Box(Modifier.fillMaxSize()) {
                    AppNowPlayingOverlay(
                        visible = visible.value,
                        cover = NowPlayingOverlayCover(null, null, null, 0),
                        queueFlow = queue,
                        theme = NowPlayingOverlayTheme(
                            false, null, "6750A4", PaletteStyle.TonalSpot,
                            ColorSpec.SpecVersion.Default, uiStyle = UiStyle.Clean
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
        composeRule.runOnIdle { assertEquals(0, closeRequests) }

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        advance(400)
        assertClosed()
    }

    @Test
    fun reopeningDuringTheSystemBackExitKeepsTheTabletMounted() {
        openPlayer()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        advance(64)
        composeRule.runOnIdle { visible.value = true }
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

    private fun backEvent(progress: Float) =
        BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    private companion object {
        const val PlayerContent = "tablet-back-player"
    }
}
