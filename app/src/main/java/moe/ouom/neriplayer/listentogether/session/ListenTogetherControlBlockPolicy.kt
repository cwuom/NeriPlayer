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
        roomState?.settings.normalized().allowMemberControl == false &&
        commandType in controlledPlaybackCommandTypes
    ) {
        return context.getString(R.string.listen_together_error_member_control_disabled)
    }
    return null
}

/**
 * 控制端(权威方)对转发来的成员控制请求做服务端侧鉴权
 * 关闭 allowMemberControl 后, 只有控制端自身发起的转发请求可通过
 * 防止改造的监听端或旧自建 Worker 绕过本机 UI 校验越权控制房间
 */
internal fun shouldRejectForwardedListenTogetherMemberControl(
    requesterUuid: String?,
    controllerUserUuid: String?,
    allowMemberControl: Boolean
): Boolean {
    if (allowMemberControl) return false
    val isRequesterController = !requesterUuid.isNullOrBlank() &&
        requesterUuid == controllerUserUuid
    return !isRequesterController
}
