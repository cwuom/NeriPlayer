package moe.ouom.neriplayer.data.model.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RestoredPlaybackStateTest {

    @Test
    fun `restored positions must not be negative`() {
        assertThrows(IllegalArgumentException::class.java) { RestoredPlaybackState.Paused(-1L) }
        assertThrows(IllegalArgumentException::class.java) { RestoredPlaybackState.ResumePending(-1L) }
        assertEquals(0L, RestoredPlaybackState.Paused(0L).positionMs)
    }

    @Test
    fun `factory clamps negative positions and picks the resume flavour`() {
        assertEquals(RestoredPlaybackState.ResumePending(0L), RestoredPlaybackState.from(-5L, shouldResume = true))
        assertEquals(RestoredPlaybackState.Paused(7L), RestoredPlaybackState.from(7L, shouldResume = false))
    }

    @Test
    fun `dropping auto resume keeps the restored position`() {
        val paused = RestoredPlaybackState.Paused(42L)

        assertEquals(paused, RestoredPlaybackState.ResumePending(42L).withoutAutoResume())
        assertSame(paused, paused.withoutAutoResume())
        assertSame(RestoredPlaybackState.None, RestoredPlaybackState.None.withoutAutoResume())
    }
}
