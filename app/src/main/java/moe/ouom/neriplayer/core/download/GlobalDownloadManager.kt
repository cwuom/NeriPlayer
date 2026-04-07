package moe.ouom.neriplayer.core.download

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
 * File: moe.ouom.neriplayer.core.download/GlobalDownloadManager
 * Updated: 2026/3/24
 */

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import kotlin.LazyThreadSafetyMode

/**
 * 全局下载管理器，统一维护下载任务和本地下载列表
 */
object GlobalDownloadManager {
    private const val TAG = "GlobalDownloadManager"
    private const val INITIAL_SCAN_DELAY_MS = 1_500L
    private const val DOWNLOAD_CATALOG_CACHE_FILE_NAME = "downloaded_song_catalog_v2.json"
    private const val DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS = 1_200L
    internal const val PLAYBACK_METADATA_HYDRATION_DELAY_MS = 1_500L
    internal const val LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSingleDownloading = MutableStateFlow(false)
    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()

    private val _downloadedSongs = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloadedSongs: StateFlow<List<DownloadedSong>> = _downloadedSongs.asStateFlow()
    private val _downloadPresenceVersion = MutableStateFlow(0)
    val downloadPresenceVersion: StateFlow<Int> = _downloadPresenceVersion.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val cancelledSongKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val catalogPersistenceLock = Any()
    private var refreshJob: Job? = null
    private var catalogPersistJob: Job? = null

    @Volatile
    private var downloadedSongPresenceIndex = DownloadedSongPresenceIndex.EMPTY

    @Volatile
    private var pendingRefresh = false

