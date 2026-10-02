package moe.ouom.neriplayer.data.sync.mapping

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import android.content.Context
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.sync.identity.identity
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.model.sync.toLegacyLyricRecoveryCandidateOrNull

fun SyncFavoritePlaylist.Companion.fromFavoritePlaylist(playlist: FavoritePlaylist, context: Context? = null, optimizeLegacyLyrics: Boolean = false): SyncFavoritePlaylist {
    val mapper = context?.let { CoverUrlMapper.getInstance(it) }
    if (playlist.isDeleted) return deletedFavoritePlaylist(playlist, mapper)
    val syncedSongs = playlist.songs.mapNotNull { SyncSong.fromSongItemOrNull(it, context, optimizeLegacyLyrics) }
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

fun SyncFavoritePlaylist.toFavoritePlaylist(
    existing: FavoritePlaylist? = null,
    retainLegacyCandidate: (SyncSong) -> Unit = {}
): FavoritePlaylist {
    val existingSongs = existing?.songs.orEmpty().associateBy {
        it.toLegacyLyricRecoveryCandidateOrNull()?.let(retainLegacyCandidate)
        it.identity()
    }
    return FavoritePlaylist(
        id = id,
        name = name,
        coverUrl = sanitizeCoverUrlForSync(coverUrl),
        trackCount = trackCount,
        source = source,
        browseId = browseId,
        playlistId = playlistId,
        subtitle = subtitle,
        songs = songs.map { song -> song.toSongItem(existingSongs[song.identity()]) },
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
