package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveOpenGatePolicyTest {

    @Test
    fun `rapid UAC1 replay attempts wait for close and produce one reopen`() {
        val gate = UsbExclusivePendingReopenGate()

        gate.request("usb_exclusive_enabled")
        gate.request("native_close_in_flight")

        assertNull(gate.takeIfNativeCloseComplete(nativeCloseInFlightCount = 1))
        assertEquals(
            "usb_exclusive_enabled",
            gate.takeIfNativeCloseComplete(nativeCloseInFlightCount = 0)
        )
        assertNull(gate.takeIfNativeCloseComplete(nativeCloseInFlightCount = 0))
    }

    @Test
    fun `UAC1 attach after UAC2 detach is accepted as a new device event`() {
        assertTrue(
            shouldHandleUsbAudioAttachAfterDetach(
                hasAudioStreamingInterface = true,
                matchesSelectedDevice = true,
                lastDetachGeneration = 8L,
                lastAttachGeneration = 7L
            )
        )
    }

    @Test
    fun `unrelated or duplicate attach cannot clear the detach gate`() {
        assertFalse(
            shouldHandleUsbAudioAttachAfterDetach(
                hasAudioStreamingInterface = false,
                matchesSelectedDevice = true,
                lastDetachGeneration = 8L,
                lastAttachGeneration = 7L
            )
        )
        assertFalse(
            shouldHandleUsbAudioAttachAfterDetach(
                hasAudioStreamingInterface = true,
                matchesSelectedDevice = false,
                lastDetachGeneration = 8L,
                lastAttachGeneration = 7L
            )
        )
        assertFalse(
            shouldHandleUsbAudioAttachAfterDetach(
                hasAudioStreamingInterface = true,
                matchesSelectedDevice = true,
                lastDetachGeneration = 8L,
                lastAttachGeneration = 9L
            )
        )
    }

    @Test
    fun `repeated playback failure cannot extend a physical detach cooldown`() {
        assertTrue(
            shouldPreserveUsbDeviceDetachOpenBlock(
                existingReason = "usb_device_detached",
                incomingReason =
                    "native_failure:native_open_deferred:usb_device_detached remainingMs=671"
            )
        )
        assertTrue(
            shouldPreserveUsbDeviceDetachOpenBlock(
                existingReason = "usb_device_detached",
                incomingReason = "failover:native_pause_failed"
            )
        )
        assertTrue(
            shouldPreserveUsbDeviceDetachOpenBlock(
                existingReason = "usb_device_detached",
                incomingReason = "usb_device_detached"
            )
        )
    }

    @Test
    fun `explicit disable replaces the physical detach cooldown`() {
        assertFalse(
            shouldPreserveUsbDeviceDetachOpenBlock(
                existingReason = "usb_device_detached",
                incomingReason = "usb_exclusive_disabled"
            )
        )
    }

    @Test
    fun `stale detached failure is ignored after a selected audio device attaches`() {
        assertTrue(
            shouldIgnoreStaleUsbDeviceDetachOpenBlock(
                incomingReason = "native_failure:native_open_deferred:usb_device_detached",
                lastDetachGeneration = 4L,
                lastAttachGeneration = 5L
            )
        )
        assertFalse(
            shouldIgnoreStaleUsbDeviceDetachOpenBlock(
                incomingReason = "usb_device_detached",
                lastDetachGeneration = 6L,
                lastAttachGeneration = 5L
            )
        )
    }

    @Test
    fun `native close gate remains distinct from device incompatibility`() {
        assertTrue(
            isNativeCloseInFlightUsbExclusiveOpenGate(
                "native_open_deferred:native_close_in_flight count=1"
            )
        )
        assertFalse(
            isNativeCloseInFlightUsbExclusiveOpenGate(
                "no_compatible_usb_audio_format"
            )
        )
    }
}
