package moe.ouom.neriplayer.data.ltw.session.control

import moe.ouom.neriplayer.data.ltw.session.state.normalized
import android.content.Context
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.ltw.control.controlledPlaybackCommandTypes
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses

internal fun resolveListenTogetherControlBlockReason(
    context: Context,
    sessionRole: String?,
    roomState: ListenTogetherRoomState?,
    commandType: String
): String? {
    if (
        roomState?.roomStatus == ListenTogetherRoomStatuses.CONTROLLER_OFFLINE &&
        sessionRole != "controller"
    ) {
        return if (roomState.settings.normalized().shareAudioLinks) {
            context.getString(CoreCommonR.string.listen_together_error_controller_offline_link)
        } else {
            context.getString(CoreCommonR.string.listen_together_error_controller_offline)
        }
    }
    if (isMemberControlBlocked(sessionRole, roomState, commandType)) {
        return context.getString(CoreCommonR.string.listen_together_error_member_control_disabled)
    }
    return null
}

private fun isMemberControlBlocked(role: String?, room: ListenTogetherRoomState?, commandType: String): Boolean =
    role == "listener" && room?.settings.normalized().allowMemberControl == false && commandType in controlledPlaybackCommandTypes
