package moe.ouom.neriplayer.core.player.policy.audio

internal const val BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT = 3
internal const val BLUETOOTH_DISCONNECT_CONFIRM_INITIAL_DELAY_MS = 300L
internal const val BLUETOOTH_DISCONNECT_CONFIRM_SAMPLE_INTERVAL_MS = 300L

internal fun shouldConfirmBluetoothDisconnect(
    stopOnBluetoothDisconnectEnabled: Boolean,
    playbackActive: Boolean,
    previousRouteWasBluetooth: Boolean,
    sampledRoutesAreBluetooth: List<Boolean>
): Boolean {
    if (!stopOnBluetoothDisconnectEnabled || !playbackActive || !previousRouteWasBluetooth) {
        return false
    }
    if (sampledRoutesAreBluetooth.size < BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT) {
        return false
    }
    return sampledRoutesAreBluetooth
        .takeLast(BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT)
        .all { routeIsBluetooth -> !routeIsBluetooth }
}
