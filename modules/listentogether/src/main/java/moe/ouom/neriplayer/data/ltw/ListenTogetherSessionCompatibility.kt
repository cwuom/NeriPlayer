package moe.ouom.neriplayer.data.ltw

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.ltw.session.membership.resolveListenTogetherJoinAutoPauseCause as resolveSessionJoinAutoPauseCause

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
