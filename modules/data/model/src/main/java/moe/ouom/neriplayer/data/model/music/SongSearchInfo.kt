package moe.ouom.neriplayer.data.model.music

import kotlinx.serialization.Serializable

@Serializable
data class SongSearchInfo(
    val id: String,
    val songName: String,
    val singer: String,
    val duration: String,
    val source: MusicPlatform,
    val albumName: String?,
    val coverUrl: String?
)
