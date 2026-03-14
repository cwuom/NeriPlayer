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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.LocalSongSupport
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.Request
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

    private val _progressFlow = MutableStateFlow<DownloadProgress?>(null)
    val progressFlow: StateFlow<DownloadProgress?> = _progressFlow
    
    private val _batchProgressFlow = MutableStateFlow<BatchDownloadProgress?>(null)
    val batchProgressFlow: StateFlow<BatchDownloadProgress?> = _batchProgressFlow
    
    // 取消下载控制
    private val _isCancelled = MutableStateFlow(false)
    val isCancelledFlow: StateFlow<Boolean> = _isCancelled

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

                val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
                val resolved = if (isBili) resolveBili(song) else resolveNetease(song.id)
                if (resolved == null) {
                    NPLogger.e(TAG, context.getString(R.string.download_no_url, song.name))
                    return@withContext
                }

                val (url, mime, extGuess) = resolved

                val ext = when {
                    !mime.isNullOrBlank() -> mimeToExt(mime)
                    else -> extFromUrl(url) ?: extGuess
                }

                val baseName = sanitizeFileName("${song.artist} - ${song.name}")
                val fileName = if (ext.isNullOrBlank()) baseName else "$baseName.$ext"

                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                val downloadDir = File(baseDir, "NeriPlayer").apply { mkdirs() }
                val tempFile = File(downloadDir, "$fileName.downloading")
                if (tempFile.exists()) tempFile.delete()

                // 同时下载歌词（所有歌曲都尝试，downloadLyrics内部会判断）
                downloadLyrics(context, song)

                // 封面缓存（使用 baseName 作为关联名，保证扫描可命中）
                try {
                    val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                    val downloadDir = File(baseDir, "NeriPlayer")
                    val coverDir = File(downloadDir, "Covers").apply { mkdirs() }
                    // 优先用网络封面
                    val coverUrl = song.coverUrl
                    if (!coverUrl.isNullOrBlank()) {
                        val coverFile = File(coverDir, "$baseName.jpg")
                        val req = Request.Builder().url(coverUrl).build()
                        val resp = AppContainer.sharedOkHttpClient.newCall(req).execute()
                        if (resp.isSuccessful) {
                            resp.body.byteStream().use { input ->
                                coverFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        resp.close()
                    }
                } catch (_: Exception) {}

                val reqBuilder = Request.Builder().url(url)
                if (isBili) {
                    val cookieMap = AppContainer.biliCookieRepo.getCookiesOnce()
                    val cookieHeader = cookieMap.entries.joinToString("; ") { (k, v) -> "$k=$v" }
                    reqBuilder
                        .header("User-Agent", BILI_UA)
                        .header("Referer", BILI_REFERER)
                        .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
                }

                val request = reqBuilder.build()
                val client = AppContainer.sharedOkHttpClient

                // 貌似很多平台都不支持多线程下载(x  所以采用单线程
                // 传入临时文件
                singleThreadDownload(client, request, tempFile, song.id, song.stableKey())

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
                if (
                    e is java.util.concurrent.CancellationException ||
                        _isCancelled.value ||
                        moe.ouom.neriplayer.core.download.GlobalDownloadManager.isSongCancelled(songKey)
                ) {
                    NPLogger.d(TAG, "下载已取消: ${song.name}")
                    _progressFlow.value = null
                    throw java.util.concurrent.CancellationException("Download cancelled")
                }
                NPLogger.e(TAG, "下载失败: ${song.name}, 错误: ${e.javaClass.simpleName} - ${e.message}", e)
                _progressFlow.value = null
                throw e  // 重新抛出异常，让调用方知道下载失败
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
                    if (moe.ouom.neriplayer.core.download.GlobalDownloadManager.isSongCancelled(song.stableKey())) {
                        NPLogger.d(TAG, "跳过已取消的歌曲: ${song.name}")
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
                            currentSong = song.name,
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
                        moe.ouom.neriplayer.core.download.GlobalDownloadManager.updateTaskStatus(
                            song.stableKey(),
                            moe.ouom.neriplayer.core.download.DownloadStatus.COMPLETED
                        )

                        _batchProgressFlow.value?.let { current ->
                            _batchProgressFlow.value = current.copy(
                                completedSongs = index + 1,
                                currentProgress = null
                            )
                        }
                    } catch (e: java.util.concurrent.CancellationException) {
                        // 下载被取消，继续下一首
                        NPLogger.d(TAG, "歌曲下载被取消: ${song.name}")
                        _batchProgressFlow.value?.let { current ->
                            _batchProgressFlow.value = current.copy(
                                completedSongs = index + 1,
                                currentProgress = null
                            )
                        }
                    } catch (e: Exception) {
                        NPLogger.e(TAG, context.getString(R.string.download_batch_failed_song, song.name, e.message ?: ""), e)
                        // 标记任务失败
                        moe.ouom.neriplayer.core.download.GlobalDownloadManager.updateTaskStatus(
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
                lyricFile.writeText(song.matchedLyric)
                baseNameLyricFile.writeText(song.matchedLyric)
                NPLogger.d(TAG, context.getString(R.string.download_lyrics_matched, song.name))
                // 不要return，继续尝试获取翻译歌词
            }

            // 如果是网易云歌曲，尝试从API获取翻译歌词
            val isFromNetease = !song.album.startsWith("Bilibili")
            if (!isFromNetease) return

            // 如果已经保存了主歌词，只获取翻译歌词
            if (!song.matchedLyric.isNullOrBlank()) {
                try {
                    val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
                    val root = JSONObject(lyrics)
                    if (root.optInt("code") == 200) {
                        val tlyric = root.optJSONObject("tlyric")?.optString("lyric") ?: ""
                        if (tlyric.isNotBlank()) {
                            transLyricFile.writeText(tlyric)
                            baseNameTransLyricFile.writeText(tlyric)
                            NPLogger.d(TAG, context.getString(R.string.download_lyrics_api, song.name))
                        }
                    }
                } catch (e: Exception) {
                    NPLogger.w(TAG, "翻译歌词下载失败: ${song.name} - ${e.message}")
                }
                return
            }

            // 如果没有matchedLyric，从API获取主歌词和翻译歌词
            val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
            val root = JSONObject(lyrics)
            if (root.optInt("code") != 200) return

            val lrc = root.optJSONObject("lrc")?.optString("lyric") ?: ""
            if (lrc.isNotBlank()) {
                lyricFile.writeText(lrc)
                baseNameLyricFile.writeText(lrc)
                NPLogger.d(TAG, "从API获取歌词保存: ${song.name}")
            }

            // 同时保存翻译歌词
            val tlyric = root.optJSONObject("tlyric")?.optString("lyric") ?: ""
            if (tlyric.isNotBlank()) {
                transLyricFile.writeText(tlyric)
                baseNameTransLyricFile.writeText(tlyric)
                NPLogger.d(TAG, "从API获取翻译歌词保存: ${song.name}")
            }
        } catch (e: Exception) {
            NPLogger.w(TAG, "歌词下载失败: ${song.name} - ${e.message}")
        }
    }

    /** 获取本地音频文件路径 */
fun getLocalFilePath(context: Context, song: SongItem): String? {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val downloadDir = File(baseDir, "NeriPlayer")
        if (!downloadDir.exists()) return null
        
        // 查找可能的文件扩展名
        val possibleExtensions = listOf("flac", "m4a", "mp3", "eac3", "aac", "wav", "ogg")
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

    private fun candidateBaseNames(song: SongItem): List<String> {
        val baseNames = linkedSetOf<String>()
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
    private suspend fun resolveNetease(songId: Long): Triple<String, String?, String?>? {
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
            Triple(ensureHttps(url), mime, type.lowercase())
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

    private fun tryWeapiFallback(songId: Long, level: String): Triple<String, String?, String?>? {
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
            Triple(finalUrl, mime, ext)
        } catch (_: Exception) { null }
    }

    // Resolve Bili audio direct url.
    private suspend fun resolveBili(song: SongItem): Triple<String, String?, String?>? {
        val resolved = resolveBiliSong(song, AppContainer.biliClient) ?: return null
        val chosen: BiliAudioStreamInfo? = AppContainer.biliPlaybackRepository
            .getBestPlayableAudio(resolved.videoInfo.bvid, resolved.cid)
        val url = chosen?.url ?: return null
        val mime = chosen.mimeType
        val ext = mimeToExt(mime)
        return Triple(url, mime, ext)
    }

    private fun sanitizeFileName(name: String): String {
        val n = Normalizer.normalize(name, Normalizer.Form.NFKD)
        return n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio" }
    }

    private fun buildLyricFiles(context: Context, song: SongItem): LyricFiles {
        val lyricsDir = getLyricsDirectory(context)
        val baseName = sanitizeFileName("${song.artist} - ${song.name}")
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

    /** 单线程下载 */
    private suspend fun singleThreadDownload(
        client: okhttp3.OkHttpClient,
        request: Request,
        destFile: File,
        songId: Long,
        songKey: String
    ) = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始下载文件: ${destFile.name}, songId=$songId")
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")

            val total = resp.body.contentLength()
            NPLogger.d(TAG, "文件总大小: ${total} bytes, songId=$songId")
            val source = resp.body.source()
            destFile.sink().buffer().use { sink ->
                var readSoFar = 0L
                val buffer = okio.Buffer()
                while (true) {
                    // 检查是否被取消(全局取消或单个任务取消)
                    if (_isCancelled.value || moe.ouom.neriplayer.core.download.GlobalDownloadManager.isSongCancelled(songKey)) {
                        NPLogger.d(TAG, "下载被取消，停止下载: songId=$songId")
                        destFile.delete() // 删除临时文件
                        _progressFlow.value = null
                        throw java.util.concurrent.CancellationException("Download cancelled")
                    }

                    val read = source.read(buffer, 8L * 1024L)
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
}



