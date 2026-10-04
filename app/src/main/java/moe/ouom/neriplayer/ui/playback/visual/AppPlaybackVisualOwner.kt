package moe.ouom.neriplayer.ui.playback.visual

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.data.model.artwork.CoverArtColorSample
import moe.ouom.neriplayer.util.media.normalizeCoverArtColorCacheKey
import moe.ouom.neriplayer.util.media.adjustedAccentColorArgb
import moe.ouom.neriplayer.util.media.isRemoteImageSource
import kotlin.time.Duration.Companion.milliseconds

internal fun SongItem?.resolveUiCoverSource(context: Context): String? {
    return this?.displayCoverUrl(context)
}

private const val NOW_PLAYING_REMOTE_BLUR_IMAGE_SIZE_PX = 640
private const val NOW_PLAYING_LOCAL_BLUR_IMAGE_SIZE_PX = 384
internal const val NOW_PLAYING_BACKGROUND_CROSSFADE_MS = 520

internal fun resolvedNowPlayingBlurImageSizePx(coverUrl: String?): Int {
    return if (isRemoteImageSource(coverUrl)) {
        NOW_PLAYING_REMOTE_BLUR_IMAGE_SIZE_PX
    } else {
        NOW_PLAYING_LOCAL_BLUR_IMAGE_SIZE_PX
    }
}

internal fun resolvedNowPlayingBlurStrength(coverUrl: String?, configuredBlurAmount: Float): Float {
    return if (isRemoteImageSource(coverUrl)) {
        configuredBlurAmount
    } else {
        configuredBlurAmount.coerceAtMost(64f)
    }
}

internal fun resolvePlaybackVisualCoverUrl(
    currentCoverUrl: String?,
    previousVisualCoverUrl: String?,
    hasCurrentSong: Boolean
): String? {
    val normalizedCoverUrl = currentCoverUrl?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        normalizedCoverUrl != null -> normalizedCoverUrl
        !hasCurrentSong -> null
        else -> previousVisualCoverUrl
    }
}

internal fun shouldClearPlaybackVisualCover(
    currentSongKey: String?,
    requestedCoverUrl: String?,
    clearDelayElapsed: Boolean
): Boolean {
    return currentSongKey == null && requestedCoverUrl == null && clearDelayElapsed
}

internal fun shouldClearRetainedPlaybackVisualCoverAfterGrace(
    currentSongKey: String?,
    retainedCoverUrl: String?,
    requestedCoverUrl: String?,
    clearDelayElapsed: Boolean
): Boolean = !hasVisualText(currentSongKey) &&
    clearDelayElapsed &&
    hasVisualText(retainedCoverUrl) &&
    !hasVisualText(requestedCoverUrl)

private fun hasVisualText(value: String?): Boolean = !value.isNullOrBlank()

internal fun shouldClearNowPlayingBlurCover(
    currentSongKey: String?,
    requestedCoverUrl: String?,
    clearDelayElapsed: Boolean
): Boolean = currentSongKey == null &&
    requestedCoverUrl?.trim().isNullOrEmpty() &&
    clearDelayElapsed

internal fun shouldRetainNowPlayingBlurCover(
    stableCoverUrl: String?,
    currentSongKey: String?,
    requestedCoverUrl: String?
): Boolean = hasVisualText(stableCoverUrl) &&
    (currentSongKey != null || hasVisualText(requestedCoverUrl))

internal data class PlaybackVisualCoverState(
    val url: String?,
    val ownerSongKey: String?
)

internal data class PlaybackVisualCoverRequest(
    val coverUrl: String?,
    val songKey: String?
)

internal fun playbackVisualCoverRequest(
    coverUrl: String?,
    songKey: String?
): PlaybackVisualCoverRequest = PlaybackVisualCoverRequest(
    coverUrl?.trim()?.takeIf(String::isNotEmpty), songKey
)