    @Volatile
    private var pendingForceRefresh = false

    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        observeDownloadProgress(appContext)
        scope.launch {
            val restoredCatalog = restorePersistedDownloadedSongs(appContext)
            val snapshotReady = ManagedDownloadStorage.ensureSnapshotCacheReady(appContext)
            if (!shouldRunInitialDownloadScan(snapshotReady = snapshotReady, catalogReady = restoredCatalog)) {
                return@launch
            }
            delay(INITIAL_SCAN_DELAY_MS)
            scanLocalFiles(appContext)
        }
    }

    private fun publishDownloadedSongs(
        context: Context,
        songs: List<DownloadedSong>,
        persistCatalog: Boolean
    ) {
        _downloadedSongs.value = songs
        downloadedSongPresenceIndex = buildDownloadedSongPresenceIndex(songs)
        _downloadPresenceVersion.value = _downloadPresenceVersion.value + 1
        if (persistCatalog) {
            scheduleDownloadedSongsCatalogPersist(context, songs)
        }
    }

    private fun scheduleDownloadedSongsCatalogPersist(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        val appContext = context.applicationContext
        synchronized(catalogPersistenceLock) {
            catalogPersistJob?.cancel()
            catalogPersistJob = scope.launch {
                delay(DOWNLOAD_CATALOG_PERSIST_DEBOUNCE_MS)
                persistDownloadedSongsCatalog(appContext, songs)
            }
        }
    }

    private data class DownloadedSongPresenceIndex(
        val localReferences: Set<String>,
        val identityKeys: Set<String>
    ) {
        fun contains(song: SongItem): Boolean {
            val localCandidates = listOfNotNull(
                song.localFilePath?.takeIf(String::isNotBlank),
                song.mediaUri?.takeIf(String::isNotBlank)
            )
            if (localCandidates.any(localReferences::contains)) {
                return true
            }
            return identityKeys.contains(downloadedSongPresenceIdentityKey(song.id, song.name, song.artist))
        }

        companion object {
            val EMPTY = DownloadedSongPresenceIndex(
                localReferences = emptySet(),
                identityKeys = emptySet()
            )
        }
    }

    private fun buildDownloadedSongPresenceIndex(
        songs: List<DownloadedSong>
    ): DownloadedSongPresenceIndex {
        return DownloadedSongPresenceIndex(
            localReferences = buildSet {
                songs.forEach { song ->
                    add(song.filePath)
                    song.mediaUri?.takeIf(String::isNotBlank)?.let(::add)
                }
            },
            identityKeys = songs.mapTo(mutableSetOf()) { song ->
                downloadedSongPresenceIdentityKey(song.id, song.name, song.artist)
            }
        )
    }

    private fun downloadedSongPresenceIdentityKey(
        id: Long,
        name: String,
        artist: String
    ): String {
        return "$id|$name|$artist"
    }

    private fun observeDownloadProgress(context: Context) {
        scope.launch {
            AudioDownloadManager.progressFlow.collect { progress ->
                progress?.let(::updateDownloadProgress)
            }
        }

        scope.launch {
            AudioDownloadManager.batchProgressFlow.collect { batchProgress ->
                if (batchProgress != null) {
                    updateBatchProgress(context, batchProgress)
                } else {
                    scanLocalFiles(context, forceRefresh = true)
                }
            }
        }

        scope.launch {
            AudioDownloadManager.isCancelledFlow.collect { isCancelled ->
                if (isCancelled) {
                    cancelAllActiveTasks()
                }
            }
        }
    }

    private fun updateDownloadProgress(progress: AudioDownloadManager.DownloadProgress) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.song.stableKey() == progress.songKey && task.status == DownloadStatus.DOWNLOADING) {
                task.copy(progress = progress)
            } else {
                task
            }
        }
    }

    private fun updateBatchProgress(
        context: Context,
        batchProgress: AudioDownloadManager.BatchDownloadProgress
    ) {
        if (batchProgress.completedSongs >= batchProgress.totalSongs) {
            scope.launch {
                delay(600)
                scanLocalFiles(context, forceRefresh = true)
            }
        }
    }

    private suspend fun finalizeCompletedDownload(
        context: Context,
        song: SongItem,
        refreshCatalog: Boolean
    ) {
        val storedAudio = resolveStoredAudio(context, song)
        if (storedAudio == null) {
            NPLogger.w(TAG, "下载完成但未找到已下载文件: ${song.name}")
            updateTaskStatus(song.stableKey(), DownloadStatus.COMPLETED)
            scanLocalFiles(context, forceRefresh = true)
            return
        }

        persistDownloadedMetadata(
            context = context,
            audio = storedAudio,
            song = song
        )
        updateTaskStatus(song.stableKey(), DownloadStatus.COMPLETED)
        if (refreshCatalog) {
            scanLocalFiles(context, forceRefresh = true)
        }
    }

    fun scanLocalFiles(context: Context, forceRefresh: Boolean = false) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (refreshJob?.isActive == true) {
                pendingRefresh = true
                pendingForceRefresh = pendingForceRefresh || forceRefresh
                return
            }

            refreshJob = scope.launch {
                var nextForceRefresh = forceRefresh
                while (true) {
                    reloadDownloadedSongs(appContext, forceRefresh = nextForceRefresh)
                    nextForceRefresh = consumePendingRefreshRequest() ?: break
                }
            }
        }
    }

    private fun consumePendingRefreshRequest(): Boolean? = synchronized(this) {
        val shouldRefreshAgain = pendingRefresh
        val shouldForceRefresh = pendingForceRefresh
        pendingRefresh = false
        pendingForceRefresh = false
        if (!shouldRefreshAgain) {
            refreshJob = null
            return null
        }
        shouldForceRefresh
    }

    private suspend fun reloadDownloadedSongs(context: Context, forceRefresh: Boolean = false) {
        _isRefreshing.value = true
        try {
            val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = forceRefresh
            )
            val songs = snapshot.audioEntries
                .mapNotNull { storedAudio ->
                    runCatching {
                        buildDownloadedSong(
                            context = context,
                            storedAudio = storedAudio,
                            snapshot = snapshot,
                            allowSlowLocalInspection = false
                        )
                    }.onFailure { error ->
                        NPLogger.w(TAG, "解析下载文件失败: ${storedAudio.name} - ${error.message}")
                    }.getOrNull()
                }
                .sortedByDescending { it.downloadTime }
            if (_downloadedSongs.value != songs) {
                publishDownloadedSongs(context, songs, persistCatalog = true)
            }
        } catch (error: Exception) {
            NPLogger.e(TAG, "扫描已下载文件失败: ${error.message}", error)
        } finally {
            _isRefreshing.value = false
        }
    }

    fun syncDownloadedSongMetadata(song: SongItem) {
        scope.launch {
            val context = AppContainer.applicationContext
            val storedAudio = resolveStoredAudio(context, song) ?: return@launch

            persistDownloadedMetadata(context, storedAudio, song)

            val currentSongs = _downloadedSongs.value
            var updated = false
            var shouldPublishCatalog = false
            val refreshedSongs = currentSongs.map { downloaded ->
                if (downloaded.filePath == storedAudio.reference) {
                    updated = true
                    buildDownloadedSong(
                        context = context,
                        storedAudio = storedAudio,
                        existingDownloadTime = downloaded.downloadTime,
                        allowSlowLocalInspection = false
                    ).also { refreshed ->
                        shouldPublishCatalog = shouldPublishDownloadedSongCatalogUpdate(
                            currentSong = downloaded,
                            updatedSong = refreshed
                        )
                    }
                } else {
                    downloaded
                }
            }

            if (updated) {
                scheduleDownloadedSongsCatalogPersist(context, refreshedSongs)
                if (shouldPublishCatalog) {
                    publishDownloadedSongs(context, refreshedSongs, persistCatalog = false)
                }
            } else {
                reloadDownloadedSongs(context)
            }
        }
    }

    private suspend fun buildDownloadedSong(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null,
        existingDownloadTime: Long? = null,
        loadLyricContents: Boolean = false,
        resolveLyricFallbacks: Boolean = false,
        allowSlowLocalInspection: Boolean = true
    ): DownloadedSong {
        val effectiveSnapshot = snapshot ?: ManagedDownloadStorage.buildDownloadLibrarySnapshot(context)
        val metadataEntry = effectiveSnapshot.metadataEntriesByAudioName[storedAudio.name]
        val snapshotMetadata = effectiveSnapshot.metadataByAudioName[storedAudio.name]
        val metadata = if (loadLyricContents || resolveLyricFallbacks) {
            readDownloadedMetadata(
                context = context,
                audio = storedAudio,
                metadataEntry = metadataEntry
            ) ?: snapshotMetadata
        } else {
            snapshotMetadata ?: readDownloadedMetadata(
                context = context,
                audio = storedAudio,
                metadataEntry = metadataEntry
            )
        }
        val (parsedArtist, parsedTitle) = parseDownloadedFileName(storedAudio.name)
        val cachedCoverReference = resolveAccessibleManagedReference(
            context = context,
            snapshot = effectiveSnapshot,
            metadata?.coverPath,
            metadata?.coverUrl,
            metadata?.originalCoverUrl
        )
            ?: findIndexedCoverReference(storedAudio, effectiveSnapshot)
        val indexedLyricReference = findIndexedLyricReference(
            audio = storedAudio,
            songId = metadata?.songId,
            translated = false,
            snapshot = effectiveSnapshot
        )
        val lyricReference = if (loadLyricContents) {
            resolveAccessibleManagedReference(
                context = context,
                snapshot = effectiveSnapshot,
                metadata?.lyricPath,
                indexedLyricReference
            )
        } else {
            null
        }
        val fileLyric = if (loadLyricContents) {
            lyricReference?.let { ManagedDownloadStorage.readText(context, it) }
        } else {
            null
        }
        val indexedLyric = if (
            loadLyricContents &&
            resolveLyricFallbacks &&
            fileLyric.isNullOrBlank() &&
            lyricReference != indexedLyricReference
        ) {
            findIndexedLyricText(
                context = context,
                audio = storedAudio,
                songId = metadata?.songId,
                translated = false,
                snapshot = effectiveSnapshot
            )
        } else {
            null
        }
        val indexedTranslatedLyricReference = findIndexedLyricReference(
            audio = storedAudio,
            songId = metadata?.songId,
            translated = true,
            snapshot = effectiveSnapshot
        )
        val translatedLyricReference = if (loadLyricContents) {
            resolveAccessibleManagedReference(
                context = context,
                snapshot = effectiveSnapshot,
                metadata?.translatedLyricPath,
                indexedTranslatedLyricReference
            )
        } else {
            null
        }
        val fileTranslatedLyric = if (loadLyricContents) {
            translatedLyricReference?.let { ManagedDownloadStorage.readText(context, it) }
        } else {
            null
        }
        val indexedTranslatedLyric = if (
            loadLyricContents &&
            resolveLyricFallbacks &&
            fileTranslatedLyric.isNullOrBlank() &&
            translatedLyricReference != indexedTranslatedLyricReference
        ) {
            findIndexedLyricText(
                context = context,
                audio = storedAudio,
                songId = metadata?.songId,
                translated = true,
                snapshot = effectiveSnapshot
            )
        } else {
            null
        }
        val needsLocalLyricFallback = loadLyricContents &&
            fileLyric.isNullOrBlank() &&
            metadata?.matchedLyric.isNullOrBlank() &&
            metadata?.originalLyric.isNullOrBlank() &&
            indexedLyric.isNullOrBlank()
        val localDetails by lazy(LazyThreadSafetyMode.NONE) {
            if (
                shouldInspectDownloadedAudioDetails(
                    allowSlowLocalInspection = allowSlowLocalInspection,
                    metadata = metadata,
                    coverReference = cachedCoverReference,
                    needsLocalLyricFallback = needsLocalLyricFallback
                )
            ) {
                inspectDownloadedAudioDetails(context, storedAudio)
            } else {
                null
            }
        }
        val coverReference = cachedCoverReference ?: localDetails?.coverUri
        val matchedLyric = if (loadLyricContents) {
            when {
                !fileLyric.isNullOrBlank() -> fileLyric
                !metadata?.matchedLyric.isNullOrBlank() -> metadata?.matchedLyric
                !metadata?.originalLyric.isNullOrBlank() -> metadata?.originalLyric
                else -> resolveDownloadedLyricContent(
                    fileLyric = null,
                    embeddedMatchedLyric = null,
                    embeddedOriginalLyric = null,
                    localLyricContent = localDetails?.lyricContent,
                    indexedLyricContent = indexedLyric
                )
            }
        } else {
            null
        }
        val matchedTranslatedLyric = if (loadLyricContents) {
            resolveDownloadedLyricContent(
                fileLyric = fileTranslatedLyric,
                embeddedMatchedLyric = metadata?.matchedTranslatedLyric,
                embeddedOriginalLyric = metadata?.originalTranslatedLyric,
                localLyricContent = null,
                indexedLyricContent = if (
                    fileTranslatedLyric.isNullOrBlank() &&
                    metadata?.matchedTranslatedLyric.isNullOrBlank() &&
                    metadata?.originalTranslatedLyric.isNullOrBlank()
                ) {
                    indexedTranslatedLyric
                } else {
                    null
                }
            )
        } else {
            null
        }
        val originalLyric = metadata?.originalLyric
        val originalTranslatedLyric = metadata?.originalTranslatedLyric

        return DownloadedSong(
            id = metadata?.songId ?: storedAudio.reference.hashCode().toLong(),
            name = metadata?.name?.takeIf(String::isNotBlank) ?: localDetails?.title?.takeIf(String::isNotBlank) ?: parsedTitle,
            artist = metadata?.artist?.takeIf(String::isNotBlank) ?: localDetails?.artist?.takeIf(String::isNotBlank) ?: parsedArtist,
            album = (if (metadata == null) localDetails?.album?.takeIf(String::isNotBlank) else null)
                ?: context.getString(R.string.local_files),
            filePath = storedAudio.reference,
            fileSize = storedAudio.sizeBytes,
            downloadTime = existingDownloadTime ?: storedAudio.lastModifiedMs,
            coverPath = coverReference,
            coverUrl = metadata?.coverUrl,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = metadata?.matchedLyricSource,
            matchedSongId = metadata?.matchedSongId,
            userLyricOffsetMs = metadata?.userLyricOffsetMs ?: 0L,
            customCoverUrl = metadata?.customCoverUrl,
            customName = metadata?.customName,
            customArtist = metadata?.customArtist,
            originalName = metadata?.originalName ?: localDetails?.originalTitle,
            originalArtist = metadata?.originalArtist ?: localDetails?.originalArtist,
            originalCoverUrl = metadata?.originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            mediaUri = storedAudio.playbackUri,
            durationMs = metadata?.durationMs?.takeIf { it > 0L } ?: localDetails?.durationMs ?: 0L
        )
    }

    private fun parseDownloadedFileName(fileName: String): Pair<String, String> {
        val nameWithoutExt = fileName.substringBeforeLast('.', fileName)
        val parts = nameWithoutExt.split(" - ", limit = 2)
        return if (parts.size >= 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            "" to nameWithoutExt
        }
    }

    private suspend fun persistDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        song: SongItem
    ) {
        val identity = song.identity()
        val coverReference = ManagedDownloadStorage.findCoverReference(context, audio)
        val lyricPath = ManagedDownloadStorage.findLyricLocation(
            context = context,
            songId = song.id,
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            translated = false
        )
        val translatedLyricPath = ManagedDownloadStorage.findLyricLocation(
            context = context,
            songId = song.id,
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            translated = true
        )
        val payload = JSONObject().apply {
            put("stableKey", identity.stableKey())
            put("songId", song.id)
            put("identityAlbum", identity.album)
            put("name", song.name)
            put("artist", song.artist)
            put("coverUrl", song.coverUrl)
            put("matchedLyric", song.matchedLyric)
            put("matchedTranslatedLyric", song.matchedTranslatedLyric)
            put("matchedLyricSource", song.matchedLyricSource?.name)
            put("matchedSongId", song.matchedSongId)
            put("userLyricOffsetMs", song.userLyricOffsetMs)
            put("customCoverUrl", song.customCoverUrl)
            put("customName", song.customName)
            put("customArtist", song.customArtist)
            put("originalName", song.originalName)
            put("originalArtist", song.originalArtist)
            put("originalCoverUrl", song.originalCoverUrl)
            put("originalLyric", song.originalLyric)
            put("originalTranslatedLyric", song.originalTranslatedLyric)
            put("mediaUri", identity.mediaUri ?: song.mediaUri)
            put("channelId", song.channelId)
            put("audioId", song.audioId)
            put("subAudioId", song.subAudioId)
            put("playlistContextId", song.playlistContextId)
            put("coverPath", coverReference)
            put("lyricPath", lyricPath)
            put("translatedLyricPath", translatedLyricPath)
            put("durationMs", song.durationMs)
        }

        runCatching {
            ManagedDownloadStorage.saveMetadata(context, audio, payload.toString())
            NPLogger.d(
                TAG,
                "保存下载 metadata: file=${audio.name}, stableKey=${identity.stableKey()}, lyricPath=$lyricPath, translatedLyricPath=$translatedLyricPath, coverPath=$coverReference"
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载元数据失败: ${audio.name} - ${error.message}")
        }
    }

    private suspend fun readDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        metadataEntry: ManagedDownloadStorage.StoredEntry? = null
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        val resolvedMetadataEntry = metadataEntry
            ?: ManagedDownloadStorage.findMetadataForAudio(context, audio)
            ?: return null
        val raw = ManagedDownloadStorage.readText(context, resolvedMetadataEntry.reference) ?: return null

        return runCatching {
            val root = JSONObject(raw)
            ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = root.optString("stableKey").takeIf(String::isNotBlank),
                songId = root.optLong("songId").takeIf { it > 0L },
                identityAlbum = root.optString("identityAlbum").takeIf(String::isNotBlank),
                name = root.optString("name").takeIf(String::isNotBlank),
                artist = root.optString("artist").takeIf(String::isNotBlank),
                coverUrl = root.optString("coverUrl").takeIf(String::isNotBlank),
                matchedLyric = root.optString("matchedLyric").takeIf(String::isNotBlank),
                matchedTranslatedLyric = root.optString("matchedTranslatedLyric").takeIf(String::isNotBlank),
                matchedLyricSource = root.optString("matchedLyricSource").takeIf(String::isNotBlank),
                matchedSongId = root.optString("matchedSongId").takeIf(String::isNotBlank),
                userLyricOffsetMs = root.optLong("userLyricOffsetMs"),
                customCoverUrl = root.optString("customCoverUrl").takeIf(String::isNotBlank),
                customName = root.optString("customName").takeIf(String::isNotBlank),
                customArtist = root.optString("customArtist").takeIf(String::isNotBlank),
                originalName = root.optString("originalName").takeIf(String::isNotBlank),
                originalArtist = root.optString("originalArtist").takeIf(String::isNotBlank),
                originalCoverUrl = root.optString("originalCoverUrl").takeIf(String::isNotBlank),
                originalLyric = root.optString("originalLyric").takeIf(String::isNotBlank),
                originalTranslatedLyric = root.optString("originalTranslatedLyric").takeIf(String::isNotBlank),
                coverPath = root.optString("coverPath").takeIf(String::isNotBlank),
                lyricPath = root.optString("lyricPath").takeIf(String::isNotBlank),
                translatedLyricPath = root.optString("translatedLyricPath").takeIf(String::isNotBlank),
                mediaUri = root.optString("mediaUri").takeIf(String::isNotBlank),
                channelId = root.optString("channelId").takeIf(String::isNotBlank),
                audioId = root.optString("audioId").takeIf(String::isNotBlank),
                subAudioId = root.optString("subAudioId").takeIf(String::isNotBlank),
                durationMs = root.optLong("durationMs")
            )
        }.getOrElse { error ->
            NPLogger.w(TAG, "解析下载元数据失败: ${audio.name} - ${error.message}")
            null
        }
    }

    private fun findIndexedLyricReference(
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val candidates = ManagedDownloadStorage.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            translated = translated
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            snapshot.lyricEntriesByName[candidate]?.reference
        }
    }

    private suspend fun findIndexedLyricText(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val reference = findIndexedLyricReference(
            audio = audio,
            songId = songId,
            translated = translated,
            snapshot = snapshot
        ) ?: return null
        return ManagedDownloadStorage.readText(context, reference)
    }

    private fun findIndexedCoverReference(
        audio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return candidateManagedDownloadBaseNames(audio.nameWithoutExtension)
            .firstNotNullOfOrNull { baseName ->
                sequenceOf("jpg", "jpeg", "png", "webp").firstNotNullOfOrNull { extension ->
                    snapshot.coverEntriesByName["$baseName.$extension"]?.reference
                }
            }
    }

    fun deleteDownloadedSong(context: Context, song: DownloadedSong) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val storedAudio = resolveStoredAudio(appContext, song.filePath)
                val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(appContext)
                val baseNames = candidateBaseNames(song, storedAudio?.nameWithoutExtension)
                val metadataReference = storedAudio?.let {
                    ManagedDownloadStorage.findMetadataForAudio(appContext, it)?.reference
                }
                val metadata = storedAudio?.let {
                    readDownloadedMetadata(appContext, it)
                }
                val currentAudioName = storedAudio?.name
                val lyricReferences = buildList {
                    metadata?.lyricPath?.let(::add)
                    metadata?.translatedLyricPath?.let(::add)
                    if (isEmpty()) {
                        ManagedDownloadStorage.findLyricLocation(
                            context = appContext,
                            songId = metadata?.songId ?: song.id,
                            candidateBaseNames = baseNames,
                            translated = false
                        )?.let(::add)
                        ManagedDownloadStorage.findLyricLocation(
                            context = appContext,
                            songId = metadata?.songId ?: song.id,
                            candidateBaseNames = baseNames,
                            translated = true
                        )?.let(::add)
                    }
                }

                storedAudio?.let {
                    ManagedDownloadStorage.deleteReference(appContext, it.reference)
                } ?: ManagedDownloadStorage.deleteReference(appContext, song.filePath)

                listOfNotNull(metadata?.coverPath, song.coverPath, metadataReference)
                    .plus(lyricReferences)
                    .distinct()
                    .forEach { reference ->
                        if (metadataReference != null && reference == metadataReference) {
                            NPLogger.d(TAG, "删除下载关联文件: song=${song.name}, reference=$reference")
                            ManagedDownloadStorage.deleteReference(appContext, reference)
                        } else if (isReferenceOwnedByOtherDownload(snapshot, currentAudioName, reference)) {
                            NPLogger.w(TAG, "跳过删除共享关联文件: song=${song.name}, reference=$reference")
                        } else {
                            NPLogger.d(TAG, "删除下载关联文件: song=${song.name}, reference=$reference")
                            ManagedDownloadStorage.deleteReference(appContext, reference)
                        }
                    }

                scanLocalFiles(appContext, forceRefresh = true)
                NPLogger.d(TAG, "删除下载文件完成: ${song.name}")
            } catch (error: Exception) {
                NPLogger.e(TAG, "删除下载文件失败: ${error.message}", error)
            }
        }
    }

    fun playDownloadedSong(context: Context, song: DownloadedSong) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                if (!ManagedDownloadStorage.exists(appContext, song.filePath)) {
                    NPLogger.w(TAG, "下载文件不存在: ${song.filePath}")
                    reloadDownloadedSongs(appContext, forceRefresh = true)
                    return@launch
                }

                val storedAudio = resolveStoredAudio(appContext, song.filePath)
                val playbackUri = storedAudio?.playbackUri
                    ?: ManagedDownloadStorage.toPlayableUri(song.filePath)
                    ?: song.filePath
                val quickSong = song.toPlaybackSongItem(
                    playbackUri = playbackUri,
                    storedAudio = storedAudio,
                    resolvedDurationMs = song.durationMs
                )
                withContext(Dispatchers.Main.immediate) {
                    PlayerManager.playPlaylist(listOf(quickSong), 0)
                }

                val refreshedSong = storedAudio?.let {
                    val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(appContext)
                    buildDownloadedSong(
                        context = appContext,
                        storedAudio = it,
                        snapshot = snapshot,
                        existingDownloadTime = song.downloadTime,
                        loadLyricContents = true,
                        resolveLyricFallbacks = true,
                        allowSlowLocalInspection = false
                    )
                } ?: song
                val hydratedDurationMs = refreshedSong.durationMs
                    .takeIf { it > 0L }
                    ?: quickSong.durationMs.takeIf { it > 0L }
                    ?: resolveAudioDuration(appContext, playbackUri)
                val hydratedSong = refreshedSong.toPlaybackSongItem(
                    playbackUri = playbackUri,
                    storedAudio = storedAudio,
                    resolvedDurationMs = hydratedDurationMs
                )
                if (hydratedSong != quickSong) {
                    delay(resolveDownloadedPlaybackHydrationDelayMs(quickSong, hydratedSong))
                    if (PlayerManager.currentSongFlow.value?.stableKey() != quickSong.stableKey()) {
                        return@launch
                    }
                    PlayerManager.hydrateSongMetadata(
                        originalSong = quickSong,
                        updatedSong = hydratedSong
                    )
                }
            } catch (error: Exception) {
                NPLogger.e(TAG, "播放下载文件失败: ${error.message}", error)
            }
        }
    }

    private fun DownloadedSong.toPlaybackSongItem(
        playbackUri: String,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        resolvedDurationMs: Long
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = resolvedDurationMs.coerceAtLeast(0L),
            coverUrl = coverPath ?: coverUrl,
            mediaUri = playbackUri,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = matchedLyricSource?.let {
                runCatching { MusicPlatform.valueOf(it) }.getOrNull()
            },
            matchedSongId = matchedSongId,
            userLyricOffsetMs = userLyricOffsetMs,
            customCoverUrl = customCoverUrl,
            customName = customName,
            customArtist = customArtist,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            localFileName = storedAudio?.name ?: filePath.substringAfterLast('/').takeIf(String::isNotBlank),
            localFilePath = storedAudio?.localFilePath ?: filePath.takeIf { it.startsWith("/") }
        )
    }

    private fun resolveAudioDuration(context: Context, location: String): Long {
        val uri = when {
            location.startsWith("/") -> Uri.fromFile(File(location))
            else -> location.toUri()
        }
        val quickDuration = runCatching {
            LocalMediaSupport.inspectQuick(context, uri).durationMs
        }.getOrNull()
        if (quickDuration != null && quickDuration > 0L) {
            return quickDuration
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrElse { error ->
            NPLogger.w(TAG, "读取下载音频时长失败: ${error.message}")
            0L
        }
    }

    fun hasDownloadedSongCached(song: SongItem): Boolean {
        return downloadedSongPresenceIndex.contains(song)
    }

    private fun downloadedSongsCacheFile(context: Context): File {
        return File(context.filesDir, DOWNLOAD_CATALOG_CACHE_FILE_NAME)
    }

    private fun restorePersistedDownloadedSongs(context: Context): Boolean {
        val rawPayload = runCatching {
            downloadedSongsCacheFile(context).takeIf(File::exists)?.readText(Charsets.UTF_8)
        }.onFailure {
            NPLogger.w(TAG, "读取下载歌曲目录缓存失败: ${it.message}")
        }.getOrNull() ?: return false

        val restoredSongs = runCatching {
            deserializeDownloadedSongsCatalog(
                raw = rawPayload,
                expectedCacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            )
        }.onFailure {
            NPLogger.w(TAG, "解析下载歌曲目录缓存失败: ${it.message}")
        }.getOrNull() ?: return false

        publishDownloadedSongs(context, restoredSongs, persistCatalog = false)
        return true
    }

    private fun persistDownloadedSongsCatalog(
        context: Context,
        songs: List<DownloadedSong>
    ) {
        runCatching {
            downloadedSongsCacheFile(context).writeText(
                serializeDownloadedSongsCatalog(
                    cacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(context),
                    songs = songs
                ),
                Charsets.UTF_8
            )
        }.onFailure {
            NPLogger.w(TAG, "写入下载歌曲目录缓存失败: ${it.message}")
        }
    }

    fun startDownload(context: Context, song: SongItem) {
        val appContext = context.applicationContext
        scope.launch {
            val songKey = song.stableKey()
            try {
                if (shouldSkipDownload(appContext, song) || !addDownloadTask(appContext, song)) {
                    return@launch
                }

                val existingAudio = ManagedDownloadStorage.findDownloadedAudio(appContext, song)
                if (existingAudio != null) {
                    updateTaskStatus(songKey, DownloadStatus.COMPLETED)
                    scanLocalFiles(appContext, forceRefresh = true)
                    return@launch
                }

                while (_isSingleDownloading.value) {
                    if (isSongCancelled(songKey)) {
                        throw CancellationException("Download cancelled before start")
                    }
                    delay(100)
                }

                if (isSongCancelled(songKey)) {
                    throw CancellationException("Download cancelled before start")
                }

                _isSingleDownloading.value = true
                try {
                    AudioDownloadManager.downloadSong(appContext, song)
                    finalizeCompletedDownload(
                        context = appContext,
                        song = song,
                        refreshCatalog = true
                    )
                } finally {
                    _isSingleDownloading.value = false
                }
            } catch (_: CancellationException) {
                clearSongCancelled(songKey)
                updateTaskStatus(songKey, DownloadStatus.CANCELLED)
                _isSingleDownloading.value = false
            } catch (error: Exception) {
                NPLogger.e(TAG, "下载失败: ${song.name} - ${error.message}", error)
                updateTaskStatus(songKey, DownloadStatus.FAILED)
                _isSingleDownloading.value = false
            }
        }
    }

    fun startBatchDownload(context: Context, songs: List<SongItem>) {
        if (songs.isEmpty()) return

        val appContext = context.applicationContext
        scope.launch {
            try {
                val pendingSongs = songs
                    .filterNot { shouldSkipDownload(appContext, it) }
                    .filter { addDownloadTask(appContext, it) }

                if (pendingSongs.isEmpty()) {
                    NPLogger.d(TAG, "没有新的批量下载任务")
                    return@launch
                }

                AudioDownloadManager.downloadPlaylist(
                    context = appContext,
                    songs = pendingSongs,
                    onSongCompleted = { completedSong ->
                        finalizeCompletedDownload(
                            context = appContext,
                            song = completedSong,
                            refreshCatalog = false
                        )
                    },
                    onSongFailed = { failedSong, _ ->
                        updateTaskStatus(failedSong.stableKey(), DownloadStatus.FAILED)
                    },
                    onSongCancelled = { cancelledSong ->
                        clearSongCancelled(cancelledSong.stableKey())
                        updateTaskStatus(cancelledSong.stableKey(), DownloadStatus.CANCELLED)
                    }
                )
            } catch (_: CancellationException) {
                val cancelledKeys = songs.map { it.stableKey() }.toSet()
                _downloadTasks.value = _downloadTasks.value.filterNot { task ->
                    task.song.stableKey() in cancelledKeys && task.status != DownloadStatus.COMPLETED
                }
                cancelledKeys.forEach(::clearSongCancelled)
            } catch (error: Exception) {
                NPLogger.e(TAG, "批量下载失败: ${error.message}", error)
                songs.forEach { song ->
                    updateTaskStatus(song.stableKey(), DownloadStatus.FAILED)
                }
            }
        }
    }

    private fun addDownloadTask(context: Context, song: SongItem): Boolean {
        if (shouldSkipDownload(context, song)) {
            return false
        }

        val songKey = song.stableKey()
        clearSongCancelled(songKey)
        val existingTask = _downloadTasks.value.find { it.song.stableKey() == songKey }
        if (existingTask != null) {
            return when (existingTask.status) {
                DownloadStatus.COMPLETED,
                DownloadStatus.CANCELLED,
                DownloadStatus.FAILED -> {
                    removeDownloadTask(songKey)
                    true
                }
                DownloadStatus.DOWNLOADING -> false
            }
        }

        _downloadTasks.value += DownloadTask(
            song = song,
            progress = null,
            status = DownloadStatus.DOWNLOADING
        )
        return true
    }

    private fun shouldSkipDownload(context: Context, song: SongItem): Boolean {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return false
        }
        NPLogger.d(TAG, "跳过本地歌曲下载: ${song.name}")
        return true
    }

    fun updateTaskStatus(songKey: String, status: DownloadStatus) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.song.stableKey() == songKey) {
                task.copy(status = status, progress = null)
            } else {
                task
            }
        }
    }

    fun removeDownloadTask(songKey: String) {
        _downloadTasks.value = _downloadTasks.value.filter { it.song.stableKey() != songKey }
    }

    private fun markSongCancelled(songKey: String) {
        cancelledSongKeys.add(songKey)
    }

    fun clearSongCancelled(songKey: String) {
        cancelledSongKeys.remove(songKey)
    }

    private fun cancelAllActiveTasks() {
        _downloadTasks.value = _downloadTasks.value.filter { it.status != DownloadStatus.DOWNLOADING }
    }

    fun cancelDownloadTask(songKey: String) {
        val task = _downloadTasks.value.find { it.song.stableKey() == songKey } ?: return
        if (task.status != DownloadStatus.DOWNLOADING) return

        markSongCancelled(songKey)
        updateTaskStatus(songKey, DownloadStatus.CANCELLED)
    }

    fun isSongCancelled(songKey: String): Boolean {
        return cancelledSongKeys.contains(songKey)
    }

    fun resumeDownloadTask(context: Context, songKey: String) {
        val task = _downloadTasks.value.find { it.song.stableKey() == songKey } ?: return
        if (task.status != DownloadStatus.CANCELLED || isSongCancelled(songKey)) {
            return
        }

        removeDownloadTask(songKey)
        startDownload(context, task.song)
    }

    fun clearCompletedTasks() {
        val downloadingTasks = _downloadTasks.value.filter { it.status == DownloadStatus.DOWNLOADING }
        if (downloadingTasks.isNotEmpty()) {
            AudioDownloadManager.cancelDownload()
            downloadingTasks.forEach { task ->
                cancelDownloadTask(task.song.stableKey())
            }
        }

        _downloadTasks.value = emptyList()
        cancelledSongKeys.clear()

        scope.launch {
            delay(100)
            AudioDownloadManager.resetCancelFlag()
        }
    }

    private fun candidateBaseNames(
        song: DownloadedSong,
        actualAudioBaseName: String? = null
    ): List<String> {
        val baseNames = linkedSetOf<String>()
        actualAudioBaseName?.takeIf { it.isNotBlank() }?.let(baseNames::add)
        baseNames += sanitizeManagedDownloadFileName("${song.displayArtist()} - ${song.displayName()}")
        baseNames += sanitizeManagedDownloadFileName("${song.artist} - ${song.name}")

        val originalName = song.originalName?.takeIf { it.isNotBlank() } ?: song.name
        val originalArtist = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
        baseNames += sanitizeManagedDownloadFileName("$originalArtist - $originalName")
        return baseNames.toList()
    }

    private suspend fun resolveStoredAudio(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? {
        resolveStoredAudio(context, resolveSongLocation(song))?.let { return it }
        return ManagedDownloadStorage.findDownloadedAudio(context, song)
    }

    private suspend fun resolveStoredAudio(
        context: Context,
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val normalized = reference?.takeIf { it.isNotBlank() } ?: return null
        return ManagedDownloadStorage.queryStoredEntry(context, normalized)
    }

    private suspend fun resolveAccessibleManagedReference(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        vararg references: String?
    ): String? {
        return references.firstNotNullOfOrNull { reference ->
            val candidate = reference?.takeIf(::isResolvableLocalReference) ?: return@firstNotNullOfOrNull null
            candidate.takeIf {
                candidate in snapshot.knownReferences || ManagedDownloadStorage.exists(context, candidate)
            }
        }
    }

    private fun resolveSongLocation(song: SongItem): String? {
        song.localFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val mediaUri = song.mediaUri?.takeIf { it.isNotBlank() } ?: return null
        return when {
            mediaUri.startsWith("/") -> mediaUri
            mediaUri.startsWith("file://") -> mediaUri
            mediaUri.startsWith("content://") -> mediaUri
            else -> null
        }
    }

    private fun inspectDownloadedAudioDetails(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) = runCatching {
        LocalMediaSupport.inspect(context, storedAudio.playbackUri.toUri())
    }.onFailure { error ->
        NPLogger.w(TAG, "读取已下载音频标签失败: ${storedAudio.name} - ${error.message}")
    }.getOrNull()

    private fun isReferenceOwnedByOtherDownload(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        currentAudioName: String?,
        reference: String
    ): Boolean {
        return snapshot.metadataByAudioName.any { (audioName, metadata) ->
            audioName != currentAudioName && listOfNotNull(
                metadata.coverPath,
                metadata.lyricPath,
                metadata.translatedLyricPath
            ).contains(reference)
        }
    }
}

