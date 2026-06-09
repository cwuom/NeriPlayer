package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind

internal fun filterBiliPlaylists(
    playlists: List<BiliPlaylist>,
    query: String,
    createdLabel: String,
    collectedLabel: String,
    collectionLabel: String
): List<BiliPlaylist> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        return playlists
    }

    return playlists.filter { playlist ->
        playlist.matchesBiliPlaylistQuery(
            query = normalizedQuery,
            createdLabel = createdLabel,
            collectedLabel = collectedLabel,
            collectionLabel = collectionLabel
        )
    }
}

internal fun BiliPlaylist.matchesBiliPlaylistQuery(
    query: String,
    createdLabel: String,
    collectedLabel: String,
    collectionLabel: String
): Boolean {
    val kindLabel = when (kind) {
        BiliPlaylistKind.CREATED_FAVORITE -> createdLabel
        BiliPlaylistKind.COLLECTED_FAVORITE -> collectedLabel
        BiliPlaylistKind.COLLECTION -> collectionLabel
    }

    return title.contains(query, ignoreCase = true) ||
        subtitle.contains(query, ignoreCase = true) ||
        kindLabel.contains(query, ignoreCase = true) ||
        coverUrl.contains(query, ignoreCase = true) ||
        mediaId.toString().contains(query) ||
        fid.toString().contains(query) ||
        mid.toString().contains(query)
}
