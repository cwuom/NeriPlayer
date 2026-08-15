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
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportResult
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioScanPhase
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioScanProgress
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistDeleteResult
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSongDeleteResult
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.Locale

data class LocalPlaylistDetailUiState(
    val playlist: LocalPlaylist? = null,
    val isResolved: Boolean = false,
    val initializationFailed: Boolean = false,
    val requestedPlaylistId: Long? = null
)

data class LocalAudioImportUiResult(
    val importedCount: Int,
    val failedCount: Int,
    val addedSongs: List<SongItem> = emptyList(),
    val createdPlaylist: LocalPlaylist? = null
)

data class LocalFilesDownloadedSongDeleteUiResult(
    val deletedCount: Int,
    val notDeletedCount: Int
)

data class LocalScanPreviewState(
    val visible: Boolean = false,
    val isScanning: Boolean = false,
    val scanProgress: LocalAudioScanProgress = LocalAudioScanProgress(),
    val songs: List<SongItem> = emptyList(),
    val query: String = "",
    val metadataOnly: Boolean = false,
    val hideExistingLocalPlaylistSongs: Boolean = false,
    val existingLocalPlaylistKeys: Set<String> = emptySet(),
    val hideDuplicateMetadataSongs: Boolean = false,
    val duplicateMetadataKeys: Set<String> = emptySet(),
    val metadataPendingKeys: Set<String> = emptySet(),
    val selectedKeys: Set<String> = emptySet()
)

internal fun scannedSongKeysAlreadyInLocalPlaylists(
    scannedSongs: List<SongItem>,
    localPlaylists: List<LocalPlaylist>
): Set<String> {
    if (scannedSongs.isEmpty() || localPlaylists.isEmpty()) return emptySet()

    val existingAddedLocalSongs = localPlaylists
        .asSequence()
        .flatMap { it.songs.asSequence() }
        .filter { LocalSongSupport.isLocalSong(it, null) }
        .toList()
    val existingAddedLocalIndex = LocalScanDuplicateIndex(existingAddedLocalSongs)
    return scannedSongs.asSequence()
        .filter { song ->
            LocalSongSupport.isLocalSong(song, null) && existingAddedLocalIndex.contains(song)
        }
        .mapTo(LinkedHashSet()) { it.stableKey() }
}

internal fun duplicateScannedSongKeysByMetadata(scannedSongs: List<SongItem>): Set<String> {
    if (scannedSongs.size < 2) return emptySet()

    val seenMetadata = HashSet<LocalScanMetadataFingerprint>(scannedSongs.size)
    return buildSet {
        scannedSongs.forEach { song ->
            val fingerprint = song.localScanMetadataFingerprint() ?: return@forEach
            if (!seenMetadata.add(fingerprint)) {
                add(song.stableKey())
            }
        }
    }
}

internal fun remapScanPreviewKeySet(
    keys: Set<String>,
    previousSong: SongItem,
    updatedSong: SongItem
): Set<String> {
    val previousKey = previousSong.stableKey()
    val updatedKey = updatedSong.stableKey()
    if (previousKey == updatedKey || previousKey !in keys) return keys
    return keys - previousKey + updatedKey
}

