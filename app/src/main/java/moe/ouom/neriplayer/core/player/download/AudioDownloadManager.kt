@file:Suppress("SpellCheckingInspection")

package moe.ouom.neriplayer.core.player.download

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
 * File: moe.ouom.neriplayer.core.player.download/AudioDownloadManager
 * Created: 2025/8/20
 */

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadCoreCommitPhase
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.clearSongCancelled
import moe.ouom.neriplayer.core.download.ManagedDownloadSizePolicy
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.boundManagedDownloadFileName
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_PREFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_SUFFIX
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.policy.shouldUseIndexedSidecarLookup
import moe.ouom.neriplayer.core.download.downloadedSongPlaybackReferenceCandidates
import moe.ouom.neriplayer.core.download.resolveDownloadedSongPlaybackReference
import moe.ouom.neriplayer.core.download.shouldRollbackCancelledAudio
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.resolver.netease.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.core.player.resolver.youtube.ChunkRequestIOException
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeGoogleVideoRangeSupport
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playbackVisualKey
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isTrustedYouTubeHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeWebRemixDirectMissingPoToken
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.settings.resolveDownloadAudioQualitySelection
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.TrafficUsageSource
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkTypeOrNull
import moe.ouom.neriplayer.data.traffic.downloadNetworkTypeOrNull
import moe.ouom.neriplayer.data.traffic.hasConfirmedInternetAccess
import moe.ouom.neriplayer.data.traffic.validatedTrafficNetworkTypeOrNull
import moe.ouom.neriplayer.util.io.readBytesLimited
import okhttp3.Dispatcher
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLConnection
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

internal class CoverDownloadSingleFlight<K : Any, V> {
    private val flights = ConcurrentHashMap<K, CompletableDeferred<Outcome<V>>>()

    internal val inFlightCount: Int
        get() = flights.size

    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            val created = CompletableDeferred<Outcome<V>>()
            val active = flights.putIfAbsent(key, created)
            if (active == null) {
                return try {
                    val value = block()
                    created.complete(Outcome.Completed(value))
                    flights.remove(key, created)
                    value
                } catch (cancellation: java.util.concurrent.CancellationException) {
                    flights.remove(key, created)
                    created.complete(Outcome.Retry)
                    throw cancellation
                } catch (error: Throwable) {
                    created.complete(Outcome.Failed(error))
                    flights.remove(key, created)
                    throw error
                }
            }
            when (val outcome = active.await()) {
                is Outcome.Completed -> return outcome.value
                is Outcome.Failed -> throw outcome.error
                Outcome.Retry -> Unit
            }
        }
    }

    private sealed interface Outcome<out V> {
        data class Completed<V>(val value: V) : Outcome<V>

        data class Failed(val error: Throwable) : Outcome<Nothing>

        data object Retry : Outcome<Nothing>
    }
}

/**
 * 音频下载管理器: 解析来源 (网易云 / Bilibili) 并保存到本地目录
 * - 不依赖系统 DownloadManager, 直接用共享 OkHttpClient, 实现自定义 Header 与代理
 * - 默认保存路径: /Android/data/<package>/files/Music/NeriPlayer/<Artist - Title>.<ext>
 * - 支持通过 SAF 将下载目录切换到自定义文件夹
 */
object AudioDownloadManager {

    private const val TAG = "NERI-Downloader"
    private val SHA256_HEX_REGEX = Regex("[0-9a-fA-F]{64}")
    private const val BILI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val BILI_REFERER = "https://www.bilibili.com"
    internal const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = DEFAULT_DOWNLOAD_PARALLELISM
    internal const val MAX_CONCURRENT_DOWNLOADS_LIMIT = MAX_DOWNLOAD_PARALLELISM
    private const val BATCH_COMPLETION_CALLBACK_PARALLELISM = 2
    private const val PROGRESS_EMIT_INTERVAL_NS = 180_000_000L
    private const val PROGRESS_EMIT_MIN_BYTES_DELTA = 256L * 1024L
    private const val PROGRESS_EVENT_BUFFER_CAPACITY = 64
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
    private const val MAX_INLINE_SIDECAR_LYRIC_BYTES = 512L * 1024L
    private const val LEGACY_INDEX_REFRESH_COOLDOWN_MS = 5_000L
    /** core 提交和目录索引发布之间允许播放入口复用已校验引用的最长时间 */
    private const val COMPLETED_AUDIO_REFERENCE_RETENTION_MS = 2 * 60 * 1_000L
    private const val COMPLETED_AUDIO_REFERENCE_MAX_ENTRIES = 512
    private const val MANAGED_PLAYBACK_REBIND_COOLDOWN_MS = 1_500L
    private const val DIRECTORY_MUTATION_PLAYBACK_WAIT_MS = 1_200L
    private const val YOUTUBE_DOWNLOAD_SHARED_DIRECT_RESOLVE_TIMEOUT_MS = 3_500L
    private const val YOUTUBE_DOWNLOAD_FRESH_DIRECT_RESOLVE_TIMEOUT_MS = 18_000L
    private const val YOUTUBE_DOWNLOAD_SHARED_PLAYABLE_RESOLVE_TIMEOUT_MS = 6_000L
    private const val YOUTUBE_DOWNLOAD_FRESH_PLAYABLE_RESOLVE_TIMEOUT_MS = 18_000L

    private fun canBlockStorageLookup(): Boolean {
        return Looper.myLooper() != Looper.getMainLooper()
    }
    private const val DOWNLOAD_READ_BUFFER_BYTES = 64L * 1024L
    private const val YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES = 4L * 1024L * 1024L
    private const val MAX_HLS_PLAYLIST_BYTES = 1L * 1024L * 1024L
    private const val MAX_HLS_SEGMENT_BYTES = 64L * 1024L * 1024L
    internal const val MAX_COVER_RESPONSE_BYTES = 16L * 1024L * 1024L

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
    private val progressEventStream =
        DownloadProgressEventStream<DownloadProgress>(PROGRESS_EVENT_BUFFER_CAPACITY)
    val progressEvents: SharedFlow<DownloadProgress> = progressEventStream.events
    
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
        ConcurrentHashMap<String, CompletedAudioReference>()
    private val completedAudioReferencesByReference =
        ConcurrentHashMap<String, CompletedAudioReference>()
    /** 多张映射必须一起替换，否则首播可能读到旧 URI 别名 */
    private val completedAudioReferenceMutationLock = Any()
    private val managedPlaybackRebindAtMsBySongKey = ConcurrentHashMap<String, Long>()
    private val partialSidecarReferencesBySongKey =
        ConcurrentHashMap<String, DownloadedSidecarReferences>()
    private val coverDownloadSingleFlight =
        CoverDownloadSingleFlight<CoverDownloadFlightKey, CachedCoverReference?>()
    private val hlsResumeStatesByWorkingPath =
        ConcurrentHashMap<String, HlsResumeState>()
    private val retryWakeSignalVersion = MutableStateFlow(0L)
    private val activeCallsBySongKey =
        ConcurrentHashMap<String, MutableSet<okhttp3.Call>>()
    private val networkPolicyPausedSongKeys =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val networkPolicyMutationLock = Any()
    private val activeSongOperationCounts = ConcurrentHashMap<String, Int>()
    private val batchSessionLock = Any()
    private val networkRecoveryMonitorLock = Any()
    private var lastConfirmedInternetAccess = false

    private data class CoverDownloadFlightKey(
        val songKey: String,
        val fileName: String
    )

    private data class CompletedAudioReference(
        val audio: ManagedDownloadStorage.StoredEntry,
        val committedAtMs: Long,
        val songLookupKeys: Set<String>,
        val rootGeneration: Long
    )

    private data class CachedManagedAudio(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        val audio: ManagedDownloadStorage.StoredEntry
    )

    private data class CachedCoverReference(
        val reference: String,
        val created: Boolean
    )

    @Volatile
    private var lastRecoveryOpportunityAtMs = 0L

    @Volatile
    private var lastLegacyIndexRefreshAtMs = 0L

    private fun requestBackgroundDownloadIndexRefresh(context: Context) {
        val nowMs = System.currentTimeMillis()
        val shouldRequest = synchronized(this) {
            val previousAtMs = lastLegacyIndexRefreshAtMs
            if (previousAtMs in 1..nowMs &&
                nowMs - previousAtMs < LEGACY_INDEX_REFRESH_COOLDOWN_MS
            ) {
                false
            } else {
                lastLegacyIndexRefreshAtMs = nowMs
                true
            }
        }
        if (!shouldRequest) {
            return
        }
        NPLogger.d(TAG, "本地音频已可读，后台轻量刷新下载索引")
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
    }

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

    private val downloadNetworkPolicyTracker = DownloadNetworkPolicyTracker()

