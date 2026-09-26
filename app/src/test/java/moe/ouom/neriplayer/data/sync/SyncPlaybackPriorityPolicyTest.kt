package moe.ouom.neriplayer.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaybackPriorityPolicyTest {

    @Test
    fun `automatic sync waits while playback has priority`() {
        assertTrue(
            shouldDeferAutomaticSyncForPlayback(
                forceSync = false,
                triggerByUserAction = false,
                playbackIntentActive = true
            )
        )
    }

    @Test
    fun `manual sync is not deferred during playback`() {
        assertFalse(
            shouldDeferAutomaticSyncForPlayback(
                forceSync = true,
                triggerByUserAction = false,
                playbackIntentActive = true
            )
        )
        assertFalse(
            shouldDeferAutomaticSyncForPlayback(
                forceSync = false,
                triggerByUserAction = true,
                playbackIntentActive = true
            )
        )
    }

    @Test
    fun `idle automatic sync is not deferred`() {
        assertFalse(
            shouldDeferAutomaticSyncForPlayback(
                forceSync = false,
                triggerByUserAction = false,
                playbackIntentActive = false
            )
        )
    }
}
