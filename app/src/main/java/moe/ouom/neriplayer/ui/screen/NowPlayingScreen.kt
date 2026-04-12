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
 * Updated: 2026/3/23
 */

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.isDownloadTaskCancellable
import moe.ouom.neriplayer.core.download.isDownloadTaskFinalizing
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.shouldHideRemoteDownloadAction
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.extractPreferredNeteaseLyricContent
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.normalizeLyricFontScale
import moe.ouom.neriplayer.data.settings.scaledLyricFontSize
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.AppleMusicLyric
import moe.ouom.neriplayer.ui.component.flattenWordTimedEntries
import moe.ouom.neriplayer.ui.component.hasWordTimedEntries
import moe.ouom.neriplayer.ui.component.isNeteaseYrc
import moe.ouom.neriplayer.ui.component.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.component.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.PlaybackSoundSheet
import moe.ouom.neriplayer.ui.component.PlaybackSourceBadge
import moe.ouom.neriplayer.ui.component.PlaybackSourceType
import moe.ouom.neriplayer.ui.component.SleepTimerDialog
import moe.ouom.neriplayer.ui.component.WaveformSlider
import moe.ouom.neriplayer.ui.component.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.component.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.component.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.component.resolveLyricsEditorInitialText
import moe.ouom.neriplayer.ui.component.resolvePreferredLyricContent
import moe.ouom.neriplayer.ui.component.toEditableLyricsText
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherRoomPanel
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.util.HapticFilledIconButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.saveCoverToPictures
import kotlin.math.roundToInt

private const val LyricsPageTransitionDurationMs = 300
private const val CoverSourceBadgeRevealBufferMs = 120
private const val CoverSourceBadgeRevealDelayMs =
    LyricsPageTransitionDurationMs + CoverSourceBadgeRevealBufferMs

internal fun shouldHideDownloadActionForSong(
    hasLocalDownload: Boolean,
    currentTask: moe.ouom.neriplayer.core.download.DownloadTask?
): Boolean = shouldHideRemoteDownloadAction(hasLocalDownload, currentTask)

private fun hasCachedLocalDownload(song: SongItem): Boolean {
    return GlobalDownloadManager.hasDownloadedSongCached(song) ||
        ManagedDownloadStorage.peekDownloadedAudio(song) != null
}

private fun buildRemoteSongShareUrl(originalSong: SongItem, queue: List<SongItem>): String {
    extractYouTubeMusicVideoId(originalSong.mediaUri)?.let { videoId ->
        return "https://music.youtube.com/watch?v=$videoId"
    }

    if (originalSong.album.startsWith(PlayerManager.BILI_SOURCE_TAG)) {
        val videoParts = queue.filter {
            it.id == originalSong.id && it.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
        }
        if (videoParts.size > 1) {
            val pageIndex = videoParts.indexOfFirst { it.album == originalSong.album }
            val pageNumber = pageIndex + 1
            if (pageIndex != -1) {
                return "https://www.bilibili.com/video/av${originalSong.id}/?p=${pageNumber}"
            }
        }
        return "https://www.bilibili.com/video/av${originalSong.id}"
    }

    val mediaUri = originalSong.mediaUri
    return when {
        originalSong.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG) ->
            "https://music.163.com/#/song?id=${originalSong.id}"
        !mediaUri.isNullOrBlank() &&
            (mediaUri.startsWith("https://") || mediaUri.startsWith("http://")) -> mediaUri
        else -> "https://music.163.com/#/song?id=${originalSong.id}"
    }
}

