package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliVideoSkipIntervalsContentTest {
    @Test
    fun `timestamp parser accepts minute and hour formats`() {
        assertEquals(62_000L, parseBiliVideoSkipTimestamp("01:02"))
        assertEquals(3_723_000L, parseBiliVideoSkipTimestamp("01:02:03"))
        assertEquals(65_000L, parseBiliVideoSkipTimestamp("65"))
    }

    @Test
    fun `whole seconds are appended as a visible skip interval`() {
        val startMs = requireNotNull(parseBiliVideoSkipTimestamp("10"))
        val endMs = requireNotNull(parseBiliVideoSkipTimestamp("20"))

        assertEquals(
            listOf(BiliVideoSkipInterval(startMs = 10_000L, endMs = 20_000L)),
            appendBiliVideoSkipInterval(
                existingIntervals = emptyList(),
                startMs = startMs,
                endMs = endMs,
                durationMs = 60_000L
            )
        )
    }

    @Test
    fun `local interval edits are not replaced by a late saved rule update`() {
        val target = BiliVideoSkipTarget(bvid = "BV1test", cid = 1L)

        assertFalse(
            shouldReloadBiliVideoSkipIntervals(
                draftTarget = target,
                selectedTarget = target,
                hasLocalIntervalEdits = true
            )
        )
        assertTrue(
            shouldReloadBiliVideoSkipIntervals(
                draftTarget = target,
                selectedTarget = target,
                hasLocalIntervalEdits = false
            )
        )
    }

    @Test
    fun `timestamp parser rejects malformed values`() {
        assertNull(parseBiliVideoSkipTimestamp("1:60"))
        assertNull(parseBiliVideoSkipTimestamp("1:2:3:4"))
        assertNull(parseBiliVideoSkipTimestamp("-01:02"))
    }

    @Test
    fun `timestamp formatter uses hours only when needed`() {
        assertEquals("01:02", formatBiliVideoSkipTimestamp(62_999L))
        assertEquals("01:02:03", formatBiliVideoSkipTimestamp(3_723_999L))
    }

    @Test
    fun `five second controls stay within playback bounds`() {
        assertEquals(
            0L,
            moveBiliVideoSkipPlaybackPosition(
                currentPositionMs = 2_000L,
                moveForward = false,
                durationMs = 30_000L
            )
        )
        assertEquals(
            30_000L,
            moveBiliVideoSkipPlaybackPosition(
                currentPositionMs = 28_000L,
                moveForward = true,
                durationMs = 30_000L
            )
        )
    }

    @Test
    fun `small seek control moves by one second`() {
        assertEquals(
            11_000L,
            moveBiliVideoSkipPlaybackPosition(
                currentPositionMs = 10_000L,
                moveForward = true,
                durationMs = 30_000L,
                stepMs = BILI_VIDEO_SKIP_SMALL_SEEK_STEP_MS
            )
        )
    }
}
