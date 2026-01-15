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

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.component.AppleMusicLyric
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.WaveformSlider
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.util.HapticFilledIconButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun LyricsScreen(
    lyrics: List<LyricEntry>,
    lyricBlurEnabled: Boolean,
    lyricBlurAmount: Float,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    onEnterAlbum: (NeteaseAlbum) -> Unit,
    onNavigateBack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    translatedLyrics: List<LyricEntry>? = null,
    lyricOffsetMs: Long,
    showLyricTranslation: Boolean = true,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedContentScope: androidx.compose.animation.AnimatedContentScope? = null,
) {
    // 处理返回键
    androidx.activity.compose.BackHandler(onBack = onNavigateBack)

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsState()
    val durationMs = currentSong?.durationMs ?: 0L

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showSongNameMenu by remember { mutableStateOf(false) }
    var showArtistMenu by remember { mutableStateOf(false) }

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
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            HapticIconButton(onClick = onNavigateBack) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_back))
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 封面 - 紧邻返回键，缩小时约48dp
            Box(
                modifier = Modifier
                    .size((64 * coverScale).dp)
                    .then(
                        if (sharedTransitionScope != null && animatedContentScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(key = "cover_image"),
                                    animatedVisibilityScope = animatedContentScope
                                )
                            }
                        } else Modifier
                    )
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
                Box {
                    Text(
                        text = currentSong?.name ?: stringResource(R.string.lyrics_unknown_song),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showSongNameMenu = true }
                            )
                    )
                    DropdownMenu(
                        expanded = showSongNameMenu,
                        onDismissRequest = { showSongNameMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_song_name)) },
                            onClick = {
                                currentSong?.name?.let { clipboardManager.setText(AnnotatedString(it)) }
                                showSongNameMenu = false
                            }
                        )
                    }
                }
                Box {
                    Text(
                        text = currentSong?.artist ?: stringResource(R.string.lyrics_unknown_artist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .then(
                                if (sharedTransitionScope != null && animatedContentScope != null) {
                                    with(sharedTransitionScope) {
                                        Modifier.sharedElement(
                                            rememberSharedContentState(key = "song_artist"),
                                            animatedVisibilityScope = animatedContentScope
                                        )
                                    }
                                } else Modifier
                            )
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showArtistMenu = true }
                            )
                    )
                    DropdownMenu(
                        expanded = showArtistMenu,
                        onDismissRequest = { showArtistMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_artist)) },
                            onClick = {
                                currentSong?.artist?.let { clipboardManager.setText(AnnotatedString(it)) }
                                showArtistMenu = false
                            }
                        )
                    }
                }
            }

            // 收藏按钮（与 NowPlaying 保持一致的逻辑）
            val playlists by PlayerManager.playlistsFlow.collectAsState()
            val favoritePlaylistName = stringResource(R.string.lyrics_favorite_playlist)
            val isFavoriteComputed = remember(currentSong, playlists, favoritePlaylistName) {
                val song = currentSong
                if (song == null) {
                    false
                } else {
                    val fav = playlists.firstOrNull { it.name == "我喜欢的音乐" || it.name == "My Favorite Music" }
                    fav?.songs?.any { it.id == song.id && it.album == song.album } == true
                }
            }
            var favOverride by remember(currentSong) { mutableStateOf<Boolean?>(null) }
            val isFavorite = favOverride ?: isFavoriteComputed

            HapticIconButton(
                onClick = {
                    if (currentSong == null) return@HapticIconButton
                    val willFav = !isFavorite
                    favOverride = willFav
                    if (willFav) PlayerManager.addCurrentToFavorites() else PlayerManager.removeCurrentFromFavorites()
                },
                modifier = Modifier.size(48.dp)
                    .then(
                        if (sharedTransitionScope != null && animatedContentScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedBounds(
                                    rememberSharedContentState(key = "btn_favorite"),
                                    animatedVisibilityScope = animatedContentScope,
                                    enter = EnterTransition.None,
                                    exit = ExitTransition.None,
                                ).zIndex(1f)
                            }
                        } else Modifier
                    )
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) stringResource(R.string.lyrics_favorited) else stringResource(R.string.lyrics_favorite),
                    tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 更多按钮
            var showMoreOptions by remember { mutableStateOf(false) }
            HapticIconButton(
                onClick = { showMoreOptions = true },
                modifier = Modifier.size(48.dp)
                    .then(
                        if (sharedTransitionScope != null && animatedContentScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedBounds(
                                    rememberSharedContentState(key = "btn_more"),
                                    animatedVisibilityScope = animatedContentScope,
                                    enter = EnterTransition.None,
                                    exit = ExitTransition.None,
                                ).zIndex(1f)
                            }
                        } else Modifier
                    )
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.lyrics_more_options))
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
                    onEnterAlbum = onEnterAlbum,
                    onNavigateUp = onNavigateBack,
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
                        inactiveBlurNear = 0.dp,
                        inactiveBlurFar = 0.dp
                    ),
                    lyricOffsetMs = lyricOffsetMs,
                    lyricBlurEnabled = lyricBlurEnabled,
                    lyricBlurAmount = lyricBlurAmount,
                    onLyricClick = { lyricEntry ->
                        onSeekTo(lyricEntry.startTimeMs)
                    },
                    translatedLyrics = if (showLyricTranslation) translatedLyrics else null,
                    translationFontSize = (16 * lyricFontScale).coerceIn(12f, 26f).sp,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.lyrics_no_lyrics), style = MaterialTheme.typography.headlineSmall)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (sharedTransitionScope != null && animatedContentScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedBounds(
                                    rememberSharedContentState(key = "progress_bar"),
                                    animatedVisibilityScope = animatedContentScope
                                ).zIndex(1f)
                            }
                        } else Modifier
                    ),
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
                HapticIconButton(onClick = { PlayerManager.previous() },
                    modifier = Modifier
                    .then(
                        if (sharedTransitionScope != null && animatedContentScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(key = "player_previous"),
                                    animatedVisibilityScope = animatedContentScope
                                )
                            }
                        } else Modifier
                    )
                    .size(42.dp)
                ) {
                    Icon(
                        Icons.Outlined.SkipPrevious,
                        contentDescription = stringResource(R.string.lyrics_previous),
                        modifier = Modifier.size(32.dp)
                    )
                }

                HapticFilledIconButton(
                    onClick = { PlayerManager.togglePlayPause() },
                    modifier = Modifier
                        .then(
                            if (sharedTransitionScope != null && animatedContentScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(key = "play_button"),
                                        animatedVisibilityScope = animatedContentScope
                                    )
                                }
                            } else Modifier
                        )
                        .size(42.dp)
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        label = "play_pause_icon",
                        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }
                    ) { currentlyPlaying ->
                        Icon(
                            imageVector = if (currentlyPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (currentlyPlaying) stringResource(R.string.lyrics_pause) else stringResource(R.string.lyrics_play)
                        )
                    }
                }

                HapticIconButton(onClick = { PlayerManager.next() },
                    modifier = Modifier
                        .then(
                            if (sharedTransitionScope != null && animatedContentScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(key = "player_next"),
                                        animatedVisibilityScope = animatedContentScope
                                    )
                                }
                            } else Modifier
                        )
                        .size(42.dp)
                ) {
                    Icon(
                        Icons.Outlined.SkipNext,
                        contentDescription = stringResource(R.string.lyrics_next),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 底部操作栏（固定在底部，与 NowPlayingScreen 完全一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放队列按钮
            var showQueueSheet by remember { mutableStateOf(false) }
            HapticIconButton(onClick = { showQueueSheet = true },  modifier = Modifier.then(
                if (sharedTransitionScope != null && animatedContentScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            rememberSharedContentState(key = "btn_queue"),
                            animatedVisibilityScope = animatedContentScope,
                            enter = EnterTransition.None,
                            exit = ExitTransition.None,
                        ).zIndex(1f)
                    }
                } else Modifier
            )) {
                Icon(
                    Icons.AutoMirrored.Outlined.QueueMusic,
                    contentDescription = stringResource(R.string.lyrics_playlist),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 定时器按钮
            val sleepTimerState by PlayerManager.sleepTimerManager.timerState.collectAsState()
            var showSleepTimerDialog by remember { mutableStateOf(false) }
            HapticIconButton(onClick = { showSleepTimerDialog = true },
                modifier = Modifier.then(
                    if (sharedTransitionScope != null && animatedContentScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                rememberSharedContentState(key = "btn_timer"),
                                animatedVisibilityScope = animatedContentScope,
                                enter = EnterTransition.None,
                                exit = ExitTransition.None,
                            ).zIndex(1f)
                        }
                    } else Modifier
                )) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = stringResource(R.string.lyrics_timer),
                    tint = if (sleepTimerState.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 音量按钮（根据设备显示不同图标，居中）
            val context = LocalContext.current
            val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
            val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val audioDeviceIcon = remember(devices) {
                when {
                    devices.any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> Icons.Default.Headset
                    devices.any { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> Icons.Default.Headset
                    else -> Icons.Default.SpeakerGroup
                }
            }
            var showVolumeSheet by remember { mutableStateOf(false) }
            HapticIconButton(onClick = { showVolumeSheet = true },
                modifier = Modifier.then(
                    if (sharedTransitionScope != null && animatedContentScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                rememberSharedContentState(key = "btn_volume"),
                                animatedVisibilityScope = animatedContentScope,
                                enter = EnterTransition.None,
                                exit = ExitTransition.None,
                            ).zIndex(1f)
                        }
                    } else Modifier
                )) {
                Icon(
                    audioDeviceIcon,
                    contentDescription = stringResource(R.string.cd_audio_device),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 歌词按钮（返回封面页，高亮显示）
            @SuppressLint("UnusedContentLambdaTargetStateParameter")
            HapticIconButton(onClick = onNavigateBack,
                modifier = Modifier.then(
                if (sharedTransitionScope != null && animatedContentScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            rememberSharedContentState(key = "btn_lyrics"),
                            animatedVisibilityScope = animatedContentScope,
                            enter = EnterTransition.None,
                            exit = ExitTransition.None,
                        ).zIndex(1f)
                    }
                } else Modifier
            )) {
                AnimatedContent(
                    targetState = true,
                    transitionSpec = {
                        (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut())
                    },
                    label = "lyrics_icon"
                ) { _ ->
                    Icon(
                        imageVector = Icons.Outlined.LibraryMusic,
                        contentDescription = stringResource(R.string.lyrics_back_to_cover),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 添加到歌单按钮
            var showAddSheet by remember { mutableStateOf(false) }
            HapticIconButton(onClick = { showAddSheet = true },
                modifier = Modifier.then(
                if (sharedTransitionScope != null && animatedContentScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            rememberSharedContentState(key = "btn_add"),
                            animatedVisibilityScope = animatedContentScope,
                            enter = EnterTransition.None,
                            exit = ExitTransition.None,
                        ).zIndex(1f)
                    }
                } else Modifier
            )) {
                Icon(
                    Icons.AutoMirrored.Outlined.PlaylistAdd,
                    contentDescription = stringResource(R.string.lyrics_add_to_playlist),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 定时器对话框
            if (showSleepTimerDialog) {
                moe.ouom.neriplayer.ui.component.SleepTimerDialog(
                    onDismiss = { showSleepTimerDialog = false }
                )
            }

            // 音量控制弹窗
            if (showVolumeSheet) {
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showVolumeSheet = false }
                ) {
                    VolumeControlSheetContent()
                }
            }

            // 播放队列弹窗
            if (showQueueSheet) {
                val queue by PlayerManager.currentQueueFlow.collectAsState()
                val displayedQueue = remember(queue) { queue }
                val currentIndexInDisplay = displayedQueue.indexOfFirst {
                    it.id == currentSong?.id && it.album == currentSong?.album
                }
                val initialIndex = (currentIndexInDisplay - 4).coerceAtLeast(0)
                val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

                LaunchedEffect(showQueueSheet, currentIndexInDisplay) {
                    if (showQueueSheet && currentIndexInDisplay >= 0) {
                        kotlinx.coroutines.delay(150)
                        listState.animateScrollToItem(currentIndexInDisplay)
                    }
                }

                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showQueueSheet = false }
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(state = listState) {
                        itemsIndexed(displayedQueue) { index, song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        PlayerManager.playFromQueue(index)
                                        showQueueSheet = false
                                    }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(48.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(song.name, maxLines = 1)
                                    Text(
                                        song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (index == currentIndexInDisplay) {
                                        Icon(
                                            Icons.Outlined.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    var showMoreMenu by remember { mutableStateOf(false) }
                                    Box {
                                        androidx.compose.material3.IconButton(onClick = { showMoreMenu = true }) {
                                            Icon(
                                                Icons.Filled.MoreVert,
                                                contentDescription = stringResource(R.string.common_more_actions),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showMoreMenu,
                                            onDismissRequest = { showMoreMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.local_playlist_play_next)) },
                                                onClick = {
                                                    PlayerManager.addToQueueNext(song)
                                                    showMoreMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.playlist_add_to_end)) },
                                                onClick = {
                                                    PlayerManager.addToQueueEnd(song)
                                                    showMoreMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // 添加到歌单弹窗
            if (showAddSheet && currentSong != null) {
                val playlists by PlayerManager.playlistsFlow.collectAsState()
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showAddSheet = false }
                ) {
                    androidx.compose.foundation.lazy.LazyColumn {
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
                                Text(stringResource(R.string.lyrics_song_count, pl.songs.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

