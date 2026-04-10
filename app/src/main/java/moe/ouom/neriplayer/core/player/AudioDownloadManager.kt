@file:Suppress("SpellCheckingInspection")

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
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.clearSongCancelled
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * 音频下载管理器：解析来源（网易云 / Bilibili）并保存到本地目录
 * - 不依赖系统 DownloadManager，直接用共享 OkHttpClient，实现自定义 Header 与代理
 * - 默认保存路径：/Android/data/<package>/files/Music/NeriPlayer/<Artist - Title>.<ext>
 * - 支持通过 SAF 将下载目录切换到自定义文件夹
 */
object AudioDownloadManager {

    private const val TAG = "NERI-Downloader"
    private const val BILI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val BILI_REFERER = "https://www.bilibili.com"
    private const val MAX_CONCURRENT_DOWNLOADS = 2
    private const val PROGRESS_EMIT_INTERVAL_NS = 180_000_000L
    private const val PROGRESS_EMIT_MIN_BYTES_DELTA = 256L * 1024L

    private fun canBlockStorageLookup(): Boolean {
        return Looper.myLooper() != Looper.getMainLooper()
    }
    private const val DOWNLOAD_READ_BUFFER_BYTES = 64L * 1024L
    private const val YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES = 4L * 1024L * 1024L

    private val _progressFlow = MutableStateFlow<DownloadProgress?>(null)
    val progressFlow: StateFlow<DownloadProgress?> = _progressFlow
    
    private val _batchProgressFlow = MutableStateFlow<BatchDownloadProgress?>(null)
    val batchProgressFlow: StateFlow<BatchDownloadProgress?> = _batchProgressFlow
    
    // 取消下载控制
    private val _isCancelled = MutableStateFlow(false)
    val isCancelledFlow: StateFlow<Boolean> = _isCancelled
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val progressPublishLock = Any()
    private val lastPublishedProgressBySongKey = mutableMapOf<String, PublishedProgressState>()
    private val completedSidecarReferencesBySongKey =
        ConcurrentHashMap<String, DownloadedSidecarReferences>()
    private val partialSidecarReferencesBySongKey =
        ConcurrentHashMap<String, DownloadedSidecarReferences>()

    private data class ResolvedDownloadSource(
        val url: String,
        val mimeType: String? = null,
        val fileExtensionHint: String? = null,
        val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
        val contentLength: Long? = null,
        val durationMs: Long? = null
    )

    enum class DownloadStage {
        TRANSFERRING,
        FINALIZING
    }

