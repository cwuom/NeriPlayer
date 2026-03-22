package moe.ouom.neriplayer.core.player

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
 * File: moe.ouom.neriplayer.core.player/AudioDownloadManager
 * Created: 2025/8/20
 */

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.clearSongCancelled
import moe.ouom.neriplayer.data.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.LocalMediaSupport
import moe.ouom.neriplayer.data.LocalSongSupport
import moe.ouom.neriplayer.data.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.displayArtist
import moe.ouom.neriplayer.data.displayCoverUrl
import moe.ouom.neriplayer.data.displayName
import moe.ouom.neriplayer.data.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.isYouTubeMusicSong
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * 音频下载管理器：解析来源（网易云 / Bilibili）并保存到本地 App 专属音乐目录
 * - 不依赖系统 DownloadManager，直接用共享 OkHttpClient，实现自定义 Header 与代理
 * - 保存路径：/Android/data/<package>/files/Music/NeriPlayer/<Artist - Title>.<ext>
 */
object AudioDownloadManager {

    private const val TAG = "NERI-Downloader"
    private const val BILI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val BILI_REFERER = "https://www.bilibili.com"
    private const val DOWNLOAD_READ_BUFFER_BYTES = 64L * 1024L
    private const val YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES = 4L * 1024L * 1024L

    private val _progressFlow = MutableStateFlow<DownloadProgress?>(null)
    val progressFlow: StateFlow<DownloadProgress?> = _progressFlow
    
    private val _batchProgressFlow = MutableStateFlow<BatchDownloadProgress?>(null)
    val batchProgressFlow: StateFlow<BatchDownloadProgress?> = _batchProgressFlow
    
    // 取消下载控制
    private val _isCancelled = MutableStateFlow(false)
    val isCancelledFlow: StateFlow<Boolean> = _isCancelled

    private data class ResolvedDownloadSource(
        val url: String,
        val mimeType: String? = null,
        val fileExtensionHint: String? = null,
        val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
        val contentLength: Long? = null,
        val durationMs: Long? = null
    )

    data class DownloadProgress(
        val songKey: String,
        val songId: Long,
        val fileName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) {
        val percentage: Int get() = if (totalBytes > 0) ((bytesRead * 100.0 / totalBytes).roundToInt()) else -1
    }

    data class BatchDownloadProgress(
        val totalSongs: Int,
        val completedSongs: Int,
        val currentSong: String,
        val currentProgress: DownloadProgress?,
        val currentSongIndex: Int = 0
    ) {
        val percentage: Int get() = if (totalSongs > 0) {
            val baseProgress = (completedSongs * 100.0 / totalSongs)
            val currentSongProgress = currentProgress?.let { progress ->
                if (progress.totalBytes > 0) {
                    (progress.bytesRead.toDouble() / progress.totalBytes) / totalSongs
                } else 0.0
            } ?: 0.0
            (baseProgress + currentSongProgress * 100).roundToInt()
        } else 0
    }

