package moe.ouom.neriplayer.listentogether.session.state

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings

internal fun ListenTogetherRoomSettings?.normalized(): ListenTogetherRoomSettings {
    return this ?: ListenTogetherRoomSettings()
}
