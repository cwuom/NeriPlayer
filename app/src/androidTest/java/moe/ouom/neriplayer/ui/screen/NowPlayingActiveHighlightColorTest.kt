package moe.ouom.neriplayer.ui.screen

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingActiveHighlightColorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun activeContentColorTransitionsInsteadOfChangingInOneFrame() {
        lateinit var targetColor: MutableState<Color>
        var renderedColor = Color.Unspecified
        val oldColor = Color(0xFFE85D75)
        val newColor = Color(0xFF4E9DFF)
        val autoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false

        try {
            composeRule.setContent {
                targetColor = androidx.compose.runtime.remember { mutableStateOf(oldColor) }
                val animatedColor = rememberStableNowPlayingActiveContentColor(targetColor.value)
                SideEffect { renderedColor = animatedColor }
            }

            advanceFrameAndAwaitIdle()
            composeRule.runOnIdle { assertEquals(oldColor, renderedColor) }

            composeRule.runOnIdle { targetColor.value = newColor }
            advanceFrameAndAwaitIdle()
            composeRule.runOnIdle { assertEquals(oldColor, renderedColor) }
            composeRule.mainClock.advanceTimeBy(
                NowPlayingActiveContentColorStabilizationDelayMs.toLong() + 1L
            )
            advanceUntilFirstInterpolatedColor()
            composeRule.runOnIdle {
                assertNotEquals(oldColor, renderedColor)
                assertNotEquals(newColor, renderedColor)
            }

            composeRule.mainClock.advanceTimeBy(
                NowPlayingActiveContentColorTransitionDurationMs.toLong() + 32L
            )
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(newColor, renderedColor) }
        } finally {
            composeRule.mainClock.autoAdvance = autoAdvance
        }
    }

    @Test
    fun transientBlueCandidateDoesNotReplaceThePreviousColor() {
        lateinit var targetColor: MutableState<Color>
        var renderedColor = Color.Unspecified
        val oldColor = Color(0xFFE85D75)
        val transientBlue = Color(0xFF8FD8FF)
        val finalColor = Color(0xFFE0A7D5)
        val autoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false

        try {
            composeRule.setContent {
                targetColor = androidx.compose.runtime.remember { mutableStateOf(oldColor) }
                val stableColor = rememberStableNowPlayingActiveContentColor(targetColor.value)
                SideEffect { renderedColor = stableColor }
            }

            advanceFrameAndAwaitIdle()
            composeRule.runOnIdle { assertEquals(oldColor, renderedColor) }

            composeRule.runOnIdle { targetColor.value = transientBlue }
            advanceFrameAndAwaitIdle()
            composeRule.runOnIdle {
                assertEquals(oldColor, renderedColor)
                assertNotEquals(transientBlue, renderedColor)
            }

            composeRule.runOnIdle { targetColor.value = finalColor }
            advanceFrameAndAwaitIdle()
            composeRule.runOnIdle { assertEquals(oldColor, renderedColor) }
            composeRule.mainClock.advanceTimeBy(
                NowPlayingActiveContentColorStabilizationDelayMs.toLong() + 1L
            )
            advanceUntilFirstInterpolatedColor()
            composeRule.runOnIdle {
                assertNotEquals(transientBlue, renderedColor)
                assertNotEquals(finalColor, renderedColor)
            }

            composeRule.mainClock.advanceTimeBy(
                NowPlayingActiveContentColorTransitionDurationMs.toLong() + 32L
            )
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(finalColor, renderedColor) }
        } finally {
            composeRule.mainClock.autoAdvance = autoAdvance
        }
    }

    private fun advanceFrameAndAwaitIdle() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun advanceUntilFirstInterpolatedColor() {
        // one frame commits the target, one starts the animation, and one samples progress
        repeat(3) { advanceFrameAndAwaitIdle() }
    }
}
