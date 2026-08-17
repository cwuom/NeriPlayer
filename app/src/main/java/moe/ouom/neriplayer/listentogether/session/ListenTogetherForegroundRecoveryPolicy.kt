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
    reconnectEnabled: Boolean
): ListenTogetherForegroundRecoveryAction {
    if (!reconnectEnabled || roomId.isNullOrBlank() || wsUrl.isNullOrBlank()) {
        return ListenTogetherForegroundRecoveryAction.NONE
    }
    return when (connectionState) {
        ListenTogetherConnectionState.DISCONNECTED -> ListenTogetherForegroundRecoveryAction.CONNECT
        ListenTogetherConnectionState.CONNECTED -> {
            ListenTogetherForegroundRecoveryAction.REFRESH_ROOM_STATE
        }
        ListenTogetherConnectionState.CONNECTING -> ListenTogetherForegroundRecoveryAction.NONE
    }
}

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
