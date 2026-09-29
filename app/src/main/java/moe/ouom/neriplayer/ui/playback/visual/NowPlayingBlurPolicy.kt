package moe.ouom.neriplayer.ui.playback.visual

import moe.ouom.neriplayer.data.identity.sameIdentityAs

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.isRemoteImageSource

internal fun hasNowPlayingCoverBlur(
    blurAvailable: Boolean,
    blurEnabled: Boolean,
    requestedCoverUrl: String?,
    retainedCoverUrl: String?
): Boolean = blurAvailable && blurEnabled &&
    (!requestedCoverUrl.isNullOrBlank() || !retainedCoverUrl.isNullOrBlank())

internal fun currentNowPlayingBlurCoverUrl(coverUrl: String?): String? =
    coverUrl?.takeUnless(String::isBlank)

internal fun nowPlayingBlurNeighborUrls(
    queue: List<SongItem>,
    currentSong: SongItem?,
    coverUrl: String?,
    resolveCover: (SongItem) -> String?
): List<String> {
    if (currentSong == null || !isRemoteImageSource(coverUrl)) return emptyList()
    val currentIndex = queue.indexOfFirst { it.sameIdentityAs(currentSong) }
    if (currentIndex < 0) return emptyList()
    return listOfNotNull(
        queue.getOrNull(currentIndex - 1)?.let(resolveCover),
        queue.getOrNull(currentIndex + 1)?.let(resolveCover)
    ).distinct()
}

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
