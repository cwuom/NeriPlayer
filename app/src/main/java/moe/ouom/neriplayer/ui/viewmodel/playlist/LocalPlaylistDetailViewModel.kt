package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.LocalAudioImportManager
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.SongIdentity

data class LocalPlaylistDetailUiState(
    val playlist: LocalPlaylist? = null,
    val isResolved: Boolean = false
)

data class LocalAudioImportUiResult(
    val importedCount: Int,
    val failedCount: Int
)

class LocalPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repo = LocalPlaylistRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LocalPlaylistDetailUiState())
    val uiState: StateFlow<LocalPlaylistDetailUiState> = _uiState

    private var playlistId: Long = 0L
    private var playlistCollectJob: Job? = null

    fun start(id: Long) {
        if (playlistId == id && _uiState.value.playlist != null) return
        playlistId = id
        playlistCollectJob?.cancel()
        playlistCollectJob = viewModelScope.launch {
            repo.playlists.collect { list ->
                _uiState.value = LocalPlaylistDetailUiState(
                    playlist = list.firstOrNull { it.id == id },
                    isResolved = true
                )
            }
        }
    }

    fun rename(newName: String) {
        viewModelScope.launch {
            var name = newName
            val favoritesName = app.getString(R.string.favorite_my_music)
            if (newName == favoritesName) {
                name = "${favoritesName}_2"
            }
            repo.renamePlaylist(playlistId, name)
        }
    }

    fun importSongs(uris: List<Uri>, onResult: (LocalAudioImportUiResult) -> Unit) {
        viewModelScope.launch {
            val result = LocalAudioImportManager.importSongs(app, uris)
            if (result.songs.isNotEmpty()) {
                repo.addSongsToPlaylist(playlistId, result.songs)
            }
            onResult(
                LocalAudioImportUiResult(
                    importedCount = result.songs.size,
                    failedCount = result.failedCount
                )
            )
        }
    }

    fun removeSongs(songs: List<SongItem>) {
        viewModelScope.launch {
            if (songs.isEmpty()) return@launch
            repo.removeSongsFromPlaylistByIdentity(playlistId, songs)
        }
    }

    fun delete(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repo.deletePlaylist(playlistId)
            onResult(ok)
        }
    }

    fun moveSong(from: Int, to: Int) {
        viewModelScope.launch { repo.moveSong(playlistId, from, to) }
    }

    fun reorderSongs(newOrder: List<SongIdentity>) {
        viewModelScope.launch { repo.reorderSongs(playlistId, newOrder) }
    }

    fun removeSong(songId: Long) {
        viewModelScope.launch { repo.removeSongFromPlaylist(playlistId, songId) }
    }
}
