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
 * File: moe.ouom.neriplayer.ui.screen/LyricsScreen
 * Created: 2025/8/13
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.component.AppleMusicLyric
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.WaveformSlider
import moe.ouom.neriplayer.util.HapticFilledIconButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
    lyrics: List<LyricEntry>,
    lyricBlurEnabled: Boolean,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    onNavigateBack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    translatedLyrics: List<LyricEntry>? = null,
) {
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsState()
    val durationMs = currentSong?.durationMs ?: 0L
    
    // 动画状态
    var isLyricsMode by remember { mutableStateOf(false) }
    
    // 启动进入动画
    LaunchedEffect(Unit) {
        isLyricsMode = true
    }
    
    // 封面动画
    val coverScale by animateFloatAsState(
        targetValue = if (isLyricsMode) 0.6f else 1f,
        animationSpec = spring(dampingRatio = 0.8f),
        label = "cover_scale"
    )
    // 垂直偏移控制在标题栏内（约-8dp），避免飞出界面
    val coverOffsetY by animateFloatAsState(
        targetValue = if (isLyricsMode) -8f else 0f,
        animationSpec = spring(dampingRatio = 0.8f),
        label = "cover_offset_y"
    )
    
    // 播放控件动画 - 轻微上浮/下沉，保持常驻在安全区域内
    val controlsOffsetY by animateFloatAsState(
        targetValue = if (isLyricsMode) 0f else 0f,
        animationSpec = spring(dampingRatio = 0.85f),
        label = "controls_offset_y"
    )
    
    // 进度条拖拽状态
    var isUserDraggingSlider by remember(currentSong?.id) { mutableStateOf(false) }
    var sliderPosition by remember(currentSong?.id) {
        mutableFloatStateOf(PlayerManager.playbackPositionFlow.value.toFloat())
    }

    // 使用填充整个屏幕，不创建新背景，复用现有背景
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    // 右滑返回
                    if (dragAmount > 50) {
                        onNavigateBack()
                    }
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // 顶部区域 - 包含缩小的封面 + 收藏 + 更多
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            HapticIconButton(onClick = onNavigateBack) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "返回")
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 封面 - 紧邻返回键，缩小时约48dp
            Box(
                modifier = Modifier
                    .size((64 * coverScale).dp)
                    .graphicsLayer { translationY = coverOffsetY }
                    .clip(RoundedCornerShape(10.dp))
            ) {
                currentSong?.coverUrl?.let { cover ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                        contentDescription = currentSong?.name ?: "",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 标题区始终占用剩余空间，避免挤出边界
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = currentSong?.name ?: "未知歌曲",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currentSong?.artist ?: "未知艺术家",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 收藏按钮（与 NowPlaying 保持一致的逻辑）
            val playlists by PlayerManager.playlistsFlow.collectAsState()
            val isFavoriteComputed = remember(currentSong, playlists) {
                val song = currentSong
                if (song == null) {
                    false
                } else {
                    val fav = playlists.firstOrNull { it.name == "我喜欢的音乐" }
                    fav?.songs?.any { it.id == song.id && it.album == song.album } == true
                }
            }
            var favOverride by remember(currentSong) { mutableStateOf<Boolean?>(null) }
            val isFavorite = favOverride ?: isFavoriteComputed

            HapticIconButton(onClick = {
                if (currentSong == null) return@HapticIconButton
                val willFav = !isFavorite
                favOverride = willFav
                if (willFav) PlayerManager.addCurrentToFavorites() else PlayerManager.removeCurrentFromFavorites()
            }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "已收藏" else "收藏",
                    tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 更多按钮
            var showMoreOptions by remember { mutableStateOf(false) }
            HapticIconButton(onClick = { showMoreOptions = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多选项")
            }
            if (showMoreOptions && currentSong != null) {
                val queue by PlayerManager.currentQueueFlow.collectAsState()
                val displayedQueue = remember(queue) { queue }
                val nowPlayingViewModel: moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                val snackbarHostState = remember { SnackbarHostState() }
                MoreOptionsSheet(
                    viewModel = nowPlayingViewModel,
                    originalSong = currentSong!!,
                    queue = displayedQueue,
                    onDismiss = { showMoreOptions = false },
                    snackbarHostState = snackbarHostState,
                    lyricFontScale = lyricFontScale,
                    onLyricFontScaleChange = onLyricFontScaleChange
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 歌词区域
        Box(
            modifier = Modifier.weight(1f)
        ) {
            if (lyrics.isNotEmpty()) {
                AppleMusicLyric(
                    lyrics = lyrics,
                    currentTimeMs = currentPosition,
                    modifier = Modifier.fillMaxSize(),
                    textColor = MaterialTheme.colorScheme.onBackground,
                    // 放大歌词与行距，增强可读性
                    fontSize = (20f * lyricFontScale).coerceIn(16f, 30f).sp,
                    centerPadding = 24.dp,
                    visualSpec = LyricVisualSpec(
                        // 控制缩放范围，避免超界
                        activeScale = 1.06f,
                        nearScale = 0.95f,
                        farScale = 0.88f,
                        inactiveBlurNear = if (lyricBlurEnabled) 2.dp else 0.dp,
                        inactiveBlurFar = if (lyricBlurEnabled) 4.dp else 0.dp
                    ),
                    lyricOffsetMs = 1000L,
                    lyricBlurEnabled = lyricBlurEnabled,
                    onLyricClick = { lyricEntry ->
                        onSeekTo(lyricEntry.startTimeMs)
                    },
                    translatedLyrics = translatedLyrics,
                    translationFontSize = (16 * lyricFontScale).coerceIn(12f, 26f).sp
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无歌词", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        
        // 底部控件 - 使用共享元素动画
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // 进度条
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatDuration(
                        if (isUserDraggingSlider) sliderPosition.toLong() else currentPosition
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                WaveformSlider(
                    modifier = Modifier.weight(1f),
                    value = if (durationMs > 0) {
                        if (isUserDraggingSlider) sliderPosition / durationMs else currentPosition.toFloat() / durationMs
                    } else 0f,
                    onValueChange = { newValue ->
                        isUserDraggingSlider = true
                        sliderPosition = newValue * durationMs.toFloat()
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
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 播放控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HapticIconButton(onClick = { PlayerManager.previous() }) {
                    Icon(
                        Icons.Outlined.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                HapticFilledIconButton(
                    onClick = { PlayerManager.togglePlayPause() },
                    modifier = Modifier.size(64.dp)
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        label = "play_pause_icon",
                        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }
                    ) { currentlyPlaying ->
                        Icon(
                            imageVector = if (currentlyPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (currentlyPlaying) "暂停" else "播放",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                
                HapticIconButton(onClick = { PlayerManager.next() }) {
                    Icon(
                        Icons.Outlined.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
