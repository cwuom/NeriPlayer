package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCloudPlaybackPolicyTest {

    @Test
    fun `allows queueing only after every page has loaded`() {
        assertTrue(
            canQueueNeteaseCloudTracks(hasTracks = true, incomplete = false)
        )
    }

    @Test
    fun `blocks queueing while a partial prefix is displayed`() {
        assertFalse(
            canQueueNeteaseCloudTracks(hasTracks = true, incomplete = true)
        )
    }

    @Test
    fun `blocks queueing when no tracks are loaded`() {
        assertFalse(
            canQueueNeteaseCloudTracks(hasTracks = false, incomplete = false)
        )
        assertFalse(
            canQueueNeteaseCloudTracks(hasTracks = false, incomplete = true)
        )
    }
}
