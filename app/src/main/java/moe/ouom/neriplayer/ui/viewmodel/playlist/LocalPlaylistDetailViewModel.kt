package moe.ouom.neriplayer.ui.viewmodel.playlist

import android.app.Application
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
import moe.ouom.neriplayer.data.stableKey

data class LocalPlaylistDetailUiState(
    val playlist: LocalPlaylist? = null,
    val isResolved: Boolean = false
)

data class LocalAudioImportUiResult(
    val importedCount: Int,
    val failedCount: Int
)

data class LocalScanPreviewState(
    val visible: Boolean = false,
    val isScanning: Boolean = false,
    val songs: List<SongItem> = emptyList(),
    val query: String = "",
    val selectedKeys: Set<String> = emptySet()
)

class LocalPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repo = LocalPlaylistRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LocalPlaylistDetailUiState())
    val uiState: StateFlow<LocalPlaylistDetailUiState> = _uiState

    private val _scanPreviewState = MutableStateFlow(LocalScanPreviewState())
    val scanPreviewState: StateFlow<LocalScanPreviewState> = _scanPreviewState

    private var playlistId: Long = 0L
    private var playlistCollectJob: Job? = null
    private var scanJob: Job? = null
    private var scanSessionId: Long = 0L

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

    fun scanDeviceSongs(onResult: (LocalAudioImportResult) -> Unit) {
        if (_scanPreviewState.value.isScanning) {
            _scanPreviewState.value = _scanPreviewState.value.copy(visible = true)
            return
        }

        scanJob?.cancel()
        val sessionId = ++scanSessionId
        _scanPreviewState.value = LocalScanPreviewState(visible = true, isScanning = true)

        lateinit var currentJob: Job
        currentJob = viewModelScope.launch {
            try {
                val result = LocalAudioImportManager.scanDeviceSongs(app)
                if (!isActiveScanSession(sessionId, currentJob)) return@launch
                _scanPreviewState.value = if (result.completed) {
                    LocalScanPreviewState(
                        visible = true,
                        isScanning = false,
                        songs = result.songs,
                        selectedKeys = result.songs.map { it.stableKey() }.toSet()
                    )
                } else {
                    LocalScanPreviewState()
                }
                onResult(result)
            } catch (_: CancellationException) {
                // 用户主动返回时直接取消，不再回调已经离开的界面。
            } finally {
                if (scanJob === currentJob) {
                    scanJob = null
                }
                if (scanSessionId == sessionId && _scanPreviewState.value.isScanning) {
                    _scanPreviewState.value = _scanPreviewState.value.copy(isScanning = false)
                }
            }
        }
        scanJob = currentJob
    }

    fun cancelDeviceSongScan() {
        scanSessionId += 1
        scanJob?.cancel()
        scanJob = null
        if (_scanPreviewState.value.isScanning) {
            _scanPreviewState.value = _scanPreviewState.value.copy(isScanning = false)
        }
    }

    fun updateScanPreviewQuery(query: String) {
        _scanPreviewState.value = _scanPreviewState.value.copy(query = query)
    }

    fun updateScanPreviewSelection(selectedKeys: Set<String>) {
        _scanPreviewState.value = _scanPreviewState.value.copy(selectedKeys = selectedKeys)
    }

    fun clearScanPreview(cancelScan: Boolean) {
        if (cancelScan) {
            cancelDeviceSongScan()
        }
        _scanPreviewState.value = LocalScanPreviewState()
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

    private fun isActiveScanSession(sessionId: Long, currentJob: Job): Boolean {
        return scanJob === currentJob && scanSessionId == sessionId
    }
}
