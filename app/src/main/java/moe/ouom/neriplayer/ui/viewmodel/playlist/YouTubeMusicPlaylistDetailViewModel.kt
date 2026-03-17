package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicUiDependencies

data class YouTubeMusicPlaylistDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val playlist: YouTubeMusicPlaylist? = null,
    val tracks: List<SongItem> = emptyList()
)

class YouTubeMusicPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(YouTubeMusicPlaylistDetailUiState())
    val uiState: StateFlow<YouTubeMusicPlaylistDetailUiState> = _uiState

    private var currentPlaylist: YouTubeMusicPlaylist? = null

    fun start(playlist: YouTubeMusicPlaylist) {
        currentPlaylist = playlist
        _uiState.value = YouTubeMusicPlaylistDetailUiState(
            loading = true,
            playlist = playlist
        )
        loadPlaylist()
    }

    fun retry() {
        currentPlaylist?.let(::start)
    }

    private fun loadPlaylist() {
        val playlist = currentPlaylist ?: return
        val gateway = YouTubeMusicUiDependencies.libraryGateway
        if (gateway == null) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                error = "YouTube Music gateway unavailable"
            )
            return
        }
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    gateway.getPlaylistDetail(playlist.browseId)
                }
                val resolvedPlaylist = playlist.copy(
                    playlistId = detail.playlistId.ifBlank { playlist.playlistId },
                    title = detail.title.ifBlank { playlist.title },
                    subtitle = detail.subtitle.ifBlank { playlist.subtitle },
                    coverUrl = detail.coverUrl.ifBlank { playlist.coverUrl },
                    trackCount = detail.trackCount.takeIf { it > 0 }
                        ?: detail.tracks.size.takeIf { it > 0 }
                        ?: playlist.trackCount
                )
                _uiState.value = YouTubeMusicPlaylistDetailUiState(
                    loading = false,
                    playlist = resolvedPlaylist,
                    tracks = detail.tracks.map { it.toSongItem(resolvedPlaylist) }
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    private fun YouTubeMusicTrack.toSongItem(playlist: YouTubeMusicPlaylist): SongItem {
        val resolvedAlbum = albumName.ifBlank { playlist.title }
        return SongItem(
            id = stableYouTubeMusicId(videoId),
            name = name,
            artist = artist,
            album = resolvedAlbum,
            albumId = stableYouTubeMusicId(playlist.playlistId.ifBlank { videoId }),
            durationMs = durationMs,
            coverUrl = coverUrl.ifBlank { playlist.coverUrl },
            mediaUri = buildYouTubeMusicMediaUri(
                videoId = videoId,
                playlistId = playlist.playlistId.ifBlank { null }
            ),
            originalName = name,
            originalArtist = artist,
            originalCoverUrl = coverUrl.ifBlank { playlist.coverUrl }
        )
    }
}
