package moe.ouom.neriplayer.core.player.download

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.metadata.isCoverPixelBudgetWithin
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.io.readBytesLimited
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream

internal data class AudioCachedCoverReference(
    val reference: String,
    val created: Boolean
)

/**
 * 封面下载和 single flight 协调器
 *
 * 封面属于可选增强，不应占用核心音频提交的状态机；所有请求仍通过 facade 提供的
 * tracked call 和取消检查，旧代次不能写入新任务
 */
internal class AudioDownloadCoverCoordinator(
    private val maxAttempts: Int,
    private val retryDelayMs: Long,
    private val maxResponseBytes: Long,
    private val ensureNotCancelled: (
        songKey: String,
        stage: String,
        batchSessionId: Long?,
        attemptId: Long?,
        requireActiveAttempt: Boolean,
        operationId: String?
    ) -> Unit,
    private val executeTrackedCall: (
        request: Request,
        songKey: String,
        operationId: String?,
        requireActiveAttempt: Boolean,
        block: (Response) -> String?
    ) -> String?,
    private val commitCover: (
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        songKey: String,
        batchSessionId: Long?,
        attemptId: Long?,
        requireActiveAttempt: Boolean,
        operationId: String?
    ) -> String?,
    private val rememberPartial: (
        songKey: String,
        operationId: String?,
        references: AudioDownloadManager.DownloadedSidecarReferences
    ) -> Unit
) {
    private val tag = "NERI-Downloader"

    private data class CoverDownloadFlightKey(
        val songKey: String,
        val fileName: String
    )

    private val singleFlight =
        CoverDownloadSingleFlight<CoverDownloadFlightKey, AudioCachedCoverReference?>()

    suspend fun cacheCover(
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
    ): AudioCachedCoverReference? {
        val coverFileName = buildCoverSidecarFileName(baseName, songKey)
        val cachedCover = singleFlight.run(
            CoverDownloadFlightKey(songKey = songKey, fileName = coverFileName)
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
                allowIndexedLookup = allowIndexedLookup,
                operationId = operationId
            )
        }
        if (cachedCover != null) {
            ensureNotCancelled(
                songKey,
                "cover_reused",
                batchSessionId,
                attemptId,
                requireActiveAttempt,
                operationId
            )
            rememberPartial(
                songKey,
                operationId,
                AudioDownloadManager.DownloadedSidecarReferences(
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
        allowIndexedLookup: Boolean,
        operationId: String?
    ): AudioCachedCoverReference? {
        val indexedCover = ManagedDownloadStorage.peekCoverReference(storedAudio)
            ?: if (allowIndexedLookup && ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                ManagedDownloadStorage.peekCoverReference(storedAudio)
            } else {
                null
            }
        val indexedEvidence = indexedCover?.let {
            ManagedDownloadReferenceLookup.inspect(context, it)
        }
        if (
            indexedEvidence is ManagedDownloadReferenceLookup.Result.PermissionLost ||
            indexedEvidence is ManagedDownloadReferenceLookup.Result.ProviderFailure ||
            indexedEvidence == ManagedDownloadReferenceLookup.Result.OutOfScope
        ) {
            NPLogger.w(
                tag,
                "封面索引引用暂不可确认，跳过本次补齐: " +
                    "song=${song.name}, reference=$indexedCover, evidence=$indexedEvidence"
            )
            return null
        }
        val existingCover = indexedCover?.takeIf {
            indexedEvidence is ManagedDownloadReferenceLookup.Result.Present
        }
        if (!existingCover.isNullOrBlank()) {
            return AudioCachedCoverReference(existingCover, created = false)
        }

        return try {
            val candidates = AudioDownloadTransferPolicy.buildCoverDownloadCandidateUrls(song)
            candidates.forEachIndexed { index, coverUrl ->
                repeat(maxAttempts) { retryIndex ->
                    ensureNotCancelled(
                        songKey,
                        "cover_request",
                        batchSessionId,
                        attemptId,
                        requireActiveAttempt,
                        operationId
                    )
                    val reference = runCatching {
                        downloadCoverCandidate(
                            context = context,
                            songKey = songKey,
                            coverUrl = coverUrl,
                            coverFileName = coverFileName,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId,
                            requireActiveAttempt = requireActiveAttempt,
                            operationId = operationId
                        )
                    }.getOrElse { error ->
                        if (error is java.util.concurrent.CancellationException) {
                            throw error
                        }
                        NPLogger.w(
                            tag,
                            "封面下载重试失败: song=${song.name}, candidate=${index + 1}/" +
                                "${candidates.size}, attempt=${retryIndex + 1}/$maxAttempts, " +
                                "${error.javaClass.simpleName}: ${error.message}",
                            error
                        )
                        null
                    }
                    if (!reference.isNullOrBlank()) {
                        NPLogger.d(tag, "封面写入完成: song=${song.name}, reference=$reference")
                        return@cacheCoverInFlight AudioCachedCoverReference(
                            reference = reference,
                            created = true
                        )
                    }
                    if (retryIndex + 1 < maxAttempts) {
                        delay(retryDelayMs * (retryIndex + 1))
                    }
                }
                NPLogger.w(
                    tag,
                    "封面候选下载失败，准备尝试下一个来源: " +
                        "song=${song.name}, candidate=${index + 1}/${candidates.size}"
                )
            }
            null
        } catch (cancellation: java.util.concurrent.CancellationException) {
            NPLogger.d(tag, "封面整理阶段收到取消: ${song.name}")
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                tag,
                "封面后台下载失败: ${song.name} - " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error
            )
            null
        }
    }

    private fun downloadCoverCandidate(
        context: Context,
        songKey: String,
        coverUrl: String,
        coverFileName: String,
        batchSessionId: Long?,
        attemptId: Long?,
        requireActiveAttempt: Boolean,
        operationId: String?
    ): String? {
        val request = Request.Builder().url(coverUrl).build()
        return executeTrackedCall(
            request,
            songKey,
            operationId,
            requireActiveAttempt
        ) { response ->
            if (!response.isSuccessful) {
                throw IOException("封面请求失败: HTTP ${response.code}")
            }
            val body: ResponseBody = response.body
            val contentType = body.contentType()?.toString().orEmpty()
            if (contentType.isNotBlank() && !contentType.startsWith("image/", true)) {
                throw IOException("封面响应不是图片: $contentType")
            }
            val declaredLength = body.contentLength()
            val bytes = body.byteStream().use { input ->
                readCoverResponseBytes(input, declaredLength, maxResponseBytes)
            }
            ensureNotCancelled(
                songKey,
                "cover_downloaded",
                batchSessionId,
                attemptId,
                requireActiveAttempt,
                operationId
            )
            val copiedBytes = bytes.size.toLong()
            if (copiedBytes <= 0L) {
                throw IOException("封面写入为空")
            }
            if (!AudioDownloadTransferPolicy.isTransferSizeComplete(declaredLength, copiedBytes)) {
                throw IOException("封面写入不完整: $copiedBytes/$declaredLength")
            }
            if (!isUsableCoverBytes(bytes)) {
                throw IOException("封面文件校验失败")
            }
            ensureNotCancelled(
                songKey,
                "cover_commit",
                batchSessionId,
                attemptId,
                requireActiveAttempt,
                operationId
            )
            commitCover(
                context,
                bytes,
                coverFileName,
                contentType.takeIf(String::isNotBlank),
                songKey,
                batchSessionId,
                attemptId,
                requireActiveAttempt,
                operationId
            )
        }
    }

    private fun isUsableCoverBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth > 0 && options.outHeight > 0 &&
                isCoverPixelBudgetWithin(options.outWidth, options.outHeight)
        }.getOrDefault(false)
    }

    companion object {
        internal fun buildCoverSidecarFileName(baseName: String, songKey: String): String {
            val suffix = ManagedDownloadStorageNaming.coverStableKeySuffix(songKey)
            return "$baseName-$suffix.jpg"
        }

        internal fun readCoverResponseBytes(
            input: InputStream,
            declaredLength: Long,
            maxResponseBytes: Long
        ): ByteArray {
            if (declaredLength > maxResponseBytes) {
                throw IOException(
                    "封面响应过大: declared=$declaredLength, limit=$maxResponseBytes"
                )
            }
            return try {
                input.readBytesLimited(maxResponseBytes)
            } catch (error: IllegalArgumentException) {
                throw IOException("封面响应超过大小限制: $maxResponseBytes", error)
            }
        }
    }
}
