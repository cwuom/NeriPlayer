package moe.ouom.neriplayer.core.player.policy.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNetworkResumePolicyTest {
    private val request = PlaybackNetworkResumeRequest(
        songKey = "bili:BV1:p2",
        requestToken = 7L,
        armedAtElapsedMs = 1_000L,
        reason = "refresh_failed"
    )

    @Test
    fun `hold only while playback is wanted and the stream needs a missing network`() {
        assertTrue(shouldHoldPlaybackForNetwork(playbackRequested = true, networkUsable = false, requiresNetwork = true))
        assertFalse(shouldHoldPlaybackForNetwork(playbackRequested = true, networkUsable = true, requiresNetwork = true))
        assertFalse(shouldHoldPlaybackForNetwork(playbackRequested = false, networkUsable = false, requiresNetwork = true))
        assertFalse(shouldHoldPlaybackForNetwork(playbackRequested = true, networkUsable = false, requiresNetwork = false))
    }

    @Test
    fun `resume when the network returns inside the window for the same untouched track`() {
        assertTrue(
            shouldResumePlaybackAfterNetworkRestored(
                request = request,
                nowElapsedMs = 1_000L + PLAYBACK_NETWORK_RESUME_WINDOW_MS,
                currentSongKey = "bili:BV1:p2",
                currentRequestToken = 7L,
                playbackRequested = false
            )
        )
    }

    @Test
    fun `do not resume after the window, a track change, a user command, or a manual play`() {
        fun resume(
            now: Long = 2_000L,
            song: String? = "bili:BV1:p2",
            token: Long = 7L,
            requested: Boolean = false
        ) = shouldResumePlaybackAfterNetworkRestored(request, now, song, token, requested)

        assertFalse(resume(now = 1_001L + PLAYBACK_NETWORK_RESUME_WINDOW_MS))
        assertFalse(resume(now = 500L))
        assertFalse(resume(song = "bili:BV1:p3"))
        assertFalse(resume(song = null))
        assertFalse(resume(token = 8L))
        assertFalse(resume(requested = true))
    }
}
