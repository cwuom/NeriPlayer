package moe.ouom.neriplayer.core.player.usb.device

import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY

internal fun UsbDevice.usbExclusiveDeviceKey(): String {
    return buildUsbExclusiveDeviceKey(
        vendorId = vendorId,
        productId = productId,
        label = stableUsbDeviceLabel()
    )
}

internal fun UsbDevice.matchesUsbExclusiveDeviceKey(deviceKey: String): Boolean {
    if (deviceKey == DEFAULT_USB_EXCLUSIVE_DEVICE_KEY) return true
    val selection = parseUsbExclusiveDeviceKey(deviceKey) ?: return false
    return vendorId == selection.vendorId &&
        productId == selection.productId &&
        normalizedDeviceLabel(stableUsbDeviceLabel()) == selection.label
}

internal fun AudioDeviceInfo.matchesUsbExclusiveDeviceKey(deviceKey: String): Boolean {
    if (deviceKey == DEFAULT_USB_EXCLUSIVE_DEVICE_KEY) return true
    val selection = parseUsbExclusiveDeviceKey(deviceKey) ?: return false
    return usbExclusiveDeviceKeyMatchesLabel(selection, productName?.toString().orEmpty())
}

internal fun usbExclusiveDeviceKeyMatchesLabel(deviceKey: String, label: String): Boolean {
    if (deviceKey == DEFAULT_USB_EXCLUSIVE_DEVICE_KEY) return true
    val selection = parseUsbExclusiveDeviceKey(deviceKey) ?: return false
    return usbExclusiveDeviceKeyMatchesLabel(selection, label)
}

internal fun usbExclusiveDeviceLabelFromKey(deviceKey: String): String? {
    return parseUsbExclusiveDeviceKey(deviceKey)?.label
        ?.replace('_', ' ')
        ?.takeIf(String::isNotBlank)
}

private data class UsbExclusiveDeviceSelection(
    val vendorId: Int,
    val productId: Int,
    val label: String
)

private fun buildUsbExclusiveDeviceKey(
    vendorId: Int,
    productId: Int,
    label: String
): String {
    return "usb:$vendorId:$productId:${normalizedDeviceLabel(label)}"
}

private fun parseUsbExclusiveDeviceKey(deviceKey: String): UsbExclusiveDeviceSelection? {
    val parts = deviceKey.split(':', limit = 4)
    if (parts.size != 4 || parts[0] != "usb") return null
    val vendorId = parts[1].toIntOrNull() ?: return null
    val productId = parts[2].toIntOrNull() ?: return null
    val label = parts[3].takeIf(String::isNotBlank) ?: return null
    return UsbExclusiveDeviceSelection(vendorId, productId, label)
}

private fun usbExclusiveDeviceKeyMatchesLabel(
    selection: UsbExclusiveDeviceSelection,
    label: String
): Boolean {
    val normalizedProduct = normalizedDeviceLabel(label)
    return normalizedProduct.isNotBlank() &&
        (normalizedProduct == selection.label || normalizedProduct.contains(selection.label))
}

private fun UsbDevice.stableUsbDeviceLabel(): String {
    return productName
        ?.takeIf(String::isNotBlank)
        ?: manufacturerName?.takeIf(String::isNotBlank)
        ?: deviceName
}

private fun normalizedDeviceLabel(value: String): String {
    return value.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
}