    data class DownloadProgress(
        val songKey: String,
        val songId: Long,
        val fileName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val stage: DownloadStage = DownloadStage.TRANSFERRING
    ) {
        val percentage: Int
            get() = when {
                stage == DownloadStage.FINALIZING -> 100
                totalBytes <= 0L -> -1
                bytesRead >= totalBytes -> 100
                else -> ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 99)
            }
    }

    data class BatchDownloadProgress(
        val totalSongs: Int,
        val completedSongs: Int,
        val currentSong: String,
        val currentProgress: DownloadProgress?,
        val currentSongIndex: Int = 0,
        val aggregateProgressFraction: Float? = null
    ) {
        val percentage: Int get() = if (totalSongs > 0) {
            aggregateProgressFraction?.let { progressFraction ->
                if (completedSongs >= totalSongs) {
                    100
                } else {
                    (progressFraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 99)
                }
            } ?: run {
                val baseProgress = (completedSongs * 100.0 / totalSongs)
                val currentSongProgress = currentProgress?.let { progress ->
                    if (progress.totalBytes > 0) {
                        (progress.bytesRead.toDouble() / progress.totalBytes) / totalSongs
                    } else 0.0
                } ?: 0.0
                if (completedSongs >= totalSongs) {
                    100
                } else {
                    (baseProgress + currentSongProgress * 100).toInt().coerceIn(0, 99)
                }
            }
        } else 0
    }

    private data class PublishedProgressState(
        val bytesRead: Long,
        val totalBytes: Long,
        val percentage: Int,
        val stage: DownloadStage,
        val emittedAtNs: Long
    )

    internal data class DownloadedSidecarReferences(
        val coverReference: String? = null,
        val lyricReference: String? = null,
        val translatedLyricReference: String? = null
    ) {
        val isEmpty: Boolean
            get() = coverReference.isNullOrBlank() &&
                lyricReference.isNullOrBlank() &&
                translatedLyricReference.isNullOrBlank()
    }

    private fun publishProgress(
        progress: DownloadProgress,
        force: Boolean = false
    ) {
        val nowNs = System.nanoTime()
        val shouldEmit = synchronized(progressPublishLock) {
            val previous = lastPublishedProgressBySongKey[progress.songKey]
            val bytesDelta = previous?.let { published ->
                val delta = progress.bytesRead - published.bytesRead
                if (delta >= 0L) delta else -delta
            } ?: Long.MAX_VALUE
            val enoughTimeElapsed = previous == null || nowNs - previous.emittedAtNs >= PROGRESS_EMIT_INTERVAL_NS
            val completedTransfer = progress.stage != DownloadStage.TRANSFERRING ||
                (progress.totalBytes > 0L && progress.bytesRead >= progress.totalBytes)
            val shouldPublishNow = force ||
                previous == null ||
                progress.stage != previous.stage ||
                completedTransfer ||
                (enoughTimeElapsed && (
                    progress.percentage != previous.percentage ||
                        progress.totalBytes != previous.totalBytes ||
                        bytesDelta >= PROGRESS_EMIT_MIN_BYTES_DELTA
                    ))

            if (shouldPublishNow) {
                lastPublishedProgressBySongKey[progress.songKey] = PublishedProgressState(
                    bytesRead = progress.bytesRead,
                    totalBytes = progress.totalBytes,
                    percentage = progress.percentage,
                    stage = progress.stage,
                    emittedAtNs = nowNs
                )
            }
            shouldPublishNow
        }

        if (shouldEmit) {
            _progressFlow.value = progress
        }
    }

    private fun clearPublishedProgress(songKey: String) {
        synchronized(progressPublishLock) {
            lastPublishedProgressBySongKey.remove(songKey)
        }
    }

    private fun clearAllPublishedProgress() {
        synchronized(progressPublishLock) {
            lastPublishedProgressBySongKey.clear()
        }
    }

    internal fun consumeCompletedSidecarReferences(
        songKey: String
    ): DownloadedSidecarReferences? {
        return completedSidecarReferencesBySongKey.remove(songKey)
    }

    internal fun consumePartialSidecarReferences(
        songKey: String
    ): DownloadedSidecarReferences? {
        return partialSidecarReferencesBySongKey.remove(songKey)
    }

    private fun rememberCompletedSidecarReferences(
        songKey: String,
        sidecarReferences: DownloadedSidecarReferences
    ) {
        if (sidecarReferences.isEmpty) {
            completedSidecarReferencesBySongKey.remove(songKey)
            return
        }
        completedSidecarReferencesBySongKey[songKey] = sidecarReferences
    }

    private fun rememberPartialSidecarReferences(
        songKey: String,
        sidecarReferences: DownloadedSidecarReferences
    ) {
        if (sidecarReferences.isEmpty) {
            return
        }
        partialSidecarReferencesBySongKey.compute(songKey) { _, existing ->
            mergeDownloadedSidecarReferences(existing, sidecarReferences)
                .takeUnless(DownloadedSidecarReferences::isEmpty)
        }
    }

    private fun clearCompletedSidecarReferences(songKey: String) {
        completedSidecarReferencesBySongKey.remove(songKey)
    }

    private fun clearPartialSidecarReferences(songKey: String) {
        partialSidecarReferencesBySongKey.remove(songKey)
    }

    internal fun mergeDownloadedSidecarReferences(
        existing: DownloadedSidecarReferences?,
        incoming: DownloadedSidecarReferences?
    ): DownloadedSidecarReferences {
        return DownloadedSidecarReferences(
            coverReference = incoming?.coverReference ?: existing?.coverReference,
            lyricReference = incoming?.lyricReference ?: existing?.lyricReference,
            translatedLyricReference = incoming?.translatedLyricReference
                ?: existing?.translatedLyricReference
        )
    }

    private fun publishFinalizingProgress(
        songId: Long,
        songKey: String,
        fileName: String,
        bytesRead: Long,
        totalBytes: Long
    ) {
        publishProgress(
            DownloadProgress(
                songKey = songKey,
                songId = songId,
                fileName = fileName,
                bytesRead = bytesRead,
                totalBytes = totalBytes,
                speedBytesPerSec = 0L,
                stage = DownloadStage.FINALIZING
            ),
            force = true
        )
    }

    private fun ensureSongDownloadNotCancelled(
        songKey: String,
        stage: String
    ) {
        if (!_isCancelled.value && !GlobalDownloadManager.isSongCancelled(songKey)) {
            return
        }
        NPLogger.d(TAG, "检测到下载取消: songKey=$songKey, stage=$stage")
        _progressFlow.value = null
        throw java.util.concurrent.CancellationException("Download cancelled during $stage")
    }

    suspend fun downloadSong(context: Context, song: SongItem) {
        downloadSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                val songKey = song.stableKey()
                var storedAudio: ManagedDownloadStorage.StoredEntry? = null
                clearCompletedSidecarReferences(songKey)
                clearPartialSidecarReferences(songKey)
                try {
                    // 检查文件是否已存在
                    if (LocalSongSupport.isLocalSong(song, context)) {
                        NPLogger.d(TAG, "Skip local song download: ${song.name}")
                        _progressFlow.value = null
                        return@withContext
                    }

                    if (ManagedDownloadStorage.findDownloadedAudio(context, song) != null) {
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

                    val baseName = ManagedDownloadStorage.buildDisplayBaseName(song)
                    val fileName = if (ext.isNullOrBlank()) baseName else "$baseName.$ext"

                    val tempFile = ManagedDownloadStorage.createWorkingFile(context, fileName)
                    if (tempFile.exists()) tempFile.delete()

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

                    val transferredBytes = tempFile.length().coerceAtLeast(0L)
                    publishFinalizingProgress(
                        songId = workingSong.id,
                        songKey = workingSong.stableKey(),
                        fileName = fileName,
                        bytesRead = transferredBytes,
                        totalBytes = transferredBytes
                    )
                    ensureSongDownloadNotCancelled(songKey, "audio_commit")
                    storedAudio = ManagedDownloadStorage.saveAudioFromTemp(
                        context = context,
                        fileName = fileName,
                        tempFile = tempFile,
                        mimeType = mime
                    )
                    ensureSongDownloadNotCancelled(songKey, "audio_committed")
                    publishFinalizingProgress(
                        songId = workingSong.id,
                        songKey = workingSong.stableKey(),
                        fileName = storedAudio.name,
                        bytesRead = transferredBytes,
                        totalBytes = transferredBytes
                    )
                    NPLogger.d(
                        TAG,
                        "音频落盘完成，开始写入 sidecar: song=${song.name}, audioFile=${storedAudio.name}, baseName=${storedAudio.nameWithoutExtension}"
                    )
                    val sidecarReferences = downloadSidecars(
                        context = context,
                        song = song,
                        songKey = songKey,
                        baseName = storedAudio.nameWithoutExtension,
                        storedAudio = storedAudio
                    )
                    ensureSongDownloadNotCancelled(songKey, "sidecar_completed")
                    rememberCompletedSidecarReferences(songKey, sidecarReferences)

                    _progressFlow.value = null
                    try {
                        context.contentResolver.openInputStream(storedAudio.playbackUri.toUri())?.close()
                    } catch (_: Exception) { }
                    clearPartialSidecarReferences(songKey)

                } catch (e: Exception) {
                    if (
                        e is java.util.concurrent.CancellationException ||
                            _isCancelled.value ||
                            GlobalDownloadManager.isSongCancelled(songKey)
                    ) {
                        val partialSidecarReferences = consumePartialSidecarReferences(songKey)
                        NPLogger.d(TAG, "下载已取消: ${song.name}")
                        if (storedAudio != null || !(partialSidecarReferences?.isEmpty ?: true)) {
                            runCatching {
                                NPLogger.d(
                                    TAG,
                                    "下载取消后回滚半成品: song=${song.name}, audio=${storedAudio?.reference}, sidecars=$partialSidecarReferences"
                                )
                                GlobalDownloadManager.rollbackCancelledDownload(
                                    context = context,
                                    song = song,
                                    storedAudio = storedAudio,
                                    sidecarReferences = partialSidecarReferences
                                )
                            }.onFailure { rollbackError ->
                                NPLogger.e(
                                    TAG,
                                    "回滚已取消下载失败: ${song.name}, ${rollbackError.message}",
                                    rollbackError
                                )
                            }
                        }
                        _progressFlow.value = null
                        clearSongCancelled(songKey)
                        clearCompletedSidecarReferences(songKey)
                        clearPartialSidecarReferences(songKey)
                        throw java.util.concurrent.CancellationException("Download cancelled")
                    }
                    NPLogger.e(TAG, "下载失败: ${song.name}, 错误: ${e.javaClass.simpleName} - ${e.message}", e)
                    _progressFlow.value = null
                    clearCompletedSidecarReferences(songKey)
                    clearPartialSidecarReferences(songKey)
                    throw e  // 重新抛出异常，让调用方知道下载失败
                } finally {
                    clearPublishedProgress(songKey)
                }
            }
        }
    }

    private suspend fun downloadSidecars(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ): DownloadedSidecarReferences {
        ensureSongDownloadNotCancelled(songKey, "sidecar_prepare")
        return coroutineScope {
            val lyricJob = async {
                downloadLyrics(context, song, songKey, baseName)
            }
            val coverJob = async {
                cacheCover(context, song, songKey, baseName, storedAudio)
            }
            val lyricReferences = lyricJob.await()
            val coverReference = coverJob.await()
            DownloadedSidecarReferences(
                coverReference = coverReference,
                lyricReference = lyricReferences.lyricReference,
                translatedLyricReference = lyricReferences.translatedLyricReference
            )
        }
    }

    private suspend fun cacheCover(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ): String? {
        val coverUrl = song.displayCoverUrl()
        if (coverUrl.isNullOrBlank()) {
            return null
        }

        val existingCover = ManagedDownloadStorage.findCoverReference(context, storedAudio)
        if (!existingCover.isNullOrBlank()) {
            rememberPartialSidecarReferences(
                songKey,
                DownloadedSidecarReferences(coverReference = existingCover)
            )
            return existingCover
        }

        val req = Request.Builder().url(coverUrl).build()
        var committedCoverReference: String? = null
        try {
            ensureSongDownloadNotCancelled(songKey, "cover_request")
            AppContainer.sharedOkHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use
                }
                val body = response.body ?: return@use
                val contentType = body.contentType()?.toString().orEmpty()
                if (contentType.isNotBlank() && !contentType.startsWith("image/", ignoreCase = true)) {
                    throw IOException("封面响应不是图片: $contentType")
                }
                val expectedLength = body.contentLength()
                val bytes = body.bytes()
                ensureSongDownloadNotCancelled(songKey, "cover_downloaded")
                val copiedBytes = bytes.size.toLong()
                if (copiedBytes <= 0L) {
                    throw IOException("封面写入为空")
                }
                if (expectedLength > 0L && copiedBytes < expectedLength) {
                    throw IOException("封面写入不完整: $copiedBytes/$expectedLength")
                }
                if (!isUsableCoverBytes(bytes)) {
                    throw IOException("封面文件校验失败")
                }
                val tempFile = ManagedDownloadStorage.createWorkingFile(context, "$baseName.jpg")
                try {
                    tempFile.writeBytes(bytes)
                    ensureSongDownloadNotCancelled(songKey, "cover_commit")
                    committedCoverReference = ManagedDownloadStorage.commitCoverFile(
                        context = context,
                        tempFile = tempFile,
                        fileName = "$baseName.jpg",
                        mimeType = contentType.takeIf { it.isNotBlank() }
                    )?.reference
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }
        } catch (cancellation: java.util.concurrent.CancellationException) {
            NPLogger.d(TAG, "封面整理阶段收到取消: ${song.name}")
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(TAG, "封面后台下载失败: ${song.name} - ${error.message}")
        }
        committedCoverReference?.let { reference ->
            rememberPartialSidecarReferences(
                songKey,
                DownloadedSidecarReferences(coverReference = reference)
            )
            NPLogger.d(TAG, "封面写入完成: song=${song.name}, reference=$reference")
        }
        return committedCoverReference
    }

    private fun isUsableCoverBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
    }

    /** 批量下载歌单中的所有歌曲 */
    suspend fun downloadPlaylist(
        context: Context,
        songs: List<SongItem>,
        maxConcurrentDownloads: Int = MAX_CONCURRENT_DOWNLOADS,
        onSongStarted: suspend (SongItem) -> Unit = {},
        onSongCompleted: suspend (SongItem) -> Unit = {},
        onSongFailed: suspend (SongItem, Throwable) -> Unit = { _, _ -> },
        onSongCancelled: suspend (SongItem) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            try {
                val remoteSongs = songs.filterNot { LocalSongSupport.isLocalSong(it, context) }
                if (remoteSongs.isEmpty()) {
                    NPLogger.d(TAG, "Skip batch download because all songs are local")
                    _batchProgressFlow.value = null
                    return@withContext
                }

                val trackedSongs = remoteSongs.mapIndexed { index, song ->
                    TrackedBatchSong(
                        song = song,
                        index = index
                    )
                }
                val trackedSongByKey = trackedSongs.associateBy { it.song.stableKey() }
                val progressMutex = Mutex()
                val latestProgressBySongKey = mutableMapOf<String, DownloadProgress>()
                var completedSongs = 0
                var currentSongLabel = ""
                var currentSongIndex = 0

                suspend fun publishBatchProgress() {
                    progressMutex.withLock {
                        val leadingEntry = latestProgressBySongKey.entries
                            .minByOrNull { entry -> trackedSongByKey.getValue(entry.key).index }
                        if (leadingEntry != null) {
                            val trackedSong = trackedSongByKey.getValue(leadingEntry.key)
                            currentSongLabel = trackedSong.song.displayName()
                            currentSongIndex = trackedSong.index
                        }
                        val aggregateProgressFraction = if (trackedSongs.isEmpty()) {
                            1.0
                        } else {
                            (
                                completedSongs.toDouble() +
                                    latestProgressBySongKey.values.sumOf { progress ->
                                        if (progress.totalBytes > 0L) {
                                            progress.bytesRead.toDouble() / progress.totalBytes.toDouble()
                                        } else {
                                            0.0
                                        }
                                    }
                                ) / trackedSongs.size.toDouble()
                        }.coerceIn(0.0, 1.0).toFloat()

                        _batchProgressFlow.value = BatchDownloadProgress(
                            totalSongs = trackedSongs.size,
                            completedSongs = completedSongs,
                            currentSong = currentSongLabel,
                            currentProgress = leadingEntry?.value,
                            currentSongIndex = currentSongIndex,
                            aggregateProgressFraction = aggregateProgressFraction
                        )
                    }
                }

                suspend fun markSongStarted(trackedSong: TrackedBatchSong) {
                    progressMutex.withLock {
                        currentSongLabel = trackedSong.song.displayName()
                        currentSongIndex = trackedSong.index
                    }
                    publishBatchProgress()
                }

                suspend fun markSongFinished(songKey: String) {
                    progressMutex.withLock {
                        latestProgressBySongKey.remove(songKey)
                        completedSongs++
                    }
                    publishBatchProgress()
                }

                _isCancelled.value = false
                _batchProgressFlow.value = BatchDownloadProgress(
                    totalSongs = trackedSongs.size,
                    completedSongs = 0,
                    currentSong = "",
                    currentProgress = null,
                    aggregateProgressFraction = 0f
                )
                val batchSemaphore = Semaphore(maxConcurrentDownloads.coerceIn(1, MAX_CONCURRENT_DOWNLOADS))

                val progressJob = launch {
                    _progressFlow.collect { progress ->
                        if (progress == null) {
                            return@collect
                        }
                        if (!trackedSongByKey.containsKey(progress.songKey)) {
                            return@collect
                        }
                        progressMutex.withLock {
                            latestProgressBySongKey[progress.songKey] = progress
                        }
                        publishBatchProgress()
                    }
                }

                try {
                    coroutineScope {
                        trackedSongs.map { trackedSong ->
                            launch {
                                batchSemaphore.withPermit {
                                    val song = trackedSong.song
                                    val songKey = song.stableKey()
                                    if (_isCancelled.value) {
                                        NPLogger.d(TAG, context.getString(R.string.download_cancelled_message))
                                        markSongFinished(songKey)
                                        invokeBatchCallback(song) { onSongCancelled(song) }
                                        return@withPermit
                                    }
                                    if (GlobalDownloadManager.isSongCancelled(songKey)) {
                                        NPLogger.d(TAG, "跳过已取消的歌曲: ${song.name}")
                                        clearSongCancelled(songKey)
                                        markSongFinished(songKey)
                                        invokeBatchCallback(song) { onSongCancelled(song) }
                                        return@withPermit
                                    }

                                    try {
                                        markSongStarted(trackedSong)
                                        invokeBatchCallback(song) { onSongStarted(song) }
                                        downloadSong(context, song)
                                        invokeBatchCallback(song) { onSongCompleted(song) }
                                    } catch (_: java.util.concurrent.CancellationException) {
                                        NPLogger.d(TAG, "歌曲下载被取消: ${song.name}")
                                        clearSongCancelled(songKey)
                                        invokeBatchCallback(song) { onSongCancelled(song) }
                                    } catch (e: Exception) {
                                        NPLogger.e(
                                            TAG,
                                            context.getString(
                                                R.string.download_batch_failed_song,
                                                song.name,
                                                e.message ?: ""
                                            ),
                                            e
                                        )
                                        invokeBatchCallback(song) { onSongFailed(song, e) }
                                    } finally {
                                        markSongFinished(songKey)
                                    }
                                }
                            }
                        }.joinAll()
                    }
                } finally {
                    progressJob.cancel()
                }

                _batchProgressFlow.value = null
            } catch (e: Exception) {
                NPLogger.e(TAG, context.getString(R.string.download_batch_failed, e.message ?: ""), e)
                _batchProgressFlow.value = null
            }
        }
    }

    private data class TrackedBatchSong(
        val song: SongItem,
        val index: Int
    )

    private suspend fun invokeBatchCallback(
        song: SongItem,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (callbackError: Exception) {
            NPLogger.e(TAG, "批量下载回调失败: ${song.name}", callbackError)
        }
    }
    
    /** 取消下载 */
    fun cancelDownload() {
        _isCancelled.value = true
        _progressFlow.value = null
        _batchProgressFlow.value = null
        clearAllPublishedProgress()
    }

    /** 重置取消标志 */
    fun resetCancelFlag() {
        _isCancelled.value = false
    }

    /** 下载歌词文件 */
    private suspend fun downloadLyrics(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String
    ): DownloadedSidecarReferences {
        var lyricReference: String? = null
        var translatedLyricReference: String? = null
        try {
            ensureSongDownloadNotCancelled(songKey, "lyrics_prepare")
            var lyricText = song.matchedLyric?.takeIf { it.isNotBlank() }
            var translatedText: String? = null
            if (lyricText != null) {
                NPLogger.d(TAG, context.getString(R.string.download_lyrics_matched, song.name))
            }
            val isYouTubeMusic = isYouTubeMusicSong(song)
            val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)

            when {
                isYouTubeMusic -> {
                    if (lyricText == null) {
                        lyricText = downloadYouTubeMusicLyrics(song)
                    }
                }
                isBili -> { /* B站暂无歌词源 */ }
                else -> {
                    val downloaded = downloadNeteaseLyrics(song)
                    if (lyricText == null) {
                        lyricText = downloaded.lyricText
                    }
                    translatedText = downloaded.translatedText
                }
            }

            ensureSongDownloadNotCancelled(songKey, "lyrics_resolved")
            lyricText?.takeIf { it.isNotBlank() }?.let { lyric ->
                lyricReference = writeManagedLyrics(
                    context = context,
                    song = song,
                    baseName = baseName,
                    content = lyric,
                    translated = false
                )
                lyricReference?.let { reference ->
                    rememberPartialSidecarReferences(
                        songKey,
                        DownloadedSidecarReferences(lyricReference = reference)
                    )
                    NPLogger.d(TAG, "歌词写入完成: song=${song.name}, reference=$reference")
                }
            }
            ensureSongDownloadNotCancelled(songKey, "lyrics_primary_written")
            translatedText?.takeIf { it.isNotBlank() }?.let { lyric ->
                translatedLyricReference = writeManagedLyrics(
                    context = context,
                    song = song,
                    baseName = baseName,
                    content = lyric,
                    translated = true
                )
                translatedLyricReference?.let { reference ->
                    rememberPartialSidecarReferences(
                        songKey,
                        DownloadedSidecarReferences(translatedLyricReference = reference)
                    )
                    NPLogger.d(TAG, "翻译歌词写入完成: song=${song.name}, reference=$reference")
                }
            }
        } catch (cancellation: java.util.concurrent.CancellationException) {
            NPLogger.d(TAG, "歌词整理阶段收到取消: ${song.name}")
            throw cancellation
        } catch (e: Exception) {
            NPLogger.w(TAG, "歌词下载失败: ${song.name} - ${e.message}")
        }
        return DownloadedSidecarReferences(
            lyricReference = lyricReference,
            translatedLyricReference = translatedLyricReference
        )
    }

    /** 从 LRCLIB / YouTube Music API 获取歌词并保存 */
    private suspend fun downloadYouTubeMusicLyrics(
        song: SongItem
    ): String? {
        if (!song.matchedLyric.isNullOrBlank()) return null
        try {
            val lrcLibResult = try {
                val durationSec = (song.durationMs / 1000).toInt()
                AppContainer.lrcLibClient.getLyrics(
                    trackName = song.name,
                    artistName = song.artist,
                    durationSeconds = durationSec
                ) ?: AppContainer.lrcLibClient.searchLyrics("${song.name} ${song.artist}")
            } catch (_: Exception) { null }

            val syncedLyrics = lrcLibResult?.syncedLyrics?.takeIf { it.isNotBlank() }
            val plainLyrics = lrcLibResult?.plainLyrics?.takeIf { it.isNotBlank() }

            when {
                syncedLyrics != null -> {
                    NPLogger.d(TAG, "LRCLIB 同步歌词保存: ${song.name}")
                    return syncedLyrics
                }
                plainLyrics != null -> {
                    NPLogger.d(TAG, "LRCLIB 纯文本歌词保存: ${song.name}")
                    return plainLyrics
                }
            }

            // 回退 YouTube Music API
            val videoId = extractYouTubeMusicVideoId(song.mediaUri) ?: return null
            val ytResult = AppContainer.youtubeMusicClient.getLyrics(videoId) ?: return null
            val lyricsText = ytResult.lyrics.takeIf { it.isNotBlank() } ?: return null
            NPLogger.d(TAG, "YouTube Music API 歌词保存: ${song.name}")
            return lyricsText
        } catch (e: Exception) {
            NPLogger.w(TAG, "YouTube Music 歌词下载失败: ${song.name} - ${e.message}")
        }
        return null
    }

    /** 从网易云 API 获取歌词并保存 */
    private fun downloadNeteaseLyrics(
        song: SongItem
    ): DownloadedLyrics {
        if (!song.matchedLyric.isNullOrBlank()) {
            try {
                val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
                val root = JSONObject(lyrics)
                if (root.optInt("code") == 200) {
                    val tlyric = root.optJSONObject("tlyric")?.optString("lyric") ?: ""
                    if (tlyric.isNotBlank()) {
                        NPLogger.d(TAG, "翻译歌词保存: ${song.name}")
                        return DownloadedLyrics(translatedText = tlyric)
                    }
                }
            } catch (e: Exception) {
                NPLogger.w(TAG, "翻译歌词下载失败: ${song.name} - ${e.message}")
            }
            return DownloadedLyrics()
        }

        try {
            val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
            val root = JSONObject(lyrics)
            if (root.optInt("code") != 200) return DownloadedLyrics()

            val yrc = root.optJSONObject("yrc")?.optString("lyric") ?: ""
            val lrc = root.optJSONObject("lrc")?.optString("lyric") ?: ""
            val translated = root.optJSONObject("tlyric")?.optString("lyric") ?: ""
            val preferredLyric = yrc.takeIf { it.isNotBlank() } ?: lrc.takeIf { it.isNotBlank() }
            if (yrc.isNotBlank()) {
                NPLogger.d(TAG, "从API获取逐字歌词保存: ${song.name}")
            }
            if (lrc.isNotBlank()) {
                NPLogger.d(TAG, "从API获取歌词保存: ${song.name}")
            }
            if (translated.isNotBlank()) {
                NPLogger.d(TAG, "从API获取翻译歌词保存: ${song.name}")
            }
            return DownloadedLyrics(
                lyricText = preferredLyric,
                translatedText = translated.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            NPLogger.w(TAG, "网易云歌词下载失败: ${song.name} - ${e.message}")
        }
        return DownloadedLyrics()
    }

    private fun writeManagedLyrics(
        context: Context,
        song: SongItem,
        baseName: String,
        content: String,
        translated: Boolean
    ): String? {
        return ManagedDownloadStorage.writeLyrics(
            context = context,
            songId = song.id,
            baseName = baseName,
            content = content,
            translated = translated
        )
    }

    fun getLocalPlaybackUri(context: Context, song: SongItem): String? {
        ManagedDownloadStorage.peekDownloadedAudio(song)?.let { return it.playbackUri }
        if (!canBlockStorageLookup()) return null
        if (!ManagedDownloadStorage.ensureSnapshotCacheReady(context)) return null
        return ManagedDownloadStorage.findAudio(context, song)?.playbackUri
    }

    fun hasLocalDownload(context: Context, song: SongItem): Boolean {
        if (GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return true
        }
        if (ManagedDownloadStorage.peekDownloadedAudio(song) != null) {
            return true
        }
        if (!canBlockStorageLookup()) {
            return false
        }
        if (!ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
            return false
        }
        return ManagedDownloadStorage.hasDownloadedAudio(context, song)
    }

    /** 解析下载歌曲对应的本地封面，供离线 UI 兜底使用 */
    fun getLocalCoverUri(context: Context, song: SongItem): String? {
        val allowBlockingLookup = canBlockStorageLookup()
        val localAudio = ManagedDownloadStorage.peekDownloadedAudio(song)
            ?: if (allowBlockingLookup && ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                ManagedDownloadStorage.findAudio(context, song)
            } else {
                null
            }
        val coverReference = localAudio?.let {
            ManagedDownloadStorage.peekCoverReference(it)
                ?: if (allowBlockingLookup && ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                    runBlocking(Dispatchers.IO) {
                        ManagedDownloadStorage.findCoverReference(context, it)
                    }
                } else {
                    null
                }
        }
        if (!coverReference.isNullOrBlank()) {
            return ManagedDownloadStorage.toPlayableUri(coverReference) ?: coverReference
        }

        if (localAudio != null || !allowBlockingLookup) {
            return null
        }

        val localAudioUri = song.localFilePath
            ?.takeIf { File(it).exists() }
            ?.let { Uri.fromFile(File(it)) }
            ?: song.mediaUri?.takeIf { it.isNotBlank() }?.toUri()
            ?: return null
        return runCatching {
            LocalMediaSupport.inspect(context, localAudioUri).coverUri
        }.getOrElse {
            NPLogger.w(TAG, "resolve local cover fallback failed: ${it.message}")
            null
        }
    }

    private fun candidateBaseNames(song: SongItem): List<String> {
        return ManagedDownloadStorage.buildCandidateBaseNames(song)
    }

    fun getLyricContent(context: Context, song: SongItem): String? {
        return ManagedDownloadStorage.readLyrics(context, song, translated = false)
    }

    fun getTranslatedLyricContent(context: Context, song: SongItem): String? {
        return ManagedDownloadStorage.readLyrics(context, song, translated = true)
    }

    // 解析网易云直链
    private suspend fun resolveNetease(songId: Long): ResolvedDownloadSource? {
        val quality = try { AppContainer.settingsRepo.audioQualityFlow.first() } catch (_: Exception) { "exhigh" }
        val raw = AppContainer.neteaseClient.getSongDownloadUrl(songId, level = quality)
        return try {
            val root = JSONObject(raw)
            if (root.optInt("code") != 200) return tryWeapiFallback(songId, quality)
            val data = NeteasePlaybackResponseParser.parseDownloadInfo(raw)
                ?: return tryWeapiFallback(songId, quality)
            val url = data.url
            val type = data.type.orEmpty() // e.g., mp3/flac
            val mime = guessMimeFromUrl(url)
            ResolvedDownloadSource(
                url = ensureHttps(url),
                mimeType = mime,
                fileExtensionHint = type.lowercase().ifBlank { extFromUrl(url) }
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
            val data = NeteasePlaybackResponseParser.parseDownloadInfo(raw) ?: return null
            val url = data.url
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

    private data class DownloadedLyrics(
        val lyricText: String? = null,
        val translatedText: String? = null
    )

    private fun ensureHttps(url: String): String = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url

    private fun mimeToExt(mime: String): String? = when (mime.lowercase()) {
        "audio/flac" -> "flac"
        "audio/x-flac" -> "flac"
        "audio/eac3", "audio/e-ac-3" -> "eac3"
        "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
        "video/mp4" -> "mp4"
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
                publishProgress(
                    DownloadProgress(
                        songKey = songKey,
                        songId = songId,
                        fileName = destFile.name,
                        bytesRead = downloadedBytes,
                        totalBytes = totalBytesHint,
                        speedBytesPerSec = (downloadedBytes / elapsedSec).toLong()
                    )
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
                    publishProgress(progress)
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
                publishProgress(
                    DownloadProgress(
                        songKey = songKey,
                        songId = songId,
                        fileName = destFile.name,
                        bytesRead = downloadedBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speed
                    )
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



