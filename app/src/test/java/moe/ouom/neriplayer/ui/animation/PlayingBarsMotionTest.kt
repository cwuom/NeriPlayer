package moe.ouom.neriplayer.ui.animation

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayingBarsMotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val specs = listOf(
        PlayingBarSpec(from = 0.3f, to = 1f, durationMillis = 300),
        PlayingBarSpec(from = 10f, to = 22f, durationMillis = 680),
        PlayingBarSpec(from = 8f, to = 16f, durationMillis = 600, delayMillis = 90)
    )

    @Test
    fun `curve matches compose reverse repeating tween at every sampled time`() {
        specs.forEach { spec ->
            val reference = infiniteRepeatable<Float>(
                animation = tween(durationMillis = spec.durationMillis, delayMillis = spec.delayMillis),
                repeatMode = RepeatMode.Reverse
            ).vectorize(Float.VectorConverter)
            var playTimeNanos = 0L
            while (playTimeNanos < 5_000_000_000L) {
                val expected = reference.getValueFromNanos(
                    playTimeNanos,
                    AnimationVector1D(spec.from),
                    AnimationVector1D(spec.to),
                    AnimationVector1D(0f)
                ).value
                assertEquals(
                    "spec=$spec t=$playTimeNanos",
                    expected,
                    reverseRepeatingTweenValue(playTimeNanos, spec),
                    1e-4f
                )
                playTimeNanos += 4_166_667L
            }
        }
    }

    @Test
    fun `settle eases from the current height to flat in 180 ms`() {
        assertEquals(0.9f, settlePlayingBarValue(0.9f, 0.35f, 0L), 1e-6f)
        assertEquals(0.35f, settlePlayingBarValue(0.9f, 0.35f, 180_000_000L), 1e-6f)
        assertEquals(0.35f, settlePlayingBarValue(0.9f, 0.35f, 400_000_000L), 1e-6f)
        val midway = settlePlayingBarValue(0.9f, 0.35f, 90_000_000L)
        assertTrue(midway < 0.9f && midway > 0.35f)
    }

    @Test
    fun `bars follow the wave while playing and settle flat once paused`() {
        var animate by mutableStateOf(true)
        lateinit var state: PlayingBarsState
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            state = rememberPlayingBarsState(specs, flatValue = 0.35f, animate = animate)
        }
        composeRule.mainClock.advanceTimeBy(16L)
        val first = state.snapshot().toList()
        composeRule.mainClock.advanceTimeBy(150L)
        assertNotEquals(first, state.snapshot().toList())

        composeRule.runOnIdle { animate = false }
        composeRule.mainClock.advanceTimeBy(400L)
        state.snapshot().forEach { assertEquals(0.35f, it, 1e-6f) }
    }

    @Test
    fun `a paused indicator starts flat`() {
        lateinit var state: PlayingBarsState
        composeRule.setContent {
            state = rememberPlayingBarsState(specs, flatValue = 0.35f, animate = false)
        }
        composeRule.waitForIdle()
        state.snapshot().forEach { assertEquals(0.35f, it, 1e-6f) }
    }
}
