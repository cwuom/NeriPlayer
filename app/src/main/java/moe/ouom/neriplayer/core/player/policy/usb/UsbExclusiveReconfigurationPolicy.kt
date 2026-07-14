package moe.ouom.neriplayer.core.player.policy.usb

internal fun shouldSkipRedundantUsbExclusiveReconfiguration(
    reason: String,
    usbExclusiveEnabled: Boolean,
    hasHealthyPlayerPcmSession: Boolean
): Boolean {
    if (!usbExclusiveEnabled || !hasHealthyPlayerPcmSession) return false
    if (reason.contains("recovery", ignoreCase = true)) return false
    if (reason.contains("preference", ignoreCase = true)) return false
    return reason.contains("permission", ignoreCase = true) ||
        reason.contains("open_gate_retry", ignoreCase = true)
}
