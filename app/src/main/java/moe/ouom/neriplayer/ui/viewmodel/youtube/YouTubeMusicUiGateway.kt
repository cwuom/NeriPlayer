package moe.ouom.neriplayer.ui.viewmodel.youtube

import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist

data class YouTubeMusicPlaylistDetail(
    val playlistId: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String,
    val trackCount: Int,
    val tracks: List<YouTubeMusicTrack>
)

data class YouTubeMusicTrack(
    val videoId: String,
    val name: String,
    val artist: String,
    val albumName: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
)

interface YouTubeMusicLibraryGateway {
    suspend fun getLibraryPlaylists(): List<YouTubeMusicPlaylist>

    suspend fun getPlaylistDetail(browseId: String): YouTubeMusicPlaylistDetail
}

/**
 * UI 层只依赖一个最小网关
 * 底层实现由主线程在 data/core 层接入后注入，避免当前分支越权修改底层代码
 */
object YouTubeMusicUiDependencies {
    @Volatile
    var libraryGateway: YouTubeMusicLibraryGateway? = null
}
