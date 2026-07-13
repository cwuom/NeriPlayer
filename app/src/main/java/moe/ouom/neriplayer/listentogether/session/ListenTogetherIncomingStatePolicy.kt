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
