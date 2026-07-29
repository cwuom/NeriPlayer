package moe.ouom.neriplayer.core.player.usb.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbExclusiveBackgroundAudioAnchorVolumeGuardTest {

    @Test
    fun `anchor keeps the pre-anchor USB volume snapshot active`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        val token = state.acquire(0.92f)

        assertEquals(0.92f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)

        state.release(token)

        assertNull(state.currentVolumeFractionOrNull())
    }

    @Test
    fun `stale anchor release cannot clear a newer volume snapshot`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        val firstToken = state.acquire(0.92f)
        val secondToken = state.acquire(0.67f)

        state.release(firstToken)

        assertEquals(0.67f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)

        state.release(secondToken)

        assertNull(state.currentVolumeFractionOrNull())
    }

    @Test
    fun `anchor snapshot is constrained to valid media volume bounds`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()

        state.acquire(2f)

        assertEquals(1f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)
    }
}
