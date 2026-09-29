package moe.ouom.neriplayer.listentogether.session.membership

import moe.ouom.neriplayer.listentogether.protocol.model.room.ListenTogetherRoomState

internal fun shouldApplyListenTogetherClosedRoomPause(
    state: ListenTogetherRoomState
): Boolean {
    return state.playback.state == "paused"
}
