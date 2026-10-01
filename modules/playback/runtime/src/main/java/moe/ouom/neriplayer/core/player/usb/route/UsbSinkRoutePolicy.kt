package moe.ouom.neriplayer.core.player.usb.route

internal const val USB_SINK_RECONFIGURE_DEBOUNCE_MS = 120L
private const val USB_SINK_RECONFIGURE_COOLDOWN_MS = 2_500L
private const val USB_SINK_OPEN_GATE_RETRY_DELAY_MS = 3_800L

internal fun UsbSinkRouteSnapshot.hasRouteMedia(): Boolean = playerInitialized && hasMediaItem

internal fun UsbSinkRouteSnapshot.hasReconfigurableItemCount(): Boolean = mediaItemCount > 0

internal fun UsbSinkRouteSnapshot.readyForDeferredSwitch(): Boolean =
    enabled && appInForeground && playerInitialized && hasMediaItem

internal fun UsbSinkRouteSnapshot.waitForPlaybackToStop(): Boolean = readyForDeferredSwitch() && playbackActive

internal fun sinkReconfigurationCooldownMs(
    reason: String,
    enabled: Boolean,
    bypassCooldown: Boolean
): Long = when {
    bypassCooldown -> USB_SINK_RECONFIGURE_DEBOUNCE_MS
    reason.contains("open_gate_retry", ignoreCase = true) -> USB_SINK_OPEN_GATE_RETRY_DELAY_MS
    enabled && (reason.contains("usb", ignoreCase = true) || reason.contains("native", ignoreCase = true)) ->
        USB_SINK_RECONFIGURE_COOLDOWN_MS
    else -> USB_SINK_RECONFIGURE_DEBOUNCE_MS
}
