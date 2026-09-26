package moe.ouom.neriplayer.util.media

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
 * File: moe.ouom.neriplayer.util/ImageUtil
 * Created: 2025/1/20
 */

import android.graphics.Bitmap
import android.content.Context
import coil.size.Precision
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.transform.Transformation
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow
import androidx.compose.ui.graphics.ImageBitmap
import java.util.LinkedHashMap

private const val DEFAULT_LOCAL_IMAGE_REQUEST_SIZE_PX = 512
private const val DEFAULT_IMAGE_CROSSFADE_DURATION_MILLIS = 180

/**
 * 创建支持离线缓存的图片请求
 */
fun offlineCachedImageRequest(
    context: Context,
    data: Any?,
    sizePx: Int? = null,
    allowHardware: Boolean = true,
    crossfade: Boolean = true,
    offlineMode: Boolean = context.isOfflineModeNow(),
    transformations: List<Transformation> = emptyList(),
    cacheKey: String? = null
): ImageRequest {
    val localSource = isLocalImageSource(data)
    val remoteSource = isRemoteImageSource(data)
    val resolvedSizePx = sizePx ?: if (localSource) DEFAULT_LOCAL_IMAGE_REQUEST_SIZE_PX else null
    val resolvedAllowHardware = if (localSource && sizePx == null) false else allowHardware
    val builder = ImageRequest.Builder(context)
        .data(data)
        .allowHardware(resolvedAllowHardware)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(if (offlineMode && remoteSource) CachePolicy.DISABLED else CachePolicy.ENABLED)
    cacheKey?.trim()?.takeIf(String::isNotEmpty)?.let { resolvedCacheKey ->
        builder
            .memoryCacheKey(resolvedCacheKey)
            .diskCacheKey(resolvedCacheKey)
    }
    if (localSource && !resolvedAllowHardware) {
        builder.bitmapConfig(Bitmap.Config.RGB_565)
    }
    if (resolvedSizePx != null) {
        builder
            .size(resolvedSizePx)
            .precision(Precision.INEXACT)
    }
    if (transformations.isNotEmpty()) {
        builder.transformations(transformations)
    }
    if (crossfade) {
        builder.crossfade(DEFAULT_IMAGE_CROSSFADE_DURATION_MILLIS)
    } else {
        builder.crossfade(false)
    }
    return builder.build()
}

fun fastScrollableImageRequest(
    context: Context,
    data: Any?,
    sizePx: Int = 512,
    crossfade: Boolean = true,
    offlineMode: Boolean = context.isOfflineModeNow(),
    cacheKey: String? = null
): ImageRequest {
    val remoteSource = isRemoteImageSource(data)
    val builder = ImageRequest.Builder(context)
        .data(data)
        .size(sizePx)
        .precision(Precision.INEXACT)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(if (offlineMode && remoteSource) CachePolicy.DISABLED else CachePolicy.ENABLED)
    cacheKey?.trim()?.takeIf(String::isNotEmpty)?.let { resolvedCacheKey ->
        builder
            .memoryCacheKey(resolvedCacheKey)
            .diskCacheKey(resolvedCacheKey)
    }
    if (isLocalImageSource(data)) {
        builder
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
    }
    if (crossfade) {
        builder.crossfade(DEFAULT_IMAGE_CROSSFADE_DURATION_MILLIS)
    } else {
        builder.crossfade(false)
    }
    return builder.build()
}

fun isRemoteImageSource(data: Any?): Boolean {
    val normalized = data?.toString()?.trim()?.lowercase().orEmpty()
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

fun isLocalImageSource(data: Any?): Boolean {
    val normalized = data?.toString()?.trim()?.lowercase().orEmpty()
    return normalized.startsWith("content://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("android.resource://") ||
        normalized.startsWith("/")
}

// retained playback artwork outlives Coil's Drawable, so keep an independent bitmap
internal fun copyBitmapForRetainedDisplay(source: Bitmap): Bitmap? {
    if (source.isRecycled) return null
    return source.copy(Bitmap.Config.ARGB_8888, false)
}

internal data class RetainedPlaybackCoverBitmap(
    val ownerKey: String,
    val coverUrl: String,
    val cacheKey: String?,
    val bitmap: ImageBitmap
)

/**
 * 保留最近成功解码的封面, 让页面重建或快速切歌时可以先显示旧帧
 */
internal object RetainedPlaybackCoverBitmapCache {
    private const val MAX_ENTRIES = 4
    private val entries = LinkedHashMap<String, RetainedPlaybackCoverBitmap>(
        MAX_ENTRIES,
        0.75f,
        true
    )

    fun put(
        ownerKey: String?,
        coverUrl: String?,
        cacheKey: String?,
        bitmap: ImageBitmap
    ) {
        val normalizedOwner = ownerKey?.trim()?.takeIf(String::isNotEmpty) ?: return
        val normalizedCover = coverUrl?.trim()?.takeIf(String::isNotEmpty) ?: return
        val entry = RetainedPlaybackCoverBitmap(
            ownerKey = normalizedOwner,
            coverUrl = normalizedCover,
            cacheKey = cacheKey?.trim()?.takeIf(String::isNotEmpty),
            bitmap = bitmap
        )
        synchronized(entries) {
            entries[cacheEntryKey(normalizedOwner, normalizedCover)] = entry
            while (entries.size > MAX_ENTRIES) {
                val iterator = entries.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun getExact(ownerKey: String?, coverUrl: String?): RetainedPlaybackCoverBitmap? {
        val normalizedOwner = ownerKey?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val normalizedCover = coverUrl?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return synchronized(entries) {
            entries[cacheEntryKey(normalizedOwner, normalizedCover)]
        }
    }

    fun getLatestForOwner(ownerKey: String?): RetainedPlaybackCoverBitmap? {
        val normalizedOwner = ownerKey?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return synchronized(entries) {
            entries.entries
                .toList()
                .asReversed()
                .firstOrNull { it.value.ownerKey == normalizedOwner }
                ?.value
        }
    }

    private fun cacheEntryKey(ownerKey: String, coverUrl: String): String {
        return "$ownerKey\u0000$coverUrl"
    }
}
