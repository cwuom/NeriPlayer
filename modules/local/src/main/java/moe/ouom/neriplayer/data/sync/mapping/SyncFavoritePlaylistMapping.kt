package moe.ouom.neriplayer.data.sync.mapping

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import android.content.Context
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.sync.CoverUrlMapper

fun SyncFavoritePlaylist.Companion.fromFavoritePlaylist(playlist: FavoritePlaylist, context: Context? = null): SyncFavoritePlaylist {
    val mapper = context?.let { CoverUrlMapper.getInstance(it) }
    if (playlist.isDeleted) return deletedFavoritePlaylist(playlist, mapper)
    val syncedSongs = playlist.songs.mapNotNull { SyncSong.fromSongItemOrNull(it, context) }
    val hasFilteredLocalSongs = syncedSongs.size != playlist.songs.size
    val syncedCoverUrl = sanitizeCoverUrlForSync(playlist.coverUrl, mapper)
        ?: syncedSongs.firstOrNull()?.coverUrl
    return SyncFavoritePlaylist(
        id = playlist.id,
        name = playlist.name,
        coverUrl = syncedCoverUrl,
        trackCount = if (hasFilteredLocalSongs) {
            syncedSongs.size
        } else {
            maxOf(playlist.trackCount, syncedSongs.size)
        },
        source = playlist.source,
        songs = syncedSongs,
        addedTime = playlist.addedTime,
        modifiedAt = playlist.modifiedAt,
        isDeleted = false,
        sortOrder = playlist.sortOrder,
        browseId = playlist.browseId,
        playlistId = playlist.playlistId,
        subtitle = playlist.subtitle
    )
}

fun SyncFavoritePlaylist.toFavoritePlaylist(): FavoritePlaylist {
    return FavoritePlaylist(
        id = id,
        name = name,
        coverUrl = sanitizeCoverUrlForSync(coverUrl),
        trackCount = trackCount,
        source = source,
        browseId = browseId,
        playlistId = playlistId,
        subtitle = subtitle,
        songs = songs.map { it.toSongItem() },
        addedTime = addedTime,
        sortOrder = sortOrder,
        modifiedAt = modifiedAt,
        isDeleted = isDeleted
    )
}

private fun deletedFavoritePlaylist(playlist: FavoritePlaylist, mapper: CoverUrlMapper?): SyncFavoritePlaylist {
    return SyncFavoritePlaylist(
        id = playlist.id,
        name = playlist.name,
        coverUrl = sanitizeCoverUrlForSync(playlist.coverUrl, mapper),
        trackCount = 0,
        source = playlist.source,
        songs = emptyList(),
        addedTime = playlist.addedTime,
        modifiedAt = playlist.modifiedAt,
        isDeleted = true,
        sortOrder = playlist.sortOrder,
        browseId = playlist.browseId,
        playlistId = playlist.playlistId,
        subtitle = playlist.subtitle
    )
}
