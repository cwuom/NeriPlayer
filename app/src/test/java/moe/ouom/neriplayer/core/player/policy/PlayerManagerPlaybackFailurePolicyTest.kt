package moe.ouom.neriplayer.core.player.policy

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerManagerPlaybackFailurePolicyTest {

    @Test
    fun `repeat one failure advances to next track when playlist still has alternatives`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 1,
            playlistSize = 4,
            repeatMode = Player.REPEAT_MODE_ONE,
            shuffleEnabled = false,
            shuffleFutureSize = 0,
            shuffleBagSize = 0
        )

        assertEquals(PlaybackFailureAdvanceAction.NEXT, action)
    }

    @Test
    fun `repeat one failure stops on last track instead of replaying same song`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 2,
            playlistSize = 3,
            repeatMode = Player.REPEAT_MODE_ONE,
            shuffleEnabled = false,
            shuffleFutureSize = 0,
            shuffleBagSize = 0
        )

        assertEquals(PlaybackFailureAdvanceAction.STOP, action)
    }

    @Test
    fun `repeat all failure wraps when current track is the last available option`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 2,
            playlistSize = 3,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleEnabled = false,
            shuffleFutureSize = 0,
            shuffleBagSize = 0
        )

        assertEquals(PlaybackFailureAdvanceAction.WRAP, action)
    }

    @Test
    fun `shuffle failure uses queued alternative before considering wrap`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 0,
            playlistSize = 3,
            repeatMode = Player.REPEAT_MODE_ONE,
            shuffleEnabled = true,
            shuffleFutureSize = 0,
            shuffleBagSize = 2
        )

        assertEquals(PlaybackFailureAdvanceAction.NEXT, action)
    }
}
