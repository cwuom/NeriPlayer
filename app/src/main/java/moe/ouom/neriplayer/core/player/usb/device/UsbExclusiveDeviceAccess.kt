package moe.ouom.neriplayer.core.player.usb.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY

private const val TAG = "UsbExclusiveDeviceAccess"

internal fun openPermittedUsbAudioDevice(
    context: Context,
    selectedDeviceKey: String = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
): Pair<UsbDevice, UsbDeviceConnection>? {
    val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: return null
    val candidates = usbManager.deviceList.values
        .sortedBy { it.deviceName }
        .filter { device ->
            runCatching { usbManager.hasPermission(device) }.getOrDefault(false) &&
                device.hasAudioStreamingInterface()
        }
    // host 侧持 VID+PID+label,精确匹配可靠,匹配不到不退回其它设备
    val selection = selectUsbExclusiveDevice(
        candidates = candidates,
        selectedDeviceKey = selectedDeviceKey,
        allowSingleFallback = false
    ) { device -> device.matchesUsbExclusiveDeviceKey(selectedDeviceKey) }
    val targetDevice = when (selection.outcome) {
        UsbExclusiveDeviceSelectionOutcome.SELECTED -> selection.device ?: return null
        UsbExclusiveDeviceSelectionOutcome.AMBIGUOUS -> {
            // auto 且在场多个 USB 音频设备:拒绝而非静默取首个,交由上层回退普通音频
            NPLogger.w(
                TAG,
                "openPermittedUsbAudioDevice(): ambiguous selection, refusing to guess; " +
                    "candidates=${candidates.size} key=$selectedDeviceKey"
            )
            return null
        }
        UsbExclusiveDeviceSelectionOutcome.NONE -> return null
    }
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
