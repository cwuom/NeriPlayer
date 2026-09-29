package moe.ouom.neriplayer.listentogether.session.connection

import moe.ouom.neriplayer.listentogether.session.state.normalized
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState

internal fun hasListenTogetherReconnectTarget(session: ListenTogetherSessionState, enabled: Boolean): Boolean =
    enabled && !session.wsUrl.isNullOrBlank() && !session.roomId.isNullOrBlank()

internal fun shouldScheduleListenTogetherReconnect(session: ListenTogetherSessionState, enabled: Boolean): Boolean =
    hasListenTogetherReconnectTarget(session, enabled) &&
        session.connectionState != ListenTogetherConnectionState.CONNECTING

internal fun needsListenTogetherListenerMembershipRecovery(
    session: ListenTogetherSessionState,
    state: ListenTogetherRoomState,
    isController: Boolean
): Boolean {
    val userUuid = session.userUuid ?: return false
    if (isController || state.roomStatus == ListenTogetherRoomStatuses.CLOSED) return false
    return state.members.none { it.userUuid.ifBlank { it.userId.orEmpty() } == userUuid }
}

internal fun isListenTogetherMissingMemberError(errorMessage: String?): Boolean {
    val normalized = errorMessage?.trim()?.lowercase().orEmpty()
    return "member not in room" in normalized || "member missing" in normalized
}

internal fun hasListenTogetherListenerRejoinIdentity(
    session: ListenTogetherSessionState,
    isController: Boolean
): Boolean = !isController &&
    listOf(session.baseUrl, session.roomId, session.userUuid, session.nickname).all { !it.isNullOrBlank() }

internal data class ListenTogetherRejoinIdentity(
    val baseUrl: String,
    val roomId: String,
    val userUuid: String,
    val nickname: String
)

internal fun listenTogetherRejoinIdentity(
    session: ListenTogetherSessionState,
    isController: Boolean
): ListenTogetherRejoinIdentity? {
    if (!hasListenTogetherListenerRejoinIdentity(session, isController)) return null
    val fields = listOfNotNull(session.baseUrl, session.roomId, session.userUuid, session.nickname)
    return ListenTogetherRejoinIdentity(
        baseUrl = fields[0],
        roomId = fields[1],
        userUuid = fields[2],
        nickname = fields[3]
    )
}

internal fun sameListenTogetherMembership(
    expected: ListenTogetherSessionState,
    current: ListenTogetherSessionState
): Boolean = listOf(expected.baseUrl, expected.roomId, expected.userUuid) ==
    listOf(current.baseUrl, current.roomId, current.userUuid)

internal fun sameListenTogetherReconnectTarget(
    expected: ListenTogetherSessionState,
    current: ListenTogetherSessionState
): Boolean = sameListenTogetherMembership(expected, current) && expected.wsUrl == current.wsUrl

internal fun canRunListenTogetherReconnect(
    expected: ListenTogetherSessionState,
    current: ListenTogetherSessionState,
    enabled: Boolean,
    sameGeneration: Boolean
): Boolean = sameGeneration && shouldScheduleListenTogetherReconnect(current, enabled) &&
    sameListenTogetherReconnectTarget(expected, current)

internal fun canStartListenTogetherMembershipRecovery(
    expected: ListenTogetherSessionState,
    current: ListenTogetherSessionState,
    stopped: Boolean,
    sameGeneration: Boolean
): Boolean = !stopped && sameGeneration && sameListenTogetherMembership(expected, current)
