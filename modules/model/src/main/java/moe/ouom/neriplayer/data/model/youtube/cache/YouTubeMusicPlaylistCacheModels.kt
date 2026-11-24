package moe.ouom.neriplayer.data.model.youtube.cache

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeContentLabel

data class CachedYouTubeMusicPlaylistTrack(
    val videoId: String,
    val name: String,
    val artist: String,
    val albumName: String,
    val durationMs: Long,
    val coverUrl: String,
    val contentLabel: YouTubeContentLabel? = null
)

data class CachedYouTubeMusicPlaylistDetail(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val creatorName: String? = null,
    val coverUrl: String,
    val trackCount: Int,
    val firstPageSignature: String,
    val tracks: List<CachedYouTubeMusicPlaylistTrack>,
    val savedAtMs: Long = System.currentTimeMillis(),
    val catalogKey: String? = null,
    val filteredEmpty: Boolean = false
) {
    val containsUnfilteredTracks: Boolean
        get() = !filteredEmpty && catalogKey?.endsWith("/false/false") != false
}
