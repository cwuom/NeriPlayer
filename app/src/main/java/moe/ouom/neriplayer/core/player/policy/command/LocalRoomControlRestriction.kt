package moe.ouom.neriplayer.core.player.policy.command

import moe.ouom.neriplayer.R

internal enum class LocalRoomControlRestriction(val errorResId: Int?, val debugReason: String) {
    NONE(null, ""),
    CONTROLLER_OFFLINE(R.string.listen_together_error_controller_offline, "local_control_blocked:controller_offline"),
    MEMBER_CONTROL_DISABLED(R.string.listen_together_error_member_control_disabled, "local_control_blocked:member_control_disabled")
}

internal fun resolveLocalRoomControlRestriction(
    roomStatus: String?,
    allowMemberControl: Boolean?,
    isController: Boolean
): LocalRoomControlRestriction {
    if (isController) return LocalRoomControlRestriction.NONE
    if (roomStatus == "controller_offline") return LocalRoomControlRestriction.CONTROLLER_OFFLINE
    if (allowMemberControl == false) return LocalRoomControlRestriction.MEMBER_CONTROL_DISABLED
    return LocalRoomControlRestriction.NONE
}
