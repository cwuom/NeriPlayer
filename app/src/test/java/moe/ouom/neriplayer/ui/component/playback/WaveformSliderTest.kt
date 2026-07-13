package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertEquals
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
}
