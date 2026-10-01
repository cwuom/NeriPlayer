package moe.ouom.neriplayer.data.model.playlist

import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem

data class LocalPlaylistSongAddResult(
    val addedSongs: List<SongItem>
) {
    val addedCount: Int
        get() = addedSongs.size
}

data class LocalPlaylistSongDeleteResult(
    val playlistId: Long,
    val song: SongItem,
    val index: Int
)

data class LocalPlaylistDeleteResult(
    val playlist: LocalPlaylist,
    val index: Int
)
