package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary

internal fun filterNeteasePlaylists(
    playlists: List<PlaylistSummary>,
    query: String
): List<PlaylistSummary> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        return playlists
    }

    return playlists.filter { playlist ->
        playlist.matchesNeteasePlaylistQuery(normalizedQuery)
    }
}

internal fun filterNeteaseAlbums(
    albums: List<AlbumSummary>,
    query: String
): List<AlbumSummary> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        return albums
    }

    return albums.filter { album ->
        album.matchesNeteaseAlbumQuery(normalizedQuery)
    }
}

internal fun PlaylistSummary.matchesNeteasePlaylistQuery(query: String): Boolean {
    return name.contains(query, ignoreCase = true) ||
        id.toString().contains(query) ||
        playCount.toString().contains(query) ||
        trackCount.toString().contains(query) ||
        picUrl.contains(query, ignoreCase = true)
}

internal fun AlbumSummary.matchesNeteaseAlbumQuery(query: String): Boolean {
    return name.contains(query, ignoreCase = true) ||
        id.toString().contains(query) ||
        size.toString().contains(query) ||
        picUrl.contains(query, ignoreCase = true)
}
