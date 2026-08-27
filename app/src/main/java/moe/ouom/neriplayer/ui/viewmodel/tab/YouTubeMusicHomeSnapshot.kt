package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicParser

internal data class YouTubeMusicHomeSnapshot(
    val playlists: List<YouTubeMusicPlaylist>,
    val shelves: List<YouTubeMusicHomeShelf>
)

internal suspend fun loadYouTubeMusicHomeSnapshot(
    playlistLimit: Int,
    loadShelves: suspend () -> List<YouTubeMusicHomeShelf>
): YouTubeMusicHomeSnapshot {
    return buildYouTubeMusicHomeSnapshot(
        shelves = loadShelves(),
        playlistLimit = playlistLimit
    )
}

internal fun buildYouTubeMusicHomeSnapshot(
    shelves: List<YouTubeMusicHomeShelf>,
    playlistLimit: Int
): YouTubeMusicHomeSnapshot {
    val playlists = YouTubeMusicParser.parseHomePlaylistRecommendations(
        shelves = shelves,
        limit = playlistLimit
    ).map { playlist ->
        YouTubeMusicPlaylist(
            browseId = playlist.browseId,
            playlistId = playlist.playlistId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            coverUrl = playlist.coverUrl,
            trackCount = playlist.trackCount ?: 0
        )
    }
    return YouTubeMusicHomeSnapshot(playlists = playlists, shelves = shelves)
}
