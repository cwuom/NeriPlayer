package moe.ouom.neriplayer.data.ltw.session.state

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings

fun ListenTogetherRoomSettings?.normalized(): ListenTogetherRoomSettings {
    return this ?: ListenTogetherRoomSettings()
}
