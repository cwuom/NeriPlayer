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
 * Created: 2025/8/20
 */

import android.content.Context
import androidx.core.net.toUri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.LocalMediaSupport
import moe.ouom.neriplayer.data.LocalSongSupport
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File
import java.util.Collections
import org.json.JSONObject

/**
 * 全局下载管理器单例，用于管理下载任务和状态
 * 不依赖于特定的ViewModel或Composable的生命周期
 */
object GlobalDownloadManager {
    private const val DOWNLOAD_METADATA_SUFFIX = ".npmeta.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 单首下载锁，确保同时只有一个单首下载任务在执行
    private val _isSingleDownloading = MutableStateFlow(false)

    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()

    private val _downloadedSongs = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloadedSongs: StateFlow<List<DownloadedSong>> = _downloadedSongs.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val cancelledSongKeys = Collections.synchronizedSet(mutableSetOf<String>())
    
    private var initialized = false
    
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        
        // 观察下载进度
        observeDownloadProgress(context)
        
        // 初始化时扫描本地文件
        scanLocalFiles(context)
    }
    
    private fun observeDownloadProgress(context: Context) {
        var lastProgressSongKey: String? = null

        scope.launch {
            AudioDownloadManager.progressFlow.collect { progress ->
                progress?.let {
                    lastProgressSongKey = it.songKey
                    updateDownloadProgress(it)
                } ?: run {
                    // 下载完成，更新任务状态
                    // 使用最后记录的 songId 来标记任务为完成
                    lastProgressSongKey?.let { songKey ->
                        val task = _downloadTasks.value.find { it.song.stableKey() == songKey }
                        if (task != null && task.status == DownloadStatus.DOWNLOADING) {
                            // 验证文件是否真的存在，避免误标记
                            val filePath = AudioDownloadManager.getLocalFilePath(context, task.song)
                            if (filePath != null) {
                                val latestSong = task.song
                                persistDownloadedMetadata(
                                    audioFile = File(filePath),
                                    song = latestSong
                                )
                                NPLogger.d("GlobalDownloadManager", "任务完成，文件已存在: ${task.song.name}")
                                updateTaskStatus(songKey, DownloadStatus.COMPLETED)
                                scanLocalFiles(context)
                            } else {
                                NPLogger.w("GlobalDownloadManager", "任务标记完成但文件不存在: ${task.song.name}")
                            }
                        }
                    }
                    lastProgressSongKey = null
                }
            }
        }

        scope.launch {
            AudioDownloadManager.batchProgressFlow.collect { batchProgress ->
                batchProgress?.let {
                    updateBatchProgress(context, it)
                } ?: run {
                    // 批量下载完成或取消，刷新本地文件列表
                    // 不清空进度，让每个任务的进度通过 progressFlow 自然清空
                    scanLocalFiles(context)
                }
            }
        }

        // 监听下载取消状态
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
        // 只更新当前下载任务的进度
        // 任务完成状态由 progressFlow 的 null 值触发，不在这里处理
        if (batchProgress.currentProgress != null) {
            updateDownloadProgress(batchProgress.currentProgress)
        }

        // 如果批量下载完成，刷新本地文件列表
        if (batchProgress.completedSongs >= batchProgress.totalSongs) {
            scope.launch {
                delay(1000) // 延迟一下，确保文件写入完成
                scanLocalFiles(context)
            }
        }
    }

    /**
     * 扫描本地文件，更新已下载歌曲列表
     */
    fun scanLocalFiles(context: Context) {
        scope.launch {
            _isRefreshing.value = true
            try {
                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) 
                    ?: context.filesDir
                val downloadDir = File(baseDir, "NeriPlayer")
                
                if (!downloadDir.exists()) {
                    _downloadedSongs.value = emptyList()
                    return@launch
                }
                
                val songs = mutableListOf<DownloadedSong>()
                val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "webm")
                
                downloadDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension.lowercase() in audioExtensions) {
                        try {
                            buildDownloadedSong(
                                context = context,
                                downloadDir = downloadDir,
                                audioFile = file
                            ).let(songs::add)
                        } catch (e: Exception) {
                            NPLogger.w("GlobalDownloadManager", "解析文件失败: ${file.name} - ${e.message}")
                        }
                    }
                }
                
                _downloadedSongs.value = songs.sortedByDescending { it.downloadTime }
                
            } catch (e: Exception) {
                NPLogger.e("GlobalDownloadManager", "扫描本地文件失败: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun syncDownloadedSongMetadata(song: SongItem) {
        val audioFile = resolveLocalAudioFile(song) ?: return
        if (!audioFile.exists()) return
        if (!isManagedDownloadFile(audioFile)) return
        persistDownloadedMetadata(audioFile, song)
        _downloadedSongs.value = _downloadedSongs.value.map { downloaded ->
            if (downloaded.filePath == audioFile.absolutePath) {
                buildDownloadedSong(
                    context = null,
                    downloadDir = audioFile.parentFile ?: return@map downloaded,
                    audioFile = audioFile,
                    existingDownloadTime = downloaded.downloadTime
                )
            } else {
                downloaded
            }
        }
    }

    private fun buildDownloadedSong(
        context: Context?,
        downloadDir: File,
        audioFile: File,
        existingDownloadTime: Long? = null
    ): DownloadedSong {
        val metadata = readDownloadedMetadata(audioFile)
        val (parsedArtist, parsedTitle) = parseDownloadedFileName(audioFile)
        val coverPath = metadata?.coverPath
            ?.takeIf { File(it).exists() }
            ?: findCoverFile(downloadDir, audioFile)
        return DownloadedSong(
            id = metadata?.songId ?: audioFile.hashCode().toLong(),
            name = metadata?.name?.takeIf { it.isNotBlank() } ?: parsedTitle,
            artist = metadata?.artist?.takeIf { it.isNotBlank() } ?: parsedArtist,
            album = context?.getString(R.string.local_files) ?: LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            filePath = audioFile.absolutePath,
            fileSize = audioFile.length(),
            downloadTime = existingDownloadTime ?: audioFile.lastModified(),
            coverPath = coverPath,
            coverUrl = metadata?.coverUrl,
            matchedLyric = metadata?.matchedLyric ?: findLyricFile(downloadDir, audioFile, metadata?.songId),
            matchedTranslatedLyric = metadata?.matchedTranslatedLyric ?: findTranslatedLyricFile(downloadDir, audioFile, metadata?.songId),
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
            mediaUri = metadata?.mediaUri,
            durationMs = metadata?.durationMs ?: 0L
        )
    }

    private fun parseDownloadedFileName(audioFile: File): Pair<String, String> {
        val nameWithoutExt = audioFile.nameWithoutExtension
        val parts = nameWithoutExt.split(" - ", limit = 2)
        return if (parts.size >= 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            "" to nameWithoutExt
        }
    }

    private fun resolveLocalAudioFile(song: SongItem): File? {
        song.localFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(File::exists)
            ?.let { return it }
        val mediaUri = song.mediaUri?.takeIf { it.isNotBlank() } ?: return null
        val candidate = when {
            mediaUri.startsWith("/") -> File(mediaUri)
            mediaUri.startsWith("file://") -> File(mediaUri.toUri().path.orEmpty())
            else -> null
        }
        return candidate?.takeIf(File::exists)
    }

    private fun persistDownloadedMetadata(audioFile: File, song: SongItem) {
        val metadataFile = metadataFileForAudio(audioFile)
        val downloadDir = audioFile.parentFile ?: return
        val coverPath = findCoverFile(downloadDir, audioFile)
        val json = JSONObject().apply {
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
            put("coverPath", coverPath)
            put("durationMs", song.durationMs)
        }
        runCatching { metadataFile.writeText(json.toString(), Charsets.UTF_8) }
            .onFailure { NPLogger.w("GlobalDownloadManager", "写入下载元数据失败: ${metadataFile.name} - ${it.message}") }
    }

    private fun readDownloadedMetadata(audioFile: File): DownloadedSongMetadata? {
        val metadataFile = metadataFileForAudio(audioFile)
        if (!metadataFile.exists()) {
            return null
        }
        return runCatching {
            val root = JSONObject(metadataFile.readText(Charsets.UTF_8))
            DownloadedSongMetadata(
                songId = root.optLong("songId", 0L).takeIf { it > 0L },
                name = root.optString("name").takeIf { it.isNotBlank() },
                artist = root.optString("artist").takeIf { it.isNotBlank() },
                coverUrl = root.optString("coverUrl").takeIf { it.isNotBlank() },
                matchedLyric = root.optString("matchedLyric").takeIf { it.isNotBlank() },
                matchedTranslatedLyric = root.optString("matchedTranslatedLyric").takeIf { it.isNotBlank() },
                matchedLyricSource = root.optString("matchedLyricSource").takeIf { it.isNotBlank() },
                matchedSongId = root.optString("matchedSongId").takeIf { it.isNotBlank() },
                userLyricOffsetMs = root.optLong("userLyricOffsetMs", 0L),
                customCoverUrl = root.optString("customCoverUrl").takeIf { it.isNotBlank() },
                customName = root.optString("customName").takeIf { it.isNotBlank() },
                customArtist = root.optString("customArtist").takeIf { it.isNotBlank() },
                originalName = root.optString("originalName").takeIf { it.isNotBlank() },
                originalArtist = root.optString("originalArtist").takeIf { it.isNotBlank() },
                originalCoverUrl = root.optString("originalCoverUrl").takeIf { it.isNotBlank() },
                originalLyric = root.optString("originalLyric").takeIf { it.isNotBlank() },
                originalTranslatedLyric = root.optString("originalTranslatedLyric").takeIf { it.isNotBlank() },
                coverPath = root.optString("coverPath").takeIf { it.isNotBlank() },
                mediaUri = root.optString("mediaUri").takeIf { it.isNotBlank() },
                durationMs = root.optLong("durationMs", 0L)
            )
        }.getOrElse {
            NPLogger.w("GlobalDownloadManager", "解析下载元数据失败: ${metadataFile.name} - ${it.message}")
            null
        }
    }

    private fun metadataFileForAudio(audioFile: File): File {
        return File(audioFile.parentFile, "${audioFile.name}$DOWNLOAD_METADATA_SUFFIX")
    }

    private fun isManagedDownloadFile(audioFile: File): Boolean {
        return audioFile.parentFile?.name.equals("NeriPlayer", ignoreCase = true)
    }

    private fun findCoverFile(downloadDir: File, audioFile: File): String? {
        val coverDir = File(downloadDir, "Covers")
        if (!coverDir.exists()) return null
        val candidates = buildList {
            add(audioFile.nameWithoutExtension)
            add(audioFile.hashCode().toString())
        }
        candidates.forEach { baseName ->
            listOf("jpg", "jpeg", "png", "webp").forEach { ext ->
                val file = File(coverDir, "$baseName.$ext")
                if (file.exists()) {
                    return file.absolutePath
                }
            }
        }
        return null
    }

    private fun findLyricFile(downloadDir: File, audioFile: File, songId: Long? = null): String? {
        val lyricsDir = File(downloadDir, "Lyrics")
        if (!lyricsDir.exists()) return null
        return buildList {
            // 按 songId 查找（AudioDownloadManager.buildLyricFiles 用 songId 保存）
            if (songId != null) add(File(lyricsDir, "${songId}.lrc"))
            add(File(lyricsDir, "${audioFile.hashCode()}.lrc"))
            add(File(lyricsDir, "${audioFile.nameWithoutExtension}.lrc"))
        }.firstNotNullOfOrNull(::readTextFileSafely)
    }

    private fun findTranslatedLyricFile(downloadDir: File, audioFile: File, songId: Long? = null): String? {
        val lyricsDir = File(downloadDir, "Lyrics")
        if (!lyricsDir.exists()) return null
        return buildList {
            if (songId != null) add(File(lyricsDir, "${songId}_trans.lrc"))
            add(File(lyricsDir, "${audioFile.hashCode()}_trans.lrc"))
            add(File(lyricsDir, "${audioFile.nameWithoutExtension}_trans.lrc"))
        }.firstNotNullOfOrNull(::readTextFileSafely)
    }

    private fun readTextFileSafely(file: File): String? {
        if (!file.exists()) {
            return null
        }
        return try {
            LocalMediaSupport.readTextFile(file)
        } catch (_: Exception) {
            NPLogger.w("GlobalDownloadManager", "读取文件失败: ${file.name}")
            null
        }
    }
    
    /**
     * 删除已下载的歌曲
     */
    fun deleteDownloadedSong(context: Context, song: DownloadedSong) {
        scope.launch {
            try {
                val file = File(song.filePath)
                if (file.exists() && file.delete()) {
                    AudioDownloadManager.getManagedLyricFiles(
                        context,
                        SongItem(
                            id = song.id,
                            name = song.name,
                            artist = song.artist,
                            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                            albumId = 0L,
                            durationMs = 0L,
                            coverUrl = song.coverUrl,
                            matchedLyric = song.matchedLyric,
                            matchedTranslatedLyric = song.matchedTranslatedLyric,
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
                            originalLyric = song.originalLyric,
                            originalTranslatedLyric = song.originalTranslatedLyric,
                            localFilePath = song.filePath
                        )
                    ).forEach { lyricFile ->
                        if (lyricFile.exists()) {
                            lyricFile.delete()
                        }
                    }
                    metadataFileForAudio(file).takeIf(File::exists)?.delete()
                    
                    // 删除封面文件
                    song.coverPath?.let { coverPath ->
                        val coverFile = File(coverPath)
                        if (coverFile.exists()) {
                            coverFile.delete()
                        }
                    }
                    
                    // 刷新本地文件列表
                    scanLocalFiles(context)
                    NPLogger.d("GlobalDownloadManager", "删除文件成功: ${song.name}")
                }
            } catch (e: Exception) {
                NPLogger.e("GlobalDownloadManager", "删除文件失败: ${e.message}")
            }
        }
    }
    
    /**
     * 播放已下载的歌曲
     */
    fun playDownloadedSong(context: Context, song: DownloadedSong) {
        try {
            val file = File(song.filePath)
            if (file.exists()) {
                // 获取音频文件的实际时长
                var durationMs = song.durationMs
                if (durationMs <= 0L) {
                    durationMs = getAudioDuration(file)
                }
                // 使用PlayerManager播放本地文件
                val songItem = SongItem(
                    id = song.id,
                    name = song.name,
                    artist = song.artist,
                    album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
                    albumId = 0L,
                    durationMs = durationMs,
                    coverUrl = song.coverUrl,
                    mediaUri = song.mediaUri ?: file.absolutePath,
                    matchedLyric = song.matchedLyric,
                    matchedTranslatedLyric = song.matchedTranslatedLyric,
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
                    originalLyric = song.originalLyric,
                    originalTranslatedLyric = song.originalTranslatedLyric,
                    localFileName = file.name,
                    localFilePath = file.absolutePath
                )
                
                // 调用PlayerManager播放
                PlayerManager.playPlaylist(listOf(songItem), 0)
                NPLogger.d("GlobalDownloadManager", "使用PlayerManager播放本地文件: ${song.name}, 时长: ${durationMs}ms")
            } else {
                NPLogger.w("GlobalDownloadManager", "文件不存在: ${song.filePath}")
            }
        } catch (e: Exception) {
            NPLogger.e("GlobalDownloadManager", "播放文件失败: ${e.message}")
        }
    }
    
    /**
     * 获取音频文件的实际时长
     */
    private fun getAudioDuration(file: File): Long {
        try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    val durationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
                    extractor.release()
                    return durationUs / 1000L
                }
            }
            extractor.release()
        } catch (e: Exception) {
            NPLogger.w("GlobalDownloadManager", "MediaExtractor 获取音频时长失败: ${e.message}")
        }

        return try {
            val mediaMetadataRetriever = android.media.MediaMetadataRetriever()
            mediaMetadataRetriever.setDataSource(file.absolutePath)
            val durationStr = mediaMetadataRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            mediaMetadataRetriever.release()
            
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            NPLogger.w("GlobalDownloadManager", "MediaMetadataRetriever 获取音频时长失败: ${e.message}")
            0L
        }
    }

    /**
     * 开始下载单首歌曲
     */
    fun startDownload(context: Context, song: SongItem) {
        scope.launch {
            val songKey = song.stableKey()
            try {
                // 添加下载任务，如果已存在则跳过
                if (shouldSkipDownload(context, song) || !addDownloadTask(context, song)) {
                    return@launch
                }

                // 检查文件是否已存在
                val existingFilePath = AudioDownloadManager.getLocalFilePath(context, song)
                if (existingFilePath != null) {
                    NPLogger.d("GlobalDownloadManager", "文件已存在，直接标记为完成: ${song.name}")
                    updateTaskStatus(songKey, DownloadStatus.COMPLETED)
                    scanLocalFiles(context)
                    return@launch
                }

                // 等待其他单首下载任务完成，避免 progressFlow 冲突
                while (_isSingleDownloading.value) {
                    if (isSongCancelled(songKey)) {
                        throw CancellationException("Download cancelled before start")
                    }
                    delay(100)
                }

                if (isSongCancelled(songKey)) {
                    throw CancellationException("Download cancelled before start")
                }

                // 设置下载锁
                _isSingleDownloading.value = true
                try {
                    // 调用实际的下载方法
                    AudioDownloadManager.downloadSong(context, song)

                    // 下载成功，直接标记为完成
                    NPLogger.d("GlobalDownloadManager", "下载完成，标记任务: ${song.name}")
                    updateTaskStatus(songKey, DownloadStatus.COMPLETED)
                    scanLocalFiles(context)
                } finally {
                    // 释放下载锁
                    _isSingleDownloading.value = false
                }
            } catch (_: CancellationException) {
                NPLogger.d("GlobalDownloadManager", "下载已取消: ${song.name}")
                clearSongCancelled(songKey)
                updateTaskStatus(songKey, DownloadStatus.CANCELLED)
                _isSingleDownloading.value = false
            } catch (e: Exception) {
                NPLogger.e("GlobalDownloadManager", "下载失败: ${e.message}")
                updateTaskStatus(songKey, DownloadStatus.FAILED)
                _isSingleDownloading.value = false
            }
        }
    }
    
    /**
     * 开始批量下载
     */
    fun startBatchDownload(context: Context, songs: List<SongItem>) {
        if (songs.isEmpty()) return

        scope.launch {
            try {
                // 添加所有下载任务，过滤已存在的
                val newSongs = songs
                    .filterNot { shouldSkipDownload(context, it) }
                    .filter { song ->
                    addDownloadTask(context, song)
                }

                if (newSongs.isEmpty()) {
                    NPLogger.d("GlobalDownloadManager", "所有歌曲已在下载队列中")
                    return@launch
                }

                // 调用批量下载方法
                AudioDownloadManager.downloadPlaylist(context, newSongs)

                NPLogger.d("GlobalDownloadManager", "开始批量下载: ${newSongs.size} 首歌曲")
            } catch (_: CancellationException) {
                NPLogger.d("GlobalDownloadManager", "批量下载已取消")
                val cancelledKeys = songs.map { it.stableKey() }.toSet()
                _downloadTasks.value = _downloadTasks.value.filterNot { task ->
                    task.song.stableKey() in cancelledKeys && task.status != DownloadStatus.COMPLETED
                }
                cancelledKeys.forEach { clearSongCancelled(it) }
            } catch (e: Exception) {
                NPLogger.e("GlobalDownloadManager", "批量下载失败: ${e.message}")
                songs.forEach { song ->
                    updateTaskStatus(song.stableKey(), DownloadStatus.FAILED)
                }
            }
        }
    }
    
    /**
     * 添加下载任务
     */
    private fun addDownloadTask(context: Context, song: SongItem): Boolean {
        if (shouldSkipDownload(context, song)) {
            return false
        }

        val songKey = song.stableKey()
        clearSongCancelled(songKey)
        val existingTask = _downloadTasks.value.find { it.song.stableKey() == songKey }
        if (existingTask != null) {
            // 如果任务已完成或已取消，移除旧任务，允许重新下载
            if (existingTask.status == DownloadStatus.COMPLETED || existingTask.status == DownloadStatus.CANCELLED) {
                NPLogger.d("GlobalDownloadManager", "移除旧任务并重新下载: ${song.name}, 旧状态: ${existingTask.status}")
                removeDownloadTask(songKey)
            } else {
                // 如果任务正在下载，不允许重复添加
                NPLogger.d("GlobalDownloadManager", "歌曲已在下载队列中: ${song.name}, 状态: ${existingTask.status}")
                return false
            }
        }
        val newTask = DownloadTask(
            song = song,
            progress = null,
            status = DownloadStatus.DOWNLOADING
        )
        _downloadTasks.value = _downloadTasks.value + newTask
        NPLogger.d("GlobalDownloadManager", "添加新下载任务: ${song.name}")
        return true
    }

    private fun shouldSkipDownload(context: Context, song: SongItem): Boolean {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return false
        }

        NPLogger.d("GlobalDownloadManager", "Skip local song download: ${song.name}")
        return true
    }
    
    /**
     * 更新任务状态（公开方法，供外部调用）
     */
    fun updateTaskStatus(songKey: String, status: DownloadStatus) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.song.stableKey() == songKey) {
                task.copy(status = status, progress = null)
            } else {
                task
            }
        }
    }

    /**
     * 移除下载任务
     */
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
        val remaining = _downloadTasks.value.filter { it.status != DownloadStatus.DOWNLOADING }
        _downloadTasks.value = remaining
    }

    /**
     * 取消单个下载任务
     */
    fun cancelDownloadTask(songKey: String) {
        val task = _downloadTasks.value.find { it.song.stableKey() == songKey } ?: return
        if (task.status != DownloadStatus.DOWNLOADING) {
            return
        }

        // 只取消单个任务，不影响其他任务
        markSongCancelled(songKey)
        updateTaskStatus(songKey, DownloadStatus.CANCELLED)
    }

    /**
     * 检查歌曲是否已被取消
     */
    fun isSongCancelled(songKey: String): Boolean {
        return cancelledSongKeys.contains(songKey)
    }

    /**
     * 恢复下载任务
     */
    fun resumeDownloadTask(context: Context, songKey: String) {
        val task = _downloadTasks.value.find { it.song.stableKey() == songKey }
        if (task != null && task.status == DownloadStatus.CANCELLED) {
            if (isSongCancelled(songKey)) {
                NPLogger.d("GlobalDownloadManager", "取消仍在处理中，暂不恢复: ${task.song.name}")
                return
            }
            // 移除旧任务
            removeDownloadTask(songKey)
            // 重新开始下载（会重新获取下载链接）
            startDownload(context, task.song)
        }
    }

    /**
     * 清除已完成和已取消的任务
     */
    fun clearCompletedTasks() {
        // 先取消所有正在下载的任务
        val downloadingTasks = _downloadTasks.value.filter { it.status == DownloadStatus.DOWNLOADING }
        if (downloadingTasks.isNotEmpty()) {
            // 调用 AudioDownloadManager 停止批量下载
            AudioDownloadManager.cancelDownload()

            // 标记所有下载中的任务为已取消
            downloadingTasks.forEach { task ->
                cancelDownloadTask(task.song.stableKey())
            }
        }

        // 清除所有任务
        _downloadTasks.value = emptyList()
        cancelledSongKeys.clear()

        // 重置取消标志，允许用户立即开始新的下载
        scope.launch {
            delay(100) // 短暂延迟，确保取消操作完成
            AudioDownloadManager.resetCancelFlag()
        }

        NPLogger.d("GlobalDownloadManager", "已清除所有下载任务")
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
