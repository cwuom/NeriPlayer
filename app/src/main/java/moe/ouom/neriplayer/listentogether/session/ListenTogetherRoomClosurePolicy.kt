package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal fun shouldApplyListenTogetherClosedRoomPause(
    state: ListenTogetherRoomState
): Boolean {
    return state.playback.state == "paused"
}