internal fun shouldRunInitialDownloadScan(
    snapshotReady: Boolean,
    catalogReady: Boolean
): Boolean {
    return !snapshotReady || !catalogReady
}

internal fun shouldUseImmediateDownloadedPlaybackHydration(
    originalSong: SongItem,
    hydratedSong: SongItem
): Boolean {
    return originalSong.name != hydratedSong.name ||
        originalSong.artist != hydratedSong.artist ||
        originalSong.durationMs != hydratedSong.durationMs ||
        originalSong.coverUrl != hydratedSong.coverUrl ||
        originalSong.customCoverUrl != hydratedSong.customCoverUrl ||
        originalSong.customName != hydratedSong.customName ||
        originalSong.customArtist != hydratedSong.customArtist ||
        originalSong.mediaUri != hydratedSong.mediaUri ||
        originalSong.localFilePath != hydratedSong.localFilePath ||
        originalSong.localFileName != hydratedSong.localFileName
}

internal fun resolveDownloadedPlaybackHydrationDelayMs(
    originalSong: SongItem,
    hydratedSong: SongItem
): Long {
    return if (shouldUseImmediateDownloadedPlaybackHydration(originalSong, hydratedSong)) {
        GlobalDownloadManager.PLAYBACK_METADATA_HYDRATION_DELAY_MS
    } else {
        GlobalDownloadManager.LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS
    }
}

