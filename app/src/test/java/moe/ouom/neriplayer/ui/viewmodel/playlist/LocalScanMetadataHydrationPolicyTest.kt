package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanMetadataHydrationPolicyTest {

    @Test
    fun `first metadata worker continues while playback is active`() {
        assertFalse(
            shouldThrottleScanMetadataHydration(
                workerSlot = 0,
                playbackIntentActive = true
            )
        )
    }

    @Test
    fun `extra metadata workers wait while playback is active`() {
        assertTrue(
            shouldThrottleScanMetadataHydration(
                workerSlot = 1,
                playbackIntentActive = true
            )
        )
    }

    @Test
    fun `all metadata workers run while playback is idle`() {
        assertFalse(
            shouldThrottleScanMetadataHydration(
                workerSlot = 7,
                playbackIntentActive = false
            )
        )
    }
}