    fun isSongDownloadActive(songKey: String): Boolean {
        return (activeSongOperationCounts[songKey] ?: 0) > 0
    }

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        synchronized(networkRecoveryMonitorLock) {
            if (networkRecoveryMonitorRegistered) {
                return
            }
            val connectivityManager: ConnectivityManager =
                appContext.getSystemService(ConnectivityManager::class.java) ?: return
            val initialNetwork = connectivityManager.activeNetwork
            val initialNetworkType = initialNetwork
                ?.let { network -> connectivityManager.getNetworkCapabilities(network) }
                ?.downloadNetworkTypeOrNull()
            downloadNetworkPolicyTracker.seed(initialNetwork, initialNetworkType)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleDefaultDownloadNetworkCallback(
                        context = appContext,
                        connectivityManager = connectivityManager,
                        callbackNetwork = network,
                        reason = "network_available"
                    )
                    if (shouldNotifyRecoveryForConfirmedInternet(appContext)) {
                        notifyRecoveryOpportunity("network_available")
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    _networkCapabilities: NetworkCapabilities
                ) {
                    handleDefaultDownloadNetworkCallback(
                        context = appContext,
                        connectivityManager = connectivityManager,
                        callbackNetwork = network,
                        reason = "network_capabilities_changed"
                    )
                    if (shouldNotifyRecoveryForConfirmedInternet(appContext)) {
                        notifyRecoveryOpportunity("network_available")
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(networkRecoveryMonitorLock) {
                        lastConfirmedInternetAccess = false
                    }
                    handleDefaultDownloadNetworkLost(
                        context = appContext,
                        connectivityManager = connectivityManager,
                        network = network
                    )
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

    private fun shouldNotifyRecoveryForConfirmedInternet(context: Context): Boolean {
        val confirmed = context.hasConfirmedInternetAccess()
        synchronized(networkRecoveryMonitorLock) {
            val shouldNotify = shouldTriggerNetworkRecovery(
                wasConfirmed = lastConfirmedInternetAccess,
                isConfirmed = confirmed
            )
            lastConfirmedInternetAccess = confirmed
            return shouldNotify
        }
    }

    private fun handleDefaultDownloadNetworkCallback(
        context: Context,
        connectivityManager: ConnectivityManager,
        callbackNetwork: Network,
        reason: String
    ) {
        val activeNetwork = runCatching { connectivityManager.activeNetwork }
            .getOrElse { error ->
                NPLogger.d(
                    TAG,
                    "忽略网络回调: 无法读取 activeNetwork, error=${error.message}"
                )
                return
        }
        if (activeNetwork != callbackNetwork) {
            NPLogger.d(
                TAG,
                "忽略过时网络回调: callback=$callbackNetwork, " +
                    "active=$activeNetwork, reason=$reason"
            )
            // 回调可能在系统切换默认网络的窗口内到达，直接以当前 active
            // 快照收敛一次，避免旧 WIFI 事件把策略留在错误状态
            val currentNetwork = activeNetwork ?: return
            val currentType = runCatching {
                connectivityManager.getNetworkCapabilities(currentNetwork)
                    ?.downloadNetworkTypeOrNull()
            }.getOrElse { error ->
                NPLogger.d(
                    TAG,
                    "忽略过时网络回调的 active 快照: 无法读取 capabilities, " +
                        "error=${error.message}"
                )
                return
            } ?: return
            handleDefaultDownloadNetworkObserved(
                context = context,
                network = currentNetwork,
                networkType = currentType,
                reason = "${reason}_active_snapshot",
                activeNetworkKnown = true
            )
            return
        }
        val activeType = runCatching {
            connectivityManager.getNetworkCapabilities(activeNetwork)
                ?.downloadNetworkTypeOrNull()
        }.getOrElse { error ->
            NPLogger.d(
                TAG,
                "忽略网络回调: 无法读取 capabilities, error=${error.message}"
            )
            return
        } ?: run {
            NPLogger.d(TAG, "忽略网络回调: active capabilities 尚未稳定, reason=$reason")
            return
        }
        handleDefaultDownloadNetworkObserved(
            context = context,
            network = callbackNetwork,
            networkType = activeType,
            reason = reason,
            activeNetworkKnown = true
        )
    }

    private fun handleDefaultDownloadNetworkObserved(
        context: Context,
        network: Network,
        networkType: TrafficNetworkType,
        reason: String,
        activeNetworkKnown: Boolean = true
    ) {
        val observation = downloadNetworkPolicyTracker.observeDefaultNetwork(
            networkKey = network,
            networkType = networkType,
            activeNetworkKey = network,
            activeNetworkKnown = activeNetworkKnown
        )
        if (observation.becameWifi) {
            GlobalDownloadManager.onWifiBoundDownloadNetworkRestored(
                context = context,
                reason = reason
            )
            GlobalDownloadManager.scheduleWifiRecoveryProbe(
                context = context,
                reason = reason
            )
        }
        if (observation.shouldPause) {
            interruptDownloadsForWifiLoss(
                networkType = networkType,
                reason = reason
            )
        }
    }

    private fun handleDefaultDownloadNetworkLost(
        context: Context,
        connectivityManager: ConnectivityManager,
        network: Network
    ) {
        val activeNetworkSnapshot = runCatching { connectivityManager.activeNetwork }
            .getOrElse { error ->
                NPLogger.d(
                    TAG,
                    "忽略网络丢失回调: 无法读取 activeNetwork, error=${error.message}"
                )
                return
            }
        val shouldPause = downloadNetworkPolicyTracker.onDefaultNetworkLost(
            networkKey = network,
            activeNetworkKey = activeNetworkSnapshot,
            activeNetworkKnown = true
        )
        val nextNetworkType = context.currentDownloadNetworkTypeOrNull()
        if (nextNetworkType == TrafficNetworkType.WIFI) {
            // onLost 可能和新的 WIFI 回调竞态，这里补一次恢复触发
            // 避免漏掉回调后等待中的下载一直停住
            GlobalDownloadManager.scheduleWifiRecoveryProbe(
                context = context,
                reason = "network_lost_replacement_wifi"
            )
            return
        }
        if (!shouldPause) {
            return
        }
        downloadNetworkPolicyTracker.markWifiLossHandled()
        interruptDownloadsForWifiLoss(
            networkType = nextNetworkType,
            reason = "network_lost"
        )
    }

    private fun interruptDownloadsForWifiLoss(
        networkType: TrafficNetworkType?,
        reason: String
    ) {
        if (networkType != TrafficNetworkType.WIFI) {
            NPLogger.w(
                TAG,
                "WIFI 下载环境已切换，准备中断下载: reason=$reason, " +
                    "nextType=${networkType ?: "UNKNOWN"}"
            )
            GlobalDownloadManager.interruptDownloadsForWifiDisconnected(networkType)
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

    internal data class PublishedProgressState(
        val attemptId: Long?,
        val bytesRead: Long,
        val totalBytes: Long,
        val percentage: Int,
        val stage: DownloadStage,
        val emittedAtNs: Long
    )

    internal fun shouldPublishAudioDownloadProgress(
        previous: PublishedProgressState?,
        progress: DownloadProgress,
        nowNs: Long,
        force: Boolean = false
    ): Boolean {
        if (force || previous == null) {
            return true
        }
        if (previous.attemptId != progress.attemptId) {
            return true
        }
        val enoughTimeElapsed = nowNs - previous.emittedAtNs >= PROGRESS_EMIT_INTERVAL_NS
        val completedTransfer = progress.stage != DownloadStage.TRANSFERRING ||
            (progress.totalBytes > 0L && progress.bytesRead >= progress.totalBytes)
        if (progress.stage != previous.stage || completedTransfer) {
            return true
        }
        if (!enoughTimeElapsed) {
            return false
        }
        if (progress.totalBytes != previous.totalBytes) {
            return true
        }
        if (progress.totalBytes <= 0L) {
            return progress.bytesRead > previous.bytesRead
        }
        val bytesDelta = progress.bytesRead - previous.bytesRead
        val absoluteBytesDelta = if (bytesDelta >= 0L) bytesDelta else -bytesDelta
        return progress.percentage != previous.percentage ||
            absoluteBytesDelta >= PROGRESS_EMIT_MIN_BYTES_DELTA
    }

    internal data class DownloadedSidecarReferences(
        val coverReference: String? = null,
        val lyricReference: String? = null,
        val translatedLyricReference: String? = null,
        val romanizedLyricReference: String? = null,
        val expectedCover: Boolean = false,
        val expectedLyric: Boolean = false,
        val expectedTranslatedLyric: Boolean = false,
        val expectedRomanizedLyric: Boolean = false,
        val createdCover: Boolean = false,
        val createdLyric: Boolean = false,
        val createdTranslatedLyric: Boolean = false,
        val createdRomanizedLyric: Boolean = false,
        /** 保留当前流程已经读到的歌词，嵌入元信息时不再重复读取 SAF 旁车 */
        val lyricContent: String? = null,
        val translatedLyricContent: String? = null,
        val romanizedLyricContent: String? = null
    ) {
        val isEmpty: Boolean
            get() = coverReference.isNullOrBlank() &&
                lyricReference.isNullOrBlank() &&
                translatedLyricReference.isNullOrBlank() &&
                romanizedLyricReference.isNullOrBlank() &&
                !expectedCover &&
                !expectedLyric &&
                !expectedTranslatedLyric &&
                !expectedRomanizedLyric

        fun retainCreatedOnly(): DownloadedSidecarReferences {
            return DownloadedSidecarReferences(
                coverReference = coverReference.takeIf { createdCover },
                lyricReference = lyricReference.takeIf { createdLyric },
                translatedLyricReference = translatedLyricReference.takeIf { createdTranslatedLyric },
                romanizedLyricReference = romanizedLyricReference.takeIf { createdRomanizedLyric },
                expectedCover = expectedCover,
                expectedLyric = expectedLyric,
                expectedTranslatedLyric = expectedTranslatedLyric,
                expectedRomanizedLyric = expectedRomanizedLyric,
                createdCover = createdCover && !coverReference.isNullOrBlank(),
                createdLyric = createdLyric && !lyricReference.isNullOrBlank(),
                createdTranslatedLyric = createdTranslatedLyric && !translatedLyricReference.isNullOrBlank(),
                createdRomanizedLyric = createdRomanizedLyric && !romanizedLyricReference.isNullOrBlank(),
                lyricContent = lyricContent.takeIf { createdLyric },
                translatedLyricContent = translatedLyricContent.takeIf {
                    createdTranslatedLyric
                },
                romanizedLyricContent = romanizedLyricContent.takeIf {
                    createdRomanizedLyric
                }
            )
        }
    }

    private data class DownloadedPayloadSummary(
        val actualBytes: Long,
        val expectedBytes: Long?,
        val resumeMetadataAvailable: Boolean = true
    )

    private class DownloadCoreCommitTracker(
        var phase: DownloadCoreCommitPhase = DownloadCoreCommitPhase.STAGING
    )

    private data class CoreCommittedAudio(
        val audio: ManagedDownloadStorage.StoredEntry,
        val transferredBytes: Long
    )

    internal data class HlsResumeState(
        val playlistFingerprint: String,
        val nextSegmentIndex: Int,
        val downloadedBytes: Long,
        val durablePrefixSha256: String = "",
        val operationId: String = "",
        val mediaSequence: Long? = null
    ) {
        val durableBytes: Long
            get() = downloadedBytes
    }

    internal data class ParsedContentRange(
        val start: Long,
        val end: Long,
        val total: Long
    ) {
        val length: Long
            get() = end - start + 1L
    }

    internal fun buildCoverDownloadCandidateUrls(song: SongItem): List<String> {
        return listOf(
            song.displayCoverUrl(),
            song.coverUrl,
            song.originalCoverUrl,
            song.customCoverUrl
        ).mapNotNull { candidate ->
            candidate
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeIf(::isNetworkCoverUrl)
        }.distinct()
    }

    private fun isNetworkCoverUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    internal fun isTransferSizeComplete(expectedBytes: Long?, actualBytes: Long): Boolean {
        val expectedSize = expectedBytes?.takeIf { it > 0L }
        if (expectedSize != null && actualBytes < expectedSize) {
            return false
        }
        return ManagedDownloadSizePolicy.isTransferSizeComplete(
            expectedSizeBytes = expectedSize,
            actualSizeBytes = actualBytes
        )
    }

    /**
     * 原始传输长度只适用于 TagLib 修改前的文件
     */
    internal fun resolveAudioCommitExpectedSize(
        transferExpectedBytes: Long?,
        bytesBeforeMetadata: Long,
        bytesAtCommit: Long
    ): Long? {
        val expectedBytes = transferExpectedBytes?.takeIf { it > 0L } ?: return null
        return expectedBytes.takeIf { bytesBeforeMetadata == bytesAtCommit }
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
            YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(request) &&
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

    internal fun resolveResumeValidatorHeader(
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): String? {
        return fingerprint?.etag?.trim()?.takeIf(::isStrongEtag)
    }

    private fun isStrongEtag(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length >= 2 &&
            !trimmed.startsWith("W/", ignoreCase = true) &&
            trimmed.startsWith('"') &&
            trimmed.endsWith('"')
    }

    internal fun shouldDiscardWorkingFileForResume(
        requestUrl: String,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Boolean {
        val recordedUrl = fingerprint?.sourceUrl
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        val currentUrl = requestUrl.trim()
        if (recordedUrl == currentUrl) {
            return false
        }
        if (!resolveResumeValidatorHeader(fingerprint).isNullOrBlank()) {
            return false
        }
        val recordedKey = resumeResourceKey(recordedUrl) ?: return false
        val currentKey = resumeResourceKey(currentUrl) ?: return false
        return recordedKey != currentKey
    }

    private fun resumeResourceKey(url: String): String? {
        val volatileQueryKeys = setOf(
            "alr",
            "expire",
            "expires",
            "lsig",
            "n",
            "sig",
            "signature",
            "sp",
            "st",
            "token"
        )
        return runCatching {
            val uri = java.net.URI(url)
            val query = uri.rawQuery
                ?.split('&')
                ?.mapNotNull { part ->
                    val key = part.substringBefore('=').lowercase()
                    part.takeIf { key.isNotBlank() && key !in volatileQueryKeys }
                }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf(String::isNotBlank)
            buildString {
                append(uri.scheme.orEmpty().lowercase())
                append("://")
                append(uri.rawAuthority.orEmpty().lowercase())
                append(uri.rawPath.orEmpty())
                if (query != null) {
                    append('?')
                    append(query)
                }
            }
        }.getOrNull()
    }

    internal fun buildResumeRequest(
        request: Request,
        completedBytes: Long,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Request {
        val resumeRangeHeader = buildResumeRangeHeader(completedBytes) ?: return request
        val validator = resolveResumeValidatorHeader(fingerprint)
        return request.newBuilder()
            .header("Range", resumeRangeHeader)
            .header("Accept-Encoding", "identity")
            .removeHeader("If-Range")
            .apply {
                if (!validator.isNullOrBlank()) {
                    header("If-Range", validator)
                }
            }
            .build()
    }

    internal fun buildChunkResumeRequest(
        request: Request,
        start: Long,
        length: Long,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Request {
        val validator = resolveResumeValidatorHeader(fingerprint)
        return YouTubeGoogleVideoRangeSupport.buildChunkedRequest(
            request = request,
            start = start,
            length = length
        ).newBuilder()
            .header("Accept-Encoding", "identity")
            .removeHeader("If-Range")
            .apply {
                if (start > 0L && !validator.isNullOrBlank()) {
                    header("If-Range", validator)
                }
            }
            .build()
    }

    internal fun resolveLatestResumeFingerprint(
        fallback: ManagedDownloadStorage.WorkingResumeFingerprint?,
        latest: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): ManagedDownloadStorage.WorkingResumeFingerprint? {
        return latest ?: fallback
    }

    internal fun parseContentRange(headers: Map<String, List<String>>): ParsedContentRange? {
        val value = responseHeaderValue(headers, "Content-Range") ?: return null
        val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) {
            return null
        }
        return ParsedContentRange(start = start, end = end, total = total)
    }

    internal fun parseUnsatisfiedContentRangeTotal(headers: Map<String, List<String>>): Long? {
        val value = responseHeaderValue(headers, "Content-Range") ?: return null
        val match = Regex("""^bytes\s+\*/(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0L }
    }

    internal fun isExactRangeEnd(
        headers: Map<String, List<String>>,
        resumedBytes: Long
    ): Boolean {
        return resumedBytes >= 0L && parseUnsatisfiedContentRangeTotal(headers) == resumedBytes
    }

    internal fun validatePartialContentRange(
        headers: Map<String, List<String>>,
        expectedStart: Long,
        bodyLength: Long? = null
    ): ParsedContentRange {
        val range = parseContentRange(headers)
            ?: throw IOException("缺少或非法 Content-Range")
        if (range.start != expectedStart) {
            throw IOException(
                "续传偏移不匹配: expected=$expectedStart, actual=${range.start}"
            )
        }
        if (bodyLength != null && bodyLength >= 0L && bodyLength != range.length) {
            throw IOException(
                "Content-Range 长度不匹配: expected=${range.length}, actual=$bodyLength"
            )
        }
        return range
    }

    internal fun isResumeResponseCompatible(
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?,
        headers: Map<String, List<String>>,
        totalBytes: Long
    ): Boolean {
        val expectedEtag = fingerprint?.etag?.trim()?.takeIf(::isStrongEtag) ?: return false
        val actualEtag = responseHeaderValue(headers, "ETag")
            ?.trim()
            ?.takeIf(::isStrongEtag)
            ?: return false
        if (expectedEtag != actualEtag) {
            return false
        }
        val expectedTotal = fingerprint.expectedContentLength?.takeIf { it > 0L }
        return expectedTotal == null || expectedTotal == totalBytes
    }

    private fun responseHeaderValue(
        headers: Map<String, List<String>>,
        name: String
    ): String? {
        return headers.entries.firstOrNull { (key, _) ->
            key.equals(name, ignoreCase = true)
        }?.value?.firstOrNull()?.takeIf(String::isNotBlank)
    }

    private fun updateWorkingResumeFingerprint(
        destFile: File,
        requestUrl: String,
        headers: Map<String, List<String>>,
        expectedContentLength: Long?
    ): Boolean {
        return runCatching {
            ManagedDownloadStorage.updateWorkingResumeFingerprint(
                workingFile = destFile,
                fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
                    sourceUrl = requestUrl,
                    etag = responseHeaderValue(headers, "ETag"),
                    lastModified = responseHeaderValue(headers, "Last-Modified"),
                    expectedContentLength = expectedContentLength?.takeIf { it > 0L }
                )
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "写入续传指纹失败，后续续传将退化为整文件重下: ${destFile.name}", error)
        }.getOrDefault(false)
    }

    internal fun resolveResponseExpectedBytes(
        requestUrl: String,
        headers: Map<String, List<String>>,
        bodyLength: Long,
        resumedBytes: Long,
        isPartialResponse: Boolean
    ): Long? {
        val contentRangeValue = responseHeaderValue(headers, "Content-Range")
        if (contentRangeValue != null) {
            return parseContentRange(headers)?.total
                ?: parseUnsatisfiedContentRangeTotal(headers)
        }
        if (bodyLength > 0L) {
            return if (isPartialResponse) {
                bodyLength + resumedBytes.coerceAtLeast(0L)
            } else {
                bodyLength
            }
        }

        if (YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(requestUrl)) {
            return YouTubeGoogleVideoRangeSupport.resolveTotalContentLength(
                requestUrl,
                headers
            )?.takeIf { it > 0L }
        }
        return null
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
        // Connectivity 回调线程不能同步查询 Room/SAF；恢复入口本身会在 IO
        // 协程中做候选检查，没有候选时立即返回
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

    internal fun buildHlsPlaylistFingerprint(
        segmentUrls: List<String>,
        playlistText: String? = null
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("neriplayer-hls-playlist-v2".toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        updateHlsFingerprintField(digest, segmentUrls.size.toString())
        segmentUrls.forEachIndexed { index, segmentUrl ->
            updateHlsFingerprintField(digest, index.toString())
            updateHlsFingerprintField(digest, canonicalHlsSegmentUri(segmentUrl))
        }
        playlistText
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter { line ->
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) ||
                    line.startsWith("#EXTINF:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true)
            }
            ?.forEachIndexed { index, line ->
                updateHlsFingerprintField(digest, "metadata:$index")
                updateHlsFingerprintField(digest, line)
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun canonicalHlsSegmentUri(url: String): String {
        val volatileQueryKeys = setOf(
            "alr",
            "expire",
            "expires",
            "lsig",
            "n",
            "sig",
            "signature",
            "sp",
            "st",
            "token"
        )
        return runCatching {
            val uri = java.net.URI(url.trim())
            val query = uri.rawQuery
                ?.split('&')
                ?.mapNotNull { part ->
                    val key = part.substringBefore('=').lowercase()
                    part.takeIf { key.isNotBlank() && key !in volatileQueryKeys }
                }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf(String::isNotBlank)
            buildString {
                if (!uri.scheme.isNullOrBlank()) {
                    append(uri.scheme.orEmpty().lowercase())
                    append("://")
                    append(uri.rawAuthority.orEmpty().lowercase())
                }
                append(uri.rawPath.orEmpty())
                if (query != null) {
                    append('?')
                    append(query)
                }
            }
        }.getOrElse {
            url.substringBefore('#').substringBefore('?')
        }
    }

    private fun updateHlsFingerprintField(
        digest: MessageDigest,
        value: String
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte()
            )
        )
        digest.update(bytes)
    }

    internal fun serializeHlsResumeState(state: HlsResumeState): String {
        return JSONObject().apply {
            put("format", "hls-resume-v2")
            put("playlistDigestSha256", state.playlistFingerprint)
            put("nextSegmentIndex", state.nextSegmentIndex)
            put("durableBytes", state.downloadedBytes.coerceAtLeast(0L))
            put("durablePrefixSha256", state.durablePrefixSha256)
            state.operationId.takeIf(String::isNotBlank)?.let { put("operationId", it) }
            state.mediaSequence?.let { put("mediaSequence", it) }
        }.toString()
    }

    internal fun deserializeHlsResumeState(raw: String?): HlsResumeState? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val json = JSONObject(raw)
            val playlistFingerprint = json.optString("playlistDigestSha256")
                .takeIf(String::isNotBlank)
                ?: json.optString("playlistFingerprint")
                    .takeIf(String::isNotBlank)
                ?: return@runCatching null
            if (!SHA256_HEX_REGEX.matches(playlistFingerprint)) {
                return@runCatching null
            }
            val nextSegmentIndex = json.getInt("nextSegmentIndex")
            val durableBytes = json.optLong(
                "durableBytes",
                json.optLong("downloadedBytes", -1L)
            )
            val durablePrefixSha256 = json.optString("durablePrefixSha256")
                .takeIf(String::isNotBlank)
            val operationId = json.optString("operationId")
                .takeIf(String::isNotBlank)
                .orEmpty()
            val mediaSequence = if (json.has("mediaSequence")) {
                json.optLong("mediaSequence").takeIf { it >= 0L }
            } else {
                null
            }
            if (
                nextSegmentIndex < 0 ||
                durableBytes < 0L ||
                durablePrefixSha256 == null ||
                !SHA256_HEX_REGEX.matches(durablePrefixSha256)
            ) {
                return@runCatching null
            }
            HlsResumeState(
                playlistFingerprint = playlistFingerprint,
                nextSegmentIndex = nextSegmentIndex,
                downloadedBytes = durableBytes,
                durablePrefixSha256 = durablePrefixSha256,
                operationId = operationId,
                mediaSequence = mediaSequence
            )
        }.getOrNull()
    }

    internal fun isHlsResumeStateCompatible(
        state: HlsResumeState,
        actualFileLength: Long,
        actualPrefixSha256: String,
        segmentCount: Int
    ): Boolean {
        return SHA256_HEX_REGEX.matches(state.playlistFingerprint) &&
            state.nextSegmentIndex in 0..segmentCount &&
            (state.nextSegmentIndex > 0 || state.durableBytes == 0L) &&
            actualFileLength >= state.durableBytes &&
            state.durableBytes >= 0L &&
            SHA256_HEX_REGEX.matches(state.durablePrefixSha256) &&
            state.durablePrefixSha256.equals(actualPrefixSha256, ignoreCase = true)
    }

    internal fun isHlsResumeStateOwnedByOperation(
        state: HlsResumeState,
        operationId: String
    ): Boolean {
        val normalizedOperationId = operationId.trim()
        return normalizedOperationId.isNotBlank() &&
            state.operationId.trim() == normalizedOperationId
    }

    private fun sha256FilePrefix(
        file: File,
        byteCount: Long
    ): String {
        return digestHex(sha256FilePrefixDigest(file, byteCount))
    }

    private fun sha256FilePrefixDigest(
        file: File,
        byteCount: Long
    ): MessageDigest {
        require(byteCount >= 0L) { "HLS durable byte count must not be negative" }
        val digest = MessageDigest.getInstance("SHA-256")
        if (byteCount == 0L) {
            return digest
        }
        var remaining = byteCount
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_READ_BUFFER_BYTES.toInt())
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) {
                    throw EOFException(
                        "HLS durable prefix is shorter than checkpoint: " +
                            "expected=$byteCount"
                    )
                }
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest
    }

    private fun digestHex(digest: MessageDigest): String {
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun digestHexSnapshot(
        digest: MessageDigest,
        file: File,
        byteCount: Long
    ): String {
        val snapshot = runCatching {
            digest.clone() as? MessageDigest
        }.getOrNull()
        return snapshot?.let(::digestHex) ?: sha256FilePrefix(file, byteCount)
    }

    private fun truncateHlsWorkingFile(
        file: File,
        byteCount: Long
    ) {
        java.io.RandomAccessFile(file, "rw").use { randomAccessFile ->
            randomAccessFile.setLength(byteCount)
            randomAccessFile.fd.sync()
        }
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
            ManagedDownloadAtomicFile.writeTextAtomically(
                target = checkpointFile,
                content = serializeHlsResumeState(state)
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "写入 HLS 恢复点失败: ${checkpointFile.name}", error)
            throw error
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
        playlistFingerprint: String,
        nextSegmentIndex: Int,
        durableBytes: Long,
        durablePrefixSha256: String,
        operationId: String,
        mediaSequence: Long?
    ) {
        val state = HlsResumeState(
            playlistFingerprint = playlistFingerprint,
            nextSegmentIndex = nextSegmentIndex,
            downloadedBytes = durableBytes.coerceAtLeast(0L),
            durablePrefixSha256 = durablePrefixSha256,
            operationId = operationId,
            mediaSequence = mediaSequence
        )
        val path = destFile.absolutePath
        val previousState = hlsResumeStatesByWorkingPath[path]
        try {
            // 先把 checkpoint 原子落盘，再发布内存状态，避免只在当前进程中
            // 看起来可恢复而重启后找不到对应证据
            persistHlsResumeState(destFile, state)
            hlsResumeStatesByWorkingPath[path] = state
        } catch (error: Throwable) {
            if (previousState == null) {
                hlsResumeStatesByWorkingPath.remove(path)
                deletePersistedHlsResumeState(destFile)
            } else {
                hlsResumeStatesByWorkingPath[path] = previousState
            }
            throw error
        }
    }

    private fun resolveHlsResumeState(
        destFile: File,
        playlistFingerprint: String,
        operationId: String = ""
    ): HlsResumeState? {
        val state = hlsResumeStatesByWorkingPath[destFile.absolutePath]
            ?: readPersistedHlsResumeState(destFile)?.also { persisted ->
                hlsResumeStatesByWorkingPath[destFile.absolutePath] = persisted
            }
            ?: return null
        return state.takeIf {
            it.playlistFingerprint == playlistFingerprint &&
                isHlsResumeStateOwnedByOperation(it, operationId)
        }
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

    private inline fun <T> withNetworkPolicyMutationPermit(
        songKey: String,
        stage: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        block: () -> T
    ): T {
        return synchronized(networkPolicyMutationLock) {
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = stage,
                batchSessionId = batchSessionId,
                attemptId = attemptId
            )
            block()
        }
    }

    private fun deleteWorkingFileUnlessNetworkPolicyPaused(
        songKey: String,
        tempFile: File?
    ): Boolean {
        return synchronized(networkPolicyMutationLock) {
            if (shouldPreserveArtifactsForNetworkPolicy(songKey)) {
                false
            } else {
                deleteWorkingFile(tempFile)
                true
            }
        }
    }

    private fun publishProgress(
        progress: DownloadProgress,
        force: Boolean = false
    ) {
        val nowNs = System.nanoTime()
        val shouldEmit = synchronized(progressPublishLock) {
            val previous = lastPublishedProgressBySongKey[progress.songKey]
            val shouldPublishNow = shouldPublishAudioDownloadProgress(
                previous = previous,
                progress = progress,
                nowNs = nowNs,
                force = force
            )

            if (shouldPublishNow) {
                lastPublishedProgressBySongKey[progress.songKey] = PublishedProgressState(
                    attemptId = progress.attemptId,
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
            progressEventStream.publish(progress)
        }
    }

    private fun clearPublishedProgress(songKey: String) {
        synchronized(progressPublishLock) {
            lastPublishedProgressBySongKey.remove(songKey)
        }
    }

    private fun clearVisibleProgressForSong(songKey: String) {
        if (_progressFlow.value?.songKey == songKey) {
            _progressFlow.value = null
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

    internal fun cancelYouTubeCalls(calls: Iterable<okhttp3.Call>): Int {
        val youtubeCalls = calls.filter { call ->
            isTrustedYouTubeHost(call.request().url.host)
        }
        youtubeCalls.forEach(okhttp3.Call::cancel)
        return youtubeCalls.size
    }

    fun cancelActiveYouTubeDownloads() {
        cancelYouTubeCalls(snapshotActiveCalls())
    }

    private inline fun <T> executeTrackedCall(
        client: okhttp3.OkHttpClient,
        request: Request,
        songKey: String,
        block: (okhttp3.Response) -> T
    ): T {
        val call = client.newCall(request)
        val pausedBeforeExecution = synchronized(networkPolicyMutationLock) {
            registerActiveCall(songKey, call)
            shouldPreserveArtifactsForNetworkPolicy(songKey)
        }
        try {
            if (pausedBeforeExecution) {
                call.cancel()
                throw java.util.concurrent.CancellationException(
                    "Download paused for network policy"
                )
            }
            return call.execute().use(block)
        } catch (error: IOException) {
            if (
                call.isCanceled() ||
                _isCancelled.value ||
                shouldPreserveArtifactsForNetworkPolicy(songKey) ||
                GlobalDownloadManager.isSongCancelled(songKey)
            ) {
                clearVisibleProgressForSong(songKey)
                throw java.util.concurrent.CancellationException("Download cancelled").apply {
                    initCause(error)
                }
            }
            throw error
        } finally {
            synchronized(networkPolicyMutationLock) {
                unregisterActiveCall(songKey, call)
            }
        }
    }

    internal fun consumeCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? {
        synchronized(completedAudioReferenceMutationLock) {
            val current = completedAudioReferencesBySongKey[songKey] ?: return null
            if (isCompletedAudioReferenceExpired(songKey, current)) {
                return null
            }
            removeCompletedAudioReferenceAliases(current)
            return current.audio
        }
    }

    internal fun releaseCompletedAudioReference(
        songKey: String,
        expectedAudio: ManagedDownloadStorage.StoredEntry? = null,
        retainForPlayback: Boolean = false
    ) {
        synchronized(completedAudioReferenceMutationLock) {
            val current = completedAudioReferencesBySongKey[songKey] ?: return
            if (isCompletedAudioReferenceExpired(songKey, current)) {
                return
            }
            if (!retainForPlayback && (expectedAudio == null || current.audio == expectedAudio)) {
                removeCompletedAudioReferenceAliases(current)
            }
        }
    }

    /** 迁移或切换下载根后，主动丢弃仍指向旧目录的内存桥接引用 */
    internal fun invalidateCompletedAudioReference(song: SongItem) {
        synchronized(completedAudioReferenceMutationLock) {
            val matches = buildSet {
                completedAudioSongLookupKeys(song).forEach { key ->
                    completedAudioReferencesBySongKey[key]?.let(::add)
                }
                listOfNotNull(song.localFilePath, song.mediaUri).forEach { reference ->
                    completedAudioReferenceKeys(reference).forEach { key ->
                        completedAudioReferencesByReference[key]?.let(::add)
                    }
                }
            }
            matches.forEach(::removeCompletedAudioReferenceAliases)
        }
    }

    /**
     * core 音频已经完成校验但全局完成回调尚未消费引用时, 播放入口也要能立即读取
     */
    internal fun peekCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? {
        synchronized(completedAudioReferenceMutationLock) {
            return completedAudioReferencesBySongKey[songKey]
                ?.takeUnless { isCompletedAudioReferenceExpired(songKey, it) }
                ?.audio
        }
    }

    /** 允许刚提交音频按原始 URI 取回, 避免歌曲身份字段尚未同步时丢失桥接 */
    internal fun peekCompletedAudioReferenceByRawReference(
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val candidates = listOfNotNull(
            reference?.trim()?.takeIf(String::isNotBlank),
            safeToPlayableUri(reference)
        ).distinct()
        if (candidates.isEmpty()) {
            return null
        }
        synchronized(completedAudioReferenceMutationLock) {
            return candidates.firstNotNullOfOrNull { key ->
                val current = completedAudioReferencesByReference[key]
                    ?: return@firstNotNullOfOrNull null
                current.takeUnless {
                    isCompletedAudioReferenceExpired(key, it)
                }?.audio
            }
        }
    }

    /** 允许播放列表使用提交时刚写入的 URI, 即使稳定身份字段尚未同步 */
    internal fun peekCompletedAudioReference(song: SongItem): ManagedDownloadStorage.StoredEntry? {
        synchronized(completedAudioReferenceMutationLock) {
            completedAudioSongLookupKeys(song).forEach { key ->
                completedAudioReferencesBySongKey[key]?.let { current ->
                    if (!isCompletedAudioReferenceExpired(key, current)) {
                        return current.audio
                    }
                }
            }
            val references = listOfNotNull(song.localFilePath, song.mediaUri)
                .flatMap { reference ->
                    listOfNotNull(
                        reference.trim().takeIf(String::isNotBlank),
                        safeToPlayableUri(reference)
                    )
                }
                .distinct()
            return references.firstNotNullOfOrNull { reference ->
                val current = completedAudioReferencesByReference[reference]
                    ?: return@firstNotNullOfOrNull null
                current.takeUnless {
                    isCompletedAudioReferenceExpired(reference, it)
                }?.audio
            }
        }
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
        rememberCompletedAudioReference(
            songLookupKeys = setOf(songKey),
            storedAudio = storedAudio
        )
    }

    /** 下载回调与播放队列可能使用不同版本的歌曲身份, 同时保存兼容别名 */
    internal fun rememberCompletedAudioReference(
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) {
        rememberCompletedAudioReference(
            songLookupKeys = completedAudioSongLookupKeys(song),
            storedAudio = storedAudio
        )
    }

    private fun rememberCompletedAudioReference(
        songLookupKeys: Set<String>,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) {
        val normalizedSongLookupKeys = songLookupKeys
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedSongLookupKeys.isEmpty()) {
            return
        }
        synchronized(completedAudioReferenceMutationLock) {
            pruneCompletedAudioReferences(System.currentTimeMillis())
            val completed = CompletedAudioReference(
                audio = storedAudio,
                committedAtMs = System.currentTimeMillis(),
                songLookupKeys = normalizedSongLookupKeys,
                rootGeneration = LocalStorageRootGeneration.current()
            )
            normalizedSongLookupKeys.forEach { key ->
                completedAudioReferencesBySongKey[key]
                    ?.let(::removeCompletedAudioReferenceAliases)
            }
            normalizedSongLookupKeys.forEach { key ->
                completedAudioReferencesBySongKey[key] = completed
            }
            completedAudioReferenceKeys(storedAudio).forEach { key ->
                completedAudioReferencesByReference[key] = completed
            }
        }
    }

    private fun pruneCompletedAudioReferences(nowMs: Long) {
        val uniqueReferences = completedAudioReferencesBySongKey.values.distinct()
        uniqueReferences
            .filter { nowMs - it.committedAtMs >= COMPLETED_AUDIO_REFERENCE_RETENTION_MS }
            .forEach(::removeCompletedAudioReferenceAliases)
        val remaining = completedAudioReferencesBySongKey.values.distinct()
        if (remaining.size <= COMPLETED_AUDIO_REFERENCE_MAX_ENTRIES) {
            return
        }
        remaining
            .sortedBy(CompletedAudioReference::committedAtMs)
            .take(remaining.size - COMPLETED_AUDIO_REFERENCE_MAX_ENTRIES)
            .forEach(::removeCompletedAudioReferenceAliases)
    }

    private fun completedAudioSongLookupKeys(song: SongItem): Set<String> {
        return buildSet {
            song.playbackVisualKey()
                .takeIf(String::isNotBlank)
                ?.let(::add)
            song.stableKey().takeIf(String::isNotBlank)?.let(::add)
            song.remoteDownloadIdentityOrNull()
                ?.stableKey()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            song.sourceStableKey
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            // 保留旧版本以原始字段生成的身份, 兼容升级后队列中的历史条目
            SongIdentity(song.id, song.album, song.mediaUri)
                .stableKey()
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    private fun completedAudioReferenceKeys(
        audio: ManagedDownloadStorage.StoredEntry
    ): Set<String> {
        return listOfNotNull(
            audio.reference,
            audio.mediaUri,
            audio.localFilePath,
            safeToPlayableUri(audio.reference),
            safeToPlayableUri(audio.mediaUri)
        ).map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun completedAudioReferenceKeys(reference: String?): Set<String> {
        return listOfNotNull(
            reference?.trim()?.takeIf(String::isNotBlank),
            safeToPlayableUri(reference)
        ).map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun safeToPlayableUri(reference: String?): String? {
        return runCatching {
            ManagedDownloadStorage.toPlayableUri(reference)
        }.getOrNull()
    }

    private fun isCompletedAudioReferenceExpired(
        lookupKey: String,
        current: CompletedAudioReference
    ): Boolean {
        val generationChanged = shouldInvalidateCompletedAudioReferenceForRoot(
            referenceRootGeneration = current.rootGeneration,
            currentRootGeneration = LocalStorageRootGeneration.current()
        )
        val expiredByAge = System.currentTimeMillis() - current.committedAtMs >=
            COMPLETED_AUDIO_REFERENCE_RETENTION_MS
        if (!generationChanged && !expiredByAge) {
            return false
        }
        if (completedAudioReferencesBySongKey.remove(lookupKey, current)) {
            removeCompletedAudioReferenceAliases(current)
        } else {
            completedAudioReferencesByReference.remove(lookupKey, current)
        }
        return true
    }

    private fun removeCompletedAudioReferenceAliases(
        current: CompletedAudioReference
    ) {
        current.songLookupKeys.forEach { key ->
            completedAudioReferencesBySongKey.remove(key, current)
        }
        completedAudioReferenceKeys(current.audio).forEach { key ->
            completedAudioReferencesByReference.remove(key, current)
        }
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
        synchronized(completedAudioReferenceMutationLock) {
            completedAudioReferencesBySongKey.remove(songKey)
                ?.let(::removeCompletedAudioReferenceAliases)
        }
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
            lyricContent = mergeSidecarContent(
                existingReference = existing?.lyricReference,
                existingContent = existing?.lyricContent,
                incomingReference = incoming?.lyricReference,
                incomingContent = incoming?.lyricContent
            ),
            translatedLyricContent = mergeSidecarContent(
                existingReference = existing?.translatedLyricReference,
                existingContent = existing?.translatedLyricContent,
                incomingReference = incoming?.translatedLyricReference,
                incomingContent = incoming?.translatedLyricContent
            ),
            romanizedLyricContent = mergeSidecarContent(
                existingReference = existing?.romanizedLyricReference,
                existingContent = existing?.romanizedLyricContent,
                incomingReference = incoming?.romanizedLyricReference,
                incomingContent = incoming?.romanizedLyricContent
            ),
            expectedCover = (existing?.expectedCover == true) ||
                (incoming?.expectedCover == true),
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
            expectedTranslatedLyric = (existing?.expectedTranslatedLyric == true) ||
                (incoming?.expectedTranslatedLyric == true),
            createdTranslatedLyric = mergeSidecarCreatedFlag(
                existingReference = existing?.translatedLyricReference,
                existingCreated = existing?.createdTranslatedLyric ?: false,
                incomingReference = incoming?.translatedLyricReference,
                incomingCreated = incoming?.createdTranslatedLyric ?: false
            ),
            romanizedLyricReference = incoming?.romanizedLyricReference
                ?: existing?.romanizedLyricReference,
            expectedLyric = (existing?.expectedLyric == true) ||
                (incoming?.expectedLyric == true),
            expectedRomanizedLyric = (existing?.expectedRomanizedLyric == true) ||
                (incoming?.expectedRomanizedLyric == true),
            createdRomanizedLyric = mergeSidecarCreatedFlag(
                existingReference = existing?.romanizedLyricReference,
                existingCreated = existing?.createdRomanizedLyric ?: false,
                incomingReference = incoming?.romanizedLyricReference,
                incomingCreated = incoming?.createdRomanizedLyric ?: false
            )
        )
    }

    private fun mergeSidecarContent(
        existingReference: String?,
        existingContent: String?,
        incomingReference: String?,
        incomingContent: String?
    ): String? {
        val normalizedIncomingReference = incomingReference
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (normalizedIncomingReference == null) {
            return existingContent
        }
        val normalizedExistingReference = existingReference
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return when {
            !incomingContent.isNullOrBlank() -> incomingContent
            normalizedIncomingReference == normalizedExistingReference -> existingContent
            else -> null
        }
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
        if (!shouldAbortDownloadWork(
                allDownloadsCancelled = _isCancelled.value,
                batchSessionCurrent = isBatchSessionCurrent(batchSessionId),
                songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey),
                attemptAllowsWork = attemptAllowsWork
            )
        ) {
            return
        }
        NPLogger.d(TAG, "检测到下载取消: songKey=$songKey, stage=$stage")
        clearVisibleProgressForSong(songKey)
        throw java.util.concurrent.CancellationException("Download cancelled during $stage")
    }

    private suspend fun buildCorePendingMetadata(
        context: Context,
        song: SongItem,
        audioTargetName: String,
        operationId: String
    ): String {
        val libraryId = ManagedDownloadStorage.ensureManagedLibraryManifest(context)
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
        val nowMs = System.currentTimeMillis()
        val identity = song.identity()
        val stableKey = song.stableKey()
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = stableKey,
            songId = song.id,
            identityAlbum = identity.album,
            album = song.album,
            name = song.name,
            artist = song.artist,
            coverUrl = song.coverUrl,
            matchedLyric = song.matchedLyric,
            matchedTranslatedLyric = song.matchedTranslatedLyric,
            matchedRomanizedLyric = song.matchedRomanizedLyric,
            matchedLyricSource = song.matchedLyricSource?.name,
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
            originalRomanizedLyric = song.originalRomanizedLyric,
            mediaUri = identity.mediaUri ?: song.mediaUri,
            channelId = song.channelId,
            audioId = song.audioId,
            subAudioId = song.subAudioId,
            playlistContextId = song.playlistContextId,
            durationMs = song.durationMs,
            downloadTimeMs = nowMs,
            downloadFinalized = false,
            createdAtMs = nowMs,
            createdAtSource = "CORE_COMMIT",
            artifactId = "managed:$libraryId:$stableKey",
            operationId = operationId,
            artifactState = "COMMITTING",
            audioFileName = audioTargetName,
            libraryId = libraryId,
            libraryAddedAtMs = nowMs
        )
        return ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata).apply {
            put("rootKey", rootKey)
        }.toString()
    }

    suspend fun downloadSong(
        context: Context,
        song: SongItem,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        operationId: String? = null,
        downloadAudioQuality: DownloadAudioQualitySelection? = null
    ) {
        withConfiguredDownloadPermit(context) {
            downloadSongOnIo(
                context = context,
                song = song,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId,
                downloadAudioQuality = downloadAudioQuality
            )
        }
    }

    private suspend fun downloadSongOnIo(
        context: Context,
        song: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?,
        downloadAudioQuality: DownloadAudioQualitySelection?
    ) {
        withContext(Dispatchers.IO) {
            executeDownloadSong(
                context = context,
                song = song,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId,
                downloadAudioQuality = downloadAudioQuality
            )
        }
    }

    private suspend fun executeDownloadSong(
        context: Context,
        song: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?,
        downloadAudioQuality: DownloadAudioQualitySelection?
    ) {
                val songKey = song.stableKey()
                val effectiveOperationId = operationId?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: UUID.randomUUID().toString()
                var storedAudio: ManagedDownloadStorage.StoredEntry? = null
                var tempFile: File? = null
                val coreCommitTracker = DownloadCoreCommitTracker()
                var cancellationCleanupAttempted = false
                beginSongDownloadOperation(songKey)
                clearPartialSidecarReferences(songKey)
                try {
                    ensureSongDownloadNotCancelled(songKey, "prepare", batchSessionId, attemptId)
                    if (LocalSongSupport.isLocalSong(song, context)) {
                        NPLogger.d(TAG, "Skip local song download: ${song.name}")
                        clearVisibleProgressForSong(songKey)
                        return
                    }

                    if (hasFastCachedManagedDownloadForStart(context, song)) {
                        NPLogger.d(
                            TAG,
                            "${context.getString(R.string.download_file_exists, song.name)}, songKey=$songKey"
                        )
                        clearVisibleProgressForSong(songKey)
                        return
                    }

                    val resolvedDownloadAudioQuality = downloadAudioQuality
                        ?.let { quality ->
                            DownloadAudioQualitySelection.normalized(
                                neteaseQuality = quality.neteaseQuality,
                                youtubeQuality = quality.youtubeQuality,
                                biliQuality = quality.biliQuality
                            )
                        }
                        ?: resolveDownloadAudioQualitySelection(context)
                    val isYouTubeMusic = isYouTubeMusicSong(song)
                    val isBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
                    var attemptNumber = 1
                    var activeTransportKind: DownloadTransportKind? = null
                    var activeWorkingFileName: String? = null
                    var resumeMetadataAvailable = true
                    var forceRefreshYouTubeSource = false
                    // 直链下载被 403 后置位: 后续重试改走不需 pot 的 HLS, 避免在脏 IP 上死磕必 403 的直链
                    var avoidYouTubeDirectSource = false
                    while (true) {
                        ensureSongDownloadNotCancelled(songKey, "prepare", batchSessionId, attemptId)
                        try {
                            val resolved = when {
                                isYouTubeMusic -> resolveYouTubeMusic(
                                    song = song,
                                    preferredQuality = resolvedDownloadAudioQuality.youtubeQuality,
                                    forceRefresh = forceRefreshYouTubeSource,
                                    avoidDirect = avoidYouTubeDirectSource
                                )
                                isBili -> resolveBili(
                                    song = song,
                                    preferredQuality = resolvedDownloadAudioQuality.biliQuality
                                )
                                else -> resolveNetease(
                                    songId = song.id,
                                    preferredQuality = resolvedDownloadAudioQuality.neteaseQuality
                                )
                            }
                            ensureSongDownloadNotCancelled(
                                songKey = songKey,
                                stage = "source_resolved",
                                batchSessionId = batchSessionId,
                                attemptId = attemptId
                            )
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

                            // song duration 已经从 resolved 获取, 不再写入数据库, 只保持在当前上下文中
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
                            val fileName = boundManagedDownloadFileName(
                                if (ext.isNullOrBlank()) baseName else "$baseName.$ext"
                            )
                            resolved.contentLength?.let { sourceExpectedBytes ->
                                NPLogger.d(
                                    TAG,
                                    "下载来源长度提示: file=$fileName, " +
                                        "sourceExpected=$sourceExpectedBytes"
                                )
                            }

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
                                // 下载不再对 googlevideo 直链注入整档 Range(bytes=0-<clen-1>):
                                // 整档单请求会被 googlevideo 全量下载风控 403(同一直链能 range 播放却下不了)
                                // 直链下载改由 resolveDownloadTransportKind/singleThreadDownload 统一走分块 range
                                // 显式整档头会命中 hasExplicitRangeHeader 反而退回 DIRECT, 故此处不再设置
                            }

                            val request = reqBuilder.build()
                            val transportKind = resolveDownloadTransportKind(
                                streamType = resolved.streamType,
                                request = request
                            )
                            if (tempFile == null) {
                                tempFile = ManagedDownloadStorage.findWorkingFileForResume(
                                    context = context,
                                    songKey = songKey
                                )
                                if (tempFile != null) {
                                    NPLogger.d(
                                        TAG,
                                        "复用 operation staging 断点: song=${workingSong.name}, " +
                                            "file=${tempFile.name}"
                                    )
                                }
                            }
                            val workingFile = withNetworkPolicyMutationPermit(
                                songKey = songKey,
                                stage = "prepare_working_file",
                                batchSessionId = batchSessionId,
                                attemptId = attemptId
                            ) {
                                if (
                                    tempFile == null ||
                                    (activeWorkingFileName != null &&
                                        activeWorkingFileName != fileName) ||
                                    (activeTransportKind != null &&
                                        activeTransportKind != transportKind)
                                ) {
                                    deleteWorkingFile(tempFile)
                                    tempFile = ManagedDownloadStorage.createWorkingFile(
                                        context = context,
                                        songKey = songKey,
                                        fileName = fileName,
                                        operationId = effectiveOperationId
                                    )
                                }
                                activeWorkingFileName = fileName
                                activeTransportKind = transportKind
                                val currentWorkingFile = requireNotNull(tempFile)
                                resumeMetadataAvailable = ManagedDownloadStorage.saveWorkingResumeMetadata(
                                    workingFile = currentWorkingFile,
                                    song = workingSong,
                                    operationId = effectiveOperationId
                                )
                                if (!resumeMetadataAvailable) {
                                    NPLogger.w(
                                        TAG,
                                        "续传元数据不可用，当前下载继续但不宣称可无损恢复: " +
                                            "file=${currentWorkingFile.name}, " +
                                            "operationId=$effectiveOperationId"
                                    )
                                }
                                currentWorkingFile
                            }
                            // 只有确认即将开始新的网络传输后才清理旧桥接, 避免重复请求
                            // 在命中已有文件并提前返回时让刚提交音频失去播放兜底
                            clearCompletedAudioReference(songKey)
                            val downloadedPayload = downloadPayloadForTransport(
                                transportKind = transportKind,
                                resolved = resolved,
                                request = request,
                                workingFile = workingFile,
                                fileName = fileName,
                                workingSong = workingSong,
                                batchSessionId = batchSessionId,
                                attemptId = attemptId,
                                effectiveOperationId = effectiveOperationId
                            )
                            resumeMetadataAvailable = resumeMetadataAvailable &&
                                downloadedPayload.resumeMetadataAvailable
                            val committedAudio = finalizeDownloadedAudio(
                                context = context,
                                songKey = songKey,
                                workingSong = workingSong,
                                fileName = fileName,
                                mimeType = mime,
                                workingFile = workingFile,
                                payloadSummary = downloadedPayload,
                                effectiveOperationId = effectiveOperationId,
                                batchSessionId = batchSessionId,
                                attemptId = attemptId,
                                coreCommitTracker = coreCommitTracker
                            )
                            storedAudio = committedAudio.audio
                            publishFinalizingProgress(
                                songId = workingSong.id,
                                songKey = workingSong.stableKey(),
                                fileName = committedAudio.audio.name,
                                bytesRead = committedAudio.transferredBytes,
                                totalBytes = committedAudio.transferredBytes,
                                attemptId = attemptId
                            )
                            NPLogger.d(
                                TAG,
                                "音频落盘完成，sidecar 转入后台整理: " +
                                    "song=${song.name}, audioFile=${committedAudio.audio.name}"
                            )
                            rememberCompletedAudioReference(workingSong, committedAudio.audio)

                            clearVisibleProgressForSong(songKey)
                            clearPartialSidecarReferences(songKey)
                            return
                        } catch (error: Exception) {
                            if (error is DownloadStorageMutationDeferredException) {
                                clearVisibleProgressForSong(songKey)
                                clearCompletedAudioReference(songKey)
                                clearPartialSidecarReferences(songKey)
                                throw error
                            }
                            val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
                            if (
                                error is java.util.concurrent.CancellationException ||
                                _isCancelled.value ||
                                    preserveArtifacts ||
                                GlobalDownloadManager.isSongCancelled(songKey)
                            ) {
                                val partialSidecarReferences = consumePartialSidecarReferences(songKey)
                                    ?.retainCreatedOnly()
                                NPLogger.d(TAG, "下载已取消: ${song.name}")
                                if (
                                    !preserveArtifacts &&
                                    shouldRollbackCancelledAudio(coreCommitTracker.phase)
                                ) {
                                    cancellationCleanupAttempted = true
                                    val cleanupResult =
                                        cleanupCancelledPendingArtifactsWithLease(
                                            context = context,
                                            songKey = songKey,
                                            operationId = effectiveOperationId
                                        )
                                    if (cleanupResult.failedCount > 0) {
                                        NPLogger.w(
                                            TAG,
                                            "取消下载 pending 半成品暂未完全清理，保留恢复凭据: " +
                                                "song=${song.name}, failed=${cleanupResult.failedCount}"
                                        )
                                    }
                                    if (storedAudio != null || partialSidecarReferences?.isEmpty == false) {
                                        runCatching {
                                            NPLogger.d(
                                                TAG,
                                                "下载取消后回滚半成品: song=${song.name}, audio=${storedAudio?.reference}, sidecars=$partialSidecarReferences"
                                            )
                                            GlobalDownloadManager.rollbackCancelledDownload(
                                                context = context,
                                                song = song,
                                                storedAudio = storedAudio,
                                                sidecarReferences = partialSidecarReferences,
                                                operationId = effectiveOperationId
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
                                }
                                if (
                                    !preserveArtifacts &&
                                        deleteWorkingFileUnlessNetworkPolicyPaused(songKey, tempFile)
                                ) {
                                    tempFile = null
                                }
                                clearVisibleProgressForSong(songKey)
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
                                ) && resumeMetadataAvailable
                                if (
                                    !preservePartial &&
                                        deleteWorkingFileUnlessNetworkPolicyPaused(songKey, tempFile)
                                ) {
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
                                    // 直链被 403: 脏 IP 下 WEB_REMIX web-GVS 直链带 pot 也挡不住
                                    // 后续重试改走不需 pot 的 HLS 兜底
                                    if (isForbiddenYouTubeDownloadFailure(error)) {
                                        avoidYouTubeDirectSource = true
                                    }
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
                            if (deleteWorkingFileUnlessNetworkPolicyPaused(songKey, tempFile)) {
                                tempFile = null
                            }
                            NPLogger.e(
                                TAG,
                                "下载失败: ${song.name}, 错误: ${error.javaClass.simpleName} - ${error.message}",
                                error
                            )
                            throw error
                        }
                    }
                } catch (e: Exception) {
                    if (e is DownloadStorageMutationDeferredException) {
                        clearVisibleProgressForSong(songKey)
                        clearCompletedAudioReference(songKey)
                        clearPartialSidecarReferences(songKey)
                        throw e
                    }
                    if (
                        e is java.util.concurrent.CancellationException ||
                            _isCancelled.value ||
                            shouldPreserveArtifactsForNetworkPolicy(songKey) ||
                            GlobalDownloadManager.isSongCancelled(songKey)
                    ) {
                        val partialSidecarReferences = consumePartialSidecarReferences(songKey)
                            ?.retainCreatedOnly()
                        NPLogger.d(TAG, "下载已取消: ${song.name}")
                        val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
                        if (
                            !preserveArtifacts &&
                            shouldRollbackCancelledAudio(coreCommitTracker.phase)
                        ) {
                            if (!cancellationCleanupAttempted) {
                                cancellationCleanupAttempted = true
                                val cleanupResult =
                                    cleanupCancelledPendingArtifactsWithLease(
                                        context = context,
                                        songKey = songKey,
                                        operationId = effectiveOperationId
                                    )
                                if (cleanupResult.failedCount > 0) {
                                    NPLogger.w(
                                        TAG,
                                        "取消下载 pending 半成品暂未完全清理，保留恢复凭据: " +
                                            "song=${song.name}, failed=${cleanupResult.failedCount}"
                                    )
                                }
                            }
                            if (storedAudio != null || partialSidecarReferences?.isEmpty == false) {
                                runCatching {
                                    NPLogger.d(
                                        TAG,
                                        "下载取消后回滚半成品: song=${song.name}, audio=${storedAudio?.reference}, sidecars=$partialSidecarReferences"
                                    )
                                    GlobalDownloadManager.rollbackCancelledDownload(
                                        context = context,
                                        song = song,
                                        storedAudio = storedAudio,
                                        sidecarReferences = partialSidecarReferences,
                                        operationId = effectiveOperationId
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
                        }
                        if (!preserveArtifacts) {
                            deleteWorkingFileUnlessNetworkPolicyPaused(songKey, tempFile)
                        }
                        clearVisibleProgressForSong(songKey)
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
                    deleteWorkingFileUnlessNetworkPolicyPaused(songKey, tempFile)
                    clearVisibleProgressForSong(songKey)
                    clearCompletedAudioReference(songKey)
                    clearPartialSidecarReferences(songKey)
                    throw e  // 重新抛出异常，让调用方知道下载失败
                } finally {
                    clearPublishedProgress(songKey)
                    endSongDownloadOperation(songKey)
                }
    }

    private suspend fun cleanupCancelledPendingArtifactsWithLease(
        context: Context,
        songKey: String,
        operationId: String
    ): ManagedDownloadStorage.StartupRecoveryResult = withContext(NonCancellable) {
        val appContext = context.applicationContext
        val deleteLease = try {
            ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(appContext)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "取消下载 pending 清理获取目录租约失败，保留恢复凭据: " +
                    "songKey=$songKey, operationId=$operationId, " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error
            )
            return@withContext ManagedDownloadStorage.StartupRecoveryResult(
                failedCount = 1
            )
        } ?: run {
            // 目录迁移期间拿不到删除租约是正常的并发结果，凭据会由恢复任务继续处理
            NPLogger.d(
                TAG,
                "目录迁移或其他目录变更进行中，延后取消下载 pending 清理: " +
                    "songKey=$songKey, operationId=$operationId"
            )
            return@withContext ManagedDownloadStorage.StartupRecoveryResult(
                failedCount = 1
            )
        }

        try {
            ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts(
                context = appContext,
                stableKey = songKey,
                operationId = operationId
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "取消下载 pending 半成品清理失败，保留恢复凭据: " +
                    "songKey=$songKey, operationId=$operationId, " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error
            )
            ManagedDownloadStorage.StartupRecoveryResult(failedCount = 1)
        } finally {
            deleteLease.close()
        }
    }

    private suspend fun downloadPayloadForTransport(
        transportKind: DownloadTransportKind,
        resolved: ResolvedDownloadSource,
        request: Request,
        workingFile: File,
        fileName: String,
        workingSong: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        effectiveOperationId: String
    ): DownloadedPayloadSummary {
        val client = backgroundDownloadClient
        return when (transportKind) {
            DownloadTransportKind.HLS -> singleThreadHlsDownload(
                client = client,
                playlistRequest = request,
                destFile = workingFile,
                displayFileName = fileName,
                songId = workingSong.id,
                songKey = workingSong.stableKey(),
                totalBytesHint = resolved.contentLength ?: 0L,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = effectiveOperationId
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
    }

    private suspend fun finalizeDownloadedAudio(
        context: Context,
        songKey: String,
        workingSong: SongItem,
        fileName: String,
        mimeType: String?,
        workingFile: File,
        payloadSummary: DownloadedPayloadSummary,
        effectiveOperationId: String,
        batchSessionId: Long?,
        attemptId: Long?,
        coreCommitTracker: DownloadCoreCommitTracker
    ): CoreCommittedAudio {
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "audio_finalize_prepare",
            batchSessionId = batchSessionId,
            attemptId = attemptId
        )
        verifyDownloadedAudioPayload(
            song = workingSong,
            tempFile = workingFile,
            displayFileName = fileName,
            payloadSummary = payloadSummary
        )
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "audio_verified",
            batchSessionId = batchSessionId,
            attemptId = attemptId
        )
        val directoryCommitLease =
            ManagedDownloadDirectoryMutationFence.acquireCommitLeaseOrNull(
                context = context,
                operationId = effectiveOperationId
            ) ?: throw DownloadStorageMutationDeferredException(effectiveOperationId)
        try {
        val bytesBeforeMetadata = workingFile.length().coerceAtLeast(0L)
        val pendingMetadata = buildCorePendingMetadata(
            context = context,
            song = workingSong,
            audioTargetName = fileName,
            operationId = effectiveOperationId
        )
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "audio_pending_metadata",
            batchSessionId = batchSessionId,
            attemptId = attemptId
        )
        if (!ManagedDownloadStorage.writePendingAudioMetadata(
                context = context,
                audioName = fileName,
                json = pendingMetadata,
                operationId = effectiveOperationId
            )
        ) {
            throw IOException("无法写入下载 pending metadata: $fileName")
        }
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "audio_pending_metadata_written",
            batchSessionId = batchSessionId,
            attemptId = attemptId
        )

        val bytesAtCommit = workingFile.length().coerceAtLeast(0L)
        val commitExpectedBytes = resolveAudioCommitExpectedSize(
            transferExpectedBytes = payloadSummary.expectedBytes,
            bytesBeforeMetadata = bytesBeforeMetadata,
            bytesAtCommit = bytesAtCommit
        )
        NPLogger.d(
            TAG,
            "音频提交长度诊断: file=$fileName, " +
                "transferReported=${payloadSummary.actualBytes}, " +
                "transferFile=$bytesBeforeMetadata, " +
                "transferExpected=${payloadSummary.expectedBytes}, " +
                "taggedFile=$bytesAtCommit, " +
                "commitExpected=$commitExpectedBytes"
        )

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
        coreCommitTracker.phase = DownloadCoreCommitPhase.COMMITTING
        val committingMarked = try {
            DownloadExecutionRoomStore.markCommitting(
                context = context,
                operationId = effectiveOperationId
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw IOException(
                "无法确认下载 operation 的提交所有权",
                error
            )
        }
        if (!committingMarked) {
            throw java.util.concurrent.CancellationException(
                "下载 operation 已失去提交所有权"
            )
        }
        // 待提交音频由可恢复写入器在完整校验后一次性显现。把核心状态
        // 作为种子元数据同步写入，进程在收尾回调前退出时仍能安全恢复首播
        val coreCommittedSeedMetadata = coreCommittedSeedMetadataJson(pendingMetadata)
        val committedAudio = withContext(NonCancellable) {
            ManagedDownloadStorage.saveAudioFromTemp(
                context = context,
                fileName = fileName,
                tempFile = workingFile,
                mimeType = mimeType,
                expectedSizeBytes = commitExpectedBytes,
                transferSizeVerified = true,
                seedMetadataJson = coreCommittedSeedMetadata,
                pendingMetadataJson = pendingMetadata
            )
        }
        coreCommitTracker.phase = DownloadCoreCommitPhase.CORE_COMMITTED
        val coreOperationMarked = try {
            DownloadExecutionRoomStore.markCoreCommitted(
                context = context,
                operationId = effectiveOperationId
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "写入下载 operation core commit 阶段失败: ${error.message}"
            )
            false
        }
        if (!coreOperationMarked) {
            NPLogger.w(
                TAG,
                "pending metadata 已提交但 operation journal 未确认，" +
                    "保留音频等待收尾恢复: operationId=$effectiveOperationId"
            )
        } else {
            // 只有目标写入成功且 durable operation 已确认后才删除 HLS 断点
            clearHlsResumeState(workingFile)
        }
        if (committedAudio.isPendingAudioWrite) {
            NPLogger.d(
                TAG,
                "音频 core 已提交并保持 pending，等待元信息收尾后提升: " +
                    "song=${workingSong.name}, file=${committedAudio.name}"
            )
        }
        if (coreOperationMarked) {
            ManagedDownloadStorage.deleteWorkingResumeMetadata(workingFile)
        }
        return CoreCommittedAudio(
            audio = committedAudio,
            transferredBytes = transferredBytes
        )
        } finally {
            directoryCommitLease.close()
        }
    }

    internal suspend fun downloadSidecarsForCompletedAudio(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ): DownloadedSidecarReferences = withContext(Dispatchers.IO) {
        val songKey = song.stableKey()
        val expectedCover = buildCoverDownloadCandidateUrls(song).isNotEmpty()
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
                throw error
            } else {
                NPLogger.w(
                    TAG,
                    "后台 sidecar 整理失败: ${song.name} - " +
                        "${error.javaClass.simpleName}: ${error.message}",
                    error
                )
            }
            DownloadedSidecarReferences(expectedCover = expectedCover)
        }
        val createdReferences = consumePartialSidecarReferences(songKey)
            ?.retainCreatedOnly()
        mergeDownloadedSidecarReferences(downloadedReferences, createdReferences)
    }

    internal suspend fun repairCoverForCompletedAudio(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ): DownloadedSidecarReferences = withContext(Dispatchers.IO) {
        val songKey = song.stableKey()
        clearPartialSidecarReferences(songKey)
        val cachedCover = runCatching {
            cacheCover(
                context = context,
                song = song,
                songKey = songKey,
                baseName = storedAudio.nameWithoutExtension,
                storedAudio = storedAudio,
                requireActiveAttempt = false,
                allowIndexedLookup = true
            )
        }.getOrElse { error ->
            if (error is java.util.concurrent.CancellationException) {
                throw error
            }
            NPLogger.w(TAG, "已下载封面修复请求失败: ${song.name} - ${error.message}")
            null
        }
        val createdReferences = consumePartialSidecarReferences(songKey)
            ?.retainCreatedOnly()
        return@withContext mergeDownloadedSidecarReferences(
            DownloadedSidecarReferences(
                coverReference = cachedCover?.reference,
                createdCover = cachedCover?.created == true
            ),
            createdReferences
        )
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
        val expectedCover = buildCoverDownloadCandidateUrls(song).isNotEmpty()
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
            val cachedCover = cacheCover(
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
                coverReference = cachedCover?.reference,
                createdCover = cachedCover?.created == true,
                expectedCover = expectedCover,
                lyricReference = lyricReferences.lyricReference,
                translatedLyricReference = lyricReferences.translatedLyricReference,
                romanizedLyricReference = lyricReferences.romanizedLyricReference,
                lyricContent = lyricReferences.lyricContent,
                translatedLyricContent = lyricReferences.translatedLyricContent,
                romanizedLyricContent = lyricReferences.romanizedLyricContent,
                expectedLyric = lyricReferences.expectedLyric,
                expectedTranslatedLyric = lyricReferences.expectedTranslatedLyric,
                expectedRomanizedLyric = lyricReferences.expectedRomanizedLyric
            )
        } else {
            coroutineScope {
                val lyricJob = async {
                    downloadLyrics(
                        context = context,
                        song = song,
                        songKey = songKey,
                        baseName = baseName,
                        serializeWrites = false,
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
                val cachedCover = coverJob.await()
                DownloadedSidecarReferences(
                    coverReference = cachedCover?.reference,
                    createdCover = cachedCover?.created == true,
                    expectedCover = expectedCover,
                    lyricReference = lyricReferences.lyricReference,
                    translatedLyricReference = lyricReferences.translatedLyricReference,
                    romanizedLyricReference = lyricReferences.romanizedLyricReference,
                    lyricContent = lyricReferences.lyricContent,
                    translatedLyricContent = lyricReferences.translatedLyricContent,
                    romanizedLyricContent = lyricReferences.romanizedLyricContent,
                    expectedLyric = lyricReferences.expectedLyric,
                    expectedTranslatedLyric = lyricReferences.expectedTranslatedLyric,
                    expectedRomanizedLyric = lyricReferences.expectedRomanizedLyric
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
    ): CachedCoverReference? {
        val coverFileName = buildCoverSidecarFileName(baseName, songKey)
        val cachedCover = coverDownloadSingleFlight.run(
            CoverDownloadFlightKey(
                songKey = songKey,
                fileName = coverFileName
            )
        ) {
            cacheCoverInFlight(
                context = context,
                song = song,
                songKey = songKey,
                storedAudio = storedAudio,
                coverFileName = coverFileName,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt,
                allowIndexedLookup = allowIndexedLookup
            )
        }
        if (cachedCover != null) {
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "cover_reused",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
            rememberPartialSidecarReferences(
                songKey,
                DownloadedSidecarReferences(
                    coverReference = cachedCover.reference,
                    createdCover = cachedCover.created
                )
            )
        }
        return cachedCover
    }

    private suspend fun cacheCoverInFlight(
        context: Context,
        song: SongItem,
        songKey: String,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        coverFileName: String,
        batchSessionId: Long?,
        attemptId: Long?,
        requireActiveAttempt: Boolean,
        allowIndexedLookup: Boolean
    ): CachedCoverReference? {
        val indexedCover = ManagedDownloadStorage.peekCoverReference(storedAudio)
            ?: if (allowIndexedLookup && ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                ManagedDownloadStorage.peekCoverReference(storedAudio)
            } else {
                null
            }
        val indexedCoverEvidence = indexedCover?.let { reference ->
            ManagedDownloadReferenceLookup.inspect(context, reference)
        }
        if (
            indexedCoverEvidence is ManagedDownloadReferenceLookup.Result.PermissionLost ||
            indexedCoverEvidence is ManagedDownloadReferenceLookup.Result.ProviderFailure ||
            indexedCoverEvidence == ManagedDownloadReferenceLookup.Result.OutOfScope
        ) {
            NPLogger.w(
                TAG,
                "封面索引引用暂不可确认，跳过本次补齐: " +
                    "song=${song.name}, reference=$indexedCover, evidence=$indexedCoverEvidence"
            )
            return null
        }
        val existingCover = indexedCover?.takeIf {
            indexedCoverEvidence is ManagedDownloadReferenceLookup.Result.Present
        }
        if (!existingCover.isNullOrBlank()) {
            return CachedCoverReference(
                reference = existingCover,
                created = false
            )
        }

        try {
            val coverCandidates = buildCoverDownloadCandidateUrls(song)
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
                            "封面下载重试失败: song=${song.name}, " +
                                "candidate=${candidateIndex + 1}/${coverCandidates.size}, " +
                                "attempt=${retryIndex + 1}/$COVER_DOWNLOAD_MAX_ATTEMPTS, " +
                                "${error.javaClass.simpleName}: ${error.message}",
                            error
                        )
                        null
                    }
                    if (!committedCoverReference.isNullOrBlank()) {
                        NPLogger.d(TAG, "封面写入完成: song=${song.name}, reference=$committedCoverReference")
                        return CachedCoverReference(
                            reference = committedCoverReference,
                            created = true
                        )
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
            NPLogger.w(
                TAG,
                "封面后台下载失败: ${song.name} - " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error
            )
        }
        return null
    }

    internal fun buildCoverSidecarFileName(baseName: String, songKey: String): String {
        val suffix = ManagedDownloadStorageNaming.coverStableKeySuffix(songKey)
        return "$baseName-$suffix.jpg"
    }

    private fun buildLyricSidecarFileName(
        baseName: String,
        songKey: String,
        translated: Boolean
    ): String {
        val suffix = ManagedDownloadStorageNaming.stableKeySuffix(songKey)
        val variant = if (translated) "_trans" else ""
        return "$baseName$variant-$suffix.lrc"
    }

    private fun buildRomanizedLyricSidecarFileName(
        baseName: String,
        songKey: String
    ): String {
        val suffix = ManagedDownloadStorageNaming.stableKeySuffix(songKey)
        return "$baseName-roma-$suffix.lrc"
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
            val body: ResponseBody = response.body
            val contentType: String = body.contentType()?.toString().orEmpty()
            if (contentType.isNotBlank() && !contentType.startsWith("image/", ignoreCase = true)) {
                throw IOException("封面响应不是图片: $contentType")
            }
            val declaredLength = body.contentLength()
            val expectedLength = declaredLength.takeIf { it > 0L }
            val bytes = body.byteStream().use { input ->
                readCoverResponseBytes(input, declaredLength)
            }
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

    internal fun readCoverResponseBytes(
        input: InputStream,
        declaredLength: Long
    ): ByteArray {
        if (declaredLength > MAX_COVER_RESPONSE_BYTES) {
            throw IOException(
                "封面响应过大: declared=$declaredLength, limit=$MAX_COVER_RESPONSE_BYTES"
            )
        }
        return try {
            input.readBytesLimited(MAX_COVER_RESPONSE_BYTES)
        } catch (error: IllegalArgumentException) {
            throw IOException("封面响应超过大小限制: $MAX_COVER_RESPONSE_BYTES", error)
        }
    }

    private fun verifyDownloadedAudioPayload(
        song: SongItem,
        tempFile: File,
        displayFileName: String,
        payloadSummary: DownloadedPayloadSummary
    ) {
        // 文件长度是提交前唯一可信的本地事实，传输计数只用于诊断
        val actualBytes = tempFile.length().coerceAtLeast(0L)
        if (actualBytes <= 0L) {
            throw IOException("下载文件为空: $displayFileName")
        }
        if (payloadSummary.actualBytes > 0L && payloadSummary.actualBytes != actualBytes) {
            NPLogger.w(
                TAG,
                "下载计数与工作文件长度不同，以文件长度为准: file=$displayFileName, " +
                    "reported=${payloadSummary.actualBytes}, fileBytes=$actualBytes"
            )
        }
        if (!isTransferSizeComplete(payloadSummary.expectedBytes, actualBytes)) {
            throw IOException("下载文件不完整: $displayFileName, $actualBytes/${payloadSummary.expectedBytes}")
        }
        NPLogger.d(
            TAG,
            "下载传输校验通过: file=$displayFileName, " +
                "reported=${payloadSummary.actualBytes}, file=$actualBytes, " +
                "expected=${payloadSummary.expectedBytes}"
        )

        val sizeBeforeProbe = tempFile.length().coerceAtLeast(0L)
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
            val sizeAfterProbe = tempFile.length().coerceAtLeast(0L)
            if (sizeAfterProbe != sizeBeforeProbe) {
                throw IOException(
                    "下载文件在完整性校验期间发生变化: " +
                        "$sizeBeforeProbe/$sizeAfterProbe"
                )
            }
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "下载音频完整性校验失败: file=$displayFileName, " +
                    "bytes=$actualBytes, expected=${payloadSummary.expectedBytes}, " +
                    "error=${error.javaClass.simpleName}: ${error.message}",
                error
            )
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
        onSongCancelled: suspend (SongItem) -> Unit = {},
        onSongPausedForNetworkPolicy: suspend (SongItem) -> Unit = {}
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

                suspend fun markSongPaused(songKey: String) {
                    progressMutex.withLock {
                        latestProgressBySongKey.remove(songKey)
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

                val progressJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    progressEvents.collect { progress ->
                        if (!isBatchSessionCurrent(batchSessionId)) {
                            return@collect
                        }
                        if (!trackedSongByKey.containsKey(progress.songKey)) {
                            return@collect
                        }
                        val expectedAttemptId = songAttemptIds[progress.songKey]
                        if (expectedAttemptId != null && progress.attemptId != expectedAttemptId) {
                            return@collect
                        }
                        progressMutex.withLock {
                            latestProgressBySongKey[progress.songKey] = progress
                        }
                        publishBatchProgress()
                    }
                }
                val completionDispatcher = BatchDownloadCompletionDispatcher(
                    scope = this,
                    maxConcurrentCallbacks = BATCH_COMPLETION_CALLBACK_PARALLELISM
                )

                suspend fun processTrackedSong(trackedSong: TrackedBatchSong) {
                    val song = trackedSong.song
                    val songKey = song.stableKey()
                    val attemptId = songAttemptIds[songKey]
                    var completionDispatched = false
                    var pausedForNetworkPolicy = false
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
                        if (shouldPreserveArtifactsForNetworkPolicy(songKey)) {
                            pausedForNetworkPolicy = true
                            markSongPaused(songKey)
                            invokeBatchCallback(song) { onSongPausedForNetworkPolicy(song) }
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
                            completionDispatched = true
                            completionDispatcher.dispatch {
                                try {
                                    GlobalDownloadManager.withSongExecutionLock(songKey) {
                                        invokeBatchCallback(song) { onSongCompleted(song) }
                                    }
                                } finally {
                                    markSongFinished(songKey)
                                }
                            }
                        } catch (_: java.util.concurrent.CancellationException) {
                            NPLogger.d(TAG, "歌曲下载被取消: ${song.name}")
                            if (shouldPreserveArtifactsForNetworkPolicy(songKey)) {
                                pausedForNetworkPolicy = true
                                markSongPaused(songKey)
                                invokeBatchCallback(song) { onSongPausedForNetworkPolicy(song) }
                            } else {
                                clearSongCancelled(songKey)
                                invokeBatchCallback(song) { onSongCancelled(song) }
                            }
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
                            if (!completionDispatched && !pausedForNetworkPolicy) {
                                markSongFinished(songKey)
                            }
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
                        completionDispatcher.awaitAll()
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
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (callbackError: Exception) {
            NPLogger.e(TAG, "批量下载回调失败: ${song.name}", callbackError)
        }
    }
    
    /** 取消下载 */
    fun cancelSongDownload(songKey: String) {
        val calls = synchronized(networkPolicyMutationLock) {
            networkPolicyPausedSongKeys.remove(songKey)
            snapshotActiveCalls(songKey)
        }
        calls.forEach { call ->
            call.cancel()
        }
        clearPublishedProgress(songKey)
        clearVisibleProgressForSong(songKey)
    }

    /** 取消下载 */
    fun cancelDownload() {
        val calls = synchronized(networkPolicyMutationLock) {
            networkPolicyPausedSongKeys.clear()
            snapshotActiveCalls()
        }
        _isCancelled.value = true
        invalidateBatchSession()
        calls.forEach { call ->
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
        val calls = synchronized(networkPolicyMutationLock) {
            networkPolicyPausedSongKeys.addAll(normalizedKeys)
            normalizedKeys
                .flatMap { songKey -> snapshotActiveCalls(songKey) }
                .distinct()
        }
        calls.forEach { call -> call.cancel() }
        _progressFlow.value?.songKey
            ?.takeIf(normalizedKeys::contains)
            ?.let(::clearVisibleProgressForSong)
        normalizedKeys.forEach(::clearPublishedProgress)
    }

    /** 系统执行宿主被外部停止时保留工作文件，供后续恢复 */
    fun pauseSongDownloadForExecutionHost(songKey: String) {
        val normalizedKey = songKey.takeIf(String::isNotBlank) ?: return
        val calls = synchronized(networkPolicyMutationLock) {
            networkPolicyPausedSongKeys.add(normalizedKey)
            snapshotActiveCalls(normalizedKey)
        }
        calls.forEach { call ->
            call.cancel()
        }
        clearPublishedProgress(normalizedKey)
        clearVisibleProgressForSong(normalizedKey)
    }

    fun isDownloadPausedForNetworkPolicy(songKey: String): Boolean {
        return networkPolicyPausedSongKeys.contains(songKey)
    }

    /** 重置取消标志 */
    fun resetCancelFlag() {
        _isCancelled.value = false
    }

    fun clearNetworkPolicyPause(songKeys: Collection<String>) {
        synchronized(networkPolicyMutationLock) {
            songKeys.forEach(networkPolicyPausedSongKeys::remove)
        }
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
        val statusCode = extractYouTubeDownloadHttpStatus(error) ?: return false
        return isRefreshableYouTubeDownloadStatusCode(statusCode)
    }

    // 403 表示服务端拒绝该直链: WEB_REMIX web-GVS 直链在脏 IP 下即便带 pot 也常被 403
    // (同一直链能 range 播放却下不了) ; 据此让下载重试改走不需 pot 的 HLS 兜底
    internal fun isForbiddenYouTubeDownloadFailure(error: Throwable): Boolean {
        return extractYouTubeDownloadHttpStatus(error) == 403
    }

    private fun extractYouTubeDownloadHttpStatus(error: Throwable): Int? {
        if (error is java.util.concurrent.CancellationException) {
            return null
        }
        return when (error) {
            is ChunkRequestIOException -> error.responseCode
            else -> parseHttpStatusCode(error)
        }
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
        // 下载重试只接受已确认的 INTERNET_CAPABILITY_INTERNET，未知状态不能
        // 被当作在线或蜂窝网络，避免切网窗口误恢复或误暂停
        val startedOffline = !context.hasConfirmedInternetAccess()
        var remainingMs = if (startedOffline) {
            maxOf(initialDelayMs, TRANSIENT_DOWNLOAD_OFFLINE_RECOVERY_WAIT_MS)
        } else {
            initialDelayMs
        }
        var recoveredOnlineAtMs: Long? = null
        var observedWakeSignalVersion = retryWakeSignalVersion.value
        while (remainingMs > 0L) {
            ensureSongDownloadNotCancelled(songKey, "retry_wait", batchSessionId, attemptId)
            val hasConfirmedInternetNow = context.hasConfirmedInternetAccess()
            if (startedOffline && hasConfirmedInternetNow) {
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
                if (
                    !startedOffline ||
                        hasConfirmedInternetNow ||
                        context.hasConfirmedInternetAccess()
                ) {
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
        return currentDownloadParallelism(context)
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
        val cachedSnapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        )
        val cachedAudio = cachedSnapshot?.let { snapshot ->
            ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
                ?: ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)
        } ?: ManagedDownloadStorage.peekDownloadedAudio(song)
            ?: ManagedDownloadStorage.peekPendingDownloadedAudio(song)
        cachedAudio?.let { audio ->
            val reference = playableReferenceForManagedAudio(audio)
                ?: return@let
            val evidence = ManagedDownloadReferenceLookup.inspect(
                context,
                reference
            )
            if (canUseReadableManagedAudioForPlayback(
                    song = song,
                    snapshot = cachedSnapshot,
                    audio = audio,
                    evidence = evidence
                )
            ) {
                if (!canExposeManagedDownloadForPlayback(cachedSnapshot, audio)) {
                    NPLogger.d(
                        TAG,
                        "直接确认本地下载音频可读，跳过未完成快照门禁: " +
                            "song=${song.name}, file=${audio.name}, " +
                            "rootEntriesComplete=${cachedSnapshot?.rootEntriesComplete}"
                    )
                    requestBackgroundDownloadIndexRefresh(context)
                }
                return audio
            }
            if (evidence == ManagedDownloadReferenceLookup.Result.Present) {
                NPLogger.d(
                    TAG,
                    "下载音频已存在但仍处于临时或替换状态，暂不作为本地歌曲播放: " +
                        "song=${song.name}, file=${audio.name}"
                )
                return null
            }
            when (evidence) {
                ManagedDownloadReferenceLookup.Result.Present -> return null
                ManagedDownloadReferenceLookup.Result.Missing -> {
                    NPLogger.w(
                        TAG,
                        "本地下载索引确认缺失，准备强制刷新: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(audio)}"
                    )
                    GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
                }
                is ManagedDownloadReferenceLookup.Result.PermissionLost,
                is ManagedDownloadReferenceLookup.Result.ProviderFailure,
                ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                    NPLogger.w(
                        TAG,
                        "本地下载索引引用暂不可确认，保留并等待对账: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(audio)}, " +
                            "evidence=$evidence"
                    )
                    return null
                }
            }
        }

        if (!canBlockStorageLookup()) {
            return null
        }
        val snapshot = cachedSnapshot
            ?: if (ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context, restorePersisted = false)
            } else {
                null
            }
        if (snapshot == null) {
            return null
        }

        val indexedAudio = ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
            ?: ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)
        if (indexedAudio != null) {
            val reference = playableReferenceForManagedAudio(indexedAudio)
                ?: return null
            val evidence = ManagedDownloadReferenceLookup.inspect(
                context,
                reference
            )
            if (canUseReadableManagedAudioForPlayback(
                    song = song,
                    snapshot = snapshot,
                    audio = indexedAudio,
                    evidence = evidence
                )
            ) {
                if (!canExposeManagedDownloadForPlayback(snapshot, indexedAudio)) {
                    NPLogger.d(
                        TAG,
                        "直接确认索引下载音频可读，跳过未完成快照门禁: " +
                            "song=${song.name}, file=${indexedAudio.name}, " +
                            "rootEntriesComplete=${snapshot.rootEntriesComplete}"
                    )
                    requestBackgroundDownloadIndexRefresh(context)
                }
                return indexedAudio
            }
            if (evidence == ManagedDownloadReferenceLookup.Result.Present) {
                NPLogger.d(
                    TAG,
                    "索引音频已存在但仍处于临时或替换状态，暂不作为本地歌曲播放: " +
                        "song=${song.name}, file=${indexedAudio.name}"
                )
                return null
            }
            when (evidence) {
                ManagedDownloadReferenceLookup.Result.Present -> return null
                ManagedDownloadReferenceLookup.Result.Missing -> {
                    NPLogger.w(
                        TAG,
                        "下载索引确认缺失，准备强制刷新: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(indexedAudio)}"
                    )
                    GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
                }
                is ManagedDownloadReferenceLookup.Result.PermissionLost,
                is ManagedDownloadReferenceLookup.Result.ProviderFailure,
                ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                    NPLogger.w(
                        TAG,
                        "下载索引引用暂不可确认，保留并等待对账: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(indexedAudio)}, " +
                            "evidence=$evidence"
                    )
                    return null
                }
            }
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

    /**
     * catalog 已就绪但歌曲身份尚未同步时，只读取持久化快照中的目标条目
     * 这里不枚举 SAF，避免把播放请求拖进整目录扫描
     */
    private fun findDurableCachedManagedAudio(
        context: Context,
        song: SongItem,
        restorePersisted: Boolean = true
    ): CachedManagedAudio? {
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = restorePersisted
        )
        val audio = snapshot?.let { currentSnapshot ->
            ManagedDownloadStorage.findDownloadedAudio(currentSnapshot, song)
                ?: ManagedDownloadStorage.findPendingDownloadedAudio(currentSnapshot, song)
        } ?: ManagedDownloadStorage.peekDownloadedAudio(song)
            ?: ManagedDownloadStorage.peekPendingDownloadedAudio(song)
        return audio?.let { CachedManagedAudio(snapshot = snapshot, audio = it) }
    }

    private fun canUseReadableManagedAudioForPlayback(
        song: SongItem,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        audio: ManagedDownloadStorage.StoredEntry,
        evidence: ManagedDownloadReferenceLookup.Result
    ): Boolean {
        if (evidence != ManagedDownloadReferenceLookup.Result.Present) {
            return false
        }
        val metadata = metadataForManagedAudio(snapshot, audio)
        return isReadableManagedAudioPlaybackAllowed(
            audioIsPending = audio.isPendingAudioWrite,
            downloadActive = isSongDownloadActive(song.stableKey()),
            downloadCancelled = GlobalDownloadManager.isSongCancelled(song.stableKey()),
            metadata = metadata,
            allowLegacyPublishedAudio = !audio.isPendingAudioWrite
        )
    }

    private fun metadataForManagedAudio(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        audio: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        return ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
    }

    private fun playableReferenceForManagedAudio(
        audio: ManagedDownloadStorage.StoredEntry
    ): String? {
        // 迁移快照可能同时包含旧私有路径和新的 SAF URI，优先使用当前根下的引用
        // 避免目录切换和目录刷新之间播放器打开过期路径
        return selectManagedPlaybackReferenceForConfiguredSaf(
            references = listOfNotNull(
                audio.mediaUri,
                audio.reference,
                audio.localFilePath
            ),
            configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri(),
            allowPending = audio.isPendingAudioWrite
        )
    }

    /**
     * 只读取现有内存快照判断是否存在明确的临时写入或替换凭据
     *
     * 这里不能触发目录扫描，否则播放线程会被 SAF 查询拖慢
     */
    private fun isManagedReferenceExplicitlyIncomplete(
        context: Context,
        song: SongItem,
        reference: String
    ): Boolean {
        val songKey = song.stableKey()
        if (
            GlobalDownloadManager.isSongCancelled(songKey) ||
            reference.lowercase(Locale.ROOT).contains(
                DOWNLOAD_STAGING_DIR_NAME.lowercase(Locale.ROOT)
            )
        ) {
            return true
        }
        val referenceLooksPending = reference.contains(
            PENDING_AUDIO_WRITE_MARKER,
            ignoreCase = true
        )
        val referenceName = ManagedDownloadStorage.normalizeManagedAudioFileName(reference)
        if (
            referenceName?.let { name ->
                name.startsWith(DOWNLOAD_STAGING_FILE_PREFIX) &&
                    name.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX)
            } == true
        ) {
            return true
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return referenceLooksPending
        val indexedFileName = referenceName
        // 先按用户手里的精确引用匹配, 防止同一首歌的 pending 条目遮蔽旧正式文件
        val exactReferences = listOfNotNull(
            reference,
            safeToPlayableUri(reference)
        ).distinct()
        fun matchesReference(audio: ManagedDownloadStorage.StoredEntry): Boolean {
            return exactReferences.any { candidate ->
                candidate == audio.reference ||
                    candidate == audio.mediaUri ||
                    candidate == audio.localFilePath ||
                    candidate == ManagedDownloadStorage.resolveStoredEntryPlaybackUri(
                        entry = audio,
                        allowPending = true
                    )
            }
        }
        val exactAudio = snapshot.audioEntries.firstOrNull(::matchesReference)
            ?: snapshot.pendingAudioEntries.firstOrNull(::matchesReference)
        if (exactAudio == null && !referenceLooksPending) {
            // 旧版本保存的 tree/document URI 可能与当前扫描得到的 URI 外形不同。
            // 只要用户手里的正式引用已经得到 Present 证据, 不能让另一个
            // 同身份的 pending 条目遮蔽它并误报为暂不可播放。
            val formalAudio = snapshot.audioEntries.firstOrNull { audio ->
                !audio.isPendingAudioWrite &&
                    (audio.name == indexedFileName ||
                        audio.logicalName == indexedFileName)
            }
            return formalAudio?.let { audio ->
                !isReadableManagedAudioPlaybackAllowed(
                    audioIsPending = false,
                    downloadActive = false,
                    downloadCancelled = false,
                    metadata = metadataForManagedAudio(snapshot, audio),
                    allowLegacyPublishedAudio = true
                )
            } ?: false
        }
        val matchedAudio = exactAudio
            ?: ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
            ?: ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)
            ?: snapshot.audioEntries.firstOrNull { audio ->
                audio.name == indexedFileName
            }
            ?: return referenceLooksPending
        return !isReadableManagedAudioPlaybackAllowed(
            audioIsPending = matchedAudio.isPendingAudioWrite,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadataForManagedAudio(snapshot, matchedAudio),
            allowLegacyPublishedAudio = !matchedAudio.isPendingAudioWrite
        )
    }

    private fun hasFastCachedManagedDownloadForStart(
        context: Context,
        song: SongItem
    ): Boolean {
        val cached = findDurableCachedManagedAudio(context, song)
        val snapshot = cached?.snapshot
        val cachedAudio = cached?.audio
        if (cachedAudio != null) {
            if (isFinalizedDownloadedMetadata(
                    ManagedDownloadStorage.metadataForAudioEntry(snapshot, cachedAudio)
                )
            ) {
                return true
            }
            NPLogger.d(
                TAG,
                "下载快照命中未完成音频，交由完成链路收尾而非重复传输: " +
                    "song=${song.name}, file=${cachedAudio.name}"
            )
            return true
        }
        return GlobalDownloadManager.findFastCachedDownloadedSongPlaybackUri(context, song) != null
    }

    internal fun resolveLocalLyricForDownload(rawLyric: String?): String? {
        return rawLyric?.takeIf { it.isNotBlank() }
    }

    internal fun shouldFetchRemoteLyricForDownload(rawLyric: String?): Boolean {
        return rawLyric == null
    }

    internal fun shouldFetchRomanizedLyricForDownload(
        shouldFetchPrimaryLyric: Boolean,
        shouldFetchTranslatedLyric: Boolean
    ): Boolean {
        return shouldFetchPrimaryLyric || shouldFetchTranslatedLyric
    }

    /** 下载歌词文件 */
    private suspend fun downloadLyrics(
        context: Context,
        song: SongItem,
        songKey: String,
        baseName: String,
        serializeWrites: Boolean = true,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        requireActiveAttempt: Boolean = true
    ): DownloadedSidecarReferences {
        var lyricReference: String? = null
        var translatedLyricReference: String? = null
        var romanizedLyricReference: String? = null
        var expectedLyric = false
        var expectedTranslatedLyric = false
        var expectedRomanizedLyric = false
        var lyricText: String? = null
        var translatedText: String? = null
        var romanizedText: String? = null
        try {
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "lyrics_prepare",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
            lyricText = resolveLocalLyricForDownload(song.matchedLyric)
            translatedText = resolveLocalLyricForDownload(song.matchedTranslatedLyric)
            val shouldFetchPrimaryLyric = shouldFetchRemoteLyricForDownload(song.matchedLyric)
            val shouldFetchTranslatedLyric =
                shouldFetchRemoteLyricForDownload(song.matchedTranslatedLyric)
            val shouldFetchRomanizedLyric = shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = shouldFetchPrimaryLyric,
                shouldFetchTranslatedLyric = shouldFetchTranslatedLyric
            )
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
                        shouldFetchTranslatedLyric = shouldFetchTranslatedLyric && translatedText == null,
                        shouldFetchRomanizedLyric = shouldFetchRomanizedLyric && romanizedText == null
                    )
                    if (lyricText == null && shouldFetchPrimaryLyric) {
                        lyricText = downloaded.lyricText
                    }
                    if (translatedText == null && shouldFetchTranslatedLyric) {
                        translatedText = downloaded.translatedText
                    }
                    if (romanizedText == null && shouldFetchRomanizedLyric) {
                        romanizedText = downloaded.romanizedText
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
            expectedLyric = !lyricText.isNullOrBlank()
            expectedTranslatedLyric = !translatedText.isNullOrBlank()
            expectedRomanizedLyric = !romanizedText.isNullOrBlank()

            suspend fun writePrimaryLyric(): String? {
                val lyric = lyricText?.takeIf(String::isNotBlank) ?: return null
                val reference = writeManagedLyrics(
                    context = context,
                    song = song,
                    baseName = baseName,
                    content = lyric,
                    translated = false
                )
                reference?.let { storedReference ->
                    rememberPartialSidecarReferences(
                        songKey,
                        DownloadedSidecarReferences(
                            lyricReference = storedReference,
                            createdLyric = true,
                            lyricContent = inlineLyricContent(lyric)
                        )
                    )
                    NPLogger.d(
                        TAG,
                        "歌词写入完成: song=${song.name}, reference=$storedReference"
                    )
                }
                return reference
            }

            suspend fun writeTranslatedLyric(): String? {
                val lyric = translatedText?.takeIf(String::isNotBlank) ?: return null
                val reference = writeManagedLyrics(
                    context = context,
                    song = song,
                    baseName = baseName,
                    content = lyric,
                    translated = true
                )
                reference?.let { storedReference ->
                    rememberPartialSidecarReferences(
                        songKey,
                        DownloadedSidecarReferences(
                            translatedLyricReference = storedReference,
                            createdTranslatedLyric = true,
                            translatedLyricContent = inlineLyricContent(lyric)
                        )
                    )
                    NPLogger.d(
                        TAG,
                        "翻译歌词写入完成: song=${song.name}, reference=$storedReference"
                    )
                }
                return reference
            }

            suspend fun writeRomanizedLyric(): String? {
                val lyric = romanizedText?.takeIf(String::isNotBlank) ?: return null
                ensureSongDownloadNotCancelled(
                    songKey = songKey,
                    stage = "lyrics_romanized_write",
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    requireActiveAttempt = requireActiveAttempt
                )
                val reference = ManagedDownloadStorage.writeRomanizedLyrics(
                    context = context,
                    songId = song.id,
                    baseName = baseName,
                    content = lyric
                )
                reference?.let { storedReference ->
                    rememberPartialSidecarReferences(
                        songKey,
                        DownloadedSidecarReferences(
                            romanizedLyricReference = storedReference,
                            createdRomanizedLyric = true,
                            romanizedLyricContent = inlineLyricContent(lyric)
                        )
                    )
                    NPLogger.d(
                        TAG,
                        "音译歌词写入完成: song=${song.name}, reference=$storedReference"
                    )
                }
                return reference
            }

            if (serializeWrites) {
                lyricReference = writePrimaryLyric()
                ensureSongDownloadNotCancelled(
                    songKey = songKey,
                    stage = "lyrics_primary_written",
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    requireActiveAttempt = requireActiveAttempt
                )
                translatedLyricReference = writeTranslatedLyric()
                romanizedLyricReference = writeRomanizedLyric()
            } else {
                coroutineScope {
                    val primaryJob = async(Dispatchers.IO) { writePrimaryLyric() }
                    val translatedJob = async(Dispatchers.IO) { writeTranslatedLyric() }
                    val romanizedJob = async(Dispatchers.IO) { writeRomanizedLyric() }
                    lyricReference = primaryJob.await()
                    translatedLyricReference = translatedJob.await()
                    romanizedLyricReference = romanizedJob.await()
                }
            }
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "lyrics_primary_written",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                requireActiveAttempt = requireActiveAttempt
            )
        } catch (cancellation: java.util.concurrent.CancellationException) {
            NPLogger.d(TAG, "歌词整理阶段收到取消: ${song.name}")
            throw cancellation
        } catch (e: Exception) {
            NPLogger.w(
                TAG,
                "歌词下载失败: ${song.name} - ${e.javaClass.simpleName}: ${e.message}",
                e
            )
        }
        return DownloadedSidecarReferences(
            lyricReference = lyricReference,
            translatedLyricReference = translatedLyricReference,
            romanizedLyricReference = romanizedLyricReference,
            expectedLyric = expectedLyric,
            expectedTranslatedLyric = expectedTranslatedLyric,
            expectedRomanizedLyric = expectedRomanizedLyric,
            createdLyric = !lyricReference.isNullOrBlank(),
            createdTranslatedLyric = !translatedLyricReference.isNullOrBlank(),
            createdRomanizedLyric = !romanizedLyricReference.isNullOrBlank(),
            lyricContent = lyricReference?.let { inlineLyricContent(lyricText) },
            translatedLyricContent = translatedLyricReference?.let {
                inlineLyricContent(translatedText)
            },
            romanizedLyricContent = romanizedLyricReference?.let {
                inlineLyricContent(romanizedText)
            }
        )
    }

    private fun inlineLyricContent(content: String?): String? {
        val normalized = content?.takeIf(String::isNotBlank) ?: return null
        return normalized.takeIf {
            it.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_INLINE_SIDECAR_LYRIC_BYTES
        }
    }

    /** 从 LRCLIB / YouTube Music API 获取歌词并保存 */
    private suspend fun downloadYouTubeMusicLyrics(
        song: SongItem
    ): String? {
        if (!shouldFetchRemoteLyricForDownload(song.matchedLyric)) return null
        try {
            val lrcLibResult = try {
                val durationSec = song.durationMs / 1_000L
                AppContainer.lrcLibClient.getLyrics(
                    trackName = song.name,
                    artistName = song.artist,
                    durationSeconds = durationSec
                ) ?: AppContainer.lrcLibClient.searchLyrics(
                    trackName = song.name,
                    artistName = song.artist,
                    durationSeconds = durationSec
                )
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
        shouldFetchTranslatedLyric: Boolean = true,
        shouldFetchRomanizedLyric: Boolean = true
    ): DownloadedLyrics {
        if (!shouldFetchPrimaryLyric && !shouldFetchTranslatedLyric && !shouldFetchRomanizedLyric) {
            return DownloadedLyrics()
        }

        if (!shouldFetchPrimaryLyric && !shouldFetchRomanizedLyric) {
            try {
                val lyrics = AppContainer.neteaseClient.getLyricNew(song.id)
                val root = JSONObject(lyrics)
                if (root.optInt("code") == 200) {
                    val tlyric: String = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
                    val romalrc = root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
                    if (shouldFetchTranslatedLyric && tlyric.isNotBlank()) {
                        NPLogger.d(TAG, "翻译歌词保存: ${song.name}")
                        return DownloadedLyrics(
                            translatedText = tlyric,
                            romanizedText = romalrc.takeIf {
                                shouldFetchRomanizedLyric && it.isNotBlank()
                            }
                        )
                    }
                    if (shouldFetchRomanizedLyric && romalrc.isNotBlank()) {
                        return DownloadedLyrics(romanizedText = romalrc)
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

            val yrc: String = root.optJSONObject("yrc")?.optString("lyric") ?: ""
            val lrc: String = root.optJSONObject("lrc")?.optString("lyric") ?: ""
            val translated: String = root.optJSONObject("tlyric")?.optString("lyric") ?: ""
            val romanized: String = root.optJSONObject("romalrc")?.optString("lyric") ?: ""
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
                },
                romanizedText = romanized.takeIf {
                    shouldFetchRomanizedLyric && it.isNotBlank()
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
        if (GlobalDownloadManager.isSongCancelled(songKey)) {
            return null
        }
        val managedDownloadHint = runCatching {
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song)
        }.getOrDefault(false)
        if (shouldWaitForManagedPlaybackDirectoryMutation(
                isManagedDownload = managedDownloadHint,
                mutationActive = ManagedDownloadDirectoryMutationFence.isActiveFast(context)
            )
        ) {
            NPLogger.d(
                TAG,
                "同步播放解析遇到目录迁移，暂不使用旧引用: song=${song.name}"
            )
            return null
        }
        resolveRecentlyCommittedAudioReference(context, song)?.let { return it }
        // 下载核心提交后内存目录已经有可验证引用时，先走零扫描路径。
        // 目录快照可能仍在后台刷新，不能让首播等待一次完整 SAF 枚举。
        val catalogPlaybackReference = GlobalDownloadManager
            .findAccessibleDownloadedSongPlaybackUri(
            context = context,
            song = song
        )
        if (
            catalogPlaybackReference != null &&
            (!managedDownloadHint || isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = catalogPlaybackReference,
                configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
            ))
        ) {
            return catalogPlaybackReference
        }
        if (catalogPlaybackReference != null) {
            NPLogger.d(
                TAG,
                "目录切换后忽略缓存中的旧播放引用: " +
                    "song=${song.name}, reference=$catalogPlaybackReference"
            )
        }
        resolveReadableManagedDownload(context, song)
            ?.let(::playableReferenceForManagedAudio)
            ?.let { return it }
        val indexedReferences = GlobalDownloadManager.findDownloadedSongCached(song)
            ?.let(::downloadedSongPlaybackReferenceCandidates)
            ?: return null
        return indexedReferences.asSequence()
            .filter { reference ->
                !managedDownloadHint || isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                    reference = reference,
                    configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
                )
            }
            .mapNotNull { reference ->
                resolveReboundDownloadedPlaybackUri(context, reference)
            }
            .firstOrNull()
    }

    private fun resolveRecentlyCommittedAudioReference(
        context: Context,
        song: SongItem,
        rawReference: String? = null
    ): String? {
        val songKey = song.stableKey()
        val audio = rawReference?.let(::peekCompletedAudioReferenceByRawReference)
            ?: peekCompletedAudioReference(song)
            ?: return null
        val reference = playableReferenceForManagedAudio(audio) ?: return null
        if (shouldDiscardCompletedReferenceForConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
            )
        ) {
            invalidateCompletedAudioReference(song)
            NPLogger.d(
                TAG,
                "目录已切换到 SAF，丢弃旧完成桥接引用并等待当前根重绑定: " +
                    "songKey=$songKey, reference=$reference"
            )
            return null
        }
        if (isMissingFilePlaybackReference(reference)) {
            invalidateCompletedAudioReference(song)
            NPLogger.d(
                TAG,
                "完成桥接引用已不存在，等待当前存储根重新绑定: " +
                    "songKey=$songKey, reference=$reference"
            )
            return null
        }
        if (!shouldUseCompletedAudioReferenceDirectly(
                reference = reference,
                downloadCancelled = GlobalDownloadManager.isSongCancelled(songKey)
            )
        ) {
            return null
        }
        NPLogger.d(
            TAG,
            "core 音频已提交，目录回调尚未完成，直接使用已校验引用并后台校验: " +
                "songKey=$songKey, reference=$reference"
        )
        // 这里不能同步查询 SAF。后台扫描只负责更新索引，Provider 短暂异常时保留桥接。
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
        return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
    }

    /**
     * 受管目录中的本地引用必须经过完成凭据校验，不能仅凭文件仍可读取就播放
     */
    suspend fun resolvePermittedLocalPlaybackUri(
        context: Context,
        song: SongItem,
        rawLocalReference: String?
    ): String? {
        return (resolvePermittedLocalPlayback(
            context = context,
            song = song,
            rawLocalReference = rawLocalReference
        ) as? LocalPlaybackReferenceResolution.Playable)?.reference
    }

    internal suspend fun resolvePermittedLocalPlayback(
        context: Context,
        song: SongItem,
        rawLocalReference: String?
    ): LocalPlaybackReferenceResolution = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val reference = rawLocalReference?.trim()?.takeIf(String::isNotBlank)
        if (reference == null) {
            // 播放队列可能先恢复歌曲身份, 再异步补齐本地引用。优先读取
            // 已提交的内存桥和快速索引, 不要把这个短窗口误报成远程地址失败
            getLocalPlaybackUri(appContext, song)?.let { verifiedReference ->
                return@withContext LocalPlaybackReferenceResolution.Playable(
                    verifiedReference
                )
            }
            return@withContext LocalPlaybackReferenceResolution.TemporarilyUnavailable(
                ManagedDownloadReferenceLookup.Result.OutOfScope
            )
        }
        val managedDownloadHint = runCatching {
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(appContext, song)
        }.getOrDefault(false)
        if (shouldWaitForManagedPlaybackDirectoryMutation(
                isManagedDownload = managedDownloadHint,
                mutationActive = runCatching {
                    ManagedDownloadDirectoryMutationFence.isActive(appContext)
                }.getOrDefault(true)
            )
        ) {
            val opened = withTimeoutOrNull(DIRECTORY_MUTATION_PLAYBACK_WAIT_MS) {
                ManagedDownloadDirectoryMutationFence.awaitOpen()
                true
            } == true
            if (!opened) {
                NPLogger.d(
                    TAG,
                    "目录迁移仍在进行，暂不使用旧播放引用: song=${song.name}"
                )
                return@withContext LocalPlaybackReferenceResolution.TemporarilyUnavailable(
                    ManagedDownloadReferenceLookup.Result.OutOfScope
                )
            }
        }
        // core 提交桥已经验证过完整音频, 必须在任何 SAF 查询前消费它
        resolveRecentlyCommittedAudioReference(
            context = appContext,
            song = song,
            rawReference = reference
        )?.let { bridgedReference ->
            return@withContext LocalPlaybackReferenceResolution.Playable(bridgedReference)
        }
        val isManagedDownload = try {
            ManagedDownloadStorage.isLikelyManagedDownloadSong(appContext, song)
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "识别受管本地音频失败，按内存线索保守处理: ${error.message}"
            )
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(appContext, song)
        }
        val configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val rawReferenceCompatible = !isManagedDownload ||
            isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = configuredDirectoryUri
            )
        if (isManagedDownload && !rawReferenceCompatible) {
            NPLogger.d(
                TAG,
                "目录切换后拒绝旧受管播放引用，准备重绑当前根: " +
                    "song=${song.name}, reference=$reference"
            )
        }
        val rawEvidence = if (rawReferenceCompatible) {
            ManagedDownloadReferenceLookup.inspect(appContext, reference)
        } else {
            // 旧根引用按暂时不可用处理，交给有界重绑定流程，不再探测旧 Provider
            ManagedDownloadReferenceLookup.Result.Missing
        }
        val managedReferenceIsExplicitlyIncomplete = if (isManagedDownload && rawReferenceCompatible) {
            isManagedReferenceExplicitlyIncomplete(
                context = appContext,
                song = song,
                reference = reference
            )
        } else {
            false
        }
        if (
            rawReferenceCompatible && shouldUseDirectPresentLocalPlayback(
                reference = reference,
                isManagedDownload = isManagedDownload,
                evidence = rawEvidence,
                downloadCancelled = GlobalDownloadManager.isSongCancelled(song.stableKey())
            )
        ) {
            if (isManagedDownload && managedReferenceIsExplicitlyIncomplete) {
                NPLogger.d(
                    TAG,
                    "正式下载引用已确认可读，跳过旧完成状态门禁: " +
                        "song=${song.name}, reference=$reference"
                )
            }
            return@withContext selectPermittedLocalPlaybackResolution(
                rawLocalReference = reference,
                isManagedDownload = isManagedDownload,
                verifiedManagedReference = null,
                rawEvidence = rawEvidence,
                managedReferenceIsExplicitlyIncomplete = false
            )
        }
        val verifiedManagedReference = if (isManagedDownload) {
            getLocalPlaybackUri(appContext, song)
                ?: if (rawEvidence == ManagedDownloadReferenceLookup.Result.Missing) {
                    forceResolveManagedPlaybackAfterMissing(
                        context = appContext,
                        song = song
                    )
                } else {
                    null
                }
        } else {
            null
        }
        // getLocalPlaybackUri 已经完成 Present 和 core 凭据校验，不能再被原始
        // pending 引用的旧标记覆盖，否则刚提交的音频会被误判为暂不可用
        val effectiveIncomplete = if (verifiedManagedReference != null) {
            false
        } else {
            managedReferenceIsExplicitlyIncomplete
        }
        selectPermittedLocalPlaybackResolution(
            rawLocalReference = reference,
            isManagedDownload = isManagedDownload,
            verifiedManagedReference = verifiedManagedReference,
            rawEvidence = rawEvidence,
            managedReferenceIsExplicitlyIncomplete = effectiveIncomplete,
            missingIsTransient = isRecentManagedPlaybackReference(
                song = song,
                reference = reference
            )
        )
    }

    /** 旧 catalog 只剩失效路径时，单飞一次强制快照重绑到迁移后的引用 */
    private suspend fun forceResolveManagedPlaybackAfterMissing(
        context: Context,
        song: SongItem
    ): String? {
        val songKey = song.stableKey()
        val nowMs = System.currentTimeMillis()
        val shouldRefresh = synchronized(managedPlaybackRebindAtMsBySongKey) {
            val previousAtMs = managedPlaybackRebindAtMsBySongKey[songKey]
            if (previousAtMs != null && nowMs - previousAtMs < MANAGED_PLAYBACK_REBIND_COOLDOWN_MS) {
                false
            } else {
                managedPlaybackRebindAtMsBySongKey[songKey] = nowMs
                true
            }
        }
        if (!shouldRefresh) {
            return null
        }
        val audio = try {
            ManagedDownloadStorage.findDownloadedAudio(
                context = context,
                song = song,
                forceRefresh = true
            ) ?: ManagedDownloadStorage.peekDownloadedAudio(song)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "旧本地引用缺失时强制重绑失败，保留目录等待重试: " +
                    "song=${song.name}, error=${error.message}",
                error
            )
            return null
        }
        val reference = audio
            ?.let(::playableReferenceForManagedAudio)
            ?.takeIf { candidate ->
                ManagedDownloadReferenceLookup.inspect(context, candidate) ==
                    ManagedDownloadReferenceLookup.Result.Present
            }
        if (reference != null) {
            requestBackgroundDownloadIndexRefresh(context)
            return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
        }
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
        return null
    }

    private fun isMissingFilePlaybackReference(reference: String): Boolean {
        val normalized = reference.trim()
        val path = when {
            normalized.startsWith("/") -> normalized
            normalized.startsWith("file:", ignoreCase = true) -> {
                runCatching { URI(normalized).path }.getOrNull()
            }
            else -> null
        } ?: return false
        return !File(path).isFile
    }

    internal fun resolveIndexedLocalPlaybackReference(
        context: Context,
        song: SongItem
    ): LocalPlaybackReferenceResolution {
        if (!mayHaveIndexedLocalDownload(context, song)) {
            return LocalPlaybackReferenceResolution.NotIndexed
        }
        // mayHaveIndexedLocalDownload 可能刚从 Room/磁盘快照恢复了条目。
        // catalog 仍未发布时也要把该持久化引用带入后续完整性门禁。
        val durableCachedAudio = findDurableCachedManagedAudio(
            context = context,
            song = song,
            restorePersisted = false
        )
        val verifiedReference = getLocalPlaybackUri(context, song)
        val indexedReference = listOfNotNull(
            GlobalDownloadManager.findDownloadedSongCached(song)
                ?.let(::resolveDownloadedSongPlaybackReference),
            durableCachedAudio?.audio?.let(::playableReferenceForManagedAudio),
            ManagedDownloadStorage.peekPendingDownloadedAudio(song)
                ?.let(::playableReferenceForManagedAudio)
        ).firstOrNull { reference ->
            isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
            )
        }
        val indexedEvidence = if (verifiedReference == null && indexedReference != null) {
            ManagedDownloadReferenceLookup.inspect(context, indexedReference)
        } else {
            null
        }
        if (
            verifiedReference == null &&
            indexedReference != null &&
            shouldUseDirectPresentLocalPlayback(
                reference = indexedReference,
                isManagedDownload = true,
                evidence = indexedEvidence
                    ?: ManagedDownloadReferenceLookup.Result.OutOfScope,
                downloadCancelled = GlobalDownloadManager.isSongCancelled(song.stableKey())
            )
        ) {
            NPLogger.d(
                TAG,
                "索引中的正式下载音频已确认可读，跳过旧完成状态门禁: " +
                    "song=${song.name}, reference=$indexedReference"
            )
            return LocalPlaybackReferenceResolution.Playable(indexedReference)
        }
        val indexedReferenceIsExplicitlyIncomplete = if (
            verifiedReference == null &&
                indexedReference != null &&
                indexedEvidence == ManagedDownloadReferenceLookup.Result.Present
        ) {
            isManagedReferenceExplicitlyIncomplete(
                context = context,
                song = song,
                reference = indexedReference
            )
        } else {
            false
        }
        return selectIndexedLocalPlaybackResolution(
            verifiedReference = verifiedReference,
            indexedReference = indexedReference,
            indexedEvidence = indexedEvidence,
            indexedReferenceIsExplicitlyIncomplete = indexedReferenceIsExplicitlyIncomplete,
            missingIsTransient = indexedReference?.let {
                isRecentManagedPlaybackReference(song = song, reference = it)
            } == true
        )
    }

    private fun isRecentManagedPlaybackReference(
        song: SongItem,
        reference: String
    ): Boolean {
        return reference.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true) ||
            peekCompletedAudioReference(song) != null ||
            isSongDownloadActive(song.stableKey())
    }

    fun mayHaveIndexedLocalDownload(context: Context, song: SongItem): Boolean {
        if (peekCompletedAudioReference(song) != null) {
            return true
        }
        if (GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return true
        }
        if (ManagedDownloadStorage.peekPendingDownloadedAudio(song) != null) {
            return true
        }
        // catalogReady 只表示内存目录已发布，不代表它已包含当前队列身份。
        // 仅在目录已发布且内存索引 miss 时恢复一次 Room/磁盘快照，避免播放
        // 请求重复读取持久化索引；快照查询本身不会枚举 SAF。
        val catalogReady = GlobalDownloadManager.isDownloadedSongCatalogReady()
        if (
            findDurableCachedManagedAudio(
                context = context,
                song = song,
                restorePersisted = catalogReady
            ) != null
        ) {
            return true
        }
        if (catalogReady) {
            return false
        }
        if (!canBlockStorageLookup()) {
            return false
        }
        if (!ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
            return false
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return false
        val audio = ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
        if (audio != null) {
            // 快照可能仍是增量或预览状态，真正的完整性和可读性由
            // getLocalPlaybackUri 的 Present 和 artifact 凭据校验负责
            return true
        }
        return ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song) != null
    }

    fun hasLocalDownload(context: Context, song: SongItem): Boolean {
        val songKey = song.stableKey()
        if (GlobalDownloadManager.isSongCancelled(songKey) || isSongDownloadActive(songKey)) {
            return false
        }
        return resolveReadableDownloadedPlaybackUri(context, song) != null
    }

    /**
     * 只读取已驻留下载索引，供滚动列表恢复已有封面，避免触发目录扫描
     */
    fun peekLocalCoverUri(song: SongItem): String? {
        if (!GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return null
        }
        val localAudio = ManagedDownloadStorage.peekDownloadedAudio(song) ?: return null
        val coverReference = ManagedDownloadStorage.peekCoverReference(localAudio) ?: return null
        return ManagedDownloadStorage.toPlayableUri(coverReference) ?: coverReference
    }

    /** 解析下载歌曲对应的本地封面, 供离线 UI 兜底使用 */
    fun getLocalCoverUri(
        context: Context,
        song: SongItem,
        resolveLocalMediaFallback: Boolean = true
    ): String? {
        val allowBlockingLookup = canBlockStorageLookup()
        val localAudio = findFinalizedCachedAudioForCover(
            context = context,
            song = song,
            allowBlockingLookup = allowBlockingLookup
        )
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
        if (!resolveLocalMediaFallback) {
            return null
        }
        if (!LocalSongSupport.isLocalSong(song)) {
            return null
        }

        return runCatching {
            LocalMediaSupport.resolveCoverUri(context, song)
        }.getOrElse {
            NPLogger.w(TAG, "resolve local cover fallback failed: ${it.message}")
            null
        }
    }

    private fun findFinalizedCachedAudioForCover(
        context: Context,
        song: SongItem,
        allowBlockingLookup: Boolean
    ): ManagedDownloadStorage.StoredEntry? {
        fun finalizedAudio(
            snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?
        ): ManagedDownloadStorage.StoredEntry? {
            val audio = snapshot?.let { currentSnapshot ->
                ManagedDownloadStorage.findDownloadedAudio(currentSnapshot, song)
            }
            return audio?.takeIf { candidate ->
                canExposeManagedDownloadForPlayback(snapshot, candidate)
            }
        }

        finalizedAudio(
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                context = context,
                restorePersisted = false
            )
        )?.let { return it }
        if (!allowBlockingLookup || !ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
            return null
        }
        return finalizedAudio(
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                context = context,
                restorePersisted = false
            )
        )
    }

    private fun resolveReadableDownloadedPlaybackUri(
        context: Context,
        song: SongItem
    ): String? {
        resolveReadableManagedDownload(context, song)
            ?.let(::playableReferenceForManagedAudio)
            ?.let { return it }
        val catalogPlaybackUri = GlobalDownloadManager.findAccessibleDownloadedSongPlaybackUri(
            context = context,
            song = song
        )?.takeIf { reference ->
            !ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song) ||
                isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                    reference = reference,
                    configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
                )
        } ?: return null
        NPLogger.d(
            TAG,
            "下载索引未命中，回退下载目录缓存播放: song=${song.name}, reference=$catalogPlaybackUri"
        )
        return catalogPlaybackUri
    }

    private fun resolveReboundDownloadedPlaybackUri(
        context: Context,
        indexedReference: String
    ): String? {
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return null
        val reboundAudio = findReboundFinalizedManagedAudio(snapshot, indexedReference)
            ?: return null
        val playbackUri = playableReferenceForManagedAudio(reboundAudio) ?: return null
        return when (ManagedDownloadReferenceLookup.inspect(context, playbackUri)) {
            ManagedDownloadReferenceLookup.Result.Present -> playbackUri
            else -> null
        }
    }

    private fun candidateBaseNames(song: SongItem): List<String> {
        return ManagedDownloadStorage.buildCandidateBaseNames(song)
    }

    fun getLyricContent(context: Context, song: SongItem): String? {
        return ManagedDownloadStorage.readLyrics(context, song, translated = false)
    }

    internal fun getLyricsBundle(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.DownloadedLyricsBundle {
        return ManagedDownloadStorage.readLyricsBundle(context, song)
    }

    internal fun getLyricsBundleFast(
        context: Context,
        song: SongItem,
        allowColdSafProbe: Boolean = true
    ): ManagedDownloadStorage.DownloadedLyricsBundle {
        return ManagedDownloadStorage.readLyricsBundleFast(
            context = context,
            song = song,
            allowColdSafProbe = allowColdSafProbe
        )
    }

    fun getTranslatedLyricContent(context: Context, song: SongItem): String? {
        return ManagedDownloadStorage.readLyrics(context, song, translated = true)
    }

    fun getRomanizedLyricContent(context: Context, song: SongItem): String? {
        return ManagedDownloadStorage.readRomanizedLyrics(context, song)
    }

    // 解析网易云直链
    private suspend fun resolveNetease(
        songId: Long,
        preferredQuality: String
    ): ResolvedDownloadSource? {
        val raw = AppContainer.neteaseClient.getSongDownloadUrl(songId, level = preferredQuality)
        return try {
            val root = JSONObject(raw)
            if (root.optInt("code") != 200) return tryWeapiFallback(songId, preferredQuality)
            val data = NeteasePlaybackResponseParser.parseDownloadInfo(raw)
                ?: return tryWeapiFallback(songId, preferredQuality)
            val url = data.url
            val type = data.type.orEmpty() // e.g., mp3/flac
            val mime = guessMimeFromUrl(url)
            ResolvedDownloadSource(
                url = ensureHttps(url),
                mimeType = mime,
                fileExtensionHint = type.lowercase().ifBlank { extFromUrl(url) },
                contentLength = data.contentLength
            )
        } catch (_: Exception) {
            tryWeapiFallback(songId, preferredQuality)
        }
    }

    private fun bitrateForQuality(level: String): Int = when (level.lowercase()) {
        "standard" -> 128000
        "higher" -> 192000
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
            ResolvedDownloadSource(
                url = finalUrl,
                mimeType = mime,
                fileExtensionHint = ext,
                contentLength = data.contentLength
            )
        } catch (_: Exception) { null }
    }

    private suspend fun resolveYouTubeMusic(
        song: SongItem,
        preferredQuality: String,
        forceRefresh: Boolean = false,
        avoidDirect: Boolean = false
    ): ResolvedDownloadSource? {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri) ?: return null
        var directPlayableAudio: YouTubePlayableAudio? = null
        var fallbackPlayableAudio: YouTubePlayableAudio? = null
        // 直链下载曾被 403 时(avoidDirect)只用不要求直链的策略并跳过所有直链候选, 改优先 HLS:
        // HLS(m3u8) 的 GVS 不需要 pot, 脏 IP 下比 WEB_REMIX web-GVS 直链稳 (对齐 yt-dlp/PoToken 指南)
        val attempts = resolveYouTubeDownloadResolveAttempts(forceRefresh)
            .let { list -> if (avoidDirect) list.filterNot { it.requireDirect } else list }
        for (attempt in attempts) {
            val candidate = resolveYouTubeMusicDownloadAudio(
                videoId = videoId,
                attempt = attempt,
                preferredQuality = preferredQuality,
                avoidDirect = avoidDirect
            ) ?: continue
            if (candidate.streamType == YouTubePlayableStreamType.DIRECT) {
                if (avoidDirect) {
                    NPLogger.w(
                        TAG,
                        "直链下载曾 403，改走 HLS：跳过直链候选 videoId=$videoId, mode=${attempt.logLabel}"
                    )
                    continue
                }
                // WEB_REMIX 直链缺 pot 时:1 字节探活可过但整段下载必被 googlevideo 403
                // 跳过该候选继续降级(重解析 mint pot / HLS 兜底), 避免下载拿到必失败的直链
                if (isYouTubeWebRemixDirectMissingPoToken(candidate.url)) {
                    NPLogger.w(
                        TAG,
                        "YouTube Music 下载直链为 WEB_REMIX 但缺少 pot，跳过继续降级: videoId=$videoId, mode=${attempt.logLabel}"
                    )
                    continue
                }
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
        attempt: YouTubeDownloadResolveAttempt,
        preferredQuality: String,
        avoidDirect: Boolean = false
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
                    preferredQualityOverride = preferredQuality,
                    forceRefresh = attempt.forceRefresh,
                    requireDirect = attempt.requireDirect,
                    preferM4a = true,
                    shareInFlight = attempt.shareInFlight,
                    avoidDirect = avoidDirect
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

    // 解析 B 站音频直链
    private suspend fun resolveBili(
        song: SongItem,
        preferredQuality: String
    ): ResolvedDownloadSource? {
        val resolved = resolveBiliSong(song, AppContainer.biliClient) ?: return null
        val chosen: BiliAudioStreamInfo? = AppContainer.biliPlaybackRepository
            .getBestPlayableAudio(
                bvid = resolved.videoInfo.bvid,
                cid = resolved.cid,
                preferredKeyOverride = preferredQuality
            )
        val url = chosen?.url ?: return null
        val mime = chosen.mimeType
        val ext = mimeToExt(mime)
        return ResolvedDownloadSource(url = url, mimeType = mime, fileExtensionHint = ext)
    }

    private data class DownloadedLyrics(
        val lyricText: String? = null,
        val translatedText: String? = null,
        val romanizedText: String? = null
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
        attemptId: Long? = null,
        operationId: String = ""
    ): DownloadedPayloadSummary = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始 HLS 下载文件: ${destFile.name}, songId=$songId")

        val playlistText = executeTrackedCall(
            client = client,
            request = playlistRequest,
            songKey = songKey
        ) { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body.byteStream().use { input ->
                input.readBytesLimited(MAX_HLS_PLAYLIST_BYTES).toString(Charsets.UTF_8)
            }
        }
        ensureDownloadNotCancelled(songId, songKey, destFile, batchSessionId, attemptId)
        val segmentUrls = parseHlsSegmentUrls(playlistRequest.url.toString(), playlistText)
        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("HLS playlist contains no segments")
        }
        val playlistFingerprint = buildHlsPlaylistFingerprint(segmentUrls, playlistText)
        val mediaSequence = parseHlsMediaSequence(playlistText)
        val resolvedResumeState = resolveHlsResumeState(
            destFile = destFile,
            playlistFingerprint = playlistFingerprint,
            operationId = operationId
        )
            ?.takeIf { resumeState ->
                if (!destFile.exists()) {
                    false
                } else {
                    val actualFileLength = destFile.length().coerceAtLeast(0L)
                    val prefixDigest = runCatching {
                        sha256FilePrefix(destFile, resumeState.durableBytes)
                    }.getOrNull() ?: return@takeIf false
                    val compatible = isHlsResumeStateCompatible(
                        state = resumeState,
                        actualFileLength = actualFileLength,
                        actualPrefixSha256 = prefixDigest,
                        segmentCount = segmentUrls.size
                    )
                    compatible
                }
            }
        resolvedResumeState?.let { resumeState ->
            if (destFile.length().coerceAtLeast(0L) > resumeState.durableBytes) {
                withNetworkPolicyMutationPermit(
                    songKey = songKey,
                    stage = "hls_resume_truncate",
                    batchSessionId = batchSessionId,
                    attemptId = attemptId
                ) {
                    if (
                        destFile.exists() &&
                            destFile.length().coerceAtLeast(0L) > resumeState.durableBytes
                    ) {
                        truncateHlsWorkingFile(destFile, resumeState.durableBytes)
                    }
                }
            }
        }
        if (resolvedResumeState == null) {
            withNetworkPolicyMutationPermit(
                songKey = songKey,
                stage = "hls_resume_reset",
                batchSessionId = batchSessionId,
                attemptId = attemptId
            ) {
                if (hasHlsResumeState(destFile)) {
                    clearHlsResumeState(destFile)
                }
                if (resolveWorkingFileBytes(destFile) > 0L) {
                    deleteWorkingFile(destFile)
                }
            }
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
        val durablePrefixDigest = runCatching {
            sha256FilePrefixDigest(destFile, attemptStartBytes)
        }.getOrElse { error ->
            throw IOException("无法读取 HLS durable 前缀", error)
        }
        try {
            val output = withNetworkPolicyMutationPermit(
                songKey = songKey,
                stage = "hls_open_working_file",
                batchSessionId = batchSessionId,
                attemptId = attemptId
            ) {
                FileOutputStream(destFile, resumeSegmentIndex > 0)
            }
            output.use { output ->
                output.sink().buffer().use { sink ->
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

                        downloadedBytes += executeTrackedCall(
                            client = client,
                            request = segmentRequest,
                            songKey = songKey
                        ) { response ->
                            if (!response.isSuccessful) {
                                throw IllegalStateException("HTTP ${response.code}")
                            }
                            response.body.source().use { source ->
                                copyHlsSegment(
                                    source = source,
                                    sink = sink,
                                    trafficAccumulator = trafficAccumulator,
                                    prefixDigest = durablePrefixDigest,
                                    expectedRawBytes = response.body.contentLength()
                                        .takeIf { it >= 0L }
                                )
                            }
                        }
                        runCatching {
                            sink.flush()
                            output.fd.sync()
                        }.onFailure { flushError ->
                            NPLogger.e(
                                TAG,
                                "HLS 段刷盘失败，暂缓推进 checkpoint: ${destFile.name}, segment=$index",
                                flushError
                            )
                            throw flushError
                        }

                        val durableBytes = destFile.length().coerceAtLeast(0L)
                        if (durableBytes != downloadedBytes) {
                            throw IOException(
                                "HLS durable length differs from tracked bytes: " +
                                    "disk=$durableBytes, tracked=$downloadedBytes, file=${destFile.name}"
                            )
                        }
                        rememberHlsResumeState(
                            destFile = destFile,
                            playlistFingerprint = playlistFingerprint,
                            nextSegmentIndex = index + 1,
                            durableBytes = durableBytes,
                            durablePrefixSha256 = digestHexSnapshot(
                                digest = durablePrefixDigest,
                                file = destFile,
                                byteCount = durableBytes
                            ),
                            operationId = operationId,
                            mediaSequence = mediaSequence
                        )

                        val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0)
                            .coerceAtLeast(0.001)
                        val attemptTransferredBytes =
                            (downloadedBytes - attemptStartBytes).coerceAtLeast(0L)
                        publishProgress(
                            DownloadProgress(
                                songKey = songKey,
                                songId = songId,
                                fileName = resolveVisibleDownloadFileName(
                                    displayFileName,
                                    destFile.name
                                ),
                                bytesRead = downloadedBytes,
                                totalBytes = totalBytesHint,
                                speedBytesPerSec = (attemptTransferredBytes / elapsedSec).toLong(),
                                attemptId = attemptId
                            )
                        )
                    }
                    sink.flush()
                    output.fd.sync()
                }
            }
        } finally {
            trafficAccumulator.flush()
        }
        NPLogger.d(
            TAG,
            "HLS 下载完成: ${destFile.name}, 实际大小: $downloadedBytes bytes, segments=${segmentUrls.size}, songId=$songId"
        )
        return@withContext DownloadedPayloadSummary(
            actualBytes = downloadedBytes,
            // HLS 分片可能不带传输层 ID3 帧，来源提示只用于进度估算
            expectedBytes = null
        )
    }

    private fun parseHlsSegmentUrls(playlistUrl: String, playlistText: String): List<String> {
        val lines = playlistText.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        require(lines.any { it == "#EXTM3U" }) { "HLS playlist is missing EXTM3U header" }
        val unsupportedTag = lines.firstOrNull { line ->
            line.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                line.startsWith("#EXT-X-MAP", ignoreCase = true) ||
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) ||
                line.startsWith("#EXT-X-I-FRAMES-ONLY", ignoreCase = true)
        }
        require(unsupportedTag == null) { "Unsupported HLS tag: ${unsupportedTag?.substringBefore(':')}" }
        require(lines.none { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            "HLS master playlists are not supported"
        }
        return lines
            .filter { !it.startsWith('#') }
            .map { segment ->
                runCatching { java.net.URI(playlistUrl).resolve(segment).toString() }
                    .getOrElse { segment }
            }
    }

    private fun parseHlsMediaSequence(playlistText: String): Long? {
        return playlistText.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
    }

    internal fun copyHlsSegment(
        source: BufferedSource,
        sink: okio.BufferedSink,
        trafficAccumulator: TrafficByteAccumulator,
        prefixDigest: MessageDigest? = null,
        expectedRawBytes: Long? = null
    ): Long {
        val header = ByteArray(10)
        var headerBytes = 0
        var rawBytes = 0L
        while (headerBytes < header.size) {
            val read = source.read(header, headerBytes, header.size - headerBytes)
            if (read == -1) {
                break
            }
            headerBytes += read
            rawBytes += read
            trafficAccumulator.add(read.toLong())
        }
        require(rawBytes <= MAX_HLS_SEGMENT_BYTES) {
            "HLS segment exceeds limit: $rawBytes > $MAX_HLS_SEGMENT_BYTES"
        }

        var outputBytes = 0L
        val hasId3Header = headerBytes == header.size &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()
        if (!hasId3Header) {
            sink.write(header, 0, headerBytes)
            prefixDigest?.update(header, 0, headerBytes)
            outputBytes += headerBytes
        } else {
            val tagSize =
                ((header[6].toInt() and 0x7f) shl 21) or
                    ((header[7].toInt() and 0x7f) shl 14) or
                    ((header[8].toInt() and 0x7f) shl 7) or
                    (header[9].toInt() and 0x7f)
            val remainingTagBytes = tagSize.toLong()
            require(10L + remainingTagBytes <= MAX_HLS_SEGMENT_BYTES) {
                "HLS ID3 tag exceeds limit: ${10L + remainingTagBytes} > $MAX_HLS_SEGMENT_BYTES"
            }
            var skipped = 0L
            val skipBuffer = ByteArray(DOWNLOAD_READ_BUFFER_BYTES.toInt())
            while (skipped < remainingTagBytes) {
                val requested = minOf(
                    skipBuffer.size.toLong(),
                    remainingTagBytes - skipped
                ).toInt()
                val read = source.read(skipBuffer, 0, requested)
                if (read == -1) {
                    throw EOFException("HLS ID3 tag is truncated")
                }
                skipped += read
                rawBytes += read
                trafficAccumulator.add(read.toLong())
                require(rawBytes <= MAX_HLS_SEGMENT_BYTES) {
                    "HLS segment exceeds limit: $rawBytes > $MAX_HLS_SEGMENT_BYTES"
                }
            }
        }

        val buffer = ByteArray(DOWNLOAD_READ_BUFFER_BYTES.toInt())
        while (true) {
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            rawBytes += read.toLong()
            trafficAccumulator.add(read.toLong())
            require(rawBytes <= MAX_HLS_SEGMENT_BYTES) {
                "HLS segment exceeds limit: $rawBytes > $MAX_HLS_SEGMENT_BYTES"
            }
            sink.write(buffer, 0, read)
            prefixDigest?.update(buffer, 0, read)
            outputBytes += read.toLong()
        }
        expectedRawBytes?.let { expected ->
            if (rawBytes != expected) {
                throw IllegalStateException(
                    "HLS segment length mismatch: expected=$expected, actual=$rawBytes"
                )
            }
        }
        return outputBytes
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
        ensureDownloadNotCancelled(songId, songKey, destFile, batchSessionId, attemptId)
        if (YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(request) &&
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
        var resumedBytes = resolveWorkingFileBytes(destFile)
        val resumeFingerprint = ManagedDownloadStorage.readWorkingResumeFingerprint(destFile)
        if (resumedBytes > 0L && shouldDiscardWorkingFileForResume(request.url.toString(), resumeFingerprint)) {
            NPLogger.w(TAG, "续传来源链接已变化，丢弃旧临时文件: ${destFile.name}")
            withNetworkPolicyMutationPermit(
                songKey = songKey,
                stage = "direct_resume_reset",
                batchSessionId = batchSessionId,
                attemptId = attemptId
            ) {
                deleteWorkingFile(destFile)
            }
            resumedBytes = 0L
        } else if (resumedBytes > 0L && resolveResumeValidatorHeader(resumeFingerprint).isNullOrBlank()) {
            NPLogger.w(TAG, "续传缺少 If-Range 校验符，回退整文件重下: ${destFile.name}")
            resumedBytes = 0L
        }
        val resumeRangeHeader = buildResumeRangeHeader(resumedBytes)
        val effectiveRequest = if (resumeRangeHeader != null) {
            NPLogger.d(
                TAG,
                "恢复直链下载: ${destFile.name}, bytes=$resumedBytes, songId=$songId"
            )
            buildResumeRequest(
                request = request,
                completedBytes = resumedBytes,
                fingerprint = resumeFingerprint
            )
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
                val durableBytes = destFile.length().coerceAtLeast(0L)
                if (
                    isExactRangeEnd(responseHeaders, resumedBytes) &&
                    expectedBytes != null &&
                    durableBytes == expectedBytes
                ) {
                    return@executeTrackedCall DownloadedPayloadSummary(
                        actualBytes = resumedBytes,
                        expectedBytes = expectedBytes
                    )
                }
                throw IllegalStateException("HTTP ${resp.code}")
            }
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")

            val appending = resumedBytes > 0L && resp.code == 206
            val partialRange = if (resp.code == 206) {
                val range = runCatching {
                    validatePartialContentRange(
                        headers = responseHeaders,
                        expectedStart = if (resumedBytes > 0L) resumedBytes else 0L,
                        bodyLength = resp.body.contentLength()
                    )
                }.getOrElse { error ->
                    withNetworkPolicyMutationPermit(
                        songKey = songKey,
                        stage = "direct_invalid_range",
                        batchSessionId = batchSessionId,
                        attemptId = attemptId
                    ) {
                        deleteWorkingFile(destFile)
                    }
                    throw error
                }
                if (appending && !isResumeResponseCompatible(resumeFingerprint, responseHeaders, range.total)) {
                    withNetworkPolicyMutationPermit(
                        songKey = songKey,
                        stage = "direct_incompatible_resume",
                        batchSessionId = batchSessionId,
                        attemptId = attemptId
                    ) {
                        deleteWorkingFile(destFile)
                    }
                    throw IOException("续传响应校验符或总长度不匹配")
                }
                range
            } else {
                null
            }
            if (resumedBytes > 0L && !appending) {
                NPLogger.w(TAG, "服务端未接受续传，回退整文件重下: ${destFile.name}, code=${resp.code}")
            }
            val initialBytes = if (appending) resumedBytes else 0L
            if (!appending && resumedBytes > 0L) {
                withNetworkPolicyMutationPermit(
                    songKey = songKey,
                    stage = "direct_restart_without_range",
                    batchSessionId = batchSessionId,
                    attemptId = attemptId
                ) {
                    deleteWorkingFile(destFile)
                }
            }
            val total = resolveResponseExpectedBytes(
                requestUrl = request.url.toString(),
                headers = responseHeaders,
                bodyLength = resp.body.contentLength(),
                resumedBytes = initialBytes,
                isPartialResponse = appending
            ) ?: 0L
            NPLogger.d(
                TAG,
                "HTTP 音频响应长度: file=${destFile.name}, code=${resp.code}, " +
                    "contentLength=${resp.body.contentLength()}, " +
                    "contentRange=${responseHeaderValue(responseHeaders, "Content-Range")}, " +
                    "queryClen=${YouTubeGoogleVideoRangeSupport.resolveQueryContentLength(request.url.toString())}, " +
                    "resolvedTotal=$total"
            )
            NPLogger.d(TAG, "文件总大小: $total bytes, songId=$songId")
            val resumeMetadataWritten = withNetworkPolicyMutationPermit(
                songKey = songKey,
                stage = "direct_resume_metadata",
                batchSessionId = batchSessionId,
                attemptId = attemptId
            ) {
                updateWorkingResumeFingerprint(
                    destFile = destFile,
                    requestUrl = request.url.toString(),
                    headers = responseHeaders,
                    expectedContentLength = total.takeIf { it > 0L }
                )
            }
            val source = resp.body.source()
            var readSoFar = initialBytes
            val trafficAccumulator = newDownloadTrafficAccumulator()
            try {
                val output = withNetworkPolicyMutationPermit(
                    songKey = songKey,
                    stage = "direct_open_working_file",
                    batchSessionId = batchSessionId,
                    attemptId = attemptId
                ) {
                    FileOutputStream(destFile, appending)
                }
                output.use { output ->
                    output.sink().buffer().use { sink ->
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
                }
            } finally {
                trafficAccumulator.flush()
            }
            NPLogger.d(TAG, "文件下载完成: ${destFile.name}, 实际大小: $readSoFar bytes, songId=$songId")
            if (partialRange != null && readSoFar != partialRange.total) {
                throw IOException(
                    "Content-Range 总长度不匹配: expected=${partialRange.total}, actual=$readSoFar"
                )
            }
            if (!isTransferSizeComplete(total.takeIf { it > 0L }, readSoFar)) {
                throw IOException("下载文件不完整: ${destFile.name}, $readSoFar/$total")
            }
            DownloadedPayloadSummary(
                actualBytes = readSoFar,
                expectedBytes = total.takeIf { it > 0L },
                resumeMetadataAvailable = resumeMetadataWritten
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
        ensureDownloadNotCancelled(songId, songKey, destFile, batchSessionId, attemptId)

        var resumedBytes = resolveWorkingFileBytes(destFile)
        val resumeFingerprint = ManagedDownloadStorage.readWorkingResumeFingerprint(destFile)
        if (resumedBytes > 0L && shouldDiscardWorkingFileForResume(request.url.toString(), resumeFingerprint)) {
            NPLogger.w(TAG, "分块续传来源链接已变化，丢弃旧临时文件: ${destFile.name}")
            withNetworkPolicyMutationPermit(
                songKey = songKey,
                stage = "chunked_resume_reset",
                batchSessionId = batchSessionId,
                attemptId = attemptId
            ) {
                deleteWorkingFile(destFile)
            }
            resumedBytes = 0L
        } else if (resumedBytes > 0L && resolveResumeValidatorHeader(resumeFingerprint).isNullOrBlank()) {
            NPLogger.w(TAG, "分块续传缺少 If-Range 校验符，回退整文件重下: ${destFile.name}")
            resumedBytes = 0L
        }
        if (resumedBytes > 0L) {
            NPLogger.d(TAG, "恢复分块下载: ${destFile.name}, bytes=$resumedBytes, songId=$songId")
        }

        var downloadedBytes = resumedBytes
        var resumeMetadataAvailable = true
        val queryTotalHint = YouTubeGoogleVideoRangeSupport.resolveQueryContentLength(
            request.url.toString()
        ) ?: 0L
        var totalBytes = 0L
        var strictTotalBytes = false
        if (queryTotalHint > 0L) {
            NPLogger.d(
                TAG,
                "分块下载长度提示: file=${destFile.name}, queryClen=$queryTotalHint, " +
                    "仅用于进度显示"
            )
        }
        val output = withNetworkPolicyMutationPermit(
            songKey = songKey,
            stage = "chunked_open_working_file",
            batchSessionId = batchSessionId,
            attemptId = attemptId
        ) {
            FileOutputStream(destFile, resumedBytes > 0L)
        }
        output.use { output ->
            output.sink().buffer().use { sink ->
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
                            resumeFingerprint = resumeFingerprint,
                            sink = sink,
                            displayFileName = displayFileName,
                            songId = songId,
                            songKey = songKey,
                            destFile = destFile,
                            startNs = startNs,
                            attemptStartBytes = resumedBytes,
                            currentDownloadedBytes = downloadedBytes,
                            currentTotalBytes = totalBytes,
                            progressTotalBytesHint = queryTotalHint,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId
                        )
                    }
                    downloadedBytes = chunkResult.value.downloadedBytes
                    totalBytes = chunkResult.value.totalBytes
                    strictTotalBytes = strictTotalBytes || chunkResult.value.strictTotalBytes
                    resumeMetadataAvailable = resumeMetadataAvailable &&
                        chunkResult.value.resumeMetadataAvailable
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
                    val alreadyComplete = totalBytes > 0L && downloadedBytes == totalBytes
                    if (error.responseCode == 403 && alreadyComplete) {
                        // 403 = CDN 拒绝, 只有在总长度已满足时才接受为完成
                        break
                    }
                    throw error
                }
            }
                sink.flush()
            }
        }

        NPLogger.d(TAG, "分块下载完成: ${destFile.name}, 实际大小: $downloadedBytes bytes, songId=$songId")
        val expectedBytes = totalBytes.takeIf { it > 0L }
        if (strictTotalBytes && expectedBytes != null && downloadedBytes != expectedBytes) {
            throw IOException("分块 Content-Range 总长度不匹配: $downloadedBytes/$expectedBytes")
        }
        if (!strictTotalBytes && !isTransferSizeComplete(expectedBytes, downloadedBytes)) {
            throw IOException("分块下载不完整: ${destFile.name}, $downloadedBytes/$expectedBytes")
        }
        return@withContext DownloadedPayloadSummary(
            actualBytes = downloadedBytes,
            expectedBytes = expectedBytes,
            resumeMetadataAvailable = resumeMetadataAvailable
        )
    }

    private data class ChunkDownloadResult(
        val requestedChunkLength: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isEndOfStream: Boolean,
        val strictTotalBytes: Boolean,
        val resumeMetadataAvailable: Boolean = true
    )

    private fun downloadChunk(
        client: okhttp3.OkHttpClient,
        request: Request,
        start: Long,
        requestedChunkLength: Long,
        resumeFingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?,
        sink: okio.BufferedSink,
        displayFileName: String,
        songId: Long,
        songKey: String,
        destFile: File,
        startNs: Long,
        attemptStartBytes: Long,
        currentDownloadedBytes: Long,
        currentTotalBytes: Long,
        progressTotalBytesHint: Long,
        batchSessionId: Long? = null,
        attemptId: Long? = null
    ): ChunkDownloadResult {
        val effectiveResumeFingerprint = resolveLatestResumeFingerprint(
            fallback = resumeFingerprint,
            latest = ManagedDownloadStorage.readWorkingResumeFingerprint(destFile)
        )
        val chunkRequest = buildChunkResumeRequest(
            request = request,
            start = start,
            length = requestedChunkLength,
            fingerprint = effectiveResumeFingerprint
        )

        val trafficAccumulator = newDownloadTrafficAccumulator()
        try {
            return executeTrackedCall(
                client = client,
                request = chunkRequest,
                songKey = songKey
            ) { response ->
                val responseHeaders = response.headers.toMultimap()
                if (response.code == 416) {
                    sink.flush()
                    val total = parseUnsatisfiedContentRangeTotal(responseHeaders)
                    val durableBytes = destFile.length().coerceAtLeast(0L)
                    if (
                        total != null &&
                        (currentTotalBytes == 0L || currentTotalBytes == total) &&
                        start == total &&
                        currentDownloadedBytes == total &&
                        durableBytes == total
                    ) {
                        return@executeTrackedCall ChunkDownloadResult(
                            requestedChunkLength = requestedChunkLength,
                            downloadedBytes = currentDownloadedBytes,
                            totalBytes = total,
                            isEndOfStream = true,
                            strictTotalBytes = true
                        )
                    }
                    throw ChunkRequestIOException(response.code, "HTTP ${response.code}")
                }
                if (!response.isSuccessful) {
                    throw ChunkRequestIOException(response.code, "HTTP ${response.code}")
                }
                if (response.code != 206) {
                    throw ChunkRequestIOException(
                        response.code,
                        "Chunk request did not return partial content: HTTP ${response.code}"
                    )
                }

                val contentRange = runCatching {
                    validatePartialContentRange(
                        headers = responseHeaders,
                        expectedStart = start,
                        bodyLength = response.body.contentLength()
                    )
                }.getOrElse { error ->
                    withNetworkPolicyMutationPermit(
                        songKey = songKey,
                        stage = "chunked_invalid_range",
                        batchSessionId = batchSessionId,
                        attemptId = attemptId
                    ) {
                        deleteWorkingFile(destFile)
                    }
                    throw error
                }
                if (start > 0L && !isResumeResponseCompatible(
                        effectiveResumeFingerprint,
                        responseHeaders,
                        contentRange.total
                    )
                ) {
                    withNetworkPolicyMutationPermit(
                        songKey = songKey,
                        stage = "chunked_incompatible_resume",
                        batchSessionId = batchSessionId,
                        attemptId = attemptId
                    ) {
                        deleteWorkingFile(destFile)
                    }
                    throw IOException("分块响应校验符或总长度不匹配")
                }
                var downloadedBytes = currentDownloadedBytes
                val totalBytes = contentRange.total
                if (currentTotalBytes > 0L && currentTotalBytes != totalBytes) {
                    throw IOException(
                        "分块总长度不匹配: expected=$currentTotalBytes, actual=$totalBytes"
                    )
                }
                val resumeMetadataWritten = withNetworkPolicyMutationPermit(
                    songKey = songKey,
                    stage = "chunked_resume_metadata",
                    batchSessionId = batchSessionId,
                    attemptId = attemptId
                ) {
                    updateWorkingResumeFingerprint(
                        destFile = destFile,
                        requestUrl = request.url.toString(),
                        headers = responseHeaders,
                        expectedContentLength = totalBytes.takeIf { it > 0L }
                    )
                }

                val source: BufferedSource = response.body.source()
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
                            totalBytes = totalBytes.takeIf { it > 0L } ?: progressTotalBytesHint,
                            speedBytesPerSec = speed,
                            attemptId = attemptId
                        )
                    )
                }

                if (chunkRead != contentRange.length) {
                    throw IOException(
                        "分块响应长度不匹配: expected=${contentRange.length}, actual=$chunkRead"
                    )
                }

                val isEndOfStream = chunkRead < requestedChunkLength || (
                    totalBytes in 1..downloadedBytes
                )

                return ChunkDownloadResult(
                    requestedChunkLength = requestedChunkLength,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    isEndOfStream = isEndOfStream,
                    strictTotalBytes = true,
                    resumeMetadataAvailable = resumeMetadataWritten
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
        val shouldAbort = synchronized(networkPolicyMutationLock) {
            shouldAbortDownloadWork(
                allDownloadsCancelled = _isCancelled.value,
                batchSessionCurrent = isBatchSessionCurrent(batchSessionId),
                songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey),
                attemptAllowsWork = GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId)
            )
        }
        if (shouldAbort) {
            NPLogger.d(TAG, "下载被取消，停止分块下载: songId=$songId")
            deleteWorkingFileUnlessNetworkPolicyPaused(songKey, destFile)
            clearVisibleProgressForSong(songKey)
            throw java.util.concurrent.CancellationException("Download cancelled")
        }
    }
}

internal fun shouldAbortDownloadWork(
    allDownloadsCancelled: Boolean,
    batchSessionCurrent: Boolean,
    songCancelled: Boolean,
    networkPolicyPaused: Boolean,
    attemptAllowsWork: Boolean
): Boolean {
    return allDownloadsCancelled ||
        !batchSessionCurrent ||
        songCancelled ||
        networkPolicyPaused ||
        !attemptAllowsWork
}

/** 只有网络从未确认切换为已确认时才唤醒一次恢复流程 */
internal fun shouldTriggerNetworkRecovery(
    wasConfirmed: Boolean,
    isConfirmed: Boolean
): Boolean {
    return isConfirmed && !wasConfirmed
}

internal fun selectPermittedLocalPlaybackReference(
    rawLocalReference: String?,
    isManagedDownload: Boolean,
    verifiedManagedReference: String?
): String? {
    val rawReference = rawLocalReference?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (!isManagedDownload) {
        return rawReference
    }
    return verifiedManagedReference?.trim()?.takeIf(String::isNotBlank)
}

internal sealed interface LocalPlaybackReferenceResolution {
    data class Playable(val reference: String) : LocalPlaybackReferenceResolution

    data object NotIndexed : LocalPlaybackReferenceResolution

    data object Missing : LocalPlaybackReferenceResolution

    data class TemporarilyUnavailable(
        val evidence: ManagedDownloadReferenceLookup.Result
    ) : LocalPlaybackReferenceResolution
}

/**
 * 正式文件已经得到 provider Present 证据时可以立即播放
 * pending 和 staging 只代表写入中的候选引用, 仍需走完成凭据门禁
 */
internal fun shouldUseDirectPresentLocalPlayback(
    reference: String?,
    isManagedDownload: Boolean,
    evidence: ManagedDownloadReferenceLookup.Result,
    downloadCancelled: Boolean = false
): Boolean {
    if (downloadCancelled || evidence != ManagedDownloadReferenceLookup.Result.Present) {
        return false
    }
    if (!isManagedDownload) {
        return reference?.trim()?.isNotBlank() == true
    }
    return isFormalManagedAudioReference(reference)
}

/**
 * core 提交桥只跳过瞬时 Provider 查询, 不跳过取消和 staging 安全边界
 * pending 引用在桥接存在时可以播放, 因为登记前已经完成完整性校验
 */
internal fun shouldUseCompletedAudioReferenceDirectly(
    reference: String?,
    downloadCancelled: Boolean = false
): Boolean {
    val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    if (downloadCancelled || normalized.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)) {
        return false
    }
    val fileName = ManagedDownloadStorage.normalizeManagedAudioFileName(normalized)
        ?: return false
    return !(fileName.startsWith(DOWNLOAD_STAGING_FILE_PREFIX) &&
        fileName.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX))
}

internal fun shouldWaitForManagedPlaybackDirectoryMutation(
    isManagedDownload: Boolean,
    mutationActive: Boolean
): Boolean = isManagedDownload && mutationActive

internal fun shouldInvalidateCompletedAudioReferenceForRoot(
    referenceRootGeneration: Long,
    currentRootGeneration: Long
): Boolean = referenceRootGeneration != currentRootGeneration

/**
 * SAF 根切换后，完成桥中的旧 file URI 或其他 tree 不能再直接交给播放器
 * 这里只做 URI 字符串解析，不访问 DocumentsProvider，避免拖慢首播
 */
internal fun shouldDiscardCompletedReferenceForConfiguredSaf(
    reference: String?,
    configuredDirectoryUri: String?
): Boolean {
    val normalizedReference = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    val configured = configuredDirectoryUri?.trim()?.takeIf(String::isNotBlank) ?: return false
    if (normalizedReference.startsWith("/") ||
        normalizedReference.startsWith("file:", ignoreCase = true)
    ) {
        return true
    }
    if (!normalizedReference.startsWith("content:", ignoreCase = true)) {
        return false
    }
    val normalizedConfigured = ManagedDownloadDirectoryIdentity
        .normalizeConfiguredDirectoryUri(configured)
        ?: return false
    val configuredAuthority = ManagedDownloadDirectoryIdentity
        .extractDirectoryAuthority(normalizedConfigured)
        .takeIf(String::isNotBlank)
        ?: return false
    val referenceAuthority = ManagedDownloadDirectoryIdentity
        .extractDirectoryAuthority(normalizedReference)
    if (!referenceAuthority.equals(configuredAuthority, ignoreCase = true)) {
        return true
    }
    val configuredTreeId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(
        normalizedConfigured,
        "/tree/"
    ) ?: return false
    val referenceTreeId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(
        normalizedReference,
        "/tree/"
    )
    if (referenceTreeId != null) {
        return referenceTreeId != configuredTreeId
    }
    val referenceDocumentId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(
        normalizedReference,
        "/document/"
    ) ?: return false
    return referenceDocumentId != configuredTreeId &&
        !referenceDocumentId.startsWith("$configuredTreeId/")
}

/**
 * 判断受管下载引用是否仍属于当前配置的 SAF 根
 *
 * 迁移期间一个条目可能同时保留旧私有路径和新的 content URI。播放侧
 * 必须先过滤旧根引用，避免目录切换和播放器打开文件之间出现竞态
 */
internal fun isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
    reference: String?,
    configuredDirectoryUri: String?
): Boolean {
    return !shouldDiscardCompletedReferenceForConfiguredSaf(
        reference = reference,
        configuredDirectoryUri = configuredDirectoryUri
    )
}

/**
 * 从一个受管条目的多个别名中选出当前存储根可用的引用
 *
 * 旧快照可能把私有路径写在 mediaUri，而迁移后的 SAF 地址仍在 reference
 * 过滤后再选择可以保持热路径无 Provider I/O，并让调用方触发一次重绑定
 */
internal fun selectManagedPlaybackReferenceForConfiguredSaf(
    references: List<String>,
    configuredDirectoryUri: String?,
    allowPending: Boolean = false
): String? {
    return references.asSequence()
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .filter { reference ->
            reference.startsWith("/") ||
                reference.startsWith("file:", ignoreCase = true) ||
                reference.startsWith("content:", ignoreCase = true)
        }
        .filter { reference ->
            allowPending || !reference.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true)
        }
        .filter { reference ->
            isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = configuredDirectoryUri
            )
        }
        .distinct()
        .firstOrNull()
}

internal fun isFormalManagedAudioReference(reference: String?): Boolean {
    val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    if (
        normalized.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true) ||
        normalized.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)
    ) {
        return false
    }
    val fileName = ManagedDownloadStorage.normalizeManagedAudioFileName(normalized)
        ?: return false
    return !(fileName.startsWith(DOWNLOAD_STAGING_FILE_PREFIX) &&
        fileName.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX))
}

private val BLOCKED_MANAGED_PLAYBACK_ARTIFACT_STATES = setOf(
    "QUEUED",
    "DOWNLOADING",
    "VERIFYING",
    "COMMITTING",
    "PENDING",
    "STAGING",
    "REPLACING",
    "ACTIVE_REPLACEMENT",
    "REPLACEMENT_PENDING",
    "RENAME_PENDING",
    "MIGRATING",
    "MIGRATION_REPLACEMENT",
    "ACTIVE",
    "IN_PROGRESS",
    "WRITING",
    "COPYING",
    "PENDING_WRITE",
    "AUDIO_PENDING",
    "UNFINALIZED",
    "INCOMPLETE",
    "PARTIAL",
    "REPAIR_REQUIRED",
    "MISSING_CONFIRMED",
    "FAILED_RETRYABLE",
    "CANCELLED"
)

private val READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES = setOf(
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "DEGRADED_COMPLETE",
    "FINALIZED",
    "COMPLETE",
    "LEGACY_V15_FINALIZED",
    "LEGACY_UNVERIFIED"
)

/**
 * 判断已有音频是否可以凭直接可读证据播放
 *
 * 快照不完整或旧 metadata 缺失不是音频损坏证据，明确的临时状态才需要阻断
 * 非 pending 的旧状态只表示目录或元信息待修复；调用方已取得 Present
 * 证据时仍可播放，避免升级后的旧歌曲被误判为没有播放地址
 * allowLegacyPublishedAudio 只能由已取得 Present 证据的调用方传入
 */
internal fun isReadableManagedAudioPlaybackAllowed(
    audioIsPending: Boolean,
    downloadActive: Boolean,
    downloadCancelled: Boolean,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    allowLegacyPublishedAudio: Boolean = false
): Boolean {
    if (downloadCancelled) {
        return false
    }
    val artifactState = metadata?.artifactState
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.uppercase(Locale.ROOT)
    val legacyPublishedAllowed = !audioIsPending &&
        allowLegacyPublishedAudio &&
        artifactState != "MISSING_CONFIRMED" &&
        artifactState != "CANCELLED"
    if (
        artifactState in BLOCKED_MANAGED_PLAYBACK_ARTIFACT_STATES &&
        !legacyPublishedAllowed
    ) {
        return false
    }
    if (audioIsPending) {
        // saveAudioFromTemp 只会在完整写入并校验后返回 pending 条目。
        // 只有 core committed 等持久凭据才能让它跨进程安全播放。
        return artifactState in READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES ||
            metadata?.downloadFinalized == true
    }
    if (legacyPublishedAllowed) {
        // 正式文件名只会在完整写入后发布。调用方必须先取得 Present
        // 证据，因此旧版本缺少 metadata 或阶段字段不应阻断本地首播。
        return true
    }
    if (downloadActive && artifactState !in READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES) {
        return false
    }
    if (artifactState in READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES) {
        return true
    }
    if (metadata == null) {
        return true
    }
    if (artifactState == null) {
        // 旧版本可能只写入 downloadFinalized=false，而没有记录阶段。
        // 普通音频已通过 Present 证据确认，不应因此切到远端播放。
        return allowLegacyPublishedAudio || metadata.downloadFinalized != false
    }
    if (metadata.downloadFinalized == false) {
        return false
    }
    return true
}

internal fun selectPermittedLocalPlaybackResolution(
    rawLocalReference: String?,
    isManagedDownload: Boolean,
    verifiedManagedReference: String?,
    rawEvidence: ManagedDownloadReferenceLookup.Result,
    managedReferenceIsExplicitlyIncomplete: Boolean = false,
    missingIsTransient: Boolean = false
): LocalPlaybackReferenceResolution {
    val rawReference = rawLocalReference?.trim()?.takeIf(String::isNotBlank)
        ?: return LocalPlaybackReferenceResolution.TemporarilyUnavailable(
            ManagedDownloadReferenceLookup.Result.OutOfScope
        )
    val verifiedReference = verifiedManagedReference
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (
        isManagedDownload &&
        verifiedReference != null &&
        !managedReferenceIsExplicitlyIncomplete
    ) {
        return LocalPlaybackReferenceResolution.Playable(verifiedReference)
    }
    return when (rawEvidence) {
        ManagedDownloadReferenceLookup.Result.Present -> {
            if (isManagedDownload && managedReferenceIsExplicitlyIncomplete) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(rawEvidence)
            } else {
                LocalPlaybackReferenceResolution.Playable(rawReference)
            }
        }
        ManagedDownloadReferenceLookup.Result.Missing -> {
            if (missingIsTransient) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(rawEvidence)
            } else {
                LocalPlaybackReferenceResolution.Missing
            }
        }
        ManagedDownloadReferenceLookup.Result.OutOfScope,
        is ManagedDownloadReferenceLookup.Result.PermissionLost,
        is ManagedDownloadReferenceLookup.Result.ProviderFailure ->
            LocalPlaybackReferenceResolution.TemporarilyUnavailable(rawEvidence)
    }
}

internal fun selectIndexedLocalPlaybackResolution(
    verifiedReference: String?,
    indexedReference: String?,
    indexedEvidence: ManagedDownloadReferenceLookup.Result?,
    indexedReferenceIsExplicitlyIncomplete: Boolean = false,
    missingIsTransient: Boolean = false
): LocalPlaybackReferenceResolution {
    verifiedReference
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { return LocalPlaybackReferenceResolution.Playable(it) }
    if (indexedReference.isNullOrBlank()) {
        return LocalPlaybackReferenceResolution.NotIndexed
    }
    return when (indexedEvidence) {
        ManagedDownloadReferenceLookup.Result.Missing -> {
            if (missingIsTransient) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(indexedEvidence)
            } else {
                LocalPlaybackReferenceResolution.Missing
            }
        }
        ManagedDownloadReferenceLookup.Result.Present -> {
            if (indexedReferenceIsExplicitlyIncomplete) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(indexedEvidence)
            } else {
                LocalPlaybackReferenceResolution.Playable(indexedReference)
            }
        }
        ManagedDownloadReferenceLookup.Result.OutOfScope,
        is ManagedDownloadReferenceLookup.Result.PermissionLost,
        is ManagedDownloadReferenceLookup.Result.ProviderFailure,
        null -> LocalPlaybackReferenceResolution.TemporarilyUnavailable(
            indexedEvidence ?: ManagedDownloadReferenceLookup.Result.OutOfScope
        )
    }
}

internal fun findReboundFinalizedManagedAudio(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    indexedReference: String
): ManagedDownloadStorage.StoredEntry? {
    if (!snapshot.rootEntriesComplete) return null
    val indexedFileName = ManagedDownloadStorage.normalizeManagedAudioFileName(indexedReference)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val reboundAudio = snapshot.audioEntries
        .asSequence()
        .filter { entry -> entry.name == indexedFileName }
        .take(2)
        .toList()
        .singleOrNull()
        ?: return null
    return reboundAudio.takeIf { audio ->
        canExposeManagedDownloadForPlayback(snapshot, audio)
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

internal fun canExposeManagedDownloadForPlayback(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
    audio: ManagedDownloadStorage.StoredEntry?
): Boolean {
    return isFinalizedDownloadedAudioEntry(
        rootEntriesComplete = snapshot?.rootEntriesComplete == true,
        isPendingAudioWrite = audio?.isPendingAudioWrite == true,
        metadata = audio?.let { entry ->
            ManagedDownloadStorage.metadataForAudioEntry(snapshot, entry)
        }
    )
}

internal fun coreCommittedSeedMetadataJson(rawMetadata: String): String? {
    return runCatching {
        JSONObject(rawMetadata)
            .put("downloadFinalized", false)
            .put("artifactState", "CORE_COMMITTED")
            .toString()
    }.getOrNull()
}
