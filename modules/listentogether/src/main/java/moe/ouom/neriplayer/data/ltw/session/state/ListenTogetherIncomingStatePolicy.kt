package moe.ouom.neriplayer.data.ltw.session.state

import moe.ouom.neriplayer.data.ltw.control.passivePositionUpdateTypes
import moe.ouom.neriplayer.data.ltw.playback.currentStableKey
import moe.ouom.neriplayer.data.ltw.playback.isListenTogetherQueueUpdateCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

/**
 * a higher-version room queue is authoritative over any local optimistic
 * reorder, including a local echo that arrives after another member's update
 */
fun shouldAcceptListenTogetherAuthoritativeQueueUpdate(
    cause: ListenTogetherCause?,
    candidateState: ListenTogetherRoomState?,
    currentState: ListenTogetherRoomState?
): Boolean {
    if (!isListenTogetherQueueUpdateCause(cause?.type)) return false
    val candidate = candidateState ?: return false
    val current = currentState ?: return false
    if (candidate.roomId != current.roomId || candidate.version <= current.version) return false
    return hasQueueIdentityChanged(candidate, current)
}

private fun hasQueueIdentityChanged(candidate: ListenTogetherRoomState, current: ListenTogetherRoomState): Boolean {
    if (candidate.queue.map { it.stableKey } != current.queue.map { it.stableKey }) {
        return true
    }
    return candidate.currentIndex != current.currentIndex ||
        candidate.currentStableKey() != current.currentStableKey()
}

internal fun shouldIgnoreListenTogetherIncomingState(
    cause: ListenTogetherCause?,
    currentUserId: String?,
    hasRecentOutboundEvent: (String) -> Boolean,
    hasRecentInboundEvent: (String) -> Boolean
): Boolean {
    val eventCause = cause ?: return false
    if (!shouldTrackControlCause(eventCause.type)) return false
    val eventId = trackedControlEventId(eventCause.eventId) ?: return false
    return hasRecentOutboundEvent(eventId) || hasRecentInboundEvent(eventId) ||
        eventCause.userUuid == currentUserId
}

private fun shouldTrackControlCause(type: String?): Boolean {
    if (type == "TRACK_FINISHED") return false
    return type?.startsWith("REQUEST_") != true
}

private fun trackedControlEventId(eventId: String?): String? = if (eventId.isNullOrBlank()) null else eventId

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

fun shouldApplyListenTogetherRoomStateToPlayer(
    candidateState: ListenTogetherRoomState,
    currentState: ListenTogetherRoomState?
): Boolean {
    if (currentState == null) return false
    if (candidateState.roomId != currentState.roomId) return false
    return candidateState.version >= currentState.version
}

fun shouldRepairListenTogetherListenerState(
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

const val LISTEN_TOGETHER_PLAYING_HEARTBEAT_INTERVAL_MS = 22_000L
const val LISTEN_TOGETHER_PAUSED_HEARTBEAT_INTERVAL_MS = 25_000L

fun resolveListenTogetherHeartbeatIntervalMs(
    isPlaying: Boolean,
    playingIntervalMs: Long,
    pausedIntervalMs: Long
): Long {
    return if (isPlaying) playingIntervalMs else pausedIntervalMs
}
