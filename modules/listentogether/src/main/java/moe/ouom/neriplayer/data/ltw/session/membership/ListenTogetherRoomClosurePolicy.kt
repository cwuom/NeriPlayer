package moe.ouom.neriplayer.data.ltw.session.membership

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

internal fun shouldApplyListenTogetherClosedRoomPause(
    state: ListenTogetherRoomState
): Boolean {
    return state.playback.state == "paused"
}