internal fun resolvePlaybackVisualCoverState(
    currentCoverUrl: String?,
    previousState: PlaybackVisualCoverState?,
    currentSongKey: String?,
    hasCurrentSong: Boolean
): PlaybackVisualCoverState {
    val normalizedCoverUrl = currentCoverUrl?.trim()?.takeIf(String::isNotEmpty)
    return when {
        normalizedCoverUrl != null -> PlaybackVisualCoverState(
            url = normalizedCoverUrl,
            ownerSongKey = currentSongKey
        )
        !hasCurrentSong -> PlaybackVisualCoverState(
            url = null,
            ownerSongKey = null
        )
        else -> previousState ?: PlaybackVisualCoverState(
            url = null,
            ownerSongKey = null
        )
    }
}

@Composable
internal fun rememberPlaybackVisualCoverState(
    request: PlaybackVisualCoverRequest
): PlaybackVisualCoverState {
    val visualCoverState = remember {
        mutableStateOf(PlaybackVisualCoverState(null, null))
    }
    val resolvedVisualCoverState = resolvePlaybackVisualCoverState(
        currentCoverUrl = request.coverUrl,
        previousState = visualCoverState.value,
        currentSongKey = request.songKey,
        hasCurrentSong = hasCurrentOrRetainedVisualCover(
            request.songKey, visualCoverState.value
        )
    )
    SideEffect {
        synchronizePlaybackVisualCoverState(visualCoverState, resolvedVisualCoverState)
    }
    ScheduleRetainedPlaybackVisualCoverClear(
        RetainedVisualCoverClearScope(visualCoverState, request)
    )
    return resolvedVisualCoverState
}

private class RetainedVisualCoverClearScope(
    val visualCoverState: MutableState<PlaybackVisualCoverState>,
    val request: PlaybackVisualCoverRequest
)

@Composable
private fun ScheduleRetainedPlaybackVisualCoverClear(scope: RetainedVisualCoverClearScope) {
    val visualCoverState = scope.visualCoverState
    val request = scope.request
    val latestRequest by rememberUpdatedState(request)
    val latestVisualCoverState by rememberUpdatedState(visualCoverState.value)
    LaunchedEffect(request, visualCoverState.value.ownerSongKey) {
        val stateAtStart = visualCoverState.value
        if (awaitRetainedPlaybackVisualCoverClear(stateAtStart, request.coverUrl) {
                shouldCommitRetainedPlaybackVisualCoverClear(
                    requestSongKey = request.songKey,
                    stateAtStart = stateAtStart,
                    latestSongKey = latestRequest.songKey,
                    latestCoverUrl = latestRequest.coverUrl,
                    latestState = latestVisualCoverState
                )
            }) {
            visualCoverState.value = PlaybackVisualCoverState(null, null)
        }
    }
}

internal fun hasCurrentOrRetainedVisualCover(
    currentSongKey: String?,
    visualCoverState: PlaybackVisualCoverState
): Boolean = currentSongKey != null || visualCoverState.url != null

private fun synchronizePlaybackVisualCoverState(
    visualCoverState: MutableState<PlaybackVisualCoverState>,
    resolvedVisualCoverState: PlaybackVisualCoverState
) {
    if (visualCoverState.value != resolvedVisualCoverState) {
        visualCoverState.value = resolvedVisualCoverState
    }
}

private suspend fun awaitRetainedPlaybackVisualCoverClear(
    stateAtStart: PlaybackVisualCoverState,
    requestedCoverUrl: String?,
    canClear: () -> Boolean
): Boolean {
    if (!shouldScheduleRetainedPlaybackVisualCoverClear(stateAtStart, requestedCoverUrl)) {
        return false
    }
    delay(PLAYBACK_VISUAL_COVER_GRACE_MS.milliseconds)
    return canClear()
}

internal fun shouldScheduleRetainedPlaybackVisualCoverClear(
    stateAtStart: PlaybackVisualCoverState,
    requestedCoverUrl: String?
): Boolean = !stateAtStart.url.isNullOrBlank() && requestedCoverUrl.isNullOrBlank()

internal fun shouldCommitRetainedPlaybackVisualCoverClear(
    requestSongKey: String?,
    stateAtStart: PlaybackVisualCoverState,
    latestSongKey: String?,
    latestCoverUrl: String?,
    latestState: PlaybackVisualCoverState
): Boolean = latestSongKey == requestSongKey &&
    latestCoverUrl.isNullOrEmpty() &&
    latestState == stateAtStart &&
    shouldClearRetainedPlaybackVisualCoverAfterGrace(
        currentSongKey = latestSongKey,
        retainedCoverUrl = latestState.url,
        requestedCoverUrl = latestCoverUrl,
        clearDelayElapsed = true
    )

