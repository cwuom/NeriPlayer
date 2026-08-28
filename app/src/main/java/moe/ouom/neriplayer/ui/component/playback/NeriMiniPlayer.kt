package moe.ouom.neriplayer.ui.component.playback

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.component/NeriMiniPlayer
 * Created: 2025/8/8
 */

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.util.media.copyBitmapForRetainedDisplay
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

object NeriMiniPlayerDefaults {
    val Height = 64.dp
    internal val ContentVerticalPadding = 8.dp
}

private const val MINI_PLAYER_COVER_CLEAR_DELAY_MS = 900L
private const val MINI_PLAYER_METADATA_LINE_HEIGHT_EM = 1.5f
private const val MINI_PLAYER_TITLE_LINE_HEIGHT_DP = 24f
private const val MINI_PLAYER_ARTIST_LINE_HEIGHT_DP = 20f
private const val MINI_PLAYER_TITLE_MIN_VISUAL_FONT_SIZE_SP = 10f
private const val MINI_PLAYER_ARTIST_MIN_VISUAL_FONT_SIZE_SP = 9f
private const val MINI_PLAYER_METADATA_AUTO_SIZE_STEP_SP = 0.25f
private const val MINI_PLAYER_COVER_BITMAP_MAX_DIMENSION_PX = 128
private const val MINI_PLAYER_COVER_FRAME_CACHE_LIMIT = 4

internal data class MiniPlayerTextAutoSizeRange(
    val minFontSizeSp: Float,
    val maxFontSizeSp: Float
)

internal fun resolveMiniPlayerTextAutoSizeRange(
    baseFontSizeSp: Float,
    maxLineHeightDp: Float,
    fontScale: Float,
    minVisualFontSizeSp: Float,
    lineHeightEm: Float
): MiniPlayerTextAutoSizeRange {
    val safeFontScale = fontScale.coerceAtLeast(0.01f)
    val safeLineHeightEm = lineHeightEm.coerceAtLeast(0.01f)
    val maxFontSizeSp = minOf(
        baseFontSizeSp,
        maxLineHeightDp / safeLineHeightEm / safeFontScale
    ).coerceAtLeast(0.1f)
    val minFontSizeSp = minOf(
        maxFontSizeSp,
        minVisualFontSizeSp / safeFontScale
    ).coerceAtLeast(0.1f)
    return MiniPlayerTextAutoSizeRange(
        minFontSizeSp = minFontSizeSp,
        maxFontSizeSp = maxFontSizeSp
    )
}

@Composable
private fun rememberMiniPlayerTextAutoSizeRange(
    style: TextStyle,
    maxLineHeightDp: Float,
    minVisualFontSizeSp: Float,
    lineHeightEm: Float
): MiniPlayerTextAutoSizeRange {
    val fontScale = LocalDensity.current.fontScale
    val baseFontSizeSp = style.fontSize.value.takeIf {
        style.fontSize.isSp && it.isFinite() && it > 0f
    } ?: 16f
    val range = remember(
        baseFontSizeSp,
        maxLineHeightDp,
        fontScale,
        minVisualFontSizeSp,
        lineHeightEm
    ) {
        resolveMiniPlayerTextAutoSizeRange(
            baseFontSizeSp = baseFontSizeSp,
            maxLineHeightDp = maxLineHeightDp,
            fontScale = fontScale,
            minVisualFontSizeSp = minVisualFontSizeSp,
            lineHeightEm = lineHeightEm
        )
    }
    return range
}

@Composable
private fun rememberMiniPlayerTextAutoSize(
    range: MiniPlayerTextAutoSizeRange
): TextAutoSize {
    val fontScale = LocalDensity.current.fontScale
    return remember(range, fontScale) {
        TextAutoSize.StepBased(
            minFontSize = range.minFontSizeSp.sp,
            maxFontSize = range.maxFontSizeSp.sp,
            stepSize = (MINI_PLAYER_METADATA_AUTO_SIZE_STEP_SP / fontScale.coerceAtLeast(0.01f)).sp
        )
    }
}

