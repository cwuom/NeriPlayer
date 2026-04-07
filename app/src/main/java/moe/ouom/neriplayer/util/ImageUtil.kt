package moe.ouom.neriplayer.util

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

private const val DEFAULT_LOCAL_IMAGE_REQUEST_SIZE_PX = 512

/**
 * 创建支持离线缓存的图片请求
 */
fun offlineCachedImageRequest(
    context: Context,
    data: Any?,
    sizePx: Int? = null,
    allowHardware: Boolean = true,
    crossfade: Boolean = false
): ImageRequest {
    val localSource = isLocalImageSource(data)
    val resolvedSizePx = sizePx ?: if (localSource) DEFAULT_LOCAL_IMAGE_REQUEST_SIZE_PX else null
    val resolvedAllowHardware = if (localSource && sizePx == null) false else allowHardware
    val builder = ImageRequest.Builder(context)
        .data(data)
        .allowHardware(resolvedAllowHardware)
        .crossfade(crossfade)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
    if (localSource && !resolvedAllowHardware) {
        builder.bitmapConfig(Bitmap.Config.RGB_565)
    }
    if (resolvedSizePx != null) {
        builder
            .size(resolvedSizePx)
            .precision(Precision.INEXACT)
    }
    return builder.build()
}

fun fastScrollableImageRequest(
    context: Context,
    data: Any?,
    sizePx: Int = 512,
    crossfade: Boolean = true
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(data)
        .size(sizePx)
        .precision(Precision.INEXACT)
        .crossfade(crossfade)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
    if (isLocalImageSource(data)) {
        builder
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
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
