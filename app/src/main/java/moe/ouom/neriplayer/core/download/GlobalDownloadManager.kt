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
import moe.ouom.neriplayer.R
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

    // 单首下载锁，确保同时只有一个单首下载任务在执行
    private val _isSingleDownloading = MutableStateFlow(false)

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
        var lastProgressSongId: Long? = null

        scope.launch {
            AudioDownloadManager.progressFlow.collect { progress ->
                progress?.let {
                    lastProgressSongId = it.songId
                    updateDownloadProgress(it)
                } ?: run {
                    // 下载完成，更新任务状态
                    // 使用最后记录的 songId 来标记任务为完成
                    lastProgressSongId?.let { songId ->
                        val task = _downloadTasks.value.find { it.song.id == songId }
                        if (task != null && task.status == DownloadStatus.DOWNLOADING) {
                            // 验证文件是否真的存在，避免误标记
                            val filePath = AudioDownloadManager.getLocalFilePath(context, task.song)
                            if (filePath != null) {
                                NPLogger.d("GlobalDownloadManager", "任务完成，文件已存在: ${task.song.name}")
                                _downloadTasks.value = _downloadTasks.value.map { t ->
                                    if (t.song.id == songId) {
                                        t.copy(status = DownloadStatus.COMPLETED, progress = null)
                                    } else {
                                        t
                                    }
                                }
                                scanLocalFiles(context)
                            } else {
                                NPLogger.w("GlobalDownloadManager", "任务标记完成但文件不存在: ${task.song.name}")
                            }
                        }
                    }
                    lastProgressSongId = null
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
                    updateAllTasksStatus(DownloadStatus.CANCELLED)
                }
            }
        }
    }
    
    private fun updateDownloadProgress(progress: AudioDownloadManager.DownloadProgress) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.song.id == progress.songId && task.status == DownloadStatus.DOWNLOADING) {
                task.copy(progress = progress)
            } else {
                task
            }
        }
    }

    private fun updateBatchProgress(context: Context, batchProgress: AudioDownloadManager.BatchDownloadProgress) {
        batchProgress?.let { progress ->
            // 只更新当前下载任务的进度
            // 任务完成状态由 progressFlow 的 null 值触发，不在这里处理
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
                                    album = context.getString(R.string.local_files),
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
                    album = context.getString(R.string.local_files),
                    albumId = 0L,
                    durationMs = durationMs,
                    coverUrl = song.coverPath,
                    matchedLyric = song.matchedLyric,
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
                // 添加下载任务，如果已存在则跳过
                if (!addDownloadTask(song)) {
                    return@launch
                }

                // 检查文件是否已存在
                val existingFilePath = AudioDownloadManager.getLocalFilePath(context, song)
                if (existingFilePath != null) {
                    NPLogger.d("GlobalDownloadManager", "文件已存在，直接标记为完成: ${song.name}")
                    updateTaskStatus(song.id, DownloadStatus.COMPLETED)
                    scanLocalFiles(context)
                    return@launch
                }

                // 等待其他单首下载任务完成，避免 progressFlow 冲突
                while (_isSingleDownloading.value) {
                    delay(100)
                }

                // 设置下载锁
                _isSingleDownloading.value = true
                try {
                    // 调用实际的下载方法
                    AudioDownloadManager.downloadSong(context, song)

                    // 下载成功，直接标记为完成
                    NPLogger.d("GlobalDownloadManager", "下载完成，标记任务: ${song.name}")
                    updateTaskStatus(song.id, DownloadStatus.COMPLETED)
                    scanLocalFiles(context)
                } finally {
                    // 释放下载锁
                    _isSingleDownloading.value = false
                }
            } catch (e: Exception) {
                NPLogger.e("GlobalDownloadManager", "下载失败: ${e.message}")
                updateTaskStatus(song.id, DownloadStatus.FAILED)
                _isSingleDownloading.value = false
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
                // 添加所有下载任务，过滤已存在的
                val newSongs = songs.filter { song ->
                    addDownloadTask(song)
                }

                if (newSongs.isEmpty()) {
                    NPLogger.d("GlobalDownloadManager", "所有歌曲已在下载队列中")
                    return@launch
                }

                // 调用批量下载方法
                AudioDownloadManager.downloadPlaylist(context, newSongs)

                NPLogger.d("GlobalDownloadManager", "开始批量下载: ${newSongs.size} 首歌曲")
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
    private fun addDownloadTask(song: SongItem): Boolean {
        val existingTask = _downloadTasks.value.find { it.song.id == song.id }
        if (existingTask != null) {
            // 如果任务已完成或已取消，移除旧任务，允许重新下载
            if (existingTask.status == DownloadStatus.COMPLETED || existingTask.status == DownloadStatus.CANCELLED) {
                NPLogger.d("GlobalDownloadManager", "移除旧任务并重新下载: ${song.name}, 旧状态: ${existingTask.status}")
                removeDownloadTask(song.id)
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
    
    /**
     * 更新任务状态（公开方法，供外部调用）
     */
    fun updateTaskStatus(songId: Long, status: DownloadStatus) {
        _downloadTasks.value = _downloadTasks.value.map { task ->
            if (task.song.id == songId) {
                task.copy(status = status, progress = null)
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

    /**
     * 取消单个下载任务
     */
    fun cancelDownloadTask(songId: Long) {
        // 只标记为已取消，不调用全局取消
        // 这样不会影响队列中的其他任务
        updateTaskStatus(songId, DownloadStatus.CANCELLED)
    }

    /**
     * 检查歌曲是否已被取消
     */
    fun isSongCancelled(songId: Long): Boolean {
        return _downloadTasks.value.find { it.song.id == songId }?.status == DownloadStatus.CANCELLED
    }

    /**
     * 恢复下载任务
     */
    fun resumeDownloadTask(context: Context, songId: Long) {
        val task = _downloadTasks.value.find { it.song.id == songId }
        if (task != null && task.status == DownloadStatus.CANCELLED) {
            // 移除旧任务
            removeDownloadTask(songId)
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
                cancelDownloadTask(task.song.id)
            }
        }

        // 清除所有任务
        _downloadTasks.value = emptyList()

        // 重置取消标志，允许用户立即开始新的下载
        scope.launch {
            delay(100) // 短暂延迟，确保取消操作完成
            AudioDownloadManager.resetCancelFlag()
        }

        NPLogger.d("GlobalDownloadManager", "已清除所有下载任务")
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
