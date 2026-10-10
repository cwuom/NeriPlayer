package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.ui.component.playback.NowPlayingCoverPreviewDialog
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceBadge
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingLyricsSharedTransitionElement
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmap
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import moe.ouom.neriplayer.util.media.copyBitmapForRetainedDisplay
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private const val NowPlayingCoverImageCrossfadeMs = 220
private const val NowPlayingCoverFrameCacheLimit = 3
private const val NowPlayingCoverBitmapMaxDimensionPx = 512
private const val NowPlayingCoverNullGraceMs = 500L

internal data class NowPlayingCoverFrame(
    val coverUrl: String,
    val cacheKey: String?,
    val decodedBitmap: ImageBitmap? = null,
    val ownerSongKey: String? = null,
    /**
     * 令牌让同一首歌重新进入时的异步图片请求彼此隔离
     */
    val requestToken: Any = Unit
)

internal data class NowPlayingCoverRequest(
    val frame: NowPlayingCoverFrame,
    val songKey: String?,
    /**
     * 每次重新进入同一封面时使用新的令牌, 防止旧的 Coil 回调污染新请求
     */
    val requestToken: Any = Unit
)

internal fun buildNowPlayingCoverRequest(
    coverUrl: String?,
    songKey: String?,
    coverCacheKey: String?,
    requestToken: Any = Unit
): NowPlayingCoverRequest? {
    val normalizedCoverUrl = normalizedCoverValue(coverUrl) ?: return null
    return NowPlayingCoverRequest(
        frame = NowPlayingCoverFrame(
            coverUrl = normalizedCoverUrl,
            cacheKey = coverCacheKey?.let { coverRequestCacheKey(it, normalizedCoverUrl) },
            ownerSongKey = songKey,
            requestToken = requestToken
        ),
        songKey = songKey,
        requestToken = requestToken
    )
}

private fun normalizedCoverValue(value: String?): String? =
    value?.trim()?.takeIf(String::isNotEmpty)

private fun coverRequestCacheKey(cacheKey: String, coverUrl: String): String =
    "$cacheKey|data=$coverUrl"

internal fun buildNowPlayingCoverCacheKey(
    coverUrl: String?,
    downloadPresenceVersion: Int,
    assetRootGeneration: Long,
    assetSongRevision: Long
): String {
    return listOf(
        "nowplaying-cover",
        coverUrl.orEmpty(),
        downloadPresenceVersion,
        assetRootGeneration,
        assetSongRevision
    ).joinToString("|")
}

internal fun resolveNowPlayingCoverOwnerKey(
    currentSongKey: String?,
    parentSongKey: String?
): String? = currentSongKey ?: parentSongKey

internal fun shouldOpenNowPlayingCoverPreviewOnTap(song: SongItem?): Boolean =
    song != null && !song.isLocalSong()

internal fun shouldOpenNowPlayingCoverPreviewOnLongPress(song: SongItem?): Boolean =
    song != null

internal enum class NowPlayingCoverPreviewAttempt {
    OPENED,
    UNAVAILABLE,
    IGNORED
}

internal fun resolveNowPlayingCoverCacheKeys(
    requestSongKey: String?,
    latestRequestSongKey: String?,
    latestSongKeyAliases: List<String>
): List<String> {
    val key = normalizedCoverValue(requestSongKey) ?: return emptyList()
    if (key != latestRequestSongKey) return listOf(key)
    return listOf(key).plus(latestSongKeyAliases.mapNotNull(::normalizedCoverValue)).distinct()
}

internal fun shouldCommitNowPlayingCoverRequest(
    completedRequest: NowPlayingCoverRequest,
    latestRequest: NowPlayingCoverRequest?
): Boolean = completedRequest == latestRequest

internal fun shouldHandleNowPlayingCoverError(
    failedRequest: NowPlayingCoverRequest?,
    latestRequest: NowPlayingCoverRequest?
): Boolean {
    return failedRequest != null && latestRequest != null &&
        shouldCommitNowPlayingCoverRequest(failedRequest, latestRequest)
}

internal fun sameNowPlayingCoverFrame(
    first: NowPlayingCoverFrame?,
    second: NowPlayingCoverFrame?
): Boolean {
    if (first == null || second == null) return first == second
    return first.coverUrl == second.coverUrl
}

private fun sameNowPlayingCoverSource(
    first: NowPlayingCoverFrame?,
    second: NowPlayingCoverFrame?
): Boolean {
    if (first == null || second == null) return first == second
    return first.coverUrl == second.coverUrl &&
        first.cacheKey == second.cacheKey &&
        first.ownerSongKey == second.ownerSongKey
}

internal fun shouldKeepNowPlayingCoverVisible(
    currentSongKey: String?,
    displayedFrame: NowPlayingCoverFrame?,
    requestedFrame: NowPlayingCoverFrame?
): Boolean {
    return currentSongKey != null || displayedFrame != null || requestedFrame != null
}

internal fun sameNowPlayingCoverRequestFrame(
    first: NowPlayingCoverFrame?,
    second: NowPlayingCoverFrame?
): Boolean = first?.requestIdentity() == second?.requestIdentity()

private data class NowPlayingCoverRequestIdentity(
    val coverUrl: String,
    val cacheKey: String?,
    val ownerSongKey: String?,
    val requestToken: Any
)

private fun NowPlayingCoverFrame.requestIdentity(): NowPlayingCoverRequestIdentity =
    NowPlayingCoverRequestIdentity(coverUrl, cacheKey, ownerSongKey, requestToken)

private fun resolveNowPlayingCoverBitmap(
    state: AsyncImagePainter.State.Success,
    sizePx: Int
): ImageBitmap? = runCatching {
    val maxDimension = min(sizePx.coerceAtLeast(1), NowPlayingCoverBitmapMaxDimensionPx)
    decodeNowPlayingCoverDrawable(state.result.drawable, maxDimension)
}.getOrNull()

