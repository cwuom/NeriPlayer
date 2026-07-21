package moe.ouom.neriplayer.core.player.policy.usb

import java.util.concurrent.atomic.AtomicReference

internal class UsbExclusivePendingReopenGate {
    private val pendingReason = AtomicReference<String?>(null)

    fun request(reason: String) {
        val normalizedReason = reason.trim().takeIf(String::isNotEmpty) ?: return
        pendingReason.compareAndSet(null, normalizedReason)
    }

    fun takeIfNativeCloseComplete(nativeCloseInFlightCount: Int): String? {
        if (nativeCloseInFlightCount != 0) return null
        return pendingReason.getAndSet(null)
    }
}

internal fun isNativeCloseInFlightUsbExclusiveOpenGate(reason: String): Boolean {
    return reason.trim().lowercase().contains("native_close_in_flight")
}

internal fun isUsbDeviceDetachOpenGate(reason: String): Boolean {
    return reason.trim().lowercase().contains("usb_device_detached")
}

internal fun shouldHandleUsbAudioAttachAfterDetach(
    hasAudioStreamingInterface: Boolean,
    matchesSelectedDevice: Boolean,
    lastDetachGeneration: Long,
    lastAttachGeneration: Long
): Boolean {
    return hasAudioStreamingInterface &&
        matchesSelectedDevice &&
        lastDetachGeneration > lastAttachGeneration
}

internal fun shouldPreserveUsbDeviceDetachOpenBlock(
    existingReason: String,
    incomingReason: String
): Boolean {
    if (!isUsbDeviceDetachOpenGate(existingReason)) return false
    val normalizedIncoming = incomingReason.trim().lowercase()
    if (normalizedIncoming == "usb_exclusive_disabled") return false
    if (normalizedIncoming.contains("apply_policy_disabled")) return false
    if (normalizedIncoming.contains("player_release")) return false
    return true
}

internal fun shouldIgnoreStaleUsbDeviceDetachOpenBlock(
    incomingReason: String,
    lastDetachGeneration: Long,
    lastAttachGeneration: Long
): Boolean {
    val normalizedIncoming = incomingReason.trim().lowercase()
    val isDerivedDetachFailure = isUsbDeviceDetachOpenGate(normalizedIncoming) &&
        normalizedIncoming != "usb_device_detached"
    return isDerivedDetachFailure && lastAttachGeneration > lastDetachGeneration
}
