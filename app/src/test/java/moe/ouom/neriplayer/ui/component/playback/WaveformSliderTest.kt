package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformSliderTest {
    @Test
    fun `wave segment count stays bounded across widths`() {
        assertEquals(48, resolveWaveSegmentCount(0f))
        assertEquals(60, resolveWaveSegmentCount(360f))
        assertEquals(180, resolveWaveSegmentCount(1_080f))
        assertEquals(180, resolveWaveSegmentCount(4_000f))
    }

    @Test
    fun `wave phase follows elapsed time and wraps by cycle`() {
        val quarterCycle = resolveWavePhase(
            anchorPhase = 0f,
            elapsedNs = 500_000_000L
        )
        val wrappedQuarterCycle = resolveWavePhase(
            anchorPhase = 0f,
            elapsedNs = 2_500_000_000L
        )

        assertEquals((Math.PI / 2.0).toFloat(), quarterCycle, 0.0001f)
        assertEquals(quarterCycle, wrappedQuarterCycle, 0.0001f)
    }

    @Test
    fun `short track progress advances at the rendered frame cadence`() {
        assertEquals(
            0.225f,
            resolveWaveProgress(
                anchorValue = 0.20f,
                durationMs = 5_000L,
                playbackSpeed = 1f,
                elapsedNs = 125_000_000L
            ),
            0.0001f
        )
        assertEquals(
            0.25f,
            resolveWaveProgress(
                anchorValue = 0.20f,
                durationMs = 5_000L,
                playbackSpeed = 1f,
                elapsedNs = 250_000_000L
            ),
            0.0001f
        )
    }

    @Test
    fun `wave progress prediction clamps values and ignores invalid inputs`() {
        assertEquals(
            0f,
            resolveWaveProgress(
                anchorValue = -0.2f,
                durationMs = 5_000L,
                playbackSpeed = 1f,
                elapsedNs = -1L
            ),
            0.0001f
        )
        assertEquals(
            1f,
            resolveWaveProgress(
                anchorValue = 0.8f,
                durationMs = 5_000L,
                playbackSpeed = 1f,
                elapsedNs = 5_000_000_000L
            ),
            0.0001f
        )
        assertEquals(
            0.8f,
            resolveWaveProgress(
                anchorValue = 0.8f,
                durationMs = 0L,
                playbackSpeed = 1f,
                elapsedNs = 250_000_000L
            ),
            0.0001f
        )
        assertEquals(
            0.8f,
            resolveWaveProgress(
                anchorValue = 0.8f,
                durationMs = 5_000L,
                playbackSpeed = Float.NaN,
                elapsedNs = 250_000_000L
            ),
            0.0001f
        )
    }

    @Test
    fun `wave progress prediction stops for waiting and seek previews`() {
        assertTrue(
            shouldPredictWaveProgress(
                isWaveAnimating = true,
                isProgressStalled = false,
                isProgressPreviewing = false
            )
        )
        assertEquals(
            false,
            shouldPredictWaveProgress(
                isWaveAnimating = true,
                isProgressStalled = true,
                isProgressPreviewing = false
            )
        )
        assertEquals(
            false,
            shouldPredictWaveProgress(
                isWaveAnimating = true,
                isProgressStalled = false,
                isProgressPreviewing = true
            )
        )
        assertEquals(
            false,
            shouldPredictWaveProgress(
                isWaveAnimating = false,
                isProgressStalled = false,
                isProgressPreviewing = false
            )
        )
    }

    @Test
    fun `predictor reanchors each real progress update without sampler lag`() {
        val predictor = WaveProgressPredictor(0.20f)

        predictor.updateTarget(
            targetValue = 0.20f,
            durationMs = 5_000L,
            playbackSpeed = 1f,
            animate = true
        )
        predictor.onFrame(1_000_000_000L)
        predictor.onFrame(1_125_000_000L)
        assertEquals(0.225f, predictor.currentValue, 0.0001f)

        predictor.updateTarget(
            targetValue = 0.25f,
            durationMs = 5_000L,
            playbackSpeed = 1f,
            animate = true
        )
        predictor.onFrame(1_250_000_000L)
        assertEquals(0.25f, predictor.currentValue, 0.0001f)
        predictor.onFrame(1_375_000_000L)
        assertEquals(0.275f, predictor.currentValue, 0.0001f)
    }

    @Test
    fun `predictor immediately aligns after pause and seek`() {
        val predictor = WaveProgressPredictor(0.40f)

        predictor.updateTarget(
            targetValue = 0.40f,
            durationMs = 10_000L,
            playbackSpeed = 1.5f,
            animate = true
        )
        predictor.onFrame(1_000_000_000L)
        predictor.onFrame(1_200_000_000L)
        assertEquals(0.43f, predictor.currentValue, 0.0001f)

        predictor.updateTarget(
            targetValue = 0.15f,
            durationMs = 10_000L,
            playbackSpeed = 1.5f,
            animate = false
        )
        assertEquals(0.15f, predictor.currentValue, 0.0001f)

        predictor.updateTarget(
            targetValue = 0.15f,
            durationMs = 10_000L,
            playbackSpeed = 1.5f,
            animate = true
        )
        predictor.onFrame(2_000_000_000L)
        assertEquals(0.15f, predictor.currentValue, 0.0001f)
        predictor.onFrame(2_200_000_000L)
        assertEquals(0.18f, predictor.currentValue, 0.0001f)
    }

    @Test
    fun `predictor resets its frame anchor when animation resumes`() {
        val predictor = WaveProgressPredictor(0.40f)

        predictor.updateTarget(
            targetValue = 0.40f,
            durationMs = 10_000L,
            playbackSpeed = 1f,
            animate = true
        )
        predictor.onFrame(1_000_000_000L)
        predictor.onFrame(1_200_000_000L)
        assertEquals(0.42f, predictor.currentValue, 0.0001f)

        predictor.resetFrameAnchor()
        predictor.onFrame(10_000_000_000L)
        assertEquals(0.42f, predictor.currentValue, 0.0001f)
        predictor.onFrame(10_200_000_000L)
        assertEquals(0.44f, predictor.currentValue, 0.0001f)
    }

    @Test
    fun `waiting pulse segment count stays dense but bounded`() {
        assertEquals(1, resolveWaitingPulseSegmentCount(0f, 24f))
        assertEquals(1, resolveWaitingPulseSegmentCount(12f, 24f))
        assertEquals(45, resolveWaitingPulseSegmentCount(1_080f, 24f))
        assertEquals(72, resolveWaitingPulseSegmentCount(4_000f, 24f))
        assertEquals(1, resolveWaitingPulseSegmentCount(1_080f, 0f))
    }

    @Test
    fun `waiting pulse phase follows its faster cycle`() {
        val quarterCycle = resolveWaitingPulsePhase(
            anchorPhase = 0f,
            elapsedNs = 350_000_000L
        )
        val wrappedQuarterCycle = resolveWaitingPulsePhase(
            anchorPhase = 0f,
            elapsedNs = 1_750_000_000L
        )

        assertEquals((Math.PI / 2.0).toFloat(), quarterCycle, 0.0001f)
        assertEquals(quarterCycle, wrappedQuarterCycle, 0.0001f)
    }

    @Test
    fun `waiting pulse peak advances one segment at a time`() {
        val segmentCount = 32
        val travelDistance = 39f
        val firstSegmentPhase = (2.0 * Math.PI * 4f / travelDistance).toFloat()
        val secondSegmentPhase = (2.0 * Math.PI * 5f / travelDistance).toFloat()

        assertEquals(0f, resolveWaitingPulseStrength(0, segmentCount, 0f), 0.0001f)
        assertEquals(0f, resolveWaitingPulseStrength(31, segmentCount, 0f), 0.0001f)
        assertEquals(
            1f,
            resolveWaitingPulseStrength(0, segmentCount, firstSegmentPhase),
            0.0001f
        )
        assertEquals(
            1f,
            resolveWaitingPulseStrength(1, segmentCount, secondSegmentPhase),
            0.0001f
        )
        assertTrue(resolveWaitingPulseStrength(8, segmentCount, 0f) in 0f..1f)
        assertEquals(0f, resolveWaitingPulseStrength(8, segmentCount, 0f), 0.0001f)
    }
}
