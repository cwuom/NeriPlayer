package moe.ouom.neriplayer.core.player.service.car.library

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist

data class CarLibrarySnapshot(
    val queue: List<SongItem> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val history: List<SongItem> = emptyList(),
    val offlineSongs: List<SongItem>? = null
)

data class CarLibraryItem(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val song: SongItem? = null
) {
    val isBrowsable: Boolean get() = song == null
    val isPlayable: Boolean get() = song != null
}

data class CarPlaybackSelection(
    val songs: List<SongItem>,
    val startIndex: Int,
    val localPlaylistId: Long? = null
)

data class CarLibraryLabels(
    val root: String = "NeriPlayer",
    val queue: String = "当前队列",
    val playlists: String = "本地歌单",
    val history: String = "最近播放",
    val offline: String = "离线歌曲"
)