private fun resolvePreferredNeteaseLyricSongId(song: SongItem?): Long? {
    if (song == null) {
        return null
    }
    val matchedSongId = song.matchedSongId?.toLongOrNull()
    if (matchedSongId != null && matchedSongId > 0) {
        return matchedSongId
    }
    val isDirectNeteaseSong = song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC ||
        song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG) ||
        song.mediaUri?.contains("music.163.com") == true
    return if (isDirectNeteaseSong) song.id.takeIf { it > 0L } else null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun NowPlayingScreen(
    onNavigateUp: () -> Unit,
    onEnterAlbum: (AlbumSummary) -> Unit,
    lyricBlurEnabled: Boolean,
    lyricBlurAmount: Float,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    advancedLyricsEnabled: Boolean = true,
    showCoverSourceBadge: Boolean = true,
    showLyricTranslation: Boolean = true,
    showNowPlayingTitle: Boolean = true,
) {
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val isPlaybackControlPlaying by PlayerManager.playbackControlPlayingFlow.collectAsState()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsState()
    val durationMs = currentSong?.durationMs ?: 0L
    val sleepTimerState by PlayerManager.sleepTimerManager.timerState.collectAsState()
    val currentPlaybackAudioInfo by PlayerManager.currentPlaybackAudioInfoFlow.collectAsState()
    val settingsRepo = remember { AppContainer.settingsRepo }
    val showProgressQualitySwitch by settingsRepo
        .nowPlayingProgressShowQualitySwitchFlow
        .collectAsState(initial = true)
    val nowPlayingToolbarDockEnabled by settingsRepo
        .nowPlayingToolbarDockEnabledFlow
        .collectAsState(initial = true)
    val showProgressAudioCodec by settingsRepo
        .nowPlayingProgressShowAudioCodecFlow
        .collectAsState(initial = true)
    val showProgressAudioSpec by settingsRepo
        .nowPlayingProgressShowAudioSpecFlow
        .collectAsState(initial = true)

    // 订阅当前播放链接
    val currentMediaUrl by PlayerManager.currentMediaUrlFlow.collectAsState()
    val isFromNeteaseTag =
        currentSong?.album?.startsWith(PlayerManager.NETEASE_SOURCE_TAG) == true
    val isFromBiliTag =
        currentSong?.album?.startsWith(PlayerManager.BILI_SOURCE_TAG) == true
    val isFromNeteaseUrl = currentMediaUrl?.contains("music.126.net", ignoreCase = true) == true
    val isFromBiliUrl = currentMediaUrl?.contains("bilivideo.", ignoreCase = true) == true
    val isFromNetease = isFromNeteaseTag || (!isFromBiliTag && isFromNeteaseUrl)
    val isFromBili = isFromBiliTag || (!isFromNeteaseTag && isFromBiliUrl)
    val rawPlaybackSourceType = when {
        currentSong?.isLocalSong() == true -> PlaybackSourceType.LOCAL
        currentSong?.let { isYouTubeMusicSong(it) } == true -> PlaybackSourceType.YOUTUBE_MUSIC
        isFromNetease -> PlaybackSourceType.NETEASE
        isFromBili -> PlaybackSourceType.BILIBILI
        else -> null
    }
    val playbackSourceSongKey = currentSong?.let {
        listOf(it.id.toString(), it.album, it.mediaUri.orEmpty(), it.localFilePath.orEmpty())
            .joinToString(separator = "|")
    }
    var playbackSourceType by remember { mutableStateOf<PlaybackSourceType?>(null) }

    val playlists by PlayerManager.playlistsFlow.collectAsState()
    val context = LocalContext.current
    val currentCoverUrl = remember(currentSong, context) {
        currentSong?.displayCoverUrl(context)
    }

    // 点击即切换，回流后撤销覆盖
    var favOverride by remember(currentSong) { mutableStateOf<Boolean?>(null) }
    val isFavoriteComputed = remember(currentSong, playlists) {
        val song = currentSong ?: return@remember false
        playlists
            .firstOrNull { FavoritesPlaylist.isSystemPlaylist(it, context) }
            ?.songs
            ?.any { it.sameIdentityAs(song) } == true
    }
    val isFavorite = favOverride ?: isFavoriteComputed

    val queue by PlayerManager.currentQueueFlow.collectAsState()
    val displayedQueue = remember(queue) { queue }
    val currentIndexInDisplay = remember(displayedQueue, currentSong) {
        displayedQueue.indexOfFirst {
            it.sameIdentityAs(currentSong)
        }
    }

    var showAddSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyricsScreen by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showCoverPageSourceBadge by remember { mutableStateOf(false) }
    var animateCoverPageSourceBadge by remember { mutableStateOf(false) }
    var previousLyricsScreenState by remember { mutableStateOf(false) }
    var showCoverMenu by remember { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }
    var showSongNameMenu by remember { mutableStateOf(false) }
    var showArtistMenu by remember { mutableStateOf(false) }
    var showQualitySwitchDialog by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState()
    val queueSheetState = rememberModalBottomSheetState()

    // Snackbar状态
    val snackbarHostState = remember { SnackbarHostState() }
    var detailSong by remember { mutableStateOf<SongItem?>(null) }
    var pendingSyncConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingSyncConfirmLabel by remember { mutableStateOf("") }

    val clipboard = LocalClipboard.current
    val screenScope = rememberCoroutineScope()

    val downloadCurrentCover: () -> Unit = {
        val song = currentSong
        if (song == null || currentCoverUrl.isNullOrBlank()) {
            screenScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.cover_download_unavailable)
                )
            }
        } else {
            screenScope.launch {
                saveCoverToPictures(
                    context = context,
                    imageUrl = currentCoverUrl,
                    suggestedName = "${song.displayArtist()} - ${song.displayName()} 封面"
                ).onSuccess { fileName ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.cover_download_success, fileName)
                    )
                }.onFailure { error ->
                    val errorMessage = error.message ?: context.getString(R.string.download_failed)
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.cover_download_failed, errorMessage)
                    )
                }
            }
        }
    }

    val coverPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            downloadCurrentCover()
        } else {
            screenScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.cover_download_permission_required)
                )
            }
        }
    }

    val requestCoverDownload: () -> Unit = {
        showCoverMenu = false
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                downloadCurrentCover()
            } else {
                coverPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            downloadCurrentCover()
        }
    }

    // 内容的进入动画
    var contentVisible by remember { mutableStateOf(false) }

    // 控制音量弹窗的显示
    var showVolumeSheet by remember { mutableStateOf(false) }
    val volumeSheetState = rememberModalBottomSheetState()

    var lyrics by remember(currentSong?.id) { mutableStateOf<List<LyricEntry>>(emptyList()) }
    var translatedLyrics by remember(currentSong?.id) { mutableStateOf<List<LyricEntry>>(emptyList()) }
    val nowPlayingViewModel: NowPlayingViewModel = viewModel()

    LaunchedEffect(
        currentSong?.id,
        currentSong?.matchedLyric,
        currentSong?.matchedTranslatedLyric,
        isFromNetease,
        currentMediaUrl
    ) {
        val song = currentSong
        val (loadedLyrics, loadedTranslatedLyrics) = withContext(Dispatchers.IO) {
            val preferredNeteaseLyric = runCatching {
                val shouldUpgradeMatchedLyric = song?.matchedLyric?.let(::isNeteaseYrc) != true
                val preferredSongId = resolvePreferredNeteaseLyricSongId(song)
                if (shouldUpgradeMatchedLyric && preferredSongId != null) {
                    extractPreferredNeteaseLyricContent(
                        AppContainer.neteaseClient.getLyricNew(preferredSongId)
                    )
                } else {
                    ""
                }
            }.getOrNull().orEmpty()
            val effectiveRawLyrics = resolvePreferredLyricContent(
                matchedLyric = song?.matchedLyric,
                preferredNeteaseLyric = preferredNeteaseLyric
            )
            val shouldDelayOnlineLyrics =
                song != null &&
                    extractYouTubeMusicVideoId(song.mediaUri) != null &&
                    currentMediaUrl.isNullOrBlank()
            val resolvedLyrics = when {
                !effectiveRawLyrics.isNullOrBlank() -> {
                    parseNeteaseLyricsAuto(effectiveRawLyrics)
                }
                shouldDelayOnlineLyrics -> {
                    // 当前曲目还在抢首播地址，先别让歌词请求去争 EJS 和鉴权链路
                    emptyList()
                }
                song != null -> {
                    // 在线拉取歌词
                    PlayerManager.getLyrics(song)
                }
                else -> {
                    emptyList()
                }
            }

            val resolvedTranslatedLyrics = try {
                when {
                    // 优先使用存储的翻译歌词
                    !song?.matchedTranslatedLyric.isNullOrBlank() -> {
                        parseNeteaseLyricsAuto(song!!.matchedTranslatedLyric!!)
                    }
                    song != null -> {
                        PlayerManager.getTranslatedLyrics(song)
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
            resolvedLyrics to resolvedTranslatedLyrics
        }
        lyrics = loadedLyrics
        translatedLyrics = loadedTranslatedLyrics
    }
    val plainLyrics = remember(lyrics) { lyrics.flattenWordTimedEntries() }
    val plainTranslatedLyrics = remember(translatedLyrics) { translatedLyrics.flattenWordTimedEntries() }
    var previewPositionOverrideMs by remember(currentSong?.id) { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { contentVisible = true }
    LaunchedEffect(currentSong?.id) { showQualitySwitchDialog = false }
    LaunchedEffect(showLyricsScreen, showCoverSourceBadge) {
        val returningFromLyrics = previousLyricsScreenState && !showLyricsScreen
        previousLyricsScreenState = showLyricsScreen
        if (!showCoverSourceBadge) {
            showCoverPageSourceBadge = false
            animateCoverPageSourceBadge = false
            return@LaunchedEffect
        }
        if (showLyricsScreen) {
            showCoverPageSourceBadge = false
            animateCoverPageSourceBadge = false
        } else {
            animateCoverPageSourceBadge = returningFromLyrics
            if (returningFromLyrics) {
                delay(CoverSourceBadgeRevealDelayMs.toLong())
            }
            showCoverPageSourceBadge = true
        }
    }
    LaunchedEffect(playbackSourceSongKey, rawPlaybackSourceType, showCoverSourceBadge) {
        when {
            !showCoverSourceBadge -> playbackSourceType = null
            rawPlaybackSourceType != null -> playbackSourceType = rawPlaybackSourceType
            playbackSourceSongKey == null -> playbackSourceType = null
            else -> {
                delay(250)
                playbackSourceType = null
            }
        }
    }

    // 当仓库回流或歌曲切换时，撤销本地乐观覆盖，用真实状态对齐
    LaunchedEffect(playlists, currentSong?.id) { favOverride = null }

    fun launchWithLocalSyncWarning(
        song: SongItem?,
        actionLabel: String,
        warnForLocalSync: Boolean = true,
        action: () -> Unit
    ) {
        if (warnForLocalSync && song?.isLocalSong() == true) {
            pendingSyncConfirmLabel = actionLabel
            pendingSyncConfirmAction = action
        } else {
            action()
        }
    }

    // 自适应布局判断
    val configuration = LocalConfiguration.current
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val windowWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val isWideLayout = windowWidthDp >= 480.dp
    val useWideLandscapeLayout = isWideLayout && isLandscape
    val isCompactTabletLandscape = useWideLandscapeLayout && windowWidthDp < 720.dp
    val secondaryControlButtonSize = when {
        useWideLandscapeLayout && isCompactTabletLandscape -> 42.dp
        useWideLandscapeLayout -> 46.dp
        else -> 42.dp
    }
    val primaryControlButtonSize = when {
        useWideLandscapeLayout && isCompactTabletLandscape -> 46.dp
        useWideLandscapeLayout -> 50.dp
        else -> 42.dp
    }
    val controlButtonSpacing = when {
        useWideLandscapeLayout && isCompactTabletLandscape -> 18.dp
        useWideLandscapeLayout -> 22.dp
        else -> 20.dp
    }

    // 歌词偏移（平台 + 用户自定义）
    val platformOffset = if (currentSong?.matchedLyricSource == MusicPlatform.QQ_MUSIC) 500L else 1000L
    val userOffset = currentSong?.userLyricOffsetMs ?: 0L
    val totalOffset = platformOffset + userOffset
    val progressInfoSegments = remember(
        currentPlaybackAudioInfo,
        showProgressQualitySwitch,
        showProgressAudioCodec,
        showProgressAudioSpec
    ) {
        buildNowPlayingProgressInfoSegments(
            audioInfo = currentPlaybackAudioInfo,
            showQualitySwitch = showProgressQualitySwitch,
            showAudioCodec = showProgressAudioCodec,
            showAudioSpec = showProgressAudioSpec
        )
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        SharedTransitionLayout {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = showLyricsScreen,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = LyricsPageTransitionDurationMs,
                                easing = LinearEasing
                            )
                        ) togetherWith fadeOut(
                            animationSpec = tween(
                                durationMillis = LyricsPageTransitionDurationMs,
                                easing = LinearEasing
                            )
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
                            advancedLyricsEnabled = advancedLyricsEnabled,
                            translatedLyrics = translatedLyrics,
                            lyricOffsetMs = totalOffset,
                            showLyricTranslation = showLyricTranslation,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@AnimatedContent
                        )
                    } else {
                // 播放页面
                val horizontalPadding = if (isLandscape) 16.dp else 20.dp
                val verticalPadding = if (isLandscape) 8.dp else 12.dp
                var contentModifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 60) onNavigateUp() }
                    }

                // 手机或竖屏下，左滑进入歌词页
                if (!useWideLandscapeLayout && lyrics.isNotEmpty()) {
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
                        if (showNowPlayingTitle) {
                            Text(
                                text = stringResource(R.string.player_now_playing),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }

                        // 收藏和更多按钮 - 右侧
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            HapticIconButton(
                                onClick = {
                                    if (currentSong == null) return@HapticIconButton
                                    val willFav = !isFavorite
                                    launchWithLocalSyncWarning(
                                        song = currentSong,
                                        actionLabel = context.getString(R.string.favorite_add),
                                        warnForLocalSync = willFav
                                    ) {
                                        favOverride = willFav
                                        if (willFav) {
                                            PlayerManager.addCurrentToFavorites()
                                        } else {
                                            PlayerManager.removeCurrentFromFavorites()
                                        }
                                    }
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
                                    tint = if (isFavorite) {
                                        Color.Red.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

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
                                    displayedLyrics = lyrics,
                                    displayedTranslatedLyrics = translatedLyrics,
                                    onDismiss = { showMoreOptions = false },
                                    onShowSongDetails = { detailSong = it },
                                    onEnterAlbum = onEnterAlbum,
                                    onNavigateUp = onNavigateUp,
                                    snackbarHostState = snackbarHostState,
                                    lyricFontScale = lyricFontScale,
                                    onLyricFontScaleChange = onLyricFontScaleChange,
                                    currentPlaybackAudioInfo = currentPlaybackAudioInfo,
                                    onShowQualitySwitch = { showQualitySwitchDialog = true }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 封面
                    BoxWithConstraints(
                        modifier = if (useWideLandscapeLayout) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.align(Alignment.CenterHorizontally)
                        }
                    ) {
                        val coverSize = when {
                            useWideLandscapeLayout -> minOf(
                                windowWidthDp * 0.40f,
                                maxWidth * 0.82f,
                                maxHeight * 0.42f
                            )
                            isLandscape -> minOf(windowWidthDp * 0.45f, maxHeight * 0.5f, maxWidth)
                            else -> minOf(maxWidth * 0.6f, maxHeight * 0.65f)
                        }
                        val coverRequestSizePx = with(LocalDensity.current) {
                            coverSize.roundToPx().coerceAtLeast(256)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(coverSize)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .sharedElement(
                                        rememberSharedContentState(key = "cover_image"),
                                        animatedVisibilityScope = this@AnimatedContent
                                    )
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        color = if (currentCoverUrl != null) {
                                            Color.Transparent
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        }
                                    )
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            if (currentCoverUrl.isNullOrBlank()) {
                                                screenScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(
                                                            R.string.cover_download_unavailable
                                                        )
                                                    )
                                                }
                                            } else {
                                                showCoverMenu = true
                                            }
                                        }
                                    )
                            ) {
                                currentCoverUrl?.let { cover ->
                                    AsyncImage(
                                        model = remember(context, cover, coverRequestSizePx) {
                                            offlineCachedImageRequest(
                                                context = context,
                                                data = cover,
                                                sizePx = coverRequestSizePx,
                                                allowHardware = false
                                            )
                                        },
                                        contentDescription = currentSong?.customName ?: currentSong?.name ?: "",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showCoverMenu,
                                onDismissRequest = { showCoverMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_download_cover)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Download,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = requestCoverDownload
                                )
                            }

                            val coverPageSourceBadgeScale by animateFloatAsState(
                                targetValue = if (showCoverPageSourceBadge && playbackSourceType != null) {
                                    1f
                                } else {
                                    0f
                                },
                                animationSpec = if (animateCoverPageSourceBadge) {
                                    tween(
                                        durationMillis = 520,
                                        easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                                    )
                                } else {
                                    snap()
                                },
                                label = "cover_source_badge_scale"
                            )

                            if (showCoverPageSourceBadge && playbackSourceType != null) {
                                playbackSourceType?.let { sourceType ->
                                    PlaybackSourceBadge(
                                        source = sourceType,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(10.dp)
                                            .graphicsLayer {
                                                scaleX = coverPageSourceBadgeScale
                                                scaleY = coverPageSourceBadgeScale
                                                alpha = coverPageSourceBadgeScale
                                            }
                                    )
                                }
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
                                    text = currentSong?.customName ?: currentSong?.name ?: "",
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
                                            val displayName = currentSong?.customName ?: currentSong?.name
                                            displayName?.let { text ->
                                                screenScope.launch {
                                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
                                                }
                                            }
                                            showSongNameMenu = false
                                        }
                                    )
                                }
                            }
                            Box {
                                Text(
                                    text = currentSong?.customArtist ?: currentSong?.artist ?: "",
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
                                            val displayArtist = currentSong?.customArtist ?: currentSong?.artist
                                            displayArtist?.let { text ->
                                                screenScope.launch {
                                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
                                                }
                                            }
                                            showArtistMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    NowPlayingProgressSection(
                        songKey = currentSong?.stableKey(),
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        progressInfoSegments = progressInfoSegments,
                        useWideLandscapeLayout = useWideLandscapeLayout,
                        onPreviewPositionChange = { previewPositionOverrideMs = it },
                        modifier = Modifier
                            .fillMaxWidth(if (useWideLandscapeLayout) 0.88f else 1f)
                            .sharedBounds(
                                rememberSharedContentState(key = "progress_bar"),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                    )

                    Spacer(Modifier.height(if (useWideLandscapeLayout) 14.dp else 10.dp))

                    // 控制按钮
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(controlButtonSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        HapticIconButton(onClick = { PlayerManager.setShuffle(!shuffleEnabled) },
                            modifier = Modifier
                                .size(secondaryControlButtonSize)
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
                            .size(secondaryControlButtonSize)
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
                                .size(primaryControlButtonSize)
                        ) {
                            AnimatedContent(
                                targetState = isPlaybackControlPlaying,
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
                            .size(secondaryControlButtonSize)
                        ) {
                            Icon(Icons.Outlined.SkipNext, contentDescription = stringResource(R.string.player_next))
                        }
                        HapticIconButton(onClick = { PlayerManager.cycleRepeatMode() },
                            modifier = Modifier
                                .size(secondaryControlButtonSize)
                        ) {
                            Icon(
                                imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Outlined.Repeat,
                                contentDescription = stringResource(R.string.player_repeat),
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }

                    // 手机/竖屏，内嵌迷你歌词
                    if (!useWideLandscapeLayout && lyrics.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))

                        NowPlayingLyricsPane(
                            lyrics = plainLyrics,
                            previewPositionOverrideMs = previewPositionOverrideMs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(8f),
                            textColor = MaterialTheme.colorScheme.onBackground,
                            fontSize = scaledLyricFontSize(18f, lyricFontScale).sp,
                            translationFontSize = scaledLyricFontSize(14f, lyricFontScale).sp,
                            visualSpec = LyricVisualSpec(),
                            lyricOffsetMs = totalOffset,
                            lyricBlurEnabled = lyricBlurEnabled,
                            lyricBlurAmount = lyricBlurAmount,
                            onLyricClick = { entry -> PlayerManager.seekTo(entry.startTimeMs) },
                            translatedLyrics = if (showLyricTranslation) plainTranslatedLyrics else null
                        )
                    }

                    if (useWideLandscapeLayout) {
                        Spacer(Modifier.height(24.dp))
                    } else {
                        // 将下面的内容推到底部
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // 底部操作栏（固定在底部）
                    Column(
                        modifier = if (useWideLandscapeLayout) {
                            Modifier
                                .fillMaxWidth(0.9f)
                                .windowInsetsPadding(WindowInsets.navigationBars)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .padding(bottom = if (nowPlayingToolbarDockEnabled) 2.dp else 0.dp)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val toolbarContainerModifier = Modifier.fillMaxWidth()
                        val toolbarContent: @Composable () -> Unit = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = if (nowPlayingToolbarDockEnabled || useWideLandscapeLayout) {
                                            18.dp
                                        } else {
                                            6.dp
                                        },
                                        vertical = if (nowPlayingToolbarDockEnabled || useWideLandscapeLayout) {
                                            12.dp
                                        } else {
                                            8.dp
                                        }
                                    ),
                                horizontalArrangement = if (useWideLandscapeLayout || nowPlayingToolbarDockEnabled) {
                                    Arrangement.SpaceEvenly
                                } else {
                                    Arrangement.SpaceBetween
                                },
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
                                        modifier = Modifier.size(if (useWideLandscapeLayout) 22.dp else 20.dp)
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
                                        modifier = Modifier.size(if (useWideLandscapeLayout) 22.dp else 20.dp)
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
                                        modifier = Modifier.size(if (useWideLandscapeLayout) 22.dp else 20.dp)
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
                                            modifier = Modifier.size(if (useWideLandscapeLayout) 22.dp else 20.dp)
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
                                        modifier = Modifier.size(if (useWideLandscapeLayout) 22.dp else 20.dp)
                                    )
                                }
                            }
                        }

                        if (nowPlayingToolbarDockEnabled) {
                            Surface(
                                modifier = toolbarContainerModifier,
                                shape = RoundedCornerShape(30.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.40f),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f)
                                )
                            ) {
                                toolbarContent()
                            }
                        } else {
                            toolbarContent()
                        }
                    }
                }

                // 平板横屏
                if (useWideLandscapeLayout) {
                    Row(
                        modifier = contentModifier,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            content = mainColumnContent
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            if (lyrics.isNotEmpty()) {
                                NowPlayingLyricsPane(
                                    lyrics = plainLyrics,
                                    previewPositionOverrideMs = previewPositionOverrideMs,
                                    modifier = Modifier.fillMaxSize(),
                                    textColor = MaterialTheme.colorScheme.onBackground,
                                    fontSize = scaledLyricFontSize(18f, lyricFontScale).sp,
                                    translationFontSize = scaledLyricFontSize(14f, lyricFontScale).sp,
                                    visualSpec = LyricVisualSpec(),
                                    lyricOffsetMs = totalOffset,
                                    lyricBlurEnabled = lyricBlurEnabled,
                                    lyricBlurAmount = lyricBlurAmount,
                                    onLyricClick = { entry -> PlayerManager.seekTo(entry.startTimeMs) },
                                    translatedLyrics = if (showLyricTranslation) plainTranslatedLyrics else null
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.LibraryMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.lyrics_no_lyrics),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
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
                    sheetState = volumeSheetState,
                    sheetGesturesEnabled = false
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
                    sheetState = queueSheetState,
                    sheetGesturesEnabled = false
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.bottomSheetScrollGuard()
                    ) {
                        itemsIndexed(
                            items = displayedQueue,
                            key = { _, song -> song.stableKey() },
                            contentType = { _, _ -> "queue_song" }
                        ) { index, song ->
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
                                    (index + 1).toString(),
                                    modifier = Modifier.width(48.dp),
                                    textAlign = TextAlign.Start,
                                    fontFamily = FontFamily.Monospace
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(song.displayName(), maxLines = 1)
                                    Text(
                                        song.displayArtist(),
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

            if (showQualitySwitchDialog && currentPlaybackAudioInfo != null) {
                NowPlayingQualityOptionsDialog(
                    title = stringResource(R.string.nowplaying_quality_switch_title),
                    selectedKey = currentPlaybackAudioInfo?.qualityKey,
                    options = currentPlaybackAudioInfo?.qualityOptions.orEmpty(),
                    onDismiss = { showQualitySwitchDialog = false },
                    onSelect = { option ->
                        PlayerManager.changeCurrentPlaybackQuality(option.key)
                        showQualitySwitchDialog = false
                    }
                )
            }

            if (showAddSheet) {
                val selectablePlaylists = remember(playlists, context) {
                    playlists.filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) }
                }
                ModalBottomSheet(
                    onDismissRequest = { showAddSheet = false },
                    sheetState = addSheetState,
                    sheetGesturesEnabled = false
                ) {
                    LazyColumn(modifier = Modifier.bottomSheetScrollGuard()) {
                        itemsIndexed(
                            items = selectablePlaylists,
                            key = { _, pl -> pl.id },
                            contentType = { _, _ -> "playlist_option" }
                        ) { _, pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        launchWithLocalSyncWarning(
                                            song = currentSong,
                                            actionLabel = context.getString(R.string.playlist_add_to)
                                        ) {
                                            PlayerManager.addCurrentToPlaylist(pl.id)
                                            showAddSheet = false
                                        }
                                    }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    pluralStringResource(
                                        R.plurals.nowplaying_song_count_format,
                                        pl.songs.size,
                                        pl.songs.size
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

            detailSong?.let { song ->
                LocalSongDetailsDialog(
                    song = song,
                    onDismiss = { detailSong = null }
                )
            }

            pendingSyncConfirmAction?.let { action ->
                LocalSongSyncConfirmDialog(
                    actionLabel = pendingSyncConfirmLabel,
                    onConfirm = {
                        pendingSyncConfirmAction = null
                        pendingSyncConfirmLabel = ""
                        action()
                    },
                    onDismiss = {
                        pendingSyncConfirmAction = null
                        pendingSyncConfirmLabel = ""
                    }
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
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    onDismiss: () -> Unit,
    onShowSongDetails: (SongItem) -> Unit = {},
    onEnterAlbum: (AlbumSummary) -> Unit,
    onNavigateUp: () -> Unit,
    snackbarHostState: SnackbarHostState,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    currentPlaybackAudioInfo: PlaybackAudioInfo? = null,
    onShowQualitySwitch: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSearchView by remember { mutableStateOf(false) }
    var showOffsetSheet by remember { mutableStateOf(false) }
    var showFontSizeSheet by remember { mutableStateOf(false) }
    var showEditInfoSheet by remember { mutableStateOf(false) }
    var showListenTogetherSheet by remember { mutableStateOf(false) }
    var showPlaybackSoundSheet by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isLocalSong = originalSong.isLocalSong()
    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsState(initial = false)
    val qualityOptions = currentPlaybackAudioInfo?.qualityOptions.orEmpty()
    val canSwitchQuality = qualityOptions.size > 1
    val currentQualityLabel = currentPlaybackAudioInfo?.qualityLabel
    val playbackSoundState by PlayerManager.playbackSoundStateFlow.collectAsState()
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()
    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
    val hasLocalDownload = remember(downloadPresenceVersion, originalSong) {
        hasCachedLocalDownload(originalSong)
    }
    val downloadSongKey = remember(originalSong) { originalSong.stableKey() }
    val currentDownloadTask = remember(downloadTasks, downloadSongKey) {
        downloadTasks.firstOrNull { it.song.stableKey() == downloadSongKey }
    }
    val shouldHideDownloadAction = remember(hasLocalDownload, currentDownloadTask) {
        shouldHideDownloadActionForSong(hasLocalDownload, currentDownloadTask)
    }
    val currentDownloadFinalizing = remember(currentDownloadTask) {
        isDownloadTaskFinalizing(currentDownloadTask)
    }
    val currentDownloadCancellable = remember(currentDownloadTask) {
        isDownloadTaskCancellable(currentDownloadTask)
    }

    LaunchedEffect(showSearchView) {
        if (showSearchView) {
            viewModel.prepareForSearch(originalSong.name)
            viewModel.performSearch()
            if (autoShowKeyboard) {
                delay(120)
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                sheetState.hide()
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 处理子页面的返回键导航
        BackHandler(
            enabled = showOffsetSheet ||
                showFontSizeSheet ||
                showSearchView ||
                showEditInfoSheet ||
                showListenTogetherSheet ||
                showPlaybackSoundSheet
        ) {
            when {
                showOffsetSheet -> showOffsetSheet = false
                showFontSizeSheet -> showFontSizeSheet = false
                showSearchView -> showSearchView = false
                showEditInfoSheet -> showEditInfoSheet = false
                showListenTogetherSheet -> showListenTogetherSheet = false
                showPlaybackSoundSheet -> showPlaybackSoundSheet = false
            }
        }

        // 处理主页面的返回键
        BackHandler(
            enabled = !showOffsetSheet &&
                !showFontSizeSheet &&
                !showSearchView &&
                !showEditInfoSheet &&
                !showListenTogetherSheet &&
                !showPlaybackSoundSheet
        ) {
            coroutineScope.launch {
                sheetState.hide()
                onDismiss()
            }
        }

        AnimatedContent(
            targetState = when {
                showOffsetSheet -> "Offset"
                showFontSizeSheet -> "FontSize"
                showSearchView -> "Search"
                showEditInfoSheet -> "EditInfo"
                showListenTogetherSheet -> "ListenTogether"
                showPlaybackSoundSheet -> "PlaybackSound"
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
                    val mainScrollState = rememberScrollState()
                    Column(
                        Modifier
                            .bottomSheetScrollGuard { mainScrollState.value == 0 }
                            .verticalScroll(mainScrollState)
                            .padding(bottom = 32.dp)
                    ) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.music_get_info)) },
                            leadingContent = { Icon(Icons.Outlined.Info, null) },
                            modifier = Modifier.clickable { showSearchView = true }
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.music_edit_info)) },
                            leadingContent = { Icon(Icons.Outlined.Edit, null) },
                            modifier = Modifier.clickable { showEditInfoSheet = true }
                        )
                        if (canSwitchQuality) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.nowplaying_quality_switch_title)) },
                                leadingContent = { Icon(Icons.Outlined.MusicNote, null) },
                                supportingContent = currentQualityLabel?.takeIf { it.isNotBlank() }?.let { label ->
                                    { Text(label) }
                                },
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onShowQualitySwitch()
                                }
                            )
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.nowplaying_audio_effects_title)) },
                            leadingContent = { Icon(Icons.Outlined.Tune, null) },
                            supportingContent = {
                                Text(stringResource(R.string.nowplaying_audio_effects_desc))
                            },
                            modifier = Modifier.clickable { showPlaybackSoundSheet = true }
                        )
                        if (isLocalSong) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.local_song_open_details)) },
                                leadingContent = { Icon(Icons.Outlined.Info, null) },
                                modifier = Modifier.clickable {
                                    onShowSongDetails(originalSong)
                                    onDismiss()
                                }
                            )
                        } else if (!shouldHideDownloadAction) {
                            val downloadHeadlineRes = when {
                                currentDownloadTask?.status == DownloadStatus.QUEUED -> R.string.download_cancel_download
                                currentDownloadTask?.status == DownloadStatus.DOWNLOADING -> R.string.download_cancel_download
                                currentDownloadTask?.status == DownloadStatus.CANCELLED -> R.string.download_to_local
                                currentDownloadTask?.status == DownloadStatus.FAILED -> R.string.action_retry
                                else -> R.string.download_to_local
                            }
                            val canClickDownloadAction =
                                (currentDownloadTask?.status != DownloadStatus.QUEUED &&
                                    currentDownloadTask?.status != DownloadStatus.DOWNLOADING) ||
                                    currentDownloadCancellable

                            ListItem(
                                headlineContent = {
                                    Text(stringResource(downloadHeadlineRes))
                                },
                                leadingContent = { Icon(Icons.Outlined.Download, null) },
                                supportingContent = {
                                    when {
                                        currentDownloadTask?.progress != null -> {
                                            val progress = currentDownloadTask.progress
                                            Column {
                                                if (progress.stage == AudioDownloadManager.DownloadStage.FINALIZING) {
                                                    Text(stringResource(R.string.download_finalizing))
                                                    LinearProgressIndicator(
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                } else {
                                                    Text(
                                                        stringResource(
                                                            R.string.download_progress_file_label,
                                                            progress.percentage,
                                                            progress.fileName
                                                        )
                                                    )
                                                    if (progress.totalBytes > 0L) {
                                                        LinearProgressIndicator(
                                                            progress = {
                                                                (progress.bytesRead.toFloat() /
                                                                    progress.totalBytes.toFloat())
                                                                    .coerceIn(0f, 1f)
                                                            },
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    } else {
                                                        LinearProgressIndicator(
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        currentDownloadTask?.status == DownloadStatus.FAILED -> {
                                            Text(stringResource(R.string.download_failed))
                                        }
                                    }
                                },
                                modifier = Modifier.clickable(enabled = canClickDownloadAction) {
                                    when (currentDownloadTask?.status) {
                                        DownloadStatus.QUEUED,
                                        DownloadStatus.DOWNLOADING -> {
                                            viewModel.cancelDownload(downloadSongKey)
                                        }

                                        DownloadStatus.CANCELLED -> {
                                            viewModel.resumeDownload(context, downloadSongKey)
                                        }

                                        DownloadStatus.FAILED -> {
                                            viewModel.retryDownload(context, originalSong)
                                        }

                                        else -> {
                                            viewModel.downloadSong(context, originalSong)
                                        }
                                    }
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
                                Text(
                                    stringResource(
                                        R.string.common_percent_int,
                                        (lyricFontScale * 100).roundToInt()
                                    )
                                )
                            },
                            modifier = Modifier.clickable { showFontSizeSheet = true }
                        )
                        if (originalSong.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG)) {
                            val albumName = originalSong.album.replace(PlayerManager.NETEASE_SOURCE_TAG, "")
                            val album = AlbumSummary(
                                id = originalSong.albumId,
                                name = albumName,
                                size = 0,
                                picUrl = originalSong.coverUrl ?: ""
                            )
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
                                if (originalSong.isLocalSong()) {
                                    coroutineScope.launch {
                                        val shared = runCatching {
                                            LocalMediaSupport.shareSongFile(context, originalSong)
                                        }.getOrElse { false }
                                        if (!shared) {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.local_song_share_failed)
                                            )
                                        } else {
                                            onDismiss()
                                        }
                                    }
                                } else {
                                    val url = buildRemoteSongShareUrl(originalSong, queue)

                                    val shareText = context.getString(R.string.nowplaying_share_song, originalSong.name, originalSong.artist, url)

                                    val sendIntent: Intent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                        type = "text/plain"
                                    }

                                    val shareIntent = Intent.createChooser(sendIntent, null)
                                    context.startActivity(shareIntent)
                                    onDismiss()
                                }
                            }
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.listen_together_title)) },
                            leadingContent = { Icon(Icons.Outlined.Headphones, null) },
                            modifier = Modifier.clickable { showListenTogetherSheet = true }
                        )
                    }
                }

                "ListenTogether" -> {
                    val listenTogetherScrollState = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(listenTogetherScrollState)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        ListenTogetherRoomPanel(
                            modifier = Modifier.fillMaxWidth(),
                            showBaseUrlEditor = false
                        )
                    }
                }

                "Search" -> {
                    // 搜索界面
                    val searchState by viewModel.manualSearchState.collectAsState()
                    val searchResultsListState = rememberLazyListState()

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
                                .focusRequester(searchFocusRequester)
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
                                LazyColumn(
                                    state = searchResultsListState,
                                    modifier = Modifier.bottomSheetScrollGuard {
                                        !searchResultsListState.canScrollBackward
                                    }
                                ) {
                                    items(
                                        items = searchState.searchResults,
                                        key = { songResult ->
                                            "${songResult.source.name}:${songResult.id}"
                                        },
                                        contentType = { "search_result" }
                                    ) { songResult ->
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

                        // 完成按钮
                        HapticTextButton(
                            onClick = { showSearchView = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.action_done))
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

                "EditInfo" -> {
                    EditSongInfoSheet(
                        viewModel = viewModel,
                        originalSong = originalSong,
                        displayedLyrics = displayedLyrics,
                        displayedTranslatedLyrics = displayedTranslatedLyrics,
                        onDismiss = { showEditInfoSheet = false },
                        snackbarHostState = snackbarHostState
                    )
                }

                "PlaybackSound" -> {
                    PlaybackSoundSheet(
                        state = playbackSoundState,
                        onSpeedChange = { value, persist -> viewModel.setPlaybackSpeed(value, persist) },
                        onPitchChange = { value, persist -> viewModel.setPlaybackPitch(value, persist) },
                        onLoudnessGainChange = { value, persist -> viewModel.setPlaybackLoudnessGain(value, persist) },
                        onEqualizerEnabledChange = viewModel::setPlaybackEqualizerEnabled,
                        onPresetSelected = viewModel::selectPlaybackEqualizerPreset,
                        onBandLevelChange = { index, value, persist ->
                            viewModel.updatePlaybackEqualizerBandLevel(index, value, persist)
                        },
                        onReset = viewModel::resetPlaybackSoundSettings,
                        onDismiss = { showPlaybackSoundSheet = false }
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

private data class NowPlayingProgressInfoSegment(
    val label: String,
    val highlighted: Boolean = false
)

private fun buildNowPlayingProgressInfoSegments(
    audioInfo: PlaybackAudioInfo?,
    showQualitySwitch: Boolean,
    showAudioCodec: Boolean,
    showAudioSpec: Boolean
): List<NowPlayingProgressInfoSegment> {
    if (audioInfo == null) return emptyList()
    val segments = mutableListOf<NowPlayingProgressInfoSegment>()
    val qualityLabel = audioInfo.qualityLabel
    if (showQualitySwitch && !qualityLabel.isNullOrBlank()) {
        segments += NowPlayingProgressInfoSegment(
            label = qualityLabel,
            highlighted = true
        )
    }
    val codecLabel = audioInfo.codecLabel
    if (showAudioCodec && !codecLabel.isNullOrBlank()) {
        segments += NowPlayingProgressInfoSegment(label = codecLabel)
    }
    val specLabel = audioInfo.specLabel?.takeIf { it.isNotBlank() }
    if (showAudioSpec && specLabel != null) {
        segments += NowPlayingProgressInfoSegment(label = specLabel)
    }
    return segments
}

@Composable
private fun NowPlayingProgressInfoRow(
    segments: List<NowPlayingProgressInfoSegment>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    Text(
                        text = "  ·  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f)
                    )
                }
                Text(
                    text = segment.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (segment.highlighted) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                    }
                )
            }
        }
    }
}

