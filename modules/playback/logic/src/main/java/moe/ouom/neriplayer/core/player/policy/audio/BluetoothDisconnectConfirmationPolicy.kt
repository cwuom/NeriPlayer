package moe.ouom.neriplayer.core.player.policy.audio

const val BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT = 3
const val BLUETOOTH_DISCONNECT_CONFIRM_INITIAL_DELAY_MS = 300L
const val BLUETOOTH_DISCONNECT_CONFIRM_SAMPLE_INTERVAL_MS = 300L

/**
 * 系统发出 BECOMING_NOISY 后约 1 秒才把媒体切到扬声器，期间蓝牙设备仍在输出列表中；
 * 采样窗口必须长于这段延迟，否则会在外放开始前把静音当作误报恢复
 */
const val BLUETOOTH_DISCONNECT_CONFIRMATION_MAX_SAMPLES = 5

fun bluetoothDisconnectSampleDelayMs(sampleIndex: Int): Long =
    if (sampleIndex == 0) BLUETOOTH_DISCONNECT_CONFIRM_INITIAL_DELAY_MS
    else BLUETOOTH_DISCONNECT_CONFIRM_SAMPLE_INTERVAL_MS

/**
 * 窗口用完且仍在蓝牙才算瞬时断连；已离开蓝牙时继续采样直到确认，
 * 否则窗口末尾刚切到扬声器的断开会在外放上恢复音量
 */
fun isBluetoothDisconnectTransient(sampledRoutesAreBluetooth: List<Boolean>): Boolean =
    sampledRoutesAreBluetooth.size >= BLUETOOTH_DISCONNECT_CONFIRMATION_MAX_SAMPLES &&
        sampledRoutesAreBluetooth.last()

fun shouldConfirmBluetoothDisconnect(
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
