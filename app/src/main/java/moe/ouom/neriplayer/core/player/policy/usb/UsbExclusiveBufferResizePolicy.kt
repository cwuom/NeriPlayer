package moe.ouom.neriplayer.core.player.policy.usb

internal fun shouldApplyActiveUsbBufferResize(
    streaming: Boolean,
    currentBufferMs: Int,
    targetBufferMs: Int
): Boolean {
    if (!streaming) return true
    return targetBufferMs >= currentBufferMs
}
