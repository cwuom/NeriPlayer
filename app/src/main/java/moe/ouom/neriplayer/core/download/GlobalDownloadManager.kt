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

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File

/**
 * 全局下载管理器单例，用于管理下载任务和状态
 * 不依赖于特定的ViewModel或Composable的生命周期
 */
object GlobalDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()
    
    private val _downloadedSongs = MutableStateFlow<List<DownloadedSong>>(emptyList())
    val downloadedSongs: StateFlow<List<DownloadedSong>> = _downloadedSongs.asStateFlow()
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    
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
        scope.launch {
            AudioDownloadManager.progressFlow.collect { progress ->
                progress?.let {
                    updateDownloadProgress(it)
                } ?: run {
                    // 下载完成，更新任务状态
                    updateCompletedTasks(context)
                }
            }
        }
        
        scope.launch {
            AudioDownloadManager.batchProgressFlow.collect { batchProgress ->
                batchProgress?.let {
                    updateBatchProgress(context, it)
                }
            }
        }
        
        // 监听下载取消状态
        scope.launch {
            AudioDownloadManager.isCancelledFlow.collect { isCancelled ->
                if (isCancelled) {
                    updateAllTasksStatus(DownloadStatus.CANCELLED)
                }
            }
        }
    }
    
    private fun updateDownloadProgress(progress: AudioDownloadManager.DownloadProgress) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.status == DownloadStatus.DOWNLOADING) {
                task.copy(progress = progress)
            } else {
                task
            }
        }
    }
    
    private fun updateBatchProgress(context: Context, batchProgress: AudioDownloadManager.BatchDownloadProgress) {
        batchProgress?.let { progress ->
            // 更新当前下载任务的进度
            if (progress.currentProgress != null) {
                updateDownloadProgress(progress.currentProgress)
            }
            
            // 如果批量下载完成，刷新本地文件列表
            if (progress.completedSongs >= progress.totalSongs) {
                scope.launch {
                    delay(1000) // 延迟一下，确保文件写入完成
                    scanLocalFiles(context)
                }
            }
        }
    }
    
    private fun updateCompletedTasks(context: Context) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.status == DownloadStatus.DOWNLOADING) {
                task.copy(status = DownloadStatus.COMPLETED)
            } else {
                task
            }
        }
        
        // 刷新本地文件列表
        scanLocalFiles(context)
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
                val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg")
                
                downloadDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension.lowercase() in audioExtensions) {
                        try {
                            // 解析文件名格式：艺术家 - 歌曲名.扩展名
                            val nameWithoutExt = file.nameWithoutExtension
                            val parts = nameWithoutExt.split(" - ", limit = 2)
                            
                            if (parts.size >= 2) {
                                val artist = parts[0].trim()
                                val title = parts[1].trim()
                                
                                val songId = file.hashCode().toLong()
                                
                                // 查找对应的封面文件
                                var coverPath = findCoverFile(downloadDir, songId)
                                if (coverPath == null) {
                                    // 回退：按 baseName.jpg 命名规则查找
                                    val coverDir = File(downloadDir, "Covers")
                                    val baseName = nameWithoutExt
                                    listOf("jpg","jpeg","png","webp").forEach { ext ->
                                        val cf = File(coverDir, "$baseName.$ext")
                                        if (cf.exists()) { coverPath = cf.absolutePath; return@forEach }
                                    }
                                }
                                
                                // 查找对应的歌词文件
                                val matchedLyric = findLyricFile(downloadDir, songId, nameWithoutExt)
                                
                                val song = DownloadedSong(
                                    id = songId,
                                    name = title,
                                    artist = artist,
                                    album = "本地文件",
                                    filePath = file.absolutePath,
                                    fileSize = file.length(),
                                    downloadTime = file.lastModified(),
                                    coverPath = coverPath,
                                    matchedLyric = matchedLyric
                                )
                                songs.add(song)
                            }
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
    
    private fun findCoverFile(downloadDir: File, songId: Long): String? {
        val coverDir = File(downloadDir, "Covers")
        if (!coverDir.exists()) return null
        
        // 优先按 hashId 命中
        listOf("jpg","jpeg","png","webp").forEach { ext ->
            val f = File(coverDir, "${songId}.$ext"); if (f.exists()) return f.absolutePath
        }
        // 其次尝试按 "Artist - Title" 命名命中
        // 在 scanLocalFiles 上下文里我们手上有 File 对象，可向上层传入基本名来二次命中
        return null
    }
    
    private fun findLyricFile(downloadDir: File, songId: Long, baseName: String): String? {
        val lyricsDir = File(downloadDir, "Lyrics")
        if (!lyricsDir.exists()) return null

        // 优先按 hashId 命中
        val hashIdFile = File(lyricsDir, "${songId}.lrc")
        if (hashIdFile.exists()) {
            return try {
                hashIdFile.readText()
            } catch (e: Exception) {
                NPLogger.w("GlobalDownloadManager", "读取歌词文件失败: ${hashIdFile.name}")
                null
            }
        }
        
        // 其次尝试按 "Artist - Title" 命名命中
        val baseNameFile = File(lyricsDir, "$baseName.lrc")
        if (baseNameFile.exists()) {
            return try {
                baseNameFile.readText()
            } catch (e: Exception) {
                NPLogger.w("GlobalDownloadManager", "读取歌词文件失败: ${baseNameFile.name}")
                null
            }
        }
        
        return null
    }
    
    /**
     * 删除已下载的歌曲
     */
    fun deleteDownloadedSong(context: Context, song: DownloadedSong) {
        scope.launch {
            try {
                val file = File(song.filePath)
                if (file.exists() && file.delete()) {
                    // 删除歌词文件
                    val lyricsFile = File(song.filePath.replaceAfterLast('.', "lrc"))
                    if (lyricsFile.exists()) {
                        lyricsFile.delete()
                    }
                    
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
                val durationMs = getAudioDuration(context, file)
                
                // 使用PlayerManager播放本地文件
                val songItem = SongItem(
                    id = song.id,
                    name = song.name,
                    artist = song.artist,
                    album = "本地文件",
                    albumId = 0L,
                    durationMs = durationMs,
                    coverUrl = song.coverPath,
                    userLyricOffsetMs = 0L
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
    private fun getAudioDuration(context: Context, file: File): Long {
        return try {
            val mediaMetadataRetriever = android.media.MediaMetadataRetriever()
            mediaMetadataRetriever.setDataSource(file.absolutePath)
            val durationStr = mediaMetadataRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            mediaMetadataRetriever.release()
            
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            NPLogger.w("GlobalDownloadManager", "获取音频时长失败: ${e.message}")
            0L
        }
    }

    /**
     * 开始下载单首歌曲
     */
    fun startDownload(context: Context, song: SongItem) {
        scope.launch {
            try {
                // 添加下载任务
                addDownloadTask(song)
                
                // 调用实际的下载方法
                AudioDownloadManager.downloadSong(context, song)
                
                NPLogger.d("GlobalDownloadManager", "开始下载: ${song.name}")
            } catch (e: Exception) {
                NPLogger.e("GlobalDownloadManager", "开始下载失败: ${e.message}")
                updateTaskStatus(song.id, DownloadStatus.FAILED)
            }
        }
    }
    
    /**
     * 开始批量下载
     */
    fun startBatchDownload(context: Context, songs: List<SongItem>, onBatchComplete: () -> Unit = {}) {
        if (songs.isEmpty()) return
        
        scope.launch {
            try {
                // 添加所有下载任务
                songs.forEach { song ->
                    addDownloadTask(song)
                }
                
                // 调用批量下载方法
                AudioDownloadManager.downloadPlaylist(context, songs)
                
                NPLogger.d("GlobalDownloadManager", "开始批量下载: ${songs.size} 首歌曲")
            } catch (e: Exception) {
                NPLogger.e("GlobalDownloadManager", "批量下载失败: ${e.message}")
                songs.forEach { song ->
                    updateTaskStatus(song.id, DownloadStatus.FAILED)
                }
            }
        }
    }
    
    /**
     * 添加下载任务
     */
    private fun addDownloadTask(song: SongItem) {
        val existingTask = _downloadTasks.value.find { it.song.id == song.id }
        if (existingTask == null) {
            val newTask = DownloadTask(
                song = song,
                progress = null,
                status = DownloadStatus.DOWNLOADING
            )
            _downloadTasks.value = _downloadTasks.value + newTask
        }
    }
    
    /**
     * 更新任务状态
     */
    private fun updateTaskStatus(songId: Long, status: DownloadStatus) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.song.id == songId) {
                task.copy(status = status)
            } else {
                task
            }
        }
    }
    
    /**
     * 更新所有任务状态
     */
    private fun updateAllTasksStatus(status: DownloadStatus) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            task.copy(status = status)
        }
    }
    
    /**
     * 移除下载任务
     */
    fun removeDownloadTask(songId: Long) {
        _downloadTasks.value = _downloadTasks.value.filter { it.song.id != songId }
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
    val matchedLyric: String? = null
)

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
