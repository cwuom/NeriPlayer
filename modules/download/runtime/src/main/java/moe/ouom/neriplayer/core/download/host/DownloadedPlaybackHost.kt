package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.data.model.SongItem

interface DownloadedPlaybackHost {
    val currentSong: SongItem?
    fun playPlaylist(songs: List<SongItem>, startIndex: Int)
    fun hydrateSongMetadata(originalSong: SongItem, updatedSong: SongItem)
}
