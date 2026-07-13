package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.control.passivePositionUpdateTypes
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal fun shouldIgnoreListenTogetherIncomingState(
    cause: ListenTogetherCause?,
    currentUserId: String?,
    hasRecentOutboundEvent: (String) -> Boolean,
    hasRecentInboundEvent: (String) -> Boolean
): Boolean {
    if (cause?.type == "TRACK_FINISHED") return false
    val eventId = cause?.eventId
    if (cause?.type?.startsWith("REQUEST_") == true) return false
    if (!eventId.isNullOrBlank() && hasRecentOutboundEvent(eventId)) return true
    if (!eventId.isNullOrBlank() && hasRecentInboundEvent(eventId)) return true
    if (!eventId.isNullOrBlank() && cause?.userUuid == currentUserId) return true
    return false
}

internal fun shouldDeferListenTogetherIncomingStateForLocalTrackFinish(
    state: ListenTogetherRoomState,
    cause: ListenTogetherCause?,
    awaitingTrackFinishStableKey: String?
): Boolean {
    val waitingStableKey = awaitingTrackFinishStableKey ?: return false
    if (cause?.type !in passivePositionUpdateTypes) return false
    if (state.playback.state != "playing") return false
    return state.currentStableKey() == waitingStableKey
}

internal fun shouldApplyListenTogetherRoomStateToPlayer(
    candidateState: ListenTogetherRoomState,
    currentState: ListenTogetherRoomState?
): Boolean {
    if (currentState == null) return false
    if (candidateState.roomId != currentState.roomId) return false
    return candidateState.version >= currentState.version
}

internal fun shouldRepairListenTogetherListenerState(
    nowElapsedMs: Long,
    lastWebSocketMessageAtElapsedMs: Long,
    lastRefreshAtElapsedMs: Long,
    pendingVersionGap: Long,
    webSocketSilenceTimeoutMs: Long,
    repairMinIntervalMs: Long
): Boolean {
    if (
        lastRefreshAtElapsedMs > 0L &&
        nowElapsedMs - lastRefreshAtElapsedMs < repairMinIntervalMs
    ) {
        return false
    }
    if (pendingVersionGap >= 0L) return true
    if (lastWebSocketMessageAtElapsedMs <= 0L) return true
    return nowElapsedMs - lastWebSocketMessageAtElapsedMs >= webSocketSilenceTimeoutMs
}

internal const val LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS = 22_000L
internal const val LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS = 25_000L

internal fun resolveListenTogetherHeartbeatIntervalMs(
    isPlaying: Boolean,
    playingIntervalMs: Long,
    pausedIntervalMs: Long
): Long {
    return if (isPlaying) playingIntervalMs else pausedIntervalMs
}
