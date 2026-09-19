package moe.ouom.neriplayer.core.player.download

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import moe.ouom.neriplayer.core.download.storage.metadata.isCoverPixelBudgetWithin

internal data class AudioDownloadCoverImage(val bytes: ByteArray, val mimeType: String)

internal fun prepareDownloadedCoverImage(bytes: ByteArray, maxBytes: Long): AudioDownloadCoverImage {
    if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) {
        throw IOException("封面文件大小校验失败")
    }
    var sourceFitsBudget = false
    var sourceMimeType = ""
    // 解码器在分配像素前采样，截断或损坏的图片仍由默认的严格解码检查拒绝
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        if (width <= 0 || height <= 0) throw IOException("封面图片尺寸无效")
        sourceFitsBudget = isCoverPixelBudgetWithin(width, height)
        sourceMimeType = info.mimeType
        var sampleSize = 1
        while (
            ((width.toLong() + sampleSize - 1) / sampleSize) *
                ((height.toLong() + sampleSize - 1) / sampleSize) > MAX_COVER_DECODE_PIXELS
        ) {
            sampleSize *= 2
        }
        decoder.setTargetSampleSize(sampleSize)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    return try {
        if (!isCoverPixelBudgetWithin(bitmap.width, bitmap.height, MAX_COVER_DECODE_PIXELS)) {
            throw IOException("封面采样后尺寸仍超过限制")
        }
        if (sourceFitsBudget) {
            AudioDownloadCoverImage(bytes, sourceMimeType)
        } else {
            val hasAlpha = bitmap.hasAlpha()
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val output = BoundedCoverOutputStream(maxBytes)
            if (!bitmap.compress(format, COVER_JPEG_QUALITY, output) || output.limitExceeded) {
                throw IOException("封面采样编码失败或超过大小限制")
            }
            val encoded = output.toByteArray()
            if (encoded.isEmpty()) throw IOException("封面采样编码为空")
            AudioDownloadCoverImage(encoded, if (hasAlpha) "image/png" else "image/jpeg")
        }
    } finally {
        bitmap.recycle()
    }
}

private const val COVER_JPEG_QUALITY = 95
// 为每路封面解码限制像素数量，避免并发增强分配完整大图
internal const val MAX_COVER_DECODE_PIXELS = 4_000_000L

private class BoundedCoverOutputStream(private val maxBytes: Long) : OutputStream() {
    private val output = ByteArrayOutputStream()
    var limitExceeded = false
        private set

    override fun write(value: Int) {
        checkCapacity(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkCapacity(length)
        output.write(bytes, offset, length)
    }

    private fun checkCapacity(length: Int) {
        if (output.size().toLong() + length > maxBytes) {
            limitExceeded = true
            throw IOException("封面采样编码超过大小限制: $maxBytes")
        }
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}