private fun decodeNowPlayingCoverDrawable(
    drawable: Drawable,
    maxDimension: Int
): ImageBitmap? = if (drawable is BitmapDrawable) {
    decodeNowPlayingBitmapDrawable(drawable.bitmap, maxDimension)
} else {
    drawable.toBitmap(
        width = maxDimension,
        height = maxDimension,
        config = Bitmap.Config.ARGB_8888
    ).asImageBitmap()
}

private fun decodeNowPlayingBitmapDrawable(
    sourceBitmap: Bitmap,
    maxDimension: Int
): ImageBitmap? {
    val scale = min(
        maxDimension.toFloat() / sourceBitmap.width.coerceAtLeast(1),
        maxDimension.toFloat() / sourceBitmap.height.coerceAtLeast(1)
    ).coerceAtMost(1f)
    return if (scale < 1f) scaledNowPlayingCoverBitmap(sourceBitmap, scale)
    else copyOriginalNowPlayingCoverBitmap(sourceBitmap)
}

private fun copyOriginalNowPlayingCoverBitmap(sourceBitmap: Bitmap): ImageBitmap? =
    copyBitmapForRetainedDisplay(sourceBitmap)?.asImageBitmap()

private fun scaledNowPlayingCoverBitmap(
    sourceBitmap: Bitmap,
    scale: Float
): ImageBitmap = sourceBitmap.scale(
    (sourceBitmap.width * scale).roundToInt().coerceAtLeast(1),
    (sourceBitmap.height * scale).roundToInt().coerceAtLeast(1),
    true
).asImageBitmap()

internal fun retainNowPlayingCoverFrame(
    displayedFrame: NowPlayingCoverFrame?,
    hasCurrentSong: Boolean
): NowPlayingCoverFrame? {
    return displayedFrame.takeIf { hasCurrentSong }
}

internal fun shouldClearNowPlayingCoverFrame(
    currentSongKey: String?,
    requestedCoverUrl: String?,
    clearDelayElapsed: Boolean
): Boolean {
    return currentSongKey == null && requestedCoverUrl == null && clearDelayElapsed
}

internal fun shouldClearNowPlayingRetainedCoverAfterGrace(
    currentSongKey: String?,
    requestedCoverUrl: String?,
    hasRetainedFrame: Boolean,
    requestFailed: Boolean,
    clearDelayElapsed: Boolean
): Boolean {
    if (!clearDelayElapsed || !hasRetainedFrame) return false
    return shouldClearStoppedNowPlayingCover(currentSongKey, requestedCoverUrl, requestFailed)
}

private fun shouldClearStoppedNowPlayingCover(
    currentSongKey: String?, requestedCoverUrl: String?, requestFailed: Boolean
): Boolean = currentSongKey.isNullOrBlank() &&
    (normalizedCoverValue(requestedCoverUrl) == null || requestFailed)

internal fun shouldRetainNowPlayingCoverOnError(
    currentSongKey: String?,
    displayedFrame: NowPlayingCoverFrame?
): Boolean = currentSongKey != null || displayedFrame != null

internal fun resolveNowPlayingCoverRequestUrl(
    resolvedCoverUrl: String?,
    visualCoverUrl: String?,
    visualCoverSongKey: String?,
    currentSongKey: String?,
    resolvedCoverSongKey: String? = null,
    resolvedCoverOwnerRequired: Boolean = false
): String? {
    val resolved = normalizedCoverValue(resolvedCoverUrl)
    if (resolved != null && resolvedCoverBelongsToSong(
            resolvedCoverSongKey, currentSongKey, resolvedCoverOwnerRequired
        )) return resolved
    return visualCoverForSong(visualCoverUrl, visualCoverSongKey, currentSongKey)
}

private fun resolvedCoverBelongsToSong(
    resolvedSongKey: String?, currentSongKey: String?, ownerRequired: Boolean
): Boolean {
    if (resolvedSongKey != null) return currentSongKey == null || resolvedSongKey == currentSongKey
    return !ownerRequired
}

private fun visualCoverForSong(
    visualCoverUrl: String?, visualSongKey: String?, currentSongKey: String?
): String? {
    val visual = normalizedCoverValue(visualCoverUrl) ?: return null
    return visual.takeIf { currentSongKey != null &&
        (visualSongKey == null || visualSongKey == currentSongKey) }
}

internal fun resolveNowPlayingVisibleCoverFrame(
    displayedFrame: NowPlayingCoverFrame?,
    requestedFrame: NowPlayingCoverFrame?,
    hasCurrentSong: Boolean,
    cachedFrame: NowPlayingCoverFrame? = null,
    failedRequest: NowPlayingCoverRequest? = null,
    clearRetainedFrame: Boolean = false
): NowPlayingCoverFrame? {
    if (!hasCurrentSong || clearRetainedFrame) return null
    val failedFrame = failedRequest?.frame
    val retainedFrame = newestCompatibleCoverFrame(cachedFrame ?: displayedFrame, requestedFrame)
    if (retainedFrame?.decodedBitmap != null) return retainedFrame
    if (shouldShowPendingRetainedFrame(retainedFrame, failedFrame)) return retainedFrame
    return visibleRequestedCoverFrame(requestedFrame, failedFrame)
}

private fun visibleRequestedCoverFrame(
    requested: NowPlayingCoverFrame?, failed: NowPlayingCoverFrame?
): NowPlayingCoverFrame? = requested?.takeUnless { isFailedCoverFrame(it, failed) }

private fun newestCompatibleCoverFrame(
    retained: NowPlayingCoverFrame?, requested: NowPlayingCoverFrame?
): NowPlayingCoverFrame? {
    if (retained == null || requested == null) return retained
    if (retained.decodedBitmap != null) return retained
    return requested.takeIf { sameNowPlayingCoverSource(retained, it) } ?: retained
}

