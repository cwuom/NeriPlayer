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

internal fun shouldDeferUsbExclusiveNoisyRouteToNativePath(
    usbExclusivePlaybackEnabled: Boolean,
    allowMixedPlaybackEnabled: Boolean,
    routeIsUsbOutput: Boolean,
    nativePlayerPcmActive: Boolean
): Boolean {
    return usbExclusivePlaybackEnabled &&
        !allowMixedPlaybackEnabled &&
        routeIsUsbOutput &&
        nativePlayerPcmActive
}

internal fun shouldResumeUsbExclusivePlaybackAfterDeviceAttach(
    usbExclusivePlaybackEnabled: Boolean,
    allowMixedPlaybackEnabled: Boolean,
    hasInterruptedPlayback: Boolean,
    resumePlaybackRequested: Boolean,
    selectedUsbOutputAvailable: Boolean,
    selectedUsbHostPermissionGranted: Boolean,
    nativeOpenGateActive: Boolean
): Boolean {
    return usbExclusivePlaybackEnabled &&
        !allowMixedPlaybackEnabled &&
        hasInterruptedPlayback &&
        resumePlaybackRequested &&
        selectedUsbOutputAvailable &&
        selectedUsbHostPermissionGranted &&
        !nativeOpenGateActive
}

internal fun resolveUsbExclusiveInterruptedPlaybackQueueIndex(
    currentQueueIndex: Int,
    queueSize: Int,
    currentQueueIndexMatchesCurrentSong: Boolean,
    currentSongQueueIndex: Int
): Int? {
    val currentQueueIndexIsValid = currentQueueIndex in 0 until queueSize
    if (currentQueueIndexIsValid && currentQueueIndexMatchesCurrentSong) {
        return currentQueueIndex
    }
    if (currentSongQueueIndex in 0 until queueSize) {
        return currentSongQueueIndex
    }
    return currentQueueIndex.takeIf { currentQueueIndexIsValid }
}
