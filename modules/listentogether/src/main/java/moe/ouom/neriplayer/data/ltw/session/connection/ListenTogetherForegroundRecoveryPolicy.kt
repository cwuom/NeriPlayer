package moe.ouom.neriplayer.data.ltw.session.connection

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState

enum class ListenTogetherForegroundRecoveryAction {
    NONE,
    CONNECT,
    REFRESH_ROOM_STATE
}

fun resolveListenTogetherForegroundRecoveryAction(
    connectionState: ListenTogetherConnectionState,
    roomId: String?,
    wsUrl: String?,
    reconnectEnabled: Boolean,
    connectingSinceElapsedMs: Long = 0L,
    nowElapsedMs: Long = 0L,
    connectingTimeoutMs: Long = DEFAULT_CONNECTING_TIMEOUT_MS
): ListenTogetherForegroundRecoveryAction {
    if (!reconnectEnabled || !hasForegroundReconnectTarget(roomId, wsUrl)) {
        return ListenTogetherForegroundRecoveryAction.NONE
    }
    return when (connectionState) {
        ListenTogetherConnectionState.DISCONNECTED -> ListenTogetherForegroundRecoveryAction.CONNECT
        ListenTogetherConnectionState.CONNECTED -> {
            ListenTogetherForegroundRecoveryAction.REFRESH_ROOM_STATE
        }
        ListenTogetherConnectionState.CONNECTING -> {
            if (hasConnectingTimedOut(connectingSinceElapsedMs, nowElapsedMs, connectingTimeoutMs)) {
                ListenTogetherForegroundRecoveryAction.CONNECT
            } else {
                ListenTogetherForegroundRecoveryAction.NONE
            }
        }
    }
}

private fun hasForegroundReconnectTarget(roomId: String?, wsUrl: String?): Boolean =
    !roomId.isNullOrBlank() && !wsUrl.isNullOrBlank()

private fun hasConnectingTimedOut(startedAtMs: Long, nowMs: Long, timeoutMs: Long): Boolean =
    startedAtMs > 0L && nowMs >= startedAtMs && nowMs - startedAtMs >= timeoutMs.coerceAtLeast(0L)

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