internal fun matchesDownloadedSong(
    song: SongItem,
    downloadedSong: DownloadedSong
): Boolean {
    val localCandidates = listOfNotNull(
        song.localFilePath?.takeIf(String::isNotBlank),
        song.mediaUri?.takeIf(String::isNotBlank)
    )
    if (localCandidates.any { candidate ->
            candidate == downloadedSong.filePath || candidate == downloadedSong.mediaUri
        }
    ) {
        return true
    }
    return song.id == downloadedSong.id &&
        song.name == downloadedSong.name &&
        song.artist == downloadedSong.artist
}

fun upsertDownloadedSongCatalog(
    currentSongs: List<DownloadedSong>,
    updatedSong: DownloadedSong
): List<DownloadedSong> {
    return currentSongs
        .filterNot { it.filePath == updatedSong.filePath }
        .plus(updatedSong)
        .sortedByDescending { it.downloadTime }
}

internal fun shouldPublishDownloadedSongCatalogUpdate(
    currentSong: DownloadedSong,
    updatedSong: DownloadedSong
): Boolean {
    return currentSong.listPresentationKey() != updatedSong.listPresentationKey()
}

private fun DownloadedSong.listPresentationKey(): String {
    return buildString {
        append(id)
        append('|')
        append(filePath)
        append('|')
        append(displayName())
        append('|')
        append(displayArtist())
        append('|')
        append(fileSize)
        append('|')
        append(downloadTime)
        append('|')
        append(customCoverUrl.orEmpty())
        append('|')
        append(coverPath.orEmpty())
        append('|')
        append(coverUrl.orEmpty())
    }
}

