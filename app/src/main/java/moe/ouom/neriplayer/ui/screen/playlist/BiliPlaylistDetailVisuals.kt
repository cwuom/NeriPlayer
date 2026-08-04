package moe.ouom.neriplayer.ui.screen.playlist

import android.content.Context
import coil.Coil
import moe.ouom.neriplayer.core.api.bili.buildBiliThumbnailUrl
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

internal const val BILI_PLAYLIST_HERO_COVER_SIZE_PX = 320

internal fun buildBiliPlaylistHeroCoverUrl(coverUrl: String): String {
    return buildBiliThumbnailUrl(
        imageUrl = coverUrl,
        width = BILI_PLAYLIST_HERO_COVER_SIZE_PX,
        height = BILI_PLAYLIST_HERO_COVER_SIZE_PX
    )
}

internal fun preloadBiliPlaylistDetailVisuals(
    context: Context,
    coverUrl: String,
    offlineMode: Boolean
) {
    val heroCoverUrl = buildBiliPlaylistHeroCoverUrl(coverUrl)
    if (heroCoverUrl.isBlank()) return

    try {
        Coil.imageLoader(context).enqueue(
            offlineCachedImageRequest(
                context = context,
                data = heroCoverUrl,
                sizePx = BILI_PLAYLIST_HERO_COVER_SIZE_PX,
                allowHardware = false,
                crossfade = true,
                offlineMode = offlineMode
            )
        )
    } catch (_: Exception) {
        // cover cache warm-up is optional; navigation must never wait for it
    }
}
