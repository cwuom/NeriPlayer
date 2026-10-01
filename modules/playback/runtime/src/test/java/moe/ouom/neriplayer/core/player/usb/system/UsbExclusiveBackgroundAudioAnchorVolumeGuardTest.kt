package moe.ouom.neriplayer.core.player.usb.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbExclusiveBackgroundAudioAnchorVolumeGuardTest {

    @Test
    fun `anchor keeps the pre-anchor USB volume snapshot active`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        val token = state.acquire(0.92f)

        state.observeRouteVolume(0.67f)

        assertEquals(0.92f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)

        state.release(token)

        assertNull(state.currentVolumeFractionOrNull())
    }

    @Test
    fun `stable route volume changes adjust the protected USB snapshot`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        val token = state.acquire(0.50f)
        state.beginRouteObservation(token)
        state.observeRouteVolume(0.40f)
        state.observeRouteVolume(0.40f)

        state.applyUserVolumeChange(0.55f)

        assertEquals(0.625f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)
    }

    @Test
    fun `volume slider zero mutes the protected USB snapshot`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        val token = state.acquire(0.92f)
        state.beginRouteObservation(token)
        state.observeRouteVolume(0.67f)
        state.observeRouteVolume(0.67f)

        state.applyUserVolumeChange(0f)
        state.observeRouteVolume(0f)
        state.observeRouteVolume(0f)

        assertEquals(0f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)
    }

    @Test
    fun `volume slider maximum reaches full protected USB volume`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        val token = state.acquire(0.67f)
        state.beginRouteObservation(token)
        state.observeRouteVolume(0.40f)
        state.observeRouteVolume(0.40f)

        state.applyUserVolumeChange(1f)

        assertEquals(1f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)
    }

    @Test
    fun `unstable route transition cannot lower the protected USB snapshot`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        val token = state.acquire(0.92f)
        state.observeRouteVolume(0.92f)
        state.observeRouteVolume(0.92f)
        state.beginRouteObservation(token)

        state.applyUserVolumeChange(0.67f)

        assertEquals(0.92f, state.currentVolumeFractionOrNull() ?: -1f, 0.0001f)
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
