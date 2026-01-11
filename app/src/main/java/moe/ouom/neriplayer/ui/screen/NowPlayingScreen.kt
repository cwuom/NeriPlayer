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
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.LibraryMusic
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.AppleMusicLyric
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.SleepTimerDialog
import moe.ouom.neriplayer.ui.component.WaveformSlider
import moe.ouom.neriplayer.ui.component.parseNeteaseLrc
import moe.ouom.neriplayer.ui.component.parseNeteaseYrc
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticFilledIconButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatDuration
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingScreen(
    onNavigateUp: () -> Unit,
    onEnterAlbum: (NeteaseAlbum) -> Unit,
    lyricBlurEnabled: Boolean,
    lyricBlurAmount: Float,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    showLyricTranslation: Boolean = true,
) {
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsState()
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsState()
    val durationMs = currentSong?.durationMs ?: 0L
    val sleepTimerState by PlayerManager.sleepTimerManager.timerState.collectAsState()

    // 订阅当前播放链接
    val currentMediaUrl by PlayerManager.currentMediaUrlFlow.collectAsState()
    val isFromNetease = currentMediaUrl?.contains("music.126.net", ignoreCase = true) == true
    val isFromBili = currentMediaUrl?.contains("bilivideo.", ignoreCase = true) == true

    // 歌单&收藏
    val playlists by PlayerManager.playlistsFlow.collectAsState()

    // 点击即切换，回流后撤销覆盖
    var favOverride by remember(currentSong) { mutableStateOf<Boolean?>(null) }
    val favoritePlaylistName = stringResource(R.string.favorite_my_music)
    val isFavoriteComputed = remember(currentSong, playlists, favoritePlaylistName) {
        val song = currentSong
        if (song == null) {
            false
        } else {
            val fav = playlists.firstOrNull { it.name == "我喜欢的音乐" || it.name == "My Favorite Music" }
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
    var showLyricsScreen by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSongNameMenu by remember { mutableStateOf(false) }
    var showArtistMenu by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState()
    val queueSheetState = rememberModalBottomSheetState()

    // Snackbar状态
    val snackbarHostState = remember { SnackbarHostState() }

    val clipboardManager = LocalClipboardManager.current

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
    var translatedLyrics by remember(currentSong?.id) { mutableStateOf<List<LyricEntry>>(emptyList()) }

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
            song != null -> {
                // 在线拉取歌词
                PlayerManager.getLyrics(song)
            }
            else -> {
                emptyList()
            }
        }

        // 同步尝试拉取翻译（仅云音乐有）
        translatedLyrics = try {
            if (song != null) PlayerManager.getTranslatedLyrics(song) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    LaunchedEffect(Unit) { contentVisible = true }
    LaunchedEffect(currentPosition) { if (!isUserDraggingSlider) sliderPosition = currentPosition.toFloat() }

    // 当仓库回流或歌曲切换时，撤销本地乐观覆盖，用真实状态对齐
    LaunchedEffect(playlists, currentSong?.id) { favOverride = null }

    // 自适应布局判断
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val useTabletLandscapeLayout = isTablet && isLandscape

    // 歌词偏移（平台 + 用户自定义）
    val platformOffset = if (currentSong?.matchedLyricSource == MusicPlatform.QQ_MUSIC) 500L else 1000L
    val userOffset = currentSong?.userLyricOffsetMs ?: 0L
    val totalOffset = platformOffset + userOffset

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        SharedTransitionLayout {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = showLyricsScreen,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(durationMillis = 300, easing = LinearEasing)
                        ) togetherWith fadeOut(
                            animationSpec = tween(durationMillis = 300, easing = LinearEasing)
                        )
                    },
                    label = "lyrics_transition"
                ) { isLyricsMode ->
                    if (isLyricsMode) {
                        // 歌词全屏页面
                        LyricsScreen(
                            lyrics = lyrics,
                            lyricBlurEnabled = lyricBlurEnabled,
                            lyricBlurAmount = lyricBlurAmount,
                            lyricFontScale = lyricFontScale,
                            onEnterAlbum = onEnterAlbum,
                            onLyricFontScaleChange = onLyricFontScaleChange,
                            onNavigateBack = { showLyricsScreen = false },
                            onSeekTo = { position -> PlayerManager.seekTo(position) },
                            translatedLyrics = translatedLyrics,
                            lyricOffsetMs = totalOffset,
                            showLyricTranslation = showLyricTranslation,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@AnimatedContent
                        )
                    } else {
                // 播放页面
                var contentModifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 60) onNavigateUp() }
                    }

                // 手机或竖屏下，左滑进入歌词页
                if (!useTabletLandscapeLayout && lyrics.isNotEmpty()) {
                    contentModifier = contentModifier.pointerInput(lyrics) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount < -20) showLyricsScreen = true
                        }
                    }
                }

                // 主列内容
                val mainColumnContent: @Composable ColumnScope.() -> Unit = {
                    // 顶部栏
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        // 返回按钮 - 左侧
                        HapticIconButton(
                            onClick = onNavigateUp,
                            modifier = Modifier.align(Alignment.CenterStart)
                                .size(48.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = "btn_back"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = EnterTransition.None,
                                    exit = ExitTransition.None,
                                ).zIndex(1f)
                        ) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.action_back))
                        }

                        // 标题 - 居中
                        Text(
                            text = stringResource(R.string.player_now_playing),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.Center)
                        )

                        // 收藏和更多按钮 - 右侧
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            HapticIconButton(
                                onClick = {
                                    if (currentSong == null) return@HapticIconButton
                                    val willFav = !isFavorite
                                    favOverride = willFav
                                    if (willFav) PlayerManager.addCurrentToFavorites() else PlayerManager.removeCurrentFromFavorites()
                                },
                                modifier = Modifier.size(48.dp)
                                    .sharedBounds(
                                        rememberSharedContentState(key = "btn_favorite"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        enter = EnterTransition.None,
                                        exit = ExitTransition.None,
                                    ).zIndex(1f)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) stringResource(R.string.nowplaying_favorited) else stringResource(R.string.nowplaying_favorite),
                                    tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            var showMoreOptions by remember { mutableStateOf(false) }
                            HapticIconButton(
                                onClick = { showMoreOptions = true },
                                modifier = Modifier.size(48.dp)
                                    .sharedBounds(
                                        rememberSharedContentState(key = "btn_more"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        enter = EnterTransition.None,
                                        exit = ExitTransition.None,
                                    ).zIndex(1f)
                            ) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.nowplaying_more_options))
                            }
                            if (showMoreOptions && currentSong != null) {
                                MoreOptionsSheet(
                                    viewModel = nowPlayingViewModel,
                                    originalSong = currentSong!!,
                                    queue = displayedQueue,
                                    onDismiss = { showMoreOptions = false },
                                    onEnterAlbum = onEnterAlbum,
                                    onNavigateUp = onNavigateUp,
                                    snackbarHostState = snackbarHostState,
                                    lyricFontScale = lyricFontScale,
                                    onLyricFontScaleChange = onLyricFontScaleChange
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 封面
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(240.dp)
                            .sharedElement(
                                rememberSharedContentState(key = "cover_image"),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                color = if (currentSong?.coverUrl != null) Color.Transparent else MaterialTheme.colorScheme.primaryContainer
                            )
                    ) {
                        currentSong?.coverUrl?.let { cover ->
                            val context = LocalContext.current
                            AsyncImage(
                                model = offlineCachedImageRequest(context, cover),
                                contentDescription = currentSong?.name ?: "",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // 右下角来源徽标
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
                                    contentDescription = stringResource(R.string.cd_netease),
                                    tint = LocalContentColor.current,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.nowplaying_netease_cloud),
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
                                    contentDescription = stringResource(R.string.cd_bilibili),
                                    tint = LocalContentColor.current,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.nowplaying_bilibili),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 标题
                    AnimatedVisibility(
                        visible = contentVisible,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        enter = slideInVertically(
                            animationSpec = tween(durationMillis = 400, delayMillis = 150),
                            initialOffsetY = { it / 4 }
                        ) + fadeIn(animationSpec = tween(durationMillis = 400, delayMillis = 150))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                Text(
                                    text = currentSong?.name ?: "",
                                    style = MaterialTheme.typography.headlineSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
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
                                    text = currentSong?.artist ?: "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .sharedElement(
                                            rememberSharedContentState(key = "song_artist"),
                                            animatedVisibilityScope = this@AnimatedContent
                                        )
                                        .clip(RoundedCornerShape(8.dp))
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
                    }

                    Spacer(Modifier.height(12.dp))

                    // 进度条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sharedBounds(
                                rememberSharedContentState(key = "progress_bar"),
                                animatedVisibilityScope = this@AnimatedContent
                            ),
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

                    // 控制按钮
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        HapticIconButton(onClick = { PlayerManager.setShuffle(!shuffleEnabled) },
                            modifier = Modifier
                                .size(42.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Shuffle,
                                contentDescription = stringResource(R.string.player_shuffle),
                                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }

                        HapticIconButton(onClick = { PlayerManager.previous() },
                            modifier = Modifier
                            .sharedElement(
                                rememberSharedContentState(key = "player_previous"),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                            .size(42.dp)
                        ) {
                            Icon(Icons.Outlined.SkipPrevious, contentDescription = stringResource(R.string.player_previous))
                        }

                        HapticFilledIconButton(
                            onClick = { PlayerManager.togglePlayPause() },
                            modifier = Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = "play_button"),
                                    animatedVisibilityScope = this@AnimatedContent
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
                                    contentDescription = if (currentlyPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play)
                                )
                            }
                        }
                        HapticIconButton(onClick = { PlayerManager.next() },
                            modifier = Modifier
                            .sharedElement(
                                rememberSharedContentState(key = "player_next"),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                            .size(42.dp)
                        ) {
                            Icon(Icons.Outlined.SkipNext, contentDescription = stringResource(R.string.player_next))
                        }
                        HapticIconButton(onClick = { PlayerManager.cycleRepeatMode() },
                            modifier = Modifier
                                .size(42.dp)
                        ) {
                            Icon(
                                imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Outlined.Repeat,
                                contentDescription = stringResource(R.string.player_repeat),
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }

                    // 手机/竖屏，内嵌迷你歌词
                    if (!useTabletLandscapeLayout && lyrics.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))

                        AppleMusicLyric(
                            lyrics = lyrics,
                            currentTimeMs = currentPosition,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(8f),
                            textColor = MaterialTheme.colorScheme.onBackground,
                            fontSize = (18f * lyricFontScale).coerceIn(14f, 26f).sp,
                            translationFontSize = (14f * lyricFontScale).coerceIn(12f, 22f).sp,
                            visualSpec = LyricVisualSpec(),
                            lyricOffsetMs = totalOffset,
                            lyricBlurEnabled = lyricBlurEnabled,
                            lyricBlurAmount = lyricBlurAmount,
                            onLyricClick = { entry -> PlayerManager.seekTo(entry.startTimeMs) },
                            translatedLyrics = if (showLyricTranslation) translatedLyrics else null
                        )
                    }

                    // 将下面的内容推到底部
                    Spacer(modifier = Modifier.weight(1f))

                    // 底部操作栏（固定在底部）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 播放队列
                        HapticIconButton(onClick = { showQueueSheet = true },
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "btn_queue"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = EnterTransition.None,
                                    exit = ExitTransition.None,
                                ).zIndex(1f)) {
                            Icon(
                                Icons.AutoMirrored.Outlined.QueueMusic,
                                contentDescription = stringResource(R.string.playlist_queue),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 定时器按钮
                        HapticIconButton(onClick = { showSleepTimerDialog = true },
                            modifier = Modifier
                            .sharedBounds(
                                rememberSharedContentState(key = "btn_timer"),
                                animatedVisibilityScope = this@AnimatedContent,
                                enter = EnterTransition.None,
                                exit = ExitTransition.None,
                            ).zIndex(1f)) {
                            Icon(
                                Icons.Outlined.Timer,
                                contentDescription = stringResource(R.string.sleep_timer_short),
                                tint = if (sleepTimerState.isActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 音量按钮（根据设备显示不同图标，居中）
                        val audioDeviceInfo = rememberAudioDeviceInfo()
                        HapticIconButton(onClick = { showVolumeSheet = true },
                            modifier = Modifier
                            .sharedBounds(
                                rememberSharedContentState(key = "btn_volume"),
                                animatedVisibilityScope = this@AnimatedContent,
                                enter = EnterTransition.None,
                                exit = ExitTransition.None,
                            ).zIndex(1f)
                        ) {
                            Icon(
                                audioDeviceInfo.second,
                                contentDescription = audioDeviceInfo.first,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 歌词按钮
                        HapticIconButton(
                            onClick = { showLyricsScreen = !showLyricsScreen },
                            enabled = lyrics.isNotEmpty(),
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "btn_lyrics"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = EnterTransition.None,
                                    exit = ExitTransition.None,
                                ).zIndex(1f)
                        ) {
                            AnimatedContent(
                                targetState = showLyricsScreen,
                                label = "lyrics_icon"
                            ) { isShowingLyrics ->
                                Icon(
                                    imageVector = if (isShowingLyrics) Icons.Outlined.LibraryMusic else Icons.Outlined.LibraryMusic,
                                    contentDescription = stringResource(R.string.lyrics_title),
                                    tint = if (lyrics.isEmpty()) {
                                        LocalContentColor.current.copy(alpha = 0.38f)
                                    } else if (isShowingLyrics) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 添加到歌单
                        HapticIconButton(onClick = { showAddSheet = true },
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "btn_add"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = EnterTransition.None,
                                    exit = ExitTransition.None,
                                ).zIndex(1f)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.PlaylistAdd,
                                contentDescription = stringResource(R.string.playlist_add_to),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 平板横屏
                if (useTabletLandscapeLayout) {
                    Row(
                        modifier = contentModifier,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f) // 左半屏
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            content = mainColumnContent
                        )
                        if (lyrics.isNotEmpty()) {
                            AppleMusicLyric(
                                lyrics = lyrics,
                                currentTimeMs = currentPosition,
                                modifier = Modifier
                                    .weight(1f) // 右半屏
                                    .fillMaxHeight(),
                                textColor = MaterialTheme.colorScheme.onBackground,
                                fontSize = (18f * lyricFontScale).coerceIn(14f, 26f).sp,
                                translationFontSize = (14f * lyricFontScale).coerceIn(12f, 22f).sp,
                                visualSpec = LyricVisualSpec(),
                                lyricOffsetMs = totalOffset,
                                lyricBlurEnabled = lyricBlurEnabled,
                                lyricBlurAmount = lyricBlurAmount,
                                onLyricClick = { entry -> PlayerManager.seekTo(entry.startTimeMs) },
                                translatedLyrics = if (showLyricTranslation) translatedLyrics else null
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = contentModifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = mainColumnContent
                    )
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
                                Text(
                                    (index + 1).toString(),
                                    modifier = Modifier.width(48.dp),
                                    textAlign = TextAlign.Start,
                                    fontFamily = FontFamily.Monospace
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

                                    // 更多操作菜单
                                    var showMoreMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { showMoreMenu = true }) {
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
                                                text = { Text(stringResource(R.string.playlist_add_to_queue)) },
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
                                Text(stringResource(R.string.nowplaying_song_count_format, pl.songs.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // 睡眠定时器对话框
            if (showSleepTimerDialog) {
                SleepTimerDialog(
                    onDismiss = { showSleepTimerDialog = false }
                )
            }
        }
    }
}
}
}

@Composable
fun rememberAudioDeviceInfo(): Pair<String, ImageVector> {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var deviceInfo by remember { mutableStateOf(getCurrentAudioDevice(audioManager, context)) }

    DisposableEffect(Unit) {
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                deviceInfo = getCurrentAudioDevice(audioManager, context)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                deviceInfo = getCurrentAudioDevice(audioManager, context)
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    return deviceInfo
}

@Composable
fun AudioDeviceHandler() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var deviceInfo by remember { mutableStateOf(getCurrentAudioDevice(audioManager, context)) }

    DisposableEffect(Unit) {
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                deviceInfo = getCurrentAudioDevice(audioManager, context)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                deviceInfo = getCurrentAudioDevice(audioManager, context)
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = deviceInfo.second,
                contentDescription = stringResource(R.string.cd_audio_device),
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

fun getCurrentAudioDevice(audioManager: AudioManager, context: Context): Pair<String, ImageVector> {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val bluetoothDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    if (bluetoothDevice != null) {
        return try {
            Pair(bluetoothDevice.productName.toString().ifBlank { context.getString(R.string.nowplaying_bluetooth_device) }, Icons.Default.Headset)
        } catch (_: SecurityException) {
            Pair(context.getString(R.string.nowplaying_bluetooth_device), Icons.Default.Headset)
        }
    }
    val wiredHeadset =
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
    if (wiredHeadset != null) return Pair(context.getString(R.string.nowplaying_wired_headset), Icons.Default.Headset)
    return Pair(context.getString(R.string.nowplaying_phone_speaker), Icons.Default.SpeakerGroup)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsSheet(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    onDismiss: () -> Unit,
    onEnterAlbum: (NeteaseAlbum) -> Unit,
    onNavigateUp: () -> Unit,
    snackbarHostState: SnackbarHostState,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showSearchView by remember { mutableStateOf(false) }
    var showOffsetSheet by remember { mutableStateOf(false) }
    var showFontSizeSheet by remember { mutableStateOf(false) }
    var enterAlbum by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
                showFontSizeSheet -> "FontSize"
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
                            headlineContent = { Text(stringResource(R.string.music_get_info)) },
                            leadingContent = { Icon(Icons.Outlined.Info, null) },
                            modifier = Modifier.clickable { showSearchView = true }
                        )
                        if (AudioDownloadManager.getLocalFilePath(context, originalSong) == null) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.download_to_local)) },
                                leadingContent = { Icon(Icons.Outlined.Download, null) },
                                modifier = Modifier.clickable {
                                    viewModel.downloadSong(context, originalSong)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.download_starting, originalSong.name))
                                    }
                                    onDismiss()
                                }
                            )
                        }

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.lyrics_adjust_offset)) },
                            leadingContent = { Icon(Icons.Outlined.Timer, null) },
                            modifier = Modifier.clickable { showOffsetSheet = true }
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.lyrics_font_size)) },
                            leadingContent = { Icon(Icons.Outlined.FormatSize, null) },
                            supportingContent = {
                                Text("${(lyricFontScale * 100).roundToInt()}%")
                            },
                            modifier = Modifier.clickable { showFontSizeSheet = true }
                        )
                        if (originalSong.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG)) {
                            val albumName = originalSong.album.replace(PlayerManager.NETEASE_SOURCE_TAG, "")
                            val album = NeteaseAlbum(id = originalSong.albumId.toLong(), name = albumName, size = 0, picUrl = originalSong?.coverUrl ?:"")
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.music_view_album, albumName)) },
                                leadingContent = { Icon(Icons.Outlined.LibraryMusic, null) },
                                modifier = Modifier.clickable {
                                    onEnterAlbum(album)
                                    onDismiss()
                                    onNavigateUp()
                                }
                            )
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.action_share)) },
                            leadingContent = { Icon(Icons.Outlined.Share, null) },
                            modifier = Modifier.clickable {
                                val song = originalSong
                                val isFromBili = song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)

                                val url = if (isFromBili) {
                                    // 筛选出队列中属于同一个B站视频的所有分P
                                    val videoParts = queue.filter {
                                        it.id == song.id && it.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
                                    }
                                    if (videoParts.size > 1) {
                                        val pageIndex = videoParts.indexOfFirst {
                                            it.album == song.album
                                        }
                                        val pageNumber = pageIndex + 1
                                        if (pageIndex != -1) {
                                            "https://www.bilibili.com/video/av${song.id}/?p=${pageNumber}"
                                        } else {
                                            "https://www.bilibili.com/video/av${song.id}"
                                        }
                                    } else {
                                        "https://www.bilibili.com/video/av${song.id}"
                                    }
                                } else {
                                    "https://music.163.com/#/song?id=${song.id}"
                                }

                                val shareText = context.getString(R.string.nowplaying_share_song, song.name, song.artist, url)

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
                            label = { Text(stringResource(R.string.search_keywords)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            trailingIcon = {
                                HapticIconButton(onClick = { viewModel.performSearch() }) {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search))
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                viewModel.performSearch()
                                focusManager.clearFocus()
                            }),
                        )

                        // 平台切换
                        androidx.compose.material3.PrimaryTabRow(
                            selectedTabIndex = searchState.selectedPlatform.ordinal,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
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
                                                val context = LocalContext.current
                                                AsyncImage(
                                                    model = offlineCachedImageRequest(
                                                        context,
                                                        songResult.coverUrl?.replaceFirst(
                                                            "http://",
                                                            "https://"
                                                        )
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
                                    text = searchState.error ?: stringResource(R.string.nowplaying_no_search_result),
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

                "FontSize" -> {
                    LyricFontSizeSheet(
                        currentScale = lyricFontScale,
                        onScaleCommit = onLyricFontScaleChange,
                        onDismiss = { showFontSizeSheet = false }
                    )
                }
            }

            // Snackbar
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalMiniPlayerHeight.current)
            )
        }
    }
}