@Composable
fun NowPlayingQualityOptionsDialog(
    title: String,
    selectedKey: String?,
    options: List<PlaybackQualityOption>,
    onDismiss: () -> Unit,
    onSelect: (PlaybackQualityOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(option.label) },
                        trailingContent = {
                            if (option.key == selectedKey) {
                                Text(
                                    text = stringResource(R.string.common_selected),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(option) },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
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
            .bottomSheetDragBlocker()
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
            .bottomSheetDragBlocker()
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
    var sliderValue by remember { mutableFloatStateOf(normalizeLyricFontScale(currentScale)) }

    LaunchedEffect(currentScale) {
        sliderValue = normalizeLyricFontScale(currentScale)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetDragBlocker()
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
            onValueChangeFinished = { onScaleCommit(normalizeLyricFontScale(sliderValue)) },
            valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
            steps = 10
        )

        Text(
            text = stringResource(R.string.nowplaying_lyrics_sample),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
            fontSize = scaledLyricFontSize(18f, sliderValue).sp
        )

        Spacer(Modifier.height(16.dp))
        HapticTextButton(onClick = {
            onScaleCommit(normalizeLyricFontScale(sliderValue))
            onDismiss()
        }) {
            Text(stringResource(R.string.action_done))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun EditSongInfoSheet(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 监听当前播放的歌曲，以便在"获取歌曲信息"后更新UI
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val actualSong = if (currentSong?.sameIdentityAs(originalSong) == true) {
        currentSong!!
    } else {
        originalSong
    }

    var coverUrl by remember { mutableStateOf(actualSong.customCoverUrl ?: actualSong.coverUrl ?: "") }
    var songName by remember { mutableStateOf(actualSong.customName ?: actualSong.name) }
    var artistName by remember { mutableStateOf(actualSong.customArtist ?: actualSong.artist) }
    var showSearchResults by remember { mutableStateOf(false) }
    var selectedSongForFill by remember { mutableStateOf<SongSearchInfo?>(null) }
    var showLyricsEditor by remember { mutableStateOf(false) }
    var lyricsToEdit by remember { mutableStateOf<String?>(null) }
    var translatedLyricsToEdit by remember { mutableStateOf<String?>(null) }
    var shouldClearLyrics by remember { mutableStateOf(false) }  // 标记是否应该清除歌词(B站)
    var shouldRestoreLyrics by remember { mutableStateOf(false) }  // 标记是否应该恢复歌词(网易云)
    var originalLyric by remember { mutableStateOf<String?>(null) }  // 保存要恢复的原始歌词
    var originalTranslatedLyric by remember { mutableStateOf<String?>(null) }  // 保存要恢复的原始翻译歌词

    // 标记用户是否手动编辑过，避免自动重置
    var userHasEdited by remember { mutableStateOf(false) }

    val searchState by viewModel.manualSearchState.collectAsState()

    val scrollState = rememberScrollState()

    // 当歌曲信息更新时，同步更新UI（仅在用户未手动编辑时）
    LaunchedEffect(actualSong) {
        if (!userHasEdited) {
            coverUrl = actualSong.customCoverUrl ?: actualSong.coverUrl ?: ""
            songName = actualSong.customName ?: actualSong.name
            artistName = actualSong.customArtist ?: actualSong.artist
        }
    }

    LaunchedEffect(Unit) {
        viewModel.prepareForSearch(originalSong.name)
    }

    fun applyOriginalInfo(
        restoreCover: Boolean,
        restoreTitle: Boolean,
        restoreArtist: Boolean,
        restoreLyrics: Boolean
    ) {
        viewModel.fetchOriginalInfo(context, actualSong) { success, info, message ->
            if (success && info != null) {
                if (restoreTitle) {
                    songName = info.name
                }
                if (restoreArtist) {
                    artistName = info.artist
                }
                if (restoreCover) {
                    coverUrl = info.coverUrl ?: ""
                }
                if (restoreLyrics) {
                    if (info.shouldClearLyrics) {
                        shouldClearLyrics = true
                        shouldRestoreLyrics = false
                        originalLyric = null
                        originalTranslatedLyric = null
                    } else {
                        shouldClearLyrics = false
                        shouldRestoreLyrics = info.lyric != null || info.translatedLyric != null
                        originalLyric = info.lyric
                        originalTranslatedLyric = info.translatedLyric
                    }
                }
                userHasEdited = true
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 使用 AnimatedVisibility 控制内容显示，避免重叠
    AnimatedVisibility(
        visible = !showLyricsEditor,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_edit_info),
                style = MaterialTheme.typography.titleMedium
            )

            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .bottomSheetScrollGuard { scrollState.value == 0 }
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 封面链接输入框
            OutlinedTextField(
                value = coverUrl,
                onValueChange = { coverUrl = it },
                label = { Text(stringResource(R.string.music_cover_url)) },
                placeholder = { Text(stringResource(R.string.music_cover_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    HapticIconButton(
                        onClick = {
                            applyOriginalInfo(
                                restoreCover = true,
                                restoreTitle = false,
                                restoreArtist = false,
                                restoreLyrics = false
                            )
                        }
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.music_restore_cover)
                        )
                    }
                }
            )

            // 封面预览
            if (coverUrl.isNotBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = offlineCachedImageRequest(
                            context = context,
                            data = coverUrl,
                            sizePx = 384,
                            allowHardware = false
                        ),
                        contentDescription = stringResource(R.string.music_edit_cover),
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 标题输入框
            OutlinedTextField(
                value = songName,
                onValueChange = { songName = it },
                label = { Text(stringResource(R.string.music_edit_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    HapticIconButton(
                        onClick = {
                            applyOriginalInfo(
                                restoreCover = false,
                                restoreTitle = true,
                                restoreArtist = false,
                                restoreLyrics = false
                            )
                        }
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.music_restore_title)
                        )
                    }
                }
            )

            // 艺术家输入框
            OutlinedTextField(
                value = artistName,
                onValueChange = { artistName = it },
                label = { Text(stringResource(R.string.music_edit_artist)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    HapticIconButton(
                        onClick = {
                            applyOriginalInfo(
                                restoreCover = false,
                                restoreTitle = false,
                                restoreArtist = true,
                                restoreLyrics = false
                            )
                        }
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.music_restore_artist)
                        )
                    }
                }
            )

            // 编辑歌词按钮
            HapticTextButton(
                onClick = {
                    // 在打开编辑器前先获取歌词
                    val displayedLyricsSnapshot = displayedLyrics.toList()
                    val displayedTranslatedLyricsSnapshot = displayedTranslatedLyrics.toList()
                    coroutineScope.launch {
                        try {
                            val loadedLyricsResult: Pair<String, String> = withContext(Dispatchers.IO) {
                                val rawNeteaseLyric = runCatching {
                                    val preferredSongId = resolvePreferredNeteaseLyricSongId(actualSong)
                                    if (preferredSongId != null) {
                                        extractPreferredNeteaseLyricContent(
                                            AppContainer.neteaseClient.getLyricNew(preferredSongId)
                                        )
                                    } else {
                                        null
                                    }
                                }.getOrNull().orEmpty()
                                val displayedLyricsText = displayedLyricsSnapshot.toEditableLyricsText()

                                // 把歌词准备挪到后台，避免打开编辑器时把主线程卡住
                                val fallbackLyricsText = run {
                                    val lyricEntries = PlayerManager.getLyrics(actualSong)
                                    if (lyricEntries.isNotEmpty()) {
                                        lyricEntries.toEditableLyricsText()
                                    } else {
                                        null
                                    }
                                }
                                val lyrics = resolveLyricsEditorInitialText(
                                    matchedLyric = actualSong.matchedLyric,
                                    preferredNeteaseLyric = rawNeteaseLyric,
                                    displayedLyricsText = displayedLyricsText,
                                    displayedHasWordTimedEntries = displayedLyricsSnapshot.hasWordTimedEntries(),
                                    fallbackLyricsText = fallbackLyricsText
                                )

                                val translatedLyrics = try {
                                    if (actualSong.matchedTranslatedLyric != null) {
                                        actualSong.matchedTranslatedLyric
                                    } else {
                                        val translatedEntries =
                                            if (displayedTranslatedLyricsSnapshot.isNotEmpty()) {
                                                displayedTranslatedLyricsSnapshot
                                            } else {
                                                PlayerManager.getTranslatedLyrics(actualSong)
                                            }
                                        if (translatedEntries.isNotEmpty()) {
                                            translatedEntries.toEditableLyricsText()
                                        } else {
                                            ""
                                        }
                                    }
                                } catch (_: Exception) {
                                    ""
                                }

                                Pair(lyrics, translatedLyrics)
                            }
                            val loadedLyrics = loadedLyricsResult.first
                            val loadedTranslatedLyrics = loadedLyricsResult.second

                            lyricsToEdit = loadedLyrics
                            translatedLyricsToEdit = loadedTranslatedLyrics
                            showLyricsEditor = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                            lyricsToEdit = ""
                            translatedLyricsToEdit = ""
                            showLyricsEditor = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.music_edit_lyrics))
            }
        }

        val actionButtonContainerWidth = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width.toDp()
        }
        val actionButtonFontSize = if (actionButtonContainerWidth < 420.dp) 11.sp else 13.sp

        // 搜索自动填充按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HapticTextButton(
                onClick = {
                    viewModel.performSearch()
                    showSearchResults = true
                    focusManager.clearFocus()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.music_auto_fill),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = actionButtonFontSize
                )
            }

            HapticTextButton(
                onClick = {
                    applyOriginalInfo(
                        restoreCover = true,
                        restoreTitle = true,
                        restoreArtist = true,
                        restoreLyrics = true
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.music_restore_original),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = actionButtonFontSize
                )
            }

            HapticTextButton(
                onClick = {
                    coroutineScope.launch {
                        try {
                            // 处理歌词：清除(B站)或恢复(网易云)
                            if (shouldClearLyrics) {
                                // B站音源：清除歌词
                                NPLogger.d("NowPlayingScreen", "=== 开始清除歌词流程 ===")
                                NPLogger.d("NowPlayingScreen", "actualSong详情: id=${actualSong.id}, album='${actualSong.album}', name='${actualSong.name}', artist='${actualSong.artist}'")
                                NPLogger.d("NowPlayingScreen", "当前歌词状态: matchedLyric=${actualSong.matchedLyric?.take(50)}, matchedTranslatedLyric=${actualSong.matchedTranslatedLyric?.take(50)}")

                                NPLogger.d("NowPlayingScreen", "准备调用PlayerManager.updateSongLyricsAndTranslation清除歌词")
                                PlayerManager.updateSongLyricsAndTranslation(
                                    actualSong,
                                    "",  // 清空歌词
                                    ""  // 清空翻译歌词
                                )
                                NPLogger.d("NowPlayingScreen", "PlayerManager.updateSongLyricsAndTranslation调用完成")
                                shouldClearLyrics = false  // 重置标志
                                NPLogger.d("NowPlayingScreen", "=== 清除歌词流程完成 ===")
                            } else if (shouldRestoreLyrics) {
                                // 网易云音源：恢复歌词
                                NPLogger.d("NowPlayingScreen", "=== 开始恢复歌词流程 ===")
                                NPLogger.d("NowPlayingScreen", "actualSong详情: id=${actualSong.id}, album='${actualSong.album}'")
                                NPLogger.d("NowPlayingScreen", "原始歌词: lyric=${originalLyric?.take(50)}, translatedLyric=${originalTranslatedLyric?.take(50)}")

                                NPLogger.d("NowPlayingScreen", "准备调用PlayerManager.updateSongLyricsAndTranslation恢复歌词")
                                PlayerManager.updateSongLyricsAndTranslation(
                                    actualSong,
                                    originalLyric,  // 恢复原始歌词
                                    originalTranslatedLyric  // 恢复原始翻译歌词
                                )
                                NPLogger.d("NowPlayingScreen", "PlayerManager.updateSongLyricsAndTranslation调用完成")
                                shouldRestoreLyrics = false  // 重置标志
                                originalLyric = null
                                originalTranslatedLyric = null
                                NPLogger.d("NowPlayingScreen", "=== 恢复歌词流程完成 ===")
                            }

                            // 然后更新歌曲信息
                            viewModel.updateSongInfo(
                                originalSong = actualSong,
                                newCoverUrl = coverUrl.ifBlank { null },
                                newName = songName,
                                newArtist = artistName
                            )

                            // 重置编辑标志，允许自动更新
                            userHasEdited = false
                            onDismiss()
                        } catch (e: Exception) {
                            NPLogger.e("NowPlayingScreen", "保存歌曲信息失败", e)
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_save_failed, e.message.orEmpty()),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.music_save_changes),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = actionButtonFontSize
                )
            }
        }
    }
    } // 关闭 AnimatedVisibility

    // 填充选项对话框
    if (selectedSongForFill != null) {
        FillOptionsDialog(
            songResult = selectedSongForFill!!,
            onDismiss = { selectedSongForFill = null },
            onConfirm = { fillCover, fillTitle, fillArtist, fillLyrics ->
                // 标记用户已编辑，防止自动重置
                userHasEdited = true

                if (fillCover) {
                    coverUrl = selectedSongForFill!!.coverUrl?.replaceFirst("http://", "https://") ?: ""
                }
                if (fillTitle) {
                    songName = selectedSongForFill!!.songName
                }
                if (fillArtist) {
                    artistName = selectedSongForFill!!.singer
                }
                if (fillLyrics) {
                    selectedSongForFill?.let { selectedSong ->
                        viewModel.fillLyrics(context, actualSong, selectedSong) { _, message ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    }
                }
                selectedSongForFill = null
                showSearchResults = false
            }
        )
    }

    // 歌词编辑器
    if (showLyricsEditor) {
        LyricsEditorSheet(
            originalSong = actualSong,
            initialLyrics = lyricsToEdit ?: actualSong.matchedLyric ?: "",
            initialTranslatedLyrics = translatedLyricsToEdit ?: "",
            onDismiss = {
                showLyricsEditor = false
                // 不关闭外层Sheet，只关闭歌词编辑器
            }
        )
    }

    // 搜索结果Sheet
    if (showSearchResults) {
        ModalBottomSheet(
            onDismissRequest = { showSearchResults = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetGesturesEnabled = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.music_select_result),
                        style = MaterialTheme.typography.titleMedium
                    )

                    HapticTextButton(onClick = { showSearchResults = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

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

                // 搜索结果列表
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (searchState.isLoading) {
                        CircularProgressIndicator()
                    } else if (searchState.searchResults.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.bottomSheetScrollGuard(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = searchState.searchResults,
                                key = { songResult ->
                                    "${songResult.source.name}:${songResult.id}"
                                },
                                contentType = { "search_result" }
                            ) { songResult ->
                                androidx.compose.material3.Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = androidx.compose.material3.CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                                    ),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
                                    )
                                ) {
                                    ListItem(
                                        colors = androidx.compose.material3.ListItemDefaults.colors(
                                            containerColor = Color.Transparent
                                        ),
                                        headlineContent = { Text(songResult.songName, maxLines = 1) },
                                        supportingContent = { Text(songResult.singer, maxLines = 1) },
                                        leadingContent = {
                                            AsyncImage(
                                                model = offlineCachedImageRequest(
                                                    context,
                                                    songResult.coverUrl?.replaceFirst("http://", "https://")
                                                ),
                                                contentDescription = songResult.songName,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            selectedSongForFill = songResult
                                            showSearchResults = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = searchState.error ?: stringResource(R.string.nowplaying_no_search_result),
                            color = if (searchState.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingProgressSection(
    songKey: String?,
    durationMs: Long,
    isPlaying: Boolean,
    progressInfoSegments: List<NowPlayingProgressInfoSegment>,
    useWideLandscapeLayout: Boolean,
    onPreviewPositionChange: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsState()
    val latestOnPreviewPositionChange by rememberUpdatedState(onPreviewPositionChange)
    var isUserDraggingSlider by remember(songKey) { mutableStateOf(false) }
    var sliderPosition by remember(songKey) {
        mutableFloatStateOf(PlayerManager.playbackPositionFlow.value.toFloat())
    }
    var pendingSeekPreviewPositionMs by remember(songKey) { mutableStateOf<Long?>(null) }
    val effectivePreviewPositionMs = resolveLyricPreviewTimeMs(
        isDraggingSlider = isUserDraggingSlider,
        sliderPreviewPositionMs = sliderPosition.toLong(),
        pendingSeekPreviewPositionMs = pendingSeekPreviewPositionMs,
        playbackPositionMs = currentPosition
    )
    val previewOverridePositionMs = remember(
        effectivePreviewPositionMs,
        isUserDraggingSlider,
        pendingSeekPreviewPositionMs
    ) {
        if (isUserDraggingSlider || pendingSeekPreviewPositionMs != null) {
            effectivePreviewPositionMs
        } else {
            null
        }
    }

    LaunchedEffect(currentPosition, isUserDraggingSlider, pendingSeekPreviewPositionMs) {
        if (!isUserDraggingSlider && pendingSeekPreviewPositionMs == null) {
            sliderPosition = currentPosition.toFloat()
        }
        val pendingPreview = pendingSeekPreviewPositionMs
        if (!isUserDraggingSlider && pendingPreview != null &&
            shouldReleaseLyricSeekPreview(
                playbackPositionMs = currentPosition,
                pendingSeekPreviewPositionMs = pendingPreview
            )
        ) {
            pendingSeekPreviewPositionMs = null
        }
    }
    LaunchedEffect(previewOverridePositionMs) {
        latestOnPreviewPositionChange(previewOverridePositionMs)
    }
    DisposableEffect(Unit) {
        onDispose {
            latestOnPreviewPositionChange(null)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatDuration(effectivePreviewPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            WaveformSlider(
                modifier = Modifier.weight(1f),
                value = if (durationMs > 0) {
                    effectivePreviewPositionMs.toFloat() / durationMs
                } else {
                    0f
                },
                onValueChange = { newPercentage ->
                    isUserDraggingSlider = true
                    sliderPosition = newPercentage * durationMs
                },
                onValueChangeFinished = {
                    val previewTarget = sliderPosition.toLong()
                    pendingSeekPreviewPositionMs = previewTarget
                    PlayerManager.seekTo(previewTarget)
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

        if (progressInfoSegments.isNotEmpty()) {
            Spacer(Modifier.height(0.dp))
            NowPlayingProgressInfoRow(
                segments = progressInfoSegments,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = if (useWideLandscapeLayout) (-5).dp else (-6).dp)
            )
        }
    }
}

@Composable
private fun NowPlayingLyricsPane(
    lyrics: List<LyricEntry>,
    previewPositionOverrideMs: Long?,
    modifier: Modifier = Modifier,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    translationFontSize: androidx.compose.ui.unit.TextUnit,
    visualSpec: LyricVisualSpec,
    lyricOffsetMs: Long,
    lyricBlurEnabled: Boolean,
    lyricBlurAmount: Float,
    onLyricClick: (LyricEntry) -> Unit,
    translatedLyrics: List<LyricEntry>? = null
) {
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsState()
    val effectivePositionMs = previewPositionOverrideMs ?: currentPosition
    AppleMusicLyric(
        lyrics = lyrics,
        currentTimeMs = effectivePositionMs,
        modifier = modifier,
        textColor = textColor,
        fontSize = fontSize,
        translationFontSize = translationFontSize,
        visualSpec = visualSpec,
        lyricOffsetMs = lyricOffsetMs,
        lyricBlurEnabled = lyricBlurEnabled,
        lyricBlurAmount = lyricBlurAmount,
        onLyricClick = onLyricClick,
        translatedLyrics = translatedLyrics
    )
}

@Composable
fun LyricsEditorSheet(
    originalSong: SongItem,
    initialLyrics: String,
    initialTranslatedLyrics: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    var lyricsText by remember { mutableStateOf(initialLyrics) }
    var translatedLyricsText by remember { mutableStateOf(initialTranslatedLyrics) }
    var isSaving by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }


    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .bottomSheetScrollGuard()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_edit_lyrics),
                style = MaterialTheme.typography.titleMedium
            )

            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }

        // 歌曲信息
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text(
                text = originalSong.customName ?: originalSong.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = originalSong.customArtist ?: originalSong.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 标签页切换
        androidx.compose.material3.PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.lyrics_original)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.lyrics_translation)) }
            )
        }

        // 歌词编辑器
        when (selectedTab) {
            0 -> {
                OutlinedTextField(
                    value = lyricsText,
                    onValueChange = { lyricsText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.lyrics_editor_hint_original),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = Int.MAX_VALUE
                )
            }
            1 -> {
                OutlinedTextField(
                    value = translatedLyricsText,
                    onValueChange = { translatedLyricsText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.lyrics_editor_hint_translation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = Int.MAX_VALUE
                )
            }
        }

        // 底部按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HapticTextButton(
                onClick = {
                    when (selectedTab) {
                        0 -> lyricsText = ""
                        1 -> translatedLyricsText = ""
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_clear))
            }

            HapticTextButton(
                onClick = {
                    coroutineScope.launch {
                        val clipText = clipboard.getClipEntry()
                            ?.clipData
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        if (!clipText.isNullOrEmpty()) {
                            when (selectedTab) {
                                0 -> lyricsText = clipText
                                1 -> translatedLyricsText = clipText
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_paste))
            }

            HapticTextButton(
                onClick = {
                    isSaving = true
                    coroutineScope.launch {
                        try {
                            // 保存原文歌词
                            PlayerManager.updateSongLyrics(originalSong, lyricsText)
                            // 保存翻译歌词
                            PlayerManager.updateSongTranslatedLyrics(originalSong, translatedLyricsText)
                            onDismiss()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.music_save_changes))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillOptionsDialog(
    songResult: SongSearchInfo,
    onDismiss: () -> Unit,
    onConfirm: (fillCover: Boolean, fillTitle: Boolean, fillArtist: Boolean, fillLyrics: Boolean) -> Unit
) {
    var fillCover by remember { mutableStateOf(true) }
    var fillTitle by remember { mutableStateOf(true) }
    var fillArtist by remember { mutableStateOf(true) }
    var fillLyrics by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.music_auto_fill_select)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 显示选中的歌曲信息
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = songResult.songName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = songResult.singer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 填充选项
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fillCover = !fillCover }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = fillCover,
                        onCheckedChange = { fillCover = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_cover))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fillTitle = !fillTitle }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = fillTitle,
                        onCheckedChange = { fillTitle = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_title))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fillArtist = !fillArtist }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = fillArtist,
                        onCheckedChange = { fillArtist = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_artist))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fillLyrics = !fillLyrics }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = fillLyrics,
                        onCheckedChange = { fillLyrics = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.music_auto_fill_lyrics))
                }
            }
        },
        confirmButton = {
            HapticTextButton(
                onClick = { onConfirm(fillCover, fillTitle, fillArtist, fillLyrics) }
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
