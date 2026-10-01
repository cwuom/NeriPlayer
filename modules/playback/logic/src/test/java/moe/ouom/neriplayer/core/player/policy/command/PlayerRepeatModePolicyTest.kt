package moe.ouom.neriplayer.core.player.policy.command

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRepeatModePolicyTest {

    @Test
    fun `repeat one is passed to exoplayer when sleep timer does not need track end`() {
        val repeatMode = resolveExoRepeatMode(
            repeatModeSetting = Player.REPEAT_MODE_ONE,
            shouldLetPlaybackEndForSleepTimer = false
        )

        assertEquals(Player.REPEAT_MODE_ONE, repeatMode)
    }

    @Test
    fun `finish current sleep timer suppresses exoplayer repeat one`() {
        val repeatMode = resolveExoRepeatMode(
            repeatModeSetting = Player.REPEAT_MODE_ONE,
            shouldLetPlaybackEndForSleepTimer = true
        )

        assertEquals(Player.REPEAT_MODE_OFF, repeatMode)
    }

    @Test
    fun `repeat all stays app managed at exoplayer layer`() {
        val repeatMode = resolveExoRepeatMode(
            repeatModeSetting = Player.REPEAT_MODE_ALL,
            shouldLetPlaybackEndForSleepTimer = false
        )

        assertEquals(Player.REPEAT_MODE_OFF, repeatMode)
    }
}