private fun TextStyle.miniPlayerLineHeightEm(): Float {
    val fontSizeUnit = fontSize
    val lineHeightUnit = lineHeight
    val fontSizeValue = fontSizeUnit.value
    val lineHeightValue = lineHeightUnit.value
    return if (
        fontSizeUnit.isSp &&
        lineHeightUnit.isSp &&
        fontSizeValue > 0f &&
        lineHeightValue > 0f
    ) {
        lineHeightValue / fontSizeValue
    } else {
        MINI_PLAYER_METADATA_LINE_HEIGHT_EM
    }
}

private fun TextStyle.withMiniPlayerLineHeight(lineHeightEm: Float): TextStyle = copy(
    lineHeight = lineHeightEm.em
)

internal fun resolveMiniPlayerDisplayedCoverUrl(
    requestedCoverUrl: String?,
    displayedCoverUrl: String?,
    requestSucceeded: Boolean,
    clearDelayElapsed: Boolean = false
): String? {
    val requested = requestedCoverUrl?.trim()?.takeIf { it.isNotEmpty() }
    val displayed = displayedCoverUrl?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        requested == null && clearDelayElapsed -> null
        requested == null -> displayed
        requested == displayed || requestSucceeded -> requested
        else -> displayed
    }
}

internal data class MiniPlayerCoverFrame(
    val coverUrl: String,
    val identityKey: String,
    val decodedBitmap: ImageBitmap? = null,
    /**
     * 每次重新进入同一封面时使用新的令牌, 防止旧的 Coil 回调污染新请求
     */
    val requestToken: Any = Unit
)

internal fun miniPlayerCoverIdentityKey(
    identityKey: String?,
    coverUrl: String?
): String? {
    return identityKey
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: coverUrl?.trim()?.takeIf(String::isNotEmpty)
}

internal fun sameMiniPlayerCoverRequest(
    first: MiniPlayerCoverFrame?,
    second: MiniPlayerCoverFrame?
): Boolean {
    if (first == null || second == null) return first == second
    return first.coverUrl == second.coverUrl &&
        first.identityKey == second.identityKey &&
        first.requestToken == second.requestToken
}

internal fun sameMiniPlayerCoverFrame(
    first: MiniPlayerCoverFrame?,
    second: MiniPlayerCoverFrame?
): Boolean {
    if (first == null || second == null) return first == second
    return first.coverUrl == second.coverUrl && first.identityKey == second.identityKey
}

internal fun shouldCommitMiniPlayerCoverRequest(
    completedRequest: MiniPlayerCoverFrame,
    latestRequest: MiniPlayerCoverFrame?
): Boolean = sameMiniPlayerCoverRequest(completedRequest, latestRequest)

internal fun shouldCommitMiniPlayerCoverFrame(
    completedFrame: MiniPlayerCoverFrame,
    latestRequestedFrame: MiniPlayerCoverFrame?,
    latestRetainedFrame: MiniPlayerCoverFrame?,
    currentIdentityKey: String?
): Boolean {
    latestRequestedFrame?.let { requestedFrame ->
        return sameMiniPlayerCoverRequest(completedFrame, requestedFrame)
    }
    val retainedFrame = latestRetainedFrame ?: return false
    if (!sameMiniPlayerCoverRequest(completedFrame, retainedFrame)) return false
    val normalizedCurrentIdentity = currentIdentityKey
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    return normalizedCurrentIdentity == null ||
        completedFrame.identityKey == normalizedCurrentIdentity
}

internal fun shouldClearMiniPlayerRetainedCoverAfterGrace(
    requestedFrame: MiniPlayerCoverFrame?,
    retainedFrame: MiniPlayerCoverFrame?,
    failedFrame: MiniPlayerCoverFrame?,
    clearDelayElapsed: Boolean,
    hasCurrentSong: Boolean = true
): Boolean {
    if (!clearDelayElapsed || retainedFrame == null || hasCurrentSong) return false
    if (requestedFrame == null) return true
    return failedFrame != null &&
        sameMiniPlayerCoverRequest(requestedFrame, failedFrame)
}

