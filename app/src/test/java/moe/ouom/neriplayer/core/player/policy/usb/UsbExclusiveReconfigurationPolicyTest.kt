package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveReconfigurationPolicyTest {

    @Test
    fun `skips delayed permission rebuild when native player session is already ready`() {
        assertTrue(
            shouldSkipRedundantUsbExclusiveReconfiguration(
                reason = "usb_permission_granted",
                usbExclusiveEnabled = true,
                hasHealthyPlayerPcmSession = true
            )
        )
    }

    @Test
    fun `skips stale open gate retry when native player session recovered independently`() {
        assertTrue(
            shouldSkipRedundantUsbExclusiveReconfiguration(
                reason = "usb_exclusive_open_gate_retry",
                usbExclusiveEnabled = true,
                hasHealthyPlayerPcmSession = true
            )
        )
    }

    @Test
    fun `never skips recovery or preference rebuilds`() {
        assertFalse(
            shouldSkipRedundantUsbExclusiveReconfiguration(
                reason = "usb_recovery:startup_zero_progress",
                usbExclusiveEnabled = true,
                hasHealthyPlayerPcmSession = true
            )
        )
        assertFalse(
            shouldSkipRedundantUsbExclusiveReconfiguration(
                reason = "usb_output_preferences_changed",
                usbExclusiveEnabled = true,
                hasHealthyPlayerPcmSession = true
            )
        )
    }

    @Test
    fun `does not skip when native session is unavailable`() {
        assertFalse(
            shouldSkipRedundantUsbExclusiveReconfiguration(
                reason = "usb_permission_granted",
                usbExclusiveEnabled = true,
                hasHealthyPlayerPcmSession = false
            )
        )
    }
}
