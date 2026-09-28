package moe.ouom.neriplayer.ui.playback.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import moe.ouom.neriplayer.ui.view.HyperBackground

internal data class NowPlayingBackdropFrame(
    val cover: NowPlayingOverlayCover,
    val background: NowPlayingOverlayBackground,
    val blurState: NowPlayingBlurState,
    val hasCoverBlur: Boolean,
    val blurStrength: Float,
    val imageSizePx: Int,
    val assetVersion: String,
    val requestKey: String?,
    val latestRequestKey: String?
)

@Composable
internal fun NowPlayingBackdropLayers(frame: NowPlayingBackdropFrame) {
    val useBlur = shouldUseNowPlayingBlur(frame.hasCoverBlur, frame.blurState.loadFailed)
    val accentCoverUrl = selectNowPlayingAccentCoverUrl(
        useBlur,
        frame.blurState.stableCoverUrl,
        frame.cover.url
    )
    NowPlayingAccentBackdrop(
        request = backdropAccentRequest(
            accentCoverUrl,
            frame.cover.songKey,
            true,
            frame.cover.assetRefreshKey,
            frame.background.offlineMode
        ),
        modifier = Modifier.fillMaxSize()
    )
    if (useBlur) {
        NowPlayingBlurredBackdrop(frame)
    } else {
        NowPlayingDynamicBackdrop(frame)
    }
}

@Composable
private fun NowPlayingBlurredBackdrop(frame: NowPlayingBackdropFrame) {
    StableNowPlayingBlurImage(frame)
    val coverUrl = currentNowPlayingBlurCoverUrl(frame.cover.url)
    if (coverUrl != null) {
        CurrentNowPlayingBlurImage(frame, coverUrl)
    }
    NowPlayingBlurDarkenLayer(frame.background.blurDarken)
}

@Composable
private fun StableNowPlayingBlurImage(frame: NowPlayingBackdropFrame) {
    val stableCoverUrl = frame.blurState.stableCoverUrl
    val stableBlurStrength = frame.blurState.stableBlurStrength
    if (!shouldShowStableNowPlayingBlur(
            stableCoverUrl,
            frame.cover.url,
            stableBlurStrength,
            frame.blurStrength
        )
    ) return

    val context = LocalContext.current
    AsyncImage(
        model = nowPlayingBlurImageRequest(
            context, stableCoverUrl, stableBlurStrength, frame.imageSizePx,
            frame.assetVersion, frame.background.offlineMode
        ).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun CurrentNowPlayingBlurImage(frame: NowPlayingBackdropFrame, coverUrl: String) {
    val context = LocalContext.current
    val callbacks = NowPlayingBlurLoadCallbacks(frame, coverUrl)
    AsyncImage(
        model = nowPlayingBlurImageRequest(
            context, coverUrl, frame.blurStrength, frame.imageSizePx,
            frame.assetVersion, frame.background.offlineMode
        ).crossfade(NOW_PLAYING_BACKGROUND_CROSSFADE_MS).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        onSuccess = callbacks.onSuccess,
        onError = callbacks.onError
    )
}

private class NowPlayingBlurLoadCallbacks(
    frame: NowPlayingBackdropFrame,
    coverUrl: String
) {
    val onSuccess: (AsyncImagePainter.State.Success) -> Unit = { _ ->
        frame.blurState.onImageSuccess(
            frame.latestRequestKey,
            frame.requestKey,
            coverUrl,
            frame.blurStrength
        )
    }
    val onError: (AsyncImagePainter.State.Error) -> Unit = { _ ->
        frame.blurState.onImageError(frame.latestRequestKey, frame.requestKey)
    }
}

@Composable
private fun NowPlayingBlurDarkenLayer(darken: Float) {
    if (darken <= 0f) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = darken.coerceIn(0f, 0.8f)))
    )
}

@Composable
private fun NowPlayingDynamicBackdrop(frame: NowPlayingBackdropFrame) {
    if (!frame.background.dynamicEnabled) return
    HyperBackground(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.80f },
        isDark = true,
        coverUrl = frame.cover.url,
        refreshKey = frame.cover.assetRefreshKey,
        offlineMode = frame.background.offlineMode,
        coverIdentityKey = frame.cover.songKey
    )
}
