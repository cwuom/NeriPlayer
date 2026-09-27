package moe.ouom.neriplayer.ui

import androidx.compose.runtime.RememberObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class NowPlayingBlurPreloadRequest(
    val hasCoverBlur: Boolean,
    val coverUrls: List<String>,
    val blurStrength: Float,
    val imageSizePx: Int,
    val assetVersion: String,
    val offlineMode: Boolean
)

internal fun nowPlayingBlurPreloadRequest(
    frame: NowPlayingBackdropFrame,
    coverUrls: List<String>
): NowPlayingBlurPreloadRequest = NowPlayingBlurPreloadRequest(
    hasCoverBlur = frame.hasCoverBlur,
    coverUrls = coverUrls,
    blurStrength = frame.blurStrength,
    imageSizePx = frame.imageSizePx,
    assetVersion = frame.assetVersion,
    offlineMode = frame.background.offlineMode
)

internal class NowPlayingBlurEffectOwner(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : RememberObserver {
    val blurState = NowPlayingBlurState()
    private var preloadRequest: NowPlayingBlurPreloadRequest? = null
    private var retentionRequest: NowPlayingBlurRetentionRequest? = null
    private var retentionJob: Job? = null

    fun updatePreload(request: NowPlayingBlurPreloadRequest, enqueue: (String) -> Unit) {
        if (preloadRequest == request) return
        preloadRequest = request
        preloadNowPlayingBlurCovers(request.hasCoverBlur, request.coverUrls, enqueue)
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
