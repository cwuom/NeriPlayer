package moe.ouom.neriplayer.ui.playback.visual

import androidx.compose.runtime.RememberObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class NowPlayingBlurCoverRequest(
    val coverUrl: String,
    val blurStrength: Float,
    val imageSizePx: Int,
    val assetVersion: String
)

internal data class NowPlayingBlurPreloadRequest(
    val hasCoverBlur: Boolean,
    val covers: List<NowPlayingBlurCoverRequest>,
    val offlineMode: Boolean
)

internal fun nowPlayingBlurPreloadRequest(
    frame: NowPlayingBackdropFrame,
    covers: List<NowPlayingOverlayCover>
): NowPlayingBlurPreloadRequest = NowPlayingBlurPreloadRequest(
    hasCoverBlur = frame.hasCoverBlur,
    covers = covers.mapNotNull { cover ->
        val coverUrl = currentNowPlayingBlurCoverUrl(cover.url) ?: return@mapNotNull null
        NowPlayingBlurCoverRequest(
            coverUrl = coverUrl,
            blurStrength = resolvedNowPlayingBlurStrength(
                coverUrl, frame.background.blurAmount.coerceIn(0f, 500f)
            ),
            imageSizePx = resolvedNowPlayingBlurImageSizePx(coverUrl),
            assetVersion = nowPlayingBlurAssetVersion(cover)
        )
    }.distinct(),
    offlineMode = frame.background.offlineMode
)

internal class NowPlayingBlurEffectOwner(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : RememberObserver {
    val blurState = NowPlayingBlurState()
    private var preloadRequest: NowPlayingBlurPreloadRequest? = null
    private var retentionRequest: NowPlayingBlurRetentionRequest? = null
    private var retentionJob: Job? = null

    fun updatePreload(
        request: NowPlayingBlurPreloadRequest,
        enqueue: (NowPlayingBlurCoverRequest) -> Unit
    ) {
        if (preloadRequest == request) return
        preloadRequest = request
        preloadNowPlayingBlurCovers(request.hasCoverBlur, request.covers, enqueue)
    }

    fun updateRetention(request: NowPlayingBlurRetentionRequest) {
        blurState.observeRequest(request.songKey, request.coverUrl)
        if (retentionRequest == request) return
        retentionRequest = request
        retentionJob?.cancel()
        retentionJob = scope.launch { blurState.reconcileRetention(request) }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() {
        scope.cancel()
    }

    override fun onAbandoned() {
        scope.cancel()
    }
}
