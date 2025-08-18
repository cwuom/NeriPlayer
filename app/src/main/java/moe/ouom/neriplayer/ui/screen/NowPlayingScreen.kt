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
 * File: moe.ouom.neriplayer.ui.screen/NowPlayingScreen
 * Created: 2025/8/8
 */

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.component.AppleMusicLyric
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.WaveformSlider
import moe.ouom.neriplayer.ui.component.parseNeteaseLrc
import moe.ouom.neriplayer.ui.component.parseNeteaseYrc
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticFilledIconButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatDuration
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onNavigateUp: () -> Unit,
    lyricBlurEnabled: Boolean,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsState()
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsState()
    val durationMs = currentSong?.durationMs ?: 0L

    // 订阅当前播放链接
    val currentMediaUrl by PlayerManager.currentMediaUrlFlow.collectAsState()
    val isFromNetease = currentMediaUrl?.contains("music.126.net", ignoreCase = true) == true
    val isFromBili = currentMediaUrl?.contains("bilivideo.", ignoreCase = true) == true

    // 歌单&收藏
    val playlists by PlayerManager.playlistsFlow.collectAsState()

    // 点击即切换，回流后撤销覆盖
    var favOverride by remember(currentSong) { mutableStateOf<Boolean?>(null) }
    val isFavoriteComputed = remember(currentSong, playlists) {
        val song = currentSong
        if (song == null) {
            false
        } else {
            val fav = playlists.firstOrNull { it.name == "我喜欢的音乐" }
            fav?.songs?.any { it.id == song.id && it.album == song.album } == true
        }
    }
    val isFavorite = favOverride ?: isFavoriteComputed

    // 缩放动画
    var bumpKey by remember(currentSong?.id) { mutableIntStateOf(0) }
    if (isFavorite) 1.0f else 1.0f
    val scale by animateFloatAsState(
        targetValue = if (bumpKey == 0) 1.0f else 1.12f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.42f),
        label = "heart_bump_scale"
    )

    val queue by PlayerManager.currentQueueFlow.collectAsState()
    val displayedQueue = remember(queue) { queue }
    val currentIndexInDisplay = displayedQueue.indexOfFirst {
        it.id == currentSong?.id && it.album == currentSong?.album
    }

    var showAddSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState()
    val queueSheetState = rememberModalBottomSheetState()

    // 是否拖拽进度条
    var isUserDraggingSlider by remember(currentSong?.id) { mutableStateOf(false) }
    var sliderPosition by remember(currentSong?.id) {
        mutableFloatStateOf(PlayerManager.playbackPositionFlow.value.toFloat())
    }

    // 内容的进入动画
    var contentVisible by remember { mutableStateOf(false) }

    // 控制音量弹窗的显示
    var showVolumeSheet by remember { mutableStateOf(false) }
    val volumeSheetState = rememberModalBottomSheetState()


    var lyrics by remember(currentSong?.id) { mutableStateOf<List<LyricEntry>>(emptyList()) }

    val nowPlayingViewModel: NowPlayingViewModel = viewModel()

    LaunchedEffect(currentSong?.id, currentSong?.matchedLyric, isFromNetease) {
        val song = currentSong
        lyrics = when {
            // 优先使用匹配到的歌词
            song?.matchedLyric != null -> {
                if (song.matchedLyric.contains(Regex("""\[\d+,\s*\d+]\(\d+,"""))) {
                    parseNeteaseYrc(song.matchedLyric)
                } else {
                    parseNeteaseLrc(song.matchedLyric)
                }
            }
            song != null && isFromNetease -> {
                PlayerManager.getNeteaseLyrics(song.id)
            }
            else -> {
                emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { contentVisible = true }
    LaunchedEffect(currentPosition) { if (!isUserDraggingSlider) sliderPosition = currentPosition.toFloat() }

    // 当仓库回流或歌曲切换时，撤销本地乐观覆盖，用真实状态对齐
    LaunchedEffect(playlists, currentSong?.id) { favOverride = null }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 60) onNavigateUp() }
                }
        ) {
            CenterAlignedTopAppBar(
                title = { Text("正在播放") },
                navigationIcon = {
                    HapticIconButton(onClick = onNavigateUp) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "返回")
                    }
                },
                actions = {
                    HapticIconButton(onClick = {
                        if (currentSong == null) return@HapticIconButton
                        val willFav = !isFavorite
                        favOverride = willFav
                        if (willFav) {
                            PlayerManager.addCurrentToFavorites()
                        } else {
                            PlayerManager.removeCurrentFromFavorites()
                        }
                        // 触发一次弹跳
                        bumpKey++
                    }) {
                        // 图标切换 和 scale/fade 动画
                        AnimatedContent(
                            targetState = isFavorite,
                            transitionSpec = {
                                (scaleIn(animationSpec = tween(160), initialScale = 0.85f) + fadeIn()) togetherWith
                                        (scaleOut(animationSpec = tween(140), targetScale = 0.85f) + fadeOut())
                            },
                            label = "favorite_icon_anim"
                        ) { fav ->
                            Icon(
                                imageVector = if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (fav) "已收藏" else "收藏",
                                tint = if (fav) Color.Red else LocalContentColor.current,
                                modifier = Modifier.graphicsLayer {
                                    val s = if (bumpKey == 0) 1f else scale
                                    scaleX = s
                                    scaleY = s
                                }
                            )
                        }
                    }

                    var showMoreOptions by remember { mutableStateOf(false) }
                    HapticIconButton(onClick = { showMoreOptions = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                    }
                    if (showMoreOptions) {
                        MoreOptionsSheet(
                            viewModel = nowPlayingViewModel,
                            originalSong = currentSong!!, // 此时 currentSong 必不为 null
                            queue = displayedQueue,
                            onDismiss = { showMoreOptions = false }
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(Modifier.height(8.dp))

            // 封面
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
                        .size(240.dp)
                        .background(
                            color = if (currentSong?.coverUrl != null) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(24.dp)
                        )
                ) {
                    currentSong?.coverUrl?.let { cover ->
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                            contentDescription = currentSong?.name ?: "",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(24.dp))
                        )
                    }

                    // 右下角覆盖显示
                    if (isFromNetease) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                                contentDescription = "网易云音乐",
                                tint = LocalContentColor.current,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "网易云",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (isFromBili) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_bilibili),
                                contentDescription = "哔哩哔哩",
                                tint = LocalContentColor.current,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "哔哩哔哩",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(currentSong?.name ?: "", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        currentSong?.artist ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                HapticIconButton(onClick = { PlayerManager.setShuffle(!shuffleEnabled) }) {
                    Icon(
                        Icons.Outlined.Shuffle,
                        contentDescription = "随机",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                HapticIconButton(onClick = { PlayerManager.previous() }) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一首")
                }

                HapticFilledIconButton(onClick = { PlayerManager.togglePlayPause() }) {
                    AnimatedContent(
                        targetState = isPlaying,
                        label = "play_pause_icon",
                        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }
                    ) { currentlyPlaying ->
                        Icon(
                            imageVector = if (currentlyPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (currentlyPlaying) "暂停" else "播放"
                        )
                    }
                }
                HapticIconButton(onClick = { PlayerManager.next() }) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "下一首")
                }
                HapticIconButton(onClick = { PlayerManager.cycleRepeatMode() }) {
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Outlined.Repeat,
                        contentDescription = "循环",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
            }

            if (lyrics.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                
                val platformOffset = if (currentSong?.matchedLyricSource == MusicPlatform.QQ_MUSIC) {
                    500L
                } else {
                    1000L
                }

                val userOffset = currentSong?.userLyricOffsetMs ?: 0L
                val totalOffset = platformOffset + userOffset

                AppleMusicLyric(
                    lyrics = lyrics,
                    currentTimeMs = currentPosition,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(8f),
                    textColor = MaterialTheme.colorScheme.onBackground,
                    visualSpec = LyricVisualSpec(),
                    lyricOffsetMs = totalOffset,
                    lyricBlurEnabled = lyricBlurEnabled
                )
            }


            // 将下面的内容推到底部
            Spacer(modifier = Modifier.weight(1f))

            // 底部操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HapticIconButton(onClick = { showQueueSheet = true }) {
                    Icon(Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = "播放列表")
                }
                HapticTextButton(onClick = { showVolumeSheet = true }) {
                    AudioDeviceHandler()
                }
                HapticIconButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = "添加到歌单")
                }
            }
        }

        // 音量控制弹窗
        if (showVolumeSheet) {
            ModalBottomSheet(
                onDismissRequest = { showVolumeSheet = false },
                sheetState = volumeSheetState
            ) {
                VolumeControlSheetContent()
            }
        }

        // 播放队列弹窗
        if (showQueueSheet) {
            val initialIndex = (currentIndexInDisplay - 4).coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            LaunchedEffect(showQueueSheet, currentIndexInDisplay) {
                if (showQueueSheet && currentIndexInDisplay >= 0) {
                    delay(150)
                    listState.animateScrollToItem(currentIndexInDisplay)
                }
            }

            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                sheetState = queueSheetState
            ) {
                LazyColumn(state = listState) {
                    itemsIndexed(displayedQueue) { index, song ->
                        val sourceIndex = index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    PlayerManager.playFromQueue(sourceIndex)
                                    showQueueSheet = false
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text((index + 1).toString(), modifier = Modifier.width(48.dp), textAlign = TextAlign.Start, fontFamily = FontFamily.Monospace)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(song.name, maxLines = 1)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (index == currentIndexInDisplay) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        if (showAddSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddSheet = false },
                sheetState = addSheetState
            ) {
                LazyColumn {
                    itemsIndexed(playlists) { _, pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    PlayerManager.addCurrentToPlaylist(pl.id)
                                    showAddSheet = false
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("${pl.songs.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AudioDeviceHandler() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var deviceInfo by remember { mutableStateOf(getCurrentAudioDevice(audioManager)) }

    DisposableEffect(Unit) {
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                deviceInfo = getCurrentAudioDevice(audioManager)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                deviceInfo = getCurrentAudioDevice(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = deviceInfo.second,
                contentDescription = "播放设备",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = deviceInfo.first,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun getCurrentAudioDevice(audioManager: AudioManager): Pair<String, ImageVector> {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val bluetoothDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    if (bluetoothDevice != null) {
        return try {
            Pair(bluetoothDevice.productName.toString().ifBlank { "蓝牙设备" }, Icons.Default.Headset)
        } catch (_: SecurityException) {
            Pair("蓝牙设备", Icons.Default.Headset)
        }
    }
    val wiredHeadset = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
    if (wiredHeadset != null) return Pair("有线耳机", Icons.Default.Headset)
    return Pair("手机扬声器", Icons.Default.SpeakerGroup)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreOptionsSheet(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showSearchView by remember { mutableStateOf(false) }
    var showOffsetSheet by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // 当弹窗打开时，如果需要，预填充搜索词
    LaunchedEffect(showSearchView) {
        if (showSearchView) {
            viewModel.prepareForSearch(originalSong.name)
            viewModel.performSearch()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AnimatedContent(
            targetState = when {
                showOffsetSheet -> "Offset"
                showSearchView -> "Search"
                else -> "Main"
            },
            transitionSpec = {
                (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                        scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                    .togetherWith(fadeOut(animationSpec = tween(90)))
            },
            label = "more_options_sheet_content"
        ) { targetState ->
            when (targetState) {
                "Main" -> {
                    Column(Modifier.padding(bottom = 32.dp)) {
                        ListItem(
                            headlineContent = { Text("获取歌曲信息") },
                            leadingContent = { Icon(Icons.Outlined.Info, null) },
                            modifier = Modifier.clickable { showSearchView = true }
                        )
                        ListItem(
                            headlineContent = { Text("调整歌词偏移") },
                            leadingContent = { Icon(Icons.Outlined.Timer, null) },
                            modifier = Modifier.clickable { showOffsetSheet = true }
                        )
                        ListItem(
                            headlineContent = { Text("分享") },
                            leadingContent = { Icon(Icons.Outlined.Share, null) },
                            modifier = Modifier.clickable {
                                val song = originalSong
                                val isFromBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)

                                val url = if (isFromBili) {
                                    // 筛选出队列中属于同一个B站视频的所有分P
                                    val videoParts = queue.filter {
                                        it.id == song.id && it.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
                                    }
                                    // 如果分 P 数量大于1，说明是多 P 视频
                                    if (videoParts.size > 1) {
                                        // 找到当前分 P 在列表中的索引
                                        val pageIndex = videoParts.indexOfFirst {
                                            // 使用包含 cid 的 album 字段进行精确匹配
                                            it.album == song.album
                                        }
                                        val pageNumber = pageIndex + 1
                                        if (pageIndex != -1) {
                                            // 生成带分 P 参数的链接
                                            "https://www.bilibili.com/video/av${song.id}/?p=${pageNumber}"
                                        } else {
                                            // 如果意外没找到，则回退到不带分P的链接
                                            "https://www.bilibili.com/video/av${song.id}"
                                        }
                                    } else {
                                        // 单 P 视频
                                        "https://www.bilibili.com/video/av${song.id}"
                                    }
                                } else {
                                    // 其他来源的歌曲
                                    "https://music.163.com/#/song?id=${song.id}"
                                }

                                // 在分享文本中加入艺术家/UP主名称
                                val shareText = "分享歌曲：${song.name} - ${song.artist}\n$url"

                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }

                                val shareIntent = Intent.createChooser(sendIntent, null)
                                context.startActivity(shareIntent)
                                onDismiss()
                            }
                        )
                    }
                }
                "Search" -> {
                    // 搜索界面
                    val searchState by viewModel.manualSearchState.collectAsState()

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OutlinedTextField(
                            value = searchState.keyword,
                            onValueChange = { viewModel.onKeywordChange(it) },
                            label = { Text("搜索关键词") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            trailingIcon = {
                                HapticIconButton(onClick = { viewModel.performSearch() }) {
                                    Icon(Icons.Default.Search, contentDescription = "搜索")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                viewModel.performSearch()
                                focusManager.clearFocus()
                            }),
                        )

                        // 平台切换
                        TabRow(selectedTabIndex = searchState.selectedPlatform.ordinal) {
                            MusicPlatform.entries.forEachIndexed { index, platform ->
                                Tab(
                                    selected = searchState.selectedPlatform.ordinal == index,
                                    onClick = { viewModel.selectPlatform(platform) },
                                    text = { Text(platform.name.replace("_", " ")) }
                                )
                            }
                        }

                        // 搜索结果区域
                        Box(Modifier.height(300.dp)) {
                            if (searchState.isLoading) {
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                            } else if (searchState.searchResults.isNotEmpty()) {
                                LazyColumn {
                                    items(searchState.searchResults) { songResult ->
                                        ListItem(
                                            headlineContent = { Text(songResult.songName, maxLines = 1) },
                                            supportingContent = { Text(songResult.singer, maxLines = 1) },
                                            leadingContent = {
                                                AsyncImage(
                                                    model = songResult.coverUrl?.replaceFirst(
                                                        "http://",
                                                        "https://"
                                                    ),
                                                    contentDescription = songResult.songName,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            },
                                            modifier = Modifier.clickable {
                                                viewModel.onSongSelected(originalSong, songResult)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = searchState.error ?: "无搜索结果",
                                    modifier = Modifier.align(Alignment.Center),
                                    color = if (searchState.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                                )
                            }
                        }
                    }
                }
                "Offset" -> {
                    LyricOffsetSheet(
                        song = originalSong,
                        onDismiss = { showOffsetSheet = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeControlSheetContent() {
    val context = LocalContext.current
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("音量", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(imageVector = Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "音量")
            Slider(
                value = currentVolume.toFloat(),
                onValueChange = {
                    currentVolume = it.toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                },
                valueRange = 0f..maxVolume.toFloat(),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LyricOffsetSheet(song: SongItem, onDismiss: () -> Unit) {
    var currentOffset by remember { mutableLongStateOf(song.userLyricOffsetMs) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("歌词偏移调整", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${if (currentOffset > 0) "+" else ""}${currentOffset} ms",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            color = when {
                currentOffset > 0 -> Color(0xFF388E3C) // 快了 绿色
                currentOffset < 0 -> MaterialTheme.colorScheme.error // 慢了红色
                else -> LocalContentColor.current
            }
        )
        Text("向右滑动歌词快进，向左滑动歌词延后", style = MaterialTheme.typography.bodySmall)

        Slider(
            value = currentOffset.toFloat(),
            onValueChange = {
                currentOffset = (it / 50).roundToInt() * 50L
            },
            onValueChangeFinished = {
                scope.launch {
                    PlayerManager.updateUserLyricOffset(song, currentOffset)
                }
            },
            valueRange = -2000f..2000f,
            steps = 79
        )
        Spacer(Modifier.height(16.dp))
        HapticTextButton(onClick = onDismiss) {
            Text("完成")
        }
    }
}