package moe.ouom.neriplayer.core.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlayerReadExtensionsTest {

    @Test
    fun `position read keeps a valid value and clamps a negative value`() {
        val player = mock(Player::class.java)
        `when`(player.currentPosition).thenReturn(1_250L, -1L)

        assertEquals(1_250L, player.currentPositionMsOr(99L))
        assertEquals(0L, player.currentPositionMsOr(99L))
    }

    @Test
    fun `position read uses the caller fallback when player throws`() {
        val player = mock(Player::class.java)
        `when`(player.currentPosition).thenThrow(IllegalStateException("player unavailable"))

        assertEquals(-1L, player.currentPositionMsOr(-1L))
    }

    @Test
    fun `duration read uses the caller fallback when player throws`() {
        val player = mock(Player::class.java)
        `when`(player.duration).thenThrow(IllegalStateException("player unavailable"))

        assertEquals(4_000L, player.durationMsOr(4_000L))
    }
}
