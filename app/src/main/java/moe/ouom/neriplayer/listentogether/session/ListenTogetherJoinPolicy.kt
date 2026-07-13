package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal fun resolveListenTogetherJoinAutoPauseCause(
    autoPauseOnJoin: Boolean,
    role: String?,
    state: ListenTogetherRoomState
): String? {
    if (!autoPauseOnJoin || role != "listener") return null
    return "JOIN_AUTO_PAUSE".takeIf { state.playback.state == "paused" }
}
