package moe.ouom.neriplayer.util

import android.content.Context
import coil.request.ImageRequest
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow

fun offlineCachedImageRequest(
    context: Context,
    data: Any?,
    sizePx: Int? = null,
    allowHardware: Boolean = true,
    crossfade: Boolean = false,
    offlineMode: Boolean = context.isOfflineModeNow()
): ImageRequest {
    return moe.ouom.neriplayer.util.media.offlineCachedImageRequest(
        context = context,
        data = data,
        sizePx = sizePx,
        allowHardware = allowHardware,
        crossfade = crossfade,
        offlineMode = offlineMode
    )
}

fun fastScrollableImageRequest(
    context: Context,
    data: Any?,
    sizePx: Int = 512,
    crossfade: Boolean = true,
    offlineMode: Boolean = context.isOfflineModeNow()
): ImageRequest {
    return moe.ouom.neriplayer.util.media.fastScrollableImageRequest(
        context = context,
        data = data,
        sizePx = sizePx,
        crossfade = crossfade,
        offlineMode = offlineMode
    )
}

fun isRemoteImageSource(data: Any?): Boolean {
    return moe.ouom.neriplayer.util.media.isRemoteImageSource(data)
}

fun isLocalImageSource(data: Any?): Boolean {
    return moe.ouom.neriplayer.util.media.isLocalImageSource(data)
}
