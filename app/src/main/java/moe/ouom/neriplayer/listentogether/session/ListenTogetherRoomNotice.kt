package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import java.util.concurrent.TimeUnit

private const val DEFAULT_CONTROLLER_GRACE_PERIOD_MS = 10 * 60 * 1000L

internal fun resolveListenTogetherRoomNotice(
    state: ListenTogetherRoomState?,
    fallbackMessage: String? = null,
    nowMs: Long = System.currentTimeMillis(),
    controllerGracePeriodMs: Long = DEFAULT_CONTROLLER_GRACE_PERIOD_MS
): String? {
    state ?: return fallbackMessage
    return when (state.roomStatus) {
        ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> {
            val offlineSince = state.controllerOfflineSince ?: return fallbackMessage ?: "controller_offline"
            val timeoutAt = offlineSince + controllerGracePeriodMs
            val remainingMs = (timeoutAt - nowMs).coerceAtLeast(0L)
            val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs).coerceAtLeast(0L)
            "controller_offline:${remainingMinutes + 1}"
        }

        ListenTogetherRoomStatuses.CLOSED -> fallbackMessage ?: state.closedReason ?: "room_closed"
        else -> fallbackMessage
    }
}
