package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState

internal fun shouldDropListenTogetherControllerLocalEcho(
    state: ListenTogetherRoomState,
    cause: ListenTogetherCause?,
    latestVersion: Long,
    currentUserId: String?,
    lastControllerLocalControlAtElapsedMs: Long,
    nowElapsedMs: Long,
    controllerLocalControlCooldownMs: Long
): Boolean {
    if (cause?.type == "TRACK_FINISHED") return false
    return currentUserId == (state.controllerUserUuid ?: state.controllerUserId) &&
        cause?.userUuid == currentUserId &&
        nowElapsedMs - lastControllerLocalControlAtElapsedMs < controllerLocalControlCooldownMs &&
        state.version <= latestVersion + 1
}
