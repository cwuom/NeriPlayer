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
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
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
    isPlaybackWaiting: Boolean = false
) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
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

    AdvancedGlassSurface(
        role = AdvancedGlassRole.MiniPlayer,
        modifier = modifier
            .fillMaxWidth()
            .height(NeriMiniPlayerDefaults.Height)
            .padding(start = 16.dp, end = 8.dp)
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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (coverUrl != null) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    if (coverUrl != null) {
                        val context = LocalContext.current
                        AsyncImage(
                            model = fastScrollableImageRequest(
                                context = context,
                                data = coverUrl,
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
                    } else {
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
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                HapticIconButton(
                    onClick = { onPlayPause() },
                    enabled = playPauseEnabled
                ) {
                    PlaybackControlIndicator(
                        isPlaying = isPlaying,
                        isPlaybackWaiting = isPlaybackWaiting,
                        playContentDescription = stringResource(R.string.lyrics_play),
                        pauseContentDescription = stringResource(R.string.lyrics_pause),
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
