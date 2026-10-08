package moe.ouom.neriplayer.ui.playback.visual

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.SongItem

internal data class NowPlayingBackdropRequest(
    val cover: NowPlayingOverlayCover,
    val queueFlow: StateFlow<List<SongItem>>,
    val background: NowPlayingOverlayBackground
)

private data class NowPlayingBlurNeighborPreloadInput(
    val queue: List<SongItem>,
    val frame: NowPlayingBackdropFrame,
    val effectOwner: NowPlayingBlurEffectOwner
)

@Composable
@NonRestartableComposable
internal fun NowPlayingBackdrop(request: NowPlayingBackdropRequest) {
    val effectOwner = remember { NowPlayingBlurEffectOwner() }
    NowPlayingBackdropContent(request, effectOwner)
}

@Composable
@NonRestartableComposable
private fun NowPlayingBackdropContent(
    request: NowPlayingBackdropRequest,
    effectOwner: NowPlayingBlurEffectOwner
) {
    val blurState = effectOwner.blurState
    val blurAvailable = isNowPlayingCoverBlurSupported()
    val frame = nowPlayingBackdropFrame(
        request.cover, request.background, blurState, blurAvailable
    )
    NowPlayingBlurNeighborPreloader(request.queueFlow, frame, effectOwner)
    NowPlayingBlurRetentionEffect(request, blurAvailable, effectOwner)
    NowPlayingBackdropWithCurrentKey(frame)
}

@Composable
private fun NowPlayingBlurRetentionEffect(
    request: NowPlayingBackdropRequest,
    blurAvailable: Boolean,
    effectOwner: NowPlayingBlurEffectOwner
) {
    val retentionRequest = NowPlayingBlurRetentionRequest(
        blurAvailable, request.background.blurEnabled, request.cover.songKey, request.cover.url
    )
    SideEffect(nowPlayingBlurRetentionAction(effectOwner, retentionRequest))
}

private fun nowPlayingBlurRetentionAction(
    effectOwner: NowPlayingBlurEffectOwner,
    request: NowPlayingBlurRetentionRequest
): () -> Unit = { effectOwner.updateRetention(request) }

@Composable
private fun NowPlayingBackdropWithCurrentKey(frame: NowPlayingBackdropFrame) {
    val latestCoverBlurRequestKey by rememberUpdatedState(frame.requestKey)
    NowPlayingBackdropLayers(frame.copy(latestRequestKey = latestCoverBlurRequestKey))
}

internal fun nowPlayingBackdropFrame(
    cover: NowPlayingOverlayCover,
    background: NowPlayingOverlayBackground,
    blurState: NowPlayingBlurState,
    blurAvailable: Boolean
): NowPlayingBackdropFrame {
    val blurStrength = background.blurAmount.coerceIn(0f, 500f)
    val effectiveBlurStrength = resolvedNowPlayingBlurStrength(cover.url, blurStrength)
    val assetVersion = nowPlayingBlurAssetVersion(cover)
    return NowPlayingBackdropFrame(
        cover = cover,
        background = background,
        blurState = blurState,
        hasCoverBlur = hasNowPlayingCoverBlur(
            blurAvailable, background.blurEnabled, cover.url, blurState.stableCoverUrl
        ),
        blurStrength = effectiveBlurStrength,
        imageSizePx = resolvedNowPlayingBlurImageSizePx(cover.url),
        assetVersion = assetVersion,
        requestKey = nowPlayingBlurRequestKey(
            cover.url, cover.songKey, effectiveBlurStrength, assetVersion
        ),
        latestRequestKey = null
    )
}

@Composable
@NonRestartableComposable
private fun NowPlayingBlurNeighborPreloader(
    queueFlow: StateFlow<List<SongItem>>,
    frame: NowPlayingBackdropFrame,
    effectOwner: NowPlayingBlurEffectOwner
) {
    val nowPlayingQueue by queueFlow.collectAsStateWithLifecycle()
    NowPlayingBlurNeighborPreloadEffect(
        NowPlayingBlurNeighborPreloadInput(nowPlayingQueue, frame, effectOwner)
    )
}

@Composable
@NonRestartableComposable
private fun NowPlayingBlurNeighborPreloadEffect(input: NowPlayingBlurNeighborPreloadInput) {
    val request = rememberNowPlayingBlurPreloadRequest(input)
    NowPlayingBlurPreloadSideEffect(request, input.effectOwner)
}

@Composable
private fun rememberNowPlayingBlurPreloadRequest(
    input: NowPlayingBlurNeighborPreloadInput
): NowPlayingBlurPreloadRequest {
    val context = LocalContext.current
    val preloadCoverUrls = remember(input.queue, input.frame.cover, context) {
        nowPlayingBlurNeighborUrls(
            input.queue, input.frame.cover.song, input.frame.cover.url
        ) { song ->
            song.resolveUiCoverSource(context)
        }
    }
    return nowPlayingBlurPreloadRequest(input.frame, preloadCoverUrls)
}

@Composable
private fun NowPlayingBlurPreloadSideEffect(
    request: NowPlayingBlurPreloadRequest,
    effectOwner: NowPlayingBlurEffectOwner
) {
    val context = LocalContext.current
    val imageLoader = Coil.imageLoader(context)
    SideEffect(nowPlayingBlurPreloadAction(effectOwner, request, context, imageLoader))
}

private fun nowPlayingBlurPreloadAction(
    effectOwner: NowPlayingBlurEffectOwner,
    request: NowPlayingBlurPreloadRequest,
    context: Context,
    imageLoader: ImageLoader
): () -> Unit = {
        effectOwner.updatePreload(request) { url ->
            imageLoader.enqueue(
                nowPlayingBlurImageRequest(
                    context, url, request.blurStrength, request.imageSizePx,
                    request.assetVersion, request.offlineMode
                )
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            )
        }
}