private fun shouldShowPendingRetainedFrame(
    retained: NowPlayingCoverFrame?, failed: NowPlayingCoverFrame?
): Boolean = retained != null && !isFailedCoverFrame(retained, failed)

private fun isFailedCoverFrame(
    frame: NowPlayingCoverFrame, failed: NowPlayingCoverFrame?
): Boolean = failed != null && sameNowPlayingCoverRequestFrame(frame, failed)

internal fun isNowPlayingCachedCoverFrameCompatible(
    cachedFrame: NowPlayingCoverFrame?,
    requestedFrame: NowPlayingCoverFrame?
): Boolean {
    if (!hasDecodedCoverFrame(cachedFrame)) return false
    return requestedFrame == null || sameNowPlayingCoverSource(cachedFrame, requestedFrame)
}

internal fun isNowPlayingRetainedCoverFrameCompatible(
    cachedFrame: NowPlayingCoverFrame?,
    requestedFrame: NowPlayingCoverFrame?
): Boolean {
    if (!hasDecodedCoverFrame(cachedFrame)) return false
    return requestedFrame == null || sameRetainedCoverOwner(requireNotNull(cachedFrame), requestedFrame)
}

private fun hasDecodedCoverFrame(frame: NowPlayingCoverFrame?): Boolean =
    frame?.decodedBitmap != null

private fun sameRetainedCoverOwner(
    cachedFrame: NowPlayingCoverFrame, requestedFrame: NowPlayingCoverFrame
): Boolean = cachedFrame.coverUrl == requestedFrame.coverUrl &&
    cachedFrame.ownerSongKey == requestedFrame.ownerSongKey

internal fun shouldAnimateNowPlayingCoverFrame(
    previousFrame: NowPlayingCoverFrame?,
    targetFrame: NowPlayingCoverFrame?
): Boolean {
    if (previousFrame == null || targetFrame == null) return false
    return !sameNowPlayingCoverFrame(previousFrame, targetFrame)
}

internal data class NowPlayingCoverPresentation(
    val requestedCover: NowPlayingCoverRequest?,
    val failedRequest: NowPlayingCoverRequest?,
    val currentDisplayedFrame: NowPlayingCoverFrame?,
    val visibleFrame: NowPlayingCoverFrame?,
    val animateVisibleFrame: Boolean
)

private data class NowPlayingCoverInputKey(
    val songKey: String?,
    val request: NowPlayingCoverRequest?
)

private data class NowPlayingCoverGraceKey(
    val input: NowPlayingCoverInputKey,
    val failedRequest: NowPlayingCoverRequest?,
    val retainedFrame: NowPlayingCoverFrame
)

