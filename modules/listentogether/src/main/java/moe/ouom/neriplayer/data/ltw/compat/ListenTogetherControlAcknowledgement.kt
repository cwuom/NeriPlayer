package moe.ouom.neriplayer.data.ltw.compat

import moe.ouom.neriplayer.data.ltw.playback.currentStableKey
import moe.ouom.neriplayer.data.ltw.playback.isListenTogetherSeekControlSatisfied
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

fun isListenTogetherPendingMemberControlSatisfied(
    event: ListenTogetherEvent,
    state: ListenTogetherRoomState?,
    seekSatisfiedDriftMs: Long = 1_500L,
    committedEventId: String? = null
): Boolean {
    val committedState = state ?: return false
    if (!isQueueMutationCommitted(event, committedState, committedEventId)) return false
    val requestedType = event.type.removePrefix("REQUEST_")
    if (requestedType == "SET_QUEUE") return isQueueControlSatisfied(event, committedState)
    if (requestedType == "SET_TRACK") return isTrackControlSatisfied(event, committedState)
    return isPlaybackControlSatisfied(requestedType, event, committedState, seekSatisfiedDriftMs)
}

private fun isQueueMutationCommitted(
    event: ListenTogetherEvent,
    state: ListenTogetherRoomState,
    committedEventId: String?
): Boolean {
    val mutation = event.queueMutation ?: return true
    val requestEventId = event.eventId
    if (requestEventId.isNullOrBlank()) return false
    return requestEventId == committedEventId && state.version > mutation.baseRoomVersion
}

private fun isPlaybackControlSatisfied(
    type: String,
    event: ListenTogetherEvent,
    state: ListenTogetherRoomState,
    seekSatisfiedDriftMs: Long
): Boolean {
    if (type == "PLAY") return state.playback.state == "playing"
    if (type == "PAUSE") return state.playback.state == "paused"
    if (type == "SEEK") return isSeekControlSatisfied(event, state, seekSatisfiedDriftMs)
    if (type == "PLAYBACK_MODE") return isPlaybackModeControlSatisfied(event, state)
    return false
}

private fun isSeekControlSatisfied(
    event: ListenTogetherEvent,
    state: ListenTogetherRoomState,
    satisfiedDriftMs: Long
): Boolean {
    val requestedPositionMs = event.positionMs ?: return false
    return isListenTogetherSeekControlSatisfied(state.playback, requestedPositionMs, satisfiedDriftMs)
}

private fun isPlaybackModeControlSatisfied(
    event: ListenTogetherEvent,
    state: ListenTogetherRoomState
): Boolean =
    (event.repeatMode == null || state.playback.repeatMode == event.repeatMode) &&
        (event.shuffleEnabled == null || state.playback.shuffleEnabled == event.shuffleEnabled)

private fun isTrackControlSatisfied(event: ListenTogetherEvent, state: ListenTogetherRoomState): Boolean {
    val requestedStableKey = event.track?.stableKey
        ?: event.queue?.getOrNull(event.currentIndex ?: -1)?.stableKey
        ?: return false
    return state.currentStableKey() == requestedStableKey
}

private fun isQueueControlSatisfied(event: ListenTogetherEvent, state: ListenTogetherRoomState): Boolean {
    if (event.queueMutation != null && event.queue == null) {
        val requestedStableKey = event.track?.stableKey
        return requestedStableKey == null || requestedStableKey == state.currentStableKey()
    }
    return isQueueSnapshotSatisfied(event, state)
}

private fun isQueueSnapshotSatisfied(event: ListenTogetherEvent, state: ListenTogetherRoomState): Boolean {
    val requestedQueue = event.queue ?: return false
    val requestedIndex = event.currentIndex ?: return false
    if (requestedQueue.isEmpty()) return isQueueClearSatisfied(requestedIndex, state)
    return isQueueSelectionSatisfied(requestedQueue, state, requestedIndex)
}

private fun isQueueSelectionSatisfied(requestedQueue: List<ListenTogetherTrack>, state: ListenTogetherRoomState, requestedIndex: Int): Boolean {
    return requestedIndex in requestedQueue.indices &&
        state.currentIndex == requestedIndex &&
        state.queue.map { it.stableKey } == requestedQueue.map { it.stableKey } &&
        state.currentStableKey() == requestedQueue[requestedIndex].stableKey
}

private fun isQueueClearSatisfied(requestedIndex: Int, state: ListenTogetherRoomState): Boolean =
    requestedIndex == -1 && state.queue.isEmpty() && state.currentIndex == -1 && state.track == null
