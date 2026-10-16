package moe.ouom.neriplayer.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientFrameGridTest {
    private val vsync120Nanos = 1_000_000_000L / 120L

    private fun renderedFrames(intervalNanos: Long, vsyncs: Int, startNanos: Long = 7_000_000_123L): Set<Long> {
        val rendered = mutableSetOf<Long>()
        var last = 0L
        for (index in 0 until vsyncs) {
            val frame = startNanos + index * vsync120Nanos
            if (isNewAmbientFrameSlot(last, frame, intervalNanos)) {
                rendered += frame
                last = frame
            }
        }
        return rendered
    }

    @Test
    fun `a 60 fps grid renders every other vsync on a 120 Hz display`() {
        val rendered = renderedFrames(AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS, vsyncs = 1_200)
        assertEquals(600.0, rendered.size.toDouble(), 2.0)
    }

    @Test
    fun `slower animations land on the same vsyncs so the window stays at 60 fps`() {
        val wave = renderedFrames(AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS, vsyncs = 1_200)
        val lyrics = renderedFrames(AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS * 2L, vsyncs = 1_200)
        val lowPowerLyrics = renderedFrames(AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS * 4L, vsyncs = 1_200)

        val union = wave + lyrics + lowPowerLyrics
        assertTrue(union.size <= wave.size + 2)
        assertEquals(300.0, lyrics.size.toDouble(), 2.0)
        assertEquals(150.0, lowPowerLyrics.size.toDouble(), 2.0)
    }

    @Test
    fun `the first frame always renders`() {
        assertTrue(isNewAmbientFrameSlot(0L, 123L, AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS))
    }
}
