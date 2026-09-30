package moe.ouom.neriplayer.core.player.presentation.command

import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.policy.command.LocalRoomControlRestriction

internal val LocalRoomControlRestriction.errorResId: Int?
    get() = when (this) {
        LocalRoomControlRestriction.NONE -> null
        LocalRoomControlRestriction.CONTROLLER_OFFLINE -> CoreCommonR.string.listen_together_error_controller_offline
        LocalRoomControlRestriction.MEMBER_CONTROL_DISABLED -> CoreCommonR.string.listen_together_error_member_control_disabled
    }
