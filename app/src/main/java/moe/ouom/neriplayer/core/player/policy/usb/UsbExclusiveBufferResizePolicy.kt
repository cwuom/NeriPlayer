package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveBackgroundBufferMs
import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveForegroundBufferMs

internal fun shouldApplyActiveUsbBufferResize(
    streaming: Boolean,
    currentBufferMs: Int,
    targetBufferMs: Int
): Boolean {
    if (streaming) return false
    return targetBufferMs != currentBufferMs
}

internal fun usbExclusiveTransferWindowDurationMs(
    bufferDurationMs: Int,
    appInForeground: Boolean
): Int {
    return if (appInForeground) {
        normalizeUsbExclusiveForegroundBufferMs(bufferDurationMs)
    } else {
        normalizeUsbExclusiveBackgroundBufferMs(bufferDurationMs)
    }
}
