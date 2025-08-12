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
 * File: moe.ouom.neriplayer.ui.screens/PlaylistDetailScreen
 * Created: 2025/8/10
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.viewmodel.NeteasePlaylist
import moe.ouom.neriplayer.ui.viewmodel.PlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.formatPlayCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: NeteasePlaylist,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: PlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as android.app.Application
                PlaylistDetailViewModel(app)
            }
        }
    )
    val ui by vm.uiState.collectAsState()

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentIndex = ui.tracks.indexOfFirst { it.id == currentSong?.id }

    LaunchedEffect(playlist.id) { vm.start(playlist) }

    val headerHeight: Dp = 280.dp

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 6 },
        exit = fadeOut() + slideOutVertically { it / 6 }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = ui.header?.name ?: playlist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(headerHeight)
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(ui.header?.coverUrl.takeUnless { it.isNullOrBlank() }
                                            ?: playlist.picUrl)
                                        .crossfade(true)
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .networkCachePolicy(CachePolicy.ENABLED)
                                        .build(),
                                    contentDescription = ui.header?.name ?: playlist.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Black.copy(alpha = 0.10f),
                                                        Color.Black.copy(alpha = 0.35f),
                                                        Color.Transparent
                                                    ),
                                                    startY = 0f,
                                                    endY = size.height
                                                )
                                            )
                                        }
                                )

                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = ui.header?.name ?: playlist.name,
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "播放量 ${formatPlayCount(ui.header?.playCount ?: playlist.playCount)} · ${(ui.header?.trackCount ?: playlist.trackCount)} 首",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        }

                        // 状态块
                        when {
                            ui.loading && ui.tracks.isEmpty() -> {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator()
                                        Text("  正在拉取歌单曲目...")
                                    }
                                }
                            }

                            ui.error != null && ui.tracks.isEmpty() -> {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "加载失败：${ui.error}",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        RetryChip { vm.retry() }
                                    }
                                }
                            }

                            else -> {
                                itemsIndexed(ui.tracks, key = { _, it -> it.id }) { index, item ->
                                    SongRow(
                                        index = index + 1,
                                        song = item,
                                        onClick = {
                                            NPLogger.d(
                                                "NERI-UI",
                                                "tap song index=$index id=${item.id}"
                                            )
                                            onSongClick(ui.tracks, index)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (currentIndex >= 0) {
                        FloatingActionButton(
                            onClick = {
                                scope.launch { listState.animateScrollToItem(currentIndex) }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                contentDescription = "定位到正在播放"
                            )
                        }
                    }
                }
            }
        }
    }
}

/* 小组件 */
@Composable
private fun RetryChip(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Text(
            "点我重试",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: SongItem,
    onClick: () -> Unit,
    indexWidth: Dp = 48.dp
) {
    val current by PlayerManager.currentSongFlow.collectAsState()
    val isPlayingSong = current?.id == song.id

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(indexWidth),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        }

        if (!song.coverUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(song.coverUrl).build(),
                    contentDescription = song.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    song.album.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isPlayingSong) {
            PlayingIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlayingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "playing")
    val animValues = listOf(
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 300),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar1"
        ),
        transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 350),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar2"
        ),
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar3"
        )
    )

    val barWidth = 3.dp
    val barMaxHeight = 12.dp

    Row(
        modifier = modifier.height(barMaxHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        animValues.forEach { anim ->
            Box(
                Modifier
                    .width(barWidth)
                    .height(barMaxHeight * anim.value)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}