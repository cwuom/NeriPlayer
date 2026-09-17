package moe.ouom.neriplayer.core.player.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.resource.DownloadStorageSpaceGuard
import moe.ouom.neriplayer.core.player.engine.datasource.ChunkRequestIOException
import moe.ouom.neriplayer.core.player.engine.datasource.ResumableHttpRangeSupport
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeGoogleVideoRangeSupport
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink

/**
 * 直链和 Range 分块传输引擎
 *
 * 引擎只负责网络响应、续传和安全刷盘，任务状态、取消、permit 和进度发布通过 hooks
 * 注入。这样网络读取不会持有歌曲锁，旧 attempt 的回调也能由 facade 统一拒绝
 */
internal class AudioDownloadFileTransfer(
    private val hooks: Hooks,
    private val readBufferBytes: Long,
    private val preferredChunkSizeBytes: Long
) {
    internal interface Hooks {
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

        fun deleteWorkingFile(file: File?)

        fun storageSpaceOwnerKey(
            operationId: String?,
            attemptId: Long?,
            songKey: String,
            file: File
        ): String

        fun <T> executeTrackedCall(
            client: OkHttpClient,
            request: Request,
            songKey: String,
            operationId: String?,
            block: (Response) -> T
        ): T

        fun newTrafficAccumulator(): TrafficByteAccumulator

        fun markTransferNetworkActivity(
            operationId: String?,
            attemptId: Long?,
            songKey: String,
            transferGeneration: Long?
        )

        fun publishProgress(progress: AudioDownloadManager.DownloadProgress)

        fun resolveVisibleDownloadFileName(
            requestedName: String,
            actualName: String
        ): String
    }

    suspend fun download(
        client: OkHttpClient,
        request: Request,
        destFile: File,
        displayFileName: String,
        songId: Long,
        songKey: String,
        batchSessionId: Long? = null,
        attemptId: Long? = null,
        operationId: String? = null,
        transferGeneration: Long? = null
    ): AudioDownloadManager.DownloadedPayloadSummary = withContext(Dispatchers.IO) {
        hooks.ensureDownloadNotCancelled(
            songId,
            songKey,
            destFile,
            batchSessionId,
            attemptId,
            operationId
        )
        if (
            YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(request) &&
            !ResumableHttpRangeSupport.hasExplicitRangeHeader(
                request.headers.names().associateWith { headerName ->
                    request.header(headerName).orEmpty()
                }
            )
        ) {
            return@withContext downloadChunked(
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

        val startNs = System.nanoTime()
        var resumedBytes = workingFileBytes(destFile)
        val resumeFingerprint = ManagedDownloadStorage.readWorkingResumeFingerprint(destFile)
        if (
            resumedBytes > 0L &&
            AudioDownloadTransferPolicy.shouldDiscardWorkingFileForResume(
                request.url.toString(),
                resumeFingerprint
            )
        ) {
            AudioDownloadLog.d("续传来源链接已变化，丢弃旧临时文件: ${destFile.name}")
            hooks.withWorkingFileMutation(
                songKey,
                "direct_resume_reset",
                batchSessionId,
                attemptId,
                operationId
            ) {
                hooks.deleteWorkingFile(destFile)
            }
            resumedBytes = 0L
        } else if (
            resumedBytes > 0L &&
            AudioDownloadTransferPolicy.resolveResumeValidatorHeader(resumeFingerprint).isNullOrBlank()
        ) {
            AudioDownloadLog.d("续传缺少 If-Range 校验符，回退整文件重下: ${destFile.name}")
            resumedBytes = 0L
        }
        val effectiveRequest = AudioDownloadTransferPolicy.buildResumeRequest(
            request = request,
            completedBytes = resumedBytes,
            fingerprint = resumeFingerprint
        )
        AudioDownloadLog.d("开始下载文件: ${destFile.name}, songId=$songId")
        return@withContext hooks.executeTrackedCall(
            client = client,
            request = effectiveRequest,
            songKey = songKey,
            operationId = operationId
        ) { response ->
            downloadResponse(
                response = response,
                request = request,
                destFile = destFile,
                displayFileName = displayFileName,
                songId = songId,
                songKey = songKey,
                batchSessionId = batchSessionId,
                attemptId = attemptId,
                operationId = operationId,
                transferGeneration = transferGeneration,
                resumedBytes = resumedBytes,
                resumeFingerprint = resumeFingerprint,
                startNs = startNs
            )
        }
    }

    private fun downloadResponse(
        response: Response,
        request: Request,
        destFile: File,
        displayFileName: String,
        songId: Long,
        songKey: String,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?,
        transferGeneration: Long?,
        resumedBytes: Long,
        resumeFingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?,
        startNs: Long
    ): AudioDownloadManager.DownloadedPayloadSummary {
        val responseHeaders = response.headers.toMultimap()
        if (resumedBytes > 0L && response.code == 416) {
            val expectedBytes = AudioDownloadTransferPolicy.resolveResponseExpectedBytes(
                requestUrl = request.url.toString(),
                headers = responseHeaders,
                bodyLength = response.body.contentLength(),
                resumedBytes = resumedBytes,
                isPartialResponse = true
            )
            val durableBytes = workingFileBytes(destFile)
            if (
                AudioDownloadTransferPolicy.isExactRangeEnd(responseHeaders, resumedBytes) &&
                expectedBytes != null &&
                durableBytes == expectedBytes
            ) {
                return AudioDownloadManager.DownloadedPayloadSummary(
                    actualBytes = resumedBytes,
                    expectedBytes = expectedBytes
                )
            }
            hooks.withWorkingFileMutation(
                songKey,
                "direct_range_not_satisfiable",
                batchSessionId,
                attemptId,
                operationId
            ) {
                hooks.deleteWorkingFile(destFile)
            }
            throw DownloadRangeRestartRequiredException()
        }
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}")
        }

        val appending = resumedBytes > 0L && response.code == 206
        val partialRange = if (response.code == 206) {
            val range = runCatching {
                AudioDownloadTransferPolicy.validatePartialContentRange(
                    headers = responseHeaders,
                    expectedStart = if (resumedBytes > 0L) resumedBytes else 0L,
                    bodyLength = response.body.contentLength()
                )
            }.getOrElse { error ->
                hooks.withWorkingFileMutation(
                    songKey,
                    "direct_invalid_range",
                    batchSessionId,
                    attemptId,
                    operationId
                ) {
                    hooks.deleteWorkingFile(destFile)
                }
                throw error
            }
            if (
                appending &&
                !AudioDownloadTransferPolicy.isResumeResponseCompatible(
                    resumeFingerprint,
                    responseHeaders,
                    range.total
                )
            ) {
                hooks.withWorkingFileMutation(
                    songKey,
                    "direct_incompatible_resume",
                    batchSessionId,
                    attemptId,
                    operationId
                ) {
                    hooks.deleteWorkingFile(destFile)
                }
                throw IOException("续传响应校验符或总长度不匹配")
            }
            range
        } else {
            null
        }
        if (resumedBytes > 0L && !appending) {
            AudioDownloadLog.d(
                "服务端未接受续传，回退整文件重下: ${destFile.name}, code=${response.code}"
            )
        }
        val initialBytes = if (appending) resumedBytes else 0L
        if (!appending && resumedBytes > 0L) {
            hooks.withWorkingFileMutation(
                songKey,
                "direct_restart_without_range",
                batchSessionId,
                attemptId,
                operationId
            ) {
                hooks.deleteWorkingFile(destFile)
            }
        }
        val total = AudioDownloadTransferPolicy.resolveResponseExpectedBytes(
            requestUrl = request.url.toString(),
            headers = responseHeaders,
            bodyLength = response.body.contentLength(),
            resumedBytes = initialBytes,
            isPartialResponse = appending
        ) ?: 0L
        val resumeMetadataWritten = hooks.withWorkingFileMutation(
            songKey,
            "direct_resume_metadata",
            batchSessionId,
            attemptId,
            operationId
        ) {
            AudioDownloadTransferPolicy.updateWorkingResumeFingerprint(
                destFile = destFile,
                requestUrl = request.url.toString(),
                headers = responseHeaders,
                expectedContentLength = total.takeIf { it > 0L }
            )
        }
        val source = response.body.source()
        var readSoFar = initialBytes
        val trafficAccumulator = hooks.newTrafficAccumulator()
        try {
            val output = hooks.withWorkingFileMutation(
                songKey,
                "direct_open_working_file",
                batchSessionId,
                attemptId,
                operationId
            ) {
                FileOutputStream(destFile, appending)
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
                // 响应长度只是服务器提示，不能在并发批量启动时一次性预留整首歌
                // 否则会把进程内预留竞争误报成磁盘已满
                expectedAdditionalBytes = null
            )
            guardedOutput.use { guarded ->
                guarded.sink().buffer().use { sink ->
                    val buffer = Buffer()
                    var durableBytes = initialBytes
                    var lastDurableSyncNs = startNs
                    while (true) {
                        hooks.ensureDownloadNotCancelled(
                            songId,
                            songKey,
                            destFile,
                            batchSessionId,
                            attemptId,
                            operationId
                        )
                        val read = source.read(buffer, readBufferBytes)
                        if (read == -1L) {
                            break
                        }
                        hooks.markTransferNetworkActivity(
                            operationId,
                            attemptId,
                            songKey,
                            transferGeneration
                        )
                        sink.write(buffer, read)
                        trafficAccumulator.add(read)
                        readSoFar += read
                        val nowNs = System.nanoTime()
                        if (
                            readSoFar - durableBytes >= DURABLE_CHECKPOINT_INTERVAL_BYTES ||
                            nowNs - lastDurableSyncNs >= DURABLE_CHECKPOINT_INTERVAL_NS
                        ) {
                            // 检查点只记录已经刷到文件描述符的前缀，避免恢复越过安全边界
                            sink.flush()
                            output.fd.sync()
                            durableBytes = readSoFar
                            lastDurableSyncNs = nowNs
                        }
                        val elapsedSec = ((nowNs - startNs) / 1_000_000_000.0)
                            .coerceAtLeast(0.001)
                        hooks.publishProgress(
                            AudioDownloadManager.DownloadProgress(
                                songKey = songKey,
                                songId = songId,
                                fileName = hooks.resolveVisibleDownloadFileName(
                                    displayFileName,
                                    destFile.name
                                ),
                                bytesRead = readSoFar,
                                totalBytes = total,
                                speedBytesPerSec = ((readSoFar - initialBytes) / elapsedSec).toLong(),
                                attemptId = attemptId,
                                operationId = operationId,
                                transferGeneration = transferGeneration,
                                durableBytesRead = durableBytes
                            )
                        )
                    }
                    // 只有缓冲区和文件描述符都刷盘后，checkpoint 才代表安全前缀
                    sink.flush()
                    output.fd.sync()
                }
            }
        } finally {
            trafficAccumulator.flush()
        }
        if (partialRange != null && readSoFar != partialRange.total) {
            throw IOException(
                "Content-Range 总长度不匹配: expected=${partialRange.total}, actual=$readSoFar"
            )
        }
        if (!AudioDownloadTransferPolicy.isTransferSizeComplete(total.takeIf { it > 0L }, readSoFar)) {
            throw IOException("下载文件不完整: ${destFile.name}, $readSoFar/$total")
        }
        return AudioDownloadManager.DownloadedPayloadSummary(
            actualBytes = readSoFar,
            expectedBytes = total.takeIf { it > 0L },
            resumeMetadataAvailable = resumeMetadataWritten
        )
    }

    private suspend fun downloadChunked(
        client: OkHttpClient,
        request: Request,
        destFile: File,
        displayFileName: String,
        songId: Long,
        songKey: String,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?,
        transferGeneration: Long?
    ): AudioDownloadManager.DownloadedPayloadSummary = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        AudioDownloadLog.d("开始分块下载文件: ${destFile.name}, songId=$songId")
        hooks.ensureDownloadNotCancelled(
            songId,
            songKey,
            destFile,
            batchSessionId,
            attemptId,
            operationId
        )

        var resumedBytes = workingFileBytes(destFile)
        val resumeFingerprint = ManagedDownloadStorage.readWorkingResumeFingerprint(destFile)
        if (
            resumedBytes > 0L &&
            AudioDownloadTransferPolicy.shouldDiscardWorkingFileForResume(
                request.url.toString(),
                resumeFingerprint
            )
        ) {
            hooks.withWorkingFileMutation(
                songKey,
                "chunked_resume_reset",
                batchSessionId,
                attemptId,
                operationId
            ) {
                hooks.deleteWorkingFile(destFile)
            }
            resumedBytes = 0L
        } else if (
            resumedBytes > 0L &&
            AudioDownloadTransferPolicy.resolveResumeValidatorHeader(resumeFingerprint).isNullOrBlank()
        ) {
            AudioDownloadLog.d("分块续传缺少 If-Range 校验符，回退整文件重下: ${destFile.name}")
            resumedBytes = 0L
        }

        var downloadedBytes = resumedBytes
        var durableBytes = resumedBytes
        var resumeMetadataAvailable = true
        val queryTotalHint = YouTubeGoogleVideoRangeSupport.resolveQueryContentLength(
            request.url.toString()
        ) ?: 0L
        var totalBytes = 0L
        var strictTotalBytes = false
        val output = hooks.withWorkingFileMutation(
            songKey,
            "chunked_open_working_file",
            batchSessionId,
            attemptId,
            operationId
        ) {
            FileOutputStream(destFile, resumedBytes > 0L)
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
            expectedAdditionalBytes = null
        )
        var rangeRestartError: ChunkRequestIOException? = null
        guardedOutput.use { guarded ->
            guarded.sink().buffer().use { sink ->
                while (true) {
                    hooks.ensureDownloadNotCancelled(
                        songId,
                        songKey,
                        destFile,
                        batchSessionId,
                        attemptId,
                        operationId
                    )
                    val remainingRequestLength = if (totalBytes > 0L) {
                        (totalBytes - downloadedBytes).coerceAtLeast(0L)
                    } else {
                        -1L
                    }
                    if (remainingRequestLength == 0L) {
                        break
                    }
                    try {
                        val chunkResult = ResumableHttpRangeSupport.executeChunkLengthFallback(
                            requestLength = remainingRequestLength,
                            preferredChunkSize = preferredChunkSizeBytes
                        ) { chunkLength ->
                            hooks.markTransferNetworkActivity(
                                operationId,
                                attemptId,
                                songKey,
                                transferGeneration
                            )
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
                                attemptId = attemptId,
                                operationId = operationId,
                                transferGeneration = transferGeneration,
                                durableBytesRead = durableBytes
                            )
                        }
                        downloadedBytes = chunkResult.value.downloadedBytes
                        totalBytes = chunkResult.value.totalBytes
                        strictTotalBytes = strictTotalBytes || chunkResult.value.strictTotalBytes
                        resumeMetadataAvailable =
                            resumeMetadataAvailable && chunkResult.value.resumeMetadataAvailable
                        // 一个 chunk 完成后统一刷盘，下一次恢复最多重做当前 chunk
                        sink.flush()
                        output.fd.sync()
                        durableBytes = downloadedBytes
                        hooks.publishProgress(
                            AudioDownloadManager.DownloadProgress(
                                songKey = songKey,
                                songId = songId,
                                fileName = hooks.resolveVisibleDownloadFileName(
                                    displayFileName,
                                    destFile.name
                                ),
                                bytesRead = downloadedBytes,
                                totalBytes = totalBytes.takeIf { it > 0L } ?: queryTotalHint,
                                speedBytesPerSec = ((downloadedBytes - resumedBytes) /
                                    ((System.nanoTime() - startNs) / 1_000_000_000.0)
                                        .coerceAtLeast(0.001)).toLong(),
                                attemptId = attemptId,
                                operationId = operationId,
                                transferGeneration = transferGeneration,
                                durableBytesRead = durableBytes
                            )
                        )
                        if (chunkResult.chunkLength != ResumableHttpRangeSupport
                                .candidateChunkLengths(remainingRequestLength, preferredChunkSizeBytes)
                                .first()
                        ) {
                            AudioDownloadLog.d(
                                "下载分块 fallback 生效: ${chunkResult.chunkLength} bytes, songId=$songId"
                            )
                        }
                        if (chunkResult.value.isEndOfStream) {
                            break
                        }
                    } catch (error: ChunkRequestIOException) {
                        val alreadyComplete = totalBytes > 0L && downloadedBytes == totalBytes
                        if (error.responseCode == 403 && alreadyComplete) {
                            break
                        }
                        if (error.responseCode == 416) {
                            rangeRestartError = error
                            break
                        }
                        throw error
                    }
                }
                // 分块请求可能跨多个响应，结束前统一刷盘再做完整性校验
                sink.flush()
                output.fd.sync()
            }
        }
        rangeRestartError?.let { error ->
            hooks.withWorkingFileMutation(
                songKey,
                "chunked_range_not_satisfiable",
                batchSessionId,
                attemptId,
                operationId
            ) {
                hooks.deleteWorkingFile(destFile)
            }
            throw DownloadRangeRestartRequiredException(error)
        }
        val expectedBytes = totalBytes.takeIf { it > 0L }
        if (strictTotalBytes && expectedBytes != null && downloadedBytes != expectedBytes) {
            throw IOException("分块 Content-Range 总长度不匹配: $downloadedBytes/$expectedBytes")
        }
        if (
            !strictTotalBytes &&
            !AudioDownloadTransferPolicy.isTransferSizeComplete(expectedBytes, downloadedBytes)
        ) {
            throw IOException("分块下载不完整: ${destFile.name}, $downloadedBytes/$expectedBytes")
        }
        AudioDownloadManager.DownloadedPayloadSummary(
            actualBytes = downloadedBytes,
            expectedBytes = expectedBytes,
            resumeMetadataAvailable = resumeMetadataAvailable
        )
    }

    private fun downloadChunk(
        client: OkHttpClient,
        request: Request,
        start: Long,
        requestedChunkLength: Long,
        resumeFingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?,
        sink: BufferedSink,
        displayFileName: String,
        songId: Long,
        songKey: String,
        destFile: File,
        startNs: Long,
        attemptStartBytes: Long,
        currentDownloadedBytes: Long,
        currentTotalBytes: Long,
        progressTotalBytesHint: Long,
        batchSessionId: Long?,
        attemptId: Long?,
        operationId: String?,
        transferGeneration: Long?,
        durableBytesRead: Long?
    ): ChunkDownloadResult {
        val effectiveFingerprint = AudioDownloadTransferPolicy.resolveLatestResumeFingerprint(
            fallback = resumeFingerprint,
            latest = ManagedDownloadStorage.readWorkingResumeFingerprint(destFile)
        )
        val chunkRequest = AudioDownloadTransferPolicy.buildChunkResumeRequest(
            request = request,
            start = start,
            length = requestedChunkLength,
            fingerprint = effectiveFingerprint
        )
        val trafficAccumulator = hooks.newTrafficAccumulator()
        try {
            return hooks.executeTrackedCall(
                client = client,
                request = chunkRequest,
                songKey = songKey,
                operationId = operationId
            ) { response ->
                val responseHeaders = response.headers.toMultimap()
                if (response.code == 416) {
                    sink.flush()
                    val total = AudioDownloadTransferPolicy.parseUnsatisfiedContentRangeTotal(
                        responseHeaders
                    )
                    val durableBytes = workingFileBytes(destFile)
                    if (
                        total != null &&
                        (currentTotalBytes == 0L || currentTotalBytes == total) &&
                        start == total &&
                        currentDownloadedBytes == total &&
                        durableBytes == total
                    ) {
                        return@executeTrackedCall ChunkDownloadResult(
                            chunkLength = requestedChunkLength,
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
                    AudioDownloadTransferPolicy.validatePartialContentRange(
                        headers = responseHeaders,
                        expectedStart = start,
                        bodyLength = response.body.contentLength()
                    )
                }.getOrElse { error ->
                    hooks.withWorkingFileMutation(
                        songKey,
                        "chunked_invalid_range",
                        batchSessionId,
                        attemptId,
                        operationId
                    ) {
                        hooks.deleteWorkingFile(destFile)
                    }
                    throw error
                }
                if (
                    start > 0L &&
                    !AudioDownloadTransferPolicy.isResumeResponseCompatible(
                        effectiveFingerprint,
                        responseHeaders,
                        contentRange.total
                    )
                ) {
                    hooks.withWorkingFileMutation(
                        songKey,
                        "chunked_incompatible_resume",
                        batchSessionId,
                        attemptId,
                        operationId
                    ) {
                        hooks.deleteWorkingFile(destFile)
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
                val resumeMetadataWritten = hooks.withWorkingFileMutation(
                    songKey,
                    "chunked_resume_metadata",
                    batchSessionId,
                    attemptId,
                    operationId
                ) {
                    AudioDownloadTransferPolicy.updateWorkingResumeFingerprint(
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
                    hooks.ensureDownloadNotCancelled(
                        songId,
                        songKey,
                        destFile,
                        batchSessionId,
                        attemptId,
                        operationId
                    )
                    val read = source.read(buffer, readBufferBytes)
                    if (read == -1L) {
                        break
                    }
                    hooks.markTransferNetworkActivity(
                        operationId,
                        attemptId,
                        songKey,
                        transferGeneration
                    )
                    sink.write(buffer, read)
                    trafficAccumulator.add(read)
                    chunkRead += read
                    downloadedBytes += read
                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0)
                        .coerceAtLeast(0.001)
                    hooks.publishProgress(
                        AudioDownloadManager.DownloadProgress(
                            songKey = songKey,
                            songId = songId,
                            fileName = hooks.resolveVisibleDownloadFileName(
                                displayFileName,
                                destFile.name
                            ),
                            bytesRead = downloadedBytes,
                            totalBytes = totalBytes.takeIf { it > 0L } ?: progressTotalBytesHint,
                            speedBytesPerSec = ((downloadedBytes - attemptStartBytes) /
                                elapsedSec).toLong(),
                            attemptId = attemptId,
                            operationId = operationId,
                            transferGeneration = transferGeneration,
                            durableBytesRead = durableBytesRead
                        )
                    )
                }
                if (chunkRead != contentRange.length) {
                    throw IOException(
                        "分块响应长度不匹配: expected=${contentRange.length}, actual=$chunkRead"
                    )
                }
                ChunkDownloadResult(
                    chunkLength = requestedChunkLength,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    isEndOfStream = chunkRead < requestedChunkLength ||
                        totalBytes in 1..downloadedBytes,
                    strictTotalBytes = true,
                    resumeMetadataAvailable = resumeMetadataWritten
                )
            }
        } finally {
            trafficAccumulator.flush()
        }
    }

    private fun workingFileBytes(file: File): Long {
        return file.takeIf(File::exists)?.length()?.coerceAtLeast(0L) ?: 0L
    }

    private data class ChunkDownloadResult(
        val chunkLength: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isEndOfStream: Boolean,
        val strictTotalBytes: Boolean,
        val resumeMetadataAvailable: Boolean = true
    )

    private object AudioDownloadLog {
        fun d(message: String) {
            moe.ouom.neriplayer.core.logging.NPLogger.d("NERI-Downloader", message)
        }
    }

    private companion object {
        private const val DURABLE_CHECKPOINT_INTERVAL_BYTES = 1L * 1024L * 1024L
        private const val DURABLE_CHECKPOINT_INTERVAL_NS = 500_000_000L
    }
}
