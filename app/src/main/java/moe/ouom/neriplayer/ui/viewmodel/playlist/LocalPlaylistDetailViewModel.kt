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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
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

internal fun shouldScheduleLocalDurationRefresh(song: SongItem): Boolean {
    if (song.durationMs > 0L) return false
    return song.localFilePath?.isNotBlank() == true ||
        song.mediaUri?.let { reference ->
            reference.startsWith("content://", ignoreCase = true) ||
                reference.startsWith("file://", ignoreCase = true) ||
                reference.startsWith("/")
        } == true
}

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

internal fun mergeLocalMetadataRefreshCandidates(
    pending: Map<String, SongItem>,
    incoming: List<SongItem>
): LinkedHashMap<String, SongItem> {
    val merged = LinkedHashMap<String, SongItem>(pending.size + incoming.size)
    merged.putAll(pending)
    incoming.forEach { song ->
        merged[song.stableKey()] = song
    }
    return merged
}

internal fun applyHydratedSongsToScanPreview(
    state: LocalScanPreviewState,
    hydratedSongs: List<SongItem?>,
    progress: LocalAudioScanProgress,
    startIndex: Int = 0,
    targetKeys: List<String>? = null
): LocalScanPreviewState {
    require(startIndex >= 0) { "startIndex must be non-negative" }
    val resolvedProgress = if (state.scanProgress.processed > progress.processed) {
        state.scanProgress
    } else {
        progress
    }
    if (hydratedSongs.isEmpty() || state.songs.isEmpty()) {
        return state.copy(scanProgress = resolvedProgress)
    }

    val updatedSongs = state.songs.toMutableList()
    var selectedKeys = state.selectedKeys
    var existingLocalPlaylistKeys = state.existingLocalPlaylistKeys
    var duplicateMetadataKeys = state.duplicateMetadataKeys
    var metadataPendingKeys = state.metadataPendingKeys
    hydratedSongs.forEachIndexed { index, hydratedSong ->
        val targetIndex = targetKeys
            ?.getOrNull(index)
            ?.let { key -> updatedSongs.indexOfFirst { it.stableKey() == key } }
            ?.takeIf { it >= 0 }
            ?: (startIndex + index)
        if (hydratedSong == null || targetIndex !in updatedSongs.indices) {
            return@forEachIndexed
        }
        val previousSong = updatedSongs[targetIndex]
        updatedSongs[targetIndex] = hydratedSong
        selectedKeys = remapScanPreviewKeySet(selectedKeys, previousSong, hydratedSong)
        existingLocalPlaylistKeys = remapScanPreviewKeySet(
            existingLocalPlaylistKeys,
            previousSong,
            hydratedSong
        )
        duplicateMetadataKeys = remapScanPreviewKeySet(
            duplicateMetadataKeys,
            previousSong,
            hydratedSong
        )
        metadataPendingKeys = metadataPendingKeys - previousSong.stableKey() - hydratedSong.stableKey()
    }
    return state.copy(
        scanProgress = resolvedProgress,
        songs = updatedSongs,
        selectedKeys = selectedKeys,
        existingLocalPlaylistKeys = existingLocalPlaylistKeys,
        duplicateMetadataKeys = duplicateMetadataKeys,
        metadataPendingKeys = metadataPendingKeys
    )
}

