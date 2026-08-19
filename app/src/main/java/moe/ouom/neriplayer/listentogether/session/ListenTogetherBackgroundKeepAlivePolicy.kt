package moe.ouom.neriplayer.listentogether.session

internal fun shouldHoldListenTogetherBackgroundKeepAlive(
    sessionActive: Boolean,
    reconnectEnabled: Boolean,
    applicationInForeground: Boolean
): Boolean {
    return sessionActive && reconnectEnabled && !applicationInForeground
}
