package moe.ouom.neriplayer.core.player.policy.usb.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveFloatingKeepAlivePolicyTest {
    @Test
    fun `floating keepalive needs both the user switch and overlay permission`() {
        assertTrue(shouldShowUsbExclusiveFloatingKeepAlive(preferenceEnabled = true, overlayPermitted = true))
        assertFalse(shouldShowUsbExclusiveFloatingKeepAlive(preferenceEnabled = true, overlayPermitted = false))
        assertFalse(shouldShowUsbExclusiveFloatingKeepAlive(preferenceEnabled = false, overlayPermitted = true))
        assertFalse(shouldShowUsbExclusiveFloatingKeepAlive(preferenceEnabled = false, overlayPermitted = false))
    }
}
