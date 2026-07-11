package moe.ouom.neriplayer.core.player.policy

internal fun isTransientUsbExclusiveOpenGate(reason: String): Boolean {
    val normalizedReason = reason.trim().lowercase()
    return normalizedReason.startsWith("native_open_deferred:route_jitter") ||
        normalizedReason.startsWith("native_open_deferred:native_close_in_flight") ||
        normalizedReason.startsWith("native_transition_in_flight") ||
        normalizedReason.startsWith("native_refresh_deferred")
}

internal fun shouldSuppressSystemFallbackForUsbExclusiveFailure(
    usbExclusivePlaybackEnabled: Boolean,
    reason: String
): Boolean {
    if (!usbExclusivePlaybackEnabled) return false
    val normalizedReason = reason.trim().lowercase()
    if (normalizedReason.isEmpty()) return false
    return normalizedReason.startsWith("native_open_deferred") ||
        normalizedReason.startsWith("native_reopen_cooling_down") ||
        normalizedReason.startsWith("native_open_failed") ||
        normalizedReason.startsWith("no_") ||
        normalizedReason == "usb_device_detached" ||
        normalizedReason.contains("no usb") ||
        normalizedReason.contains("no device") ||
        normalizedReason.contains("permission") ||
        normalizedReason.contains("securityexception") ||
        normalizedReason.contains("libusb_error_no_device") ||
        normalizedReason.contains("deviceonline=false") ||
        normalizedReason.contains("transport") ||
        normalizedReason.contains("transfer") ||
        normalizedReason.contains("submit") ||
        normalizedReason.contains("start") ||
        normalizedReason.contains("resume") ||
        normalizedReason.contains("play") ||
        normalizedReason.contains("pause") ||
        normalizedReason.contains("flush")
}
