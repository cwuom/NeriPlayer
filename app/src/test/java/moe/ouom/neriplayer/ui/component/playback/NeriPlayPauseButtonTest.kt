package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriPlayPauseButtonTest {

    @Test
    fun `container is round at rest and squares off while playing`() {
        assertEquals(PlayPauseCornerPercentPaused, resolvePlayPauseCornerPercent(isPlaying = false, pressed = false), 0f)
        assertEquals(PlayPauseCornerPercentPlaying, resolvePlayPauseCornerPercent(isPlaying = true, pressed = false), 0f)
    }

    @Test
    fun `press tightens the corners in both playback states`() {
        val pressed = resolvePlayPauseCornerPercent(isPlaying = false, pressed = true)

        assertEquals(pressed, resolvePlayPauseCornerPercent(isPlaying = true, pressed = true), 0f)
        assertTrue(pressed < PlayPauseCornerPercentPlaying)
    }
}