private const val COVER_SEED_WARMUP_DELAY_MS = 180L
internal const val PLAYBACK_VISUAL_COVER_GRACE_MS = 1200L
internal const val PLAYBACK_COVER_SEED_GRACE_MS = 1200L

internal data class PlaybackCoverSeed(
    val coverUrl: String,
    val seedHex: String,
    val songKey: String?
)

internal fun resolveActiveCoverSeedHex(
    visualCoverUrl: String?,
    sampledCoverUrl: String?,
    sampledSeedHex: String?,
    currentSongKey: String? = null,
    sampledSongKey: String? = null
): String? {
    val visualCacheKey = normalizeCoverArtColorCacheKey(visualCoverUrl) ?: return null
    val sampledCacheKey = normalizeCoverArtColorCacheKey(sampledCoverUrl) ?: return null
    val belongsToVisual = visualCacheKey == sampledCacheKey
    val belongsToSameSong = currentSongKey != null && currentSongKey == sampledSongKey
    return sampledSeedHex?.takeIf {
        belongsToVisual || belongsToSameSong
    }
}

internal fun resolveCoverSeedWarmupDelayMillis(
    showNowPlaying: Boolean,
    dynamicColorEnabled: Boolean,
    hasCachedSample: Boolean
): Long {
    if (!dynamicColorEnabled || showNowPlaying || hasCachedSample) {
        return 0L
    }
    return COVER_SEED_WARMUP_DELAY_MS
}

internal fun isCurrentAccentRequest(
    requestedCoverUrl: String?,
    requestedSongKey: String?,
    latestCoverUrl: String?,
    latestSongKey: String?
): Boolean = requestedCoverUrl == latestCoverUrl && requestedSongKey == latestSongKey

internal interface BackdropAccentSampleSource {
    fun cached(coverUrl: String): CoverArtColorSample?
    suspend fun load(context: Context, coverUrl: String, offlineMode: Boolean): CoverArtColorSample?
}

private object CachedBackdropAccentSampleSource : BackdropAccentSampleSource {
    override fun cached(coverUrl: String): CoverArtColorSample? = CoverArtColorCache.peek(coverUrl)

    override suspend fun load(
        context: Context,
        coverUrl: String,
        offlineMode: Boolean
    ): CoverArtColorSample? = CoverArtColorCache.getOrLoad(context, coverUrl, offlineMode)
}

internal suspend fun loadBackdropAccent(
    source: BackdropAccentSampleSource,
    context: Context,
    coverUrl: String?,
    offlineMode: Boolean,
    isCurrentRequest: () -> Boolean,
    onSample: (CoverArtColorSample?) -> Unit
) {
    if (coverUrl == null) {
        clearBackdropAccentAfterGrace(isCurrentRequest, onSample)
    } else {
        emitCachedBackdropAccent(source, coverUrl, isCurrentRequest, onSample)
        emitLoadedBackdropAccent(source, context, coverUrl, offlineMode, isCurrentRequest, onSample)
    }
}

private suspend fun clearBackdropAccentAfterGrace(
    isCurrentRequest: () -> Boolean,
    onSample: (CoverArtColorSample?) -> Unit
) {
    delay(PLAYBACK_COVER_SEED_GRACE_MS.milliseconds)
    if (isCurrentRequest()) onSample(null)
}

private suspend fun emitCachedBackdropAccent(
    source: BackdropAccentSampleSource,
    coverUrl: String,
    isCurrentRequest: () -> Boolean,
    onSample: (CoverArtColorSample?) -> Unit
) {
    val cached = source.cached(coverUrl) ?: return
    currentCoroutineContext().ensureActive()
    if (isCurrentRequest()) onSample(cached)
}

