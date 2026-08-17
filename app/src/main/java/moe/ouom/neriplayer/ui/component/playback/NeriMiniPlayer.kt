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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import kotlin.math.abs
import kotlin.math.exp
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
    isAudioRouteMuted: Boolean = false
) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val context = LocalContext.current
    val requestedCoverUrl = coverUrl?.trim()?.takeIf { it.isNotEmpty() }
    var displayedCoverUrl by remember { mutableStateOf(requestedCoverUrl) }
    val latestRequestedCoverUrl by rememberUpdatedState(requestedCoverUrl)
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

    LaunchedEffect(requestedCoverUrl) {
        if (requestedCoverUrl == null) {
            delay(MINI_PLAYER_COVER_CLEAR_DELAY_MS)
            if (latestRequestedCoverUrl == null) {
                displayedCoverUrl = resolveMiniPlayerDisplayedCoverUrl(
                    requestedCoverUrl = null,
                    displayedCoverUrl = displayedCoverUrl,
                    requestSucceeded = false,
                    clearDelayElapsed = true
                )
            }
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
                            color = if (displayedCoverUrl != null || requestedCoverUrl != null) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    if (displayedCoverUrl != null) {
                        AsyncImage(
                            model = fastScrollableImageRequest(
                                context = context,
                                data = displayedCoverUrl,
                                sizePx = 128,
                                crossfade = false,
                                offlineMode = offlineMode
                            ),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }

                    if (requestedCoverUrl != null && requestedCoverUrl != displayedCoverUrl) {
                        AsyncImage(
                            model = fastScrollableImageRequest(
                                context = context,
                                data = requestedCoverUrl,
                                sizePx = 128,
                                crossfade = false,
                                offlineMode = offlineMode
                            ),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = 0f },
                            onSuccess = {
                                if (latestRequestedCoverUrl == requestedCoverUrl) {
                                    displayedCoverUrl = resolveMiniPlayerDisplayedCoverUrl(
                                        requestedCoverUrl = requestedCoverUrl,
                                        displayedCoverUrl = displayedCoverUrl,
                                        requestSucceeded = true
                                    )
                                }
                            }
                        )
                    } else if (displayedCoverUrl == null) {
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
