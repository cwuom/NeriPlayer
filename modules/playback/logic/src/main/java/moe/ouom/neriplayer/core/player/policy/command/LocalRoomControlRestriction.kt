package moe.ouom.neriplayer.core.player.policy.command

enum class LocalRoomControlRestriction(val debugReason: String) {
    NONE(""),
    CONTROLLER_OFFLINE("local_control_blocked:controller_offline"),
    MEMBER_CONTROL_DISABLED("local_control_blocked:member_control_disabled")
}

fun resolveLocalRoomControlRestriction(
    roomStatus: String?,
    allowMemberControl: Boolean?,
    isController: Boolean
): LocalRoomControlRestriction {
    if (isController) return LocalRoomControlRestriction.NONE
    if (roomStatus == "controller_offline") return LocalRoomControlRestriction.CONTROLLER_OFFLINE
    if (allowMemberControl == false) return LocalRoomControlRestriction.MEMBER_CONTROL_DISABLED
    return LocalRoomControlRestriction.NONE
}
