package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState

internal enum class ListenTogetherForegroundRecoveryAction {
    NONE,
    CONNECT,
    REFRESH_ROOM_STATE
}

internal fun resolveListenTogetherForegroundRecoveryAction(
    connectionState: ListenTogetherConnectionState,
    roomId: String?,
    wsUrl: String?,
    reconnectEnabled: Boolean,
    connectingSinceElapsedMs: Long = 0L,
    nowElapsedMs: Long = 0L,
    connectingTimeoutMs: Long = DEFAULT_CONNECTING_TIMEOUT_MS
): ListenTogetherForegroundRecoveryAction {
    if (!reconnectEnabled || roomId.isNullOrBlank() || wsUrl.isNullOrBlank()) {
        return ListenTogetherForegroundRecoveryAction.NONE
    }
    return when (connectionState) {
        ListenTogetherConnectionState.DISCONNECTED -> ListenTogetherForegroundRecoveryAction.CONNECT
        ListenTogetherConnectionState.CONNECTED -> {
            ListenTogetherForegroundRecoveryAction.REFRESH_ROOM_STATE
        }
        ListenTogetherConnectionState.CONNECTING -> {
            val connectingDurationMs = nowElapsedMs - connectingSinceElapsedMs
            if (
                connectingSinceElapsedMs in 1 downTo nowElapsedMs &&
                connectingDurationMs >= connectingTimeoutMs.coerceAtLeast(0L)
            ) {
                ListenTogetherForegroundRecoveryAction.CONNECT
            } else {
                ListenTogetherForegroundRecoveryAction.NONE
            }
        }
    }
}

private const val DEFAULT_CONNECTING_TIMEOUT_MS = 15_000L

internal fun shouldReconnectListenTogetherForegroundSocket(
    reconnectEnabled: Boolean,
    connectionState: ListenTogetherConnectionState,
    expectedRoomId: String?,
    currentRoomId: String?,
    lastWebSocketMessageAtElapsedMs: Long,
    probeStartedAtElapsedMs: Long
): Boolean {
    return reconnectEnabled &&
        connectionState == ListenTogetherConnectionState.CONNECTED &&
        !expectedRoomId.isNullOrBlank() &&
        expectedRoomId == currentRoomId &&
        lastWebSocketMessageAtElapsedMs < probeStartedAtElapsedMs
}
