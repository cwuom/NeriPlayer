package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem

internal fun playlistDownloadCandidates(songs: List<SongItem>): List<SongItem> =
    songs.filterNot(SongItem::isLocalSong)