    suspend fun downloadSong(context: Context, song: SongItem) {
        withContext(Dispatchers.IO) {
            val songKey = song.stableKey()
            var sidecarJob: Job? = null
            try {
                // 检查文件是否已存在
                if (LocalSongSupport.isLocalSong(song, context)) {
                    NPLogger.d(TAG, "Skip local song download: ${song.name}")
                    _progressFlow.value = null
                    return@withContext
                }

                val existingFilePath = getLocalFilePath(context, song)
                if (existingFilePath != null) {
                    NPLogger.d(TAG, context.getString(R.string.download_file_exists, song.name))
                    // 文件已存在，设置进度为null触发任务完成
                    _progressFlow.value = null
                    return@withContext
                }

                val isYouTubeMusic = isYouTubeMusicSong(song)
                val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
                val resolved = when {
                    isYouTubeMusic -> resolveYouTubeMusic(song)
                    isBili -> resolveBili(song)
                    else -> resolveNetease(song.id)
                }
                if (resolved == null) {
                    NPLogger.e(TAG, context.getString(R.string.download_no_url, song.name))
                    return@withContext
                }

                // song duration 已经从 resolved 获取，不再写入数据库，只保持在当前上下文中
                // 真正的持久化由 GlobalDownloadManager 完成
                val workingSong = if (song.durationMs == 0L && resolved.durationMs != null && resolved.durationMs > 0L) {
                    song.copy(durationMs = resolved.durationMs)
                } else {
                    song
                }

                val url = resolved.url
                val mime = resolved.mimeType
                val extGuess = resolved.fileExtensionHint

                val ext = when {
                    resolved.streamType == YouTubePlayableStreamType.HLS ->
                        resolved.fileExtensionHint ?: "aac"
                    !mime.isNullOrBlank() -> mimeToExt(mime)
                    else -> extFromUrl(url) ?: extGuess
                }

                val baseName = sanitizeFileName("${song.displayArtist()} - ${song.displayName()}")
                val fileName = if (ext.isNullOrBlank()) baseName else "$baseName.$ext"

                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                val downloadDir = File(baseDir, "NeriPlayer").apply { mkdirs() }
                val tempFile = File(downloadDir, "$fileName.downloading")
                if (tempFile.exists()) tempFile.delete()

                sidecarJob = launchSidecarDownload(context, song, baseName)

                val reqBuilder = Request.Builder().url(url)
                if (isBili) {
                    val cookieMap = AppContainer.biliCookieRepo.getCookiesOnce()
                    val cookieHeader = cookieMap.entries.joinToString("; ") { (k, v) -> "$k=$v" }
                    reqBuilder
                        .header("User-Agent", BILI_UA)
                        .header("Referer", BILI_REFERER)
                        .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
                } else if (isYouTubeMusic) {
                    val auth = AppContainer.youtubeAuthRepo.getAuthOnce().normalized()
                    auth.buildYouTubeStreamRequestHeaders(
                        refererOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
                        streamUrl = url
                    ).forEach { (name, value) ->
                        reqBuilder.header(name, value)
                    }
                    val totalContentLength = resolved.contentLength
                        ?: YouTubeGoogleVideoRangeSupport.resolveQueryContentLength(url)
                    if (
                        resolved.streamType == YouTubePlayableStreamType.DIRECT &&
                        totalContentLength != null &&
                        YouTubeGoogleVideoRangeSupport.shouldForceExplicitFullRange(url)
                    ) {
                        reqBuilder.header(
                            "Range",
                            YouTubeGoogleVideoRangeSupport.buildFullRangeHeader(totalContentLength)
                        )
                    }
                }

                val request = reqBuilder.build()
                val client = AppContainer.sharedOkHttpClient

                // 貌似很多平台都不支持多线程下载(x  所以采用单线程
                // 传入临时文件
                if (resolved.streamType == YouTubePlayableStreamType.HLS) {
                    singleThreadHlsDownload(
                        client = client,
                        playlistRequest = request,
                        destFile = tempFile,
                        songId = workingSong.id,
                        songKey = workingSong.stableKey(),
                        totalBytesHint = resolved.contentLength ?: 0L
                    )
                } else {
                    singleThreadDownload(client, request, tempFile, workingSong.id, workingSong.stableKey())
                }

                // 下载完成后，重命名为正式文件
                val destFile = uniqueFile(downloadDir, fileName)
                val renamed = tempFile.renameTo(destFile)
                if (!renamed) {
                    runCatching {
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()
                    }.getOrElse {
                        tempFile.delete()
                        throw IllegalStateException("保存失败：无法重命名临时文件")
                    }
                }

                _progressFlow.value = null
                // 通知媒体库（仅当保存到公共目录时必要；App 专属目录通常播放器可直接访问）
                try {
                    context.contentResolver.openInputStream(Uri.fromFile(destFile))?.close()
                } catch (_: Exception) { }

            } catch (e: Exception) {
                sidecarJob?.cancel()
                if (
                    e is java.util.concurrent.CancellationException ||
                        _isCancelled.value ||
                        GlobalDownloadManager.isSongCancelled(songKey)
                ) {
                    NPLogger.d(TAG, "下载已取消: ${song.name}")
                    _progressFlow.value = null
                    clearSongCancelled(songKey)
                    throw java.util.concurrent.CancellationException("Download cancelled")
                }
                NPLogger.e(TAG, "下载失败: ${song.name}, 错误: ${e.javaClass.simpleName} - ${e.message}", e)
                _progressFlow.value = null
                throw e  // 重新抛出异常，让调用方知道下载失败
            }
        }
    }

    private fun launchSidecarDownload(
        context: Context,
        song: SongItem,
        baseName: String
    ): Job {
        return AppContainer.launchBackgroundIo {
            runCatching {
                downloadLyrics(context, song)
            }.onFailure { error ->
                NPLogger.w(TAG, "歌词后台下载失败: ${song.name} - ${error.message}")
            }
            runCatching {
                cacheCover(context, song, baseName)
            }.onFailure { error ->
                NPLogger.w(TAG, "封面后台下载失败: ${song.name} - ${error.message}")
            }
        }
    }