internal fun resolveMiniPlayerVisibleCoverFrame(
    requestedFrame: MiniPlayerCoverFrame?,
    displayedFrame: MiniPlayerCoverFrame?,
    cachedFrame: MiniPlayerCoverFrame?,
    retainedFrame: MiniPlayerCoverFrame?,
    hasCurrentSong: Boolean = true,
    failedFrame: MiniPlayerCoverFrame? = null,
    clearRetainedFrame: Boolean = false
): MiniPlayerCoverFrame? {
    if (!hasCurrentSong) return null
    if (clearRetainedFrame) return null
    val retainedCandidate = cachedFrame ?: displayedFrame ?: retainedFrame
    val retained = if (
        retainedCandidate != null &&
            requestedFrame != null &&
            retainedCandidate.decodedBitmap == null &&
            sameMiniPlayerCoverFrame(retainedCandidate, requestedFrame)
    ) {
        requestedFrame
    } else {
        retainedCandidate
    }
    if (retained?.decodedBitmap != null) {
        return retained
    }
    if (
        retained != null &&
        (failedFrame == null || !sameMiniPlayerCoverRequest(retained, failedFrame))
    ) {
        return retained
    }
    return requestedFrame?.takeUnless { frame ->
        failedFrame != null && sameMiniPlayerCoverRequest(frame, failedFrame)
    }
}

internal fun miniPlayerCoverCacheKey(frame: MiniPlayerCoverFrame): String {
    return "${frame.identityKey}|data=${frame.coverUrl}"
}

private fun resolveMiniPlayerCoverBitmap(
    state: AsyncImagePainter.State.Success
): ImageBitmap? {
    return runCatching {
        val drawable = state.result.drawable
        val maxDimension = MINI_PLAYER_COVER_BITMAP_MAX_DIMENSION_PX
        if (drawable is BitmapDrawable) {
            val sourceBitmap = drawable.bitmap
            val scale = min(
                maxDimension.toFloat() / sourceBitmap.width.coerceAtLeast(1),
                maxDimension.toFloat() / sourceBitmap.height.coerceAtLeast(1)
            ).coerceAtMost(1f)
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    sourceBitmap,
                    (sourceBitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (sourceBitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                ).asImageBitmap()
            } else {
                copyBitmapForRetainedDisplay(sourceBitmap)?.asImageBitmap()
            }
        } else {
            drawable.toBitmap(
                width = maxDimension,
                height = maxDimension,
                config = Bitmap.Config.ARGB_8888
            ).asImageBitmap()
        }
    }.getOrNull()
}

@Composable
internal fun AutoSizingMiniPlayerText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLineHeightDp: Float,
    minVisualFontSizeSp: Float,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val lineHeightEm = style.miniPlayerLineHeightEm()
    val range = rememberMiniPlayerTextAutoSizeRange(
        style = style,
        maxLineHeightDp = maxLineHeightDp,
        minVisualFontSizeSp = minVisualFontSizeSp,
        lineHeightEm = lineHeightEm
    )
    val autoSize = rememberMiniPlayerTextAutoSize(range)
    Text(
        text = text,
        style = style.withMiniPlayerLineHeight(lineHeightEm),
        autoSize = autoSize,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = onTextLayout
    )
}

@Composable
internal fun EllipsizingMiniPlayerText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLineHeightDp: Float,
    minVisualFontSizeSp: Float,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val lineHeightEm = style.miniPlayerLineHeightEm()
    val range = rememberMiniPlayerTextAutoSizeRange(
        style = style,
        maxLineHeightDp = maxLineHeightDp,
        minVisualFontSizeSp = minVisualFontSizeSp,
        lineHeightEm = lineHeightEm
    )
    Text(
        text = text,
        style = style
            .withMiniPlayerLineHeight(lineHeightEm)
            .copy(fontSize = range.maxFontSizeSp.sp),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = onTextLayout
    )
}

