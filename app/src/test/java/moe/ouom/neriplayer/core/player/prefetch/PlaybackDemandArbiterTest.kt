package moe.ouom.neriplayer.core.player.prefetch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDemandArbiterTest {

    @Test
    fun `markPlaybackDemand makes matching prefetch yield`() {
        val arbiter = PlaybackDemandArbiter()

        arbiter.markPlaybackDemand("ytmusic-video-high")

        assertTrue(arbiter.shouldYieldPrefetch("ytmusic-video-high"))
        assertFalse(arbiter.shouldYieldPrefetch("ytmusic-other-high"))
    }

    @Test
    fun `clearPlaybackDemand releases matching prefetch`() {
        val arbiter = PlaybackDemandArbiter()

        arbiter.markPlaybackDemand("ytmusic-video-high")
        arbiter.clearPlaybackDemand("ytmusic-video-high")

        assertFalse(arbiter.shouldYieldPrefetch("ytmusic-video-high"))
    }
}
