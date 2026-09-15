package moe.ouom.neriplayer.core.player.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.resource.DownloadStorageSpaceGuard
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.util.io.readBytesLimited
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink

/**
 * 负责 HLS 清单、分段下载和可恢复刷盘
 *
 * 传输期间不持有歌曲锁，状态检查、工作文件变更和进度发布由 facade 注入，
 * 这样 HLS 长连接不会阻塞同曲目新代次的准入
 */
internal class AudioDownloadHlsTransfer(
    private val hooks: Hooks,
    private val maxPlaylistBytes: Long,
    private val maxSegmentBytes: Long,
    private val readBufferBytes: Int
) {
    internal interface Hooks {
        fun markTransferNetworkActivity(
            operationId: String?,
            attemptId: Long?,
            songKey: String,
            transferGeneration: Long?
        )

        fun <T> executeTrackedCall(
            client: OkHttpClient,
            request: Request,
            songKey: String,
            operationId: String?,
            block: (Response) -> T
        ): T

        fun ensureDownloadNotCancelled(
            songId: Long,
            songKey: String,
            destFile: File,
            batchSessionId: Long?,
            attemptId: Long?,
            operationId: String?
        )

        fun <T> withWorkingFileMutation(
            songKey: String,
            stage: String,
            batchSessionId: Long?,
            attemptId: Long?,
            operationId: String?,
            block: () -> T
        ): T

        fun resolveHlsResumeState(
            destFile: File,
            playlistFingerprint: String,
            operationId: String
        ): AudioDownloadManager.HlsResumeState?

        fun isHlsResumeStateCompatible(
            state: AudioDownloadManager.HlsResumeState,
            actualFileLength: Long,
            actualPrefixSha256: String,
            segmentCount: Int
        ): Boolean

        fun sha256FilePrefix(file: File, byteCount: Long): String

        fun sha256FilePrefixDigest(file: File, byteCount: Long): MessageDigest

        fun digestHexSnapshot(
            digest: MessageDigest,
            file: File,
            byteCount: Long
        ): String

        fun hasHlsResumeState(file: File?): Boolean

        fun clearHlsResumeState(file: File?)

        fun resolveWorkingFileBytes(file: File?): Long

        fun truncateWorkingFile(file: File, byteCount: Long)

        fun deleteWorkingFile(file: File?)

        fun storageSpaceOwnerKey(
            operationId: String?,
            attemptId: Long?,
            songKey: String,
            file: File
        ): String

        fun newTrafficAccumulator(): TrafficByteAccumulator

        fun rememberHlsResumeState(
            destFile: File,
            playlistFingerprint: String,
            nextSegmentIndex: Int,
            durableBytes: Long,
            durablePrefixSha256: String,
            operationId: String,
            mediaSequence: Long?
        )

        fun publishProgress(progress: AudioDownloadManager.DownloadProgress)

        fun resolveVisibleDownloadFileName(
            requestedName: String,
            actualName: String
        ): String
    }

    suspend fun download(
        client: OkHttpClient,
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
    ): AudioDownloadManager.DownloadedPayloadSummary = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        NPLogger.d(TAG, "开始 HLS 下载文件: ${destFile.name}, songId=$songId")

        hooks.markTransferNetworkActivity(
            operationId = operationId,
            attemptId = attemptId,
            songKey = songKey,
            transferGeneration = transferGeneration
        )
        val playlistText = hooks.executeTrackedCall(
            client = client,
            request = playlistRequest,
            songKey = songKey,
            operationId = operationId
        ) { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            response.body.byteStream().use { input ->
                input.readBytesLimited(maxPlaylistBytes).toString(Charsets.UTF_8)
            }
        }
        ensureDownloadNotCancelled(
            songId = songId,
            songKey = songKey,
            destFile = destFile,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId
        )
        val segmentUrls = AudioHlsSegmentSupport.parseSegmentUrls(
            playlistRequest.url.toString(),
            playlistText
        )
        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("HLS playlist contains no segments")
        }
        val playlistFingerprint = AudioDownloadManager.buildHlsPlaylistFingerprint(
            segmentUrls,
            playlistText
        )
        val mediaSequence = AudioHlsSegmentSupport.parseMediaSequence(playlistText)
        val resolvedResumeState = hooks.resolveHlsResumeState(
            destFile = destFile,
            playlistFingerprint = playlistFingerprint,
            operationId = operationId
        )?.takeIf { resumeState ->
            if (!destFile.exists()) {
                false
            } else {
                val actualFileLength = destFile.length().coerceAtLeast(0L)
                val prefixDigest = runCatching {
                    hooks.sha256FilePrefix(destFile, resumeState.durableBytes)
                }.getOrNull() ?: return@takeIf false
                hooks.isHlsResumeStateCompatible(
                    state = resumeState,
                    actualFileLength = actualFileLength,
                    actualPrefixSha256 = prefixDigest,
                    segmentCount = segmentUrls.size
                )
            }
        }
        resolvedResumeState?.let { resumeState ->
            if (destFile.length().coerceAtLeast(0L) > resumeState.durableBytes) {
                hooks.withWorkingFileMutation(
                    songKey = songKey,
                    stage = "hls_resume_truncate",
                    batchSessionId = batchSessionId,
                    attemptId = attemptId,
                    operationId = operationId
                ) {
                    if (
                        destFile.exists() &&
                            destFile.length().coerceAtLeast(0L) > resumeState.durableBytes
                    ) {
                        hooks.truncateWorkingFile(destFile, resumeState.durableBytes)
                    }
                }
            }
        }
        if (resolvedResumeState == null) {
            hooks.withWorkingFileMutation(
                songKey = songKey,
                stage = "hls_resume_reset",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId
            ) {
                if (hooks.hasHlsResumeState(destFile)) {
                    hooks.clearHlsResumeState(destFile)
                }
                if (hooks.resolveWorkingFileBytes(destFile) > 0L) {
                    hooks.deleteWorkingFile(destFile)
                }
            }
        }
        val resumeSegmentIndex = resolvedResumeState?.nextSegmentIndex ?: 0
        val attemptStartBytes = resolvedResumeState?.downloadedBytes?.coerceAtLeast(0L) ?: 0L
        if (resumeSegmentIndex > 0) {
            NPLogger.d(
                TAG,
                "恢复 HLS 下载: ${destFile.name}, segment=$resumeSegmentIndex/${segmentUrls.size}, " +
                    "bytes=$attemptStartBytes, songId=$songId"
            )
        }

        val headerMap = playlistRequest.headers.names().associateWith { name ->
            playlistRequest.header(name).orEmpty()
        }
        var downloadedBytes = attemptStartBytes
        val trafficAccumulator = hooks.newTrafficAccumulator()
        val durablePrefixDigest = runCatching {
            hooks.sha256FilePrefixDigest(destFile, attemptStartBytes)
        }.getOrElse { error ->
            throw IOException("无法读取 HLS durable 前缀", error)
        }
        try {
            val output = hooks.withWorkingFileMutation(
                songKey = songKey,
                stage = "hls_open_working_file",
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId
            ) {
                FileOutputStream(destFile, resumeSegmentIndex > 0)
            }
            val guardedOutput = DownloadStorageSpaceGuard.global.guardOutput(
                output = output,
                root = destFile.parentFile ?: destFile,
                ownerKey = hooks.storageSpaceOwnerKey(
                    operationId = operationId,
                    attemptId = attemptId,
                    songKey = songKey,
                    file = destFile
                ),
                // HLS 长度提示可能包含误差，按实际写入增长预留，避免批量并发时
                // 把总长度提示叠加成虚假的空间不足
                expectedAdditionalBytes = null
            )
            guardedOutput.use { guarded ->
                guarded.sink().buffer().use { sink ->
                    segmentUrls.drop(resumeSegmentIndex).forEachIndexed {
                        relativeIndex,
                        segmentUrl
                    ->
                        val index = resumeSegmentIndex + relativeIndex
                        ensureDownloadNotCancelled(
                            songId = songId,
                            songKey = songKey,
                            destFile = destFile,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId,
                            operationId = operationId
                        )
                        val segmentRequest = Request.Builder()
                            .url(segmentUrl)
                            .apply {
                                headerMap.forEach { (name, value) -> header(name, value) }
                            }
                            .build()
                        downloadedBytes += hooks.executeTrackedCall(
                            client = client,
                            request = segmentRequest,
                            songKey = songKey,
                            operationId = operationId
                        ) { response ->
                            if (!response.isSuccessful) {
                                throw IllegalStateException("HTTP ${response.code}")
                            }
                            response.body.source().use { source ->
                                AudioHlsSegmentSupport.copySegment(
                                    source = source,
                                    sink = sink,
                                    trafficAccumulator = trafficAccumulator,
                                    maxSegmentBytes = maxSegmentBytes,
                                    readBufferBytes = readBufferBytes,
                                    prefixDigest = durablePrefixDigest,
                                    expectedRawBytes = response.body.contentLength()
                                        .takeIf { it >= 0L },
                                    onNetworkActivity = {
                                        hooks.markTransferNetworkActivity(
                                            operationId = operationId,
                                            attemptId = attemptId,
                                            songKey = songKey,
                                            transferGeneration = transferGeneration
                                        )
                                    }
                                )
                            }
                        }
                        runCatching {
                            sink.flush()
                            output.fd.sync()
                        }.onFailure { flushError ->
                            NPLogger.e(
                                TAG,
                                "HLS 段刷盘失败，暂缓推进 checkpoint: " +
                                    "${destFile.name}, segment=$index",
                                flushError
                            )
                            throw flushError
                        }
                        val durableBytes = destFile.length().coerceAtLeast(0L)
                        if (durableBytes != downloadedBytes) {
                            throw IOException(
                                "HLS durable length differs from tracked bytes: " +
                                    "disk=$durableBytes, tracked=$downloadedBytes, " +
                                    "file=${destFile.name}"
                            )
                        }
                        hooks.rememberHlsResumeState(
                            destFile = destFile,
                            playlistFingerprint = playlistFingerprint,
                            nextSegmentIndex = index + 1,
                            durableBytes = durableBytes,
                            durablePrefixSha256 = hooks.digestHexSnapshot(
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
                        hooks.publishProgress(
                            AudioDownloadManager.DownloadProgress(
                                songKey = songKey,
                                songId = songId,
                                fileName = hooks.resolveVisibleDownloadFileName(
                                    displayFileName,
                                    destFile.name
                                ),
                                bytesRead = downloadedBytes,
                                totalBytes = totalBytesHint,
                                speedBytesPerSec = (attemptTransferredBytes / elapsedSec).toLong(),
                                attemptId = attemptId,
                                operationId = operationId,
                                transferGeneration = transferGeneration,
                                durableBytesRead = durableBytes
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
            "HLS 下载完成: ${destFile.name}, 实际大小: $downloadedBytes bytes, " +
                "segments=${segmentUrls.size}, songId=$songId"
        )
        return@withContext AudioDownloadManager.DownloadedPayloadSummary(
            actualBytes = downloadedBytes,
            expectedBytes = null
        )
    }

    private fun ensureDownloadNotCancelled(
        songId: Long,
        songKey: String,
        destFile: File,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?
    ) {
        hooks.ensureDownloadNotCancelled(
            songId = songId,
            songKey = songKey,
            destFile = destFile,
            batchSessionId = batchSessionId,
            attemptId = attemptId,
            operationId = operationId
        )
    }

    companion object {
        private const val TAG = "NERI-Downloader"
    }
}
