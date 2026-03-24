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
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.text.Normalizer
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
import moe.ouom.neriplayer.data.local.media.LocalMediaDetails
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject

/**
 * 全局下载管理器，统一维护下载任务和本地下载列表。
 */
object GlobalDownloadManager {
    private const val TAG = "GlobalDownloadManager"
    private const val INITIAL_SCAN_DELAY_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSingleDownloading = MutableStateFlow(false)
    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()

    private val _downloadedSongs = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloadedSongs: StateFlow<List<DownloadedSong>> = _downloadedSongs.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val cancelledSongKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private var refreshJob: Job? = null

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
            delay(INITIAL_SCAN_DELAY_MS)
            scanLocalFiles(appContext)
        }
    }

    private fun observeDownloadProgress(context: Context) {
        var lastProgressSongKey: String? = null

        scope.launch {
            AudioDownloadManager.progressFlow.collect { progress ->
                if (progress != null) {
                    lastProgressSongKey = progress.songKey
                    updateDownloadProgress(progress)
                    return@collect
                }

                val songKey = lastProgressSongKey
                lastProgressSongKey = null
                if (songKey.isNullOrBlank()) {
                    return@collect
                }

                val task = _downloadTasks.value.find { it.song.stableKey() == songKey }
                    ?: return@collect
                if (task.status != DownloadStatus.DOWNLOADING) {
                    return@collect
                }

                val storedAudio = resolveStoredAudio(context, task.song)
                if (storedAudio == null) {
                    NPLogger.w(TAG, "任务完成但未找到已下载文件: ${task.song.name}")
                    return@collect
                }

                persistDownloadedMetadata(
                    context = context,
                    audio = storedAudio,
                    song = task.song
                )
                updateTaskStatus(songKey, DownloadStatus.COMPLETED)
                scanLocalFiles(context)
            }
        }

        scope.launch {
            AudioDownloadManager.batchProgressFlow.collect { batchProgress ->
                if (batchProgress != null) {
                    updateBatchProgress(context, batchProgress)
                } else {
                    scanLocalFiles(context)
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
        batchProgress.currentProgress?.let(::updateDownloadProgress)

        if (batchProgress.completedSongs >= batchProgress.totalSongs) {
            scope.launch {
                delay(600)
                scanLocalFiles(context)
            }
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
                            snapshot = snapshot
                        )
                    }.onFailure { error ->
                        NPLogger.w(TAG, "解析下载文件失败: ${storedAudio.name} - ${error.message}")
                    }.getOrNull()
                }
                .sortedByDescending { it.downloadTime }
            _downloadedSongs.value = songs
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

            var updated = false
            val refreshedSongs = _downloadedSongs.value.map { downloaded ->
                if (downloaded.filePath == storedAudio.reference) {
                    updated = true
                    buildDownloadedSong(
                        context = context,
                        storedAudio = storedAudio,
                        existingDownloadTime = downloaded.downloadTime
                    )
                } else {
                    downloaded
                }
            }

            if (updated) {
                _downloadedSongs.value = refreshedSongs
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
        resolveLyricFallbacks: Boolean = false
    ): DownloadedSong {
        val effectiveSnapshot = snapshot ?: ManagedDownloadStorage.buildDownloadLibrarySnapshot(context)
        val metadata = readDownloadedMetadata(
            context = context,
            audio = storedAudio,
            metadataEntry = effectiveSnapshot.metadataEntriesByAudioName[storedAudio.name],
            includeLyrics = false
        )
        val (parsedArtist, parsedTitle) = parseDownloadedFileName(storedAudio.name)
        val coverReference = metadata?.coverPath
            ?.takeIf { it in effectiveSnapshot.knownReferences || ManagedDownloadStorage.exists(context, it) }
            ?: findIndexedCoverReference(storedAudio, effectiveSnapshot)
        val matchedLyric = metadata?.matchedLyric ?: if (resolveLyricFallbacks) {
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
        val matchedTranslatedLyric = metadata?.matchedTranslatedLyric ?: if (resolveLyricFallbacks) {
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

        return DownloadedSong(
            id = metadata?.songId ?: storedAudio.reference.hashCode().toLong(),
            name = metadata?.name?.takeIf(String::isNotBlank) ?: parsedTitle,
            artist = metadata?.artist?.takeIf(String::isNotBlank) ?: parsedArtist,
            album = context.getString(R.string.local_files),
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
            originalName = metadata?.originalName,
            originalArtist = metadata?.originalArtist,
            originalCoverUrl = metadata?.originalCoverUrl,
            originalLyric = metadata?.originalLyric,
            originalTranslatedLyric = metadata?.originalTranslatedLyric,
            mediaUri = storedAudio.playbackUri,
            durationMs = metadata?.durationMs ?: 0L
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
        val coverReference = ManagedDownloadStorage.findCoverReference(context, audio)
        val payload = JSONObject().apply {
            put("songId", song.id)
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
            put("mediaUri", song.mediaUri)
            put("coverPath", coverReference)
            put("durationMs", song.durationMs)
        }

        runCatching {
            ManagedDownloadStorage.saveMetadata(context, audio, payload.toString())
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载元数据失败: ${audio.name} - ${error.message}")
        }
    }

    private suspend fun readDownloadedMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        metadataEntry: ManagedDownloadStorage.StoredEntry? = null,
        includeLyrics: Boolean = false
    ): DownloadedSongMetadata? {
        val resolvedMetadataEntry = metadataEntry
            ?: ManagedDownloadStorage.findMetadataForAudio(context, audio)
            ?: return null
        val raw = ManagedDownloadStorage.readText(context, resolvedMetadataEntry.reference) ?: return null

        return runCatching {
            val root = JSONObject(raw)
            DownloadedSongMetadata(
                songId = root.optLong("songId").takeIf { it > 0L },
                name = root.optString("name").takeIf(String::isNotBlank),
                artist = root.optString("artist").takeIf(String::isNotBlank),
                coverUrl = root.optString("coverUrl").takeIf(String::isNotBlank),
                matchedLyric = root.optString("matchedLyric")
                    .takeIf { includeLyrics && it.isNotBlank() },
                matchedTranslatedLyric = root.optString("matchedTranslatedLyric")
                    .takeIf { includeLyrics && it.isNotBlank() },
                matchedLyricSource = root.optString("matchedLyricSource").takeIf(String::isNotBlank),
                matchedSongId = root.optString("matchedSongId").takeIf(String::isNotBlank),
                userLyricOffsetMs = root.optLong("userLyricOffsetMs"),
                customCoverUrl = root.optString("customCoverUrl").takeIf(String::isNotBlank),
                customName = root.optString("customName").takeIf(String::isNotBlank),
                customArtist = root.optString("customArtist").takeIf(String::isNotBlank),
                originalName = root.optString("originalName").takeIf(String::isNotBlank),
                originalArtist = root.optString("originalArtist").takeIf(String::isNotBlank),
                originalCoverUrl = root.optString("originalCoverUrl").takeIf(String::isNotBlank),
                originalLyric = root.optString("originalLyric")
                    .takeIf { includeLyrics && it.isNotBlank() },
                originalTranslatedLyric = root.optString("originalTranslatedLyric")
                    .takeIf { includeLyrics && it.isNotBlank() },
                coverPath = root.optString("coverPath").takeIf(String::isNotBlank),
                mediaUri = root.optString("mediaUri").takeIf(String::isNotBlank),
                durationMs = root.optLong("durationMs")
            )
        }.getOrElse { error ->
            NPLogger.w(TAG, "解析下载元数据失败: ${audio.name} - ${error.message}")
            null
        }
    }

    private suspend fun findIndexedLyricText(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val candidates = buildList {
            if (songId != null && songId > 0L) {
                add(if (translated) "${songId}_trans.lrc" else "${songId}.lrc")
            }
            candidateManagedDownloadBaseNames(audio.nameWithoutExtension).forEach { baseName ->
                add(if (translated) "${baseName}_trans.lrc" else "$baseName.lrc")
            }
        }
        val reference = candidates.firstNotNullOfOrNull { candidate ->
            snapshot.lyricEntriesByName[candidate]?.reference
        } ?: return null
        return ManagedDownloadStorage.readText(context, reference)
    }

    private fun findIndexedCoverReference(
        audio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return candidateManagedDownloadBaseNames(audio.nameWithoutExtension)
            .firstNotNullOfOrNull { baseName ->
                sequenceOf("jpg", "jpeg", "png", "webp")
                    .mapNotNull { extension ->
                        snapshot.coverEntriesByName["$baseName.$extension"]?.reference
                    }
                    .firstOrNull()
            }
    }

    fun deleteDownloadedSong(context: Context, song: DownloadedSong) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val storedAudio = resolveStoredAudio(appContext, song.filePath)
                val baseNames = candidateBaseNames(song, storedAudio?.nameWithoutExtension)
                val metadataReference = storedAudio?.let {
                    ManagedDownloadStorage.findMetadataForAudio(appContext, it)?.reference
                }
                val lyricReferences = buildList {
                    ManagedDownloadStorage.findLyricLocation(
                        context = appContext,
                        songId = song.id,
                        candidateBaseNames = baseNames,
                        translated = false
                    )?.let(::add)
                    ManagedDownloadStorage.findLyricLocation(
                        context = appContext,
                        songId = song.id,
                        candidateBaseNames = baseNames,
                        translated = true
                    )?.let(::add)
                }

                storedAudio?.let {
                    ManagedDownloadStorage.deleteReference(appContext, it.reference)
                } ?: ManagedDownloadStorage.deleteReference(appContext, song.filePath)

                listOfNotNull(song.coverPath, metadataReference)
                    .plus(lyricReferences)
                    .distinct()
                    .forEach { reference ->
                        ManagedDownloadStorage.deleteReference(appContext, reference)
                    }

                reloadDownloadedSongs(appContext)
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
                val durationMs = song.durationMs.takeIf { it > 0L }
                    ?: resolveAudioDuration(appContext, playbackUri)

                val songItem = SongItem(
                    id = song.id,
                    name = song.name,
                    artist = song.artist,
                    album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                    albumId = 0L,
                    durationMs = durationMs,
                    coverUrl = song.coverPath ?: song.coverUrl,
                    mediaUri = playbackUri,
                    matchedLyricSource = song.matchedLyricSource?.let {
                        runCatching { MusicPlatform.valueOf(it) }.getOrNull()
                    },
                    matchedSongId = song.matchedSongId,
                    userLyricOffsetMs = song.userLyricOffsetMs,
                    customCoverUrl = song.customCoverUrl,
                    customName = song.customName,
                    customArtist = song.customArtist,
                    originalName = song.originalName,
                    originalArtist = song.originalArtist,
                    originalCoverUrl = song.originalCoverUrl,
                    localFileName = storedAudio?.name,
                    localFilePath = storedAudio?.localFilePath
                )
                withContext(Dispatchers.Main.immediate) {
                    PlayerManager.playPlaylist(listOf(songItem), 0)
                }
            } catch (error: Exception) {
                NPLogger.e(TAG, "播放下载文件失败: ${error.message}", error)
            }
        }
    }

    private fun resolveAudioDuration(context: Context, location: String): Long {
        val uri = when {
            location.startsWith("/") -> Uri.fromFile(File(location))
            else -> location.toUri()
        }
        return runCatching {
            LocalMediaSupport.inspect(context, uri).durationMs
        }.getOrElse { error ->
            NPLogger.w(TAG, "读取下载音频时长失败: ${error.message}")
            0L
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

                if (ManagedDownloadStorage.hasDownloadedAudio(appContext, song)) {
                    updateTaskStatus(songKey, DownloadStatus.COMPLETED)
                    reloadDownloadedSongs(appContext)
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
                    updateTaskStatus(songKey, DownloadStatus.COMPLETED)
                    reloadDownloadedSongs(appContext)
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

                AudioDownloadManager.downloadPlaylist(appContext, pendingSongs)
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
        return ManagedDownloadStorage.findAudio(context, song)
    }

    private suspend fun resolveStoredAudio(
        context: Context,
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val normalized = reference?.takeIf { it.isNotBlank() } ?: return null
        return ManagedDownloadStorage.queryStoredEntry(context, normalized)
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
}

private data class DownloadedSongMetadata(
    val songId: Long? = null,
    val name: String? = null,
    val artist: String? = null,
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
    val coverPath: String? = null,
    val mediaUri: String? = null,
    val durationMs: Long = 0L
)

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
