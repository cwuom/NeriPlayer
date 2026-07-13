package moe.ouom.neriplayer.core.player.usb.session

internal fun shouldHoldUsbExclusiveWakeLock(
    streaming: Boolean,
    transitioning: Boolean,
    nativeCloseInFlightCount: Int
): Boolean {
    return streaming || transitioning || nativeCloseInFlightCount > 0
}
