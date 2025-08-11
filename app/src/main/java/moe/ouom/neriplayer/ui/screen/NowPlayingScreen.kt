package moe.ouom.neriplayer.ui.screen

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
 * File: moe.ouom.neriplayer.ui.screens/NowPlayingScreen
 * Created: 2025/8/8
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.component.WaveformSlider
import moe.ouom.neriplayer.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onNavigateUp: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsState()
    val durationMs = currentSong?.durationMs ?: 0L
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsState()

    var isUserDraggingSlider by remember(currentSong?.id) { mutableStateOf(false) }

    var sliderPosition by remember(currentSong?.id) {
        mutableFloatStateOf(PlayerManager.playbackPositionFlow.value.toFloat())
    }

    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        contentVisible = true
    }

    LaunchedEffect(currentPosition) {
        if (!isUserDraggingSlider) {
            sliderPosition = currentPosition.toFloat()
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 60) {
                            onNavigateUp()
                        }
                    }
                }
        ) {
            CenterAlignedTopAppBar(
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { /* 收藏 */ }) {
                        Icon(
                            Icons.Outlined.FavoriteBorder,
                            contentDescription = "收藏"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(
                visible = contentVisible,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = 400, delayMillis = 100),
                    initialOffsetY = { it / 5 }
                ) + fadeIn(animationSpec = tween(durationMillis = 400, delayMillis = 100))
            ) {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .background(
                            color = if (currentSong?.coverUrl != null) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(24.dp)
                        )
                ) {
                    currentSong?.coverUrl?.let { cover ->
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                            contentDescription = currentSong!!.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(24.dp))
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            AnimatedVisibility(
                visible = contentVisible,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = 400, delayMillis = 150),
                    initialOffsetY = { it / 4 }
                ) + fadeIn(animationSpec = tween(durationMillis = 400, delayMillis = 150))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(currentSong?.name ?: "", style = MaterialTheme.typography.headlineSmall)
                    Text(currentSong?.artist ?: "", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatDuration(sliderPosition.toLong()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                WaveformSlider(
                    modifier = Modifier.weight(1f),
                    value = if (durationMs > 0) sliderPosition / durationMs else 0f,
                    onValueChange = { newPercentage ->
                        isUserDraggingSlider = true
                        sliderPosition = (newPercentage * durationMs)
                    },
                    onValueChangeFinished = {
                        PlayerManager.seekTo(sliderPosition.toLong())
                        isUserDraggingSlider = false
                    },
                    isPlaying = isPlaying
                )

                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                IconButton(onClick = { PlayerManager.setShuffle(!shuffleEnabled) }) {
                    Icon(
                        Icons.Outlined.Shuffle,
                        contentDescription = "随机",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                IconButton(onClick = { PlayerManager.previous() }) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一首")
                }
                FilledIconButton(onClick = { PlayerManager.togglePlayPause() }) {
                    AnimatedContent(
                        targetState = isPlaying,
                        label = "play_pause_icon",
                        transitionSpec = {
                            (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut())
                        }
                    ) { currentlyPlaying ->
                        Icon(
                            imageVector = if (currentlyPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (currentlyPlaying) "暂停" else "播放"
                        )
                    }
                }
                IconButton(onClick = { PlayerManager.next() }) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "下一首")
                }
                IconButton(onClick = { PlayerManager.cycleRepeatMode() }) {
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Outlined.Repeat,
                        contentDescription = "循环",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
            }
        }
    }
}