package moe.ouom.neriplayer.data.ltw.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ListenTogetherPlaybackSyncPolicyTest {
    @Test
    fun `soft sync rates change direction and intensity at drift boundaries`() {
        for ((drift, expected) in listOf(
            99L to null, 100L to 1.03f, 399L to 1.03f, 400L to 1.05f,
            999L to 1.05f, 1_000L to null, -100L to 0.97f, -400L to 0.95f
        )) {
            assertEquals(expected, rate(drift))
        }
        assertNull(resolveListenTogetherSoftSyncPlaybackRate(200L, 200L, false, false, 100L, 400L, 1_000L))
        assertNull(resolveListenTogetherSoftSyncPlaybackRate(200L, 200L, true, true, 100L, 400L, 1_000L))
    }

    @Test
    fun `zero position protection applies only to passive updates of the same playing track`() {
        fun holds(cause: String? = "HEARTBEAT", playing: Boolean = true, expected: Long = 0L,
                  local: Long = 5_000L, contextChanged: Boolean = false, indexChanged: Boolean = false) =
            shouldIgnoreListenTogetherUnexpectedZeroPositionRollback(cause, playing, expected, local, contextChanged, indexChanged, 5_000L)
        assertTrue(holds())
        assertFalse(holds(cause = "SEEK"))
        assertFalse(holds(cause = null))
        assertFalse(holds(playing = false))
        assertFalse(holds(expected = 1L))
        assertFalse(holds(local = 4_999L))
        assertFalse(holds(contextChanged = true))
        assertFalse(holds(indexChanged = true))
    }

    private fun rate(drift: Long): Float? =
        resolveListenTogetherSoftSyncPlaybackRate(abs(drift), drift, true, false, 100L, 400L, 1_000L)
}
