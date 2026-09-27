package moe.ouom.neriplayer.ui

import android.content.Context
import android.graphics.Bitmap
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision

internal fun nowPlayingBlurImageCacheKey(
    coverUrl: String?,
    blurStrength: Float?,
    assetVersion: String
): String = "nowplaying-blur:$coverUrl:$blurStrength:$assetVersion"

internal fun nowPlayingBlurNetworkCachePolicy(
    offlineMode: Boolean,
    coverUrl: String?
): CachePolicy = if (shouldDisableNowPlayingBlurNetwork(offlineMode, coverUrl)) {
    CachePolicy.DISABLED
} else {
    CachePolicy.ENABLED
}

private fun nowPlayingBlurTransformations(
    context: Context,
    blurStrength: Float?
): List<BlurTransformation> {
    val strength = normalizedNowPlayingBlurStrength(blurStrength)
    return if (strength > 0f) listOf(BlurTransformation(context, strength)) else emptyList()
}

internal fun normalizedNowPlayingBlurStrength(blurStrength: Float?): Float = blurStrength ?: 0f

internal fun nowPlayingBlurImageRequest(
    context: Context,
    coverUrl: String?,
    blurStrength: Float?,
    imageSizePx: Int,
    assetVersion: String,
    offlineMode: Boolean
): ImageRequest.Builder {
    val cacheKey = nowPlayingBlurImageCacheKey(coverUrl, blurStrength, assetVersion)
    return ImageRequest.Builder(context)
        .data(coverUrl)
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .size(imageSizePx)
        .precision(Precision.INEXACT)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .networkCachePolicy(nowPlayingBlurNetworkCachePolicy(offlineMode, coverUrl))
        .transformations(nowPlayingBlurTransformations(context, blurStrength))
}

internal fun preloadNowPlayingBlurCovers(
    hasCoverBlur: Boolean,
    coverUrls: List<String>,
    enqueue: (String) -> Unit
) {
    if (!hasCoverBlur) return
    coverUrls.forEach(enqueue)
}
