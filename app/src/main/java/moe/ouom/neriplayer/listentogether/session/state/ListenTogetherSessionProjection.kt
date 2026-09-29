package moe.ouom.neriplayer.listentogether.session.state

import moe.ouom.neriplayer.listentogether.session.membership.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.api.ltw.ws.buildListenTogetherWsUrl
import moe.ouom.neriplayer.listentogether.protocol.message.http.ListenTogetherRoomResponse
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherSessionState

internal data class PreparedListenTogetherSessionUpdate(
    val normalizedBaseUrl: String,
    val resolvedWsUrl: String?,
    val sessionChanged: Boolean,
    private val response: ListenTogetherRoomResponse,
    private val userUuid: String?,
    private val role: String?,
    private val memberSecret: String?,
    private val joinSecret: String?
) {
    fun applyTo(current: ListenTogetherSessionState): ListenTogetherSessionState = current.copy(
        baseUrl = normalizedBaseUrl,
        roomId = response.roomId,
        userUuid = userUuid,
        nickname = response.nickname,
        role = role,
        token = response.token,
        memberSecret = memberSecret,
        joinSecret = joinSecret,
        wsUrl = resolvedWsUrl,
        lastError = response.error,
        roomNotice = null
    )
}

internal fun prepareListenTogetherSessionUpdate(
    baseUrl: String,
    response: ListenTogetherRoomResponse,
    previous: ListenTogetherSessionState
): PreparedListenTogetherSessionUpdate {
    val normalizedBaseUrl = resolveListenTogetherBaseUrl(baseUrl)
    val userUuid = response.userUuid ?: response.userId
    val sameRoom = previous.roomId.equals(response.roomId, ignoreCase = true)
    val sameUser = previous.userUuid.equals(userUuid, ignoreCase = true)
    val changed = sessionIdentityChanged(previous, normalizedBaseUrl, response.roomId, userUuid)
    return PreparedListenTogetherSessionUpdate(
        normalizedBaseUrl = normalizedBaseUrl,
        resolvedWsUrl = resolveSessionWebSocketUrl(normalizedBaseUrl, response),
        sessionChanged = changed,
        response = response,
        userUuid = userUuid,
        role = resolveListenTogetherSessionRole(
            sessionUserId = userUuid,
            fallbackRole = response.role,
            state = response.state
        ),
        memberSecret = response.memberSecret ?: previous.memberSecret.takeIf { sameRoom && sameUser },
        joinSecret = response.joinSecret ?: previous.joinSecret.takeIf { sameRoom }
    )
}

private fun sessionIdentityChanged(
    previous: ListenTogetherSessionState,
    baseUrl: String,
    roomId: String?,
    userUuid: String?
): Boolean = baseUrlChanged(previous.baseUrl, baseUrl) ||
    roomChanged(previous.roomId, roomId) ||
    knownUserChanged(previous.userUuid, userUuid)

private fun baseUrlChanged(previous: String?, next: String): Boolean =
    !previous.equals(next, ignoreCase = true)

private fun roomChanged(previous: String?, next: String?): Boolean =
    !previous.equals(next, ignoreCase = true)

private fun knownUserChanged(previous: String?, next: String?): Boolean {
    if (previous.isNullOrBlank() || next.isNullOrBlank()) return false
    return !previous.equals(next, ignoreCase = true)
}

private fun resolveSessionWebSocketUrl(baseUrl: String, response: ListenTogetherRoomResponse): String? {
    val supplied = response.wsUrl
    if (supplied != null && !isInternalRoomWebSocketUrl(supplied)) return supplied
    return fallbackSessionWebSocketUrl(baseUrl, response.roomId, response.token)
}

private fun fallbackSessionWebSocketUrl(baseUrl: String, roomId: String?, token: String?): String? {
    if (roomId.isNullOrBlank() || token.isNullOrBlank()) return null
    return buildListenTogetherWsUrl(baseUrl, roomId, token)
}

private fun isInternalRoomWebSocketUrl(url: String): Boolean =
    url.contains("://room.internal/", ignoreCase = true) ||
        url.contains("://room.internal?", ignoreCase = true) ||
        url.contains("://room.internal:", ignoreCase = true)
