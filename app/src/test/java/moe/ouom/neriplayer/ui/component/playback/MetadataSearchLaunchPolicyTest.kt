package moe.ouom.neriplayer.ui.component.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSearchLaunchPolicyTest {

    @Test
    fun `pending media load always defers automatic search`() {
        assertTrue(
            shouldDeferMetadataAutoSearch(
                pendingMediaLoad = true,
                playbackState = Player.STATE_IDLE,
                playWhenReady = false
            )
        )
    }

    @Test
    fun `active buffering defers automatic search`() {
        assertTrue(
            shouldDeferMetadataAutoSearch(
                pendingMediaLoad = false,
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = true
            )
        )
    }

    @Test
    fun `paused buffering does not block metadata search`() {
        assertFalse(
            shouldDeferMetadataAutoSearch(
                pendingMediaLoad = false,
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = false
            )
        )
    }

    @Test
    fun `ready playback allows automatic search`() {
        assertFalse(
            shouldDeferMetadataAutoSearch(
                pendingMediaLoad = false,
                playbackState = Player.STATE_READY,
                playWhenReady = true
            )
        )
    }
}