internal class NowPlayingCoverOwner(
    private val scope: CoroutineScope,
    private val nullGraceMs: Long = NowPlayingCoverNullGraceMs
) {
    private val decodedFramesBySongKey = mutableStateMapOf<String, NowPlayingCoverFrame>()
    private var latestRequest: NowPlayingCoverRequest? = null
    private var latestAliases: List<String> = emptyList()
    private var previousVisibleFrame by mutableStateOf<NowPlayingCoverFrame?>(null)
    private var failedCoverRequest by mutableStateOf<NowPlayingCoverRequest?>(null)
    private var clearedInput by mutableStateOf<NowPlayingCoverInputKey?>(null)
    private var graceKey: NowPlayingCoverGraceKey? = null
    private var graceJob: Job? = null
    private val retainedFrameToken = Any()
    private var previewSessionKey: String? = null
    private var previewOpen by mutableStateOf(false)
    private var composedRequestInput: NowPlayingCoverRequestInput? = null
    private var composedRequest: NowPlayingCoverRequest? = null
    var displayedFrame by mutableStateOf<NowPlayingCoverFrame?>(null)
        private set

    val cachedSongKeys: Set<String> get() = decodedFramesBySongKey.keys.toSet()
    val currentFailedRequest: NowPlayingCoverRequest?
        get() = failedCoverRequest?.takeIf { shouldHandleNowPlayingCoverError(it, latestRequest) }

    fun previewOpenFor(sessionKey: String?): Boolean =
        previewOpen && previewSessionKey == sessionKey

    fun openPreview(sessionKey: String?, coverUrl: String?): Boolean {
        if (coverUrl.isNullOrBlank()) return false
        previewSessionKey = sessionKey
        previewOpen = true
        return true
    }

    fun tryOpenPreview(
        song: SongItem?,
        longPress: Boolean,
        sessionKey: String?,
        coverUrl: String?
    ): NowPlayingCoverPreviewAttempt {
        val allowed = if (longPress) shouldOpenNowPlayingCoverPreviewOnLongPress(song)
        else shouldOpenNowPlayingCoverPreviewOnTap(song)
        if (!allowed) return NowPlayingCoverPreviewAttempt.IGNORED
        return if (openPreview(sessionKey, coverUrl)) NowPlayingCoverPreviewAttempt.OPENED
        else NowPlayingCoverPreviewAttempt.UNAVAILABLE
    }

    fun openPreviewOrNotify(
        song: SongItem?,
        longPress: Boolean,
        sessionKey: String?,
        coverUrl: String?,
        onUnavailable: () -> Unit
    ) {
        if (tryOpenPreview(song, longPress, sessionKey, coverUrl) ==
            NowPlayingCoverPreviewAttempt.UNAVAILABLE) onUnavailable()
    }

    fun closePreview() {
        previewOpen = false
    }

    fun downloadPreviewAction(onDownload: () -> Unit): () -> Unit = {
        closePreview()
        onDownload()
    }

    fun onPreviewSessionChanged(sessionKey: String?) {
        if (previewOpen && previewSessionKey != sessionKey) closePreview()
    }

    fun visiblePreviewUrl(sessionKey: String?, coverUrl: String?): String? =
        coverUrl?.takeIf { previewOpenFor(sessionKey) && it.isNotBlank() }

    fun requestFor(input: NowPlayingCoverRequestInput): NowPlayingCoverRequest? {
        if (input != composedRequestInput) {
            composedRequestInput = input
            composedRequest = newRequest(input.coverUrl, input.songKey, input.cacheKey)
        }
        return composedRequest
    }

    fun newRequest(coverUrl: String?, songKey: String?, coverCacheKey: String?): NowPlayingCoverRequest? {
        cancelGraceClear()
        val request = buildNowPlayingCoverRequest(coverUrl, songKey, coverCacheKey, Any())
        latestRequest = request
        return request
    }

    fun presentation(
        request: NowPlayingCoverRequest?,
        songKey: String?,
        songKeyAliases: List<String>
    ): NowPlayingCoverPresentation {
        val aliases = normalizedCoverAliases(songKey, songKeyAliases)
        val failed = currentFailureFor(request)
        val frames = displayedCoverFrames(aliases, request?.frame)
        latestAliases = aliases
        return buildPresentation(request, songKey, failed, frames)
    }

    private fun currentFailureFor(request: NowPlayingCoverRequest?): NowPlayingCoverRequest? =
        failedCoverRequest?.takeIf { shouldHandleNowPlayingCoverError(it, request) }

    private data class DisplayedCoverFrames(
        val cached: NowPlayingCoverFrame?,
        val current: NowPlayingCoverFrame?
    )

    private fun displayedCoverFrames(
        aliases: List<String>, requestedFrame: NowPlayingCoverFrame?
    ): DisplayedCoverFrames {
        val cached = cachedFrame(aliases, requestedFrame)
        val retained = retainedBitmapFrame(aliases, requestedFrame)
        return DisplayedCoverFrames(cached, cached ?: retained ?: displayedFrame)
    }

    private fun buildPresentation(
        request: NowPlayingCoverRequest?,
        songKey: String?,
        failed: NowPlayingCoverRequest?,
        frames: DisplayedCoverFrames
    ): NowPlayingCoverPresentation {
        val visible = resolveNowPlayingVisibleCoverFrame(
            displayedFrame = frames.current,
            requestedFrame = request?.frame,
            hasCurrentSong = shouldKeepNowPlayingCoverVisible(songKey, frames.current, request?.frame),
            cachedFrame = frames.cached,
            failedRequest = failed,
            clearRetainedFrame = clearedInput == NowPlayingCoverInputKey(songKey, request)
        )
        return NowPlayingCoverPresentation(
            request, failed, frames.current, visible,
            shouldAnimateNowPlayingCoverFrame(previousVisibleFrame, visible)
        )
    }

    fun onPresented(songKey: String?, presentation: NowPlayingCoverPresentation) {
        if (previousVisibleFrame != presentation.visibleFrame) {
            previousVisibleFrame = presentation.visibleFrame
        }
        scheduleRetainedFrameClear(songKey, presentation)
    }

    fun publishDecodedFrame(request: NowPlayingCoverRequest, frame: NowPlayingCoverFrame) {
        if (!shouldCommitNowPlayingCoverRequest(request, latestRequest)) return
        cancelGraceClear()
        displayedFrame = frame
        failedCoverRequest = null
        clearedInput = null
        cacheDecodedFrame(request, frame)
    }

    private fun cacheDecodedFrame(request: NowPlayingCoverRequest, frame: NowPlayingCoverFrame) {
        resolveNowPlayingCoverCacheKeys(request.songKey, latestRequest?.songKey, latestAliases)
            .forEach { key ->
                decodedFramesBySongKey[key] = frame
                frame.decodedBitmap?.let { bitmap ->
                    RetainedPlaybackCoverBitmapCache.put(key, frame.coverUrl, frame.cacheKey, bitmap)
                }
                trimDecodedFrames()
            }
    }

    private fun trimDecodedFrames() {
        while (decodedFramesBySongKey.size > NowPlayingCoverFrameCacheLimit) {
            val oldestKey = decodedFramesBySongKey.keys.firstOrNull() ?: break
            decodedFramesBySongKey.remove(oldestKey)
        }
    }

    fun rejectDecodedFrame(frame: NowPlayingCoverFrame, failedRequest: NowPlayingCoverRequest?) {
        if (!shouldHandleNowPlayingCoverError(failedRequest, latestRequest)) return
        if (hasDecodedFrameFor(frame)) return
        cancelGraceClear()
        failedCoverRequest = failedRequest
        // 请求失败时保留当前可见帧, 等停止播放后的宽限期再清理
        decodedFramesBySongKey.entries.removeAll { (_, cachedFrame) ->
            shouldRemoveFailedPendingFrame(cachedFrame, frame)
        }
    }

    private fun shouldRemoveFailedPendingFrame(
        cachedFrame: NowPlayingCoverFrame, failedFrame: NowPlayingCoverFrame
    ): Boolean = cachedFrame.decodedBitmap == null &&
        sameNowPlayingCoverRequestFrame(cachedFrame, failedFrame)

    private fun hasDecodedFrameFor(frame: NowPlayingCoverFrame): Boolean =
        listOfNotNull(displayedFrame)
            .plus(decodedFramesBySongKey.values)
            .any { it.decodedBitmap != null && sameNowPlayingCoverRequestFrame(it, frame) }

    fun completeDecodedFrame(
        request: NowPlayingCoverRequest,
        frame: NowPlayingCoverFrame,
        state: AsyncImagePainter.State.Success,
        sizePx: Int
    ) {
        if (!shouldCommitNowPlayingCoverRequest(request, latestRequest)) return
        publishDecodedResult(request, frame, resolveNowPlayingCoverBitmap(state, sizePx))
    }

    private fun publishDecodedResult(
        request: NowPlayingCoverRequest,
        frame: NowPlayingCoverFrame,
        bitmap: ImageBitmap?
    ) {
        if (bitmap != null) publishDecodedFrame(request, frame.copy(decodedBitmap = bitmap))
        else rejectDecodedFrame(frame, request)
    }

    fun completeVisibleFrame(
        request: NowPlayingCoverRequest?,
        frame: NowPlayingCoverFrame,
        state: AsyncImagePainter.State.Success,
        sizePx: Int
    ) {
        val activeRequest = request ?: return
        completeMatchingVisibleFrame(activeRequest, frame, state, sizePx)
    }

    private fun completeMatchingVisibleFrame(
        request: NowPlayingCoverRequest,
        frame: NowPlayingCoverFrame,
        state: AsyncImagePainter.State.Success,
        sizePx: Int
    ) {
        if (request.frame == frame) completeDecodedFrame(request, frame, state, sizePx)
    }

    fun rejectVisibleFrame(request: NowPlayingCoverRequest?, frame: NowPlayingCoverFrame) {
        val activeRequest = request ?: return
        rejectMatchingVisibleFrame(activeRequest, frame)
    }

    private fun rejectMatchingVisibleFrame(request: NowPlayingCoverRequest, frame: NowPlayingCoverFrame) {
        if (request.frame == frame) rejectDecodedFrame(frame, request)
    }

    private fun cachedFrame(
        aliases: List<String>,
        requestedFrame: NowPlayingCoverFrame?
    ): NowPlayingCoverFrame? = aliases.asSequence()
        .mapNotNull(decodedFramesBySongKey::get)
        .firstOrNull { isNowPlayingCachedCoverFrameCompatible(it, requestedFrame) }

    private fun retainedBitmapFrame(
        aliases: List<String>,
        requestedFrame: NowPlayingCoverFrame?
    ): NowPlayingCoverFrame? {
        val sharedEntry = findSharedRetainedFrame(aliases, requestedFrame) ?: return null
        return frameFromRetainedEntry(sharedEntry, requestedFrame)
            .takeIf { isNowPlayingRetainedCoverFrameCompatible(it, requestedFrame) }
    }

    private fun findSharedRetainedFrame(
        aliases: List<String>, requestedFrame: NowPlayingCoverFrame?
    ): RetainedPlaybackCoverBitmap? =
        requestedFrame?.let { RetainedPlaybackCoverBitmapCache.getExact(it.ownerSongKey, it.coverUrl) }
            ?: aliases.asSequence()
                .mapNotNull(RetainedPlaybackCoverBitmapCache::getLatestForOwner)
                .firstOrNull()

    private fun frameFromRetainedEntry(
        entry: RetainedPlaybackCoverBitmap,
        requestedFrame: NowPlayingCoverFrame?
    ): NowPlayingCoverFrame = NowPlayingCoverFrame(
        coverUrl = entry.coverUrl,
        cacheKey = entry.cacheKey,
        decodedBitmap = entry.bitmap,
        ownerSongKey = entry.ownerKey,
        requestToken = retainedRequestToken(requestedFrame)
    )

    private fun retainedRequestToken(requestedFrame: NowPlayingCoverFrame?): Any =
        if (requestedFrame == null) retainedFrameToken else requestedFrame.requestToken

    private fun scheduleRetainedFrameClear(songKey: String?, presentation: NowPlayingCoverPresentation) {
        val input = NowPlayingCoverInputKey(songKey, presentation.requestedCover)
        val candidate = graceCandidate(input, presentation)
        if (candidate == graceKey) return
        cancelGraceClear()
        graceKey = candidate
        if (candidate != null && clearedInput != input) {
            graceJob = scope.launch {
                delay(nullGraceMs.milliseconds)
                clearRetainedFrameIfCurrent(candidate)
            }
        }
    }

    private fun graceCandidate(
        input: NowPlayingCoverInputKey,
        presentation: NowPlayingCoverPresentation
    ): NowPlayingCoverGraceKey? {
        if (input.songKey != null || input.request != null) return null
        val retained = presentation.currentDisplayedFrame ?: return null
        return NowPlayingCoverGraceKey(input, presentation.failedRequest, retained)
    }

    private fun clearRetainedFrameIfCurrent(candidate: NowPlayingCoverGraceKey) {
        if (candidate != graceKey) return
        clearRetainedFrame(candidate)
    }

    private fun clearRetainedFrame(candidate: NowPlayingCoverGraceKey) {
        // 宽限 Job 只为没有歌曲和请求的帧创建, 新请求会取消它
        clearedInput = candidate.input
        displayedFrame = null
        decodedFramesBySongKey.entries.removeAll { (_, frame) -> frame == candidate.retainedFrame }
    }

    private fun cancelGraceClear() {
        graceJob?.cancel()
        graceJob = null
        graceKey = null
    }

    fun close() {
        cancelGraceClear()
    }
}

