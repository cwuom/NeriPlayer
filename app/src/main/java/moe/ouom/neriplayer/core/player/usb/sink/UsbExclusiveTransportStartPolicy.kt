package moe.ouom.neriplayer.core.player.usb.sink

internal fun shouldStartUsbExclusiveNativeTransport(
    hasQueuedPcm: Boolean,
    queuedFrames: Long,
    requiredPrerollFrames: Long,
    allowShortPreroll: Boolean,
    resumingPausedTransport: Boolean
): Boolean {
    if (!hasQueuedPcm || queuedFrames <= 0L) return false
    return allowShortPreroll || resumingPausedTransport || queuedFrames >= requiredPrerollFrames
}
