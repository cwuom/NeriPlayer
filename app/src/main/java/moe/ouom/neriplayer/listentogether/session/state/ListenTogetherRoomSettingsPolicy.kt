package moe.ouom.neriplayer.listentogether.session.state

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomSettings

internal fun ListenTogetherRoomSettings?.normalized(): ListenTogetherRoomSettings {
    return this ?: ListenTogetherRoomSettings()
}