@Composable
fun NeriMiniPlayer(
    title: String,
    artist: String,
    coverUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    playPauseEnabled: Boolean = true,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    enableBlur: Boolean = true,
    offlineMode: Boolean = false,
    isPlaybackWaiting: Boolean = false,
    isAudioRouteMuted: Boolean = false,
    visualCoverUrl: String? = null,
    coverIdentityKey: String? = null,
    visualCoverIdentityKey: String? = null,
    hasCurrentSong: Boolean = true
) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val context = LocalContext.current
    val requestedCoverUrl = coverUrl?.trim()?.takeIf { it.isNotEmpty() }
    val retainedCoverUrl = visualCoverUrl?.trim()?.takeIf { it.isNotEmpty() }
    val requestedIdentityKey = miniPlayerCoverIdentityKey(
        identityKey = coverIdentityKey,
        coverUrl = requestedCoverUrl
    )
    val retainedIdentityKey = miniPlayerCoverIdentityKey(
        identityKey = visualCoverIdentityKey,
        coverUrl = retainedCoverUrl
    )
    val requestedFrame = remember(requestedCoverUrl, requestedIdentityKey) {
        requestedCoverUrl?.let { url ->
            requestedIdentityKey?.let { key ->
                MiniPlayerCoverFrame(
                    coverUrl = url,
                    identityKey = key,
                    requestToken = Any()
                )
            }
        }
    }
    val retainedFrame = remember(retainedCoverUrl, retainedIdentityKey) {
        retainedCoverUrl?.let { url ->
            retainedIdentityKey?.let { key ->
                MiniPlayerCoverFrame(
                    coverUrl = url,
                    identityKey = key,
                    requestToken = Any()
                )
            }
        }
    }
    val effectiveRetainedFrame = if (
        retainedFrame?.decodedBitmap == null &&
            sameMiniPlayerCoverFrame(requestedFrame, retainedFrame)
    ) {
        requestedFrame
    } else {
        retainedFrame
    }
    var displayedFrame by remember { mutableStateOf<MiniPlayerCoverFrame?>(null) }
    var failedCoverFrame by remember(requestedFrame, effectiveRetainedFrame) {
        mutableStateOf<MiniPlayerCoverFrame?>(null)
    }
    var clearRetainedFrame by remember(requestedFrame, effectiveRetainedFrame) {
        mutableStateOf(false)
    }
    val decodedFramesByIdentity = remember {
        mutableStateMapOf<String, MiniPlayerCoverFrame>()
    }
    val latestRequestedFrame by rememberUpdatedState(requestedFrame)
    val latestRetainedFrame by rememberUpdatedState(effectiveRetainedFrame)
    val failedFrameForVisibleState = failedCoverFrame?.takeIf { failed ->
        when {
            requestedFrame != null -> sameMiniPlayerCoverRequest(failed, requestedFrame)
            effectiveRetainedFrame != null ->
                sameMiniPlayerCoverRequest(failed, effectiveRetainedFrame)
            else -> false
        }
    }
    val latestFailedFrame by rememberUpdatedState(failedFrameForVisibleState)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)
    val swipeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val reboundPeakPx = with(density) { 52.dp.toPx() }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var swipeJob by remember { mutableStateOf<Job?>(null) }
    fun resistedOffset(distancePx: Float): Float {
        if (distancePx == 0f) return 0f
        return sign(distancePx) * reboundPeakPx * (1f - exp(-abs(distancePx) / reboundPeakPx))
    }

    fun animateSwipeRelease(targetDirection: Float, onComplete: () -> Unit) {
        swipeJob?.cancel()
        swipeJob = coroutineScope.launch {
            swipeOffset.animateTo(
                targetValue = targetDirection * reboundPeakPx,
                animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
            )
            swipeOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            )
            onComplete()
        }
    }

    val cachedRequestedFrame = requestedFrame?.let { frame ->
        decodedFramesByIdentity[frame.identityKey]?.takeIf {
            it.decodedBitmap != null && it.coverUrl == frame.coverUrl
        }
    }
    val cachedRetainedFrame = retainedFrame?.let { frame ->
        decodedFramesByIdentity[frame.identityKey]?.takeIf {
            it.decodedBitmap != null && it.coverUrl == frame.coverUrl
        }
    }
    val sharedFrameForCurrentIdentity = sequenceOf(
        requestedIdentityKey,
        retainedIdentityKey
    ).filterNotNull()
        .distinct()
        .mapNotNull(RetainedPlaybackCoverBitmapCache::getLatestForOwner)
        .firstOrNull()
        ?.let { entry ->
            MiniPlayerCoverFrame(
                coverUrl = entry.coverUrl,
                identityKey = entry.ownerKey,
                decodedBitmap = entry.bitmap,
                requestToken = requestedFrame?.requestToken ?: Any()
            )
        }
    val effectiveCachedRequestedFrame = cachedRequestedFrame
        ?: requestedFrame?.let { frame ->
            RetainedPlaybackCoverBitmapCache.getExact(
                ownerKey = frame.identityKey,
                coverUrl = frame.coverUrl
            )
        }?.let { entry ->
            requestedFrame.copy(decodedBitmap = entry.bitmap)
        }
    val visibleFrame = resolveMiniPlayerVisibleCoverFrame(
        requestedFrame = requestedFrame,
        displayedFrame = displayedFrame,
        cachedFrame = effectiveCachedRequestedFrame ?: sharedFrameForCurrentIdentity,
        retainedFrame = cachedRetainedFrame ?: effectiveRetainedFrame,
        failedFrame = failedFrameForVisibleState,
        clearRetainedFrame = clearRetainedFrame
    )

    fun publishDecodedFrame(
        completedFrame: MiniPlayerCoverFrame,
        decodedBitmap: ImageBitmap?
    ) {
        if (
            decodedBitmap == null ||
                !shouldCommitMiniPlayerCoverFrame(
                    completedFrame = completedFrame,
                    latestRequestedFrame = latestRequestedFrame,
                    latestRetainedFrame = latestRetainedFrame,
                    currentIdentityKey = coverIdentityKey
                )
        ) {
            return
        }
        val decodedFrame = completedFrame.copy(decodedBitmap = decodedBitmap)
        displayedFrame = decodedFrame
        failedCoverFrame = null
        clearRetainedFrame = false
        decodedFramesByIdentity[decodedFrame.identityKey] = decodedFrame
        RetainedPlaybackCoverBitmapCache.put(
            ownerKey = decodedFrame.identityKey,
            coverUrl = decodedFrame.coverUrl,
            cacheKey = null,
            bitmap = decodedBitmap
        )
        while (decodedFramesByIdentity.size > MINI_PLAYER_COVER_FRAME_CACHE_LIMIT) {
            val oldestKey = decodedFramesByIdentity.keys.firstOrNull() ?: break
            decodedFramesByIdentity.remove(oldestKey)
        }
    }

    fun rejectCoverFrame(failedFrame: MiniPlayerCoverFrame?) {
        if (failedFrame == null) return
        val displayedDecodedFrame = displayedFrame?.takeIf {
            it.decodedBitmap != null && sameMiniPlayerCoverRequest(it, failedFrame)
        }
        val cachedDecodedFrame = decodedFramesByIdentity[failedFrame.identityKey]
            ?.takeIf {
                it.decodedBitmap != null && sameMiniPlayerCoverRequest(it, failedFrame)
            }
        if (displayedDecodedFrame != null || cachedDecodedFrame != null) return
        val latestFrame = latestRequestedFrame
        if (latestFrame == null) {
            val retainedFrame = latestRetainedFrame
            if (
                retainedFrame != null &&
                    sameMiniPlayerCoverRequest(failedFrame, retainedFrame)
            ) {
                failedCoverFrame = failedFrame
            }
            return
        }
        if (!sameMiniPlayerCoverRequest(failedFrame, latestFrame)) return
        failedCoverFrame = failedFrame
        clearRetainedFrame = false
        if (
            displayedFrame != null &&
            sameMiniPlayerCoverRequest(displayedFrame, failedFrame)
        ) {
            displayedFrame = null
        }
        decodedFramesByIdentity.entries.removeAll { (_, cachedFrame) ->
            sameMiniPlayerCoverRequest(cachedFrame, failedFrame)
        }
    }

    LaunchedEffect(
        requestedFrame,
        effectiveRetainedFrame,
        failedFrameForVisibleState,
        hasCurrentSong
    ) {
        if (clearRetainedFrame || hasCurrentSong) return@LaunchedEffect
        val retainedFrame = effectiveRetainedFrame ?: displayedFrame
        val failedFrame = failedFrameForVisibleState
        val shouldWait = when {
            requestedFrame == null -> retainedFrame != null
            failedFrame != null -> sameMiniPlayerCoverRequest(failedFrame, requestedFrame)
            else -> false
        }
        if (!shouldWait) return@LaunchedEffect
        val requestAtStart = requestedFrame
        val retainedAtStart = effectiveRetainedFrame
        delay(MINI_PLAYER_COVER_CLEAR_DELAY_MS)
        val currentRetained = latestRetainedFrame ?: displayedFrame
        val currentFailed = latestFailedFrame
        if (
            latestRequestedFrame == requestAtStart &&
                latestRetainedFrame == retainedAtStart &&
                shouldClearMiniPlayerRetainedCoverAfterGrace(
                    requestedFrame = latestRequestedFrame,
                    retainedFrame = currentRetained,
                    failedFrame = currentFailed,
                    clearDelayElapsed = true,
                    hasCurrentSong = hasCurrentSong
                )
        ) {
            clearRetainedFrame = true
            displayedFrame = null
        }
    }

    AdvancedGlassSurface(
        role = AdvancedGlassRole.MiniPlayer,
        modifier = modifier
            .fillMaxWidth()
            .height(NeriMiniPlayerDefaults.Height)
            .padding(horizontal = 8.dp)
            .clip(shape)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        swipeJob?.cancel()
                        dragDistancePx = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragDistancePx += dragAmount
                        swipeJob?.cancel()
                        swipeJob = coroutineScope.launch {
                            swipeOffset.snapTo(resistedOffset(dragDistancePx))
                        }
                    },
                    onDragCancel = {
                        dragDistancePx = 0f
                        swipeJob?.cancel()
                        swipeJob = coroutineScope.launch {
                            swipeOffset.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
                            )
                        }
                    },
                    onDragEnd = {
                        val finalDistancePx = dragDistancePx
                        dragDistancePx = 0f
                        when {
                            finalDistancePx <= -swipeThresholdPx -> animateSwipeRelease(
                                targetDirection = -1f,
                                onComplete = { currentOnNext() }
                            )

                            finalDistancePx >= swipeThresholdPx -> animateSwipeRelease(
                                targetDirection = 1f,
                                onComplete = { currentOnPrevious() }
                            )

                            else -> {
                                swipeJob?.cancel()
                                swipeJob = coroutineScope.launch {
                                    swipeOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
                                    )
                                }
                            }
                        }
                    }
                )
            }
            .clickable { onExpand() },
        shape = shape,
        fallbackColor = MaterialTheme.colorScheme.secondaryContainer,
        tintColor = MaterialTheme.colorScheme.secondaryContainer,
        enabled = enableBlur
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            shape = shape,
            modifier = Modifier.matchParentSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .graphicsLayer {
                        translationX = swipeOffset.value
                        val offsetRatio = (abs(swipeOffset.value) / reboundPeakPx).coerceIn(0f, 1f)
                        scaleX = 1f - offsetRatio * 0.025f
                        scaleY = 1f - offsetRatio * 0.025f
                    }
                    .padding(
                        horizontal = 12.dp,
                        vertical = NeriMiniPlayerDefaults.ContentVerticalPadding
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (visibleFrame != null) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    if (visibleFrame?.decodedBitmap != null) {
                        Image(
                            bitmap = visibleFrame.decodedBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else if (visibleFrame != null) {
                        key(visibleFrame.requestToken) {
                            AsyncImage(
                                model = fastScrollableImageRequest(
                                    context = context,
                                    data = visibleFrame.coverUrl,
                                    sizePx = 128,
                                    crossfade = false,
                                    offlineMode = offlineMode,
                                    cacheKey = miniPlayerCoverCacheKey(visibleFrame)
                                ),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                onSuccess = { state ->
                                    val bitmap = resolveMiniPlayerCoverBitmap(state)
                                    if (bitmap != null &&
                                        shouldCommitMiniPlayerCoverFrame(
                                            completedFrame = visibleFrame,
                                            latestRequestedFrame = latestRequestedFrame,
                                            latestRetainedFrame = latestRetainedFrame,
                                            currentIdentityKey = coverIdentityKey
                                        )
                                    ) {
                                        publishDecodedFrame(visibleFrame, bitmap)
                                    } else if (bitmap == null) {
                                        rejectCoverFrame(visibleFrame)
                                    }
                                },
                                onError = { rejectCoverFrame(visibleFrame) }
                            )
                        }
                    }

        if (
            requestedFrame != null &&
            failedFrameForVisibleState == null &&
            !sameMiniPlayerCoverFrame(requestedFrame, visibleFrame)
        ) {
                        key(requestedFrame.requestToken) {
                            AsyncImage(
                                model = fastScrollableImageRequest(
                                    context = context,
                                    data = requestedFrame.coverUrl,
                                    sizePx = 128,
                                    crossfade = false,
                                    offlineMode = offlineMode,
                                    cacheKey = miniPlayerCoverCacheKey(requestedFrame)
                                ),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = 0f },
                                onSuccess = { state ->
                                    resolveMiniPlayerCoverBitmap(state)?.let { bitmap ->
                                        publishDecodedFrame(requestedFrame, bitmap)
                                    } ?: rejectCoverFrame(requestedFrame)
                                },
                                onError = { rejectCoverFrame(requestedFrame) }
                            )
                        }
                    }

                    if (visibleFrame == null) {
                        Box(
                            modifier = Modifier.matchParentSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    EllipsizingMiniPlayerText(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLineHeightDp = MINI_PLAYER_TITLE_LINE_HEIGHT_DP,
                        minVisualFontSizeSp = MINI_PLAYER_TITLE_MIN_VISUAL_FONT_SIZE_SP,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AutoSizingMiniPlayerText(
                        text = artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        maxLineHeightDp = MINI_PLAYER_ARTIST_LINE_HEIGHT_DP,
                        minVisualFontSizeSp = MINI_PLAYER_ARTIST_MIN_VISUAL_FONT_SIZE_SP,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HapticIconButton(
                    onClick = { onPlayPause() },
                    enabled = playPauseEnabled
                ) {
                    PlaybackControlIndicator(
                        isPlaying = isPlaying,
                        isPlaybackWaiting = isPlaybackWaiting,
                        isAudioRouteMuted = isAudioRouteMuted,
                        playContentDescription = stringResource(R.string.lyrics_play),
                        pauseContentDescription = stringResource(R.string.lyrics_pause),
                        restoreVolumeContentDescription = stringResource(R.string.player_restore_volume),
                        waitingContentDescription = stringResource(R.string.player_waiting),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        progressIndicatorSize = 22.dp,
                        progressStrokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
