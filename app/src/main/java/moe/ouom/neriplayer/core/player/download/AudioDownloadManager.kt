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
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadCoreCommitPhase
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.clearSongCancelled
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.boundManagedDownloadFileName
import moe.ouom.neriplayer.core.download.resource.DownloadTransferPermitRegistry
import moe.ouom.neriplayer.core.download.resource.DownloadTransferWatchdog
import moe.ouom.neriplayer.core.download.resource.DownloadStorageSpaceDeferredException
import moe.ouom.neriplayer.core.download.resource.classifyDownloadStorageSpaceFailure
import moe.ouom.neriplayer.core.download.resource.isDefinitive
import moe.ouom.neriplayer.core.download.observability.DownloadStartupTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTraceToken
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.DownloadTransferAdmissionDeferredException
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.isPostCoreDownloadOperationState
import moe.ouom.neriplayer.core.download.policy.shouldUseIndexedSidecarLookup
import moe.ouom.neriplayer.core.download.shouldRollbackCancelledAudio
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.metadata.MAX_SOURCE_COVER_BYTES
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.isTrustedYouTubeHost
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import moe.ouom.neriplayer.data.settings.resolveDownloadAudioQualitySelection
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.TrafficUsageSource
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType
import moe.ouom.neriplayer.data.traffic.downloadNetworkTypeOrNull
import moe.ouom.neriplayer.data.traffic.hasConfirmedInternetAccess
import okhttp3.Dispatcher
import okhttp3.Request
import okio.BufferedSource
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 音频下载管理器: 解析来源 (网易云 / Bilibili) 并保存到本地目录
 * - 不依赖系统 DownloadManager, 直接用共享 OkHttpClient, 实现自定义 Header 与代理
 * - 默认保存路径: /Android/data/<package>/files/Music/NeriPlayer/<Artist - Title>.<ext>
 * - 支持通过 SAF 将下载目录切换到自定义文件夹
 */
object AudioDownloadManager {

    private const val TAG = "NERI-Downloader"
    private const val DOWNLOAD_NETWORK_POLICY_PREFS = "download_network_policy_state"
    private const val NETWORK_GENERATION_PREF = "network_generation"
    private val SHA256_HEX_REGEX = Regex("[0-9a-fA-F]{64}")
    private const val BILI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val BILI_REFERER = "https://www.bilibili.com"
    internal const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = DEFAULT_DOWNLOAD_PARALLELISM
    internal const val MAX_CONCURRENT_DOWNLOADS_LIMIT = MAX_DOWNLOAD_PARALLELISM
    internal const val SOURCE_RESOLVE_PARALLELISM = 10
    internal const val CORE_COMMIT_PARALLELISM = 2
    private const val BATCH_COMPLETION_CALLBACK_PARALLELISM = 2
    private const val DURABLE_CHECKPOINT_INTERVAL_BYTES = 1L * 1024L * 1024L
    private const val DURABLE_CHECKPOINT_INTERVAL_NS = 500_000_000L
    private const val PROGRESS_EVENT_BUFFER_CAPACITY = 64
    private const val DOWNLOAD_TRAFFIC_FLUSH_BYTES = 512L * 1024L
    private const val TRANSIENT_DOWNLOAD_MAX_ATTEMPTS = 6
    private const val TRANSIENT_DOWNLOAD_OFFLINE_RECOVERY_WAIT_MS = 12_000L
    private const val TRANSIENT_DOWNLOAD_NETWORK_SETTLE_MS = 750L
    private const val DOWNLOAD_RETRY_POLL_SLICE_MS = 250L
    private const val STORAGE_SPACE_CONTENTION_RETRY_DELAY_MS = 750L
    private const val DOWNLOAD_CLIENT_MAX_REQUESTS = 24
    private const val RECOVERY_OPPORTUNITY_COOLDOWN_MS = 2_500L
    private const val DOWNLOAD_CLIENT_MAX_REQUESTS_PER_HOST = 12
    private const val DOWNLOAD_CLIENT_CONNECT_TIMEOUT_MS = 20_000L
    private const val DOWNLOAD_CLIENT_READ_TIMEOUT_MS = 45_000L
    private const val DOWNLOAD_CLIENT_WRITE_TIMEOUT_MS = 45_000L
    private const val COVER_DOWNLOAD_MAX_ATTEMPTS = 3
    private const val COVER_DOWNLOAD_RETRY_DELAY_MS = 250L
    /** core 提交和目录索引发布之间允许播放入口复用已校验引用的最长时间 */
    private const val COMPLETED_AUDIO_REFERENCE_RETENTION_MS = 2 * 60 * 1_000L
    private const val COMPLETED_AUDIO_REFERENCE_MAX_ENTRIES = 512
    private const val DOWNLOAD_READ_BUFFER_BYTES = 64L * 1024L
    private const val YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES = 4L * 1024L * 1024L
    private const val MAX_HLS_PLAYLIST_BYTES = 1L * 1024L * 1024L
    private const val MAX_HLS_SEGMENT_BYTES = 64L * 1024L * 1024L
    internal const val MAX_COVER_RESPONSE_BYTES = MAX_SOURCE_COVER_BYTES

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

    private val progressStore = AudioDownloadProgressStore(PROGRESS_EVENT_BUFFER_CAPACITY)
    val progressFlow: StateFlow<DownloadProgress?> = progressStore.progressFlow
    val progressEvents: SharedFlow<DownloadProgress> = progressStore.progressEvents
    val batchProgressFlow: StateFlow<BatchDownloadProgress?> = progressStore.batchProgressFlow

    // 取消下载控制
    private val _isCancelled = MutableStateFlow(false)
    val isCancelledFlow: StateFlow<Boolean> = _isCancelled
    private val sourceResolveSemaphore = Semaphore(SOURCE_RESOLVE_PARALLELISM)
    private val coreCommitSemaphore = Semaphore(CORE_COMMIT_PARALLELISM)
    private val _activeNetworkTransfers = MutableStateFlow(0)
    internal val activeNetworkTransfers: StateFlow<Int> = _activeNetworkTransfers.asStateFlow()
    private val transferPermitRegistry = DownloadTransferPermitRegistry(
        maxParallelism = MAX_CONCURRENT_DOWNLOADS_LIMIT,
        onSnapshotChanged = { snapshot ->
            _activeNetworkTransfers.value = snapshot.activeTransferCount
        }
    )
    private val transferWatchdog = DownloadTransferWatchdog(transferPermitRegistry)

    /** 暴露传输槽位快照，供恢复诊断区分排队、真实 I/O 和无进展任务 */
    internal fun transferPermitSnapshot(): DownloadTransferPermitRegistry.Snapshot {
        return transferPermitRegistry.snapshot()
    }