private fun normalizedCoverAliases(songKey: String?, songKeyAliases: List<String>): List<String> =
    listOfNotNull(songKey).plus(songKeyAliases).filter(String::isNotBlank).distinct()

@Composable
internal fun rememberNowPlayingCoverOwner(): NowPlayingCoverOwner {
    val scope = rememberCoroutineScope()
    val owner = remember(scope) { NowPlayingCoverOwner(scope) }
    DisposableEffect(owner) { onDispose(owner::close) }
    return owner
}

internal data class NowPlayingCoverSource(
    val coverUrl: String?,
    val songKey: String?,
    val songKeyAliases: List<String>,
    val cacheKey: String,
    val contentDescription: String
)

internal fun buildNowPlayingCoverSource(
    coverUrl: String?,
    songKey: String?,
    songKeyAliases: List<String>,
    downloadPresenceVersion: Int,
    assetRootGeneration: Long,
    assetSongRevision: Long,
    song: SongItem?
): NowPlayingCoverSource = NowPlayingCoverSource(
    coverUrl = coverUrl,
    songKey = songKey,
    songKeyAliases = songKeyAliases,
    cacheKey = buildNowPlayingCoverCacheKey(
        coverUrl, downloadPresenceVersion, assetRootGeneration, assetSongRevision
    ),
    contentDescription = song?.displayName().orEmpty()
)