internal fun sortScannedSongsBySourceTime(songs: List<SongItem>): List<SongItem> {
    // sortedWith 保持相同时间的扫描顺序, 避免时间精度不足时跳来跳去
    return songs.sortedWith(compareByDescending<SongItem> { it.addedAt.coerceAtLeast(0L) })
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

internal fun claimNextScanMetadataHydrationIndex(
    nextSongIndex: AtomicInteger,
    totalCount: Int
): Int? {
    return nextSongIndex.getAndIncrement().takeIf { it < totalCount }
}

private const val SCAN_METADATA_HYDRATION_PLAYBACK_PARALLELISM = 1

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
        const val SCAN_PREVIEW_HYDRATION_BATCH_SIZE = 32
        const val SCAN_PREVIEW_HYDRATION_PARALLELISM = 8
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
    private var scanPreviewHydrationJob: Job? = null
    private var localDurationRefreshJob: Job? = null
    private var scanSessionId: Long = 0L

    fun start(id: Long) {
        if (playlistId == id && _uiState.value.playlist?.id == id) return
        playlistId = id
        playlistCollectJob?.cancel()
        localDurationRefreshJob?.cancel()
        localDurationRefreshJob = null
        _metadataProcessingState.value = LocalMetadataProcessingState()
        _uiState.value = LocalPlaylistDetailUiState(requestedPlaylistId = id)
        playlistCollectJob = viewModelScope.launch {
            launch {
                val preview = runCatching { repo.readFastPlaylist(id) }
                    .onFailure { error ->
                        NPLogger.w(TAG, "读取本地歌单首屏快照失败: ${error.message}")
                    }
                    .getOrNull()
                    ?: return@launch
                if (playlistId != id || _uiState.value.playlist != null) return@launch
                _uiState.value = _uiState.value.copy(
                    playlist = preview,
                    requestedPlaylistId = id
                )
            }
            if (!repo.awaitInitialized()) {
                if (playlistId != id) return@launch
                _uiState.value = _uiState.value.copy(
                    initializationFailed = true,
                    requestedPlaylistId = id
                )
                return@launch
            }
            var initialLocalDurationRefreshScheduled = false
            repo.playlists.collect { list ->
                val resolvedPlaylist = list.firstOrNull { it.id == id }
                if (
                    !initialLocalDurationRefreshScheduled &&
                    resolvedPlaylist != null &&
                    LocalFilesPlaylist.isSystemPlaylist(resolvedPlaylist, app)
                ) {
                    initialLocalDurationRefreshScheduled = true
                    scheduleMissingLocalDurationRefresh(resolvedPlaylist.songs)
                }
                _uiState.value = LocalPlaylistDetailUiState(
                    playlist = resolvedPlaylist,
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
        scanPreviewHydrationJob?.cancel()
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
                    val scanOptions = _scanPreviewState.value
                    val localPlaylists = repo.playlists.value.toList()
                    val completedProgress = LocalAudioScanProgress(
                        phase = LocalAudioScanPhase.COMPLETED,
                        processed = result.songs.size,
                        total = result.songs.size,
                        discoveredSongs = result.songs.size,
                        elapsedMs = SystemClock.elapsedRealtime() - scanStartedAt
                    )
                    val metadataPendingKeys = if (result.metadataDeferred) {
                        result.songs.mapTo(LinkedHashSet()) { it.stableKey() }
                    } else {
                        emptySet()
                    }
                    val preparedState = withContext(Dispatchers.Default) {
                        buildScanPreviewState(
                            songs = result.songs,
                            localPlaylists = localPlaylists,
                            options = scanOptions,
                            metadataPendingKeys = metadataPendingKeys,
                            selectedKeys = null,
                            progress = completedProgress
                        )
                    }
                    // 歌词、翻译和罗马字在播放或编辑时按需读取, 不阻塞扫描结果
                    _scanPreviewState.value = preparedState.copy(
                        isScanning = false,
                        scanProgress = completedProgress
                    )
                    if (result.metadataDeferred) {
                        scheduleScanPreviewMetadataHydration(
                            sessionId = sessionId,
                            songs = preparedState.songs
                        )
                    }
                    result.copy(
                        songs = preparedState.songs
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
        scanPreviewHydrationJob?.cancel()
        scanJob = null
        scanPreviewHydrationJob = null
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
        } else {
            scanPreviewHydrationJob?.cancel()
            scanPreviewHydrationJob = null
        }
        _scanPreviewState.value = LocalScanPreviewState()
    }

    private fun scheduleScanPreviewMetadataHydration(
        sessionId: Long,
        songs: List<SongItem>
    ) {
        scanPreviewHydrationJob?.cancel()
        val candidates = songs.filter(::shouldHydrateScanPreviewMetadata)
        if (candidates.isEmpty()) return

        val hydrationDispatcher = Dispatchers.IO.limitedParallelism(
            SCAN_PREVIEW_HYDRATION_PARALLELISM
        )
        scanPreviewHydrationJob = viewModelScope.launch {
            try {
                val batches = candidates.chunked(SCAN_PREVIEW_HYDRATION_BATCH_SIZE)
                batches.forEach { batch ->
                    val hydrated = coroutineScope {
                        batch.map { song ->
                            async(hydrationDispatcher) {
                                try {
                                    LocalAudioImportManager.hydrateLocalSongIdentityMetadata(
                                        context = app,
                                        song = song
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    NPLogger.w(
                                        TAG,
                                        "扫描预览补全本地歌曲失败: ${error.message}"
                                    )
                                    null
                                }
                            }
                        }.awaitAll()
                    }
                    if (
                        !isActive ||
                        scanSessionId != sessionId ||
                        !_scanPreviewState.value.visible
                    ) {
                        return@launch
                    }
                    _scanPreviewState.value = applyHydratedSongsToScanPreview(
                        state = _scanPreviewState.value,
                        hydratedSongs = hydrated,
                        progress = _scanPreviewState.value.scanProgress,
                        targetKeys = batch.map(SongItem::stableKey)
                    )
                }
            } finally {
                if (scanPreviewHydrationJob === coroutineContext[Job]) {
                    scanPreviewHydrationJob = null
                }
            }
        }
    }

    fun applyScannedSongs(
        songs: List<SongItem>,
        onResult: (LocalAudioImportUiResult) -> Unit
    ) {
        viewModelScope.launch {
            runLocalPlaylistMutationSafely("applyScannedSongs") {
                repo.addScannedSongsToLocalFilesPlaylistWithResult(songs)
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

    private fun scheduleMissingLocalDurationRefresh(songs: List<SongItem>) {
        val candidates = songs
            .distinctBy(SongItem::stableKey)
            .filter(::shouldScheduleLocalDurationRefresh)
        if (candidates.isEmpty()) return
        localDurationRefreshJob?.cancel()
        localDurationRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.refreshMissingLocalSongDurations(candidates)
            }.onFailure { error ->
                if (error !is CancellationException) {
                    NPLogger.w(TAG, "后台补全本地歌曲时长失败: ${error.message}")
                }
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

    private fun buildScanPreviewState(
        songs: List<SongItem>,
        localPlaylists: List<LocalPlaylist>,
        options: LocalScanPreviewState,
        metadataPendingKeys: Set<String>,
        selectedKeys: Set<String>?,
        progress: LocalAudioScanProgress
    ): LocalScanPreviewState {
        val preparedSongs = prepareScannedSongs(songs)
        val preparedSongKeys = preparedSongs.mapTo(LinkedHashSet(preparedSongs.size)) {
            it.stableKey()
        }
        val pendingKeys = metadataPendingKeys.intersect(preparedSongKeys)
        val existingLocalPlaylistKeys = scannedSongKeysAlreadyInLocalPlaylists(
            scannedSongs = preparedSongs,
            localPlaylists = localPlaylists
        )
        val duplicateMetadataKeys = duplicateScannedSongKeysByMetadata(preparedSongs)
        val hiddenKeys = buildSet {
            if (options.metadataOnly) {
                preparedSongs
                    .asSequence()
                    .filter { song ->
                        song.stableKey() !in pendingKeys && !hasMeaningfulScanMetadata(song)
                    }
                    .forEach { song -> add(song.stableKey()) }
            }
            if (options.hideExistingLocalPlaylistSongs) {
                addAll(existingLocalPlaylistKeys)
            }
            if (options.hideDuplicateMetadataSongs) {
                addAll(duplicateMetadataKeys)
            }
        }
        val initialSelection = selectedKeys ?: preparedSongKeys
        return LocalScanPreviewState(
            visible = true,
            isScanning = progress.phase != LocalAudioScanPhase.COMPLETED,
            scanProgress = progress,
            songs = preparedSongs,
            query = options.query,
            metadataOnly = options.metadataOnly,
            hideExistingLocalPlaylistSongs = options.hideExistingLocalPlaylistSongs,
            existingLocalPlaylistKeys = existingLocalPlaylistKeys,
            hideDuplicateMetadataSongs = options.hideDuplicateMetadataSongs,
            duplicateMetadataKeys = duplicateMetadataKeys,
            metadataPendingKeys = pendingKeys,
            selectedKeys = initialSelection.intersect(preparedSongKeys) - hiddenKeys
        )
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
        return sortScannedSongsBySourceTime(songs)
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

    private fun shouldHydrateScanPreviewMetadata(song: SongItem): Boolean {
        // 文件名或下载 metadata 已经给出有效身份时, 不再为首屏重复打开音频容器
        val unknownArtist = app.getString(moe.ouom.neriplayer.R.string.music_unknown_artist)
        val artistNeedsRepair = song.artist.trim().let { artist ->
            artist.isBlank() ||
                artist.equals(unknownArtist, ignoreCase = true) ||
                artist.equals("<unknown>", ignoreCase = true) ||
                artist.equals("<unknown artist>", ignoreCase = true) ||
                artist.equals("unknown artist", ignoreCase = true) ||
                artist.equals("未知艺术家", ignoreCase = true)
        }
        if (!artistNeedsRepair && hasMeaningfulScanMetadata(song)) return false
        val fileTitle = song.localFileName
            ?.substringBeforeLast('.', song.localFileName)
            ?.trim()
            .orEmpty()
        return artistNeedsRepair ||
            song.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY ||
            (fileTitle.isNotBlank() && song.name.trim().equals(fileTitle, ignoreCase = true))
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
