package moe.ouom.neriplayer.core.player.usb.session

internal fun shouldHoldUsbExclusiveWakeLock(
    streaming: Boolean,
    transitioning: Boolean,
    transportCommandInFlight: Boolean,
    nativeCloseInFlightCount: Int
): Boolean {
    return streaming ||
        transitioning ||
        transportCommandInFlight ||
        nativeCloseInFlightCount > 0
}

internal fun resolveUsbExclusiveStreamingState(
    hasNativeHandle: Boolean,
    runtimeRunning: Boolean?,
    currentStreaming: Boolean
): Boolean {
    if (!hasNativeHandle) return false
    return runtimeRunning ?: currentStreaming
}

internal fun resolveUsbExclusivePausedState(
    hasActivePlayerSession: Boolean,
    runtimePaused: Boolean?,
    currentPaused: Boolean
): Boolean {
    if (!hasActivePlayerSession) return false
    return runtimePaused ?: currentPaused
}