internal fun shouldInspectDownloadedAudioDetails(
    allowSlowLocalInspection: Boolean,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    coverReference: String?,
    needsLocalLyricFallback: Boolean
): Boolean {
    if (!allowSlowLocalInspection) return false
    if (needsLocalLyricFallback) return true
    return metadata == null ||
        metadata.name.isNullOrBlank() ||
        metadata.artist.isNullOrBlank() ||
        metadata.originalName.isNullOrBlank() ||
        metadata.originalArtist.isNullOrBlank() ||
        metadata.durationMs <= 0L ||
        coverReference.isNullOrBlank()
}

internal fun resolveDownloadedLyricContent(
    fileLyric: String?,
    embeddedMatchedLyric: String?,
    embeddedOriginalLyric: String?,
    localLyricContent: String?,
    indexedLyricContent: String?
): String? {
    return fileLyric?.takeIf(String::isNotBlank)
        ?: embeddedMatchedLyric?.takeIf(String::isNotBlank)
        ?: embeddedOriginalLyric?.takeIf(String::isNotBlank)
        ?: localLyricContent?.takeIf(String::isNotBlank)
        ?: indexedLyricContent?.takeIf(String::isNotBlank)
}

private fun isResolvableLocalReference(reference: String): Boolean {
    return reference.startsWith("/") ||
        reference.startsWith("content://") ||
        reference.startsWith("file://")
}

