package moe.ouom.neriplayer.data.ltw.session.control

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

fun shouldDropListenTogetherControllerLocalEcho(
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
