package moe.ouom.neriplayer.data.ltw.session.state

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

internal fun latestListenTogetherAcceptedRoomVersion(
    lastAppliedRoomVersion: Long,
    currentState: ListenTogetherRoomState?
): Long {
    return maxOf(lastAppliedRoomVersion, currentState?.version ?: -1L)
}
