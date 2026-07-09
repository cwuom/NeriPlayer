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
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.clearSongCancelled
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.shouldUseIndexedSidecarLookup
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.TrafficUsageSource
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.currentTrafficNetworkType
import moe.ouom.neriplayer.util.hasLikelyInternetAccess
import okhttp3.Dispatcher
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLConnection
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.LazyThreadSafetyMode

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
    internal const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = DEFAULT_DOWNLOAD_PARALLELISM
    internal const val MAX_CONCURRENT_DOWNLOADS_LIMIT = MAX_DOWNLOAD_PARALLELISM
    private const val PROGRESS_EMIT_INTERVAL_NS = 180_000_000L
    private const val PROGRESS_EMIT_MIN_BYTES_DELTA = 256L * 1024L
    private const val DOWNLOAD_TRAFFIC_FLUSH_BYTES = 512L * 1024L
    private const val TRANSIENT_DOWNLOAD_MAX_ATTEMPTS = 6
    private const val TRANSIENT_DOWNLOAD_OFFLINE_RECOVERY_WAIT_MS = 12_000L
    private const val TRANSIENT_DOWNLOAD_NETWORK_SETTLE_MS = 750L
    private const val DOWNLOAD_RETRY_POLL_SLICE_MS = 250L
    private const val DOWNLOAD_CLIENT_MAX_REQUESTS = 24
    private const val RECOVERY_OPPORTUNITY_COOLDOWN_MS = 2_500L
    private const val DOWNLOAD_CLIENT_MAX_REQUESTS_PER_HOST = 12
    private const val DOWNLOAD_CLIENT_CONNECT_TIMEOUT_MS = 20_000L
    private const val DOWNLOAD_CLIENT_READ_TIMEOUT_MS = 45_000L
    private const val DOWNLOAD_CLIENT_WRITE_TIMEOUT_MS = 45_000L
    private const val COVER_DOWNLOAD_MAX_ATTEMPTS = 3
    private const val COVER_DOWNLOAD_RETRY_DELAY_MS = 250L
    private const val YOUTUBE_DOWNLOAD_SHARED_DIRECT_RESOLVE_TIMEOUT_MS = 3_500L
    private const val YOUTUBE_DOWNLOAD_FRESH_DIRECT_RESOLVE_TIMEOUT_MS = 18_000L
    private const val YOUTUBE_DOWNLOAD_SHARED_PLAYABLE_RESOLVE_TIMEOUT_MS = 6_000L
    private const val YOUTUBE_DOWNLOAD_FRESH_PLAYABLE_RESOLVE_TIMEOUT_MS = 18_000L

    private fun canBlockStorageLookup(): Boolean {
        return Looper.myLooper() != Looper.getMainLooper()
    }
    private const val DOWNLOAD_READ_BUFFER_BYTES = 64L * 1024L
    private const val YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES = 4L * 1024L * 1024L

    private val backgroundDownloadClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer.sharedOkHttpClient.newBuilder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = DOWNLOAD_CLIENT_MAX_REQUESTS
                    maxRequestsPerHost = DOWNLOAD_CLIENT_MAX_REQUESTS_PER_HOST
                }
            )
            .connectTimeout(DOWNLOAD_CLIENT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_CLIENT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DOWNLOAD_CLIENT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val _progressFlow = MutableStateFlow<DownloadProgress?>(null)
    val progressFlow: StateFlow<DownloadProgress?> = _progressFlow
    
    private val _batchProgressFlow = MutableStateFlow<BatchDownloadProgress?>(null)
    val batchProgressFlow: StateFlow<BatchDownloadProgress?> = _batchProgressFlow
    
    // 取消下载控制
    private val _isCancelled = MutableStateFlow(false)
    val isCancelledFlow: StateFlow<Boolean> = _isCancelled
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS_LIMIT)
    private val downloadPermitLock = Mutex()
    private var activeDownloadPermitCount = 0
    private val progressPublishLock = Any()
    private val lastPublishedProgressBySongKey = mutableMapOf<String, PublishedProgressState>()
    private val completedAudioReferencesBySongKey =
        ConcurrentHashMap<String, ManagedDownloadStorage.StoredEntry>()
    private val partialSidecarReferencesBySongKey =
        ConcurrentHashMap<String, DownloadedSidecarReferences>()
    private val sharedCoverReferencesByLookupKey = ConcurrentHashMap<String, String>()
    private val hlsResumeStatesByWorkingPath =
        ConcurrentHashMap<String, HlsResumeState>()
    private val retryWakeSignalVersion = MutableStateFlow(0L)
    private val activeCallsBySongKey =
        ConcurrentHashMap<String, MutableSet<okhttp3.Call>>()
    private val networkPolicyPausedSongKeys =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val activeSongOperationCounts = ConcurrentHashMap<String, Int>()
    private val batchSessionLock = Any()
    private val networkRecoveryMonitorLock = Any()

    @Volatile
    private var lastRecoveryOpportunityAtMs = 0L

    private fun newDownloadTrafficAccumulator(): TrafficByteAccumulator {
        val appContext = AppContainer.applicationContext
        val networkType = appContext.currentTrafficNetworkType()
        return TrafficByteAccumulator(DOWNLOAD_TRAFFIC_FLUSH_BYTES) { bytes ->
            AppContainer.trafficStatsRepo.recordNetworkBytes(
                networkType = networkType,
                bytes = bytes,
                source = TrafficUsageSource.DOWNLOAD
            )
        }
    }

    @Volatile
    private var nextBatchSessionId = 0L

    @Volatile
    private var visibleBatchSessionId = 0L

    private val activeBatchSessionIds = linkedSetOf<Long>()

    @Volatile
    private var networkRecoveryMonitorRegistered = false

    @Volatile
    private var lastObservedTrafficNetworkType: TrafficNetworkType? = null

    fun isSongDownloadActive(songKey: String): Boolean {
        return (activeSongOperationCounts[songKey] ?: 0) > 0
    }

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        synchronized(networkRecoveryMonitorLock) {
            if (networkRecoveryMonitorRegistered) {
                return
            }
            val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
                ?: return
            lastObservedTrafficNetworkType = appContext.currentTrafficNetworkType()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleTrafficNetworkChanged(appContext, "network_available")
                    if (appContext.hasLikelyInternetAccess()) {
                        notifyRecoveryOpportunity("network_available")
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    if (appContext.hasLikelyInternetAccess()) {
                        notifyRecoveryOpportunity("network_available")
                    }
                    handleTrafficNetworkChanged(appContext, "network_capabilities_changed")
                }

                override fun onLost(network: Network) {
                    handleTrafficNetworkChanged(appContext, "network_lost")
                }
            }
            val registered = runCatching {
                connectivityManager.registerDefaultNetworkCallback(callback)
                true
            }.getOrDefault(false)
            if (registered) {
                networkRecoveryMonitorRegistered = true
            }
        }
    }

    private fun handleTrafficNetworkChanged(context: Context, reason: String) {
        val nextType = context.currentTrafficNetworkType()
        val previousType = lastObservedTrafficNetworkType
        lastObservedTrafficNetworkType = nextType
        if (previousType == TrafficNetworkType.WIFI && nextType != TrafficNetworkType.WIFI) {
            NPLogger.w(
                TAG,
                "WIFI 下载环境已切换，准备中断下载: reason=$reason, nextType=$nextType"
            )
            GlobalDownloadManager.interruptDownloadsForWifiDisconnected(nextType)
        }
    }

    private data class ResolvedDownloadSource(
        val url: String,
        val mimeType: String? = null,
        val fileExtensionHint: String? = null,
        val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
        val contentLength: Long? = null,
        val durationMs: Long? = null
    )

    internal enum class DownloadTransportKind {
        DIRECT,
        CHUNKED_RANGE,
        HLS
    }

    internal data class YouTubeDownloadResolveAttempt(
        val forceRefresh: Boolean,
        val requireDirect: Boolean,
        val timeoutMs: Long,
        val shareInFlight: Boolean
    ) {
        val logLabel: String
            get() = buildString {
                append(if (forceRefresh) "fresh" else "shared")
                append('_')
                append(if (requireDirect) "direct" else "playable")
            }
    }

    enum class DownloadStage {
        TRANSFERRING,
        WAITING_RETRY,
        FINALIZING
    }

    data class DownloadProgress(
        val songKey: String,
        val songId: Long,
        val fileName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val stage: DownloadStage = DownloadStage.TRANSFERRING,
        val attemptId: Long? = null
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
        val translatedLyricReference: String? = null,
        val createdCover: Boolean = false,
        val createdLyric: Boolean = false,
        val createdTranslatedLyric: Boolean = false
    ) {
        val isEmpty: Boolean
            get() = coverReference.isNullOrBlank() &&
                lyricReference.isNullOrBlank() &&
                translatedLyricReference.isNullOrBlank()

        fun retainCreatedOnly(): DownloadedSidecarReferences {
            return DownloadedSidecarReferences(
                coverReference = coverReference.takeIf { createdCover },
                lyricReference = lyricReference.takeIf { createdLyric },
                translatedLyricReference = translatedLyricReference.takeIf { createdTranslatedLyric },
                createdCover = createdCover && !coverReference.isNullOrBlank(),
                createdLyric = createdLyric && !lyricReference.isNullOrBlank(),
                createdTranslatedLyric = createdTranslatedLyric && !translatedLyricReference.isNullOrBlank()
            )
        }
    }

    private data class DownloadedPayloadSummary(
        val actualBytes: Long,
        val expectedBytes: Long?
    )

    internal data class HlsResumeState(
        val playlistFingerprint: Int,
        val nextSegmentIndex: Int,
        val downloadedBytes: Long
    )

    internal fun buildCoverDownloadCandidateUrls(song: SongItem): List<String> {
        return linkedSetOf<String?>().apply {
            add(song.displayCoverUrl())
            add(song.coverUrl)
            add(song.originalCoverUrl)
            add(song.customCoverUrl)
        }.mapNotNull { it?.takeIf(String::isNotBlank) }
    }

    internal fun isTransferSizeComplete(expectedBytes: Long?, actualBytes: Long): Boolean {
        return expectedBytes == null || expectedBytes <= 0L || actualBytes >= expectedBytes
    }

    internal fun resolveDownloadTransportKind(
        streamType: YouTubePlayableStreamType,
        request: Request
    ): DownloadTransportKind {
        if (streamType == YouTubePlayableStreamType.HLS) {
            return DownloadTransportKind.HLS
        }
        val headers = request.headers.names().associateWith { headerName ->
            request.header(headerName).orEmpty()
        }
        return if (
            YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(request) &&
            !YouTubeGoogleVideoRangeSupport.hasExplicitRangeHeader(headers)
        ) {
            DownloadTransportKind.CHUNKED_RANGE
        } else {
            DownloadTransportKind.DIRECT
        }
    }

    internal fun buildResumeRangeHeader(completedBytes: Long): String? {
        return completedBytes
            .takeIf { it > 0L }
            ?.let { "bytes=$it-" }
    }

    private fun parseContentRangeStart(headers: Map<String, List<String>>): Long? {
        val contentRangeValue = headers.entries.firstOrNull { (key, _) ->
            key.equals("Content-Range", ignoreCase = true)
        }?.value?.firstOrNull() ?: return null
        return Regex("""bytes\s+(\d+)-\d+/.*""", RegexOption.IGNORE_CASE)
            .find(contentRangeValue)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    internal fun resolveResponseExpectedBytes(
        requestUrl: String,
        headers: Map<String, List<String>>,
        bodyLength: Long,
        resumedBytes: Long,
        isPartialResponse: Boolean
    ): Long? {
        val resolvedTotal = YouTubeGoogleVideoRangeSupport.resolveTotalContentLength(
            requestUrl,
            headers
        )
        if (resolvedTotal != null && resolvedTotal > 0L) {
            return resolvedTotal
        }
        if (bodyLength <= 0L) {
            return null
        }
        return if (isPartialResponse) {
            bodyLength + resumedBytes.coerceAtLeast(0L)
        } else {
            bodyLength
        }
    }

    internal fun shouldPreservePartialDownloadForRetry(
        transportKind: DownloadTransportKind?,
        existingBytes: Long,
        hasHlsResumeState: Boolean
    ): Boolean {
        if (existingBytes <= 0L || transportKind == null) {
            return false
        }
        return when (transportKind) {
            DownloadTransportKind.DIRECT,
            DownloadTransportKind.CHUNKED_RANGE -> true
            DownloadTransportKind.HLS -> hasHlsResumeState
        }
    }

    internal fun advanceRetryWakeSignalVersion(currentVersion: Long): Long {
        return if (currentVersion == Long.MAX_VALUE) 0L else currentVersion + 1L
    }

    internal fun resolveYouTubeDownloadResolveAttempts(
        forceRefresh: Boolean
    ): List<YouTubeDownloadResolveAttempt> {
        val attempts = mutableListOf<YouTubeDownloadResolveAttempt>()
        if (!forceRefresh) {
            attempts += YouTubeDownloadResolveAttempt(
                forceRefresh = false,
                requireDirect = true,
                timeoutMs = YOUTUBE_DOWNLOAD_SHARED_DIRECT_RESOLVE_TIMEOUT_MS,
                shareInFlight = true
            )
        }
        attempts += YouTubeDownloadResolveAttempt(
            forceRefresh = true,
            requireDirect = true,
            timeoutMs = YOUTUBE_DOWNLOAD_FRESH_DIRECT_RESOLVE_TIMEOUT_MS,
            shareInFlight = false
        )
        if (!forceRefresh) {
            attempts += YouTubeDownloadResolveAttempt(
                forceRefresh = false,
                requireDirect = false,
                timeoutMs = YOUTUBE_DOWNLOAD_SHARED_PLAYABLE_RESOLVE_TIMEOUT_MS,
                shareInFlight = true
            )
        }
        attempts += YouTubeDownloadResolveAttempt(
            forceRefresh = true,
            requireDirect = false,
            timeoutMs = YOUTUBE_DOWNLOAD_FRESH_PLAYABLE_RESOLVE_TIMEOUT_MS,
            shareInFlight = false
        )
        return attempts
    }

    fun notifyRecoveryOpportunity(reason: String) {
        val appContext = AppContainer.applicationContext
        val nowMs = System.currentTimeMillis()
        synchronized(networkRecoveryMonitorLock) {
            if (nowMs - lastRecoveryOpportunityAtMs < RECOVERY_OPPORTUNITY_COOLDOWN_MS) {
                NPLogger.d(TAG, "跳过重复下载恢复机会: reason=$reason")
                return
            }
            lastRecoveryOpportunityAtMs = nowMs
        }
        if (!GlobalDownloadManager.hasPendingRecoveryCandidates(appContext)) {
            NPLogger.d(TAG, "跳过无候选下载恢复机会: reason=$reason")
            return
        }
        evictDownloadConnections()
        retryWakeSignalVersion.value = advanceRetryWakeSignalVersion(retryWakeSignalVersion.value)
        GlobalDownloadManager.recoverPendingDownloadsForNetworkRestored(
            context = appContext,
            reason = reason
        )
        NPLogger.d(TAG, "下载恢复机会已触发: reason=$reason")
    }

    private fun evictDownloadConnections() {
        runCatching {
            backgroundDownloadClient.connectionPool.evictAll()
        }
    }

    private fun resolveWorkingFileBytes(tempFile: File?): Long {
        return tempFile?.takeIf(File::exists)?.length()?.coerceAtLeast(0L) ?: 0L
    }

    internal fun serializeHlsResumeState(state: HlsResumeState): String {
        return JSONObject().apply {
            put("playlistFingerprint", state.playlistFingerprint)
            put("nextSegmentIndex", state.nextSegmentIndex)
            put("downloadedBytes", state.downloadedBytes.coerceAtLeast(0L))
        }.toString()
    }

    internal fun deserializeHlsResumeState(raw: String?): HlsResumeState? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val json = JSONObject(raw)
            HlsResumeState(
                playlistFingerprint = json.getInt("playlistFingerprint"),
                nextSegmentIndex = json.getInt("nextSegmentIndex"),
                downloadedBytes = json.getLong("downloadedBytes").coerceAtLeast(0L)
            )
        }.getOrNull()
    }

    private fun hlsResumeCheckpointFile(destFile: File): File {
        return ManagedDownloadStorage.buildWorkingHlsCheckpointFile(destFile)
    }

    private fun persistHlsResumeState(
        destFile: File,
        state: HlsResumeState
    ) {
        val checkpointFile = hlsResumeCheckpointFile(destFile)
        runCatching {
            checkpointFile.parentFile?.mkdirs()
            checkpointFile.writeText(serializeHlsResumeState(state), Charsets.UTF_8)
        }.onFailure { error ->
            NPLogger.w(TAG, "写入 HLS 恢复点失败: ${checkpointFile.name}, ${error.message}")
        }
    }

    private fun readPersistedHlsResumeState(destFile: File): HlsResumeState? {
        val checkpointFile = hlsResumeCheckpointFile(destFile)
        if (!checkpointFile.exists() || !checkpointFile.isFile) {
            return null
        }
        return runCatching {
            deserializeHlsResumeState(checkpointFile.readText(Charsets.UTF_8))
        }.onFailure { error ->
            NPLogger.w(TAG, "读取 HLS 恢复点失败: ${checkpointFile.name}, ${error.message}")
        }.getOrNull()
    }

    private fun deletePersistedHlsResumeState(destFile: File?) {
        destFile ?: return
        val checkpointFile = hlsResumeCheckpointFile(destFile)
        if (checkpointFile.exists()) {
            runCatching {
                checkpointFile.delete()
            }
        }
    }

    private fun rememberHlsResumeState(
        destFile: File,
        playlistFingerprint: Int,
        nextSegmentIndex: Int,
        downloadedBytes: Long
    ) {
        val state = HlsResumeState(
            playlistFingerprint = playlistFingerprint,
            nextSegmentIndex = nextSegmentIndex,
            downloadedBytes = downloadedBytes.coerceAtLeast(0L)
        )
        hlsResumeStatesByWorkingPath[destFile.absolutePath] = state
        persistHlsResumeState(destFile, state)
    }

    private fun resolveHlsResumeState(
        destFile: File,
        playlistFingerprint: Int
    ): HlsResumeState? {
        val state = hlsResumeStatesByWorkingPath[destFile.absolutePath]
            ?: readPersistedHlsResumeState(destFile)?.also { persisted ->
                hlsResumeStatesByWorkingPath[destFile.absolutePath] = persisted
            }
            ?: return null
        return state.takeIf { it.playlistFingerprint == playlistFingerprint }
    }

    private fun hasHlsResumeState(destFile: File?): Boolean {
        return destFile != null && (
            hlsResumeStatesByWorkingPath.containsKey(destFile.absolutePath) ||
                hlsResumeCheckpointFile(destFile).exists()
            )
    }

    private fun clearHlsResumeState(destFile: File?) {
        destFile ?: return
        hlsResumeStatesByWorkingPath.remove(destFile.absolutePath)
        deletePersistedHlsResumeState(destFile)
    }

    private fun deleteWorkingFile(tempFile: File?) {
        clearHlsResumeState(tempFile)
        ManagedDownloadStorage.deleteWorkingDownloadArtifacts(tempFile)
    }

    private fun shouldPreserveArtifactsForNetworkPolicy(songKey: String): Boolean {
        return networkPolicyPausedSongKeys.contains(songKey)
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

    private fun startBatchSession(): Long {
        return synchronized(batchSessionLock) {
            val sessionId = ++nextBatchSessionId
            activeBatchSessionIds += sessionId
            visibleBatchSessionId = sessionId
            sessionId
        }
    }

    private fun invalidateBatchSession() {
        synchronized(batchSessionLock) {
            activeBatchSessionIds.clear()
            visibleBatchSessionId = 0L
            nextBatchSessionId++
        }
    }

    private fun isBatchSessionCurrent(batchSessionId: Long?): Boolean {
        return batchSessionId == null || synchronized(batchSessionLock) {
            batchSessionId in activeBatchSessionIds
        }
    }

    private fun finishBatchSession(batchSessionId: Long) {
        val shouldClearProgress = synchronized(batchSessionLock) {
            val wasVisible = visibleBatchSessionId == batchSessionId
            activeBatchSessionIds.remove(batchSessionId)
            if (wasVisible) {
                visibleBatchSessionId = activeBatchSessionIds.maxOrNull() ?: 0L
            }
            wasVisible
        }
        if (shouldClearProgress) {
            _batchProgressFlow.value = null
        }
    }

    private fun updateBatchProgressForSession(
        batchSessionId: Long,
        progress: BatchDownloadProgress?
    ) {
        val shouldPublish = synchronized(batchSessionLock) {
            batchSessionId in activeBatchSessionIds && visibleBatchSessionId == batchSessionId
        }
        if (!shouldPublish) {
            return
        }
        _batchProgressFlow.value = progress
    }

    private fun beginSongDownloadOperation(songKey: String) {
        activeSongOperationCounts.compute(songKey) { _, current ->
            (current ?: 0) + 1
        }
    }

    private fun endSongDownloadOperation(songKey: String) {
        activeSongOperationCounts.computeIfPresent(songKey) { _, current ->
            val nextCount = current - 1
            if (nextCount <= 0) {
                null
            } else {
                nextCount
            }
        }
    }

    private fun registerActiveCall(songKey: String, call: okhttp3.Call) {
        activeCallsBySongKey.compute(songKey) { _, current ->
            val calls = current ?: Collections.newSetFromMap(ConcurrentHashMap<okhttp3.Call, Boolean>())
            calls.add(call)
            calls
        }
    }

    private fun unregisterActiveCall(songKey: String, call: okhttp3.Call) {
        activeCallsBySongKey.computeIfPresent(songKey) { _, current ->
            current.remove(call)
            if (current.isEmpty()) {
                null
            } else {
                current
            }
        }
    }

    private fun snapshotActiveCalls(songKey: String? = null): List<okhttp3.Call> {
        return if (songKey == null) {
            activeCallsBySongKey.values.flatMap { calls -> calls.toList() }
        } else {
            activeCallsBySongKey[songKey]?.toList().orEmpty()
        }
    }

    private inline fun <T> executeTrackedCall(
        client: okhttp3.OkHttpClient,
        request: Request,
        songKey: String,
        block: (okhttp3.Response) -> T
    ): T {
        val call = client.newCall(request)
        registerActiveCall(songKey, call)
        try {
            return call.execute().use(block)
        } catch (error: IOException) {
            if (call.isCanceled() || _isCancelled.value || GlobalDownloadManager.isSongCancelled(songKey)) {
                _progressFlow.value = null
                throw java.util.concurrent.CancellationException("Download cancelled").apply {
                    initCause(error)
                }
            }
            throw error
        } finally {
            unregisterActiveCall(songKey, call)
        }
    }

    internal fun consumeCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? {
        return completedAudioReferencesBySongKey.remove(songKey)
    }

    internal fun consumePartialSidecarReferences(
        songKey: String
    ): DownloadedSidecarReferences? {
        return partialSidecarReferencesBySongKey.remove(songKey)
    }

    internal fun rememberCompletedAudioReference(
        songKey: String,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) {
        completedAudioReferencesBySongKey[songKey] = storedAudio
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

    private fun clearCompletedAudioReference(songKey: String) {
        completedAudioReferencesBySongKey.remove(songKey)
    }

    private fun clearPartialSidecarReferences(songKey: String) {
        partialSidecarReferencesBySongKey.remove(songKey)
    }

    internal fun buildSharedCoverLookupKeys(song: SongItem): List<String> {
        val remoteCoverKeys = buildRemoteCoverLookupKeys(song)
        return linkedSetOf<String>().apply {
            remoteCoverKeys.forEach { add("url:$it") }
            if (remoteCoverKeys.isEmpty()) {
                song.identity().album.takeIf(String::isNotBlank)?.let { add("album:$it") }
            }
        }.toList()
    }

    private fun buildRemoteCoverLookupKeys(song: SongItem): List<String> {
        return linkedSetOf<String>().apply {
            song.customCoverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            song.coverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            song.originalCoverUrl?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }.toList()
    }

    private suspend fun findSharedCoverReference(
        context: Context,
        song: SongItem,
        excludedAudioName: String? = null,
        allowIndexedLookup: Boolean = true
    ): String? {
        val lookupKeys = buildSharedCoverLookupKeys(song)
        if (lookupKeys.isEmpty()) {
            return null
        }
        val fastSnapshot = if (allowIndexedLookup) {
            null
        } else {
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                context = context,
                restoreFromDisk = false
            )
        }
        for (lookupKey in lookupKeys) {
            val rememberedReference = sharedCoverReferencesByLookupKey[lookupKey] ?: continue
            if (!allowIndexedLookup) {
                if (rememberedReference in fastSnapshot?.knownReferences.orEmpty()) {
                    return rememberedReference
                }
                sharedCoverReferencesByLookupKey.remove(lookupKey, rememberedReference)
                continue
            }
            if (ManagedDownloadStorage.exists(context, rememberedReference)) {
                return rememberedReference
            }
            sharedCoverReferencesByLookupKey.remove(lookupKey, rememberedReference)
        }
        if (!allowIndexedLookup) {
            val snapshot = fastSnapshot ?: return null
            return ManagedDownloadStorage.findReusableCoverReference(
                snapshot = snapshot,
                song = song,
                excludedAudioName = excludedAudioName
            )?.also { indexedReference ->
                rememberSharedCoverReference(song, indexedReference)
            }
        }
        val indexedReference = ManagedDownloadStorage.findReusableCoverReference(
            context = context,
            song = song,
            excludedAudioName = excludedAudioName
        )
        if (!indexedReference.isNullOrBlank()) {
            rememberSharedCoverReference(song, indexedReference)
        }
        return indexedReference
    }

    private fun rememberSharedCoverReference(song: SongItem, coverReference: String?) {
        val normalizedReference = coverReference?.takeIf(String::isNotBlank) ?: return
        buildSharedCoverLookupKeys(song).forEach { lookupKey ->
            sharedCoverReferencesByLookupKey.putIfAbsent(lookupKey, normalizedReference)
        }
    }

    internal fun mergeDownloadedSidecarReferences(
        existing: DownloadedSidecarReferences?,
        incoming: DownloadedSidecarReferences?
    ): DownloadedSidecarReferences {
        return DownloadedSidecarReferences(
            coverReference = incoming?.coverReference ?: existing?.coverReference,
            createdCover = mergeSidecarCreatedFlag(
                existingReference = existing?.coverReference,
                existingCreated = existing?.createdCover ?: false,
                incomingReference = incoming?.coverReference,
                incomingCreated = incoming?.createdCover ?: false
            ),
            lyricReference = incoming?.lyricReference ?: existing?.lyricReference,
            createdLyric = mergeSidecarCreatedFlag(
                existingReference = existing?.lyricReference,
                existingCreated = existing?.createdLyric ?: false,
                incomingReference = incoming?.lyricReference,
                incomingCreated = incoming?.createdLyric ?: false
            ),
            translatedLyricReference = incoming?.translatedLyricReference
                ?: existing?.translatedLyricReference,
            createdTranslatedLyric = mergeSidecarCreatedFlag(
                existingReference = existing?.translatedLyricReference,
                existingCreated = existing?.createdTranslatedLyric ?: false,
                incomingReference = incoming?.translatedLyricReference,
                incomingCreated = incoming?.createdTranslatedLyric ?: false
            )
        )
    }

    private fun mergeSidecarCreatedFlag(
        existingReference: String?,
        existingCreated: Boolean,
        incomingReference: String?,
        incomingCreated: Boolean
    ): Boolean {
        val incoming = incomingReference?.takeIf(String::isNotBlank)
            ?: return existingCreated
        val existing = existingReference?.takeIf(String::isNotBlank)
            ?: return incomingCreated
        return if (incoming == existing) {
            existingCreated || incomingCreated
        } else {
            incomingCreated
        }
    }

    private fun publishFinalizingProgress(
        songId: Long,
        songKey: String,
        fileName: String,
        bytesRead: Long,
        totalBytes: Long,
        attemptId: Long? = null
    ) {
        publishProgress(
            DownloadProgress(
                songKey = songKey,
                songId = songId,
                fileName = fileName,
                bytesRead = bytesRead,
                totalBytes = totalBytes,
                speedBytesPerSec = 0L,
                stage = DownloadStage.FINALIZING,
                attemptId = attemptId
            ),
            force = true
        )
    }

    private fun publishRetryWaitingProgress(
        songId: Long,
        songKey: String,
        fileName: String,
        bytesRead: Long,
        totalBytes: Long,
        attemptId: Long? = null
    ) {
        publishProgress(
            DownloadProgress(
                songKey = songKey,
                songId = songId,
                fileName = fileName,
                bytesRead = bytesRead.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L),
                speedBytesPerSec = 0L,
                stage = DownloadStage.WAITING_RETRY,
                attemptId = attemptId
            ),
            force = true
        )
    }

    private fun ensureSongDownloadNotCancelled(
        songKey: String,
        stage: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        requireActiveAttempt: Boolean = true
    ) {
        val attemptAllowsWork = if (requireActiveAttempt) {
            GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId)
        } else {
            attemptId == null || GlobalDownloadManager.isDownloadAttemptCurrent(songKey, attemptId)
        }
        if (
            !_isCancelled.value &&
            isBatchSessionCurrent(batchSessionId) &&
            !GlobalDownloadManager.isSongCancelled(songKey) &&
            attemptAllowsWork
        ) {
            return
        }
        NPLogger.d(TAG, "检测到下载取消: songKey=$songKey, stage=$stage")
        _progressFlow.value = null
        throw java.util.concurrent.CancellationException("Download cancelled during $stage")
    }

    suspend fun downloadSong(
        context: Context,
        song: SongItem,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ) {
        withConfiguredDownloadPermit(context) {
            withContext(Dispatchers.IO) {
                val songKey = song.stableKey()
                var storedAudio: ManagedDownloadStorage.StoredEntry? = null
                var tempFile: File? = null
                beginSongDownloadOperation(songKey)
                clearCompletedAudioReference(songKey)
                clearPartialSidecarReferences(songKey)
                try {
                    ensureSongDownloadNotCancelled(songKey, "prepare", batchSessionId, attemptId)
                    if (LocalSongSupport.isLocalSong(song, context)) {
                        NPLogger.d(TAG, "Skip local song download: ${song.name}")
                        _progressFlow.value = null
                        return@withContext
                    }

                    if (hasFastCachedManagedDownloadForStart(context, song)) {
                        NPLogger.d(
                            TAG,
                            "${context.getString(R.string.download_file_exists, song.name)}, songKey=$songKey"
                        )
                        _progressFlow.value = null
                        return@withContext
                    }

                    val isYouTubeMusic = isYouTubeMusicSong(song)
                    val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
                    var attemptNumber = 1
                    var activeTransportKind: DownloadTransportKind? = null
                    var activeWorkingFileName: String? = null
                    var forceRefreshYouTubeSource = false
                    while (true) {
                        ensureSongDownloadNotCancelled(songKey, "prepare", batchSessionId, attemptId)
                        try {
                            val resolved = when {
                                isYouTubeMusic -> resolveYouTubeMusic(
                                    song = song,
                                    forceRefresh = forceRefreshYouTubeSource
                                )
                                isBili -> resolveBili(song)
                                else -> resolveNetease(song.id)
                            }
                            if (resolved == null) {
                                if (attemptNumber < TRANSIENT_DOWNLOAD_MAX_ATTEMPTS) {
                                    val retryDelayMs = resolveTransientDownloadRetryDelayMs(attemptNumber)
                                    val visibleFileName =
                                        activeWorkingFileName ?: ManagedDownloadStorage.buildDisplayBaseName(song)
                                    publishRetryWaitingProgress(
                                        songId = song.id,
                                        songKey = songKey,
                                        fileName = visibleFileName,
                                        bytesRead = resolveWorkingFileBytes(tempFile),
                                        totalBytes = _progressFlow.value
                                            ?.takeIf { it.songKey == songKey }
                                            ?.totalBytes
                                            ?: 0L,
                                        attemptId = attemptId
                                    )
                                    NPLogger.w(
                                        TAG,
                                        "下载链接暂时不可用，准备重试($attemptNumber/$TRANSIENT_DOWNLOAD_MAX_ATTEMPTS): ${song.name}"
                                    )
                                    if (isYouTubeMusic) {
                                        forceRefreshYouTubeSource = true
                                    }
                                    evictDownloadConnections()
                                    waitForRetryOrCancellation(
                                        context = context,
                                        songKey = songKey,
                                        delayMs = retryDelayMs,
                                        batchSessionId = batchSessionId,
                                        attemptId = attemptId
                                    )
                                    attemptNumber++
                                    continue
                                }
                                throw IOException(context.getString(R.string.download_no_url, song.name))
                            }
                            forceRefreshYouTubeSource = false

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
                            val transportKind = resolveDownloadTransportKind(
                                streamType = resolved.streamType,
                                request = request
                            )
                            if (
                                tempFile == null ||
                                activeWorkingFileName != fileName ||
                                activeTransportKind != transportKind
                            ) {
                                deleteWorkingFile(tempFile)
                                tempFile = ManagedDownloadStorage.createWorkingFile(
                                    context = context,
                                    songKey = songKey,
                                    fileName = fileName
                                )
                                activeWorkingFileName = fileName
                                activeTransportKind = transportKind
                            }
                            val workingFile = tempFile
                                ?: throw IOException("无法创建下载缓存文件: $fileName")
                            ManagedDownloadStorage.saveWorkingResumeMetadata(
                                workingFile = workingFile,
                                song = workingSong
                            )
                            val client = backgroundDownloadClient

                            // 貌似很多平台都不支持多线程下载(x  所以采用单线程
                            // 传入临时文件
                            val downloadedPayload = when (transportKind) {
                                DownloadTransportKind.HLS -> singleThreadHlsDownload(
                                    client = client,
                                    playlistRequest = request,
                                    destFile = workingFile,
                                    displayFileName = fileName,
                                    songId = workingSong.id,
                                    songKey = workingSong.stableKey(),
                                    totalBytesHint = resolved.contentLength ?: 0L,
                                    batchSessionId = batchSessionId,
                                    attemptId = attemptId
                                )
                                DownloadTransportKind.DIRECT,
                                DownloadTransportKind.CHUNKED_RANGE -> singleThreadDownload(
                                    client = client,
                                    request = request,
                                    destFile = workingFile,
                                    displayFileName = fileName,
                                    songId = workingSong.id,
                                    songKey = workingSong.stableKey(),
                                    batchSessionId = batchSessionId,
                                    attemptId = attemptId
                                )
                            }
                            verifyDownloadedAudioPayload(
                                song = workingSong,
                                tempFile = workingFile,
                                displayFileName = fileName,
                                payloadSummary = downloadedPayload
                            )
                            clearHlsResumeState(workingFile)

                            val transferredBytes = workingFile.length().coerceAtLeast(0L)
                            publishFinalizingProgress(
                                songId = workingSong.id,
                                songKey = workingSong.stableKey(),
                                fileName = fileName,
                                bytesRead = transferredBytes,
                                totalBytes = transferredBytes,
                                attemptId = attemptId
                            )
                            ensureSongDownloadNotCancelled(songKey, "audio_commit", batchSessionId, attemptId)
                            val seedMetadataJson = buildSeedDownloadedMetadataJson(workingSong)
                            storedAudio = ManagedDownloadStorage.saveAudioFromTemp(
                                context = context,
                                fileName = fileName,
                                tempFile = workingFile,
                                mimeType = mime,
                                expectedSizeBytes = downloadedPayload.expectedBytes,
                                seedMetadataJson = seedMetadataJson
                            )
                            ManagedDownloadStorage.deleteWorkingResumeMetadata(workingFile)
                            ensureSongDownloadNotCancelled(songKey, "audio_committed", batchSessionId, attemptId)
                            publishFinalizingProgress(
                                songId = workingSong.id,
                                songKey = workingSong.stableKey(),
                                fileName = storedAudio.name,
                                bytesRead = transferredBytes,
                                totalBytes = transferredBytes,
                                attemptId = attemptId
                            )
                            NPLogger.d(
                                TAG,
                                "音频落盘完成，sidecar 转入后台整理: song=${song.name}, audioFile=${storedAudio.name}"
                            )
                            rememberCompletedAudioReference(songKey, storedAudio)

                            _progressFlow.value = null
                            clearPartialSidecarReferences(songKey)
                            return@withContext
                        } catch (error: Exception) {
                            if (
                                error is java.util.concurrent.CancellationException ||
                                    _isCancelled.value ||
                                    GlobalDownloadManager.isSongCancelled(songKey)
                            ) {
                                val partialSidecarReferences = consumePartialSidecarReferences(songKey)
                                    ?.retainCreatedOnly()
                                NPLogger.d(TAG, "下载已取消: ${song.name}")
                                val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
                                if (
                                    !preserveArtifacts &&
                                    (storedAudio != null || !(partialSidecarReferences?.isEmpty ?: true))
                                ) {
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
                                        storedAudio = null
                                    }.onFailure { rollbackError ->
                                        NPLogger.e(
                                            TAG,
                                            "回滚已取消下载失败: ${song.name}, ${rollbackError.message}",
                                            rollbackError
                                        )
                                    }
                                }
                                if (!preserveArtifacts) {
                                    deleteWorkingFile(tempFile)
                                    tempFile = null
                                }
                                _progressFlow.value = null
                                if (!preserveArtifacts) {
                                    clearSongCancelled(songKey)
                                }
                                clearCompletedAudioReference(songKey)
                                clearPartialSidecarReferences(songKey)
                                throw java.util.concurrent.CancellationException(
                                    if (preserveArtifacts) "Download paused for network policy" else "Download cancelled"
                                )
                            }

                            clearPartialSidecarReferences(songKey)
                            if (
                                storedAudio == null &&
                                attemptNumber < TRANSIENT_DOWNLOAD_MAX_ATTEMPTS &&
                                shouldRetryDownloadFailureForSource(error, isYouTubeMusic)
                            ) {
                                val partialBytes = resolveWorkingFileBytes(tempFile)
                                val preservePartial = shouldPreservePartialDownloadForRetry(
                                    transportKind = activeTransportKind,
                                    existingBytes = partialBytes,
                                    hasHlsResumeState = hasHlsResumeState(tempFile)
                                )
                                if (!preservePartial) {
                                    deleteWorkingFile(tempFile)
                                    tempFile = null
                                }
                                val retryDelayMs = resolveTransientDownloadRetryDelayMs(attemptNumber)
                                val visibleFileName =
                                    activeWorkingFileName ?: ManagedDownloadStorage.buildDisplayBaseName(song)
                                publishRetryWaitingProgress(
                                    songId = song.id,
                                    songKey = songKey,
                                    fileName = visibleFileName,
                                    bytesRead = if (preservePartial) partialBytes else 0L,
                                    totalBytes = _progressFlow.value
                                        ?.takeIf { it.songKey == songKey }
                                        ?.totalBytes
                                        ?: 0L,
                                    attemptId = attemptId
                                )
                                if (isYouTubeMusic && shouldRefreshYouTubeDownloadSourceOnFailure(error)) {
                                    forceRefreshYouTubeSource = true
                                }
                                NPLogger.w(
                                    TAG,
                                    "下载遇到网络波动，准备重试($attemptNumber/$TRANSIENT_DOWNLOAD_MAX_ATTEMPTS): ${song.name}, refreshYouTubeSource=$forceRefreshYouTubeSource, ${error.javaClass.simpleName} - ${error.message}"
                                )
                                evictDownloadConnections()
                                waitForRetryOrCancellation(
                                    context = context,
                                    songKey = songKey,
                                    delayMs = retryDelayMs,
                                    batchSessionId = batchSessionId,
                                    attemptId = attemptId
                                )
                                attemptNumber++
                                continue
                            }
                            deleteWorkingFile(tempFile)
                            tempFile = null
                            NPLogger.e(
                                TAG,
                                "下载失败: ${song.name}, 错误: ${error.javaClass.simpleName} - ${error.message}",
                                error
                            )
                            throw error
                        }
                    }
                } catch (e: Exception) {
                    if (
                        e is java.util.concurrent.CancellationException ||
                            _isCancelled.value ||
                            GlobalDownloadManager.isSongCancelled(songKey)
                    ) {
                        val partialSidecarReferences = consumePartialSidecarReferences(songKey)
                            ?.retainCreatedOnly()
                        NPLogger.d(TAG, "下载已取消: ${song.name}")
                        val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
                        if (
                            !preserveArtifacts &&
                            (storedAudio != null || !(partialSidecarReferences?.isEmpty ?: true))
                        ) {
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
                                storedAudio = null
                            }.onFailure { rollbackError ->
                                NPLogger.e(
                                    TAG,
                                    "回滚已取消下载失败: ${song.name}, ${rollbackError.message}",
                                    rollbackError
                                )
                            }
                        }
                        if (!preserveArtifacts) {
                            deleteWorkingFile(tempFile)
                        }
                        _progressFlow.value = null
                        if (!preserveArtifacts) {
                            clearSongCancelled(songKey)
                        }
                        clearCompletedAudioReference(songKey)
                        clearPartialSidecarReferences(songKey)
                        throw java.util.concurrent.CancellationException(
                            if (preserveArtifacts) "Download paused for network policy" else "Download cancelled"
                        )
                    }
                    NPLogger.e(TAG, "下载失败: ${song.name}, 错误: ${e.javaClass.simpleName} - ${e.message}", e)
                    deleteWorkingFile(tempFile)
                    _progressFlow.value = null
                    clearCompletedAudioReference(songKey)
                    clearPartialSidecarReferences(songKey)
                    throw e  // 重新抛出异常，让调用方知道下载失败
                } finally {
                    clearPublishedProgress(songKey)
                    endSongDownloadOperation(songKey)
                }
            }
        }
    }

    internal suspend fun downloadSidecarsForCompletedAudio(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ): DownloadedSidecarReferences = withContext(Dispatchers.IO) {
        val songKey = song.stableKey()
        clearPartialSidecarReferences(songKey)
        val downloadedReferences = runCatching {
            downloadSidecars(
                context = context,
                song = song,
                songKey = songKey,
                baseName = storedAudio.nameWithoutExtension,
                storedAudio = storedAudio,
                requireActiveAttempt = false
            )
        }.getOrElse { error ->
            if (error is java.util.concurrent.CancellationException) {
                NPLogger.d(TAG, "后台 sidecar 整理已取消: ${song.name}")
            } else {
                NPLogger.w(TAG, "后台 sidecar 整理失败: ${song.name} - ${error.message}")
            }
            DownloadedSidecarReferences()
        }
        val createdReferences = consumePartialSidecarReferences(songKey)
            ?.retainCreatedOnly()
        mergeDownloadedSidecarReferences(downloadedReferences, createdReferences)
    }

    private suspend fun <T> withConfiguredDownloadPermit(
        context: Context,
        block: suspend () -> T
    ): T {
        return downloadSemaphore.withPermit {
            acquireConfiguredDownloadPermit(context)
            try {
                block()
            } finally {
                releaseConfiguredDownloadPermit()
            }
        }
    }

    private suspend fun acquireConfiguredDownloadPermit(context: Context) {
        while (true) {
            val configuredLimit = resolveConfiguredDownloadParallelism(context)
            val acquired = downloadPermitLock.withLock {
                if (activeDownloadPermitCount >= configuredLimit) {
                    false
                } else {
                    activeDownloadPermitCount++
                    true
                }
            }
            if (acquired) {
                return
            }
            delay(DOWNLOAD_RETRY_POLL_SLICE_MS)
        }
    }

    private suspend fun releaseConfiguredDownloadPermit() {
        downloadPermitLock.withLock {
            activeDownloadPermitCount = (activeDownloadPermitCount - 1).coerceAtLeast(0)
        }
    }

    private suspend fun downloadSidecars(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        requireActiveAttempt: Boolean = true
    ): DownloadedSidecarReferences {
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "sidecar_prepare",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            requireActiveAttempt = requireActiveAttempt
        )
        val useSequentialSidecarWrites = ManagedDownloadStorage.usesDocumentTree(context)
        val allowIndexedSidecarLookup = shouldUseIndexedSidecarLookup(
            usesDocumentTree = useSequentialSidecarWrites,
            allowSlowLookup = true
        )
        val references = if (useSequentialSidecarWrites) {
            val lyricReferences = downloadLyrics(
                context = context,
                song = song,
                songKey = songKey,
                baseName = baseName,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
            val coverReference = cacheCover(
                context = context,
                song = song,
                songKey = songKey,
                baseName = baseName,
                storedAudio = storedAudio,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt,
                allowIndexedLookup = allowIndexedSidecarLookup
            )
            DownloadedSidecarReferences(
                coverReference = coverReference,
                lyricReference = lyricReferences.lyricReference,
                translatedLyricReference = lyricReferences.translatedLyricReference
            )
        } else {
            coroutineScope {
                val lyricJob = async {
                    downloadLyrics(
                        context = context,
                        song = song,
                        songKey = songKey,
                        baseName = baseName,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        requireActiveAttempt = requireActiveAttempt
                    )
                }
                val coverJob = async {
                    cacheCover(
                        context = context,
                        song = song,
                        songKey = songKey,
                        baseName = baseName,
                        storedAudio = storedAudio,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        requireActiveAttempt = requireActiveAttempt,
                        allowIndexedLookup = allowIndexedSidecarLookup
                    )
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
        return mergeDownloadedSidecarReferences(
            references,
            partialSidecarReferencesBySongKey[songKey]?.retainCreatedOnly()
        )
    }

    private suspend fun cacheCover(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        requireActiveAttempt: Boolean = true,
        allowIndexedLookup: Boolean = true
    ): String? {
        val existingCover = ManagedDownloadStorage.peekCoverReference(storedAudio)
            ?: if (allowIndexedLookup && ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                ManagedDownloadStorage.peekCoverReference(storedAudio)
            } else {
                null
            }
        if (!existingCover.isNullOrBlank()) {
            rememberSharedCoverReference(song, existingCover)
            rememberPartialSidecarReferences(
                songKey,
                DownloadedSidecarReferences(
                    coverReference = existingCover,
                    createdCover = false
                )
            )
            return existingCover
        }

        val sharedCover = findSharedCoverReference(
            context = context,
            song = song,
            excludedAudioName = storedAudio.name,
            allowIndexedLookup = allowIndexedLookup
        )
        if (!sharedCover.isNullOrBlank()) {
            rememberSharedCoverReference(song, sharedCover)
            rememberPartialSidecarReferences(
                songKey,
                DownloadedSidecarReferences(
                    coverReference = sharedCover,
                    createdCover = false
                )
            )
            return sharedCover
        }

        try {
            val coverCandidates = buildCoverDownloadCandidateUrls(song)
            val coverFileName = buildCoverSidecarFileName(baseName, songKey)
            coverCandidates.forEachIndexed { candidateIndex, coverUrl ->
                repeat(COVER_DOWNLOAD_MAX_ATTEMPTS) { retryIndex ->
                    ensureSongDownloadNotCancelled(
                        songKey = songKey,
                        stage = "cover_request",
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        requireActiveAttempt = requireActiveAttempt
                    )
                    val committedCoverReference = runCatching {
                        downloadCoverCandidate(
                            context = context,
                            songKey = songKey,
                            coverUrl = coverUrl,
                            coverFileName = coverFileName,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId,
                            requireActiveAttempt = requireActiveAttempt
                        )
                    }.getOrElse { error ->
                        if (error is java.util.concurrent.CancellationException) {
                            throw error
                        }
                        NPLogger.w(
                            TAG,
                            "封面下载重试失败: song=${song.name}, candidate=${candidateIndex + 1}/${coverCandidates.size}, attempt=${retryIndex + 1}/$COVER_DOWNLOAD_MAX_ATTEMPTS, ${error.message}"
                        )
                        null
                    }
                    if (!committedCoverReference.isNullOrBlank()) {
                        rememberSharedCoverReference(song, committedCoverReference)
                        rememberPartialSidecarReferences(
                            songKey,
                            DownloadedSidecarReferences(
                                coverReference = committedCoverReference,
                                createdCover = true
                            )
                        )
                        NPLogger.d(TAG, "封面写入完成: song=${song.name}, reference=$committedCoverReference")
                        return committedCoverReference
                    }
                    if (retryIndex + 1 < COVER_DOWNLOAD_MAX_ATTEMPTS) {
                        delay(COVER_DOWNLOAD_RETRY_DELAY_MS * (retryIndex + 1))
                    }
                }
                NPLogger.w(
                    TAG,
                    "封面候选下载失败，准备尝试下一个来源: song=${song.name}, candidate=${candidateIndex + 1}/${coverCandidates.size}"
                )
            }
        } catch (cancellation: java.util.concurrent.CancellationException) {
            NPLogger.d(TAG, "封面整理阶段收到取消: ${song.name}")
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(TAG, "封面后台下载失败: ${song.name} - ${error.message}")
        }
        return null
    }

    private fun buildCoverSidecarFileName(baseName: String, songKey: String): String {
        val suffix = java.lang.Long.toHexString(songKey.hashCode().toLong() and 0xffffffffL)
        return "$baseName-$suffix.jpg"
    }

    private fun downloadCoverCandidate(
        context: Context,
        songKey: String,
        coverUrl: String,
        coverFileName: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        requireActiveAttempt: Boolean = true
    ): String? {
        val req = Request.Builder().url(coverUrl).build()
        return executeTrackedCall(
            client = backgroundDownloadClient,
            request = req,
            songKey = songKey
        ) { response ->
            if (!response.isSuccessful) {
                throw IOException("封面请求失败: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("封面响应为空")
            val contentType = body.contentType()?.toString().orEmpty()
            if (contentType.isNotBlank() && !contentType.startsWith("image/", ignoreCase = true)) {
                throw IOException("封面响应不是图片: $contentType")
            }
            val expectedLength = body.contentLength().takeIf { it > 0L }
            val bytes = body.bytes()
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "cover_downloaded",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
            val copiedBytes = bytes.size.toLong()
            if (copiedBytes <= 0L) {
                throw IOException("封面写入为空")
            }
            if (!isTransferSizeComplete(expectedLength, copiedBytes)) {
                throw IOException("封面写入不完整: $copiedBytes/$expectedLength")
            }
            if (!isUsableCoverBytes(bytes)) {
                throw IOException("封面文件校验失败")
            }
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "cover_commit",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
            ManagedDownloadStorage.commitCoverBytes(
                context = context,
                bytes = bytes,
                fileName = coverFileName,
                mimeType = contentType.takeIf { it.isNotBlank() }
            )?.reference
        }
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

    private fun verifyDownloadedAudioPayload(
        song: SongItem,
        tempFile: File,
        displayFileName: String,
        payloadSummary: DownloadedPayloadSummary
    ) {
        val actualBytes = maxOf(payloadSummary.actualBytes, tempFile.length().coerceAtLeast(0L))
        if (actualBytes <= 0L) {
            throw IOException("下载文件为空: $displayFileName")
        }
        if (!isTransferSizeComplete(payloadSummary.expectedBytes, actualBytes)) {
            throw IOException("下载文件不完整: $displayFileName, $actualBytes/${payloadSummary.expectedBytes}")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(tempFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.takeIf(String::isNotBlank)
                ?.let { hasAudio ->
                    if (hasAudio == "no") {
                        throw IOException("下载文件不包含音轨: $displayFileName")
                    }
                }
        } catch (error: Exception) {
            throw IOException("下载文件校验失败: ${song.name}", error)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 批量下载歌单中的所有歌曲 */
    suspend fun downloadPlaylist(
        context: Context,
        songs: List<SongItem>,
        maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS,
        songAttemptIds: Map<String, Long> = emptyMap(),
        onSongStarted: suspend (SongItem) -> Unit = {},
        onSongCompleted: suspend (SongItem) -> Unit = {},
        onSongFailed: suspend (SongItem, Throwable) -> Unit = { _, _ -> },
        onSongCancelled: suspend (SongItem) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            var batchSessionId: Long? = null
            try {
                batchSessionId = startBatchSession()
                val remoteSongs = songs.filterNot { LocalSongSupport.isLocalSong(it, context) }
                if (remoteSongs.isEmpty()) {
                    NPLogger.d(TAG, "Skip batch download because all songs are local")
                    updateBatchProgressForSession(batchSessionId, null)
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

                        updateBatchProgressForSession(
                            batchSessionId,
                            BatchDownloadProgress(
                                totalSongs = trackedSongs.size,
                                completedSongs = completedSongs,
                                currentSong = currentSongLabel,
                                currentProgress = leadingEntry?.value,
                                currentSongIndex = currentSongIndex,
                                aggregateProgressFraction = aggregateProgressFraction
                            )
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
                updateBatchProgressForSession(
                    batchSessionId,
                    BatchDownloadProgress(
                        totalSongs = trackedSongs.size,
                        completedSongs = 0,
                        currentSong = "",
                        currentProgress = null,
                        aggregateProgressFraction = 0f
                    )
                )
                val workerCount = resolveBatchDownloadWorkerCount(
                    songCount = trackedSongs.size,
                    requestedParallelism = maxConcurrentDownloads
                )

                val progressJob = launch {
                    _progressFlow.collect { progress ->
                        if (progress == null) {
                            return@collect
                        }
                        if (!isBatchSessionCurrent(batchSessionId)) {
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

                suspend fun processTrackedSong(trackedSong: TrackedBatchSong) {
                    val song = trackedSong.song
                    val songKey = song.stableKey()
                    val attemptId = songAttemptIds[songKey]
                    GlobalDownloadManager.withSongExecutionLock(songKey) {
                        if (_isCancelled.value || !isBatchSessionCurrent(batchSessionId)) {
                            NPLogger.d(TAG, context.getString(R.string.download_cancelled_message))
                            markSongFinished(songKey)
                            invokeBatchCallback(song) { onSongCancelled(song) }
                            return@withSongExecutionLock
                        }
                        if (GlobalDownloadManager.isSongCancelled(songKey)) {
                            NPLogger.d(TAG, "跳过已取消的歌曲: ${song.name}")
                            clearSongCancelled(songKey)
                            markSongFinished(songKey)
                            invokeBatchCallback(song) { onSongCancelled(song) }
                            return@withSongExecutionLock
                        }
                        if (!GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId)) {
                            NPLogger.d(TAG, "跳过过期的批量下载项: ${song.name}")
                            markSongFinished(songKey)
                            return@withSongExecutionLock
                        }

                        try {
                            markSongStarted(trackedSong)
                            invokeBatchCallback(song) { onSongStarted(song) }
                            downloadSong(
                                context = context,
                                song = song,
                                batchSessionId = batchSessionId,
                                attemptId = attemptId
                            )
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

                try {
                    coroutineScope {
                        val nextSongIndex = AtomicInteger(0)
                        List(workerCount) {
                            launch {
                                while (true) {
                                    val songIndex = nextSongIndex.getAndIncrement()
                                    if (songIndex >= trackedSongs.size) {
                                        break
                                    }
                                    processTrackedSong(trackedSongs[songIndex])
                                }
                            }
                        }.joinAll()
                    }
                } finally {
                    progressJob.cancel()
                }

                updateBatchProgressForSession(batchSessionId, null)
            } catch (cancellation: java.util.concurrent.CancellationException) {
                batchSessionId?.let { updateBatchProgressForSession(it, null) }
                throw cancellation
            } catch (e: Exception) {
                NPLogger.e(TAG, context.getString(R.string.download_batch_failed, e.message ?: ""), e)
                batchSessionId?.let { updateBatchProgressForSession(it, null) }
            } finally {
                batchSessionId?.let(::finishBatchSession)
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
    fun cancelSongDownload(songKey: String) {
        networkPolicyPausedSongKeys.remove(songKey)
        snapshotActiveCalls(songKey).forEach { call ->
            call.cancel()
        }
        clearPublishedProgress(songKey)
        if (_progressFlow.value?.songKey == songKey) {
            _progressFlow.value = null
        }
    }

    /** 取消下载 */
    fun cancelDownload() {
        networkPolicyPausedSongKeys.clear()
        _isCancelled.value = true
        invalidateBatchSession()
        snapshotActiveCalls().forEach { call ->
            call.cancel()
        }
        _progressFlow.value = null
        _batchProgressFlow.value = null
        clearAllPublishedProgress()
    }

    fun pauseDownloadsForNetworkPolicy(songKeys: Collection<String>) {
        val normalizedKeys = songKeys
            .mapNotNull { it.takeIf(String::isNotBlank) }
            .distinct()
        if (normalizedKeys.isEmpty()) {
            return
        }
        networkPolicyPausedSongKeys.addAll(normalizedKeys)
        _isCancelled.value = true
        invalidateBatchSession()
        normalizedKeys
            .flatMap { songKey -> snapshotActiveCalls(songKey) }
            .distinct()
            .forEach { call -> call.cancel() }
        _progressFlow.value = null
        _batchProgressFlow.value = null
        normalizedKeys.forEach(::clearPublishedProgress)
    }

    fun isDownloadPausedForNetworkPolicy(songKey: String): Boolean {
        return networkPolicyPausedSongKeys.contains(songKey)
    }

    /** 重置取消标志 */
    fun resetCancelFlag() {
        _isCancelled.value = false
    }

    fun clearNetworkPolicyPause(songKeys: Collection<String>) {
        songKeys.forEach(networkPolicyPausedSongKeys::remove)
    }

    internal fun resolveTransientDownloadRetryDelayMs(attemptNumber: Int): Long {
        return when (attemptNumber.coerceAtLeast(1)) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 4_000L
            else -> 5_000L
        }
    }

    internal fun shouldRetryTransientDownloadFailure(error: Throwable): Boolean {
        if (error is java.util.concurrent.CancellationException) {
            return false
        }
        if (error is ChunkRequestIOException) {
            return isTransientHttpStatusCode(error.responseCode)
        }
        parseHttpStatusCode(error)?.let(::isTransientHttpStatusCode)?.let { shouldRetry ->
            return shouldRetry
        }
        return generateSequence(error) { it.cause }.any { cause ->
            when (cause) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                is InterruptedIOException,
                is EOFException,
                is SSLException -> true

                is SocketException -> true
                is IOException -> isTransientNetworkMessage(cause.message)
                else -> false
            }
        }
    }

    internal fun shouldRetryDownloadFailureForSource(
        error: Throwable,
        isYouTubeMusic: Boolean
    ): Boolean {
        if (shouldRetryTransientDownloadFailure(error)) {
            return true
        }
        return isYouTubeMusic && shouldRefreshYouTubeDownloadSourceOnFailure(error)
    }

    internal fun shouldRefreshYouTubeDownloadSourceOnFailure(error: Throwable): Boolean {
        if (error is java.util.concurrent.CancellationException) {
            return false
        }
        val statusCode = when (error) {
            is ChunkRequestIOException -> error.responseCode
            else -> parseHttpStatusCode(error)
        } ?: return false
        return isRefreshableYouTubeDownloadStatusCode(statusCode)
    }

    private fun isRefreshableYouTubeDownloadStatusCode(statusCode: Int): Boolean {
        return statusCode == 401 ||
            statusCode == 403 ||
            statusCode == 410 ||
            statusCode == 416 ||
            isTransientHttpStatusCode(statusCode)
    }

    private fun parseHttpStatusCode(error: Throwable): Int? {
        val message = error.message.orEmpty()
        return Regex("""HTTP\s+(\d{3})""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun isTransientHttpStatusCode(statusCode: Int): Boolean {
        return statusCode == 408 ||
            statusCode == 409 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode in 500..599
    }

    private fun isTransientNetworkMessage(message: String?): Boolean {
        val normalized = message?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return false
        }
        return normalized.contains("unexpected end of stream") ||
            normalized.contains("connection shutdown") ||
            normalized.contains("connection reset") ||
            normalized.contains("connection abort") ||
            normalized.contains("broken pipe") ||
            normalized.contains("software caused connection abort") ||
            normalized.contains("failed to connect") ||
            normalized.contains("unable to resolve host") ||
            normalized.contains("network is unreachable") ||
            normalized.contains("stream was reset") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out")
    }

    private suspend fun waitForRetryOrCancellation(
        context: Context,
        songKey: String,
        delayMs: Long,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ) {
        val initialDelayMs = delayMs.coerceAtLeast(0L)
        val startedOffline = !context.hasLikelyInternetAccess()
        var remainingMs = if (startedOffline) {
            maxOf(initialDelayMs, TRANSIENT_DOWNLOAD_OFFLINE_RECOVERY_WAIT_MS)
        } else {
            initialDelayMs
        }
        var recoveredOnlineAtMs: Long? = null
        var observedWakeSignalVersion = retryWakeSignalVersion.value
        while (remainingMs > 0L) {
            ensureSongDownloadNotCancelled(songKey, "retry_wait", batchSessionId, attemptId)
            val hasLikelyInternetNow = context.hasLikelyInternetAccess()
            if (startedOffline && hasLikelyInternetNow) {
                val nowMs = System.currentTimeMillis()
                val recoveredAtMs = recoveredOnlineAtMs ?: nowMs.also { recoveredOnlineAtMs = it }
                if (nowMs - recoveredAtMs >= TRANSIENT_DOWNLOAD_NETWORK_SETTLE_MS) {
                    return
                }
            } else {
                recoveredOnlineAtMs = null
            }
            val nextSliceMs = remainingMs.coerceAtMost(DOWNLOAD_RETRY_POLL_SLICE_MS)
            val wakeSignalResult = withTimeoutOrNull(nextSliceMs) {
                retryWakeSignalVersion.first { version ->
                    version != observedWakeSignalVersion
                }
            }
            if (wakeSignalResult != null) {
                observedWakeSignalVersion = wakeSignalResult
                if (!startedOffline || hasLikelyInternetNow || context.hasLikelyInternetAccess()) {
                    if (!startedOffline) {
                        return
                    }
                    val wakeAtMs = System.currentTimeMillis()
                    val recoveredAtMs = recoveredOnlineAtMs ?: wakeAtMs.also { recoveredOnlineAtMs = it }
                    if (wakeAtMs - recoveredAtMs >= TRANSIENT_DOWNLOAD_NETWORK_SETTLE_MS) {
                        return
                    }
                    continue
                }
            }
            remainingMs -= nextSliceMs
        }
        ensureSongDownloadNotCancelled(songKey, "retry_wait", batchSessionId, attemptId)
    }

    internal fun clampBatchDownloadParallelism(requestedParallelism: Int): Int {
        return normalizeDownloadParallelism(requestedParallelism)
    }

    internal suspend fun resolveConfiguredDownloadParallelism(context: Context): Int {
        val setting = AutoSettingsSchema.download.downloadParallelism
        val configuredValue = runCatching {
            context.applicationContext.autoSettingFlow(setting).first()
        }.getOrElse { error ->
            NPLogger.w(TAG, "读取下载线程数量失败，按默认值处理: ${error.message}")
            setting.defaultValue
        }
        return normalizeDownloadParallelism(configuredValue)
    }

    internal fun resolveBatchDownloadWorkerCount(
        songCount: Int,
        requestedParallelism: Int
    ): Int {
        if (songCount <= 0) {
            return 0
        }
        return clampBatchDownloadParallelism(requestedParallelism).coerceAtMost(songCount)
    }

    private fun resolveReadableManagedDownload(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? {
        ManagedDownloadStorage.peekDownloadedAudio(song)?.let { cachedAudio ->
            if (ManagedDownloadStorage.isReferenceAccessible(context, cachedAudio.playbackUri)) {
                return cachedAudio
            }
            NPLogger.w(
                TAG,
                "本地下载索引命中不可读音频，准备强制刷新: song=${song.name}, reference=${cachedAudio.playbackUri}"
            )
            GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
        }

        if (!canBlockStorageLookup()) {
            return null
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
            ?: if (ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context, restoreFromDisk = false)
            } else {
                null
            }
        if (snapshot == null) {
            return null
        }

        val indexedAudio = ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
        if (indexedAudio != null) {
            if (ManagedDownloadStorage.isReferenceAccessible(context, indexedAudio.playbackUri)) {
                return indexedAudio
            }
            NPLogger.w(
                TAG,
                "下载索引命中不可读音频，准备强制刷新: song=${song.name}, reference=${indexedAudio.playbackUri}"
            )
            GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
            return null
        }

        if (!GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return null
        }

        NPLogger.w(
            TAG,
            "下载目录缓存命中但快照索引未命中，回退目录缓存播放并后台对账: song=${song.name}"
        )
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
        return null
    }

    private fun hasFastCachedManagedDownloadForStart(
        context: Context,
        song: SongItem
    ): Boolean {
        if (ManagedDownloadStorage.peekDownloadedAudio(song) != null) {
            return true
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
        if (snapshot != null && ManagedDownloadStorage.findDownloadedAudio(snapshot, song) != null) {
            return true
        }
        return GlobalDownloadManager.findFastCachedDownloadedSongPlaybackUri(context, song) != null
    }

    private fun buildSeedDownloadedMetadataJson(
        song: SongItem
    ): String {
        val identity = song.identity()
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
            put("durationMs", song.durationMs.coerceAtLeast(0L))
            put("downloadFinalized", false)
        }
        return payload.toString()
    }

    internal fun resolveLocalLyricForDownload(rawLyric: String?): String? {
        return rawLyric?.takeIf { it.isNotBlank() }
    }

    internal fun shouldFetchRemoteLyricForDownload(rawLyric: String?): Boolean {
        return rawLyric == null
    }

    /** 下载歌词文件 */
    private suspend fun downloadLyrics(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        requireActiveAttempt: Boolean = true
    ): DownloadedSidecarReferences {
        var lyricReference: String? = null
        var translatedLyricReference: String? = null
        try {
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "lyrics_prepare",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
            var lyricText = resolveLocalLyricForDownload(song.matchedLyric)
            var translatedText = resolveLocalLyricForDownload(song.matchedTranslatedLyric)
            val shouldFetchPrimaryLyric = shouldFetchRemoteLyricForDownload(song.matchedLyric)
            val shouldFetchTranslatedLyric =
                shouldFetchRemoteLyricForDownload(song.matchedTranslatedLyric)
            if (lyricText != null) {
                NPLogger.d(TAG, context.getString(R.string.download_lyrics_matched, song.name))
            }
            val isYouTubeMusic = isYouTubeMusicSong(song)
            val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)

            when {
                isYouTubeMusic -> {
                    if (lyricText == null && shouldFetchPrimaryLyric) {
                        lyricText = downloadYouTubeMusicLyrics(song)
                    }
                }
                isBili -> { /* B站暂无歌词源 */ }
                else -> {
                    val downloaded = downloadNeteaseLyrics(
                        song = song,
                        shouldFetchPrimaryLyric = shouldFetchPrimaryLyric && lyricText == null,
                        shouldFetchTranslatedLyric = shouldFetchTranslatedLyric && translatedText == null
                    )
                    if (lyricText == null && shouldFetchPrimaryLyric) {
                        lyricText = downloaded.lyricText
                    }
                    if (translatedText == null && shouldFetchTranslatedLyric) {
                        translatedText = downloaded.translatedText
                    }
                }
            }

            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "lyrics_resolved",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
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
                        DownloadedSidecarReferences(
                            lyricReference = reference,
                            createdLyric = true
                        )
                    )
                    NPLogger.d(TAG, "歌词写入完成: song=${song.name}, reference=$reference")
                }
            }
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "lyrics_primary_written",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
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
                        DownloadedSidecarReferences(
                            translatedLyricReference = reference,
                            createdTranslatedLyric = true
                        )
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
            translatedLyricReference = translatedLyricReference,
            createdLyric = !lyricReference.isNullOrBlank(),
            createdTranslatedLyric = !translatedLyricReference.isNullOrBlank()
        )
    }

    /** 从 LRCLIB / YouTube Music API 获取歌词并保存 */
    private suspend fun downloadYouTubeMusicLyrics(
        song: SongItem
    ): String? {
        if (!shouldFetchRemoteLyricForDownload(song.matchedLyric)) return null
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
        song: SongItem,
        shouldFetchPrimaryLyric: Boolean = true,
        shouldFetchTranslatedLyric: Boolean = true
    ): DownloadedLyrics {
        if (!shouldFetchPrimaryLyric && !shouldFetchTranslatedLyric) {
            return DownloadedLyrics()
        }

        if (!shouldFetchPrimaryLyric) {
            try {
                val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
                val root = JSONObject(lyrics)
                if (root.optInt("code") == 200) {
                    val tlyric = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
                    if (shouldFetchTranslatedLyric && tlyric.isNotBlank()) {
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
            val preferredLyric = if (shouldFetchPrimaryLyric) {
                yrc.takeIf { it.isNotBlank() } ?: lrc.takeIf { it.isNotBlank() }
            } else {
                null
            }
            if (shouldFetchPrimaryLyric && yrc.isNotBlank()) {
                NPLogger.d(TAG, "从API获取逐字歌词保存: ${song.name}")
            }
            if (shouldFetchPrimaryLyric && lrc.isNotBlank()) {
                NPLogger.d(TAG, "从API获取歌词保存: ${song.name}")
            }
            if (shouldFetchTranslatedLyric && translated.isNotBlank()) {
                NPLogger.d(TAG, "从API获取翻译歌词保存: ${song.name}")
            }
            return DownloadedLyrics(
                lyricText = preferredLyric,
                translatedText = translated.takeIf {
                    shouldFetchTranslatedLyric && it.isNotBlank()
                }
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
        val songKey = song.stableKey()
        if (GlobalDownloadManager.isSongCancelled(songKey) || isSongDownloadActive(songKey)) {
            return null
        }
        return resolveReadableDownloadedPlaybackUri(context, song)
    }

    fun mayHaveIndexedLocalDownload(context: Context, song: SongItem): Boolean {
        if (GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return true
        }
        ManagedDownloadStorage.peekDownloadedAudio(song)?.let { return true }
        if (GlobalDownloadManager.isDownloadedSongCatalogReady()) {
            return false
        }
        if (!canBlockStorageLookup()) {
            return false
        }
        if (!ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
            return false
        }
        return ManagedDownloadStorage.peekDownloadedAudio(song) != null
    }

    fun hasLocalDownload(context: Context, song: SongItem): Boolean {
        val songKey = song.stableKey()
        if (GlobalDownloadManager.isSongCancelled(songKey) || isSongDownloadActive(songKey)) {
            return false
        }
        return resolveReadableDownloadedPlaybackUri(context, song) != null
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

    private fun resolveReadableDownloadedPlaybackUri(
        context: Context,
        song: SongItem
    ): String? {
        resolveReadableManagedDownload(context, song)?.playbackUri?.let { return it }
        val catalogPlaybackUri = GlobalDownloadManager.findAccessibleDownloadedSongPlaybackUri(
            context = context,
            song = song
        ) ?: return null
        NPLogger.d(
            TAG,
            "下载索引未命中，回退下载目录缓存播放: song=${song.name}, reference=$catalogPlaybackUri"
        )
        return catalogPlaybackUri
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

    private suspend fun resolveYouTubeMusic(
        song: SongItem,
        forceRefresh: Boolean = false
    ): ResolvedDownloadSource? {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri) ?: return null
        var directPlayableAudio: YouTubePlayableAudio? = null
        var fallbackPlayableAudio: YouTubePlayableAudio? = null
        for (attempt in resolveYouTubeDownloadResolveAttempts(forceRefresh)) {
            val candidate = resolveYouTubeMusicDownloadAudio(
                videoId = videoId,
                attempt = attempt
            ) ?: continue
            if (candidate.streamType == YouTubePlayableStreamType.DIRECT) {
                directPlayableAudio = candidate
                break
            }
            if (!attempt.requireDirect && fallbackPlayableAudio == null) {
                fallbackPlayableAudio = candidate
                break
            }
            NPLogger.w(
                TAG,
                "YouTube Music 下载直链策略返回非直链，继续降级: videoId=$videoId, mode=${attempt.logLabel}, type=${candidate.streamType}"
            )
        }
        val playableAudio = directPlayableAudio ?: fallbackPlayableAudio ?: return null
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

    private suspend fun resolveYouTubeMusicDownloadAudio(
        videoId: String,
        attempt: YouTubeDownloadResolveAttempt
    ): YouTubePlayableAudio? {
        val startedAtMs = System.currentTimeMillis()
        return try {
            val playableAudio = withTimeoutOrNull(attempt.timeoutMs) {
                val playbackRepository = if (attempt.shareInFlight) {
                    AppContainer.youtubeMusicPlaybackRepository
                } else {
                    AppContainer.youtubeMusicDownloadPlaybackRepository
                }
                playbackRepository.getBestPlayableAudio(
                    videoId = videoId,
                    forceRefresh = attempt.forceRefresh,
                    requireDirect = attempt.requireDirect,
                    preferM4a = true,
                    shareInFlight = attempt.shareInFlight
                )
            }
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            if (playableAudio == null) {
                NPLogger.w(
                    TAG,
                    "YouTube Music 下载解析未命中或超时: videoId=$videoId, mode=${attempt.logLabel}, timeoutMs=${attempt.timeoutMs}, elapsedMs=$elapsedMs"
                )
            } else {
                NPLogger.d(
                    TAG,
                    "YouTube Music 下载解析命中: videoId=$videoId, mode=${attempt.logLabel}, type=${playableAudio.streamType}, elapsedMs=$elapsedMs"
                )
            }
            playableAudio
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) {
                throw error
            }
            NPLogger.w(
                TAG,
                "YouTube Music 下载解析失败，切换下一策略: videoId=$videoId, mode=${attempt.logLabel}, ${error.javaClass.simpleName} - ${error.message}"
            )
            null
        }
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
        displayFileName: String,
        songId: Long,
        songKey: String,
        totalBytesHint: Long,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ): DownloadedPayloadSummary = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始 HLS 下载文件: ${destFile.name}, songId=$songId")

        val playlistText = executeTrackedCall(
            client = client,
            request = playlistRequest,
            songKey = songKey
        ) { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body.string()
        }
        val segmentUrls = parseHlsSegmentUrls(playlistRequest.url.toString(), playlistText)
        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("HLS playlist contains no segments")
        }
        val playlistFingerprint = segmentUrls.joinToString("\n").hashCode()
        val resolvedResumeState = resolveHlsResumeState(destFile, playlistFingerprint)
            ?.takeIf { resumeState ->
                resumeState.nextSegmentIndex in 0..segmentUrls.size &&
                    destFile.exists() &&
                    destFile.length().coerceAtLeast(0L) == resumeState.downloadedBytes
            }
        if (resolvedResumeState == null && resolveWorkingFileBytes(destFile) > 0L) {
            deleteWorkingFile(destFile)
        }
        val resumeSegmentIndex = resolvedResumeState?.nextSegmentIndex ?: 0
        val attemptStartBytes = resolvedResumeState?.downloadedBytes?.coerceAtLeast(0L) ?: 0L
        if (resumeSegmentIndex > 0) {
            NPLogger.d(
                TAG,
                "恢复 HLS 下载: ${destFile.name}, segment=$resumeSegmentIndex/${segmentUrls.size}, bytes=$attemptStartBytes, songId=$songId"
            )
        }

        val headerMap = playlistRequest.headers.names().associateWith { name ->
            playlistRequest.header(name).orEmpty()
        }

        var downloadedBytes = attemptStartBytes
        val trafficAccumulator = newDownloadTrafficAccumulator()
        try {
            FileOutputStream(destFile, resumeSegmentIndex > 0).sink().buffer().use { sink ->
                segmentUrls.drop(resumeSegmentIndex).forEachIndexed { relativeIndex, segmentUrl ->
                    val index = resumeSegmentIndex + relativeIndex
                    ensureDownloadNotCancelled(songId, songKey, destFile, batchSessionId, attemptId)
                    val segmentRequest = Request.Builder()
                        .url(segmentUrl)
                        .apply {
                            headerMap.forEach { (name, value) ->
                                header(name, value)
                            }
                        }
                        .build()

                    executeTrackedCall(
                        client = client,
                        request = segmentRequest,
                        songKey = songKey
                    ) { response ->
                        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                        val rawPayload = response.body.bytes()
                        trafficAccumulator.add(rawPayload.size.toLong())
                        val payload = stripLeadingId3(rawPayload)
                        sink.write(payload)
                        downloadedBytes += payload.size.toLong()
                    }
                    rememberHlsResumeState(
                        destFile = destFile,
                        playlistFingerprint = playlistFingerprint,
                        nextSegmentIndex = index + 1,
                        downloadedBytes = downloadedBytes
                    )

                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0)
                        .coerceAtLeast(0.001)
                    val attemptTransferredBytes = (downloadedBytes - attemptStartBytes).coerceAtLeast(0L)
                    publishProgress(
                        DownloadProgress(
                            songKey = songKey,
                            songId = songId,
                            fileName = resolveVisibleDownloadFileName(displayFileName, destFile.name),
                            bytesRead = downloadedBytes,
                            totalBytes = totalBytesHint,
                            speedBytesPerSec = (attemptTransferredBytes / elapsedSec).toLong(),
                            attemptId = attemptId
                        )
                    )
                    if ((index + 1) % 8 == 0) {
                        sink.flush()
                    }
                }
                sink.flush()
            }
        } finally {
            trafficAccumulator.flush()
        }
        clearHlsResumeState(destFile)

        NPLogger.d(
            TAG,
            "HLS 下载完成: ${destFile.name}, 实际大小: $downloadedBytes bytes, segments=${segmentUrls.size}, songId=$songId"
        )
        return@withContext DownloadedPayloadSummary(
            actualBytes = downloadedBytes,
            expectedBytes = totalBytesHint.takeIf { it > 0L }
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
        displayFileName: String,
        songId: Long,
        songKey: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ): DownloadedPayloadSummary = withContext(Dispatchers.IO) {
        if (YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(request) &&
            !YouTubeGoogleVideoRangeSupport.hasExplicitRangeHeader(
                request.headers.names().associateWith { headerName ->
                    request.header(headerName).orEmpty()
                }
            )
        ) {
            return@withContext singleThreadChunkedDownload(
                client = client,
                request = request,
                destFile = destFile,
                displayFileName = displayFileName,
                songId = songId,
                songKey = songKey,
                batchSessionId = batchSessionId,
                attemptId = attemptId
            )
        }

        val startNs = System.nanoTime()
        val resumedBytes = resolveWorkingFileBytes(destFile)
        val resumeRangeHeader = buildResumeRangeHeader(resumedBytes)
        val effectiveRequest = if (resumeRangeHeader != null) {
            NPLogger.d(
                TAG,
                "恢复直链下载: ${destFile.name}, bytes=$resumedBytes, songId=$songId"
            )
            request.newBuilder()
                .header("Range", resumeRangeHeader)
                .build()
        } else {
            request
        }
        NPLogger.d(TAG, "开始下载文件: ${destFile.name}, songId=$songId")
        return@withContext executeTrackedCall(
            client = client,
            request = effectiveRequest,
            songKey = songKey
        ) { resp ->
            val responseHeaders = resp.headers.toMultimap()
            if (resumedBytes > 0L && resp.code == 416) {
                val expectedBytes = resolveResponseExpectedBytes(
                    requestUrl = request.url.toString(),
                    headers = responseHeaders,
                    bodyLength = resp.body.contentLength(),
                    resumedBytes = resumedBytes,
                    isPartialResponse = true
                )
                if (expectedBytes != null && resumedBytes >= expectedBytes) {
                    return@executeTrackedCall DownloadedPayloadSummary(
                        actualBytes = resumedBytes,
                        expectedBytes = expectedBytes
                    )
                }
                throw IllegalStateException("HTTP ${resp.code}")
            }
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")

            val appending = resumedBytes > 0L && resp.code == 206
            if (resumedBytes > 0L && !appending) {
                NPLogger.w(TAG, "服务端未接受续传，回退整文件重下: ${destFile.name}, code=${resp.code}")
            }
            if (appending) {
                val resumedStart = parseContentRangeStart(responseHeaders)
                if (resumedStart != null && resumedStart != resumedBytes) {
                    throw IOException(
                        "续传偏移不匹配: expected=$resumedBytes, actual=$resumedStart"
                    )
                }
            }
            val initialBytes = if (appending) resumedBytes else 0L
            if (!appending && resumedBytes > 0L) {
                deleteWorkingFile(destFile)
            }
            val total = resolveResponseExpectedBytes(
                requestUrl = request.url.toString(),
                headers = responseHeaders,
                bodyLength = resp.body.contentLength(),
                resumedBytes = initialBytes,
                isPartialResponse = appending
            ) ?: 0L
            NPLogger.d(TAG, "文件总大小: $total bytes, songId=$songId")
            val source = resp.body.source()
            var readSoFar = initialBytes
            val trafficAccumulator = newDownloadTrafficAccumulator()
            try {
                FileOutputStream(destFile, appending).sink().buffer().use { sink ->
                    val buffer = Buffer()
                    while (true) {
                        ensureDownloadNotCancelled(songId, songKey, destFile, batchSessionId, attemptId)

                        val read = source.read(buffer, DOWNLOAD_READ_BUFFER_BYTES)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        trafficAccumulator.add(read)
                        readSoFar += read
                        val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                        val speed = ((readSoFar - initialBytes) / elapsedSec).toLong()
                        val progress = DownloadProgress(
                            songKey = songKey,
                            songId = songId,
                            fileName = resolveVisibleDownloadFileName(displayFileName, destFile.name),
                            bytesRead = readSoFar,
                            totalBytes = total,
                            speedBytesPerSec = speed,
                            attemptId = attemptId
                        )
                        publishProgress(progress)
                    }
                    sink.flush()
                }
            } finally {
                trafficAccumulator.flush()
            }
            NPLogger.d(TAG, "文件下载完成: ${destFile.name}, 实际大小: $readSoFar bytes, songId=$songId")
            if (!isTransferSizeComplete(total.takeIf { it > 0L }, readSoFar)) {
                throw IOException("下载文件不完整: ${destFile.name}, $readSoFar/$total")
            }
            DownloadedPayloadSummary(
                actualBytes = readSoFar,
                expectedBytes = total.takeIf { it > 0L }
            )
        }
    }

    private suspend fun singleThreadChunkedDownload(
        client: okhttp3.OkHttpClient,
        request: Request,
        destFile: File,
        displayFileName: String,
        songId: Long,
        songKey: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ): DownloadedPayloadSummary = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始分块下载文件: ${destFile.name}, songId=$songId")

        val resumedBytes = resolveWorkingFileBytes(destFile)
        if (resumedBytes > 0L) {
            NPLogger.d(TAG, "恢复分块下载: ${destFile.name}, bytes=$resumedBytes, songId=$songId")
        }

        var downloadedBytes = resumedBytes
        var totalBytes = YouTubeGoogleVideoRangeSupport.resolveQueryContentLength(request.url.toString()) ?: 0L
        FileOutputStream(destFile, resumedBytes > 0L).sink().buffer().use { sink ->
            while (true) {
                ensureDownloadNotCancelled(songId, songKey, destFile, batchSessionId, attemptId)

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
                            displayFileName = displayFileName,
                            songId = songId,
                            songKey = songKey,
                            destFile = destFile,
                            startNs = startNs,
                            attemptStartBytes = resumedBytes,
                            currentDownloadedBytes = downloadedBytes,
                            currentTotalBytes = totalBytes,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId
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
                    val alreadyComplete = totalBytes > 0L && downloadedBytes >= totalBytes
                    if (error.responseCode == 416 && downloadedBytes > 0L) {
                        // 416 = range 越界，通常意味着当前 offset 已经到尾部
                        break
                    }
                    if (error.responseCode == 403 && alreadyComplete) {
                        // 403 = CDN 拒绝，只有在总长度已满足时才接受为完成
                        break
                    }
                    throw error
                }
            }
            sink.flush()
        }

        NPLogger.d(TAG, "分块下载完成: ${destFile.name}, 实际大小: $downloadedBytes bytes, songId=$songId")
        val expectedBytes = totalBytes.takeIf { it > 0L }
        if (!isTransferSizeComplete(expectedBytes, downloadedBytes)) {
            throw IOException("分块下载不完整: ${destFile.name}, $downloadedBytes/$expectedBytes")
        }
        return@withContext DownloadedPayloadSummary(
            actualBytes = downloadedBytes,
            expectedBytes = expectedBytes
        )
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
        displayFileName: String,
        songId: Long,
        songKey: String,
        destFile: File,
        startNs: Long,
        attemptStartBytes: Long,
        currentDownloadedBytes: Long,
        currentTotalBytes: Long,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ): ChunkDownloadResult {
        val chunkRequest = YouTubeGoogleVideoRangeSupport.buildChunkedRequest(
            request = request,
            start = start,
            length = requestedChunkLength
        )

        val trafficAccumulator = newDownloadTrafficAccumulator()
        try {
            executeTrackedCall(
                client = client,
                request = chunkRequest,
                songKey = songKey
            ) { response ->
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
                    ensureDownloadNotCancelled(songId, songKey, destFile, batchSessionId, attemptId)

                    val read = source.read(buffer, DOWNLOAD_READ_BUFFER_BYTES)
                    if (read == -1L) {
                        break
                    }
                    sink.write(buffer, read)
                    trafficAccumulator.add(read)
                    chunkRead += read
                    downloadedBytes += read

                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0)
                        .coerceAtLeast(0.001)
                    val speed = ((downloadedBytes - attemptStartBytes) / elapsedSec).toLong()
                    publishProgress(
                        DownloadProgress(
                            songKey = songKey,
                            songId = songId,
                            fileName = resolveVisibleDownloadFileName(displayFileName, destFile.name),
                            bytesRead = downloadedBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speed,
                            attemptId = attemptId
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
        } finally {
            trafficAccumulator.flush()
        }
    }

    private fun ensureDownloadNotCancelled(
        songId: Long,
        songKey: String,
        destFile: File,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ) {
        if (
            _isCancelled.value ||
            !isBatchSessionCurrent(batchSessionId) ||
            GlobalDownloadManager.isSongCancelled(songKey) ||
            !GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId)
        ) {
            NPLogger.d(TAG, "下载被取消，停止分块下载: songId=$songId")
            if (!shouldPreserveArtifactsForNetworkPolicy(songKey)) {
                deleteWorkingFile(destFile)
            }
            _progressFlow.value = null
            throw java.util.concurrent.CancellationException("Download cancelled")
        }
    }
}

internal fun resolveVisibleDownloadFileName(
    targetFileName: String?,
    fallbackTempFileName: String
): String {
    return targetFileName
        ?.takeIf(String::isNotBlank)
        ?: fallbackTempFileName
}
