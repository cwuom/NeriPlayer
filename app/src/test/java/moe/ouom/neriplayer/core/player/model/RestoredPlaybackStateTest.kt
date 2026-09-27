package moe.ouom.neriplayer.core.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RestoredPlaybackStateTest {
    @Test
    fun `disabling automatic resume keeps the manual resume position`() {
        val pending = RestoredPlaybackState.from(positionMs = 42_000L, shouldResume = true)

        assertEquals(RestoredPlaybackState.ResumePending(42_000L), pending)
        assertEquals(RestoredPlaybackState.Paused(42_000L), pending.withoutAutoResume())
    }

    @Test
    fun `restoring a paused track keeps its position and normalizes invalid progress`() {
        assertEquals(RestoredPlaybackState.Paused(12_000L), RestoredPlaybackState.from(12_000L, false))
        assertEquals(RestoredPlaybackState.ResumePending(0L), RestoredPlaybackState.from(-10L, true))
    }

    @Test
    fun `suppressing an absent restore request cannot create one`() {
        assertSame(RestoredPlaybackState.None, RestoredPlaybackState.None.withoutAutoResume())
    }
}
