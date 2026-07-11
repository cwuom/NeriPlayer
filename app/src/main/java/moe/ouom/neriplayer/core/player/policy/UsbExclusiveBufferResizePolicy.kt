package moe.ouom.neriplayer.core.player.policy

internal fun shouldApplyActiveUsbBufferResize(
    streaming: Boolean,
    currentBufferMs: Int,
    targetBufferMs: Int
): Boolean {
    return !streaming || targetBufferMs >= currentBufferMs
}