    private fun cacheCover(
        context: Context,
        song: SongItem,
        baseName: String
    ) {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val downloadDir = File(baseDir, "NeriPlayer")
        val coverDir = File(downloadDir, "Covers").apply { mkdirs() }
        val coverUrl = song.displayCoverUrl()
        if (coverUrl.isNullOrBlank()) {
            return
        }

        val coverFile = File(coverDir, "$baseName.jpg")
        if (coverFile.exists() && coverFile.length() > 0L) {
            return
        }

        val req = Request.Builder().url(coverUrl).build()
        AppContainer.sharedOkHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                return
            }
            response.body.byteStream().use { input ->
                coverFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** 批量下载歌单中的所有歌曲 */
    suspend fun downloadPlaylist(context: Context, songs: List<SongItem>) {
        withContext(Dispatchers.IO) {
            try {
                val remoteSongs = songs.filterNot { LocalSongSupport.isLocalSong(it, context) }
                if (remoteSongs.isEmpty()) {
                    NPLogger.d(TAG, "Skip batch download because all songs are local")
                    _batchProgressFlow.value = null
                    return@withContext
                }

                _isCancelled.value = false
                _batchProgressFlow.value = BatchDownloadProgress(
                    totalSongs = remoteSongs.size,
                    completedSongs = 0,
                    currentSong = "",
                    currentProgress = null
                )

                for (index in remoteSongs.indices) {
                    val song = remoteSongs[index]
                    // 检查是否被全局取消
                    if (_isCancelled.value) {
                        NPLogger.d(TAG, context.getString(R.string.download_cancelled_message))
                        break
                    }

                    // 检查当前歌曲是否被单独取消
                    if (GlobalDownloadManager.isSongCancelled(song.stableKey())) {
                        NPLogger.d(TAG, "跳过已取消的歌曲: ${song.name}")
                        clearSongCancelled(song.stableKey())
                        _batchProgressFlow.value?.let { current ->
                            _batchProgressFlow.value = current.copy(
                                completedSongs = index + 1,
                                currentProgress = null
                            )
                        }
                        continue
                    }

                    try {
                        _batchProgressFlow.value = _batchProgressFlow.value?.copy(
                            currentSong = song.displayName(),
                            currentProgress = null,
                            currentSongIndex = index
                        )

                        // 监听单首歌曲的下载进度
                        val progressJob = launch {
                            _progressFlow.collect { progress ->
                                _batchProgressFlow.value?.let { current ->
                                    _batchProgressFlow.value = current.copy(currentProgress = progress)
                                }
                            }
                        }

                        try {
                            downloadSong(context, song)

                        // 停止监听进度
                        } finally {
                            progressJob.cancel()
                        }

                        // 下载成功，直接标记任务为完成
                        GlobalDownloadManager.updateTaskStatus(
                            song.stableKey(),
                            moe.ouom.neriplayer.core.download.DownloadStatus.COMPLETED
                        )

                        _batchProgressFlow.value?.let { current ->
                            _batchProgressFlow.value = current.copy(
                                completedSongs = index + 1,
                                currentProgress = null
                            )
                        }
                    } catch (_: java.util.concurrent.CancellationException) {
                        // 下载被取消，继续下一首
                        NPLogger.d(TAG, "歌曲下载被取消: ${song.name}")
                        clearSongCancelled(song.stableKey())
                        _batchProgressFlow.value?.let { current ->
                            _batchProgressFlow.value = current.copy(
                                completedSongs = index + 1,
                                currentProgress = null
                            )
                        }
                    } catch (e: Exception) {
                        NPLogger.e(TAG, context.getString(R.string.download_batch_failed_song, song.name, e.message ?: ""), e)
                        // 标记任务失败
                        GlobalDownloadManager.updateTaskStatus(
                            song.stableKey(),
                            moe.ouom.neriplayer.core.download.DownloadStatus.FAILED
                        )
                    }
                }

                _batchProgressFlow.value = null
            } catch (e: Exception) {
                NPLogger.e(TAG, context.getString(R.string.download_batch_failed, e.message ?: ""), e)
                _batchProgressFlow.value = null
            }
        }
    }
    
    /** 取消下载 */
    fun cancelDownload() {
        _isCancelled.value = true
        _progressFlow.value = null
        _batchProgressFlow.value = null
    }

    /** 重置取消标志 */
    fun resetCancelFlag() {
        _isCancelled.value = false
    }

    /** 下载歌词文件 */
    private fun downloadLyrics(context: Context, song: SongItem) {
        try {
            val lyricFiles = buildLyricFiles(context, song)
            val lyricFile = lyricFiles.lyricById
            val baseNameLyricFile = lyricFiles.lyricByName
            val transLyricFile = lyricFiles.translatedById
            val baseNameTransLyricFile = lyricFiles.translatedByName

            // 优先使用song.matchedLyric中的歌词
            if (!song.matchedLyric.isNullOrBlank()) {
                lyricFile.writeText(song.matchedLyric, Charsets.UTF_8)
                baseNameLyricFile.writeText(song.matchedLyric, Charsets.UTF_8)
                NPLogger.d(TAG, context.getString(R.string.download_lyrics_matched, song.name))
            }

            val isYouTubeMusic = isYouTubeMusicSong(song)
            val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)

            when {
                isYouTubeMusic -> downloadYouTubeMusicLyrics(song, lyricFile, baseNameLyricFile)
                isBili -> { /* B站暂无歌词源 */ }
                else -> downloadNeteaseLyrics(song, lyricFile, baseNameLyricFile, transLyricFile, baseNameTransLyricFile)
            }
        } catch (e: Exception) {
            NPLogger.w(TAG, "歌词下载失败: ${song.name} - ${e.message}")
        }
    }

