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
import moe.ouom.neriplayer.core.download.policy.DownloadCoreCommitPhase
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
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.host.DownloadTransferAdmissionDeferredException
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.state.isPostCoreDownloadOperationState
import moe.ouom.neriplayer.core.download.policy.shouldUseIndexedSidecarLookup
import moe.ouom.neriplayer.core.download.policy.shouldRollbackCancelledAudio
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

    internal const val TAG = "NERI-Downloader"
    internal const val DOWNLOAD_NETWORK_POLICY_PREFS = "download_network_policy_state"
    internal const val NETWORK_GENERATION_PREF = "network_generation"
    internal val SHA256_HEX_REGEX = Regex("[0-9a-fA-F]{64}")
    internal const val BILI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    internal const val BILI_REFERER = "https://www.bilibili.com"
    internal const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = DEFAULT_DOWNLOAD_PARALLELISM
    internal const val MAX_CONCURRENT_DOWNLOADS_LIMIT = MAX_DOWNLOAD_PARALLELISM
    internal const val SOURCE_RESOLVE_PARALLELISM = 10
    internal const val CORE_COMMIT_PARALLELISM = 2
    internal const val BATCH_COMPLETION_CALLBACK_PARALLELISM = 2
    internal const val DURABLE_CHECKPOINT_INTERVAL_BYTES = 1L * 1024L * 1024L
    internal const val DURABLE_CHECKPOINT_INTERVAL_NS = 500_000_000L
    internal const val PROGRESS_EVENT_BUFFER_CAPACITY = 64
    internal const val DOWNLOAD_TRAFFIC_FLUSH_BYTES = 512L * 1024L
    internal const val TRANSIENT_DOWNLOAD_MAX_ATTEMPTS = 6
    internal const val TRANSIENT_DOWNLOAD_OFFLINE_RECOVERY_WAIT_MS = 12_000L
    internal const val TRANSIENT_DOWNLOAD_NETWORK_SETTLE_MS = 750L
    internal const val DOWNLOAD_RETRY_POLL_SLICE_MS = 250L
    internal const val STORAGE_SPACE_CONTENTION_RETRY_DELAY_MS = 750L
    internal const val DOWNLOAD_CLIENT_MAX_REQUESTS = 24
    internal const val RECOVERY_OPPORTUNITY_COOLDOWN_MS = 2_500L
    internal const val DOWNLOAD_CLIENT_MAX_REQUESTS_PER_HOST = 12
    internal const val DOWNLOAD_CLIENT_CONNECT_TIMEOUT_MS = 20_000L
    internal const val DOWNLOAD_CLIENT_READ_TIMEOUT_MS = 45_000L
    internal const val DOWNLOAD_CLIENT_WRITE_TIMEOUT_MS = 45_000L
    internal const val COVER_DOWNLOAD_MAX_ATTEMPTS = 3
    internal const val COVER_DOWNLOAD_RETRY_DELAY_MS = 250L
    /** core 提交和目录索引发布之间允许播放入口复用已校验引用的最长时间 */
    internal const val COMPLETED_AUDIO_REFERENCE_RETENTION_MS = 2 * 60 * 1_000L
    internal const val COMPLETED_AUDIO_REFERENCE_MAX_ENTRIES = 512
    internal const val DOWNLOAD_READ_BUFFER_BYTES = 64L * 1024L
    internal const val YOUTUBE_DOWNLOAD_PREFERRED_CHUNK_SIZE_BYTES = 4L * 1024L * 1024L
    internal const val MAX_HLS_PLAYLIST_BYTES = 1L * 1024L * 1024L
    internal const val MAX_HLS_SEGMENT_BYTES = 64L * 1024L * 1024L
    internal const val MAX_COVER_RESPONSE_BYTES = MAX_SOURCE_COVER_BYTES

    internal val backgroundDownloadClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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

    internal val progressStore = AudioDownloadProgressStore(PROGRESS_EVENT_BUFFER_CAPACITY)
    val progressFlow: StateFlow<DownloadProgress?> = progressStore.progressFlow
    val progressEvents: SharedFlow<DownloadProgress> = progressStore.progressEvents
    val batchProgressFlow: StateFlow<BatchDownloadProgress?> = progressStore.batchProgressFlow

    // 取消下载控制
    internal val _isCancelled = MutableStateFlow(false)
    internal val sourceResolveSemaphore = Semaphore(SOURCE_RESOLVE_PARALLELISM)
    internal val coreCommitSemaphore = Semaphore(CORE_COMMIT_PARALLELISM)
    internal val transferPermitRegistry = DownloadTransferPermitRegistry(
        maxParallelism = MAX_CONCURRENT_DOWNLOADS_LIMIT
    )
    internal val transferWatchdog = DownloadTransferWatchdog(transferPermitRegistry)

    /** 暴露传输槽位快照，供恢复诊断区分排队、真实 I/O 和无进展任务 */
    internal fun transferPermitSnapshot(): DownloadTransferPermitRegistry.Snapshot {
        return transferPermitRegistry.snapshot()
    }

    internal fun promoteWaitingTransferForManualRetry(
        operationId: String
    ): Boolean {
        return transferPermitRegistry.promoteWaitingOperation(operationId)
    }

    /** 保留节流或事件缓冲丢弃前的最新值，供恢复绑定时补偿 */
    internal val latestProgressByOperation: StateFlow<Map<String, DownloadProgress>> =
        progressStore.latestProgressByOperation
    /** 增量进度事件供批量和全局投影消费，避免反复遍历全量快照 */
    internal val latestProgressEvents: SharedFlow<DownloadProgress> =
        progressStore.latestProgressEvents
    internal val completedAudioReferenceRegistry = AudioDownloadReferenceRegistry(
        retentionMs = COMPLETED_AUDIO_REFERENCE_RETENTION_MS,
        maxEntries = COMPLETED_AUDIO_REFERENCE_MAX_ENTRIES
    )
    internal val referenceOwnership = AudioDownloadReferenceOwnership()
    internal val managedPlaybackRebindAtMsBySongKey = ConcurrentHashMap<String, Long>()
    internal val coverCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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
    internal val hlsResumeStore = AudioHlsResumeStore(
        checkpointFileFor = ManagedDownloadStorage::buildWorkingHlsCheckpointFile,
        readBufferBytes = DOWNLOAD_READ_BUFFER_BYTES.toInt()
    )
    internal val hlsTransfer = AudioDownloadHlsTransfer(
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
    internal val fileTransfer = AudioDownloadFileTransfer(
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
    internal val playbackCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioDownloadPlaybackCoordinator(
            completedAudioReferenceRegistry = completedAudioReferenceRegistry,
            isSongDownloadActive = ::isSongDownloadActive,
            directoryMutationWaitMs = 1_200L,
            tag = TAG
        )
    }
    internal val batchCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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
    internal val retryWakeSignalVersion = MutableStateFlow(0L)
    /** operation 注册表同时保护生命周期、暂停标记和活动网络调用 */
    internal val operationRegistry = AudioDownloadOperationRegistry(referenceOwnership)
    internal val networkRecoveryMonitorLock = Any()
    internal var lastConfirmedInternetAccess = false

    @Volatile
    internal var lastRecoveryOpportunityAtMs = 0L



    @Volatile
    internal var networkRecoveryMonitorRegistered = false

    internal val downloadNetworkPolicyTracker = DownloadNetworkPolicyTracker()

    fun isSongDownloadActive(songKey: String): Boolean {
        return operationRegistry.isSongDownloadActive(songKey)
    }

    internal fun isOperationDownloadActive(operationId: String): Boolean {
        return operationRegistry.isOperationDownloadActive(operationId)
    }

    internal fun currentDownloadNetworkGeneration(): Long =
        downloadNetworkPolicyTracker.currentGeneration()



    fun initialize(context: Context) {
        return this.initializeImpl(context)
    }












    internal data class ResolvedDownloadSource(
        val url: String,
        val mimeType: String? = null,
        val fileExtensionHint: String? = null,
        val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
        val contentLength: Long? = null,
        val durationMs: Long? = null,
        val contentMd5: String? = null
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
        val durableBytesRead: Long? = null,
        /** 同一进程内的发布顺序，防止补偿快照覆盖较新的增量事件 */
        val publicationSequence: Long = 0L
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

    internal class DownloadCoreCommitTracker(
        var phase: DownloadCoreCommitPhase = DownloadCoreCommitPhase.STAGING
    )

    /** 保存一次 operation 的可恢复状态，避免把大量局部变量塞进单个状态机 */
    internal class DownloadExecutionAttemptState(
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

    internal data class PreparedDownloadAttempt(
        val resolved: ResolvedDownloadSource,
        val workingSong: SongItem,
        val request: Request,
        val transportKind: DownloadTransportKind,
        val fileName: String,
        val mimeType: String?,
        val workingFile: File
    )

    internal enum class DownloadAttemptFailureAction {
        RETRY
    }

    internal data class CoreCommittedAudio(
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

    internal fun responseHeaderValue(
        headers: Map<String, List<String>>,
        name: String
    ): String? = AudioDownloadTransferPolicy.responseHeaderValue(headers, name)

    internal fun updateWorkingResumeFingerprint(
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
        return this.notifyRecoveryOpportunityImpl(reason)
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
        return this.isHlsResumeStateCompatibleImpl(state, actualFileLength, actualPrefixSha256, segmentCount)
    }


    internal fun isHlsResumeStateOwnedByOperation(
        state: HlsResumeState,
        operationId: String
    ): Boolean {
        return hlsResumeStore.isOwnedByOperation(state, operationId)
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

    internal fun clearVisibleProgressForSong(
        songKey: String,
        expectedAttemptId: Long? = null,
        expectedOperationId: String? = null
    ) = progressStore.clearVisibleProgressForSong(
        songKey = songKey,
        expectedAttemptId = expectedAttemptId,
        expectedOperationId = expectedOperationId
    )

    internal fun clearAllPublishedProgress() = progressStore.clearAllPublished()



    /** 网络仍在读取但暂时没有形成进度事件时，单独刷新传输看门狗心跳 */


    internal fun startBatchSession(): Long = progressStore.startBatchSession()

    internal fun invalidateBatchSession() = progressStore.invalidateBatchSession()

    internal fun isBatchSessionCurrent(batchSessionId: Long?): Boolean =
        progressStore.isBatchSessionCurrent(batchSessionId)

    internal fun finishBatchSession(batchSessionId: Long) =
        progressStore.finishBatchSession(batchSessionId)

    internal fun updateBatchProgressForSession(
        batchSessionId: Long,
        progress: BatchDownloadProgress?
    ) = progressStore.updateBatchProgressForSession(batchSessionId, progress)





    internal fun claimReferenceOwnershipForEnrichment(
        songKey: String,
        operationId: String
    ): Boolean = operationRegistry.claimReferenceOwnershipForEnrichment(songKey, operationId)













    /** 只取消指定 operation 的网络调用，保留同一歌曲的新代次 */
    internal fun cancelOperationDownload(
        songKey: String,
        operationIds: Collection<String>
    ): Int {
        return this.cancelOperationDownloadImpl(songKey, operationIds)
    }


    /** 在系统取消协程前建立保留标记，避免取消异常先删除可续传文件 */
    internal fun pauseOperationDownloadForExecutionHost(
        operationId: String,
        durableState: String? = null
    ): Boolean {
        return this.pauseOperationDownloadForExecutionHostImpl(operationId, durableState)
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



    internal fun consumeCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? =
        completedAudioReferenceRegistry.consumeCompletedAudioReference(songKey)

    internal fun releaseCompletedAudioReference(
        songKey: String,
        expectedAudio: ManagedDownloadStorage.StoredEntry? = null,
        retainForPlayback: Boolean = false
    ) {
        return this.releaseCompletedAudioReferenceImpl(songKey, expectedAudio, retainForPlayback)
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



    internal fun rememberPartialSidecarReferences(
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

    internal fun clearCompletedAudioReference(
        songKey: String,
        operationId: String? = null
    ) = operationRegistry.withMutationLock {
        if (operationRegistry.allowsReference(songKey, operationId)) {
            completedAudioReferenceRegistry.clearCompletedAudioReference(songKey)
        }
    }

    internal fun clearPartialSidecarReferences(
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
        return this.publishStageProgressImpl(songId, songKey, fileName, stage, attemptId, operationId, bytesRead, totalBytes)
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
        this.downloadSongImpl(
            context = context,
            song = song,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId,
            downloadAudioQuality = downloadAudioQuality,
            forceFreshTransfer = forceFreshTransfer
        )
    }

    internal suspend fun downloadSongWithResult(
        context: Context,
        song: SongItem,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        operationId: String? = null,
        downloadAudioQuality: DownloadAudioQualitySelection? = null,
        forceFreshTransfer: Boolean = false
    ): ManagedDownloadStorage.StoredEntry? {
        return this.downloadSongImpl(
            context = context,
            song = song,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId,
            downloadAudioQuality = downloadAudioQuality,
            forceFreshTransfer = forceFreshTransfer
        )
    }






    /** 统一处理取消、空间等待和普通失败，避免下载入口生成过大的协程状态机 */

















    internal suspend fun cleanupCancelledPendingArtifactsWithLease(
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






    internal suspend fun cacheCover(
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
        return this.cancelSongDownloadImpl(songKey)
    }


    /** 取消下载 */
    fun cancelDownload() {
        return this.cancelDownloadImpl()
    }


    fun pauseDownloadsForNetworkPolicy(songKeys: Collection<String>) {
        return this.pauseDownloadsForNetworkPolicyImpl(songKeys)
    }


    /** 系统执行宿主被外部停止时保留工作文件，供后续恢复 */
    fun pauseSongDownloadForExecutionHost(songKey: String) {
        return this.pauseSongDownloadForExecutionHostImpl(songKey)
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



    internal fun clampBatchDownloadParallelism(requestedParallelism: Int): Int {
        return normalizeDownloadParallelism(requestedParallelism)
    }

    internal fun onConfiguredDownloadParallelismChanged(
        configuredValue: Int,
        configurationRevision: Long? = null
    ) {
        return this.onConfiguredDownloadParallelismChangedImpl(configuredValue, configurationRevision)
    }


    internal fun resolveBatchDownloadWorkerCount(
        songCount: Int,
        requestedParallelism: Int
    ): Int {
        return this.resolveBatchDownloadWorkerCountImpl(songCount, requestedParallelism)
    }


    internal suspend fun findFastCachedManagedDownloadForStart(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? = playbackCoordinator.findFastCachedManagedDownloadForStart(context, song)

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
        return this.shouldFetchRomanizedLyricForDownloadImpl(shouldFetchPrimaryLyric, shouldFetchTranslatedLyric)
    }


    /** 下载歌词文件 */


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
        return this.getLyricsBundleFastImpl(context, song, allowColdSafProbe)
    }


    fun getTranslatedLyricContent(context: Context, song: SongItem): String? {
        return ManagedDownloadStorage.readLyrics(context, song, translated = true)
    }

    fun getRomanizedLyricContent(context: Context, song: SongItem): String? {
        return ManagedDownloadStorage.readRomanizedLyrics(context, song)
    }

    // 解析网易云直链
    internal suspend fun resolveNetease(
        songId: Long,
        preferredQuality: String
    ): ResolvedDownloadSource? = AudioDownloadSourceResolver.resolveNetease(
        songId = songId,
        preferredQuality = preferredQuality
    )

    internal suspend fun resolveYouTubeMusic(
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

    internal suspend fun resolveYouTubeMusicDownloadAudio(
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
    internal suspend fun resolveBili(
        song: SongItem,
        preferredQuality: String
    ): ResolvedDownloadSource? = AudioDownloadSourceResolver.resolveBili(
        song = song,
        preferredQuality = preferredQuality
    )

    internal fun ensureHttps(url: String): String = AudioDownloadSourceResolver.ensureHttps(url)

    internal fun mimeToExt(mime: String): String? = AudioDownloadSourceResolver.mimeToExt(mime)

    internal fun guessMimeFromUrl(url: String): String? =
        AudioDownloadSourceResolver.guessMimeFromUrl(url)

    internal fun extFromUrl(url: String): String? = AudioDownloadSourceResolver.extFromUrl(url)

    internal fun copyHlsSegment(
        source: BufferedSource,
        sink: okio.BufferedSink,
        trafficAccumulator: TrafficByteAccumulator,
        prefixDigest: MessageDigest? = null,
        expectedRawBytes: Long? = null,
        onNetworkActivity: (() -> Unit)? = null
    ): Long {
        return this.copyHlsSegmentImpl(source, sink, trafficAccumulator, prefixDigest, expectedRawBytes, onNetworkActivity)
    }


    /** 单线程下载 */
    internal suspend fun singleThreadDownload(
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


}
