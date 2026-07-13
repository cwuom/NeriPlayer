package moe.ouom.neriplayer.core.startup.app

import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicPlaylistDetail as ApiPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicLibraryGateway
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicUiDependencies

internal object YouTubeMusicUiGatewayInitializer {
    fun initialize() {
        YouTubeMusicUiDependencies.libraryGateway = object : YouTubeMusicLibraryGateway {
            override suspend fun getLibraryPlaylists(): List<YouTubeMusicPlaylist> {
                return AppContainer.youtubeMusicClient.getLibraryPlaylists(
                    resolveMissingTrackCounts = false
                ).map { playlist ->
                    YouTubeMusicPlaylist(
                        browseId = playlist.browseId,
                        playlistId = playlist.playlistId,
                        title = playlist.title,
                        subtitle = playlist.subtitle,
                        coverUrl = playlist.coverUrl,
                        trackCount = playlist.trackCount ?: 0
                    )
                }
            }

            override suspend fun getPlaylistDetail(browseId: String): YouTubeMusicPlaylistDetail {
                val detail = AppContainer.youtubeMusicClient.getPlaylistDetail(browseId)
                return detail.toUiDetail()
            }

            override suspend fun getPlaylistDetailPreview(browseId: String): YouTubeMusicPlaylistDetail {
                val detail = AppContainer.youtubeMusicClient.getPlaylistDetailPreview(browseId)
                return detail.toUiDetail()
            }
        }
    }

    private fun ApiPlaylistDetail.toUiDetail(): YouTubeMusicPlaylistDetail {
        return YouTubeMusicPlaylistDetail(
            playlistId = playlistId,
            title = title,
            subtitle = subtitle,
            coverUrl = coverUrl,
            trackCount = trackCount ?: tracks.size,
            fullyLoaded = fullyLoaded,
            tracks = tracks.map { track ->
                YouTubeMusicTrack(
                    videoId = track.videoId,
                    name = track.title,
                    artist = track.artist,
                    albumName = track.album,
                    durationMs = track.durationMs,
                    coverUrl = track.coverUrl
                )
            }
        )
    }
}