    /** 从 LRCLIB / YouTube Music API 获取歌词并保存 */
    private fun downloadYouTubeMusicLyrics(
        song: SongItem,
        lyricFile: File,
        baseNameLyricFile: File
    ) {
        if (!song.matchedLyric.isNullOrBlank()) return
        try {
            val lrcLibResult = try {
                runBlocking(Dispatchers.IO) {
                    val durationSec = (song.durationMs / 1000).toInt()
                    AppContainer.lrcLibClient.getLyrics(
                        trackName = song.name,
                        artistName = song.artist,
                        durationSeconds = durationSec
                    ) ?: AppContainer.lrcLibClient.searchLyrics("${song.name} ${song.artist}")
                }
            } catch (_: Exception) { null }

            val syncedLyrics = lrcLibResult?.syncedLyrics?.takeIf { it.isNotBlank() }
            val plainLyrics = lrcLibResult?.plainLyrics?.takeIf { it.isNotBlank() }

            when {
                syncedLyrics != null -> {
                    lyricFile.writeText(syncedLyrics, Charsets.UTF_8)
                    baseNameLyricFile.writeText(syncedLyrics, Charsets.UTF_8)
                    NPLogger.d(TAG, "LRCLIB 同步歌词保存: ${song.name}")
                    return
                }
                plainLyrics != null -> {
                    lyricFile.writeText(plainLyrics, Charsets.UTF_8)
                    baseNameLyricFile.writeText(plainLyrics, Charsets.UTF_8)
                    NPLogger.d(TAG, "LRCLIB 纯文本歌词保存: ${song.name}")
                    return
                }
            }

            // 回退 YouTube Music API
            val videoId = extractYouTubeMusicVideoId(song.mediaUri) ?: return
            val ytResult = runBlocking(Dispatchers.IO) {
                AppContainer.youtubeMusicClient.getLyrics(videoId)
            } ?: return
            val lyricsText = ytResult.lyrics.takeIf { it.isNotBlank() } ?: return
            lyricFile.writeText(lyricsText, Charsets.UTF_8)
            baseNameLyricFile.writeText(lyricsText, Charsets.UTF_8)
            NPLogger.d(TAG, "YouTube Music API 歌词保存: ${song.name}")
        } catch (e: Exception) {
            NPLogger.w(TAG, "YouTube Music 歌词下载失败: ${song.name} - ${e.message}")
        }
    }

    /** 从网易云 API 获取歌词并保存 */
    private fun downloadNeteaseLyrics(
        song: SongItem,
        lyricFile: File,
        baseNameLyricFile: File,
        transLyricFile: File,
        baseNameTransLyricFile: File
    ) {
        if (!song.matchedLyric.isNullOrBlank()) {
            try {
                val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
                val root = JSONObject(lyrics)
                if (root.optInt("code") == 200) {
                    val tlyric = root.optJSONObject("tlyric")?.optString("lyric") ?: ""
                    if (tlyric.isNotBlank()) {
                        transLyricFile.writeText(tlyric, Charsets.UTF_8)
                        baseNameTransLyricFile.writeText(tlyric, Charsets.UTF_8)
                        NPLogger.d(TAG, "翻译歌词保存: ${song.name}")
                    }
                }
            } catch (e: Exception) {
                NPLogger.w(TAG, "翻译歌词下载失败: ${song.name} - ${e.message}")
            }
            return
        }

        try {
            val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
            val root = JSONObject(lyrics)
            if (root.optInt("code") != 200) return

            val lrc = root.optJSONObject("lrc")?.optString("lyric") ?: ""
            if (lrc.isNotBlank()) {
                lyricFile.writeText(lrc, Charsets.UTF_8)
                baseNameLyricFile.writeText(lrc, Charsets.UTF_8)
                NPLogger.d(TAG, "从API获取歌词保存: ${song.name}")
            }

            val tlyric = root.optJSONObject("tlyric")?.optString("lyric") ?: ""
            if (tlyric.isNotBlank()) {
                transLyricFile.writeText(tlyric, Charsets.UTF_8)
                baseNameTransLyricFile.writeText(tlyric, Charsets.UTF_8)
                NPLogger.d(TAG, "从API获取翻译歌词保存: ${song.name}")
            }
        } catch (e: Exception) {
            NPLogger.w(TAG, "网易云歌词下载失败: ${song.name} - ${e.message}")
        }
    }

    /** 获取本地音频文件路径 */
fun getLocalFilePath(context: Context, song: SongItem): String? {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val downloadDir = File(baseDir, "NeriPlayer")
        if (!downloadDir.exists()) return null
        
        // 查找可能的文件扩展名
        val possibleExtensions = listOf("flac", "m4a", "mp3", "eac3", "aac", "wav", "ogg", "webm")
        val baseNames = candidateBaseNames(song)
        for (baseName in baseNames) {
            for (ext in possibleExtensions) {
                val file = File(downloadDir, "$baseName.$ext")
                if (file.exists()) return file.absolutePath
            }
        }
        val fileNamePatterns = baseNames.map { baseName ->
            Regex("^${Regex.escape(baseName)}(?: \\(\\d+\\))?$")
        }
        return downloadDir.listFiles()
            ?.firstOrNull { file ->
                file.isFile &&
                    file.extension.lowercase() in possibleExtensions &&
                    fileNamePatterns.any { pattern -> pattern.matches(file.nameWithoutExtension) }
            }
            ?.absolutePath
    }

