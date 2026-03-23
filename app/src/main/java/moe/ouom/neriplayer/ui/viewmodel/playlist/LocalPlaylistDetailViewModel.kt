package moe.ouom.neriplayer.ui.viewmodel.playlist

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/LocalPlaylistDetailViewModel
 * Updated: 2026/3/23
 */


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportResult
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey

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

@Suppress("unused")
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
                    val preparedSongs = prepareScannedSongs(result.songs)
                    LocalScanPreviewState(
                        visible = true,
                        isScanning = false,
                        songs = preparedSongs,
                        selectedKeys = preparedSongs.map { it.stableKey() }.toSet()
                    )
                } else {
                    LocalScanPreviewState()
                }
                onResult(result)
            } catch (_: CancellationException) {
                // 用户主动返回时直接取消，不再回调已经离开的界面
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

    fun syncFavoritesToNeteaseLiked(onResult: (NeteaseLikeSyncResult) -> Unit) {
        viewModelScope.launch {
            val result = repo.syncFavoritesToNeteaseLiked(AppContainer.neteaseClient)
            onResult(result)
        }
    }

    fun syncSongsToNeteaseLiked(
        songs: List<SongItem>,
        onResult: (NeteaseLikeSyncResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = repo.syncSongsToNeteaseLiked(AppContainer.neteaseClient, songs)
            onResult(result)
        }
    }

    private fun isActiveScanSession(sessionId: Long, currentJob: Job): Boolean {
        return scanJob === currentJob && scanSessionId == sessionId
    }

    private fun prepareScannedSongs(songs: List<SongItem>): List<SongItem> {
        val localFilesIdentities = LocalFilesPlaylist
            .firstOrNull(repo.playlists.value, app)
            ?.songs
            .orEmpty()
            .map { it.identity() }
            .toSet()

        return songs
            .filterNot { it.identity() in localFilesIdentities }
            .sortedWith(localScanSongComparator())
    }

    private fun localScanSongComparator(): Comparator<SongItem> {
        return compareByDescending<SongItem> { metadataRichnessScore(it) }
            .thenByDescending { it.durationMs.coerceAtLeast(0L) }
            .thenBy { it.name.lowercase() }
            .thenBy { it.artist.lowercase() }
            .thenBy { it.album.lowercase() }
            .thenBy { it.localFilePath.orEmpty().lowercase() }
            .thenBy { it.stableKey() }
    }

    private fun metadataRichnessScore(song: SongItem): Int {
        val fileTitle = song.localFileName
            ?.substringBeforeLast('.', song.localFileName)
            ?.trim()
            .orEmpty()
        var score = 0

        val hasMeaningfulTitle = song.name.isNotBlank()
        if (hasMeaningfulTitle) {
            score += if (fileTitle.isNotBlank() && !song.name.equals(fileTitle, ignoreCase = true)) 3 else 1
        }

        if (song.artist.isMeaningfulMetadata(app.getString(moe.ouom.neriplayer.R.string.music_unknown_artist))) {
            score += 2
        }
        if (song.album.isMeaningfulAlbum(app)) {
            score += 2
        }
        if (song.durationMs > 0L) {
            score += 1
        }
        if (!song.coverUrl.isNullOrBlank() || !song.originalCoverUrl.isNullOrBlank()) {
            score += 1
        }
        if (!song.originalName.isNullOrBlank() && !song.originalName.equals(fileTitle, ignoreCase = true)) {
            score += 1
        }
        if (!song.originalArtist.isNullOrBlank()) {
            score += 1
        }

        return score
    }
}

private fun String?.isMeaningfulMetadata(unknownArtist: String): Boolean {
    val value = this?.trim().orEmpty()
    return value.isNotBlank() && !value.equals(unknownArtist, ignoreCase = true)
}

private fun String?.isMeaningfulAlbum(application: Application): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isBlank()) return false
    if (value == LocalSongSupport.LOCAL_ALBUM_IDENTITY) return false
    return !LocalFilesPlaylist.matches(value, application)
}
