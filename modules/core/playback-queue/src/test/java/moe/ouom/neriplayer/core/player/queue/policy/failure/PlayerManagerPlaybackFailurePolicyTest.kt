package moe.ouom.neriplayer.core.player.queue.policy.failure

import moe.ouom.neriplayer.core.player.queue.model.PlaybackFailureAdvanceAction
import moe.ouom.neriplayer.core.player.queue.policy.QueueRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerManagerPlaybackFailurePolicyTest {

    @Test
    fun `failure cannot select a track from an empty queue or a stale index`() {
        assertEquals(PlaybackFailureAdvanceAction.STOP, resolvePlaybackFailureAdvanceAction(0, 0, QueueRepeatMode.ALL))
        assertEquals(PlaybackFailureAdvanceAction.STOP, resolvePlaybackFailureAdvanceAction(-1, 3, QueueRepeatMode.ALL))
        assertEquals(PlaybackFailureAdvanceAction.STOP, resolvePlaybackFailureAdvanceAction(3, 3, QueueRepeatMode.ALL))
    }

    @Test
    fun `failure never wraps a single-track queue`() {
        assertEquals(PlaybackFailureAdvanceAction.STOP, resolvePlaybackFailureAdvanceAction(0, 1, QueueRepeatMode.ALL))
    }

    @Test
    fun `repeat one failure advances to next track when playlist still has alternatives`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 1,
            playlistSize = 4,
            repeatMode = QueueRepeatMode.ONE
        )

        assertEquals(PlaybackFailureAdvanceAction.NEXT, action)
    }

    @Test
    fun `repeat one failure stops on last track instead of replaying same song`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 2,
            playlistSize = 3,
            repeatMode = QueueRepeatMode.ONE
        )

        assertEquals(PlaybackFailureAdvanceAction.STOP, action)
    }

    @Test
    fun `repeat all failure wraps when current track is the last available option`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 2,
            playlistSize = 3,
            repeatMode = QueueRepeatMode.ALL
        )

        assertEquals(PlaybackFailureAdvanceAction.WRAP, action)
    }

    @Test
    fun `failure follows queue order after shuffle has already reordered playlist`() {
        val action = resolvePlaybackFailureAdvanceAction(
            currentIndex = 0,
            playlistSize = 3,
            repeatMode = QueueRepeatMode.ONE
        )

        assertEquals(PlaybackFailureAdvanceAction.NEXT, action)
    }
}
