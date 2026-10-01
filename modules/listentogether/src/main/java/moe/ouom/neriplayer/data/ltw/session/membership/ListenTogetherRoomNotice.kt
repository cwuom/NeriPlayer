package moe.ouom.neriplayer.data.ltw.session.membership

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.util.units.MINUTE_MS
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.data.ltw.session.normalizedListenTogetherIdentity

private const val DEFAULT_CONTROLLER_GRACE_PERIOD_MS = 10 * MINUTE_MS

fun normalizeListenTogetherRoomClosureReason(reason: String?): String? {
    val normalizedReason = reason.normalizedListenTogetherIdentity() ?: return null
    return when {
        normalizedReason.equals("controller_left", ignoreCase = true) -> "controller_left"
        normalizedReason.equals("controller_timeout", ignoreCase = true) -> "controller_timeout"
        normalizedReason.equals("room_closed", ignoreCase = true) -> "room_closed"
        else -> normalizedReason
    }
}

fun isNormalListenTogetherRoomClosureReason(reason: String?): Boolean {
    return normalizeListenTogetherRoomClosureReason(reason) == "controller_left"
}

fun resolveListenTogetherRoomNotice(
    state: ListenTogetherRoomState?,
    fallbackMessage: String? = null,
    nowMs: Long = System.currentTimeMillis(),
    controllerGracePeriodMs: Long = DEFAULT_CONTROLLER_GRACE_PERIOD_MS,
    showControllerReconnected: Boolean = false
): String? {
    state ?: return fallbackMessage
    return when (state.roomStatus) {
        ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> offlineRoomNotice(state, fallbackMessage, nowMs, controllerGracePeriodMs)
        ListenTogetherRoomStatuses.CLOSED -> closedRoomNotice(state, fallbackMessage)
        else -> fallbackMessage?.takeUnless {
            it.equals("controller_reconnected", ignoreCase = true) && !showControllerReconnected
        }
    }
}

private fun offlineRoomNotice(
    state: ListenTogetherRoomState,
    fallbackMessage: String?,
    nowMs: Long,
    gracePeriodMs: Long
): String {
    val offlineSince = state.controllerOfflineSince ?: return fallbackMessage ?: "controller_offline"
    val remainingMs = (offlineSince + gracePeriodMs - nowMs).coerceAtLeast(0L)
    val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs).coerceAtLeast(0L)
    return "controller_offline:${remainingMinutes + 1}"
}

private fun closedRoomNotice(state: ListenTogetherRoomState, fallbackMessage: String?): String =
    state.closedReason.normalizedListenTogetherIdentity() ?: fallbackMessage ?: "room_closed"

fun shouldShowListenTogetherControllerReconnectedNotice(
    isCurrentUserController: Boolean,
    observedControllerOffline: Boolean
): Boolean {
    return !isCurrentUserController && observedControllerOffline
}
