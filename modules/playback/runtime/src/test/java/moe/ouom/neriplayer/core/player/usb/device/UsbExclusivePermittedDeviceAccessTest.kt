package moe.ouom.neriplayer.core.player.usb.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UsbExclusivePermittedDeviceAccessTest {

    private val usbManager = mock(UsbManager::class.java)
    private val connection = mock(UsbDeviceConnection::class.java)

    @Test
    fun `missing or foreign usb service opens nothing`() {
        assertNull(openPermittedUsbAudioDevice(context(usbService = null)))
        assertNull(openPermittedUsbAudioDevice(context(usbService = Any())))
    }

    @Test
    fun `auto selection opens the only permitted dac with a streaming interface`() {
        val dac = usbDevice("/dev/bus/usb/001/004", "DAC One", streamingInterface())
        val locked = usbDevice("/dev/bus/usb/001/002", "Locked DAC", streamingInterface())
        val revoked = usbDevice("/dev/bus/usb/001/003", "Revoked DAC", streamingInterface())
        val controlOnly = usbDevice(
            "/dev/bus/usb/001/005",
            "Headset Buttons",
            usbInterface(UsbConstants.USB_CLASS_AUDIO, subclass = 0x01),
            usbInterface(UsbConstants.USB_CLASS_HID, subclass = 0x00)
        )
        val keyboard = usbDevice("/dev/bus/usb/001/006", "Keyboard", usbInterface(UsbConstants.USB_CLASS_HID, 0x02))
        attach(dac, locked, revoked, controlOnly, keyboard)
        grant(dac, controlOnly, keyboard)
        `when`(usbManager.hasPermission(revoked)).thenThrow(SecurityException("revoked"))
        `when`(usbManager.openDevice(dac)).thenReturn(connection)

        val opened = openPermittedUsbAudioDevice(context(usbManager))

        assertSame(dac, opened?.first)
        assertSame(connection, opened?.second)
    }

    @Test
    fun `auto selection refuses to guess between two permitted dacs`() {
        val first = usbDevice("/dev/bus/usb/001/002", "DAC One", streamingInterface())
        val second = usbDevice("/dev/bus/usb/001/003", "DAC Two", streamingInterface())
        attach(first, second)
        grant(first, second)

        assertNull(openPermittedUsbAudioDevice(context(usbManager)))
        verify(usbManager, never()).openDevice(any())
    }

    @Test
    fun `explicit key opens the matching dac and never substitutes another`() {
        val first = usbDevice("/dev/bus/usb/001/002", "DAC One", streamingInterface())
        val second = usbDevice("/dev/bus/usb/001/003", "DAC Two", streamingInterface())
        attach(first, second)
        grant(first, second)
        `when`(usbManager.openDevice(second)).thenReturn(connection)

        val opened = openPermittedUsbAudioDevice(context(usbManager), "usb:4660:22136:dac_two")
        val absent = openPermittedUsbAudioDevice(context(usbManager), "usb:4660:22136:dac_three")

        assertSame(second, opened?.first)
        assertSame(connection, opened?.second)
        assertNull(absent)
        verify(usbManager, never()).openDevice(first)
    }

    @Test
    fun `refused connections and failing device queries open nothing`() {
        val dac = usbDevice("/dev/bus/usb/001/002", "DAC One", streamingInterface())
        attach(dac)
        grant(dac)

        assertNull(openPermittedUsbAudioDevice(context(usbManager)))
        verify(usbManager).openDevice(dac)

        `when`(usbManager.deviceList).thenThrow(IllegalStateException("usb service died"))
        assertNull(openPermittedUsbAudioDevice(context(usbManager)))
    }

    @Test
    fun `permitted host with one usb dac sink reports an exclusive output`() {
        val dac = usbDevice("/dev/bus/usb/001/002", "DAC One", streamingInterface())
        attach(dac)
        grant(dac)
        val audioManager = audioManager(
            audioOutput(id = 12, type = AudioDeviceInfo.TYPE_USB_HEADSET, name = "DAC One"),
            audioOutput(id = 4, type = AudioDeviceInfo.TYPE_USB_DEVICE, name = "DAC One"),
            audioOutput(id = 2, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, name = "Speaker"),
            audioOutput(id = 5, type = AudioDeviceInfo.TYPE_USB_ACCESSORY, name = "Mic", sink = false)
        )

        assertTrue(hasPermittedUsbAudioOutput(context(usbManager, audioManager)))
    }

    @Test
    fun `sinks of two different dacs need an explicit key`() {
        val dac = usbDevice("/dev/bus/usb/001/002", "DAC One", streamingInterface())
        attach(dac)
        grant(dac)
        val audioManager = audioManager(
            audioOutput(id = 4, type = AudioDeviceInfo.TYPE_USB_DEVICE, name = "DAC One"),
            audioOutput(id = 6, type = AudioDeviceInfo.TYPE_USB_ACCESSORY, name = "Dock DAC")
        )
        val context = context(usbManager, audioManager)

        assertFalse(hasPermittedUsbAudioOutput(context))
        assertTrue(hasPermittedUsbAudioOutput(context, "usb:4660:22136:dac_one"))
    }

    @Test
    fun `missing permission audio service or output list reports no exclusive output`() {
        val dac = usbDevice("/dev/bus/usb/001/002", "DAC One", streamingInterface())
        attach(dac)
        val audioManager = audioManager(
            audioOutput(id = 4, type = AudioDeviceInfo.TYPE_USB_DEVICE, name = "DAC One")
        )
        val withoutPermission = context(usbManager, audioManager)

        assertFalse(hasPermittedUsbAudioOutput(withoutPermission))
        verify(withoutPermission, never()).getSystemService(Context.AUDIO_SERVICE)

        grant(dac)
        assertFalse(hasPermittedUsbAudioOutput(context(usbManager, audioService = null)))

        `when`(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            .thenThrow(IllegalStateException("audio server died"))
        assertFalse(hasPermittedUsbAudioOutput(context(usbManager, audioManager)))
    }

    private fun context(usbService: Any?, audioService: Any? = null): Context =
        mock(Context::class.java).also {
            `when`(it.applicationContext).thenReturn(it)
            `when`(it.getSystemService(Context.USB_SERVICE)).thenReturn(usbService)
            `when`(it.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioService)
        }

    private fun attach(vararg devices: UsbDevice) {
        val attached = HashMap<String, UsbDevice>().apply {
            devices.forEach { put(it.deviceName, it) }
        }
        `when`(usbManager.deviceList).thenReturn(attached)
    }

    private fun grant(vararg devices: UsbDevice) {
        devices.forEach { `when`(usbManager.hasPermission(it)).thenReturn(true) }
    }

    private fun streamingInterface(): UsbInterface =
        usbInterface(UsbConstants.USB_CLASS_AUDIO, subclass = 0x02)

    private fun usbInterface(interfaceClass: Int, subclass: Int): UsbInterface =
        mock(UsbInterface::class.java).also {
            `when`(it.interfaceClass).thenReturn(interfaceClass)
            `when`(it.interfaceSubclass).thenReturn(subclass)
        }

    private fun usbDevice(path: String, product: String, vararg interfaces: UsbInterface): UsbDevice =
        mock(UsbDevice::class.java).also { device ->
            `when`(device.deviceName).thenReturn(path)
            `when`(device.productName).thenReturn(product)
            `when`(device.vendorId).thenReturn(0x1234)
            `when`(device.productId).thenReturn(0x5678)
            `when`(device.interfaceCount).thenReturn(interfaces.size)
            interfaces.forEachIndexed { index, usbInterface ->
                `when`(device.getInterface(index)).thenReturn(usbInterface)
            }
        }

    private fun audioManager(vararg outputs: AudioDeviceInfo): AudioManager =
        mock(AudioManager::class.java).also {
            `when`(it.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(*outputs))
        }

    private fun audioOutput(id: Int, type: Int, name: String, sink: Boolean = true): AudioDeviceInfo =
        mock(AudioDeviceInfo::class.java).also {
            `when`(it.id).thenReturn(id)
            `when`(it.type).thenReturn(type)
            `when`(it.productName).thenReturn(name)
            `when`(it.isSink).thenReturn(sink)
        }
}
