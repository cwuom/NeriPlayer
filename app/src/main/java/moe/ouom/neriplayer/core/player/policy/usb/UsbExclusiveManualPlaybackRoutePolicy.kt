package moe.ouom.neriplayer.core.player.policy.usb

internal fun shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
    usbExclusivePlaybackEnabled: Boolean,
    allowMixedPlaybackEnabled: Boolean,
    hasUsbAudioOutput: Boolean,
    hasUsbHostAudioDevice: Boolean
): Boolean {
    if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
    return !hasUsbAudioOutput && !hasUsbHostAudioDevice
}

internal fun shouldStopUsbExclusivePlaybackForNoisyRoute(
    usbExclusivePlaybackEnabled: Boolean,
    allowMixedPlaybackEnabled: Boolean,
    routeIsUsbOutput: Boolean,
    playbackActive: Boolean
): Boolean {
    if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
    if (!routeIsUsbOutput || !playbackActive) return false
    return true
}
