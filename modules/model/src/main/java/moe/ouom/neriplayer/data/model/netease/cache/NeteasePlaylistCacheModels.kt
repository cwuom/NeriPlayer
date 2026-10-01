package moe.ouom.neriplayer.data.model.netease.cache

data class CachedNeteasePlaylistHeader(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val playCount: Long,
    val trackCount: Int
)

data class CachedNeteaseArtist(
    val id: Long,
    val name: String
)

data class CachedNeteasePlaylistTrack(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val coverUrl: String?,
    val audioId: String?,
    val artists: List<CachedNeteaseArtist> = emptyList(),
    val addedAt: Long = 0L
)

data class CachedNeteasePlaylistDetail(
    val playlistId: Long,
    val header: CachedNeteasePlaylistHeader,
    val recentTrackSignature: String,
    val tracks: List<CachedNeteasePlaylistTrack>,
    val radarCacheContext: String? = null,
    val savedAtMs: Long = System.currentTimeMillis()
)
