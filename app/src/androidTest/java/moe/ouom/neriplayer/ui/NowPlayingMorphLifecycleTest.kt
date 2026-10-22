package moe.ouom.neriplayer.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.component.common.PredictiveDismissHandler
import moe.ouom.neriplayer.ui.component.common.rememberPredictiveDismissState
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingMorphState
import moe.ouom.neriplayer.ui.playback.visual.rememberNowPlayingMorphState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingMorphLifecycleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val visible = mutableStateOf(false)
    private val artwork = mutableStateOf<ImageBitmap?>(null)
    private val flight = SharedCoverFlightState()
    private lateinit var morph: NowPlayingMorphState

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val dismiss = rememberPredictiveDismissState()
            val currentArtwork = artwork.value
            morph = rememberNowPlayingMorphState(
                visible.value, flight, artwork = { currentArtwork }
            ) { dismiss.progress }
            if (morph.mounted) {
                PredictiveDismissHandler(dismiss, onDismiss = { visible.value = false })
            }
        }
    }

    @Test
    fun cancelledDragUnmountsThePlayerAndReleasesTheCover() {
        composeRule.runOnIdle { flight.dragOpenProgress = 0.18f }
        settle()
        composeRule.runOnIdle {
            assertTrue(morph.mounted)
            assertEquals(0.18f, morph.progress.value, 0.001f)
            flight.dragOpenProgress = null
        }
        settle()
        assertClosed()
    }

    @Test
    fun cancelledBackPreviewRestoresTheOpenPlayerBeforeOrdinaryClose() {
        composeRule.runOnIdle { visible.value = true }
        settle()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.6f)) }
        settle()
        composeRule.runOnIdle {
            assertTrue(morph.mounted)
            assertEquals(0.76f, morph.progress.value, 0.001f)
            dispatcher.dispatchOnBackCancelled()
        }
        settle()
        assertOpen()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        settle()
        assertClosed()
    }

    @Test
    fun reopeningDuringCloseLeavesThePlayerMountedAndTheCoverReleased() {
        composeRule.runOnIdle { visible.value = true }
        settle()
        composeRule.runOnIdle { visible.value = false }
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.runOnIdle { visible.value = true }
        settle()
        assertOpen()
    }

    @Test
    fun backPreviewCapturesTheLatestProviderAndKeepsItUntilTheNextGesture() {
        val initial = ImageBitmap(2, 2)
        val next = ImageBitmap(3, 3)
        val replacement = ImageBitmap(4, 4)
        composeRule.runOnIdle {
            artwork.value = initial
            visible.value = true
        }
        settle()
        composeRule.runOnIdle { artwork.value = next }
        settle()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.4f)) }
        settle()
        composeRule.runOnIdle {
            assertSame(next, flight.linkedBitmap)
            artwork.value = replacement
        }
        settle()
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.7f)) }
        settle()
        composeRule.runOnIdle {
            assertSame(next, flight.linkedBitmap)
            dispatcher.dispatchOnBackCancelled()
        }
        settle()
        composeRule.runOnIdle {
            assertNull(flight.linkedBitmap)
            dispatcher.dispatchOnBackStarted(backEvent(0f))
        }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.4f)) }
        settle()
        composeRule.runOnIdle {
            assertSame(replacement, flight.linkedBitmap)
            dispatcher.onBackPressed()
        }
        settle()
        assertClosed()
    }

    @Test
    fun ordinaryCloseHandsBackTheMiniPlayerWithin320Milliseconds() {
        composeRule.runOnIdle { visible.value = true }
        settle()
        composeRule.runOnIdle { visible.value = false }
        composeRule.mainClock.advanceTimeBy(128)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue("关闭期间仍需绘制完整的收回过程", morph.mounted)
            assertTrue(morph.progress.value > 0f && morph.progress.value < 1f)
            assertTrue("退出中保留单一封面归属", flight.linked)
        }
        composeRule.mainClock.advanceTimeBy(192)
        composeRule.waitForIdle()
        assertClosed()
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.waitForIdle()
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    private fun assertOpen() = composeRule.runOnIdle {
        assertTrue(morph.mounted)
        assertEquals(1f, morph.progress.value, 0.001f)
        assertFalse(flight.linked)
    }

    private fun assertClosed() = composeRule.runOnIdle {
        assertFalse("关闭后应卸载: progress=${morph.progress.value}, linked=${flight.linked}", morph.mounted)
        assertEquals(0f, morph.progress.value, 0.001f)
        assertFalse(flight.linked)
    }
}
