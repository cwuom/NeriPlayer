package moe.ouom.neriplayer.data.ltw.session.connection

fun shouldHoldListenTogetherBackgroundKeepAlive(
    sessionActive: Boolean,
    reconnectEnabled: Boolean,
    applicationInForeground: Boolean
): Boolean {
    return sessionActive && reconnectEnabled && !applicationInForeground
}

/**
 * A running playback foreground service already keeps the process alive, so a connected listener can let
 * the CPU sleep between room messages. The controller keeps the lock because its heartbeats must reach the
 * server within the 45 s controller timeout, and reconnect windows keep it so recovery is not stalled.
 */
fun shouldHoldListenTogetherBackgroundWakeLock(
    keepAliveNeeded: Boolean,
    isController: Boolean,
    playbackServiceForeground: Boolean,
    reconnecting: Boolean
): Boolean {
    return keepAliveNeeded && (isController || !playbackServiceForeground || reconnecting)
}
