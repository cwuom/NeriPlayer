package moe.ouom.neriplayer.data.local.media.curation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.editableCoverDecodeSampleSize

/** 嵌入封面的目标尺寸；车机与蓝牙常见解码上限在 500~1000 之间，原图只建议给大屏设备 */
enum class EmbeddedCoverSizePreset(val maxEdgePx: Int?) {
    COMPACT(500),
    STANDARD(1000),
    ORIGINAL(null)
}

data class PreparedEmbeddedCover(
    val reference: String,
    val byteSize: Int,
    val width: Int,
    val height: Int
)

data class EmbeddedCoverDimensions(val width: Int, val height: Int)

object EmbeddedCoverPreparer {
    private const val JPEG_QUALITY = 90
    private const val STAGING_DIRECTORY_NAME = "local_curation_covers"
    private const val JPEG_MIME_SIGNATURE_0 = 0xFF.toByte()
    private const val JPEG_MIME_SIGNATURE_1 = 0xD8.toByte()

    /** 按长边等比缩放，不放大小图 */
    fun fitWithinEdge(width: Int, height: Int, maxEdgePx: Int?): EmbeddedCoverDimensions {
        if (width <= 0 || height <= 0) return EmbeddedCoverDimensions(width, height)
        val longEdge = maxOf(width, height)
        if (maxEdgePx == null || maxEdgePx <= 0 || longEdge <= maxEdgePx) {
            return EmbeddedCoverDimensions(width, height)
        }
        val scale = maxEdgePx.toDouble() / longEdge
        return EmbeddedCoverDimensions(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    /** 解码采样率取不低于目标尺寸的最大 2 次幂，保证缩放前的位图仍有足够细节 */
    fun decodeSampleSize(width: Int, height: Int, maxEdgePx: Int?): Int {
        if (maxEdgePx == null || maxEdgePx <= 0) return 1
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= maxEdgePx) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** 预览阶段的暂存目录；会话结束或下次启动整理时可整体删除，不影响已提交的封面 */
    fun stagingDirectory(context: Context): File = File(context.cacheDir, STAGING_DIRECTORY_NAME)

    fun clearStaging(context: Context) {
        stagingDirectory(context).deleteRecursively()
    }

    /**
     * 读取任意本地/网络封面并压缩为待嵌入的 JPEG，结果按内容寻址暂存到缓存目录供预览。
     * 原图预设下，已是 JPEG 且未超过像素预算的图片原样保留。
     */
    suspend fun prepare(
        context: Context,
        sourceReference: String,
        preset: EmbeddedCoverSizePreset
    ): PreparedEmbeddedCover? = withContext(Dispatchers.IO) {
        val sourceBytes = LocalMediaSupport.readEditableCoverBytes(context, sourceReference)
            ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val sourceTarget = fitWithinEdge(bounds.outWidth, bounds.outHeight, preset.maxEdgePx)
        val keepSource = isJpeg(sourceBytes) &&
            sourceTarget.width == bounds.outWidth && sourceTarget.height == bounds.outHeight &&
            editableCoverDecodeSampleSize(bounds.outWidth, bounds.outHeight) == 1
        val encoded = if (keepSource) {
            EncodedCover(sourceBytes, sourceTarget)
        } else {
            encodeJpeg(sourceBytes, bounds, preset.maxEdgePx) ?: return@withContext null
        }
        val staged = stageBytes(stagingDirectory(context), encoded.bytes) ?: return@withContext null
        PreparedEmbeddedCover(
            reference = Uri.fromFile(staged).toString(),
            byteSize = encoded.bytes.size,
            width = encoded.dimensions.width,
            height = encoded.dimensions.height
        )
    }

    /** 把暂存封面转存到持久的自定义封面目录，返回可长期作为 `customCoverUrl` 的引用 */
    suspend fun commit(context: Context, prepared: PreparedEmbeddedCover): String? =
        withContext(Dispatchers.IO) {
            val staged = prepared.reference.toUri().path?.let(::File)?.takeIf(File::isFile)
                ?: return@withContext null
            val bytes = runCatching { staged.readBytes() }.getOrNull() ?: return@withContext null
            CustomSongCoverStorage.persistPreparedCover(context, bytes)
        }

    private fun stageBytes(directory: File, bytes: ByteArray): File? {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = File(directory, "${sha256Hex(bytes)}.jpg")
        if (target.isFile && target.length() == bytes.size.toLong()) return target
        val temporary = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            temporary.writeBytes(bytes)
            if (temporary.renameTo(target) || target.isFile) target else null
        } catch (_: IOException) {
            null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private class EncodedCover(val bytes: ByteArray, val dimensions: EmbeddedCoverDimensions)

    private fun encodeJpeg(
        sourceBytes: ByteArray,
        bounds: BitmapFactory.Options,
        maxEdgePx: Int?
    ): EncodedCover? {
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = maxOf(
                decodeSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx),
                editableCoverDecodeSampleSize(bounds.outWidth, bounds.outHeight)
            )
        }
        val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOptions)
            ?: return null
        val target = fitWithinEdge(decoded.width, decoded.height, maxEdgePx)
        val scaled = if (decoded.width != target.width || decoded.height != target.height) {
            decoded.scale(target.width, target.height)
        } else {
            decoded
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    output.toByteArray().takeIf { it.isNotEmpty() }?.let { EncodedCover(it, target) }
                } else {
                    null
                }
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size > 2 && bytes[0] == JPEG_MIME_SIGNATURE_0 && bytes[1] == JPEG_MIME_SIGNATURE_1
    }
}
