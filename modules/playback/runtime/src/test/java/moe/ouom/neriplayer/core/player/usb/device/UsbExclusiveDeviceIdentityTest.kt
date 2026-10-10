package moe.ouom.neriplayer.core.player.usb.device

import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UsbExclusiveDeviceIdentityTest {

    @Test
    fun `device keys use the first usable usb label`() {
        assertEquals("usb:7:8:fiio_k7", usbDevice(productName = " FiiO K7 ", manufacturerName = "FiiO").usbExclusiveDeviceKey())
        assertEquals("usb:7:8:fiio_electronics", usbDevice(productName = null, manufacturerName = "FiiO Electronics").usbExclusiveDeviceKey())
        assertEquals("usb:7:8:dev_bus_usb_001_002", usbDevice(productName = " ", manufacturerName = null).usbExclusiveDeviceKey())
        assertEquals("usb:7:8:dev_bus_usb_001_002", usbDevice(productName = null, manufacturerName = " ").usbExclusiveDeviceKey())
    }

    @Test
    fun `usb devices match vendor, product and exact label`() {
        val device = usbDevice(productName = "DAC Pro", manufacturerName = null)

        assertTrue(device.matchesUsbExclusiveDeviceKey("auto"))
        assertTrue(device.matchesUsbExclusiveDeviceKey("usb:7:8:dac_pro"))
        assertFalse(device.matchesUsbExclusiveDeviceKey("usb:9:8:dac_pro"))
        assertFalse(device.matchesUsbExclusiveDeviceKey("usb:7:9:dac_pro"))
        assertFalse(device.matchesUsbExclusiveDeviceKey("usb:7:8:dac"))
        assertFalse(device.matchesUsbExclusiveDeviceKey("bogus"))
    }

    @Test
    fun `audio outputs match keys by their product label`() {
        val named = mock(AudioDeviceInfo::class.java)
        `when`(named.productName).thenReturn("DAC Pro")
        val unnamed = mock(AudioDeviceInfo::class.java)

        assertTrue(named.matchesUsbExclusiveDeviceKey("auto"))
        assertTrue(named.matchesUsbExclusiveDeviceKey("usb:1:2:dac_pro"))
        assertFalse(named.matchesUsbExclusiveDeviceKey("usb:1:2"))
        assertFalse(unnamed.matchesUsbExclusiveDeviceKey("usb:1:2:dac_pro"))
    }

    @Test
    fun `malformed device keys never match a label`() {
        assertTrue(usbExclusiveDeviceKeyMatchesLabel("auto", ""))
        assertTrue(usbExclusiveDeviceKeyMatchesLabel("usb:7:8:dac", "DAC"))
        listOf("usb:7:8", "hid:7:8:dac", "usb:x:8:dac", "usb:7:y:dac", "usb:7:8: ").forEach { key ->
            assertFalse(key, usbExclusiveDeviceKeyMatchesLabel(key, "DAC"))
        }
    }

    @Test
    fun `device labels are recovered from keys for display`() {
        assertEquals("fiio k7", usbExclusiveDeviceLabelFromKey("usb:7:8:fiio_k7"))
        assertNull(usbExclusiveDeviceLabelFromKey("usb:7:8:_"))
        assertNull(usbExclusiveDeviceLabelFromKey("auto"))
    }

    private fun usbDevice(productName: String?, manufacturerName: String?): UsbDevice =
        mock(UsbDevice::class.java).also {
            `when`(it.vendorId).thenReturn(7)
            `when`(it.productId).thenReturn(8)
            `when`(it.productName).thenReturn(productName)
            `when`(it.manufacturerName).thenReturn(manufacturerName)
            `when`(it.deviceName).thenReturn("/dev/bus/usb/001/002")
        }
}
