package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState

internal data class ListenTogetherMembershipCredential(
    val baseUrl: String,
    val roomId: String,
    val userUuid: String,
    val token: String?,
    val memberSecret: String?,
    val joinSecret: String?
)

internal fun ListenTogetherSessionState.toMembershipCredentialOrNull():
    ListenTogetherMembershipCredential? {
    val normalizedBaseUrl = baseUrl?.trim().orEmpty()
    val normalizedRoomId = roomId?.trim().orEmpty()
    val normalizedUserUuid = userUuid?.trim().orEmpty()
    val normalizedToken = token?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedMemberSecret = memberSecret?.trim()?.takeIf { it.isNotEmpty() }
    if (
        normalizedBaseUrl.isEmpty() ||
        normalizedRoomId.isEmpty() ||
        normalizedUserUuid.isEmpty() ||
        (normalizedToken == null && normalizedMemberSecret == null)
    ) {
        return null
    }
    return ListenTogetherMembershipCredential(
        baseUrl = normalizedBaseUrl,
        roomId = normalizedRoomId,
        userUuid = normalizedUserUuid,
        token = normalizedToken,
        memberSecret = normalizedMemberSecret,
        joinSecret = joinSecret?.trim()?.takeIf { it.isNotEmpty() }
    )
}

internal fun resolveReusableListenTogetherMembershipCredential(
    activeSession: ListenTogetherSessionState,
    retainedCredential: ListenTogetherMembershipCredential?,
    baseUrl: String,
    roomId: String,
    userUuid: String
): ListenTogetherMembershipCredential? {
    return sequenceOf(activeSession.toMembershipCredentialOrNull(), retainedCredential)
        .filterNotNull()
        .firstOrNull { credential ->
            credential.baseUrl.equals(baseUrl.trim(), ignoreCase = true) &&
                credential.roomId.equals(roomId.trim(), ignoreCase = true) &&
                credential.userUuid.equals(userUuid.trim(), ignoreCase = true)
        }
}