private suspend fun emitLoadedBackdropAccent(
    source: BackdropAccentSampleSource,
    context: Context,
    coverUrl: String,
    offlineMode: Boolean,
    isCurrentRequest: () -> Boolean,
    onSample: (CoverArtColorSample?) -> Unit
) {
    val loaded = source.load(context, coverUrl, offlineMode)
    currentCoroutineContext().ensureActive()
    if (loaded != null) emitCurrentBackdropAccent(loaded, isCurrentRequest, onSample)
}

private fun emitCurrentBackdropAccent(
    sample: CoverArtColorSample,
    isCurrentRequest: () -> Boolean,
    onSample: (CoverArtColorSample?) -> Unit
) {
    if (isCurrentRequest()) onSample(sample)
}

private fun fallbackBackdropColor(isDark: Boolean): Color =
    if (isDark) Color(0xFF121212) else Color(0xFFF5F5F5)

private fun vignetteAlpha(isDark: Boolean): Float = if (isDark) 0.12f else 0.25f

private fun whiteMaskAlpha(isDark: Boolean): Float = if (isDark) 0f else 0.05f

internal data class BackdropAccentRequest(
    val coverUrl: String?,
    val songKey: String?,
    val isDark: Boolean,
    val refreshKey: Int,
    val offlineMode: Boolean
)

internal fun backdropAccentRequest(
    coverUrl: String?,
    songKey: String?,
    isDark: Boolean,
    refreshKey: Int,
    offlineMode: Boolean
): BackdropAccentRequest = BackdropAccentRequest(
    coverUrl?.trim()?.takeIf(String::isNotEmpty), songKey, isDark, refreshKey, offlineMode
)

private class BackdropAccentOwner(initialRequest: BackdropAccentRequest) {
    var target by mutableStateOf(backdropAccentColor(
        CoverArtColorCache.peek(initialRequest.coverUrl), initialRequest.isDark
    ))
        private set
    var currentRequest: BackdropAccentRequest? = null

    suspend fun load(context: Context, request: BackdropAccentRequest) {
        loadBackdropAccent(
            source = CachedBackdropAccentSampleSource,
            context = context,
            coverUrl = request.coverUrl,
            offlineMode = request.offlineMode,
            isCurrentRequest = { currentRequest == request },
            onSample = { sample -> target = backdropAccentColor(sample, request.isDark) }
        )
    }
}

internal fun backdropAccentColor(sample: CoverArtColorSample?, isDark: Boolean): Color? =
    sample?.let { Color(adjustedAccentColorArgb(it.baseColorArgb, isDark)) }

@Composable
private fun rememberNowPlayingAccentTarget(request: BackdropAccentRequest): Color? {
    val owner = remember { BackdropAccentOwner(request) }
    BindBackdropAccentRequest(owner, request)
    return owner.target
}

@Composable
private fun BindBackdropAccentRequest(
    owner: BackdropAccentOwner,
    request: BackdropAccentRequest
) {
    val context = LocalContext.current
    LaunchedEffect(request) {
        owner.currentRequest = request
        owner.load(context, request)
    }
}

@Composable
internal fun NowPlayingAccentBackdrop(
    request: BackdropAccentRequest,
    modifier: Modifier
) {
    val target = rememberNowPlayingAccentTarget(request)
    AccentBackdropSurface(target, request.isDark, modifier)
}

@Composable
private fun AccentBackdropSurface(target: Color?, isDark: Boolean, modifier: Modifier) {
    val bgColor by animateColorAsState(
        targetValue = target ?: fallbackBackdropColor(isDark),
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "accent-bg"
    )
    val vignetteAlpha by animateFloatAsState(
        targetValue = vignetteAlpha(isDark),
        animationSpec = tween(300),
        label = "vignette-alpha"
    )
    val whiteMaskAlpha by animateFloatAsState(
        targetValue = whiteMaskAlpha(isDark),
        animationSpec = tween(300),
        label = "white-mask-alpha"
    )
    Box(
        modifier = modifier
            .background(bgColor)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = vignetteAlpha),
                            Color.Transparent
                        )
                    )
                )
                if (whiteMaskAlpha > 0f) {
                    drawRect(Color.White.copy(alpha = whiteMaskAlpha))
                }
            }
    )
}
