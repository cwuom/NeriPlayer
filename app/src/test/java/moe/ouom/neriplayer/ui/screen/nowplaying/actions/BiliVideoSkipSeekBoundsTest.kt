package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliVideoSkipSeekBoundsTest {

    @Test
    fun `unknown or invalid durations do not cap forward seeks`() {
        assertEquals(15_000L, moveBiliVideoSkipPlaybackPosition(10_000L, moveForward = true, durationMs = 0L))
        assertEquals(8_000L, moveBiliVideoSkipPlaybackPosition(3_000L, moveForward = true, durationMs = -1L))
    }

    @Test
    fun `backward seeks subtract one step when the position is past the first step`() {
        assertEquals(7_000L, moveBiliVideoSkipPlaybackPosition(12_000L, moveForward = false, durationMs = 60_000L))
        assertEquals(
            500L,
            moveBiliVideoSkipPlaybackPosition(
                currentPositionMs = 1_500L,
                moveForward = false,
                durationMs = 60_000L,
                stepMs = BILI_VIDEO_SKIP_SMALL_SEEK_STEP_MS
            )
        )
    }

    @Test
    fun `positions past the end are clamped before moving backwards`() {
        assertEquals(55_000L, moveBiliVideoSkipPlaybackPosition(90_000L, moveForward = false, durationMs = 60_000L))
    }

    @Test
    fun `non positive steps still move by one millisecond`() {
        assertEquals(
            9L,
            moveBiliVideoSkipPlaybackPosition(10L, moveForward = false, durationMs = 60_000L, stepMs = 0L)
        )
        assertEquals(
            11L,
            moveBiliVideoSkipPlaybackPosition(10L, moveForward = true, durationMs = 60_000L, stepMs = -5L)
        )
    }
}
