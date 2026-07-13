package moe.ouom.neriplayer.listentogether.session

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.listentogether.control.controlledPlaybackCommandTypes
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses

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
            context.getString(R.string.listen_together_error_controller_offline_link)
        } else {
            context.getString(R.string.listen_together_error_controller_offline)
        }
    }
    if (
        sessionRole == "listener" &&
        roomState?.settings.normalized()?.allowMemberControl == false &&
        commandType in controlledPlaybackCommandTypes
    ) {
        return context.getString(R.string.listen_together_error_member_control_disabled)
    }
    return null
}
