package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.usb.transport.suppressesSystemFallbackPlayback
import moe.ouom.neriplayer.core.player.usb.transport.usbExclusiveErrorCode

private val SYSTEM_FALLBACK_BLOCKED_PREFIXES = listOf(
    "native_open_deferred", "native_reopen_cooling_down", "native_open_failed", "no_"
)
private val SYSTEM_FALLBACK_BLOCKED_FRAGMENTS = listOf(
    "no usb", "no device", "permission", "securityexception", "libusb_error_no_device",
    "deviceonline=false", "route_noisy", "transport", "transfer", "submit", "start",
    "resume", "play", "pause", "flush"
)

internal fun isTransientUsbExclusiveOpenGate(reason: String): Boolean {
    val normalizedReason = reason.trim().lowercase()
    return normalizedReason.startsWith("native_open_deferred:route_jitter") ||
        normalizedReason.startsWith("native_open_deferred:native_close_in_flight") ||
        normalizedReason.startsWith("native_transition_in_flight") ||
        normalizedReason.startsWith("native_refresh_deferred")
}

internal fun shouldBypassCooldownForUsbExclusiveOpenGateRetry(reason: String): Boolean {
    val normalizedReason = reason.trim().lowercase()
    return normalizedReason.contains("native_transition_in_flight")
}

internal fun shouldSuppressSystemFallbackForUsbExclusiveFailure(
    usbExclusivePlaybackEnabled: Boolean,
    reason: String
): Boolean {
    if (!usbExclusivePlaybackEnabled) return false
    val normalizedReason = reason.trim().lowercase()
    if (normalizedReason.isEmpty()) return true
    if (reason.usbExclusiveErrorCode().suppressesSystemFallbackPlayback) return true
    return hasBlockedSystemFallbackPrefix(normalizedReason) ||
        normalizedReason == "usb_device_detached" ||
        hasBlockedSystemFallbackFragment(normalizedReason)
}

private fun hasBlockedSystemFallbackPrefix(reason: String): Boolean =
    SYSTEM_FALLBACK_BLOCKED_PREFIXES.any(reason::startsWith)

private fun hasBlockedSystemFallbackFragment(reason: String): Boolean =
    SYSTEM_FALLBACK_BLOCKED_FRAGMENTS.any(reason::contains)
