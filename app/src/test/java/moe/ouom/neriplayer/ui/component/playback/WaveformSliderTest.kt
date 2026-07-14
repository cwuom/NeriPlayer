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
