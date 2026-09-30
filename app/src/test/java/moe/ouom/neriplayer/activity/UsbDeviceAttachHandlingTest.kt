package moe.ouom.neriplayer.activity

import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceAttachHandlingTest {
    @Test
    fun `USB attach action is ignored when handling setting is off`() {
        assertFalse(
            shouldProcessUsbDeviceAttachedAction(
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                handlingEnabled = false
            )
        )
    }

    @Test
    fun `non USB attach action is still processed when handling setting is off`() {
        assertTrue(
            shouldProcessUsbDeviceAttachedAction(
                AudioManager.ACTION_AUDIO_BECOMING_NOISY,
                handlingEnabled = false
            )
        )
    }

    @Test
    fun `USB attach alias follows handling setting`() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            usbDeviceAttachAliasComponentState(handlingEnabled = true)
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            usbDeviceAttachAliasComponentState(handlingEnabled = false)
        )
    }
}
