package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveFallbackPolicyTest {

    @Test
    fun `route jitter and close-in-flight gates remain transient`() {
        assertTrue(
            isTransientUsbExclusiveOpenGate(
                "native_open_deferred:route_jitter remainingMs=2117"
            )
        )
        assertTrue(
            isTransientUsbExclusiveOpenGate(
                "native_open_deferred:native_close_in_flight"
            )
        )
        assertFalse(isTransientUsbExclusiveOpenGate("usb_device_detached"))
    }

    @Test
    fun `close-in-flight gate waits for normal retry cadence`() {
        assertFalse(
            shouldBypassCooldownForUsbExclusiveOpenGateRetry(
                "native_open_deferred:native_close_in_flight count=1"
            )
        )
        assertTrue(
            shouldBypassCooldownForUsbExclusiveOpenGateRetry(
                "native_transition_in_flight"
            )
        )
    }

    @Test
    fun `physical detach suppresses system fallback while exclusive playback is enabled`() {
        assertTrue(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = "usb_device_detached"
            )
        )
    }

    @Test
    fun `native no-device runtime suppresses system fallback`() {
        assertTrue(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = "deviceOnline=false lastError=LIBUSB_ERROR_NO_DEVICE"
            )
        )
    }

    @Test
    fun `exclusive playback suppresses blank system fallback while native route is unresolved`() {
        assertTrue(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = ""
            )
        )
    }

    @Test
    fun `feedback scheduler gaps suppress noisy fallback retries`() {
        assertTrue(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = "lastError=async_feedback_scheduler_unavailable"
            )
        )
    }

    @Test
    fun `noisy USB route suppresses system fallback`() {
        assertTrue(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = "usb_audio_route_noisy"
            )
        )
    }

    @Test
    fun `native pause and resume failures suppress system fallback`() {
        assertTrue(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = "native_resume_before_write_failed"
            )
        )
        assertTrue(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = "native_pause_failed"
            )
        )
    }

    @Test
    fun `exclusive disabled allows system fallback`() {
        assertFalse(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = false,
                reason = "usb_device_detached"
            )
        )
    }

    @Test
    fun `format compatibility fallback remains available`() {
        assertFalse(
            shouldSuppressSystemFallbackForUsbExclusiveFailure(
                usbExclusivePlaybackEnabled = true,
                reason = "channel_mapping_requires_system_processor"
            )
        )
    }
}