internal fun Modifier.nowPlayingCoverPanelModifier(
    wideLandscape: Boolean,
    columnScope: ColumnScope
): Modifier = with(columnScope) {
    if (wideLandscape) this@nowPlayingCoverPanelModifier.fillMaxWidth()
    else this@nowPlayingCoverPanelModifier.align(Alignment.CenterHorizontally)
}

internal fun resolveNowPlayingCoverSize(
    wideLandscape: Boolean,
    landscape: Boolean,
    windowWidth: Dp,
    maxWidth: Dp,
    maxHeight: Dp
): Dp = when {
    wideLandscape -> minOf(windowWidth * 0.40f, maxWidth * 0.82f, maxHeight * 0.42f)
    landscape -> minOf(windowWidth * 0.45f, maxHeight * 0.5f, maxWidth)
    else -> minOf(maxWidth * 0.6f, maxHeight * 0.65f)
}

internal fun resolveNowPlayingAdaptiveCoverSize(
    maxWidth: Dp,
    maxHeight: Dp,
    preferredSize: Dp
): Dp = minOf(maxWidth, maxHeight, preferredSize).coerceAtLeast(0.dp)

internal data class NowPlayingCoverViewport(
    val windowWidth: Dp,
    val landscape: Boolean,
    val wideLandscape: Boolean
)

internal fun resolveNowPlayingCoverViewport(windowWidth: Dp, orientation: Int): NowPlayingCoverViewport {
    val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
    return NowPlayingCoverViewport(windowWidth, landscape, landscape && windowWidth >= 480.dp)
}

