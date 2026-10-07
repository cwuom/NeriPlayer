package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveProgressPredictorEdgeTest {

    @Test
    fun `non finite initial progress starts at the beginning and finite values are clamped`() {
        assertEquals(0f, WaveProgressPredictor(Float.NaN).currentValue, 0f)
        assertEquals(0f, WaveProgressPredictor(Float.POSITIVE_INFINITY).currentValue, 0f)
        assertEquals(0f, WaveProgressPredictor(Float.NEGATIVE_INFINITY).currentValue, 0f)
        assertEquals(1f, WaveProgressPredictor(1.7f).currentValue, 0f)
        assertEquals(0f, WaveProgressPredictor(-0.3f).currentValue, 0f)
    }

    @Test
    fun `repeating the same target while animating keeps the predicted position`() {
        val predictor = animatingPredictor(start = 0.20f, durationMs = 10_000L, speed = 1f)
        predictor.onFrame(2_000_000_000L)
        assertEquals(0.30f, predictor.currentValue, 0.0001f)

        predictor.updateTarget(0.20f, 10_000L, 1f, animate = true)
        predictor.onFrame(3_000_000_000L)

        assertEquals(0.40f, predictor.currentValue, 0.0001f)
    }

    @Test
    fun `pausing without a new target snaps back to the reported position`() {
        val predictor = animatingPredictor(start = 0.20f, durationMs = 10_000L, speed = 1f)
        predictor.onFrame(2_000_000_000L)

        predictor.updateTarget(0.20f, 10_000L, 1f, animate = false)

        assertEquals(0.20f, predictor.currentValue, 0f)
    }

    @Test
    fun `speed change alone re-anchors the prediction`() {
        val predictor = animatingPredictor(start = 0.20f, durationMs = 10_000L, speed = 1f)
        predictor.onFrame(2_000_000_000L)

        predictor.updateTarget(0.20f, 10_000L, 2f, animate = true)
        assertEquals(0.20f, predictor.currentValue, 0f)
        predictor.onFrame(5_000_000_000L)
        predictor.onFrame(6_000_000_000L)

        assertEquals(0.40f, predictor.currentValue, 0.0001f)
    }

    @Test
    fun `invalid playback speed freezes the prediction at the anchor`() {
        val predictor = WaveProgressPredictor(0.5f)
        predictor.updateTarget(0.5f, 10_000L, playbackSpeed = -1f, animate = true)
        predictor.onFrame(1_000_000_000L)
        predictor.onFrame(4_000_000_000L)

        assertEquals(0.5f, predictor.currentValue, 0f)
    }

    @Test
    fun `non positive frame timestamps are ignored`() {
        val predictor = animatingPredictor(start = 0.20f, durationMs = 10_000L, speed = 1f)
        predictor.onFrame(2_000_000_000L)

        predictor.onFrame(0L)
        predictor.onFrame(-5L)

        assertEquals(0.30f, predictor.currentValue, 0.0001f)
    }

    @Test
    fun `a frame clock that moves backwards restarts prediction from the reported anchor`() {
        val predictor = animatingPredictor(start = 0.20f, durationMs = 10_000L, speed = 1f)
        predictor.onFrame(3_000_000_000L)
        assertEquals(0.40f, predictor.currentValue, 0.0001f)

        predictor.onFrame(500_000_000L)
        assertEquals(0.20f, predictor.currentValue, 0.0001f)
        predictor.onFrame(1_500_000_000L)

        assertEquals(0.30f, predictor.currentValue, 0.0001f)
    }

    @Test
    fun `waiting pulse is dark for empty bars and out of range segments`() {
        val phase = (Math.PI / 2.0).toFloat()

        assertEquals(0f, resolveWaitingPulseStrength(segmentIndex = 0, segmentCount = 0, phase = phase), 0f)
        assertEquals(0f, resolveWaitingPulseStrength(segmentIndex = 0, segmentCount = -3, phase = phase), 0f)
        assertEquals(0f, resolveWaitingPulseStrength(segmentIndex = -1, segmentCount = 8, phase = phase), 0f)
        assertEquals(0f, resolveWaitingPulseStrength(segmentIndex = 8, segmentCount = 8, phase = phase), 0f)
    }

    @Test
    fun `waiting pulse wraps negative phases onto the same cycle`() {
        val phase = 2.9f
        val twoPi = (2.0 * Math.PI).toFloat()
        val strength = resolveWaitingPulseStrength(segmentIndex = 3, segmentCount = 8, phase = phase)

        assertTrue(strength > 0.9f)
        assertEquals(
            strength,
            resolveWaitingPulseStrength(segmentIndex = 3, segmentCount = 8, phase = phase - twoPi),
            0.0001f
        )
    }

    @Test
    fun `waiting state outranks play and pause but yields to a muted route`() {
        assertEquals(
            PlaybackControlVisualState.WAITING,
            resolvePlaybackControlVisualState(isPlaying = true, isPlaybackWaiting = true, isAudioRouteMuted = false)
        )
        assertEquals(
            PlaybackControlVisualState.PAUSE,
            resolvePlaybackControlVisualState(isPlaying = true, isPlaybackWaiting = false, isAudioRouteMuted = false)
        )
        assertEquals(
            PlaybackControlVisualState.PLAY,
            resolvePlaybackControlVisualState(isPlaying = false, isPlaybackWaiting = false, isAudioRouteMuted = false)
        )
    }

    private fun animatingPredictor(start: Float, durationMs: Long, speed: Float): WaveProgressPredictor {
        return WaveProgressPredictor(start).apply {
            updateTarget(start, durationMs, speed, animate = true)
            onFrame(1_000_000_000L)
        }
    }
}
