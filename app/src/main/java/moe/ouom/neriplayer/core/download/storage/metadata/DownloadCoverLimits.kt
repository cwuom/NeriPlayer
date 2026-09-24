package moe.ouom.neriplayer.core.download.storage.metadata

import java.io.IOException

/** 下载封面 source 和解码阶段共用的资源边界 */
internal const val MAX_SOURCE_COVER_BYTES = 16L * 1024L * 1024L
internal const val MAX_COVER_PIXELS = 16_000_000L
internal const val COVER_STREAM_BUFFER_SIZE_BYTES = 64 * 1024

internal fun isCoverPixelBudgetWithin(
    width: Int,
    height: Int,
    maxPixels: Long = MAX_COVER_PIXELS
): Boolean {
    if (width <= 0 || height <= 0 || maxPixels <= 0L) return false
    return width.toLong() * height.toLong() <= maxPixels
}

internal class CoverSourceTooLargeException(
    val actualBytes: Long,
    val maxBytes: Long = MAX_SOURCE_COVER_BYTES
) : IOException("cover exceeds source limit: $actualBytes > $maxBytes")

internal class CoverPixelBudgetExceededException(
    val width: Int,
    val height: Int,
    val maxPixels: Long = MAX_COVER_PIXELS
) : IOException("cover pixel budget exceeded: ${width}x$height > $maxPixels")