@Composable
private fun currentNowPlayingCoverViewport(): NowPlayingCoverViewport {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return resolveNowPlayingCoverViewport(width, LocalConfiguration.current.orientation)
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun NowPlayingCoverPanel(
    owner: NowPlayingCoverOwner,
    source: NowPlayingCoverSource,
    song: SongItem?,
    previewSessionKey: String?,
    offlineMode: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    showSourceBadge: Boolean,
    animateSourceBadge: Boolean,
    playbackSourceType: PlaybackSourceType?,
    onPreviewUnavailable: () -> Unit,
    modifier: Modifier,
    preferredSize: Dp? = null
) {
    val viewport = currentNowPlayingCoverViewport()
    NowPlayingCoverSizedPanel(
        owner, source, song, previewSessionKey, offlineMode,
        sharedTransitionScope, animatedVisibilityScope, showSourceBadge,
        animateSourceBadge, playbackSourceType, onPreviewUnavailable,
        viewport, modifier, preferredSize
    )
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun NowPlayingCoverSizedPanel(
    owner: NowPlayingCoverOwner,
    source: NowPlayingCoverSource,
    song: SongItem?,
    previewSessionKey: String?,
    offlineMode: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    showSourceBadge: Boolean,
    animateSourceBadge: Boolean,
    playbackSourceType: PlaybackSourceType?,
    onPreviewUnavailable: () -> Unit,
    viewport: NowPlayingCoverViewport,
    modifier: Modifier,
    preferredSize: Dp?
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val coverSize = if (preferredSize != null) {
            resolveNowPlayingAdaptiveCoverSize(maxWidth, maxHeight, preferredSize)
        } else {
            resolveNowPlayingCoverSize(
                viewport.wideLandscape, viewport.landscape, viewport.windowWidth, maxWidth, maxHeight
            )
        }
        val requestSizePx = with(density) { coverSize.roundToPx().coerceAtLeast(256) }
        Box(modifier = Modifier.align(Alignment.Center).size(coverSize)) {
            NowPlayingCoverPanelImage(
                owner = owner,
                content = NowPlayingCoverPanelImageContent(source, offlineMode, requestSizePx),
                preview = NowPlayingCoverPanelPreview(song, previewSessionKey, onPreviewUnavailable),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
            NowPlayingCoverSourceBadge(
                showSourceBadge, animateSourceBadge, playbackSourceType,
                Modifier.align(Alignment.BottomEnd).padding(10.dp)
            )
        }
    }
}

private data class NowPlayingCoverPanelImageContent(
    val source: NowPlayingCoverSource,
    val offlineMode: Boolean,
    val requestSizePx: Int
)

private data class NowPlayingCoverPanelPreview(
    val song: SongItem?,
    val sessionKey: String?,
    val onUnavailable: () -> Unit
) {
    fun action(owner: NowPlayingCoverOwner, coverUrl: String?, longPress: Boolean): () -> Unit = {
        owner.openPreviewOrNotify(song, longPress, sessionKey, coverUrl, onUnavailable)
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun NowPlayingCoverPanelImage(
    owner: NowPlayingCoverOwner,
    content: NowPlayingCoverPanelImageContent,
    preview: NowPlayingCoverPanelPreview,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val sharedModifier = Modifier.nowPlayingCoverSharedModifier(
        sharedTransitionScope, animatedVisibilityScope
    )
    NowPlayingCoverInteractiveImage(owner, content, preview, sharedModifier)
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.nowPlayingCoverSharedModifier(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = with(sharedTransitionScope) {
    this@nowPlayingCoverSharedModifier.sharedElement(
        rememberSharedContentState(NowPlayingLyricsSharedTransitionElement.COVER.key),
        animatedVisibilityScope = animatedVisibilityScope
    )
}

@Composable
private fun NowPlayingCoverInteractiveImage(
    owner: NowPlayingCoverOwner,
    content: NowPlayingCoverPanelImageContent,
    preview: NowPlayingCoverPanelPreview,
    modifier: Modifier
) {
    val previewModifier = Modifier.nowPlayingCoverPreviewModifier(
        preview.song,
        onTap = preview.action(owner, content.source.coverUrl, longPress = false),
        onLongPress = preview.action(owner, content.source.coverUrl, longPress = true)
    )
    val imageModifier = Modifier.fillMaxSize()
        .then(modifier)
        .clip(RoundedCornerShape(24.dp))
        .background(coverSurfaceColor(content.source.coverUrl, MaterialTheme.colorScheme.primaryContainer))
        .then(previewModifier)
    NowPlayingCoverImageFrame(
        owner, content.source, content.offlineMode, content.requestSizePx, imageModifier
    )
}

@Composable
private fun NowPlayingCoverImageFrame(
    owner: NowPlayingCoverOwner,
    source: NowPlayingCoverSource,
    offlineMode: Boolean,
    requestSizePx: Int,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        StableNowPlayingCoverImage(
            owner = owner,
            coverUrl = source.coverUrl,
            songKey = source.songKey,
            songKeyAliases = source.songKeyAliases,
            context = LocalContext.current,
            coverRequestSizePx = requestSizePx,
            offlineMode = offlineMode,
            coverCacheKey = source.cacheKey,
            contentDescription = source.contentDescription,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun coverSurfaceColor(coverUrl: String?, placeholder: Color): Color =
    if (coverUrl == null) placeholder else Color.Transparent

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.nowPlayingCoverPreviewModifier(
    song: SongItem?,
    onTap: () -> Unit,
    onLongPress: () -> Unit
): Modifier = if (song != null) {
    combinedClickable(
        onClick = onTap,
        onLongClick = onLongPress
    )
} else this

@Composable
private fun NowPlayingCoverSourceBadge(
    show: Boolean,
    animate: Boolean,
    sourceType: PlaybackSourceType?,
    modifier: Modifier
) {
    val scale by animateFloatAsState(
        targetValue = nowPlayingCoverBadgeScaleTarget(show, sourceType),
        animationSpec = nowPlayingCoverBadgeAnimationSpec(animate),
        label = "cover_source_badge_scale"
    )
    NowPlayingCoverVisibleBadge(sourceType.takeIf { show }, scale, modifier)
}

internal fun nowPlayingCoverBadgeScaleTarget(show: Boolean, sourceType: PlaybackSourceType?): Float =
    if (show && sourceType != null) 1f else 0f

private fun nowPlayingCoverBadgeAnimationSpec(animate: Boolean): AnimationSpec<Float> =
    if (animate) tween(durationMillis = 520, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
    else snap()

@Composable
private fun NowPlayingCoverVisibleBadge(
    sourceType: PlaybackSourceType?,
    scale: Float,
    modifier: Modifier
) {
    sourceType?.let { source -> NowPlayingCoverBadgeImage(source, scale, modifier) }
}

@Composable
private fun NowPlayingCoverBadgeImage(
    source: PlaybackSourceType,
    scale: Float,
    modifier: Modifier
) {
    PlaybackSourceBadge(
        source = source,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = scale
        }
    )
}

@Composable
internal fun NowPlayingCoverPreviewHost(
    owner: NowPlayingCoverOwner,
    previewSessionKey: String?,
    coverUrl: String?,
    songName: String,
    offlineMode: Boolean,
    onDownload: () -> Unit
) {
    NowPlayingCoverPreviewSessionEffect(owner, previewSessionKey)
    NowPlayingCoverActivePreviewHost(
        owner, owner.visiblePreviewUrl(previewSessionKey, coverUrl), songName, offlineMode, onDownload
    )
}

@Composable
private fun NowPlayingCoverPreviewSessionEffect(
    owner: NowPlayingCoverOwner,
    previewSessionKey: String?
) {
    SideEffect { owner.onPreviewSessionChanged(previewSessionKey) }
}

@Composable
private fun NowPlayingCoverActivePreviewHost(
    owner: NowPlayingCoverOwner,
    visibleUrl: String?,
    songName: String,
    offlineMode: Boolean,
    onDownload: () -> Unit
) {
    if (visibleUrl == null) return
    NowPlayingCoverPreviewDialog(
        coverUrl = visibleUrl,
        songName = songName,
        offlineMode = offlineMode,
        onDownload = owner.downloadPreviewAction(onDownload),
        onDismiss = owner::closePreview
    )
}

@Composable
internal fun StableNowPlayingCoverImage(
    owner: NowPlayingCoverOwner,
    coverUrl: String?,
    songKey: String?,
    context: Context,
    coverRequestSizePx: Int,
    offlineMode: Boolean,
    coverCacheKey: String?,
    contentDescription: String?,
    modifier: Modifier,
    songKeyAliases: List<String>
) {
    val request = owner.requestFor(NowPlayingCoverRequestInput(coverUrl, songKey, coverCacheKey))
    val presentation = owner.presentation(request, songKey, songKeyAliases)
    SideEffect { owner.onPresented(songKey, presentation) }
    NowPlayingCoverSurface(
        owner, presentation, context, coverRequestSizePx, offlineMode,
        contentDescription, modifier
    )
}

internal data class NowPlayingCoverRequestInput(
    val coverUrl: String?,
    val songKey: String?,
    val cacheKey: String?
)

@Composable
private fun NowPlayingCoverSurface(
    owner: NowPlayingCoverOwner,
    presentation: NowPlayingCoverPresentation,
    context: Context,
    sizePx: Int,
    offlineMode: Boolean,
    contentDescription: String?,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        Crossfade(
            targetState = presentation.visibleFrame,
            animationSpec = if (presentation.animateVisibleFrame) {
                tween(durationMillis = NowPlayingCoverImageCrossfadeMs)
            } else snap(),
            label = "NowPlayingCoverImage"
        ) { frame ->
            NowPlayingCoverFrameContent(
                owner, presentation.requestedCover, frame, context, sizePx,
                offlineMode, contentDescription
            )
        }
        NowPlayingCoverPreload(owner, presentation, context, sizePx, offlineMode)
    }
}

@Composable
private fun NowPlayingCoverFrameContent(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest?,
    frame: NowPlayingCoverFrame?,
    context: Context,
    sizePx: Int,
    offlineMode: Boolean,
    contentDescription: String?
) {
    if (frame == null) {
        NowPlayingCoverPlaceholder()
        return
    }
    NowPlayingCoverLoadedFrame(
        owner, request, frame, context, sizePx, offlineMode, contentDescription
    )
}

@Composable
private fun NowPlayingCoverLoadedFrame(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest?,
    frame: NowPlayingCoverFrame,
    context: Context,
    sizePx: Int,
    offlineMode: Boolean,
    contentDescription: String?
) {
    val bitmap = frame.decodedBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        NowPlayingCoverAsyncFrame(
            owner, request, frame, context, sizePx, offlineMode, contentDescription
        )
    }
}

@Composable
private fun NowPlayingCoverPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun NowPlayingCoverAsyncFrame(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest?,
    frame: NowPlayingCoverFrame,
    context: Context,
    sizePx: Int,
    offlineMode: Boolean,
    contentDescription: String?
) {
    val model = rememberNowPlayingCoverImageRequest(
        NowPlayingCoverImageInput(context, frame, sizePx, offlineMode)
    )
    NowPlayingCoverKeyedAsyncFrame(owner, request, frame, model, sizePx, contentDescription)
}

@Composable
private fun NowPlayingCoverKeyedAsyncFrame(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest?,
    frame: NowPlayingCoverFrame,
    model: ImageRequest,
    sizePx: Int,
    contentDescription: String?
) {
    key(frame.requestToken) {
        NowPlayingCoverAsyncImage(owner, request, frame, model, sizePx, contentDescription)
    }
}

@Composable
private fun NowPlayingCoverAsyncImage(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest?,
    frame: NowPlayingCoverFrame,
    model: ImageRequest,
    sizePx: Int,
    contentDescription: String?
) {
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        onSuccess = { state -> owner.completeVisibleFrame(request, frame, state, sizePx) },
        onError = { owner.rejectVisibleFrame(request, frame) }
    )
}

@Composable
private fun NowPlayingCoverPreload(
    owner: NowPlayingCoverOwner,
    presentation: NowPlayingCoverPresentation,
    context: Context,
    sizePx: Int,
    offlineMode: Boolean
) {
    val request = presentation.requestedCover ?: return
    NowPlayingCoverPreloadRequest(owner, request, presentation, context, sizePx, offlineMode)
}

@Composable
private fun NowPlayingCoverPreloadRequest(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest,
    presentation: NowPlayingCoverPresentation,
    context: Context,
    sizePx: Int,
    offlineMode: Boolean
) {
    if (!shouldPreloadNowPlayingCover(request, presentation.failedRequest, presentation.visibleFrame)) return
    NowPlayingCoverPreloadKeyed(owner, request, context, sizePx, offlineMode)
}

@Composable
private fun NowPlayingCoverPreloadKeyed(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest,
    context: Context,
    sizePx: Int,
    offlineMode: Boolean
) {
    val model = rememberNowPlayingCoverImageRequest(
        NowPlayingCoverImageInput(context, request.frame, sizePx, offlineMode)
    )
    NowPlayingCoverKeyedPreloadImage(owner, request, model, sizePx)
}

@Composable
private fun NowPlayingCoverKeyedPreloadImage(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest,
    model: ImageRequest,
    sizePx: Int
) {
    key(request.requestToken) {
        NowPlayingCoverPreloadImage(owner, request, model, sizePx)
    }
}

@Composable
private fun NowPlayingCoverPreloadImage(
    owner: NowPlayingCoverOwner,
    request: NowPlayingCoverRequest,
    model: ImageRequest,
    sizePx: Int
) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0f },
        onSuccess = { state -> owner.completeDecodedFrame(request, request.frame, state, sizePx) },
        onError = { owner.rejectDecodedFrame(request.frame, request) }
    )
}

internal fun shouldPreloadNowPlayingCover(
    request: NowPlayingCoverRequest?,
    failedRequest: NowPlayingCoverRequest?,
    visibleFrame: NowPlayingCoverFrame?
): Boolean = request != null && failedRequest == null &&
    !sameNowPlayingCoverSource(request.frame, visibleFrame)

private data class NowPlayingCoverImageInput(
    val context: Context,
    val frame: NowPlayingCoverFrame,
    val sizePx: Int,
    val offlineMode: Boolean
)

@Composable
private fun rememberNowPlayingCoverImageRequest(input: NowPlayingCoverImageInput): ImageRequest =
    remember(input) {
        nowPlayingCoverImageRequest(input.context, input.frame, input.sizePx, input.offlineMode)
    }

private fun nowPlayingCoverImageRequest(
    context: Context,
    frame: NowPlayingCoverFrame,
    sizePx: Int,
    offlineMode: Boolean
) = offlineCachedImageRequest(
    context = context,
    data = frame.coverUrl,
    sizePx = sizePx,
    allowHardware = false,
    crossfade = false,
    offlineMode = offlineMode,
    cacheKey = frame.cacheKey
)