    /** 保留节流或事件缓冲丢弃前的最新值，供恢复绑定时补偿 */
    internal val latestProgressByOperation: StateFlow<Map<String, DownloadProgress>> =
        progressStore.latestProgressByOperation
    /** 增量进度事件供批量和全局投影消费，避免反复遍历全量快照 */
    internal val latestProgressEvents: SharedFlow<DownloadProgress> =
        progressStore.latestProgressEvents
    private val completedAudioReferenceRegistry = AudioDownloadReferenceRegistry(
        retentionMs = COMPLETED_AUDIO_REFERENCE_RETENTION_MS,
        maxEntries = COMPLETED_AUDIO_REFERENCE_MAX_ENTRIES
    )
    private val referenceOwnership = AudioDownloadReferenceOwnership()
    private val managedPlaybackRebindAtMsBySongKey = ConcurrentHashMap<String, Long>()
    private val coverCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioDownloadCoverCoordinator(
            maxAttempts = COVER_DOWNLOAD_MAX_ATTEMPTS,
            retryDelayMs = COVER_DOWNLOAD_RETRY_DELAY_MS,
            maxResponseBytes = MAX_COVER_RESPONSE_BYTES,
            ensureNotCancelled = { key, stage, session, attempt, active, operationId ->
                ensureSongDownloadNotCancelled(
                    songKey = key,
                    stage = stage,
                    batchSessionId = session,
                    attemptId = attempt,
                    operationId = operationId,
                    requireActiveAttempt = active
                )
            },
            executeTrackedCall = { request, songKey, operationId, active, block ->
                executeTrackedCall(
                    client = backgroundDownloadClient,
                    request = request,
                    songKey = songKey,
                    operationId = operationId,
                    requireActiveAttempt = active,
                    block = block
                )
            },
            commitCover = { coverContext, bytes, fileName, mimeType,
                            key, session, attempt, active, operation ->
                withNetworkPolicyMutationPermit(
                    songKey = key,
                    stage = "cover_commit",
                    batchSessionId = session,
                    attemptId = attempt,
                    operationId = operation,
                    requireActiveAttempt = active
                ) {
                    ManagedDownloadStorage.commitCoverBytes(
                        context = coverContext,
                        bytes = bytes,
                        fileName = fileName,
                        mimeType = mimeType
                    )?.reference
                }
            },
            rememberPartial = { key, partialOperationId, references ->
                rememberPartialSidecarReferences(
                    songKey = key,
                    sidecarReferences = references,
                    operationId = partialOperationId
                )
            }
        )
    }
    private val hlsResumeStore = AudioHlsResumeStore(
        checkpointFileFor = ManagedDownloadStorage::buildWorkingHlsCheckpointFile,
        readBufferBytes = DOWNLOAD_READ_BUFFER_BYTES.toInt()
    )
    private val hlsTransfer = AudioDownloadHlsTransfer(
        hooks = object : AudioDownloadHlsTransfer.Hooks {
            override fun markTransferNetworkActivity(
                operationId: String?,
                attemptId: Long?,
                songKey: String,
                transferGeneration: Long?
            ) {
                this@AudioDownloadManager.markTransferNetworkActivity(
                    operationId = operationId,
                    attemptId = attemptId,
                    songKey = songKey,
                    transferGeneration = transferGeneration
                )
            }

            override fun <T> executeTrackedCall(
                client: okhttp3.OkHttpClient,
                request: Request,
                songKey: String,
                operationId: String?,
                block: (okhttp3.Response) -> T
            ): T = this@AudioDownloadManager.executeTrackedCall(
                client = client,
                request = request,
                songKey = songKey,
                operationId = operationId,
                block = block
            )

            override fun ensureDownloadNotCancelled(
                songId: Long,
                songKey: String,
                destFile: File,
                batchSessionId: Long?,
                attemptId: Long?,
                operationId: String?
            ) {
                this@AudioDownloadManager.ensureDownloadNotCancelled(
                    songId = songId,
                    songKey = songKey,
                    destFile = destFile,
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    operationId = operationId
                )
            }

            override fun <T> withWorkingFileMutation(
                songKey: String,
                stage: String,
                batchSessionId: Long?,
                attemptId: Long?,
                operationId: String?,
                block: () -> T
            ): T = this@AudioDownloadManager.withNetworkPolicyMutationPermit(
                songKey = songKey,
                stage = stage,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId,
                block = block
            )

            override fun resolveHlsResumeState(
                destFile: File,
                playlistFingerprint: String,
                operationId: String
            ): HlsResumeState? = this@AudioDownloadManager.resolveHlsResumeState(
                destFile = destFile,
                playlistFingerprint = playlistFingerprint,
                operationId = operationId
            )

            override fun isHlsResumeStateCompatible(
                state: HlsResumeState,
                actualFileLength: Long,
                actualPrefixSha256: String,
                segmentCount: Int
            ): Boolean = this@AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = actualFileLength,
                actualPrefixSha256 = actualPrefixSha256,
                segmentCount = segmentCount
            )

            override fun sha256FilePrefix(file: File, byteCount: Long): String =
                this@AudioDownloadManager.sha256FilePrefix(file, byteCount)

            override fun sha256FilePrefixDigest(file: File, byteCount: Long): MessageDigest =
                this@AudioDownloadManager.sha256FilePrefixDigest(file, byteCount)

            override fun digestHexSnapshot(
                digest: MessageDigest,
                file: File,
                byteCount: Long
            ): String = this@AudioDownloadManager.digestHexSnapshot(
                digest = digest,
                file = file,
                byteCount = byteCount
            )

            override fun hasHlsResumeState(file: File?): Boolean =
                this@AudioDownloadManager.hasHlsResumeState(file)

            override fun clearHlsResumeState(file: File?) {
                this@AudioDownloadManager.clearHlsResumeState(file)
            }

            override fun resolveWorkingFileBytes(file: File?): Long =
                this@AudioDownloadManager.resolveWorkingFileBytes(file)

            override fun truncateWorkingFile(file: File, byteCount: Long) {
                this@AudioDownloadManager.truncateWorkingFile(file, byteCount)
            }

            override fun deleteWorkingFile(file: File?) {
                this@AudioDownloadManager.deleteWorkingFile(file)
            }

            override fun storageSpaceOwnerKey(
                operationId: String?,
                attemptId: Long?,
                songKey: String,
                file: File
            ): String = this@AudioDownloadManager.storageSpaceOwnerKey(
                operationId = operationId,
                attemptId = attemptId,
                songKey = songKey,
                file = file
            )

            override fun newTrafficAccumulator(): TrafficByteAccumulator =
                this@AudioDownloadManager.newDownloadTrafficAccumulator()

            override fun rememberHlsResumeState(
                destFile: File,
                playlistFingerprint: String,
                nextSegmentIndex: Int,
                durableBytes: Long,
                durablePrefixSha256: String,
                operationId: String,
                mediaSequence: Long?
            ) {
                this@AudioDownloadManager.rememberHlsResumeState(
                    destFile = destFile,
                    playlistFingerprint = playlistFingerprint,
                    nextSegmentIndex = nextSegmentIndex,
                    durableBytes = durableBytes,
                    durablePrefixSha256 = durablePrefixSha256,
                    operationId = operationId,
                    mediaSequence = mediaSequence
                )
            }

            override fun publishProgress(progress: DownloadProgress) {
                this@AudioDownloadManager.publishProgress(progress)
            }

            override fun resolveVisibleDownloadFileName(
                requestedName: String,
                actualName: String
            ): String = moe.ouom.neriplayer.core.player.download
                .resolveVisibleDownloadFileName(requestedName, actualName)
        },
        maxPlaylistBytes = MAX_HLS_PLAYLIST_BYTES,
        maxSegmentBytes = MAX_HLS_SEGMENT_BYTES,
        readBufferBytes = DOWNLOAD_READ_BUFFER_BYTES.toInt()
    )
    private val fileTransfer = AudioDownloadFileTransfer(
        hooks = object : AudioDownloadFileTransfer.Hooks {
            override fun ensureDownloadNotCancelled(
                songId: Long,
                songKey: String,
                destFile: File,
                batchSessionId: Long?,
                attemptId: Long?,
                operationId: String?
            ) {
                this@AudioDownloadManager.ensureDownloadNotCancelled(
                    songId = songId,
                    songKey = songKey,
                    destFile = destFile,
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    operationId = operationId
                )
            }

            override fun <T> withWorkingFileMutation(
                songKey: String,
                stage: String,
                batchSessionId: Long?,
                attemptId: Long?,
                operationId: String?,
                block: () -> T
            ): T = this@AudioDownloadManager.withNetworkPolicyMutationPermit(
                songKey = songKey,
                stage = stage,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId,
                block = block
            )

            override fun deleteWorkingFile(file: File?) {
                this@AudioDownloadManager.deleteWorkingFile(file)
            }

            override fun storageSpaceOwnerKey(
                operationId: String?,
                attemptId: Long?,
                songKey: String,
                file: File
            ): String = this@AudioDownloadManager.storageSpaceOwnerKey(
                operationId = operationId,
                attemptId = attemptId,
                songKey = songKey,
                file = file
            )

            override fun <T> executeTrackedCall(
                client: okhttp3.OkHttpClient,
                request: Request,
                songKey: String,
                operationId: String?,
                block: (okhttp3.Response) -> T
            ): T = this@AudioDownloadManager.executeTrackedCall(
                client = client,
                request = request,
                songKey = songKey,
                operationId = operationId,
                block = block
            )

            override fun newTrafficAccumulator(): TrafficByteAccumulator =
                this@AudioDownloadManager.newDownloadTrafficAccumulator()

            override fun markTransferNetworkActivity(
                operationId: String?,
                attemptId: Long?,
                songKey: String,
                transferGeneration: Long?
            ) {
                this@AudioDownloadManager.markTransferNetworkActivity(
                    operationId = operationId,
                    attemptId = attemptId,
                    songKey = songKey,
                    transferGeneration = transferGeneration
                )
            }

            override fun publishProgress(progress: DownloadProgress) {
                this@AudioDownloadManager.publishProgress(progress)
            }

            override fun resolveVisibleDownloadFileName(
                requestedName: String,
                actualName: String
            ): String = moe.ouom.neriplayer.core.player.download
                .resolveVisibleDownloadFileName(requestedName, actualName)
        },
        readBufferBytes = DOWNLOAD_READ_BUFFER_BYTES,
        preferredChunkSizeBytes = YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES
    )
    private val playbackCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioDownloadPlaybackCoordinator(
            completedAudioReferenceRegistry = completedAudioReferenceRegistry,
            isSongDownloadActive = ::isSongDownloadActive,
            directoryMutationWaitMs = 1_200L,
            tag = TAG
        )
    }
    private val batchCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioDownloadBatchCoordinator(
            latestProgressByOperation = latestProgressByOperation,
            latestProgressEvents = latestProgressEvents,
            maxCompletionCallbacks = BATCH_COMPLETION_CALLBACK_PARALLELISM,
            hooks = object : AudioDownloadBatchCoordinator.Hooks {
                override fun startBatchSession(): Long =
                    this@AudioDownloadManager.startBatchSession()

                override fun isBatchSessionCurrent(batchSessionId: Long?): Boolean =
                    this@AudioDownloadManager.isBatchSessionCurrent(batchSessionId)

                override fun finishBatchSession(batchSessionId: Long) {
                    this@AudioDownloadManager.finishBatchSession(batchSessionId)
                }

                override fun updateBatchProgressForSession(
                    batchSessionId: Long,
                    progress: BatchDownloadProgress?
                ) {
                    this@AudioDownloadManager.updateBatchProgressForSession(
                        batchSessionId,
                        progress
                    )
                }

                override fun isAllDownloadsCancelled(): Boolean = _isCancelled.value

                override fun resetCancelFlag() {
                    _isCancelled.value = false
                }

                override fun resolveWorkerCount(
                    songCount: Int,
                    requestedParallelism: Int
                ): Int = this@AudioDownloadManager.resolveBatchDownloadWorkerCount(
                    songCount = songCount,
                    requestedParallelism = requestedParallelism
                )

                override fun shouldPreserveArtifactsForNetworkPolicy(
                    songKey: String
                ): Boolean = this@AudioDownloadManager
                    .shouldPreserveArtifactsForNetworkPolicy(songKey)

                override suspend fun downloadSong(
                    context: Context,
                    song: SongItem,
                    batchSessionId: Long,
                    attemptId: Long?
                ) {
                    this@AudioDownloadManager.downloadSong(
                        context = context,
                        song = song,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId
                    )
                }
            },
            tag = TAG
        )
    }
    private val retryWakeSignalVersion = MutableStateFlow(0L)
    /** operation 注册表同时保护生命周期、暂停标记和活动网络调用 */
    private val operationRegistry = AudioDownloadOperationRegistry(referenceOwnership)
    private val networkRecoveryMonitorLock = Any()
    private var lastConfirmedInternetAccess = false

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
    private var networkRecoveryMonitorRegistered = false

    private val downloadNetworkPolicyTracker = DownloadNetworkPolicyTracker()

    fun isSongDownloadActive(songKey: String): Boolean {
        return operationRegistry.isSongDownloadActive(songKey)
    }

    internal fun isOperationDownloadActive(operationId: String): Boolean {
        return operationRegistry.isOperationDownloadActive(operationId)
    }

    internal fun currentDownloadNetworkGeneration(): Long =
        downloadNetworkPolicyTracker.currentGeneration()

    private fun persistDownloadNetworkGeneration(context: Context, generation: Long) {
        val persisted = context.applicationContext
            .getSharedPreferences(DOWNLOAD_NETWORK_POLICY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(NETWORK_GENERATION_PREF, generation.coerceAtLeast(0L))
            .commit()
        if (!persisted) {
            NPLogger.w(TAG, "持久化下载网络代次失败: generation=$generation")
        }
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
            val persistedNetworkGeneration = appContext
                .getSharedPreferences(DOWNLOAD_NETWORK_POLICY_PREFS, Context.MODE_PRIVATE)
                .getLong(NETWORK_GENERATION_PREF, 0L)
            downloadNetworkPolicyTracker.seed(
                networkKey = initialNetwork,
                networkType = initialNetworkType,
                initialGeneration = persistedNetworkGeneration
            )
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
        if (observation.changed) {
            persistDownloadNetworkGeneration(context, observation.generation)
        }
        if (observation.becameWifi) {
            GlobalDownloadManager.onWifiBoundDownloadNetworkRestored(
                context = context,
                reason = reason,
                networkGeneration = observation.generation
            )
            GlobalDownloadManager.scheduleWifiRecoveryProbe(
                context = context,
                reason = reason
            )
        }
        if (observation.shouldPause) {
            interruptDownloadsForWifiLoss(
                networkType = networkType,
                reason = reason,
                networkGeneration = observation.generation
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
        val networkGeneration = downloadNetworkPolicyTracker.currentGeneration()
        persistDownloadNetworkGeneration(
            context = context,
            generation = networkGeneration
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
            reason = "network_lost",
            networkGeneration = networkGeneration
        )
    }

    private fun interruptDownloadsForWifiLoss(
        networkType: TrafficNetworkType?,
        reason: String,
        networkGeneration: Long? = null
    ) {
        if (networkType != TrafficNetworkType.WIFI) {
            NPLogger.w(
                TAG,
                "WIFI 下载环境已切换，准备中断下载: reason=$reason, " +
                    "nextType=${networkType ?: "UNKNOWN"}"
            )
            GlobalDownloadManager.interruptDownloadsForWifiDisconnected(
                callbackNetworkType = networkType,
                networkGeneration = networkGeneration
            )
        }
    }

    internal data class ResolvedDownloadSource(
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
        WAITING_HOST,
        WAITING_DELETE_CLEANUP,
        RESOLVING_SOURCE,
        PREPARING_STORAGE,
        TRANSFERRING,
        VERIFYING_AUDIO,
        COMMITTING_CORE,
        ASSETS_ENRICHING,
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
        val attemptId: Long? = null,
        val operationId: String? = null,
        /** 当前传输 permit 的代次，拒绝旧 attempt 的迟到进度回调 */
        val transferGeneration: Long? = null,
        /** 已完成 flush 和 fsync 的前缀，只有这部分可以写入恢复检查点 */
        val durableBytesRead: Long? = null
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
        val operationId: String? = null,
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
    ): Boolean = AudioDownloadProgressPolicy.shouldPublishAudioDownloadProgress(
        previous,
        progress,
        nowNs,
        force
    )

    internal fun shouldReplaceLatestProgress(
        previous: DownloadProgress?,
        incoming: DownloadProgress
    ): Boolean = AudioDownloadProgressPolicy.shouldReplaceLatestProgress(previous, incoming)

    internal fun mergeLatestProgress(
        previous: DownloadProgress?,
        incoming: DownloadProgress
    ): DownloadProgress = AudioDownloadProgressPolicy.mergeLatestProgress(previous, incoming)


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

    internal enum class DownloadedSidecarStage {
        COVER,
        LYRICS
    }

    internal data class DownloadedPayloadSummary(
        val actualBytes: Long,
        val expectedBytes: Long?,
        val resumeMetadataAvailable: Boolean = true
    )

    private class DownloadCoreCommitTracker(
        var phase: DownloadCoreCommitPhase = DownloadCoreCommitPhase.STAGING
    )

    /** 保存一次 operation 的可恢复状态，避免把大量局部变量塞进单个状态机 */
    private class DownloadExecutionAttemptState(
        var tempFile: File? = null,
        var storedAudio: ManagedDownloadStorage.StoredEntry? = null,
        val coreCommitTracker: DownloadCoreCommitTracker = DownloadCoreCommitTracker(),
        var cancellationCleanupAttempted: Boolean = false,
        var attemptNumber: Int = 1,
        var activeTransportKind: DownloadTransportKind? = null,
        var activeWorkingFileName: String? = null,
        var resumeMetadataAvailable: Boolean = true,
        var forceRefreshYouTubeSource: Boolean = false,
        var avoidYouTubeDirectSource: Boolean = false
    )

    private data class PreparedDownloadAttempt(
        val resolved: ResolvedDownloadSource,
        val workingSong: SongItem,
        val request: Request,
        val transportKind: DownloadTransportKind,
        val fileName: String,
        val mimeType: String?,
        val workingFile: File
    )

    private enum class DownloadAttemptFailureAction {
        RETRY
    }

    private data class CoreCommittedAudio(
        val audio: ManagedDownloadStorage.StoredEntry,
        val transferredBytes: Long,
        val operationCoreCommitted: Boolean,
        val transferOwnerToken: Long? = null
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

    internal fun buildCoverDownloadCandidateUrls(song: SongItem): List<String> =
        AudioDownloadTransferPolicy.buildCoverDownloadCandidateUrls(song)

    internal fun isTransferSizeComplete(expectedBytes: Long?, actualBytes: Long): Boolean =
        AudioDownloadTransferPolicy.isTransferSizeComplete(expectedBytes, actualBytes)

    internal fun resolveAudioCommitExpectedSize(
        transferExpectedBytes: Long?,
        bytesBeforeMetadata: Long,
        bytesAtCommit: Long
    ): Long? = AudioDownloadTransferPolicy.resolveAudioCommitExpectedSize(
        transferExpectedBytes,
        bytesBeforeMetadata,
        bytesAtCommit
    )

    internal fun resolveDownloadTransportKind(
        streamType: YouTubePlayableStreamType,
        request: Request
    ): DownloadTransportKind = AudioDownloadTransferPolicy.resolveDownloadTransportKind(
        streamType,
        request
    )

    internal fun buildResumeRangeHeader(completedBytes: Long): String? =
        AudioDownloadTransferPolicy.buildResumeRangeHeader(completedBytes)

    internal fun resolveResumeValidatorHeader(
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): String? = AudioDownloadTransferPolicy.resolveResumeValidatorHeader(fingerprint)

    internal fun shouldDiscardWorkingFileForResume(
        requestUrl: String,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Boolean = AudioDownloadTransferPolicy.shouldDiscardWorkingFileForResume(
        requestUrl,
        fingerprint
    )

    internal fun buildResumeRequest(
        request: Request,
        completedBytes: Long,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Request = AudioDownloadTransferPolicy.buildResumeRequest(
        request,
        completedBytes,
        fingerprint
    )

    internal fun buildChunkResumeRequest(
        request: Request,
        start: Long,
        length: Long,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Request = AudioDownloadTransferPolicy.buildChunkResumeRequest(
        request,
        start,
        length,
        fingerprint
    )

    internal fun resolveLatestResumeFingerprint(
        fallback: ManagedDownloadStorage.WorkingResumeFingerprint?,
        latest: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): ManagedDownloadStorage.WorkingResumeFingerprint? =
        AudioDownloadTransferPolicy.resolveLatestResumeFingerprint(fallback, latest)

    internal fun parseContentRange(
        headers: Map<String, List<String>>
    ): ParsedContentRange? = AudioDownloadTransferPolicy.parseContentRange(headers)

    internal fun parseUnsatisfiedContentRangeTotal(
        headers: Map<String, List<String>>
    ): Long? = AudioDownloadTransferPolicy.parseUnsatisfiedContentRangeTotal(headers)

    internal fun isExactRangeEnd(
        headers: Map<String, List<String>>,
        resumedBytes: Long
    ): Boolean = AudioDownloadTransferPolicy.isExactRangeEnd(headers, resumedBytes)

    internal fun validatePartialContentRange(
        headers: Map<String, List<String>>,
        expectedStart: Long,
        bodyLength: Long? = null
    ): ParsedContentRange = AudioDownloadTransferPolicy.validatePartialContentRange(
        headers,
        expectedStart,
        bodyLength
    )

    internal fun isResumeResponseCompatible(
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?,
        headers: Map<String, List<String>>,
        totalBytes: Long
    ): Boolean = AudioDownloadTransferPolicy.isResumeResponseCompatible(
        fingerprint,
        headers,
        totalBytes
    )

    private fun responseHeaderValue(
        headers: Map<String, List<String>>,
        name: String
    ): String? = AudioDownloadTransferPolicy.responseHeaderValue(headers, name)

    private fun updateWorkingResumeFingerprint(
        destFile: File,
        requestUrl: String,
        headers: Map<String, List<String>>,
        expectedContentLength: Long?
    ): Boolean = AudioDownloadTransferPolicy.updateWorkingResumeFingerprint(
        destFile,
        requestUrl,
        headers,
        expectedContentLength
    )

    internal fun resolveResponseExpectedBytes(
        requestUrl: String,
        headers: Map<String, List<String>>,
        bodyLength: Long,
        resumedBytes: Long,
        isPartialResponse: Boolean
    ): Long? = AudioDownloadTransferPolicy.resolveResponseExpectedBytes(
        requestUrl,
        headers,
        bodyLength,
        resumedBytes,
        isPartialResponse
    )

    internal fun shouldPreservePartialDownloadForRetry(
        transportKind: DownloadTransportKind?,
        existingBytes: Long,
        hasHlsResumeState: Boolean
    ): Boolean = AudioDownloadTransferPolicy.shouldPreservePartialDownloadForRetry(
        transportKind,
        existingBytes,
        hasHlsResumeState
    )

    internal fun advanceRetryWakeSignalVersion(currentVersion: Long): Long =
        AudioDownloadTransferPolicy.advanceRetryWakeSignalVersion(currentVersion)

    internal fun resolveYouTubeDownloadResolveAttempts(
        forceRefresh: Boolean
    ): List<YouTubeDownloadResolveAttempt> =
        AudioDownloadTransferPolicy.resolveYouTubeDownloadResolveAttempts(forceRefresh)


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
    ): String = hlsResumeStore.buildPlaylistFingerprint(segmentUrls, playlistText)

    internal fun serializeHlsResumeState(state: HlsResumeState): String {
        return hlsResumeStore.serialize(state)
    }

    internal fun deserializeHlsResumeState(raw: String?): HlsResumeState? {
        return hlsResumeStore.deserialize(raw)
    }

    internal fun isHlsResumeStateCompatible(
        state: HlsResumeState,
        actualFileLength: Long,
        actualPrefixSha256: String,
        segmentCount: Int
    ): Boolean {
        return hlsResumeStore.isCompatible(
            state = state,
            actualFileLength = actualFileLength,
            actualPrefixSha256 = actualPrefixSha256,
            segmentCount = segmentCount
        )
    }

    internal fun isHlsResumeStateOwnedByOperation(
        state: HlsResumeState,
        operationId: String
    ): Boolean {
        return hlsResumeStore.isOwnedByOperation(state, operationId)
    }

    private fun sha256FilePrefix(file: File, byteCount: Long): String {
        return hlsResumeStore.sha256FilePrefix(file, byteCount)
    }

    private fun sha256FilePrefixDigest(file: File, byteCount: Long): MessageDigest {
        return hlsResumeStore.sha256FilePrefixDigest(file, byteCount)
    }

    private fun digestHexSnapshot(
        digest: MessageDigest,
        file: File,
        byteCount: Long
    ): String {
        return hlsResumeStore.digestHexSnapshot(digest, file, byteCount)
    }

    private fun truncateWorkingFile(file: File, byteCount: Long) {
        hlsResumeStore.truncate(file, byteCount)
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
        hlsResumeStore.remember(
            destFile = destFile,
            playlistFingerprint = playlistFingerprint,
            nextSegmentIndex = nextSegmentIndex,
            durableBytes = durableBytes,
            durablePrefixSha256 = durablePrefixSha256,
            operationId = operationId,
            mediaSequence = mediaSequence
        )
    }

    private fun resolveHlsResumeState(
        destFile: File,
        playlistFingerprint: String,
        operationId: String = ""
    ): HlsResumeState? {
        return hlsResumeStore.resolve(destFile, playlistFingerprint, operationId)
    }

    private fun hasHlsResumeState(destFile: File?): Boolean {
        return hlsResumeStore.has(destFile)
    }

    private fun clearHlsResumeState(destFile: File?) {
        hlsResumeStore.clear(destFile)
    }

    private fun deleteWorkingFile(tempFile: File?) {
        clearHlsResumeState(tempFile)
        ManagedDownloadStorage.deleteWorkingDownloadArtifacts(tempFile)
    }

    private fun shouldPreserveArtifactsForNetworkPolicy(songKey: String): Boolean {
        return operationRegistry.isNetworkPolicyPaused(songKey)
    }

    private fun <T> withNetworkPolicyMutationPermit(
        songKey: String,
        stage: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        operationId: String? = null,
        requireActiveAttempt: Boolean = true,
        block: () -> T
    ): T {
        return operationRegistry.withMutationLock {
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = stage,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId,
                requireActiveAttempt = requireActiveAttempt
            )
            block()
        }
    }

    private fun deleteWorkingFileUnlessNetworkPolicyPaused(
        songKey: String,
        tempFile: File?
    ): Boolean {
        return operationRegistry.withMutationLock {
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
        val normalizedOperationId = progress.operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (
            normalizedOperationId != null &&
                !operationRegistry.allowsReference(
                    songKey = progress.songKey,
                    operationId = normalizedOperationId,
                    attemptId = progress.attemptId
                )
        ) {
            // 取消和重下交错时，已经在路上的旧回调不能污染替代任务的进度
            NPLogger.d(
                TAG,
                "忽略过期下载进度: songKey=${progress.songKey}, " +
                    "operationId=$normalizedOperationId, attemptId=${progress.attemptId}"
            )
            return
        }
        if (
            progress.stage == DownloadStage.TRANSFERRING &&
                progress.transferGeneration != null
        ) {
            transferPermitRegistry.recordProgress(
                ownerKey = DownloadTransferPermitRegistry.ownerKey(
                    operationId = progress.operationId,
                    attemptId = progress.attemptId,
                    stableKey = progress.songKey
                ),
                generation = progress.transferGeneration,
                absoluteBytes = progress.bytesRead
            )
        }
        progressStore.publish(
            progress = progress,
            nowNs = System.nanoTime(),
            force = force
        )
    }

    private fun clearPublishedProgress(
        songKey: String,
        expectedAttemptId: Long? = null,
        expectedOperationId: String? = null
    ) {
        progressStore.clearPublished(
            songKey = songKey,
            expectedAttemptId = expectedAttemptId,
            expectedOperationId = expectedOperationId
        )
    }

    internal fun latestProgressForSong(
        songKey: String,
        attemptId: Long? = null,
        operationId: String? = null
    ): DownloadProgress? = progressStore.latestProgressForSong(
        songKey = songKey,
        attemptId = attemptId,
        operationId = operationId
    )

    internal fun latestProgressSnapshot(): List<DownloadProgress> =
        progressStore.latestProgressSnapshot()

    private fun clearVisibleProgressForSong(
        songKey: String,
        expectedAttemptId: Long? = null,
        expectedOperationId: String? = null
    ) = progressStore.clearVisibleProgressForSong(
        songKey = songKey,
        expectedAttemptId = expectedAttemptId,
        expectedOperationId = expectedOperationId
    )

    private fun clearAllPublishedProgress() = progressStore.clearAllPublished()

    private fun storageSpaceOwnerKey(
        operationId: String?,
        attemptId: Long?,
        songKey: String,
        file: File
    ): String {
        return buildString {
            append("download-output:")
            append(operationId?.trim().orEmpty().ifBlank { "anonymous" })
            append('#')
            append(attemptId ?: 0L)
            append(':')
            append(songKey)
            append(':')
            append(file.absolutePath)
        }
    }

    /** 网络仍在读取但暂时没有形成进度事件时，单独刷新传输看门狗心跳 */
    private fun markTransferNetworkActivity(
        operationId: String?,
        attemptId: Long?,
        songKey: String,
        transferGeneration: Long?
    ) {
        transferGeneration ?: return
        transferPermitRegistry.markNetworkActivity(
            ownerKey = DownloadTransferPermitRegistry.ownerKey(
                operationId = operationId,
                attemptId = attemptId,
                stableKey = songKey
            ),
            generation = transferGeneration
        )
    }

    private fun startBatchSession(): Long = progressStore.startBatchSession()

    private fun invalidateBatchSession() = progressStore.invalidateBatchSession()

    private fun isBatchSessionCurrent(batchSessionId: Long?): Boolean =
        progressStore.isBatchSessionCurrent(batchSessionId)

    private fun finishBatchSession(batchSessionId: Long) =
        progressStore.finishBatchSession(batchSessionId)

    private fun updateBatchProgressForSession(
        batchSessionId: Long,
        progress: BatchDownloadProgress?
    ) = progressStore.updateBatchProgressForSession(batchSessionId, progress)

    private fun beginSongDownloadOperation(
        songKey: String,
        operationId: String,
        attemptId: Long?
    ) {
        operationRegistry.beginSongDownloadOperation(songKey, operationId, attemptId)
    }

    private fun endSongDownloadOperation(songKey: String, operationId: String) {
        operationRegistry.endSongDownloadOperation(songKey, operationId)
    }

    private fun claimReferenceOwnershipForEnrichment(
        songKey: String,
        operationId: String
    ): Boolean = operationRegistry.claimReferenceOwnershipForEnrichment(songKey, operationId)

    private fun releaseReferenceOwnership(
        songKey: String,
        operationId: String
    ) {
        operationRegistry.releaseReferenceOwnership(songKey, operationId)
    }

    private fun registerActiveCall(
        songKey: String,
        call: okhttp3.Call,
        operationId: String?
    ) {
        operationRegistry.registerActiveCall(songKey, call, operationId)
    }

    private fun unregisterActiveCall(
        songKey: String,
        call: okhttp3.Call,
        operationId: String?
    ) {
        operationRegistry.unregisterActiveCall(songKey, call, operationId)
    }

    private fun snapshotActiveCalls(songKey: String? = null): List<okhttp3.Call> {
        return operationRegistry.snapshotActiveCalls(songKey)
    }

    private fun snapshotActiveCalls(operationIds: Collection<String>): List<okhttp3.Call> {
        return operationRegistry.snapshotActiveCalls(operationIds)
    }

    private fun activeOperationIdsForSongLocked(songKey: String): Set<String> {
        return operationRegistry.activeOperationIdsForSong(songKey)
    }

    /** 只取消指定 operation 的网络调用，保留同一歌曲的新代次 */
    internal fun cancelOperationDownload(
        songKey: String,
        operationIds: Collection<String>
    ): Int {
        val normalizedIds = operationIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedIds.isEmpty()) return 0
        val calls = operationRegistry.withMutationLock {
            // 先封存 operation，再取消当前调用，防止旧协程在取消窗口内新建请求
            normalizedIds.forEach(operationRegistry::clearCoreCommitted)
            operationRegistry.markExecutionHostPaused(normalizedIds)
            operationRegistry.revokeReference(songKey, normalizedIds)
            snapshotActiveCalls(normalizedIds)
        }
        calls.forEach(okhttp3.Call::cancel)
        normalizedIds.forEach { operationId ->
            clearPublishedProgress(
                songKey = songKey,
                expectedOperationId = operationId
            )
        }
        val visibleOperationId = progressStore.currentProgress()
            ?.takeIf { progress -> progress.songKey == songKey }
            ?.operationId
        if (visibleOperationId in normalizedIds) {
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedOperationId = visibleOperationId
            )
        }
        normalizedIds.forEach { operationId ->
            operationRegistry.clearExecutionHostPausedIfInactive(setOf(operationId))
        }
        return calls.size
    }

    /** 在系统取消协程前建立保留标记，避免取消异常先删除可续传文件 */
    internal fun pauseOperationDownloadForExecutionHost(
        operationId: String,
        durableState: String? = null
    ): Boolean {
        val normalizedId = operationId.trim().takeIf(String::isNotBlank) ?: return false
        if (isPostCoreDownloadOperationState(durableState)) {
            operationRegistry.clearExecutionHostPaused(normalizedId)
            NPLogger.d(
                TAG,
                "宿主停止跳过已提交 core operation: operationId=$normalizedId, " +
                    "state=$durableState"
            )
            return false
        }
        var skippedCoreCommitted = false
        var songKey: String? = null
        val calls = operationRegistry.withMutationLock {
            if (operationRegistry.isCoreCommitted(normalizedId)) {
                operationRegistry.clearExecutionHostPaused(normalizedId)
                skippedCoreCommitted = true
                emptyList()
            } else {
                songKey = operationRegistry.songKeyForOperation(normalizedId)
                val currentSongKey = songKey
                if (currentSongKey == null) {
                    emptyList()
                } else {
                    operationRegistry.markExecutionHostPaused(normalizedId)
                    operationRegistry.revokeReference(currentSongKey, setOf(normalizedId))
                    snapshotActiveCalls(listOf(normalizedId))
                }
            }
        }
        if (skippedCoreCommitted) {
            NPLogger.d(
                TAG,
                "宿主停止跳过已提交 core operation: operationId=$normalizedId"
            )
            return false
        }
        val resolvedSongKey = songKey ?: return false
        calls.forEach(okhttp3.Call::cancel)
        clearPublishedProgress(
            songKey = resolvedSongKey,
            expectedOperationId = normalizedId
        )
        val visibleProgress = progressStore.currentProgress()
        if (
            visibleProgress?.songKey == resolvedSongKey &&
                visibleProgress.operationId == normalizedId
        ) {
            clearVisibleProgressForSong(
                songKey = resolvedSongKey,
                expectedOperationId = normalizedId
            )
        }
        return true
    }

    internal fun isOperationPausedForExecutionHost(operationId: String): Boolean {
        val normalizedId = operationId.trim()
        return normalizedId.isNotBlank() &&
            operationRegistry.isExecutionHostPaused(normalizedId)
    }

    internal fun isCoreCommittedOperation(operationId: String): Boolean {
        return operationRegistry.isCoreCommitted(operationId)
    }

    internal fun markCoreCommittedOperation(operationId: String) {
        operationRegistry.markCoreCommitted(operationId)
        operationRegistry.clearExecutionHostPaused(operationId)
    }

    internal fun clearCoreCommittedOperation(operationId: String) {
        operationRegistry.clearCoreCommitted(operationId)
    }

    internal fun clearOperationPauseForExecutionHost(operationId: String) {
        val normalizedId = operationId.trim()
        if (normalizedId.isNotBlank()) {
            operationRegistry.clearExecutionHostPaused(normalizedId)
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
        operationId: String? = null,
        requireActiveAttempt: Boolean = true,
        block: (okhttp3.Response) -> T
    ): T {
        val call = client.newCall(request)
        val normalizedOperationId = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val pausedBeforeExecution = operationRegistry.withMutationLock {
            registerActiveCall(songKey, call, normalizedOperationId)
            shouldPreserveArtifactsForNetworkPolicy(songKey) ||
                normalizedOperationId?.let(operationRegistry::isExecutionHostPaused) == true
        }
        try {
            if (pausedBeforeExecution) {
                call.cancel()
                throw java.util.concurrent.CancellationException(
                    if (
                        normalizedOperationId?.let(
                            operationRegistry::isExecutionHostPaused
                        ) == true
                    ) {
                        "Download execution host paused"
                    } else {
                        "Download paused for network policy"
                    }
                )
            }
            return call.execute().use(block)
        } catch (error: IOException) {
            if (
                call.isCanceled() ||
                (_isCancelled.value && requireActiveAttempt) ||
                shouldPreserveArtifactsForNetworkPolicy(songKey) ||
                GlobalDownloadManager.isSongCancelled(songKey)
            ) {
                clearVisibleProgressForSong(
                    songKey = songKey,
                    expectedOperationId = normalizedOperationId
                )
                throw java.util.concurrent.CancellationException("Download cancelled").apply {
                    initCause(error)
                }
            }
            throw error
        } finally {
            operationRegistry.withMutationLock {
                unregisterActiveCall(songKey, call, normalizedOperationId)
            }
        }
    }

    internal fun consumeCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? =
        completedAudioReferenceRegistry.consumeCompletedAudioReference(songKey)

    internal fun releaseCompletedAudioReference(
        songKey: String,
        expectedAudio: ManagedDownloadStorage.StoredEntry? = null,
        retainForPlayback: Boolean = false
    ) {
        completedAudioReferenceRegistry.releaseCompletedAudioReference(
            songKey = songKey,
            expectedAudio = expectedAudio,
            retainForPlayback = retainForPlayback
        )
    }

    /** 迁移或切换下载根后，主动丢弃仍指向旧目录的内存桥接引用 */
    internal fun invalidateCompletedAudioReference(song: SongItem) {
        completedAudioReferenceRegistry.invalidateCompletedAudioReference(song)
    }

    /**
     * core 音频已经完成校验但全局完成回调尚未消费引用时, 播放入口也要能立即读取
     */
    internal fun peekCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? =
        completedAudioReferenceRegistry.peekCompletedAudioReference(songKey)

    /** 允许刚提交音频按原始 URI 取回, 避免歌曲身份字段尚未同步时丢失桥接 */
    internal fun peekCompletedAudioReferenceByRawReference(
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? =
        completedAudioReferenceRegistry.peekCompletedAudioReferenceByRawReference(reference)

    /** 允许播放列表使用提交时刚写入的 URI, 即使稳定身份字段尚未同步 */
    internal fun peekCompletedAudioReference(song: SongItem): ManagedDownloadStorage.StoredEntry? =
        completedAudioReferenceRegistry.peekCompletedAudioReference(song)

    internal fun consumePartialSidecarReferences(
        songKey: String,
        operationId: String? = null
    ): DownloadedSidecarReferences? = operationRegistry.withMutationLock {
        if (!operationRegistry.allowsReference(songKey, operationId)) {
            return@withMutationLock null
        }
        completedAudioReferenceRegistry.consumePartialSidecarReferences(songKey)
    }

    internal fun rememberCompletedAudioReference(
        songKey: String,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        operationId: String? = null
    ) = operationRegistry.withMutationLock {
        if (!operationRegistry.allowsReference(songKey, operationId)) {
            return@withMutationLock
        }
        completedAudioReferenceRegistry.rememberCompletedAudioReference(songKey, storedAudio)
    }

    /** 下载回调与播放队列可能使用不同版本的歌曲身份, 同时保存兼容别名 */
    internal fun rememberCompletedAudioReference(
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        operationId: String? = null
    ) = operationRegistry.withMutationLock {
        if (!operationRegistry.allowsReference(song.stableKey(), operationId)) {
            return@withMutationLock
        }
        completedAudioReferenceRegistry.rememberCompletedAudioReference(song, storedAudio)
    }

    private fun safeToPlayableUri(reference: String?): String? {
        return runCatching {
            ManagedDownloadStorage.toPlayableUri(reference)
        }.getOrNull()
    }

    private fun rememberPartialSidecarReferences(
        songKey: String,
        sidecarReferences: DownloadedSidecarReferences,
        operationId: String? = null
    ) = operationRegistry.withMutationLock {
        if (!operationRegistry.allowsReference(songKey, operationId)) {
            return@withMutationLock
        }
        completedAudioReferenceRegistry.rememberPartialSidecarReferences(
            songKey,
            sidecarReferences
        )
    }

    private fun clearCompletedAudioReference(
        songKey: String,
        operationId: String? = null
    ) = operationRegistry.withMutationLock {
        if (operationRegistry.allowsReference(songKey, operationId)) {
            completedAudioReferenceRegistry.clearCompletedAudioReference(songKey)
        }
    }

    private fun clearPartialSidecarReferences(
        songKey: String,
        operationId: String? = null
    ) = operationRegistry.withMutationLock {
        if (operationRegistry.allowsReference(songKey, operationId)) {
            completedAudioReferenceRegistry.clearPartialSidecarReferences(songKey)
        }
    }

    internal fun mergeDownloadedSidecarReferences(
        existing: DownloadedSidecarReferences?,
        incoming: DownloadedSidecarReferences?
    ): DownloadedSidecarReferences = AudioDownloadSidecarPolicy.mergeDownloadedSidecarReferences(
        existing,
        incoming
    )


    private fun publishFinalizingProgress(
        songId: Long,
        songKey: String,
        fileName: String,
        bytesRead: Long,
        totalBytes: Long,
        attemptId: Long? = null,
        operationId: String? = null
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
                attemptId = attemptId,
                operationId = operationId
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
        attemptId: Long? = null,
        operationId: String? = null
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
                attemptId = attemptId,
                operationId = operationId
            ),
            force = true
        )
    }

    internal fun publishStageProgress(
        songId: Long,
        songKey: String,
        fileName: String,
        stage: DownloadStage,
        attemptId: Long? = null,
        operationId: String? = null,
        bytesRead: Long = 0L,
        totalBytes: Long = 0L
    ) {
        publishProgress(
            DownloadProgress(
                songKey = songKey,
                songId = songId,
                fileName = fileName,
                bytesRead = bytesRead.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L),
                speedBytesPerSec = 0L,
                stage = stage,
                attemptId = attemptId,
                operationId = operationId
            ),
            force = true
        )
    }

    private fun ensureSongDownloadNotCancelled(
        songKey: String,
        stage: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        operationId: String? = null,
        requireActiveAttempt: Boolean = true
    ) {
        val attemptAllowsWork = if (requireActiveAttempt) {
            GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId)
        } else {
            attemptId == null || GlobalDownloadManager.isDownloadAttemptCurrent(songKey, attemptId)
        }
        val normalizedOperationId = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val clearFenceAllowsWork = !isDownloadClearFenceBlockingWork(
            songKey = songKey,
            operationId = normalizedOperationId
        )
        val operationAllowsWork = normalizedOperationId?.let { id ->
            operationRegistry.allowsReference(songKey, id) &&
                !operationRegistry.isExecutionHostPaused(id) &&
                clearFenceAllowsWork
        } ?: clearFenceAllowsWork
        if (!shouldAbortDownloadWork(
                // 后台补齐复用已提交音频，不应继承上一轮全局取消标志
                allDownloadsCancelled = _isCancelled.value && requireActiveAttempt,
                batchSessionCurrent = isBatchSessionCurrent(batchSessionId),
                songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey),
                attemptAllowsWork = attemptAllowsWork,
                operationAllowsWork = operationAllowsWork
            )
        ) {
            return
        }
        NPLogger.d(TAG, "检测到下载取消: songKey=$songKey, stage=$stage")
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = operationId
        )
        throw java.util.concurrent.CancellationException("Download cancelled during $stage")
    }

    private fun isDownloadClearFenceBlockingWork(
        songKey: String,
        operationId: String?
    ): Boolean {
        return PersistentDownloadClearFenceStore.isBlocked(
            context = AppContainer.applicationContext,
            stableKey = songKey,
            operationId = operationId
        )
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
        downloadAudioQuality: DownloadAudioQualitySelection? = null,
        forceFreshTransfer: Boolean = false
    ) {
        downloadSongOnIo(
            context = context,
            song = song,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId,
            downloadAudioQuality = downloadAudioQuality,
            forceFreshTransfer = forceFreshTransfer
        )
    }

    private suspend fun downloadSongOnIo(
        context: Context,
        song: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?,
        downloadAudioQuality: DownloadAudioQualitySelection?,
        forceFreshTransfer: Boolean
    ) {
        withContext(Dispatchers.IO) {
            executeDownloadSong(
                context = context,
                song = song,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId,
                downloadAudioQuality = downloadAudioQuality,
                forceFreshTransfer = forceFreshTransfer
            )
        }
    }

    private suspend fun executeDownloadSong(
        context: Context,
        song: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?,
        downloadAudioQuality: DownloadAudioQualitySelection?,
        forceFreshTransfer: Boolean
    ) {
        val songKey = song.stableKey()
        val effectiveOperationId = operationId?.trim()
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        val traceToken = DownloadOperationTrace.begin(
            operationId = effectiveOperationId,
            attemptId = attemptId
        )
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.ENQUEUED
        )
        val state = DownloadExecutionAttemptState()
        // 进入新的真实传输前清掉旧代次的 core 标记，避免取消后复用 operation
        // 时把新下载误当成后台增强任务
        operationRegistry.clearCoreCommitted(effectiveOperationId)
        beginSongDownloadOperation(songKey, effectiveOperationId, attemptId)
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
        try {
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "prepare",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            if (LocalSongSupport.isLocalSong(song, context)) {
                NPLogger.d(TAG, "Skip local song download: ${song.name}")
                clearVisibleProgressForSong(
                    songKey = songKey,
                    expectedAttemptId = attemptId,
                    expectedOperationId = effectiveOperationId
                )
                return
            }

            if (!forceFreshTransfer && hasFastCachedManagedDownloadForStart(context, song)) {
                NPLogger.d(
                    TAG,
                    "${context.getString(R.string.download_file_exists, song.name)}, songKey=$songKey"
                )
                clearVisibleProgressForSong(
                    songKey = songKey,
                    expectedAttemptId = attemptId,
                    expectedOperationId = effectiveOperationId
                )
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
            // 阶段契约由尝试层按 stage = "source_resolved" 和
            // stage = "prepare_working_file" 顺序推进
            // 真实传输前才会调用 clearCompletedAudioReference(songKey)，再进入
            // downloadPayloadForTransport(...) 和 finalizeDownloadedAudio(...)
            // 取消收敛共享 cleanupCancelledPendingArtifactsWithLease(...) 的恢复凭据
            runDownloadAttempts(
                context = context,
                song = song,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                effectiveOperationId = effectiveOperationId,
                downloadAudioQuality = resolvedDownloadAudioQuality,
                isYouTubeMusic = isYouTubeMusic,
                isBili = isBili,
                state = state
            )
        } catch (error: Exception) {
            handleDownloadSongFailure(
                context = context,
                song = song,
                songKey = songKey,
                effectiveOperationId = effectiveOperationId,
                attemptId = attemptId,
                state = state,
                error = error
            )
        } finally {
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.TERMINAL
            )
            clearPublishedProgress(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = effectiveOperationId
            )
            endSongDownloadOperation(songKey, effectiveOperationId)
        }
    }

    /** 统一处理取消、空间等待和普通失败，避免下载入口生成过大的协程状态机 */
    private suspend fun handleDownloadSongFailure(
        context: Context,
        song: SongItem,
        songKey: String,
        effectiveOperationId: String,
        attemptId: Long?,
        state: DownloadExecutionAttemptState,
        error: Exception
    ): Nothing {
        if (
            error is DownloadStorageMutationDeferredException ||
                error is DownloadStorageSpaceDeferredException ||
                error is DownloadTransferAdmissionDeferredException
        ) {
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = effectiveOperationId
            )
            clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
            clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
            throw error
        }
        if (
            error is java.util.concurrent.CancellationException ||
                _isCancelled.value ||
                shouldPreserveArtifactsForNetworkPolicy(songKey) ||
                GlobalDownloadManager.isSongCancelled(songKey)
        ) {
            handleDownloadSongCancellation(
                context = context,
                song = song,
                songKey = songKey,
                effectiveOperationId = effectiveOperationId,
                attemptId = attemptId,
                state = state,
                error = error
            )
        }
        NPLogger.e(
            TAG,
            "下载失败: ${song.name}, 错误: ${error.javaClass.simpleName} - ${error.message}",
            error
        )
        deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = effectiveOperationId
        )
        clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
        throw error
    }

    private suspend fun handleDownloadSongCancellation(
        context: Context,
        song: SongItem,
        songKey: String,
        effectiveOperationId: String,
        attemptId: Long?,
        state: DownloadExecutionAttemptState,
        error: Exception
    ): Nothing {
        val partialSidecarReferences = consumePartialSidecarReferences(
            songKey,
            operationId = effectiveOperationId
        )
            ?.retainCreatedOnly()
        NPLogger.d(TAG, "下载已取消: ${song.name}")
        val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
        val preserveCancellationArtifacts =
            shouldPreserveWorkingArtifactsAfterCancellation(
                cancellation = error is java.util.concurrent.CancellationException,
                allDownloadsCancelled = _isCancelled.value,
                songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                networkPolicyPaused = preserveArtifacts
            )
        if (
            !preserveCancellationArtifacts &&
            shouldRollbackCancelledAudio(state.coreCommitTracker.phase)
        ) {
            if (!state.cancellationCleanupAttempted) {
                state.cancellationCleanupAttempted = true
                val cleanupResult = cleanupCancelledPendingArtifactsWithLease(
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
            if (state.storedAudio != null || partialSidecarReferences?.isEmpty == false) {
                runCatching {
                    NPLogger.d(
                        TAG,
                        "下载取消后回滚半成品: song=${song.name}, " +
                            "audio=${state.storedAudio?.reference}, " +
                            "sidecars=$partialSidecarReferences"
                    )
                    GlobalDownloadManager.rollbackCancelledDownload(
                        context = context,
                        song = song,
                        storedAudio = state.storedAudio,
                        sidecarReferences = partialSidecarReferences,
                        operationId = effectiveOperationId
                    )
                    state.storedAudio = null
                }.onFailure { rollbackError ->
                    NPLogger.e(
                        TAG,
                        "回滚已取消下载失败: ${song.name}, ${rollbackError.message}",
                        rollbackError
                    )
                }
            }
        }
        if (!preserveCancellationArtifacts) {
            deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
        }
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = effectiveOperationId
        )
        if (!preserveCancellationArtifacts) {
            clearSongCancelled(songKey)
        }
        clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
        throw java.util.concurrent.CancellationException(
            if (preserveCancellationArtifacts) {
                "Download cancellation deferred for recovery"
            } else {
                "Download cancelled"
            }
        )
    }

    private suspend fun runDownloadAttempts(
        context: Context,
        song: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        effectiveOperationId: String,
        downloadAudioQuality: DownloadAudioQualitySelection,
        isYouTubeMusic: Boolean,
        isBili: Boolean,
        state: DownloadExecutionAttemptState
    ) {
        val songKey = song.stableKey()
        while (true) {
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "prepare",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            publishStageProgress(
                songId = song.id,
                songKey = songKey,
                fileName = state.activeWorkingFileName
                    ?: ManagedDownloadStorage.buildDisplayBaseName(song),
                stage = DownloadStage.RESOLVING_SOURCE,
                attemptId = attemptId,
                operationId = effectiveOperationId,
                bytesRead = resolveWorkingFileBytes(state.tempFile),
                totalBytes = progressStore.currentProgress()
                    ?.takeIf { it.songKey == songKey }
                    ?.totalBytes
                    ?: 0L
            )
            try {
                if (
                    executeDownloadAttempt(
                        context = context,
                        song = song,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        effectiveOperationId = effectiveOperationId,
                        downloadAudioQuality = downloadAudioQuality,
                        isYouTubeMusic = isYouTubeMusic,
                        isBili = isBili,
                        state = state
                    )
                ) {
                    return
                }
            } catch (error: Exception) {
                when (
                    handleDownloadAttemptFailure(
                        context = context,
                        song = song,
                        batchSessionId = batchSessionId,
                        attemptId = attemptId,
                        effectiveOperationId = effectiveOperationId,
                        isYouTubeMusic = isYouTubeMusic,
                        state = state,
                        error = error
                    )
                ) {
                    DownloadAttemptFailureAction.RETRY -> Unit
                }
            }
        }
    }

    private suspend fun executeDownloadAttempt(
        context: Context,
        song: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        effectiveOperationId: String,
        downloadAudioQuality: DownloadAudioQualitySelection,
        isYouTubeMusic: Boolean,
        isBili: Boolean,
        state: DownloadExecutionAttemptState
    ): Boolean {
        val songKey = song.stableKey()
        val traceToken = DownloadOperationTrace.begin(
            operationId = effectiveOperationId,
            attemptId = attemptId
        )
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.SOURCE_RESOLVE_STARTED
        )
        val resolved = try {
            resolveDownloadSourceForAttempt(
                song = song,
                downloadAudioQuality = downloadAudioQuality,
                isYouTubeMusic = isYouTubeMusic,
                isBili = isBili,
                state = state
            )
        } finally {
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.SOURCE_RESOLVE_FINISHED
            )
        }
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "source_resolved",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        if (resolved == null) {
            if (state.attemptNumber >= TRANSIENT_DOWNLOAD_MAX_ATTEMPTS) {
                throw IOException(context.getString(R.string.download_no_url, song.name))
            }
            val retryDelayMs = resolveTransientDownloadRetryDelayMs(state.attemptNumber)
            publishRetryWaitingProgress(
                songId = song.id,
                songKey = songKey,
                fileName = state.activeWorkingFileName
                    ?: ManagedDownloadStorage.buildDisplayBaseName(song),
                bytesRead = resolveWorkingFileBytes(state.tempFile),
                totalBytes = progressStore.currentProgress()
                    ?.takeIf { it.songKey == songKey }
                    ?.totalBytes
                    ?: 0L,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            NPLogger.w(
                TAG,
                "下载链接暂时不可用，准备重试(${state.attemptNumber}/$TRANSIENT_DOWNLOAD_MAX_ATTEMPTS): " +
                    song.name
            )
            if (isYouTubeMusic) {
                state.forceRefreshYouTubeSource = true
            }
            evictDownloadConnections()
            waitForRetryOrCancellation(
                context = context,
                songKey = songKey,
                delayMs = retryDelayMs,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            state.attemptNumber++
            return false
        }
        state.forceRefreshYouTubeSource = false
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.PREPARE_STARTED
        )
        val prepared = try {
            prepareDownloadAttempt(
                context = context,
                song = song,
                resolved = resolved,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                effectiveOperationId = effectiveOperationId,
                state = state
            )
        } finally {
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.PREPARE_FINISHED
            )
        }
        transferAndCommitDownloadAttempt(
            context = context,
            songKey = songKey,
            prepared = prepared,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            effectiveOperationId = effectiveOperationId,
            state = state
        )
        return true
    }

    private suspend fun resolveDownloadSourceForAttempt(
        song: SongItem,
        downloadAudioQuality: DownloadAudioQualitySelection,
        isYouTubeMusic: Boolean,
        isBili: Boolean,
        state: DownloadExecutionAttemptState
    ): ResolvedDownloadSource? {
        return sourceResolveSemaphore.withPermit {
            when {
                isYouTubeMusic -> resolveYouTubeMusic(
                    song = song,
                    preferredQuality = downloadAudioQuality.youtubeQuality,
                    forceRefresh = state.forceRefreshYouTubeSource,
                    avoidDirect = state.avoidYouTubeDirectSource
                )
                isBili -> resolveBili(
                    song = song,
                    preferredQuality = downloadAudioQuality.biliQuality
                )
                else -> resolveNetease(
                    songId = song.id,
                    preferredQuality = downloadAudioQuality.neteaseQuality
                )
            }
        }
    }

    private suspend fun prepareDownloadAttempt(
        context: Context,
        song: SongItem,
        resolved: ResolvedDownloadSource,
        batchSessionId: Long?,
        attemptId: Long?,
        effectiveOperationId: String,
        state: DownloadExecutionAttemptState
    ): PreparedDownloadAttempt {
        val songKey = song.stableKey()
        val workingSong = if (
            song.durationMs == 0L &&
                resolved.durationMs != null &&
                resolved.durationMs > 0L
        ) {
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
                "下载来源长度提示: file=$fileName, sourceExpected=$sourceExpectedBytes"
            )
        }
        val requestBuilder = Request.Builder().url(url)
        if (song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)) {
            val cookieMap = AppContainer.biliCookieRepo.getCookiesOnce()
            val cookieHeader = cookieMap.entries.joinToString("; ") { (key, value) ->
                "$key=$value"
            }
            requestBuilder
                .header("User-Agent", BILI_UA)
                .header("Referer", BILI_REFERER)
                .apply {
                    if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                }
        } else if (isYouTubeMusicSong(song)) {
            val auth = AppContainer.youtubeAuthRepo.getAuthOnce().normalized()
            auth.buildYouTubeStreamRequestHeaders(
                refererOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
                streamUrl = url
            ).forEach { (name, value) -> requestBuilder.header(name, value) }
            // 直链统一交给分块传输，避免整档 Range 触发 googlevideo 风控
        }
        val request = requestBuilder.build()
        val transportKind = resolveDownloadTransportKind(
            streamType = resolved.streamType,
            request = request
        )
        publishStageProgress(
            songId = workingSong.id,
            songKey = songKey,
            fileName = fileName,
            stage = DownloadStage.PREPARING_STORAGE,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            bytesRead = resolveWorkingFileBytes(state.tempFile),
            totalBytes = resolved.contentLength ?: 0L
        )
        if (state.tempFile == null) {
            state.tempFile = ManagedDownloadStorage.findWorkingFileForResume(
                context = context,
                songKey = songKey
            )
            state.tempFile?.let { file ->
                NPLogger.d(
                    TAG,
                    "复用 operation staging 断点: song=${workingSong.name}, file=${file.name}"
                )
            }
        }
        val workingFile = withNetworkPolicyMutationPermit(
            songKey = songKey,
            stage = "prepare_working_file",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        ) {
            if (
                state.tempFile == null ||
                    (state.activeWorkingFileName != null &&
                        state.activeWorkingFileName != fileName) ||
                    (state.activeTransportKind != null &&
                        state.activeTransportKind != transportKind)
            ) {
                deleteWorkingFile(state.tempFile)
                state.tempFile = ManagedDownloadStorage.createWorkingFile(
                    context = context,
                    songKey = songKey,
                    fileName = fileName,
                    operationId = effectiveOperationId
                )
            }
            state.activeWorkingFileName = fileName
            state.activeTransportKind = transportKind
            val currentWorkingFile = requireNotNull(state.tempFile)
            state.resumeMetadataAvailable = ManagedDownloadStorage.saveWorkingResumeMetadata(
                workingFile = currentWorkingFile,
                song = workingSong,
                operationId = effectiveOperationId
            )
            if (!state.resumeMetadataAvailable) {
                NPLogger.w(
                    TAG,
                    "续传元数据不可用，当前下载继续但不宣称可无损恢复: " +
                        "file=${currentWorkingFile.name}, operationId=$effectiveOperationId"
                )
            }
            currentWorkingFile
        }
        reconcileWorkingFileWithDurableCheckpoint(
            context = context,
            songKey = songKey,
            workingFile = workingFile,
            transportKind = transportKind,
            operationId = effectiveOperationId,
            attemptId = attemptId,
            batchSessionId = batchSessionId
        )
        return PreparedDownloadAttempt(
            resolved = resolved,
            workingSong = workingSong,
            request = request,
            transportKind = transportKind,
            fileName = fileName,
            mimeType = mime,
            workingFile = workingFile
        )
    }

    private suspend fun reconcileWorkingFileWithDurableCheckpoint(
        context: Context,
        songKey: String,
        workingFile: File,
        transportKind: DownloadTransportKind,
        operationId: String,
        attemptId: Long?,
        batchSessionId: Long?
    ) {
        // HLS 已经有带摘要的分段检查点，不能让普通字节检查点覆盖它
        if (transportKind == DownloadTransportKind.HLS || attemptId == null) {
            return
        }
        val checkpoint = DownloadExecutionRoomStore.readProgressCheckpoint(
            context = context.applicationContext,
            operationId = operationId,
            stableKey = songKey,
            attemptId = attemptId
        ) ?: return
        val safeBytes = checkpoint.bytesWritten.coerceAtLeast(0L)
        val fileBytes = resolveWorkingFileBytes(workingFile)
        if (fileBytes <= safeBytes) {
            return
        }
        withNetworkPolicyMutationPermit(
            songKey = songKey,
            stage = "truncate_to_durable_checkpoint",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId
        ) {
            truncateWorkingFile(workingFile, safeBytes)
        }
        NPLogger.w(
            TAG,
            "工作文件尾部超过安全检查点，已截断后续传: " +
                "file=${workingFile.name}, disk=$fileBytes, safe=$safeBytes, " +
                "operationId=$operationId"
        )
    }

    private suspend fun transferAndCommitDownloadAttempt(
        context: Context,
        songKey: String,
        prepared: PreparedDownloadAttempt,
        batchSessionId: Long?,
        attemptId: Long?,
        effectiveOperationId: String,
        state: DownloadExecutionAttemptState
    ) {
        val traceToken = DownloadOperationTrace.begin(
            operationId = effectiveOperationId,
            attemptId = attemptId
        )
        // 只有确认即将开始新的网络传输后才清理旧桥接
        clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
        publishStageProgress(
            songId = prepared.workingSong.id,
            songKey = songKey,
            fileName = prepared.fileName,
            stage = DownloadStage.TRANSFERRING,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            bytesRead = resolveWorkingFileBytes(prepared.workingFile),
            totalBytes = prepared.resolved.contentLength ?: 0L
        )
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.NETWORK_PERMIT_REQUESTED
        )
        val ownerKey = DownloadTransferPermitRegistry.ownerKey(
            operationId = effectiveOperationId,
            attemptId = attemptId,
            stableKey = prepared.workingSong.stableKey()
        )
        var coreTransferReleaseDispatched = false
        val committedAudio = withTransferCyclePermit(
            context = context,
            ownerKey = ownerKey,
            traceToken = traceToken,
            operationId = effectiveOperationId,
            attemptId = attemptId
        ) { permit, markNetworkFinished, transferOwnerToken ->
            val downloadedPayload = transferWatchdog.run(permit) {
                downloadPayloadForTransport(
                    transportKind = prepared.transportKind,
                    resolved = prepared.resolved,
                    request = prepared.request,
                    workingFile = prepared.workingFile,
                    fileName = prepared.fileName,
                    workingSong = prepared.workingSong,
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    effectiveOperationId = effectiveOperationId,
                    transferGeneration = permit.generation
                )
            }
            markNetworkFinished()
            state.resumeMetadataAvailable = state.resumeMetadataAvailable &&
                downloadedPayload.resumeMetadataAvailable
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.CORE_COMMIT_REQUESTED
            )
            val committedAudio = coreCommitSemaphore.withPermit {
                // 网络阶段可以并行，最终文件提交必须和同曲目的新代次串行
                GlobalDownloadManager.withSongExecutionLock(songKey) {
                    // 把 semaphore 和同曲目提交锁的等待合并为一个 Core admission 段
                    DownloadOperationTrace.mark(
                        traceToken,
                        DownloadOperationTracePhase.CORE_COMMIT_GRANTED
                    )
                    DownloadOperationTrace.mark(
                        traceToken,
                        DownloadOperationTracePhase.CORE_COMMIT_STARTED
                    )
                    try {
                        ensureSongDownloadNotCancelled(
                            songKey = songKey,
                            stage = "core_commit_lock",
                            batchSessionId = batchSessionId,
                            attemptId = attemptId,
                            operationId = effectiveOperationId
                        )
                        finalizeDownloadedAudio(
                            context = context,
                            songKey = songKey,
                            workingSong = prepared.workingSong,
                            fileName = prepared.fileName,
                            mimeType = prepared.mimeType,
                            workingFile = prepared.workingFile,
                            payloadSummary = downloadedPayload,
                            effectiveOperationId = effectiveOperationId,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId,
                            coreCommitTracker = state.coreCommitTracker
                        )
                    } finally {
                        DownloadOperationTrace.mark(
                            traceToken,
                            DownloadOperationTracePhase.CORE_COMMIT_FINISHED
                        )
                    }
                }
            }
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.CORE_COMMITTED
            )
            val committedWithOwner = committedAudio.copy(transferOwnerToken = transferOwnerToken)
            if (committedWithOwner.operationCoreCommitted) {
                // permit 仍在本次传输的 finally 之前，先释放 host owner 并唤醒补位，
                // 避免下一个 operation 抢到网络 permit 后又被旧 owner 拒绝
                coreTransferReleaseDispatched = GlobalDownloadManager.wakeDownloadExecutionPumpAfterCoreCommit(
                    context = context,
                    operationId = effectiveOperationId,
                    attemptId = attemptId,
                    transferOwnerToken = transferOwnerToken
                )
            }
            committedWithOwner
        }
        // 只有 operation journal 的 CAS 成功后才释放宿主传输槽位；pending
        // 音频已经落盘但 journal 失败时必须留给恢复路径收敛
        if (!committedAudio.operationCoreCommitted) {
            NPLogger.w(
                TAG,
                "Core Commit journal 未确认，暂不释放传输槽位: " +
                    "operationId=$effectiveOperationId, attemptId=$attemptId"
            )
        } else if (!coreTransferReleaseDispatched) {
            coreTransferReleaseDispatched = GlobalDownloadManager.wakeDownloadExecutionPumpAfterCoreCommit(
                context = context,
                operationId = effectiveOperationId,
                attemptId = attemptId,
                transferOwnerToken = committedAudio.transferOwnerToken
            )
        }
        state.storedAudio = committedAudio.audio
        publishStageProgress(
            songId = prepared.workingSong.id,
            songKey = songKey,
            fileName = committedAudio.audio.name,
            stage = DownloadStage.ASSETS_ENRICHING,
            bytesRead = committedAudio.transferredBytes,
            totalBytes = committedAudio.transferredBytes,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
        NPLogger.d(
            TAG,
            "音频落盘完成，sidecar 转入后台整理: " +
                "song=${prepared.workingSong.name}, audioFile=${committedAudio.audio.name}"
        )
        rememberCompletedAudioReference(
            song = prepared.workingSong,
            storedAudio = committedAudio.audio,
            operationId = effectiveOperationId
        )
        clearVisibleProgressForSong(
            songKey = songKey,
            expectedAttemptId = attemptId,
            expectedOperationId = effectiveOperationId
        )
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
    }

    private suspend fun handleDownloadAttemptFailure(
        context: Context,
        song: SongItem,
        batchSessionId: Long?,
        attemptId: Long?,
        effectiveOperationId: String,
        isYouTubeMusic: Boolean,
        state: DownloadExecutionAttemptState,
        error: Exception
    ): DownloadAttemptFailureAction {
        val songKey = song.stableKey()
        if (
            error is DownloadStorageMutationDeferredException ||
                error is DownloadTransferAdmissionDeferredException
        ) {
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = effectiveOperationId
            )
            clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
            clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
            throw error
        }
        val storageFailureKind = classifyDownloadStorageSpaceFailure(error)
        if (storageFailureKind != null) {
            publishRetryWaitingProgress(
                songId = song.id,
                songKey = songKey,
                fileName = state.activeWorkingFileName
                    ?: ManagedDownloadStorage.buildDisplayBaseName(song),
                bytesRead = resolveWorkingFileBytes(state.tempFile),
                totalBytes = progressStore.currentProgress()
                    ?.takeIf { it.songKey == songKey }
                    ?.totalBytes
                    ?: 0L,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            if (storageFailureKind.isDefinitive) {
                clearVisibleProgressForSong(
                    songKey = songKey,
                    expectedAttemptId = attemptId,
                    expectedOperationId = effectiveOperationId
                )
                // 只有已知真实容量耗尽或 Provider 返回 ENOSPC 才进入全局取消，
                // 进程内预留竞争和空间探测失败继续保留工作文件重试
                throw DownloadStorageSpaceDeferredException(
                    operationId = effectiveOperationId,
                    failureKind = storageFailureKind,
                    cancelAllDownloads = true
                )
            }
            NPLogger.w(
                TAG,
                "下载空间检查暂不可用，保留工作文件短暂重试: " +
                    "song=${song.name}, operationId=$effectiveOperationId, " +
                    "kind=$storageFailureKind"
            )
            waitForRetryOrCancellation(
                context = context,
                songKey = songKey,
                delayMs = STORAGE_SPACE_CONTENTION_RETRY_DELAY_MS,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            return DownloadAttemptFailureAction.RETRY
        }
        val preserveArtifacts = shouldPreserveArtifactsForNetworkPolicy(songKey)
        val preserveCancellationArtifacts =
            shouldPreserveWorkingArtifactsAfterCancellation(
                cancellation = error is java.util.concurrent.CancellationException,
                allDownloadsCancelled = _isCancelled.value,
                songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                networkPolicyPaused = preserveArtifacts
            )
        if (
            error is java.util.concurrent.CancellationException ||
                _isCancelled.value ||
                preserveArtifacts ||
                GlobalDownloadManager.isSongCancelled(songKey)
        ) {
            val partialSidecarReferences = consumePartialSidecarReferences(
                songKey,
                operationId = effectiveOperationId
            )
                ?.retainCreatedOnly()
            NPLogger.d(TAG, "下载已取消: ${song.name}")
            if (
                !preserveCancellationArtifacts &&
                    shouldRollbackCancelledAudio(state.coreCommitTracker.phase)
            ) {
                state.cancellationCleanupAttempted = true
                val cleanupResult = cleanupCancelledPendingArtifactsWithLease(
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
                if (state.storedAudio != null || partialSidecarReferences?.isEmpty == false) {
                    runCatching {
                        NPLogger.d(
                            TAG,
                            "下载取消后回滚半成品: song=${song.name}, " +
                                "audio=${state.storedAudio?.reference}, " +
                                "sidecars=$partialSidecarReferences"
                        )
                        GlobalDownloadManager.rollbackCancelledDownload(
                            context = context,
                            song = song,
                            storedAudio = state.storedAudio,
                            sidecarReferences = partialSidecarReferences,
                            operationId = effectiveOperationId
                        )
                        state.storedAudio = null
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
                !preserveCancellationArtifacts &&
                    deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
            ) {
                state.tempFile = null
            }
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = effectiveOperationId
            )
            if (!preserveCancellationArtifacts) {
                clearSongCancelled(songKey)
            }
            clearCompletedAudioReference(songKey, operationId = effectiveOperationId)
            clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
            throw java.util.concurrent.CancellationException(
                if (preserveCancellationArtifacts) {
                    "Download cancellation deferred for recovery"
                } else {
                    "Download cancelled"
                }
            )
        }
        clearPartialSidecarReferences(songKey, operationId = effectiveOperationId)
        if (
            state.storedAudio == null &&
                state.attemptNumber < TRANSIENT_DOWNLOAD_MAX_ATTEMPTS &&
                shouldRetryDownloadFailureForSource(error, isYouTubeMusic)
        ) {
            val partialBytes = resolveWorkingFileBytes(state.tempFile)
            val preservePartial = shouldPreservePartialDownloadForRetry(
                transportKind = state.activeTransportKind,
                existingBytes = partialBytes,
                hasHlsResumeState = hasHlsResumeState(state.tempFile)
            ) && state.resumeMetadataAvailable
            if (
                !preservePartial &&
                    deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)
            ) {
                state.tempFile = null
            }
            val retryDelayMs = resolveTransientDownloadRetryDelayMs(state.attemptNumber)
            publishRetryWaitingProgress(
                songId = song.id,
                songKey = songKey,
                fileName = state.activeWorkingFileName
                    ?: ManagedDownloadStorage.buildDisplayBaseName(song),
                bytesRead = if (preservePartial) partialBytes else 0L,
                totalBytes = progressStore.currentProgress()
                    ?.takeIf { it.songKey == songKey }
                    ?.totalBytes
                    ?: 0L,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            if (isYouTubeMusic && shouldRefreshYouTubeDownloadSourceOnFailure(error)) {
                state.forceRefreshYouTubeSource = true
                if (isForbiddenYouTubeDownloadFailure(error)) {
                    state.avoidYouTubeDirectSource = true
                }
            }
            NPLogger.w(
                TAG,
                "下载遇到网络波动，准备重试(${state.attemptNumber}/$TRANSIENT_DOWNLOAD_MAX_ATTEMPTS): " +
                    "${song.name}, refreshYouTubeSource=${state.forceRefreshYouTubeSource}, " +
                    "${error.javaClass.simpleName} - ${error.message}"
            )
            evictDownloadConnections()
            waitForRetryOrCancellation(
                context = context,
                songKey = songKey,
                delayMs = retryDelayMs,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = effectiveOperationId
            )
            state.attemptNumber++
            return DownloadAttemptFailureAction.RETRY
        }
        if (deleteWorkingFileUnlessNetworkPolicyPaused(songKey, state.tempFile)) {
            state.tempFile = null
        }
        NPLogger.e(
            TAG,
            "下载失败: ${song.name}, 错误: ${error.javaClass.simpleName} - ${error.message}",
            error
        )
        throw error
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
        effectiveOperationId: String,
        transferGeneration: Long
    ): DownloadedPayloadSummary {
        val client = backgroundDownloadClient
        return when (transportKind) {
            DownloadTransportKind.HLS -> hlsTransfer.download(
                client = client,
                playlistRequest = request,
                destFile = workingFile,
                displayFileName = fileName,
                songId = workingSong.id,
                songKey = workingSong.stableKey(),
                totalBytesHint = resolved.contentLength ?: 0L,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = effectiveOperationId,
                transferGeneration = transferGeneration
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
                attemptId = attemptId,
                operationId = effectiveOperationId,
                transferGeneration = transferGeneration
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
        publishStageProgress(
            songId = workingSong.id,
            songKey = songKey,
            fileName = fileName,
            stage = DownloadStage.VERIFYING_AUDIO,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            bytesRead = workingFile.length().coerceAtLeast(0L),
            totalBytes = payloadSummary.expectedBytes ?: workingFile.length().coerceAtLeast(0L)
        )
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "audio_finalize_prepare",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
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
            attemptId = attemptId,
            operationId = effectiveOperationId
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
            attemptId = attemptId,
            operationId = effectiveOperationId
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
            attemptId = attemptId,
            operationId = effectiveOperationId
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
        publishStageProgress(
            songId = workingSong.id,
            songKey = workingSong.stableKey(),
            fileName = fileName,
            stage = DownloadStage.COMMITTING_CORE,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            bytesRead = transferredBytes,
            totalBytes = transferredBytes
        )
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "audio_commit",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = effectiveOperationId
        )
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
        val coreMarkerOwned = operationRegistry.markCoreCommittedIfOwned(
            songKey = songKey,
            operationId = effectiveOperationId,
            attemptId = attemptId
        )
        if (!coreMarkerOwned) {
            NPLogger.d(
                TAG,
                "core 提交后发现 operation 引用已被撤销，不重新打开宿主保护: " +
                    "song=${workingSong.name}, operationId=$effectiveOperationId"
            )
        }
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
                "音频 core 已提交，交由发布阶段提升为正式文件: " +
                    "song=${workingSong.name}, file=${committedAudio.name}"
            )
        }
        if (coreOperationMarked) {
            ManagedDownloadStorage.deleteWorkingResumeMetadata(workingFile)
        }
        publishStageProgress(
            songId = workingSong.id,
            songKey = songKey,
            fileName = fileName,
            stage = DownloadStage.ASSETS_ENRICHING,
            attemptId = attemptId,
            operationId = effectiveOperationId,
            bytesRead = transferredBytes,
            totalBytes = transferredBytes
        )
        return CoreCommittedAudio(
            audio = committedAudio,
            transferredBytes = transferredBytes,
            operationCoreCommitted = coreOperationMarked
        )
        } finally {
            directoryCommitLease.close()
        }
    }

    internal suspend fun downloadSidecarsForCompletedAudio(
        context: Context,
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        operationId: String? = null,
        stageObserver: ((DownloadedSidecarStage, Boolean) -> Unit)? = null
    ): DownloadedSidecarReferences = withContext(Dispatchers.IO) {
        val songKey = song.stableKey()
        val normalizedOperationId = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val referenceLeaseAcquired = normalizedOperationId?.let { id ->
            claimReferenceOwnershipForEnrichment(songKey, id)
        } ?: true
        if (!referenceLeaseAcquired) {
            NPLogger.d(
                TAG,
                "sidecar 整理发现歌曲已归属更新代次，跳过旧 operation: " +
                    "song=${song.name}, operationId=$normalizedOperationId"
            )
            throw CancellationException("Stale download operation cannot enrich sidecars")
        }
        try {
            val expectedCover = buildCoverDownloadCandidateUrls(song).isNotEmpty()
            clearPartialSidecarReferences(
                songKey = songKey,
                operationId = normalizedOperationId
            )
            val downloadedReferences = runCatching {
                downloadSidecars(
                    context = context,
                    song = song,
                    songKey = songKey,
                    baseName = storedAudio.nameWithoutExtension,
                    storedAudio = storedAudio,
                    requireActiveAttempt = false,
                    operationId = normalizedOperationId,
                    stageObserver = stageObserver
                )
            }.getOrElse { error ->
                if (error is CancellationException) {
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
            val createdReferences = consumePartialSidecarReferences(
                songKey = songKey,
                operationId = normalizedOperationId
            )?.retainCreatedOnly()
            mergeDownloadedSidecarReferences(downloadedReferences, createdReferences)
        } finally {
            normalizedOperationId?.takeIf { referenceLeaseAcquired }?.let { id ->
                releaseReferenceOwnership(songKey, id)
            }
        }
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

    /**
     * 一次 transfer cycle 持有网络槽位直到最小 Core Commit 完成
     *
     * 网络 I/O 结束时只关闭 active 标记，permit 本身由调用方的 Core Commit 继续持有
     */
    private suspend fun <T> withTransferCyclePermit(
        context: Context,
        ownerKey: String,
        traceToken: DownloadOperationTraceToken?,
        operationId: String? = null,
        attemptId: Long? = null,
        block: suspend (
            permit: DownloadTransferPermitRegistry.Permit,
            markNetworkFinished: () -> Unit,
            transferOwnerToken: Long?
        ) -> T
    ): T {
        val configured = currentDownloadParallelismSnapshot(context)
        transferPermitRegistry.updateConfiguredParallelism(
            requestedParallelism = configured.value,
            reason = "user_setting",
            configurationRevision = configured.revision
        )
        val permit = transferPermitRegistry.acquire(
            ownerKey = ownerKey
        )
        var networkFinished = false
        fun markNetworkFinished() {
            if (networkFinished) return
            permit.markNetworkIoFinished()
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.NETWORK_FINISHED
            )
            networkFinished = true
        }
        try {
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.NETWORK_PERMIT_GRANTED
            )
            val transferOwnerToken = operationId?.let { normalizedOperationId ->
                DownloadExecutionHosts.onTransferStarted(
                    context = context.applicationContext,
                    operationId = normalizedOperationId,
                    attemptId = attemptId
                )
            }
            if (operationId != null && transferOwnerToken == null) {
                throw DownloadTransferAdmissionDeferredException(
                    operationId = operationId,
                    attemptId = attemptId
                )
            }
            permit.markNetworkIoStarted()
            DownloadOperationTrace.mark(
                traceToken,
                DownloadOperationTracePhase.NETWORK_STARTED
            )
            DownloadStartupTrace.markTransferStarted()
            return block(permit, ::markNetworkFinished, transferOwnerToken)
        } finally {
            withContext(NonCancellable) {
                markNetworkFinished()
                permit.release()
                DownloadOperationTrace.mark(
                    traceToken,
                    DownloadOperationTracePhase.NETWORK_PERMIT_RELEASED
                )
            }
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
        requireActiveAttempt: Boolean = true,
        operationId: String? = null,
        stageObserver: ((DownloadedSidecarStage, Boolean) -> Unit)? = null
    ): DownloadedSidecarReferences {
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "sidecar_prepare",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId,
            requireActiveAttempt = requireActiveAttempt
        )
        val useSequentialSidecarWrites = ManagedDownloadStorage.usesDocumentTree(context)
        val expectedCover = buildCoverDownloadCandidateUrls(song).isNotEmpty()
        val allowIndexedSidecarLookup = shouldUseIndexedSidecarLookup(
            usesDocumentTree = useSequentialSidecarWrites,
            allowSlowLookup = true
        )
        val references = if (useSequentialSidecarWrites) {
            val lyricReferences = observeSidecarStage(
                stage = DownloadedSidecarStage.LYRICS,
                observer = stageObserver
            ) {
                downloadLyrics(
                    context = context,
                    song = song,
                    songKey = songKey,
                    baseName = baseName,
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    requireActiveAttempt = requireActiveAttempt,
                    operationId = operationId
                )
            }
            val cachedCover = observeSidecarStage(
                stage = DownloadedSidecarStage.COVER,
                observer = stageObserver
            ) {
                cacheCover(
                    context = context,
                    song = song,
                    songKey = songKey,
                    baseName = baseName,
                    storedAudio = storedAudio,
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    requireActiveAttempt = requireActiveAttempt,
                    allowIndexedLookup = allowIndexedSidecarLookup,
                    operationId = operationId
                )
            }
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
                    observeSidecarStage(
                        stage = DownloadedSidecarStage.LYRICS,
                        observer = stageObserver
                    ) {
                        downloadLyrics(
                            context = context,
                            song = song,
                            songKey = songKey,
                            baseName = baseName,
                            serializeWrites = false,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId,
                            requireActiveAttempt = requireActiveAttempt,
                            operationId = operationId
                        )
                    }
                }
                val coverJob = async {
                    observeSidecarStage(
                        stage = DownloadedSidecarStage.COVER,
                        observer = stageObserver
                    ) {
                        cacheCover(
                            context = context,
                            song = song,
                            songKey = songKey,
                            baseName = baseName,
                            storedAudio = storedAudio,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId,
                            requireActiveAttempt = requireActiveAttempt,
                            allowIndexedLookup = allowIndexedSidecarLookup,
                            operationId = operationId
                        )
                    }
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
            completedAudioReferenceRegistry.peekPartialSidecarReferences(songKey)
                ?.retainCreatedOnly()
        )
    }

    private suspend fun <T> observeSidecarStage(
        stage: DownloadedSidecarStage,
        observer: ((DownloadedSidecarStage, Boolean) -> Unit)?,
        block: suspend () -> T
    ): T {
        runCatching { observer?.invoke(stage, true) }
        return try {
            block()
        } finally {
            runCatching { observer?.invoke(stage, false) }
        }
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
        allowIndexedLookup: Boolean = true,
        operationId: String? = null
    ): AudioCachedCoverReference? = coverCoordinator.cacheCover(
        context = context,
        song = song,
        songKey = songKey,
        baseName = baseName,
        storedAudio = storedAudio,
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        requireActiveAttempt = requireActiveAttempt,
        allowIndexedLookup = allowIndexedLookup,
        operationId = operationId
    )

    internal fun buildCoverSidecarFileName(baseName: String, songKey: String): String {
        return AudioDownloadCoverCoordinator.buildCoverSidecarFileName(baseName, songKey)
    }

    internal fun readCoverResponseBytes(
        input: InputStream,
        declaredLength: Long
    ): ByteArray = AudioDownloadCoverCoordinator.readCoverResponseBytes(
        input = input,
        declaredLength = declaredLength,
        maxResponseBytes = MAX_COVER_RESPONSE_BYTES
    )

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
    ) = batchCoordinator.downloadPlaylist(
        context = context,
        songs = songs,
        maxConcurrentDownloads = maxConcurrentDownloads,
        songAttemptIds = songAttemptIds,
        onSongStarted = onSongStarted,
        onSongCompleted = onSongCompleted,
        onSongFailed = onSongFailed,
        onSongCancelled = onSongCancelled,
        onSongPausedForNetworkPolicy = onSongPausedForNetworkPolicy
    )

    /** 取消下载 */
    fun cancelSongDownload(songKey: String) {
        val calls = operationRegistry.withMutationLock {
            operationRegistry.removeNetworkPolicyPaused(songKey)
            val operationIds = activeOperationIdsForSongLocked(songKey)
            operationIds.forEach(operationRegistry::clearCoreCommitted)
            operationRegistry.markExecutionHostPaused(operationIds)
            operationRegistry.revokeReference(songKey, operationIds)
            operationRegistry.clearInactiveExecutionHostPausesExcept(operationIds)
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
        val calls = operationRegistry.withMutationLock {
            operationRegistry.clearNetworkPolicyPaused()
            val operationIds = operationRegistry.activeOperationIds()
            operationIds.forEach(operationRegistry::clearCoreCommitted)
            operationRegistry.markExecutionHostPaused(operationIds)
            operationRegistry.revokeAllReferences()
            snapshotActiveCalls()
        }
        _isCancelled.value = true
        invalidateBatchSession()
        calls.forEach { call ->
            call.cancel()
        }
        progressStore.clearVisibleProgress()
        progressStore.clearBatchProgress()
        clearAllPublishedProgress()
    }

    fun pauseDownloadsForNetworkPolicy(songKeys: Collection<String>) {
        val normalizedKeys = songKeys
            .mapNotNull { it.takeIf(String::isNotBlank) }
            .distinct()
        if (normalizedKeys.isEmpty()) {
            return
        }
        val calls = operationRegistry.withMutationLock {
            operationRegistry.addNetworkPolicyPaused(normalizedKeys)
            normalizedKeys.forEach { songKey ->
                val operationIds = activeOperationIdsForSongLocked(songKey)
                operationRegistry.markExecutionHostPaused(operationIds)
                operationRegistry.revokeReference(songKey, operationIds)
            }
            normalizedKeys.flatMap(::snapshotActiveCalls).distinct()
        }
        calls.forEach { call -> call.cancel() }
        progressStore.currentProgress()?.songKey
            ?.takeIf(normalizedKeys::contains)
            ?.let(::clearVisibleProgressForSong)
        normalizedKeys.forEach(::clearPublishedProgress)
    }

    /** 系统执行宿主被外部停止时保留工作文件，供后续恢复 */
    fun pauseSongDownloadForExecutionHost(songKey: String) {
        val normalizedKey = songKey.takeIf(String::isNotBlank) ?: return
        val calls = operationRegistry.withMutationLock {
            operationRegistry.addNetworkPolicyPaused(setOf(normalizedKey))
            val operationIds = activeOperationIdsForSongLocked(normalizedKey)
            operationRegistry.markExecutionHostPaused(operationIds)
            operationRegistry.revokeReference(normalizedKey, operationIds)
            snapshotActiveCalls(normalizedKey)
        }
        calls.forEach { call ->
            call.cancel()
        }
        clearPublishedProgress(normalizedKey)
        clearVisibleProgressForSong(normalizedKey)
    }

    fun isDownloadPausedForNetworkPolicy(songKey: String): Boolean {
        return operationRegistry.isNetworkPolicyPaused(songKey)
    }

    /** 重置取消标志 */
    fun resetCancelFlag() {
        _isCancelled.value = false
    }

    fun clearNetworkPolicyPause(songKeys: Collection<String>) {
        operationRegistry.removeNetworkPolicyPaused(songKeys)
    }

    internal fun resolveTransientDownloadRetryDelayMs(attemptNumber: Int): Long =
        AudioDownloadTransferPolicy.resolveTransientDownloadRetryDelayMs(attemptNumber)

    internal fun shouldRetryTransientDownloadFailure(error: Throwable): Boolean =
        AudioDownloadTransferPolicy.shouldRetryTransientDownloadFailure(error)

    internal fun shouldRetryDownloadFailureForSource(
        error: Throwable,
        isYouTubeMusic: Boolean
    ): Boolean = AudioDownloadTransferPolicy.shouldRetryDownloadFailureForSource(
        error,
        isYouTubeMusic
    )

    internal fun shouldRefreshYouTubeDownloadSourceOnFailure(error: Throwable): Boolean =
        AudioDownloadTransferPolicy.shouldRefreshYouTubeDownloadSourceOnFailure(error)

    internal fun isForbiddenYouTubeDownloadFailure(error: Throwable): Boolean =
        AudioDownloadTransferPolicy.isForbiddenYouTubeDownloadFailure(error)

    private suspend fun waitForRetryOrCancellation(
        context: Context,
        songKey: String,
        delayMs: Long,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        operationId: String? = null
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
            ensureSongDownloadNotCancelled(
                songKey = songKey,
                stage = "retry_wait",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId
            )
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
        ensureSongDownloadNotCancelled(
            songKey = songKey,
            stage = "retry_wait",
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId
        )
    }

    internal fun clampBatchDownloadParallelism(requestedParallelism: Int): Int {
        return normalizeDownloadParallelism(requestedParallelism)
    }

    internal suspend fun resolveConfiguredDownloadParallelism(context: Context): Int {
        return currentDownloadParallelism(context)
    }

    internal fun onConfiguredDownloadParallelismChanged(
        configuredValue: Int,
        configurationRevision: Long? = null
    ) {
        transferPermitRegistry.updateConfiguredParallelism(
            requestedParallelism = configuredValue,
            reason = "user_setting",
            configurationRevision = configurationRevision
        )
        GlobalDownloadManager.wakeDownloadExecutionPumpAfterParallelismChanged(
            AppContainer.applicationContext
        )
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

    private fun hasFastCachedManagedDownloadForStart(
        context: Context,
        song: SongItem
    ): Boolean = playbackCoordinator.hasFastCachedManagedDownloadForStart(context, song)

    internal fun resolveLocalLyricForDownload(rawLyric: String?): String? {
        return AudioDownloadLyricsCoordinator.resolveLocalLyric(rawLyric)
    }

    internal fun shouldFetchRemoteLyricForDownload(rawLyric: String?): Boolean {
        return AudioDownloadLyricsCoordinator.shouldFetchRemoteLyric(rawLyric)
    }

    internal fun shouldFetchRomanizedLyricForDownload(
        shouldFetchPrimaryLyric: Boolean,
        shouldFetchTranslatedLyric: Boolean
    ): Boolean {
        return AudioDownloadLyricsCoordinator.shouldFetchRomanizedLyric(
            shouldFetchPrimaryLyric,
            shouldFetchTranslatedLyric
        )
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
        requireActiveAttempt: Boolean = true,
        operationId: String? = null
    ): DownloadedSidecarReferences {
        return AudioDownloadLyricsCoordinator.download(
            context = context,
            song = song,
            songKey = songKey,
            baseName = baseName,
            serializeWrites = serializeWrites,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            requireActiveAttempt = requireActiveAttempt,
            operationId = operationId,
            ensureNotCancelled = { key, stage, session, attempt, active, operation ->
                ensureSongDownloadNotCancelled(
                    songKey = key,
                    stage = stage,
                    batchSessionId = session,
                    attemptId = attempt,
                    operationId = operation,
                    requireActiveAttempt = active
                )
            },
            writeSidecar = { key, stage, session, attempt, active, operation, block ->
                withNetworkPolicyMutationPermit(
                    songKey = key,
                    stage = stage,
                    batchSessionId = session,
                    attemptId = attempt,
                    operationId = operation,
                    requireActiveAttempt = active,
                    block = block
                )
            },
            rememberPartial = { key, partialOperationId, references ->
                rememberPartialSidecarReferences(
                    songKey = key,
                    sidecarReferences = references,
                    operationId = partialOperationId
                )
            }
        )
    }

    fun getLocalPlaybackUri(context: Context, song: SongItem): String? =
        playbackCoordinator.getLocalPlaybackUri(context, song)

    suspend fun resolvePermittedLocalPlaybackUri(
        context: Context,
        song: SongItem,
        rawLocalReference: String?
    ): String? = playbackCoordinator.resolvePermittedLocalPlaybackUri(
        context = context,
        song = song,
        rawLocalReference = rawLocalReference
    )

    internal suspend fun resolvePermittedLocalPlayback(
        context: Context,
        song: SongItem,
        rawLocalReference: String?
    ): LocalPlaybackReferenceResolution = playbackCoordinator.resolvePermittedLocalPlayback(
        context = context,
        song = song,
        rawLocalReference = rawLocalReference
    )

    internal fun resolveIndexedLocalPlaybackReference(
        context: Context,
        song: SongItem
    ): LocalPlaybackReferenceResolution = playbackCoordinator.resolveIndexedLocalPlaybackReference(
        context = context,
        song = song
    )

    fun mayHaveIndexedLocalDownload(context: Context, song: SongItem): Boolean =
        playbackCoordinator.mayHaveIndexedLocalDownload(context, song)

    fun hasLocalDownload(context: Context, song: SongItem): Boolean =
        playbackCoordinator.hasLocalDownload(context, song)

    /**
     * 只读取已驻留下载索引，供滚动列表恢复已有封面，避免触发目录扫描
     */
    fun peekLocalCoverUri(song: SongItem): String? =
        playbackCoordinator.peekLocalCoverUri(song)

    /** 解析下载歌曲对应的本地封面, 供离线 UI 兜底使用 */
    fun getLocalCoverUri(
        context: Context,
        song: SongItem,
        resolveLocalMediaFallback: Boolean = true
    ): String? = playbackCoordinator.getLocalCoverUri(
        context = context,
        song = song,
        resolveLocalMediaFallback = resolveLocalMediaFallback
    )

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
    ): ResolvedDownloadSource? = AudioDownloadSourceResolver.resolveNetease(
        songId = songId,
        preferredQuality = preferredQuality
    )

    private suspend fun resolveYouTubeMusic(
        song: SongItem,
        preferredQuality: String,
        forceRefresh: Boolean = false,
        avoidDirect: Boolean = false
    ): ResolvedDownloadSource? = AudioDownloadSourceResolver.resolveYouTubeMusic(
        song = song,
        preferredQuality = preferredQuality,
        forceRefresh = forceRefresh,
        avoidDirect = avoidDirect
    )

    private suspend fun resolveYouTubeMusicDownloadAudio(
        videoId: String,
        attempt: YouTubeDownloadResolveAttempt,
        preferredQuality: String,
        avoidDirect: Boolean = false
    ): YouTubePlayableAudio? = AudioDownloadSourceResolver.resolveYouTubeMusicDownloadAudioForManager(
        videoId = videoId,
        attempt = attempt,
        preferredQuality = preferredQuality,
        avoidDirect = avoidDirect
    )

    // 解析 B 站音频直链
    private suspend fun resolveBili(
        song: SongItem,
        preferredQuality: String
    ): ResolvedDownloadSource? = AudioDownloadSourceResolver.resolveBili(
        song = song,
        preferredQuality = preferredQuality
    )

    private fun ensureHttps(url: String): String = AudioDownloadSourceResolver.ensureHttps(url)

    private fun mimeToExt(mime: String): String? = AudioDownloadSourceResolver.mimeToExt(mime)

    private fun guessMimeFromUrl(url: String): String? =
        AudioDownloadSourceResolver.guessMimeFromUrl(url)

    private fun extFromUrl(url: String): String? = AudioDownloadSourceResolver.extFromUrl(url)

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
        operationId: String = "",
        transferGeneration: Long? = null
    ): DownloadedPayloadSummary = hlsTransfer.download(
        client = client,
        playlistRequest = playlistRequest,
        destFile = destFile,
        displayFileName = displayFileName,
        songId = songId,
        songKey = songKey,
        totalBytesHint = totalBytesHint,
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = operationId,
        transferGeneration = transferGeneration
    )

    private fun parseHlsSegmentUrls(playlistUrl: String, playlistText: String): List<String> {
        return AudioHlsSegmentSupport.parseSegmentUrls(playlistUrl, playlistText)
    }

    private fun parseHlsMediaSequence(playlistText: String): Long? {
        return AudioHlsSegmentSupport.parseMediaSequence(playlistText)
    }

    internal fun copyHlsSegment(
        source: BufferedSource,
        sink: okio.BufferedSink,
        trafficAccumulator: TrafficByteAccumulator,
        prefixDigest: MessageDigest? = null,
        expectedRawBytes: Long? = null,
        onNetworkActivity: (() -> Unit)? = null
    ): Long {
        return AudioHlsSegmentSupport.copySegment(
            source = source,
            sink = sink,
            trafficAccumulator = trafficAccumulator,
            maxSegmentBytes = MAX_HLS_SEGMENT_BYTES,
            readBufferBytes = DOWNLOAD_READ_BUFFER_BYTES.toInt(),
            prefixDigest = prefixDigest,
            expectedRawBytes = expectedRawBytes,
            onNetworkActivity = onNetworkActivity
        )
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
        attemptId: Long? = null,
        operationId: String? = null,
        transferGeneration: Long? = null
    ): DownloadedPayloadSummary = fileTransfer.download(
        client = client,
        request = request,
        destFile = destFile,
        displayFileName = displayFileName,
        songId = songId,
        songKey = songKey,
        batchSessionId = batchSessionId,
        attemptId = attemptId,
        operationId = operationId,
        transferGeneration = transferGeneration
    )

    private fun ensureDownloadNotCancelled(
        songId: Long,
        songKey: String,
        destFile: File,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        operationId: String? = null
    ) {
        val shouldAbort = operationRegistry.withMutationLock {
            val normalizedOperationId = operationId
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val clearFenceAllowsWork = !isDownloadClearFenceBlockingWork(
                songKey = songKey,
                operationId = normalizedOperationId
            )
            val operationAllowsWork = normalizedOperationId?.let { id ->
                operationRegistry.allowsReference(songKey, id) &&
                    !operationRegistry.isExecutionHostPaused(id) &&
                    clearFenceAllowsWork
            } ?: clearFenceAllowsWork
            shouldAbortDownloadWork(
                allDownloadsCancelled = _isCancelled.value,
                batchSessionCurrent = isBatchSessionCurrent(batchSessionId),
                songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey),
                attemptAllowsWork = GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId),
                operationAllowsWork = operationAllowsWork
            )
        }
        if (shouldAbort) {
            NPLogger.d(TAG, "下载被取消，停止分块下载: songId=$songId")
            val preserveCancellationArtifacts =
                shouldPreserveWorkingArtifactsAfterCancellation(
                    cancellation = true,
                    allDownloadsCancelled = _isCancelled.value,
                    songCancelled = GlobalDownloadManager.isSongCancelled(songKey),
                    networkPolicyPaused = shouldPreserveArtifactsForNetworkPolicy(songKey)
                )
            if (!preserveCancellationArtifacts) {
                deleteWorkingFileUnlessNetworkPolicyPaused(songKey, destFile)
            }
            clearVisibleProgressForSong(
                songKey = songKey,
                expectedAttemptId = attemptId,
                expectedOperationId = operationId
            )
            throw java.util.concurrent.CancellationException("Download cancelled")
        }
    }
}
