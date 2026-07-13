package moe.ouom.neriplayer.core.player.usb.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY

internal fun openPermittedUsbAudioDevice(
    context: Context,
    selectedDeviceKey: String = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
): Pair<UsbDevice, UsbDeviceConnection>? {
    val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: return null
    val targetDevice = usbManager.deviceList.values
        .sortedBy { it.deviceName }
        .firstOrNull { device ->
            runCatching { usbManager.hasPermission(device) }.getOrDefault(false) &&
                device.hasAudioStreamingInterface() &&
                device.matchesUsbExclusiveDeviceKey(selectedDeviceKey)
        }
        ?: return null
    val connection = usbManager.openDevice(targetDevice) ?: return null
    return targetDevice to connection
}

private fun UsbDevice.hasAudioStreamingInterface(): Boolean {
    return (0 until interfaceCount).any { index ->
        val usbInterface = getInterface(index)
        usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
            usbInterface.interfaceSubclass == 0x02
    }
}
