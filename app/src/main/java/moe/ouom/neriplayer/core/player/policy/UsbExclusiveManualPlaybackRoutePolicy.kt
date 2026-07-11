package moe.ouom.neriplayer.core.player.policy

internal fun shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
    usbExclusivePlaybackEnabled: Boolean,
    allowMixedPlaybackEnabled: Boolean,
    hasUsbAudioOutput: Boolean,
    hasUsbHostAudioDevice: Boolean
): Boolean {
    if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
    return !hasUsbAudioOutput && !hasUsbHostAudioDevice
}
