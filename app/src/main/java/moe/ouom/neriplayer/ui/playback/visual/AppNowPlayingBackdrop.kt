package moe.ouom.neriplayer.ui.playback.visual

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.isRemoteImageSource

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
        blurAvailable, request.background.blurEnabled, request.cover.songKey,
        request.cover.url, request.cover.clearCover
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
    val effectiveCover = if (cover.clearCover) cover.copy(url = null) else cover
    val blurStrength = background.blurAmount.coerceIn(0f, 500f)
    val effectiveBlurStrength = resolvedNowPlayingBlurStrength(effectiveCover.url, blurStrength)
    val assetVersion = nowPlayingBlurAssetVersion(effectiveCover)
    return NowPlayingBackdropFrame(
        cover = effectiveCover,
        background = background,
        blurState = blurState,
        hasCoverBlur = !effectiveCover.clearCover && hasNowPlayingCoverBlur(
            blurAvailable, background.blurEnabled, effectiveCover.url, blurState.stableCoverUrl
        ),
        blurStrength = effectiveBlurStrength,
        imageSizePx = resolvedNowPlayingBlurImageSizePx(effectiveCover.url),
        assetVersion = assetVersion,
        requestKey = nowPlayingBlurRequestKey(
            effectiveCover.url, effectiveCover.songKey, effectiveBlurStrength, assetVersion
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
    if (!frame.hasCoverBlur || !isRemoteImageSource(frame.cover.url)) {
        val request = nowPlayingBlurPreloadRequest(frame, emptyList())
        SideEffect { effectOwner.updatePreload(request) {} }
        return
    }
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
    val neighborSongs = remember(input.queue, input.frame.cover.song, input.frame.cover.url) {
        nowPlayingBlurNeighborSongs(
            input.queue, input.frame.cover.song, input.frame.cover.url
        )
    }
    val covers = neighborSongs.mapIndexed { index, song ->
        val songKey = remember(song) { song.stableKey() }
        key(index, songKey) {
            val revisionFlow = remember(songKey) { LocalAssetInvalidationBus.revisionFlow(songKey) }
            val revision by revisionFlow.collectAsStateWithLifecycle(
                initialValue = LocalAssetInvalidationBus.currentSongRevision(songKey)
            )
            remember(song, input.frame.cover, revision, context) {
                nowPlayingBlurNeighborCover(
                    input.frame.cover, song, song.resolveUiCoverSource(context), revision
                )
            }
        }
    }
    return nowPlayingBlurPreloadRequest(input.frame, covers)
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
        effectOwner.updatePreload(request) { cover ->
            imageLoader.enqueue(
                nowPlayingBlurImageRequest(
                    context, cover.coverUrl, cover.blurStrength, cover.imageSizePx,
                    cover.assetVersion, request.offlineMode
                )
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            )
        }
}
