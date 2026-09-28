package moe.ouom.neriplayer.listentogether.session.state

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal fun latestListenTogetherAcceptedRoomVersion(
    lastAppliedRoomVersion: Long,
    currentState: ListenTogetherRoomState?
): Long {
    return maxOf(lastAppliedRoomVersion, currentState?.version ?: -1L)
}
