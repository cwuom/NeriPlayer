package moe.ouom.neriplayer.data.local.media.curation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
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

/** [newlyCreated] 表示文件由这次提交创建，写入失败且没有其他歌曲引用时可以删除 */
data class CommittedEmbeddedCover(val reference: String, val newlyCreated: Boolean)

/** EXIF 方向对应的像素变换；嵌入封面不保留 EXIF，必须先把像素转正 */
data class CoverOrientation(val rotationDegrees: Int, val mirrored: Boolean) {
    val isIdentity: Boolean get() = rotationDegrees == 0 && !mirrored

    companion object {
        val NORMAL = CoverOrientation(0, mirrored = false)

        fun fromExif(orientation: Int): CoverOrientation = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> CoverOrientation(0, mirrored = true)
            ExifInterface.ORIENTATION_ROTATE_180 -> CoverOrientation(180, mirrored = false)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> CoverOrientation(180, mirrored = true)
            ExifInterface.ORIENTATION_TRANSPOSE -> CoverOrientation(90, mirrored = true)
            ExifInterface.ORIENTATION_ROTATE_90 -> CoverOrientation(90, mirrored = false)
            ExifInterface.ORIENTATION_TRANSVERSE -> CoverOrientation(270, mirrored = true)
            ExifInterface.ORIENTATION_ROTATE_270 -> CoverOrientation(270, mirrored = false)
            else -> NORMAL
        }
    }
}

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
        val orientation = if (isJpeg(sourceBytes)) readOrientation(sourceBytes) else CoverOrientation.NORMAL
        val sourceTarget = fitWithinEdge(bounds.outWidth, bounds.outHeight, preset.maxEdgePx)
        val keepSource = isJpeg(sourceBytes) && orientation.isIdentity &&
            sourceTarget.width == bounds.outWidth && sourceTarget.height == bounds.outHeight &&
            editableCoverDecodeSampleSize(bounds.outWidth, bounds.outHeight) == 1
        val encoded = if (keepSource) {
            EncodedCover(sourceBytes, sourceTarget)
        } else {
            encodeJpeg(sourceBytes, bounds, preset.maxEdgePx, orientation) ?: return@withContext null
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
    suspend fun commit(context: Context, prepared: PreparedEmbeddedCover): CommittedEmbeddedCover? =
        withContext(Dispatchers.IO) {
            val staged = prepared.reference.toUri().path?.let(::File)?.takeIf(File::isFile)
                ?: return@withContext null
            val bytes = runCatching { staged.readBytes() }.getOrNull() ?: return@withContext null
            CustomSongCoverStorage.findPreparedCover(context, bytes)
                ?.let { return@withContext CommittedEmbeddedCover(it, newlyCreated = false) }
            CustomSongCoverStorage.persistPreparedCover(context, bytes)
                ?.let { CommittedEmbeddedCover(it, newlyCreated = true) }
        }

    /** 删除提交后没有被任何歌曲采用的封面，只处理自定义封面目录内的文件 */
    suspend fun discard(context: Context, references: Collection<String>) = withContext(Dispatchers.IO) {
        references.forEach { reference -> CustomSongCoverStorage.deletePreparedCover(context, reference) }
    }

    private fun readOrientation(bytes: ByteArray): CoverOrientation = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        CoverOrientation.fromExif(
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        )
    }.getOrDefault(CoverOrientation.NORMAL)

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
        maxEdgePx: Int?,
        orientation: CoverOrientation
    ): EncodedCover? {
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = maxOf(
                decodeSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx),
                editableCoverDecodeSampleSize(bounds.outWidth, bounds.outHeight)
            )
        }
        val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOptions)
            ?.let { bitmap -> applyOrientation(bitmap, orientation) }
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

    private fun applyOrientation(bitmap: Bitmap, orientation: CoverOrientation): Bitmap {
        if (orientation.isIdentity) return bitmap
        val matrix = Matrix().apply {
            if (orientation.mirrored) postScale(-1f, 1f)
            postRotate(orientation.rotationDegrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size > 2 && bytes[0] == JPEG_MIME_SIGNATURE_0 && bytes[1] == JPEG_MIME_SIGNATURE_1
    }
}
