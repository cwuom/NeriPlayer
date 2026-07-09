package moe.ouom.neriplayer.core.player.debug

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.util.NPLogger

internal object UsbExclusiveDebugLogger {
    private const val TAG = "NERI-UsbExclusive"

    fun logSnapshot(
        context: Context,
        audioManager: AudioManager,
        reason: String,
        enabled: Boolean,
        preferredDevice: AudioDeviceInfo? = null
    ) {
        val outputDevices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrElse { error ->
            NPLogger.w(TAG, "snapshot($reason): failed to query audio outputs", error)
            emptyList()
        }
        val usbOutputs = outputDevices.filter { isUsbOutputType(it.type) }

        NPLogger.d(
            TAG,
            "snapshot($reason): enabled=$enabled, preferred=${preferredDevice.describeAudioDevice()}, " +
                "audioOutputs=${outputDevices.size}, usbAudioOutputs=${usbOutputs.size}, " +
                "outputs=${outputDevices.joinToString(prefix = "[", postfix = "]") { it.describeAudioDevice() }}"
        )
        logUsbHostDevices(context, reason)
    }

    fun logAudioDeviceCallback(
        reason: String,
        devices: Array<out AudioDeviceInfo>?
    ) {
        NPLogger.d(
            TAG,
            "$reason: count=${devices?.size ?: 0}, " +
                "devices=${devices.orEmpty().joinToString(prefix = "[", postfix = "]") { it.describeAudioDevice() }}"
        )
    }

    fun logFocusPolicy(
        usbExclusivePlayback: Boolean,
        allowMixedPlayback: Boolean,
        handleFocus: Boolean
    ) {
        NPLogger.d(
            TAG,
            "audioFocusPolicy(): usbExclusivePlayback=$usbExclusivePlayback, " +
                "allowMixedPlayback=$allowMixedPlayback, handleFocus=$handleFocus"
        )
    }

    private fun logUsbHostDevices(context: Context, reason: String) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            NPLogger.w(TAG, "usbHost($reason): UsbManager unavailable")
            return
        }

        val devices = runCatching {
            usbManager.deviceList.values.toList()
        }.getOrElse { error ->
            NPLogger.w(TAG, "usbHost($reason): failed to query USB devices", error)
            emptyList()
        }

        NPLogger.d(
            TAG,
            "usbHost($reason): devices=${devices.size}, " +
                "list=${devices.joinToString(prefix = "[", postfix = "]") { it.describeUsbDevice(usbManager) }}"
        )
    }

    @SuppressLint("MissingPermission")
    private fun AudioDeviceInfo?.describeAudioDevice(): String {
        if (this == null) return "none"
        val name = runCatching { productName?.toString().orEmpty() }
            .getOrElse { "productName_error:${it.javaClass.simpleName}" }
            .ifBlank { "blank" }
        val address = runCatching { address }
            .getOrElse { "address_error:${it.javaClass.simpleName}" }
            .ifBlank { "blank" }

        return "id=$id,type=${audioDeviceTypeName(type)}($type),name=$name,address=$address," +
            "sink=$isSink,source=$isSource,rates=${sampleRates.compactForLog()}," +
            "channels=${channelCounts.compactForLog()},encodings=${encodings.compactForLog()}"
    }

    private fun UsbDevice.describeUsbDevice(usbManager: UsbManager): String {
        val permissionState = runCatching { usbManager.hasPermission(this) }
            .fold(
                onSuccess = { it.toString() },
                onFailure = { "error:${it.javaClass.simpleName}" }
            )

        return "name=$deviceName,vendor=$vendorId,product=$productId," +
            "class=${usbClassName(deviceClass)}($deviceClass),subclass=$deviceSubclass," +
            "protocol=$deviceProtocol,interfaces=$interfaceCount,hasPermission=$permissionState"
    }

    private fun IntArray.compactForLog(maxItems: Int = 8): String {
        if (isEmpty()) return "[]"
        val visible = take(maxItems).joinToString(prefix = "[", postfix = "]")
        return if (size <= maxItems) visible else "$visible+$size"
    }

    private fun audioDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
            AudioDeviceInfo.TYPE_DOCK -> "DOCK"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            else -> bleAudioDeviceTypeName(type) ?: "UNKNOWN"
        }
    }

    private fun bleAudioDeviceTypeName(type: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return when (type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
            else -> null
        }
    }

    private fun usbClassName(deviceClass: Int): String {
        return when (deviceClass) {
            android.hardware.usb.UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
            android.hardware.usb.UsbConstants.USB_CLASS_COMM -> "COMM"
            android.hardware.usb.UsbConstants.USB_CLASS_HID -> "HID"
            android.hardware.usb.UsbConstants.USB_CLASS_HUB -> "HUB"
            android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE"
            android.hardware.usb.UsbConstants.USB_CLASS_PER_INTERFACE -> "PER_INTERFACE"
            android.hardware.usb.UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPEC"
            else -> "UNKNOWN"
        }
    }
}
