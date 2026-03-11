package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.LocalAudioImportManager
import moe.ouom.neriplayer.data.LocalAudioImportResult
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.SongIdentity
import moe.ouom.neriplayer.data.identity

data class LocalPlaylistDetailUiState(
    val playlist: LocalPlaylist? = null,
    val isResolved: Boolean = false
)

data class LocalAudioImportUiResult(
    val importedCount: Int,
    val failedCount: Int,
    val preservedExisting: Boolean = false
)

class LocalPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repo = LocalPlaylistRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LocalPlaylistDetailUiState())
    val uiState: StateFlow<LocalPlaylistDetailUiState> = _uiState

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private var playlistId: Long = 0L
    private var playlistCollectJob: Job? = null
    private var scanJob: Job? = null

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
            repo.renamePlaylist(playlistId, newName)
        }
    }

    fun importSongs(uris: List<Uri>, onResult: (LocalAudioImportUiResult) -> Unit) {
        viewModelScope.launch {
            val beforeSongs = repo.playlists.value.firstOrNull { it.id == playlistId }?.songs.orEmpty()
            val beforeKeys = beforeSongs.map { it.identity() }.toSet()
            val result = LocalAudioImportManager.importSongs(app, uris)
            if (result.songs.isNotEmpty()) {
                repo.addSongsToPlaylist(playlistId, result.songs)
            }
            val afterSongs = repo.playlists.value.firstOrNull { it.id == playlistId }?.songs.orEmpty()
            val afterKeys = afterSongs.map { it.identity() }.toSet()
            onResult(
                LocalAudioImportUiResult(
                    importedCount = (afterKeys - beforeKeys).size,
                    failedCount = result.failedCount
                )
            )
        }
    }

    fun scanDeviceSongs(onResult: (LocalAudioImportResult) -> Unit) {
        if (_isScanning.value) return
        _isScanning.value = true
        lateinit var currentJob: Job
        currentJob = viewModelScope.launch {
            try {
                onResult(LocalAudioImportManager.scanDeviceSongs(app))
            } catch (_: CancellationException) {
                // 返回扫描页时允许立刻取消，不继续回调 UI。
            } finally {
                if (scanJob === currentJob) {
                    scanJob = null
                }
                _isScanning.value = false
            }
        }
        scanJob = currentJob
    }

    fun cancelDeviceSongScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun applyScannedSongs(
        songs: List<SongItem>,
        onResult: (LocalAudioImportUiResult) -> Unit
    ) {
        viewModelScope.launch {
            val beforeSongs = repo.playlists.value.firstOrNull { it.id == playlistId }?.songs.orEmpty()
            val beforeKeys = beforeSongs.map { it.identity() }.toSet()
            repo.addSongsToLocalFilesPlaylist(songs)
            val afterSongs = repo.playlists.value.firstOrNull { it.id == playlistId }?.songs.orEmpty()
            val afterKeys = afterSongs.map { it.identity() }.toSet()
            onResult(
                LocalAudioImportUiResult(
                    importedCount = (afterKeys - beforeKeys).size,
                    failedCount = 0
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