    /** 解析下载歌曲对应的本地封面，供离线 UI 兜底使用 */
    fun getLocalCoverUri(context: Context, song: SongItem): String? {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val coverDir = File(File(baseDir, "NeriPlayer"), "Covers")
        val possibleExtensions = listOf("jpg", "jpeg", "png", "webp")
        val baseNames = candidateBaseNames(song)
        if (coverDir.exists()) {
            for (baseName in baseNames) {
                for (ext in possibleExtensions) {
                    val file = File(coverDir, "$baseName.$ext")
                    if (file.exists()) return file.toURI().toString()
                }
            }

            val fileNamePatterns = baseNames.map { baseName ->
                Regex("^${Regex.escape(baseName)}(?: \\(\\d+\\))?$")
            }
            coverDir.listFiles()
                ?.firstOrNull { file ->
                    file.isFile &&
                        file.extension.lowercase() in possibleExtensions &&
                        fileNamePatterns.any { pattern -> pattern.matches(file.nameWithoutExtension) }
                }
                ?.toURI()
                ?.toString()
                ?.let { return it }
        }

        val localAudioPath = getLocalFilePath(context, song)
            ?: song.localFilePath?.takeIf { File(it).exists() }
            ?: return null
        val localAudioUri = Uri.fromFile(File(localAudioPath))
        return runCatching {
            LocalMediaSupport.inspect(context, localAudioUri).coverUri
        }.getOrElse {
            NPLogger.w(TAG, "resolve local cover fallback failed: ${it.message}")
            null
        }
    }

    private fun candidateBaseNames(song: SongItem): List<String> {
        val baseNames = linkedSetOf<String>()
        baseNames += sanitizeFileName("${song.customArtist ?: song.artist} - ${song.customName ?: song.name}")
        baseNames += sanitizeFileName("${song.artist} - ${song.name}")

        val originalName = song.originalName?.takeIf { it.isNotBlank() } ?: song.name
        val originalArtist = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
        baseNames += sanitizeFileName("$originalArtist - $originalName")

        return baseNames.toList()
    }

    /** 获取本地歌词文件路径 */
    fun getLyricFilePath(context: Context, song: SongItem): String? {
        val files = buildLyricFiles(context, song)
        return sequenceOf(files.lyricById, files.lyricByName)
            .firstOrNull(File::exists)
            ?.absolutePath
    }

    /** 获取本地翻译歌词文件路径 */
    fun getTranslatedLyricFilePath(context: Context, song: SongItem): String? {
        val files = buildLyricFiles(context, song)
        return sequenceOf(files.translatedById, files.translatedByName)
            .firstOrNull(File::exists)
            ?.absolutePath
    }

    fun getManagedLyricFiles(context: Context, song: SongItem): List<File> {
        val files = buildLyricFiles(context, song)
        return listOf(
            files.lyricById,
            files.lyricByName,
            files.translatedById,
            files.translatedByName
        )
    }

    // 解析网易云直链
    private suspend fun resolveNetease(songId: Long): ResolvedDownloadSource? {
        val quality = try { AppContainer.settingsRepo.audioQualityFlow.first() } catch (_: Exception) { "exhigh" }
        val raw = AppContainer.neteaseClient.getSongDownloadUrl(songId, level = quality)
        return try {
            val root = JSONObject(raw)
            if (root.optInt("code") != 200) return tryWeapiFallback(songId, quality)
            val data = when (val d = root.opt("data")) {
                is JSONObject -> d
                is JSONArray -> d.optJSONObject(0)
                else -> null
            } ?: return tryWeapiFallback(songId, quality)
            val url = data.optString("url", "")
            if (url.isNullOrBlank()) return tryWeapiFallback(songId, quality)
            val type = data.optString("type", "") // e.g., mp3/flac
            val mime = guessMimeFromUrl(url)
            ResolvedDownloadSource(
                url = ensureHttps(url),
                mimeType = mime,
                fileExtensionHint = type.lowercase()
            )
        } catch (_: Exception) {
            tryWeapiFallback(songId, quality)
        }
    }

    private fun bitrateForQuality(level: String): Int = when (level.lowercase()) {
        "standard" -> 128000
        "exhigh" -> 320000
        "lossless", "hires", "jyeffect", "sky", "jymaster" -> 1411200
        else -> 320000
    }

