package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal fun shouldAutoPauseListenTogetherForMemberChange(
    autoPauseOnMemberChange: Boolean,
    memberChangeType: String?
): Boolean {
    if (!autoPauseOnMemberChange) return false
    return memberChangeType == "MEMBER_JOINED" || memberChangeType == "MEMBER_LEFT"
}

internal fun resolveListenTogetherJoinAutoPauseCause(
    autoPauseOnJoin: Boolean,
    role: String?,
    state: ListenTogetherRoomState
): String? {
    if (
        !shouldAutoPauseListenTogetherForMemberChange(
            autoPauseOnMemberChange = autoPauseOnJoin,
            memberChangeType = "MEMBER_JOINED"
        ) || role != "listener"
    ) {
        return null
    }
    return "JOIN_AUTO_PAUSE".takeIf { state.playback.state == "paused" }
}
