package moe.ouom.neriplayer.data.model.youtube.music

data class YouTubeMusicPlaylistPage(
    val tracks: List<YouTubeMusicPlaylistTrack>,
    val continuation: String? = null
)