internal fun serializeDownloadedSongsCatalog(
    cacheKey: String,
    songs: List<DownloadedSong>
): String {
    return JSONObject().apply {
        put("cacheKey", cacheKey)
        put("songs", JSONArray().apply {
            songs.forEach { song ->
                put(
                    JSONObject().apply {
                        put("id", song.id)
                        put("name", song.name)
                        put("artist", song.artist)
                        put("album", song.album)
                        put("filePath", song.filePath)
                        put("fileSize", song.fileSize)
                        put("downloadTime", song.downloadTime)
                        put("coverPath", song.coverPath)
                        put("coverUrl", song.coverUrl)
                        put("matchedLyricSource", song.matchedLyricSource)
                        put("matchedSongId", song.matchedSongId)
                        put("userLyricOffsetMs", song.userLyricOffsetMs)
                        put("customCoverUrl", song.customCoverUrl)
                        put("customName", song.customName)
                        put("customArtist", song.customArtist)
                        put("originalName", song.originalName)
                        put("originalArtist", song.originalArtist)
                        put("originalCoverUrl", song.originalCoverUrl)
                        put("mediaUri", song.mediaUri)
                        put("durationMs", song.durationMs)
                    }
                )
            }
        })
    }.toString()
}

internal fun deserializeDownloadedSongsCatalog(
    raw: String,
    expectedCacheKey: String
): List<DownloadedSong>? {
    val root = JSONObject(raw)
    if (root.optString("cacheKey") != expectedCacheKey) {
        return null
    }
    val songs = root.optJSONArray("songs") ?: return emptyList()
    return buildList(songs.length()) {
        for (index in 0 until songs.length()) {
            val item = songs.optJSONObject(index) ?: continue
            add(
                DownloadedSong(
                    id = item.optLong("id"),
                    name = item.optString("name"),
                    artist = item.optString("artist"),
                    album = item.optString("album"),
                    filePath = item.optString("filePath"),
                    fileSize = item.optLong("fileSize"),
                    downloadTime = item.optLong("downloadTime"),
                    coverPath = item.optString("coverPath").takeIf(String::isNotBlank),
                    coverUrl = item.optString("coverUrl").takeIf(String::isNotBlank),
                    matchedLyric = item.optString("matchedLyric").takeIf(String::isNotBlank),
                    matchedTranslatedLyric = item.optString("matchedTranslatedLyric").takeIf(String::isNotBlank),
                    matchedLyricSource = item.optString("matchedLyricSource").takeIf(String::isNotBlank),
                    matchedSongId = item.optString("matchedSongId").takeIf(String::isNotBlank),
                    userLyricOffsetMs = item.optLong("userLyricOffsetMs"),
                    customCoverUrl = item.optString("customCoverUrl").takeIf(String::isNotBlank),
                    customName = item.optString("customName").takeIf(String::isNotBlank),
                    customArtist = item.optString("customArtist").takeIf(String::isNotBlank),
                    originalName = item.optString("originalName").takeIf(String::isNotBlank),
                    originalArtist = item.optString("originalArtist").takeIf(String::isNotBlank),
                    originalCoverUrl = item.optString("originalCoverUrl").takeIf(String::isNotBlank),
                    originalLyric = item.optString("originalLyric").takeIf(String::isNotBlank),
                    originalTranslatedLyric = item.optString("originalTranslatedLyric").takeIf(String::isNotBlank),
                    mediaUri = item.optString("mediaUri").takeIf(String::isNotBlank),
                    durationMs = item.optLong("durationMs")
                )
            )
        }
    }
}

data class DownloadedSong(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val fileSize: Long,
    val downloadTime: Long,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val matchedLyricSource: String? = null,
    val matchedSongId: String? = null,
    val userLyricOffsetMs: Long = 0L,
    val customCoverUrl: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val originalCoverUrl: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val mediaUri: String? = null,
    val durationMs: Long = 0L
) {
    fun displayName(): String = customName ?: name
    fun displayArtist(): String = customArtist ?: artist
}

data class DownloadTask(
    val song: SongItem,
    val progress: AudioDownloadManager.DownloadProgress?,
    val status: DownloadStatus
)

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}
