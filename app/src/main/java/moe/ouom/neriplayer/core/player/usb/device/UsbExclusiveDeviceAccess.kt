package moe.ouom.neriplayer.core.player.usb.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY

private const val TAG = "UsbExclusiveDeviceAccess"

internal fun hasPermittedUsbAudioOutput(
    context: Context,
    selectedDeviceKey: String = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
): Boolean {
    val appContext = context.applicationContext
    val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: return false
    if (selectPermittedUsbAudioDevice(usbManager, selectedDeviceKey).outcome !=
        UsbExclusiveDeviceSelectionOutcome.SELECTED
    ) {
        return false
    }
    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return false
    val outputs = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.isUsbExclusiveOutput() }
            .sortedBy(AudioDeviceInfo::getId)
            .groupBy { normalizeUsbExclusiveDeviceLabel(it.productName?.toString().orEmpty()) }
            .values
            .map { it.first() }
    }.getOrElse { emptyList() }
    return selectUsbExclusiveDevice(
        candidates = outputs,
        selectedDeviceKey = selectedDeviceKey,
        allowSingleFallback = true
    ) { device -> device.matchesUsbExclusiveDeviceKey(selectedDeviceKey) }.outcome ==
        UsbExclusiveDeviceSelectionOutcome.SELECTED
}

internal fun openPermittedUsbAudioDevice(
    context: Context,
    selectedDeviceKey: String = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
): Pair<UsbDevice, UsbDeviceConnection>? {
    val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: return null
    val selection = selectPermittedUsbAudioDevice(usbManager, selectedDeviceKey)
    val targetDevice = when (selection.outcome) {
        UsbExclusiveDeviceSelectionOutcome.SELECTED -> selection.device ?: return null
        UsbExclusiveDeviceSelectionOutcome.AMBIGUOUS -> {
            // auto 且在场多个 USB 音频设备:拒绝而非静默取首个,交由上层回退普通音频
            NPLogger.w(
                TAG,
                "openPermittedUsbAudioDevice(): ambiguous selection, refusing to guess; " +
                    "key=$selectedDeviceKey"
            )
            return null
        }
        UsbExclusiveDeviceSelectionOutcome.NONE -> return null
    }
    val connection = usbManager.openDevice(targetDevice) ?: return null
    return targetDevice to connection
}

private fun selectPermittedUsbAudioDevice(
    usbManager: UsbManager,
    selectedDeviceKey: String
): UsbExclusiveDeviceSelectionResult<UsbDevice> {
    val candidates = runCatching {
        usbManager.deviceList.values
            .sortedBy { it.deviceName }
            .filter { device ->
                runCatching { usbManager.hasPermission(device) }.getOrDefault(false) &&
                    device.hasAudioStreamingInterface()
            }
    }.getOrElse { emptyList() }
    // host 侧持 VID+PID+label,精确匹配可靠,匹配不到不退回其它设备
    return selectUsbExclusiveDevice(
        candidates = candidates,
        selectedDeviceKey = selectedDeviceKey,
        allowSingleFallback = false
    ) { device -> device.matchesUsbExclusiveDeviceKey(selectedDeviceKey) }
}

private fun UsbDevice.hasAudioStreamingInterface(): Boolean {
    return (0 until interfaceCount).any { index ->
        val usbInterface = getInterface(index)
        usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
            usbInterface.interfaceSubclass == 0x02
    }
}

private fun AudioDeviceInfo.isUsbExclusiveOutput(): Boolean {
    return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET
}
