package moe.ouom.neriplayer.core.integration.download

import moe.ouom.neriplayer.core.download.host.DownloadedPlaybackHost
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem

internal object AndroidDownloadedPlaybackHost : DownloadedPlaybackHost {
    override val currentSong get() = PlayerManager.currentSongFlow.value

    override fun playPlaylist(songs: List<SongItem>, startIndex: Int) {
        PlayerManager.playPlaylist(songs, startIndex)
    }

    override fun hydrateSongMetadata(originalSong: SongItem, updatedSong: SongItem) {
        PlayerManager.hydrateSongMetadata(originalSong, updatedSong)
    }
}
