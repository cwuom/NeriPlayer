package moe.ouom.neriplayer.ui.playback.visual

import android.os.Build
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.identity.sameIdentityAs

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.isRemoteImageSource

/** 封面模糊是 Coil + RenderScript 位图模糊（support mode），不依赖 Android 12 的 RenderEffect */
internal fun isNowPlayingCoverBlurSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.P

internal fun hasNowPlayingCoverBlur(
    blurAvailable: Boolean,
    blurEnabled: Boolean,
    requestedCoverUrl: String?,
    retainedCoverUrl: String?
): Boolean = blurAvailable && blurEnabled &&
    (!requestedCoverUrl.isNullOrBlank() || !retainedCoverUrl.isNullOrBlank())

internal fun currentNowPlayingBlurCoverUrl(coverUrl: String?): String? =
    coverUrl?.takeUnless(String::isBlank)

internal data class NowPlayingCoverAssetRefresh(
    val foregroundToken: Int,
    val rootGeneration: Long
) {
    fun key(songRevision: Long): Int {
        var result = foregroundToken
        result = 31 * result + rootGeneration.hashCode()
        return 31 * result + songRevision.hashCode()
    }
}

internal fun nowPlayingBlurNeighborSongs(
    queue: List<SongItem>,
    currentSong: SongItem?,
    coverUrl: String?
): List<SongItem> {
    if (currentSong == null || !isRemoteImageSource(coverUrl)) return emptyList()
    val currentIndex = queue.indexOfFirst { it.sameIdentityAs(currentSong) }
    if (currentIndex < 0) return emptyList()
    return listOfNotNull(
        queue.getOrNull(currentIndex - 1),
        queue.getOrNull(currentIndex + 1)
    )
}

internal fun nowPlayingBlurNeighborCover(
    currentCover: NowPlayingOverlayCover,
    song: SongItem,
    coverUrl: String?,
    songRevision: Long
): NowPlayingOverlayCover = NowPlayingOverlayCover(
    url = coverUrl,
    songKey = song.playbackVisualKey(),
    song = song,
    assetRefreshKey = currentCover.assetRefresh?.key(songRevision) ?: currentCover.assetRefreshKey,
    assetRefresh = currentCover.assetRefresh
)

internal fun nowPlayingBlurAssetVersion(cover: NowPlayingOverlayCover): String =
    "${cover.assetRefreshKey}:${cover.songKey}:${cover.url}"

internal fun nowPlayingBlurRequestKey(
    coverUrl: String?,
    songKey: String?,
    blurStrength: Float,
    assetVersion: String
): String? = coverUrl?.takeIf(String::isNotBlank)?.let { url ->
    "nowplaying-blur:$songKey:$url:$blurStrength:$assetVersion"
}

internal fun shouldShowStableNowPlayingBlur(
    stableCoverUrl: String?,
    requestedCoverUrl: String?,
    stableBlurStrength: Float?,
    requestedBlurStrength: Float
): Boolean = stableCoverUrl != null &&
    (stableCoverUrl != requestedCoverUrl || stableBlurStrength != requestedBlurStrength)

internal fun resolveNowPlayingBlurLoadFailure(
    latestRequestKey: String?,
    expectedRequestKey: String?,
    stableCoverUrl: String?,
    previousFailure: Boolean
): Boolean = if (latestRequestKey == expectedRequestKey) {
    stableCoverUrl.isNullOrBlank()
} else {
    previousFailure
}

internal fun shouldDisableNowPlayingBlurNetwork(
    offlineMode: Boolean,
    coverUrl: String?
): Boolean = offlineMode && isRemoteImageSource(coverUrl)

internal fun shouldUseNowPlayingBlur(hasCoverBlur: Boolean, loadFailed: Boolean): Boolean =
    hasCoverBlur && !loadFailed

internal fun selectNowPlayingAccentCoverUrl(
    useBlur: Boolean,
    stableCoverUrl: String?,
    requestedCoverUrl: String?
): String? = if (useBlur) stableCoverUrl ?: requestedCoverUrl else requestedCoverUrl