    private fun tryWeapiFallback(songId: Long, level: String): ResolvedDownloadSource? {
        return try {
            val br = bitrateForQuality(level)
            val raw = AppContainer.neteaseClient.getSongUrl(songId, bitrate = br)
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) return null
            val data = when (val d = root.opt("data")) {
                is JSONObject -> d
                is JSONArray -> d.optJSONObject(0)
                else -> null
            } ?: return null
            val url = data.optString("url", "")
            if (url.isNullOrBlank()) return null
            val finalUrl = ensureHttps(url)
            val mime = guessMimeFromUrl(finalUrl)
            val ext = extFromUrl(finalUrl)
            ResolvedDownloadSource(url = finalUrl, mimeType = mime, fileExtensionHint = ext)
        } catch (_: Exception) { null }
    }

    private suspend fun resolveYouTubeMusic(song: SongItem): ResolvedDownloadSource? {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri) ?: return null
        val playbackRepository = AppContainer.youtubeMusicPlaybackRepository
        suspend fun resolve(forceRefresh: Boolean, requireDirect: Boolean) =
            playbackRepository.getBestPlayableAudio(
                videoId = videoId,
                forceRefresh = forceRefresh,
                requireDirect = requireDirect,
                preferM4a = true
            )
        val directPlayableAudio = resolve(forceRefresh = false, requireDirect = true)
            ?: resolve(forceRefresh = true, requireDirect = true)
        val playableAudio = directPlayableAudio
            ?: resolve(forceRefresh = false, requireDirect = false)
            ?: resolve(forceRefresh = true, requireDirect = false)
            ?: return null
        if (directPlayableAudio == null && playableAudio.streamType == YouTubePlayableStreamType.HLS) {
            NPLogger.w(TAG, "YouTube Music 下载未拿到直链，回退 HLS: videoId=$videoId")
        }
        if (playableAudio.streamType == YouTubePlayableStreamType.HLS) {
            return ResolvedDownloadSource(
                url = playableAudio.url,
                mimeType = "audio/aac",
                fileExtensionHint = "aac",
                streamType = YouTubePlayableStreamType.HLS,
                contentLength = playableAudio.contentLength
            )
        }
        val mimeType = playableAudio.mimeType ?: guessMimeFromUrl(playableAudio.url)
        return ResolvedDownloadSource(
            url = playableAudio.url,
            mimeType = mimeType,
            fileExtensionHint = extFromUrl(playableAudio.url),
            contentLength = playableAudio.contentLength,
            durationMs = playableAudio.durationMs
        )
    }

    // Resolve Bili audio direct url.
    private suspend fun resolveBili(song: SongItem): ResolvedDownloadSource? {
        val resolved = resolveBiliSong(song, AppContainer.biliClient) ?: return null
        val chosen: BiliAudioStreamInfo? = AppContainer.biliPlaybackRepository
            .getBestPlayableAudio(resolved.videoInfo.bvid, resolved.cid)
        val url = chosen?.url ?: return null
        val mime = chosen.mimeType
        val ext = mimeToExt(mime)
        return ResolvedDownloadSource(url = url, mimeType = mime, fileExtensionHint = ext)
    }

    private fun sanitizeFileName(name: String): String {
        val n = Normalizer.normalize(name, Normalizer.Form.NFKD)
        return n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio" }
    }

    private fun buildLyricFiles(context: Context, song: SongItem): LyricFiles {
        val lyricsDir = getLyricsDirectory(context)
        val baseName = sanitizeFileName("${song.displayArtist()} - ${song.displayName()}")
        return LyricFiles(
            lyricById = File(lyricsDir, "${song.id}.lrc"),
            lyricByName = File(lyricsDir, "$baseName.lrc"),
            translatedById = File(lyricsDir, "${song.id}_trans.lrc"),
            translatedByName = File(lyricsDir, "${baseName}_trans.lrc")
        )
    }

    private fun getLyricsDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(baseDir, "NeriPlayer/Lyrics").apply { mkdirs() }
    }

    private data class LyricFiles(
        val lyricById: File,
        val lyricByName: File,
        val translatedById: File,
        val translatedByName: File
    )

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var idx = 1
        while (f.exists() && idx < 10_000) {
            val candidate = if (ext.isBlank()) "$base (${idx})" else "$base (${idx}).${ext}"
            f = File(dir, candidate)
            idx++
        }
        return f
    }

    private fun ensureHttps(url: String): String = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url

    private fun mimeToExt(mime: String): String? = when (mime.lowercase()) {
        "audio/flac" -> "flac"
        "audio/x-flac" -> "flac"
        "audio/eac3", "audio/e-ac-3" -> "eac3"
        "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
        "audio/webm" -> "webm"
        "audio/ogg" -> "ogg"
        "audio/mpeg" -> "mp3"
        else -> null
    }

    private fun guessMimeFromUrl(url: String): String? {
        return try {
            URLConnection.guessContentTypeFromName(url.toUri().lastPathSegment)
        } catch (_: Exception) { null }
    }

    private fun extFromUrl(url: String): String? {
        val p = url.toUri().lastPathSegment ?: return null
        val dot = p.lastIndexOf('.')
        if (dot <= 0 || dot == p.length - 1) return null
        return p.substring(dot + 1).lowercase().take(6)
    }

    private suspend fun singleThreadHlsDownload(
        client: okhttp3.OkHttpClient,
        playlistRequest: Request,
        destFile: File,
        songId: Long,
        songKey: String,
        totalBytesHint: Long
    ) = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始 HLS 下载文件: ${destFile.name}, songId=$songId")

        val playlistText = client.newCall(playlistRequest).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body.string()
        }
        val segmentUrls = parseHlsSegmentUrls(playlistRequest.url.toString(), playlistText)
        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("HLS playlist contains no segments")
        }

        val headerMap = playlistRequest.headers.names().associateWith { name ->
            playlistRequest.header(name).orEmpty()
        }

        var downloadedBytes = 0L
        destFile.sink().buffer().use { sink ->
            segmentUrls.forEachIndexed { index, segmentUrl ->
                ensureDownloadNotCancelled(songId, songKey, destFile)

                val segmentRequest = Request.Builder()
                    .url(segmentUrl)
                    .apply {
                        headerMap.forEach { (name, value) ->
                            header(name, value)
                        }
                    }
                    .build()

                client.newCall(segmentRequest).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val payload = stripLeadingId3(response.body.bytes())
                    sink.write(payload)
                    downloadedBytes += payload.size.toLong()
                }

                val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0)
                    .coerceAtLeast(0.001)
                _progressFlow.value = DownloadProgress(
                    songKey = songKey,
                    songId = songId,
                    fileName = destFile.name,
                    bytesRead = downloadedBytes,
                    totalBytes = totalBytesHint,
                    speedBytesPerSec = (downloadedBytes / elapsedSec).toLong()
                )
                if ((index + 1) % 8 == 0) {
                    sink.flush()
                }
            }
            sink.flush()
        }

        NPLogger.d(
            TAG,
            "HLS 下载完成: ${destFile.name}, 实际大小: $downloadedBytes bytes, segments=${segmentUrls.size}, songId=$songId"
        )
    }

    private fun parseHlsSegmentUrls(playlistUrl: String, playlistText: String): List<String> {
        return playlistText.lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .map { segment ->
                runCatching { java.net.URI(playlistUrl).resolve(segment).toString() }
                    .getOrElse { segment }
            }
            .toList()
    }

    private fun stripLeadingId3(bytes: ByteArray): ByteArray {
        if (bytes.size < 10) {
            return bytes
        }
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            return bytes
        }
        val tagSize =
            ((bytes[6].toInt() and 0x7f) shl 21) or
                ((bytes[7].toInt() and 0x7f) shl 14) or
                ((bytes[8].toInt() and 0x7f) shl 7) or
                (bytes[9].toInt() and 0x7f)
        val payloadOffset = 10 + tagSize
        return if (payloadOffset in 1 until bytes.size) {
            bytes.copyOfRange(payloadOffset, bytes.size)
        } else {
            bytes
        }
    }

    /** 单线程下载 */
    private suspend fun singleThreadDownload(
        client: okhttp3.OkHttpClient,
        request: Request,
        destFile: File,
        songId: Long,
        songKey: String
    ) = withContext(Dispatchers.IO) {
        if (YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(request) &&
            !YouTubeGoogleVideoRangeSupport.hasExplicitRangeHeader(
                request.headers.names().associateWith { headerName ->
                    request.header(headerName).orEmpty()
                }
            )
        ) {
            singleThreadChunkedDownload(
                client = client,
                request = request,
                destFile = destFile,
                songId = songId,
                songKey = songKey
            )
            return@withContext
        }

        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始下载文件: ${destFile.name}, songId=$songId")
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")

            val total = resp.body.contentLength()
            NPLogger.d(TAG, "文件总大小: $total bytes, songId=$songId")
            val source = resp.body.source()
            destFile.sink().buffer().use { sink ->
                var readSoFar = 0L
                val buffer = Buffer()
                while (true) {
                    // 检查是否被取消(全局取消或单个任务取消)
                    if (_isCancelled.value || GlobalDownloadManager.isSongCancelled(songKey)) {
                        NPLogger.d(TAG, "下载被取消，停止下载: songId=$songId")
                        destFile.delete() // 删除临时文件
                        _progressFlow.value = null
                        throw java.util.concurrent.CancellationException("Download cancelled")
                    }

                    val read = source.read(buffer, DOWNLOAD_READ_BUFFER_BYTES)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    readSoFar += read
                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                    val speed = (readSoFar / elapsedSec).toLong()
                    val progress = DownloadProgress(
                        songKey = songKey,
                        songId = songId,
                        fileName = destFile.name,
                        bytesRead = readSoFar,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    )
                    _progressFlow.value = progress
                }
                sink.flush()
                NPLogger.d(TAG, "文件下载完成: ${destFile.name}, 实际大小: $readSoFar bytes, songId=$songId")
            }
        }
    }

    private suspend fun singleThreadChunkedDownload(
        client: okhttp3.OkHttpClient,
        request: Request,
        destFile: File,
        songId: Long,
        songKey: String
    ) = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始分块下载文件: ${destFile.name}, songId=$songId")

        var downloadedBytes = 0L
        var totalBytes = 0L
        destFile.sink().buffer().use { sink ->
            while (true) {
                if (_isCancelled.value || GlobalDownloadManager.isSongCancelled(songKey)) {
                    NPLogger.d(TAG, "下载被取消，停止分块下载: songId=$songId")
                    destFile.delete()
                    _progressFlow.value = null
                    throw java.util.concurrent.CancellationException("Download cancelled")
                }

                val remainingRequestLength = if (totalBytes > 0L) {
                    (totalBytes - downloadedBytes).coerceAtLeast(0L)
                } else {
                    -1L
                }
                if (remainingRequestLength == 0L) {
                    break
                }

                try {
                    val chunkResult = YouTubeGoogleVideoRangeSupport.executeChunkLengthFallback(
                        requestLength = remainingRequestLength,
                        preferredChunkSize = YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES
                    ) { chunkLength ->
                        downloadChunk(
                            client = client,
                            request = request,
                            start = downloadedBytes,
                            requestedChunkLength = chunkLength,
                            sink = sink,
                            songId = songId,
                            songKey = songKey,
                            destFile = destFile,
                            startNs = startNs,
                            currentDownloadedBytes = downloadedBytes,
                            currentTotalBytes = totalBytes
                        )
                    }
                    downloadedBytes = chunkResult.value.downloadedBytes
                    totalBytes = chunkResult.value.totalBytes
                    if (
                        chunkResult.chunkLength !=
                        YouTubeGoogleVideoRangeSupport.candidateChunkLengths(
                            requestLength = remainingRequestLength,
                            preferredChunkSize = YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES
                        ).first()
                    ) {
                        NPLogger.w(
                            TAG,
                            "下载分块 fallback 生效: ${chunkResult.chunkLength} bytes, songId=$songId"
                        )
                    }
                    if (chunkResult.value.isEndOfStream) {
                        break
                    }
                } catch (error: ChunkRequestIOException) {
                    if ((error.responseCode == 416 || error.responseCode == 403) && downloadedBytes > 0L) {
                        // 416 = range 越界，403 = CDN 拒绝，已有部分数据时保留
                        break
                    }
                    throw error
                }
            }
            sink.flush()
        }

        NPLogger.d(TAG, "分块下载完成: ${destFile.name}, 实际大小: $downloadedBytes bytes, songId=$songId")
    }

    private data class ChunkDownloadResult(
        val requestedChunkLength: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isEndOfStream: Boolean
    )

    private fun downloadChunk(
        client: okhttp3.OkHttpClient,
        request: Request,
        start: Long,
        requestedChunkLength: Long,
        sink: okio.BufferedSink,
        songId: Long,
        songKey: String,
        destFile: File,
        startNs: Long,
        currentDownloadedBytes: Long,
        currentTotalBytes: Long
    ): ChunkDownloadResult {
        val chunkRequest = YouTubeGoogleVideoRangeSupport.buildChunkedRequest(
            request = request,
            start = start,
            length = requestedChunkLength
        )

        client.newCall(chunkRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw ChunkRequestIOException(response.code, "HTTP ${response.code}")
            }

            val responseHeaders = response.headers.toMultimap()
            var downloadedBytes = currentDownloadedBytes
            var totalBytes = YouTubeGoogleVideoRangeSupport.resolveTotalContentLength(
                uri = request.url.toString().toUri(),
                headers = responseHeaders
            ) ?: currentTotalBytes
            val actualChunkLength = YouTubeGoogleVideoRangeSupport.resolveChunkResponseLength(
                requestedLength = requestedChunkLength,
                headers = responseHeaders,
                delegateOpenLength = response.body.contentLength()
            )

            val source = response.body.source()
            val buffer = Buffer()
            var chunkRead = 0L
            while (true) {
                ensureDownloadNotCancelled(songId, songKey, destFile)

                val read = source.read(buffer, DOWNLOAD_READ_BUFFER_BYTES)
                if (read == -1L) {
                    break
                }
                sink.write(buffer, read)
                chunkRead += read
                downloadedBytes += read

                val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0)
                    .coerceAtLeast(0.001)
                val speed = (downloadedBytes / elapsedSec).toLong()
                _progressFlow.value = DownloadProgress(
                    songKey = songKey,
                    songId = songId,
                    fileName = destFile.name,
                    bytesRead = downloadedBytes,
                    totalBytes = totalBytes,
                    speedBytesPerSec = speed
                )
            }

            if (chunkRead <= 0L) {
                return ChunkDownloadResult(
                    requestedChunkLength = requestedChunkLength,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    isEndOfStream = true
                )
            }

            if (totalBytes <= 0L && actualChunkLength < requestedChunkLength) {
                totalBytes = downloadedBytes
            }

            val isEndOfStream = chunkRead < requestedChunkLength || (
                totalBytes in 1..downloadedBytes
            )

            return ChunkDownloadResult(
                requestedChunkLength = requestedChunkLength,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                isEndOfStream = isEndOfStream
            )
        }
    }

    private fun ensureDownloadNotCancelled(
        songId: Long,
        songKey: String,
        destFile: File
    ) {
        if (_isCancelled.value || GlobalDownloadManager.isSongCancelled(songKey)) {
            NPLogger.d(TAG, "下载被取消，停止分块下载: songId=$songId")
            destFile.delete()
            _progressFlow.value = null
            throw java.util.concurrent.CancellationException("Download cancelled")
        }
    }
}



