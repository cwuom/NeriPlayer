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
        normalizeUsbExclusiveDeviceLabel(stableUsbDeviceLabel()) == selection.label
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

internal enum class UsbExclusiveDeviceSelectionOutcome {
    SELECTED,
    NONE,
    AMBIGUOUS
}

internal data class UsbExclusiveDeviceSelectionResult<T>(
    val outcome: UsbExclusiveDeviceSelectionOutcome,
    val device: T?
)

/**
 * 统一 host 与音频侧的独占设备选择判定,消除两侧各自按不同排序取首个导致"能力查自 A, 音频走 B"
 * 的选错/无声问题:
 * - 指定设备:选中精确匹配项(同一物理设备可能有多个入口,取首个);匹配不到且仅一个候选且
 *   allowSingleFallback 为真时退回该候选(兼容 productName 为空导致 label 不一致);否则视为未找到
 * - auto:仅当恰好一个候选时选中;多个候选返回 AMBIGUOUS(拒绝而非静默取首个),交由上层回退普通音频
 * 注:音频侧候选须先按物理设备去重后再传入,否则同一 DAC 的多个 AudioDeviceInfo 会被误判为多设备
 */
internal fun <T> selectUsbExclusiveDevice(
    candidates: List<T>,
    selectedDeviceKey: String,
    allowSingleFallback: Boolean,
    matches: (T) -> Boolean
): UsbExclusiveDeviceSelectionResult<T> {
    if (selectedDeviceKey != DEFAULT_USB_EXCLUSIVE_DEVICE_KEY) {
        val matched = candidates.filter(matches)
        return when {
            matched.size == 1 -> UsbExclusiveDeviceSelectionResult(
                UsbExclusiveDeviceSelectionOutcome.SELECTED,
                matched.first()
            )
            // 显式 key 命中多个物理设备(如两台同型号 DAC)无法区分具体单元,
            // 拒绝而非静默取首个,避免能力查询与音频输出落到不同设备(H1/M3)
            matched.size > 1 -> UsbExclusiveDeviceSelectionResult(
                UsbExclusiveDeviceSelectionOutcome.AMBIGUOUS,
                null
            )
            allowSingleFallback && candidates.size == 1 -> UsbExclusiveDeviceSelectionResult(
                UsbExclusiveDeviceSelectionOutcome.SELECTED,
                candidates.first()
            )
            // 指定设备不在场:拒绝(NONE),不从无关设备里挑选
            else -> UsbExclusiveDeviceSelectionResult(
                UsbExclusiveDeviceSelectionOutcome.NONE,
                null
            )
        }
    }
    return when (candidates.size) {
        0 -> UsbExclusiveDeviceSelectionResult(UsbExclusiveDeviceSelectionOutcome.NONE, null)
        1 -> UsbExclusiveDeviceSelectionResult(
            UsbExclusiveDeviceSelectionOutcome.SELECTED,
            candidates.first()
        )
        else -> UsbExclusiveDeviceSelectionResult(
            UsbExclusiveDeviceSelectionOutcome.AMBIGUOUS,
            null
        )
    }
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
    return "usb:$vendorId:$productId:${normalizeUsbExclusiveDeviceLabel(label)}"
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
    // 精确相等,避免子串误命中(如键 "dac" 命中 "dac_pro"),与 host 侧 VID+PID+label 精确匹配对齐
    val normalizedProduct = normalizeUsbExclusiveDeviceLabel(label)
    return normalizedProduct.isNotBlank() && normalizedProduct == selection.label
}

private fun UsbDevice.stableUsbDeviceLabel(): String {
    return productName
        ?.takeIf(String::isNotBlank)
        ?: manufacturerName?.takeIf(String::isNotBlank)
        ?: deviceName
}

internal fun normalizeUsbExclusiveDeviceLabel(value: String): String {
    return value.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
}
