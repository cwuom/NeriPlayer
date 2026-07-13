package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal fun resolveListenTogetherSessionRole(
    sessionUserId: String?,
    fallbackRole: String?,
    state: ListenTogetherRoomState?
): String? {
    val normalizedUserId = sessionUserId?.trim()?.takeIf { it.isNotBlank() }
    val controllerUserId = state?.controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
        ?: state?.controllerUserId?.trim()?.takeIf { it.isNotBlank() }
    return when {
        normalizedUserId != null && controllerUserId != null -> {
            if (normalizedUserId == controllerUserId) "controller" else "listener"
        }

        else -> fallbackRole
    }
}
