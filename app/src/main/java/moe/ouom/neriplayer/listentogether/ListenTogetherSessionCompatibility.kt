package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherJoinAutoPauseCause as resolveSessionJoinAutoPauseCause

internal fun resolveListenTogetherJoinAutoPauseCause(
    autoPauseOnJoin: Boolean,
    role: String?,
    state: ListenTogetherRoomState
): String? {
    return resolveSessionJoinAutoPauseCause(
        autoPauseOnJoin = autoPauseOnJoin,
        role = role,
        state = state
    )
}