private data class LocalScanMetadataFingerprint(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

private fun SongItem.localScanMetadataFingerprint(): LocalScanMetadataFingerprint? {
    val title = normalizeLocalScanMetadataText(name)
    val artist = normalizeLocalScanMetadataText(artist)
    val album = normalizeLocalScanMetadataText(album)
    if (
        title.isBlank() ||
        artist.isBlank() ||
        album.isBlank() ||
        album == LocalSongSupport.LOCAL_ALBUM_IDENTITY ||
        durationMs <= 0L
    ) {
        return null
    }
    return LocalScanMetadataFingerprint(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs
    )
}

private fun normalizeLocalScanMetadataText(value: String): String {
    return value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(LOCAL_SCAN_METADATA_WHITESPACE, " ")
}

private val LOCAL_SCAN_METADATA_WHITESPACE = Regex("\\s+")

internal fun shouldThrottleScanMetadataHydration(
    workerSlot: Int,
    playbackIntentActive: Boolean
): Boolean = playbackIntentActive && workerSlot >= SCAN_METADATA_HYDRATION_PLAYBACK_PARALLELISM

private const val SCAN_METADATA_HYDRATION_PLAYBACK_PARALLELISM = 1
private const val SCAN_METADATA_HYDRATION_PLAYBACK_WAIT_MS = 80L

private class LocalScanDuplicateIndex(songs: List<SongItem>) {
    private val identities = HashSet<SongIdentity>(songs.size)
    private val localKeys = HashSet<String>()

    init {
        songs.forEach { song ->
            identities += song.identity()
            localKeys += LocalSongSupport.localDuplicateKeys(
                song = song,
                includeMetadataFallback = true
            )
        }
    }

    fun contains(song: SongItem): Boolean {
        return song.identity() in identities ||
            LocalSongSupport.localDuplicateKeys(
                song = song,
                includeMetadataFallback = true
            ).any(localKeys::contains)
    }
}

data class LocalMetadataProcessingState(
    val isProcessing: Boolean = false,
    val playlistId: Long? = null,
    val processedCount: Int = 0,
    val totalCount: Int = 0
)

@Suppress("unused")
class LocalPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "LocalPlaylistScanVM"
        const val SCAN_METADATA_HYDRATION_PARALLELISM = 8
    }

    private val app = application
    private val repo = LocalPlaylistRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LocalPlaylistDetailUiState())
    val uiState: StateFlow<LocalPlaylistDetailUiState> = _uiState

    private val _scanPreviewState = MutableStateFlow(LocalScanPreviewState())
    val scanPreviewState: StateFlow<LocalScanPreviewState> = _scanPreviewState

    private val _metadataProcessingState = MutableStateFlow(LocalMetadataProcessingState())
    val metadataProcessingState: StateFlow<LocalMetadataProcessingState> = _metadataProcessingState

    private var playlistId: Long = 0L
    private var playlistCollectJob: Job? = null
    private var scanJob: Job? = null
    private var scanSessionId: Long = 0L

    fun start(id: Long) {
        if (playlistId == id && _uiState.value.playlist?.id == id) return
        playlistId = id
        playlistCollectJob?.cancel()
        _uiState.value = LocalPlaylistDetailUiState(requestedPlaylistId = id)
        playlistCollectJob = viewModelScope.launch {
            if (!repo.awaitInitialized()) {
                if (playlistId != id) return@launch
                _uiState.value = LocalPlaylistDetailUiState(
                    initializationFailed = true,
                    requestedPlaylistId = id
                )
                return@launch
            }
            repo.playlists.collect { list ->
                _uiState.value = LocalPlaylistDetailUiState(
                    playlist = list.firstOrNull { it.id == id },
                    isResolved = true,
                    requestedPlaylistId = id
                )
            }
        }
    }

    fun rename(newName: String) {
        launchPlaylistMutation("renamePlaylist") {
            repo.renamePlaylist(playlistId, newName)
        }
    }

    fun scanDeviceSongs(onResult: (LocalAudioImportResult) -> Unit) {
        startLocalAudioScan(onResult) { onProgress ->
            LocalAudioImportManager.scanDeviceSongs(app, onProgress)
        }
    }

    fun scanFolderSongs(folderUri: Uri, onResult: (LocalAudioImportResult) -> Unit) {
        startLocalAudioScan(onResult) { onProgress ->
            LocalAudioImportManager.scanFolderSongs(app, folderUri, onProgress)
        }
    }

    private fun startLocalAudioScan(
        onResult: (LocalAudioImportResult) -> Unit,
        scanAction: suspend ((LocalAudioScanProgress) -> Unit) -> LocalAudioImportResult
    ) {
        if (_scanPreviewState.value.isScanning) {
            _scanPreviewState.value = _scanPreviewState.value.copy(visible = true)
            return
        }

        scanJob?.cancel()
        val sessionId = ++scanSessionId
        val scanStartedAt = SystemClock.elapsedRealtime()
        _scanPreviewState.value = LocalScanPreviewState(
            visible = true,
            isScanning = true,
            scanProgress = LocalAudioScanProgress()
        )
        NPLogger.d(TAG, "start scan session=$sessionId")

        lateinit var currentJob: Job
        currentJob = viewModelScope.launch {
            try {
                val result = scanAction { progress ->
                    if (scanSessionId == sessionId) {
                        _scanPreviewState.value = _scanPreviewState.value.copy(
                            visible = true,
                            isScanning = true,
                            scanProgress = progress
                        )
                    }
                }
                if (!isActiveScanSession(sessionId, currentJob)) return@launch
                val scanElapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
                NPLogger.d(
                    TAG,
                    "scan action finished: session=$sessionId, songs=${result.songs.size}, failed=${result.failedCount}, completed=${result.completed}, elapsed=${scanElapsedMs}ms"
                )
                val finalizedResult = if (result.completed) {
                    val hydratedSongs = if (result.metadataDeferred) {
                        hydrateScanSongs(
                            sessionId = sessionId,
                            songs = result.songs,
                            scanStartedAt = scanStartedAt
                        )
                    } else {
                        result.songs
                    }
                    if (!isActiveScanSession(sessionId, currentJob)) return@launch

                    val prepareStartedAt = SystemClock.elapsedRealtime()
                    val localPlaylists = repo.playlists.value.toList()
                    val preparedState = withContext(Dispatchers.Default) {
                        val preparedSongs = prepareScannedSongs(hydratedSongs)
                        LocalScanPreviewState(
                            visible = true,
                            isScanning = false,
                            scanProgress = LocalAudioScanProgress(
                                phase = LocalAudioScanPhase.COMPLETED,
                                processed = preparedSongs.size,
                                total = preparedSongs.size,
                                discoveredSongs = preparedSongs.size,
                                elapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
                            ),
                            songs = preparedSongs,
                            existingLocalPlaylistKeys = scannedSongKeysAlreadyInLocalPlaylists(
                                scannedSongs = preparedSongs,
                                localPlaylists = localPlaylists
                            ),
                            duplicateMetadataKeys = duplicateScannedSongKeysByMetadata(
                                preparedSongs
                            ),
                            metadataPendingKeys = emptySet(),
                            selectedKeys = preparedSongs.mapTo(LinkedHashSet(preparedSongs.size)) {
                                it.stableKey()
                            }
                        )
                    }
                    val prepareElapsedMs = SystemClock.elapsedRealtime() - prepareStartedAt
                    NPLogger.d(
                        TAG,
                        "prepareScannedSongs finished: session=$sessionId, input=${result.songs.size}, output=${preparedState.songs.size}, elapsed=${prepareElapsedMs}ms"
                    )
                    val scanOptions = _scanPreviewState.value
                    val hiddenKeys = buildSet {
                        if (scanOptions.metadataOnly) {
                            preparedState.songs
                                .asSequence()
                                .filterNot(::hasMeaningfulScanMetadata)
                                .forEach { add(it.stableKey()) }
                        }
                        if (scanOptions.hideExistingLocalPlaylistSongs) {
                            addAll(preparedState.existingLocalPlaylistKeys)
                        }
                        if (scanOptions.hideDuplicateMetadataSongs) {
                            addAll(preparedState.duplicateMetadataKeys)
                        }
                    }
                    val finalizedState = preparedState.copy(
                        query = scanOptions.query,
                        metadataOnly = scanOptions.metadataOnly,
                        hideExistingLocalPlaylistSongs =
                            scanOptions.hideExistingLocalPlaylistSongs,
                        hideDuplicateMetadataSongs = scanOptions.hideDuplicateMetadataSongs,
                        selectedKeys = preparedState.selectedKeys - hiddenKeys
                    )
                    _scanPreviewState.value = finalizedState
                    result.copy(
                        songs = finalizedState.songs,
                        metadataDeferred = false
                    )
                } else {
                    LocalScanPreviewState()
                    result
                }
                onResult(finalizedResult)
            } catch (_: CancellationException) {
                // 用户主动返回时直接取消，不再回调已经离开的界面
                NPLogger.d(TAG, "scan cancelled: session=$sessionId")
            } finally {
                if (scanJob === currentJob) {
                    scanJob = null
                }
                if (scanSessionId == sessionId && _scanPreviewState.value.isScanning) {
                    _scanPreviewState.value = _scanPreviewState.value.copy(isScanning = false)
                }
                NPLogger.d(
                    TAG,
                    "scan session finished: session=$sessionId, totalElapsed=${SystemClock.elapsedRealtime() - scanStartedAt}ms"
                )
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

    fun updateScanPreviewMetadataOnly(metadataOnly: Boolean) {
        val current = _scanPreviewState.value
        if (current.metadataOnly == metadataOnly) return
        val selectedKeys = if (metadataOnly) {
            val metadataKeys = current.songs
                .asSequence()
                .filter { song ->
                    song.stableKey() in current.metadataPendingKeys ||
                        hasMeaningfulScanMetadata(song)
                }
                .mapTo(LinkedHashSet()) { it.stableKey() }
            current.selectedKeys.intersect(metadataKeys)
        } else {
            current.selectedKeys
        }
        _scanPreviewState.value = current.copy(
            metadataOnly = metadataOnly,
            selectedKeys = selectedKeys
        )
    }

    fun updateScanPreviewHideExistingLocalPlaylistSongs(
        hideExistingLocalPlaylistSongs: Boolean
    ) {
        val current = _scanPreviewState.value
        if (current.hideExistingLocalPlaylistSongs == hideExistingLocalPlaylistSongs) return
        _scanPreviewState.value = current.copy(
            hideExistingLocalPlaylistSongs = hideExistingLocalPlaylistSongs,
            selectedKeys = if (hideExistingLocalPlaylistSongs) {
                current.selectedKeys - current.existingLocalPlaylistKeys
            } else {
                current.selectedKeys
            }
        )
    }

    fun updateScanPreviewHideDuplicateMetadataSongs(hideDuplicateMetadataSongs: Boolean) {
        val current = _scanPreviewState.value
        if (current.hideDuplicateMetadataSongs == hideDuplicateMetadataSongs) return
        _scanPreviewState.value = current.copy(
            hideDuplicateMetadataSongs = hideDuplicateMetadataSongs,
            selectedKeys = if (hideDuplicateMetadataSongs) {
                current.selectedKeys - current.duplicateMetadataKeys
            } else {
                current.selectedKeys
            }
        )
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
            runLocalPlaylistMutationSafely("applyScannedSongs") {
                repo.addScannedSongsToLocalFilesPlaylistAndCount(songs)
            }.onSuccess { importedCount ->
                onResult(
                    LocalAudioImportUiResult(
                        importedCount = importedCount,
                        failedCount = 0
                    )
                )
            }.onFailure {
                onResult(LocalAudioImportUiResult(importedCount = 0, failedCount = songs.size))
            }
        }
    }

    fun createPlaylistWithScannedSongs(
        name: String,
        songs: List<SongItem>,
        onResult: (LocalAudioImportUiResult) -> Unit
    ) {
        viewModelScope.launch {
            runLocalPlaylistMutationSafely("createPlaylistWithScannedSongs") {
                repo.createPlaylistWithScannedSongs(name, songs)
            }.onSuccess { playlist ->
                onResult(
                    LocalAudioImportUiResult(
                        importedCount = playlist.songs.size,
                        failedCount = 0,
                        addedSongs = playlist.songs.toList(),
                        createdPlaylist = playlist
                    )
                )
            }.onFailure {
                onResult(LocalAudioImportUiResult(importedCount = 0, failedCount = songs.size))
            }
        }
    }

    fun addScannedSongsToPlaylist(
        targetPlaylistId: Long,
        songs: List<SongItem>,
        onResult: (LocalAudioImportUiResult) -> Unit
    ) {
        viewModelScope.launch {
            runLocalPlaylistMutationSafely("addScannedSongsToPlaylist") {
                repo.addScannedSongsToPlaylistWithResult(targetPlaylistId, songs)
            }.onSuccess { addResult ->
                onResult(
                    LocalAudioImportUiResult(
                        importedCount = addResult.addedCount,
                        failedCount = 0,
                        addedSongs = addResult.addedSongs
                    )
                )
            }.onFailure {
                onResult(LocalAudioImportUiResult(importedCount = 0, failedCount = songs.size))
            }
        }
    }

    fun removeSongs(
        songs: List<SongItem>,
        onResult: (Result<List<LocalPlaylistSongDeleteResult>>) -> Unit = {}
    ) {
        if (songs.isEmpty()) {
            onResult(Result.success(emptyList()))
            return
        }
        viewModelScope.launch {
            onResult(
                runLocalPlaylistMutationSafely("removeSongs") {
                    repo.removeSongsFromPlaylistByIdentityWithResult(playlistId, songs)
                }
            )
        }
    }

    fun deleteDownloadedSongs(
        songs: List<DownloadedSong>,
        onResult: (LocalFilesDownloadedSongDeleteUiResult) -> Unit
    ) {
        if (songs.isEmpty()) {
            onResult(LocalFilesDownloadedSongDeleteUiResult(0, 0))
            return
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                val deletion = GlobalDownloadManager.deleteDownloadedSongsWithResult(
                    context = app,
                    songs = songs
                )
                onResult(
                    LocalFilesDownloadedSongDeleteUiResult(
                        deletedCount = deletion.deletedSongs.size,
                        notDeletedCount = deletion.failedSongs.size
                    )
                )
            }
        }
    }

    fun clearSongs(
        onResult: (Result<List<LocalPlaylistSongDeleteResult>>) -> Unit = {}
    ) {
        viewModelScope.launch {
            onResult(
                runLocalPlaylistMutationSafely("clearSongs") {
                    repo.clearPlaylistSongsWithResult(playlistId)
                }
            )
        }
    }

    fun delete(onResult: (Result<List<LocalPlaylistDeleteResult>>) -> Unit) {
        viewModelScope.launch {
            onResult(
                runLocalPlaylistMutationSafely("deletePlaylist") {
                    repo.deletePlaylistsWithResult(listOf(playlistId))
                }
            )
        }
    }

    fun moveSong(from: Int, to: Int) {
        launchPlaylistMutation("moveSong") { repo.moveSong(playlistId, from, to) }
    }

    fun reorderSongs(newOrder: List<SongIdentity>) {
        launchPlaylistMutation("reorderSongs") { repo.reorderSongs(playlistId, newOrder) }
    }

    fun removeSong(songId: Long) {
        launchPlaylistMutation("removeSong") { repo.removeSongFromPlaylist(playlistId, songId) }
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

    fun fetchNeteaseRemotePlaylists(
        onResult: (Result<List<NeteaseRemotePlaylist>>) -> Unit
    ): Job {
        return viewModelScope.launch {
            onResult(
                runCatching {
                    repo.fetchNeteaseRemotePlaylists(AppContainer.neteaseClient)
                }
            )
        }
    }

    fun syncSongsToNeteasePlaylist(
        targetPlaylistId: Long,
        songs: List<SongItem>,
        onResult: (NeteaseLikeSyncResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = repo.syncSongsToNeteasePlaylist(
                client = AppContainer.neteaseClient,
                targetPlaylistId = targetPlaylistId,
                songs = songs
            )
            onResult(result)
        }
    }

    private fun isActiveScanSession(sessionId: Long, currentJob: Job): Boolean {
        return scanJob === currentJob && scanSessionId == sessionId
    }

    private suspend fun hydrateScanSongs(
        sessionId: Long,
        songs: List<SongItem>,
        scanStartedAt: Long
    ): List<SongItem> {
        if (songs.isEmpty()) return emptyList()

        val processedCount = AtomicInteger(0)
        val totalCount = songs.size
        _scanPreviewState.update { current ->
            current.copy(
                visible = true,
                isScanning = true,
                scanProgress = LocalAudioScanProgress(
                    phase = LocalAudioScanPhase.HYDRATING_METADATA,
                    processed = 0,
                    total = totalCount,
                    discoveredSongs = totalCount,
                    elapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
                )
            )
        }

        val hydrationDispatcher = Dispatchers.IO.limitedParallelism(
            SCAN_METADATA_HYDRATION_PARALLELISM
        )
        val hydratedSongs = arrayOfNulls<SongItem>(totalCount)
        val nextSongIndex = AtomicInteger(0)
        coroutineScope {
            (0 until SCAN_METADATA_HYDRATION_PARALLELISM).map { workerSlot ->
                async(hydrationDispatcher) {
                    while (true) {
                        ensureActive()
                        awaitPlaybackSafeMetadataHydrationSlot(workerSlot)
                        ensureActive()
                        val songIndex = nextSongIndex.getAndIncrement()
                        if (songIndex >= totalCount) break
                        val song = songs[songIndex]

                        val hydratedSong = runCatching {
                            LocalAudioImportManager.hydrateLocalSongTextMetadata(
                                context = app,
                                song = song,
                                resolveCoverFallback = false
                            )
                        }.onFailure {
                            NPLogger.w(
                                TAG,
                                "scan metadata hydration failed for ${song.name}: ${it.message}"
                            )
                        }.getOrDefault(song)
                        val processed = processedCount.incrementAndGet()
                        if (scanSessionId == sessionId) {
                            _scanPreviewState.update { current ->
                                if (scanSessionId != sessionId) {
                                    current
                                } else {
                                    current.copy(
                                        isScanning = true,
                                        scanProgress = LocalAudioScanProgress(
                                            phase = LocalAudioScanPhase.HYDRATING_METADATA,
                                            processed = processed,
                                            total = totalCount,
                                            discoveredSongs = totalCount,
                                            elapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
                                        )
                                    )
                                }
                            }
                        }
                        hydratedSongs[songIndex] = hydratedSong
                    }
                }
            }.awaitAll()
        }
        return hydratedSongs.mapIndexed { index, hydratedSong -> hydratedSong ?: songs[index] }
    }

    private suspend fun awaitPlaybackSafeMetadataHydrationSlot(workerSlot: Int) {
        while (
            shouldThrottleScanMetadataHydration(
                workerSlot = workerSlot,
                playbackIntentActive = PlayerManager.playbackControlPlayingFlow.value
            )
        ) {
            delay(SCAN_METADATA_HYDRATION_PLAYBACK_WAIT_MS)
        }
    }

    private fun launchPlaylistMutation(
        operation: String,
        mutation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runLocalPlaylistMutationSafely(operation, mutation)
        }
    }

    private fun prepareScannedSongs(songs: List<SongItem>): List<SongItem> {
        return songs.sortedWith(localScanSongComparator())
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

    private fun hasMeaningfulScanMetadata(song: SongItem): Boolean {
        val unknownArtist = app.getString(moe.ouom.neriplayer.R.string.music_unknown_artist)
        val fileTitle = song.localFileName
            ?.substringBeforeLast('.', song.localFileName)
            ?.trim()
            .orEmpty()
        val hasTitleMetadata = song.name.isNotBlank() &&
            (fileTitle.isBlank() || !song.name.equals(fileTitle, ignoreCase = true))
        return hasTitleMetadata ||
            song.artist.isMeaningfulMetadata(unknownArtist) ||
            song.album.isMeaningfulAlbum(app) ||
            !song.coverUrl.isNullOrBlank() ||
            !song.originalCoverUrl.isNullOrBlank()
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