@Composable
fun VolumeControlSheetContent() {
    val context = LocalContext.current
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    // 获取当前音频设备信息
    val audioDeviceInfo = rememberAudioDeviceInfo()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(audioDeviceInfo.first, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(imageVector = audioDeviceInfo.second, contentDescription = audioDeviceInfo.first)
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
fun LyricOffsetSheet(song: SongItem, onDismiss: () -> Unit) {
    var currentOffset by remember { mutableLongStateOf(song.userLyricOffsetMs) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.lyrics_adjust_offset), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${if (currentOffset > 0) "+" else ""}${currentOffset} ms",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            color = when {
                currentOffset > 0 -> Color(0xFF388E3C) // 快了 绿色
                currentOffset < 0 -> MaterialTheme.colorScheme.error // 慢了 红色
                else -> LocalContentColor.current
            }
        )
        Text(stringResource(R.string.lyrics_offset_hint), style = MaterialTheme.typography.bodySmall)

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
            Text(stringResource(R.string.action_done))
        }
    }
}

@Composable
fun LyricFontSizeSheet(
    currentScale: Float,
    onScaleCommit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentScale) }

    LaunchedEffect(currentScale) {
        sliderValue = currentScale
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.lyrics_font_size), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${(sliderValue * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace)
        )
        Text(
            text = stringResource(R.string.nowplaying_font_size_hint),
            style = MaterialTheme.typography.bodySmall
        )

        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onScaleCommit(sliderValue) },
            valueRange = 0.5f..1.6f,
            steps = 10
        )

        Text(
            text = stringResource(R.string.nowplaying_lyrics_sample),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
            fontSize = (18f * sliderValue).coerceIn(12f, 28f).sp
        )

        Spacer(Modifier.height(16.dp))
        HapticTextButton(onClick = {
            onScaleCommit(sliderValue)
            onDismiss()
        }) {
            Text(stringResource(R.string.action_done))
        }
    }
}
