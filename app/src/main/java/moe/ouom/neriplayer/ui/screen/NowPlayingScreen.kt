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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.shouldHideRemoteDownloadAction
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
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
import moe.ouom.neriplayer.data.settings.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.LYRIC_DEFAULT_OFFSET_STEP_MS
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.normalizeLyricFontScale
import moe.ouom.neriplayer.data.settings.resolveLyricDefaultOffsetMs
import moe.ouom.neriplayer.data.settings.scaledLyricFontSize
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.lyrics.AdvancedLyricsView
import moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsView
import moe.ouom.neriplayer.ui.component.lyrics.buildPhoneticLyricEntries
import moe.ouom.neriplayer.ui.component.lyrics.flattenWordTimedEntries
import moe.ouom.neriplayer.ui.component.lyrics.hasWordTimedEntries
import moe.ouom.neriplayer.ui.component.local.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.LyricShareSheet
import moe.ouom.neriplayer.ui.component.lyrics.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.playback.PlaybackSoundSheet
import moe.ouom.neriplayer.ui.component.playback.SongMetadataSearchContent
import moe.ouom.neriplayer.ui.component.playback.NowPlayingCoverPreviewDialog
import moe.ouom.neriplayer.ui.component.playback.PlaybackControlIndicator
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceBadge
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import moe.ouom.neriplayer.ui.component.playback.rememberDelayedPlaybackWaiting
import moe.ouom.neriplayer.ui.component.playback.SleepTimerDialog
import moe.ouom.neriplayer.ui.component.playback.WaveformSlider
import moe.ouom.neriplayer.ui.component.playback.resolvePlaybackWaiting
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.component.lyrics.rememberLyricSeekHapticFeedback
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricsEditorInitialText
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.resolvePreferredLyricContent
import moe.ouom.neriplayer.ui.component.lyrics.resolveStoredLyricText
import moe.ouom.neriplayer.ui.component.lyrics.toEditableLyricsText
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherRoomPanel
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.util.media.saveCoverToPictures
import java.util.Locale
import kotlin.math.roundToInt

private const val LyricsPageTransitionDurationMs = 300
private const val CoverSourceBadgeRevealBufferMs = 120
private const val CoverSourceBadgeRevealDelayMs =
    LyricsPageTransitionDurationMs + CoverSourceBadgeRevealBufferMs
private const val QueueSheetMaxHeightFraction = 0.9f
private val LyricOffsetStepMsFloat = LYRIC_DEFAULT_OFFSET_STEP_MS.toFloat()

internal enum class NowPlayingWideLyricsMode {
    NO_LYRICS,
    ADVANCED,
    SYNCED
}

internal fun resolveNowPlayingWideLyricsMode(
    hasLyrics: Boolean,
    advancedLyricsEnabled: Boolean
): NowPlayingWideLyricsMode = when {
    !hasLyrics -> NowPlayingWideLyricsMode.NO_LYRICS
    advancedLyricsEnabled -> NowPlayingWideLyricsMode.ADVANCED
    else -> NowPlayingWideLyricsMode.SYNCED
}

internal fun shouldHideDownloadActionForSong(
    hasLocalDownload: Boolean,
    currentTask: moe.ouom.neriplayer.core.download.DownloadTask?
): Boolean = shouldHideRemoteDownloadAction(hasLocalDownload, currentTask)

internal fun buildNowPlayingQueueItemKey(index: Int, song: SongItem): String {
    return "$index:${song.stableKey()}"
}

internal fun resolveNowPlayingPlaybackSourceType(
    isLocalSong: Boolean,
    isYouTubeMusicSong: Boolean,
    isFromNeteaseTag: Boolean,
    isFromBiliTag: Boolean,
    currentMediaUrl: String?,
    playbackAudioSource: PlaybackAudioSource?
): PlaybackSourceType? {
    if (isLocalSong) return PlaybackSourceType.LOCAL

    when (playbackAudioSource) {
        PlaybackAudioSource.NETEASE -> return PlaybackSourceType.NETEASE
        PlaybackAudioSource.BILIBILI -> return PlaybackSourceType.BILIBILI
        PlaybackAudioSource.YOUTUBE_MUSIC -> return PlaybackSourceType.YOUTUBE_MUSIC
        PlaybackAudioSource.LOCAL,
        null -> Unit
    }

    if (isYouTubeMusicSong) return PlaybackSourceType.YOUTUBE_MUSIC

    val isFromNeteaseUrl = currentMediaUrl?.contains("music.126.net", ignoreCase = true) == true
    val isFromBiliUrl = currentMediaUrl?.contains("bilivideo.", ignoreCase = true) == true
    return when {
        isFromBiliTag || (!isFromNeteaseTag && isFromBiliUrl) -> PlaybackSourceType.BILIBILI
        isFromNeteaseTag || (!isFromBiliTag && isFromNeteaseUrl) -> PlaybackSourceType.NETEASE
        else -> null
    }
}

internal fun hasCachedLocalDownload(song: SongItem): Boolean {
    return GlobalDownloadManager.hasDownloadedSongCached(song) ||
        ManagedDownloadStorage.peekDownloadedAudio(song) != null
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

private data class LoadedLyricsState(
    val rawLyrics: String?,
    val rawTranslatedLyrics: String?,
    val rawPhoneticLyrics: String?,
    val lyrics: List<LyricEntry>,
    val translatedLyrics: List<LyricEntry>,
    val phoneticLyrics: List<LyricEntry>,
    val plainLyrics: List<LyricEntry>,
    val plainTranslatedLyrics: List<LyricEntry>,
    val embeddedPhoneticLyrics: List<LyricEntry>
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun NowPlayingScreen(
    onNavigateUp: () -> Unit,
    showLyricsScreen: Boolean,
    onShowLyricsScreenChange: (Boolean) -> Unit,
    onEnterAlbum: (AlbumSummary) -> Unit,
    onEnterArtist: (NeteaseArtistSummary) -> Unit = {},
    lyricBlurEnabled: Boolean,
    lyricBlurAmount: Float,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    advancedLyricsEnabled: Boolean = true,
    showCoverSourceBadge: Boolean = true,
    showLyricTranslation: Boolean = true,
    showNowPlayingTitle: Boolean = true,
    offlineMode: Boolean = false,
) {
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val isPlaybackControlPlaying by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
    val usbPlaybackPreparing by PlayerManager.usbExclusivePlaybackPreparingFlow.collectAsStateWithLifecycle()
    val isPlaybackWaiting = resolvePlaybackWaiting(
        playbackRequested = isPlaybackControlPlaying,
        isPlaying = isPlaying,
        usbPlaybackPreparing = usbPlaybackPreparing
    )
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsStateWithLifecycle()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsStateWithLifecycle()
    val durationMs by PlayerManager.playbackDurationFlow.collectAsStateWithLifecycle()
    val sleepTimerState by PlayerManager.sleepTimerManager.timerState.collectAsStateWithLifecycle()
    val currentPlaybackAudioInfo by PlayerManager.currentPlaybackAudioInfoFlow.collectAsStateWithLifecycle()
    val playbackSoundState by PlayerManager.playbackSoundStateFlow.collectAsStateWithLifecycle()
    val settingsRepo = remember { AppContainer.settingsRepo }
    val listenTogetherSessionManager = remember { AppContainer.listenTogetherSessionManager }
    val listenTogetherSessionState by listenTogetherSessionManager.sessionState.collectAsStateWithLifecycle()
    val listenTogetherRoomState by listenTogetherSessionManager.roomState.collectAsStateWithLifecycle()
    val playbackProgressSeekEnabled = resolveListenTogetherProgressSeekEnabled(
        sessionUserUuid = listenTogetherSessionState.userUuid,
        fallbackRole = listenTogetherSessionState.role,
        roomId = listenTogetherSessionState.roomId,
        controllerUserUuid = listenTogetherRoomState?.controllerUserUuid,
        controllerUserId = listenTogetherRoomState?.controllerUserId,
        allowMemberControl = listenTogetherRoomState?.settings?.allowMemberControl
    )
    val showProgressQualitySwitch by settingsRepo
        .nowPlayingProgressShowQualitySwitchFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingToolbarDockEnabled by settingsRepo
        .nowPlayingToolbarDockEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val showProgressAudioCodec by settingsRepo
        .nowPlayingProgressShowAudioCodecFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val showProgressAudioSpec by settingsRepo
        .nowPlayingProgressShowAudioSpecFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val cloudMusicLyricDefaultOffsetMs by settingsRepo
        .cloudMusicLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS)
    val qqMusicLyricDefaultOffsetMs by settingsRepo
        .qqMusicLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS)
    val lyricTranslationUsePhonetic by settingsRepo
        .lyricTranslationUsePhoneticFlow
        .collectAsStateWithLifecycle(initialValue = false)

    // 订阅当前播放链接
    val currentMediaUrl by PlayerManager.currentMediaUrlFlow.collectAsStateWithLifecycle()
    val isFromNeteaseTag =
        currentSong?.album?.startsWith(PlayerManager.NETEASE_SOURCE_TAG) == true
    val isFromBiliTag =
        currentSong?.album?.startsWith(PlayerManager.BILI_SOURCE_TAG) == true
    val rawPlaybackSourceType = resolveNowPlayingPlaybackSourceType(
        isLocalSong = currentSong?.isLocalSong() == true,
        isYouTubeMusicSong = currentSong?.let { isYouTubeMusicSong(it) } == true,
        isFromNeteaseTag = isFromNeteaseTag,
        isFromBiliTag = isFromBiliTag,
        currentMediaUrl = currentMediaUrl,
        playbackAudioSource = currentPlaybackAudioInfo?.source
    )
    val playbackSourceSongKey = currentSong?.let {
        listOf(it.id.toString(), it.album, it.mediaUri.orEmpty(), it.localFilePath.orEmpty())
            .joinToString(separator = "|")
    }
    var playbackSourceType by remember { mutableStateOf<PlaybackSourceType?>(null) }

    val playlists by PlayerManager.playlistsFlow.collectAsStateWithLifecycle()
    val localPlaylistsReady by PlayerManager.localPlaylistsReadyFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coverDownloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val currentCoverUrl = remember(currentSong, context, coverDownloadPresenceVersion) {
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
    LaunchedEffect(isFavoriteComputed) {
        if (favOverride == isFavoriteComputed) {
            favOverride = null
        }
    }
    val isFavorite = favOverride ?: isFavoriteComputed

    val queue by PlayerManager.currentQueueFlow.collectAsStateWithLifecycle()
    val displayedQueue = remember(queue) { queue }
    val currentIndexInDisplay = remember(displayedQueue, currentSong) {
        displayedQueue.indexOfFirst {
            it.sameIdentityAs(currentSong)
        }
    }

    var showAddSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showCoverPageSourceBadge by remember { mutableStateOf(false) }
    var animateCoverPageSourceBadge by remember { mutableStateOf(false) }
    var previousLyricsScreenState by remember { mutableStateOf(false) }
    var showCoverPreview by remember(playbackSourceSongKey) { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }
    var showSongNameMenu by remember { mutableStateOf(false) }
    var showArtistMenu by remember { mutableStateOf(false) }
    var showQualitySwitchDialog by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
        showCoverPreview = false
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
    var rawLyricsText by remember(currentSong?.id) { mutableStateOf<String?>(null) }
    var rawTranslatedLyricsText by remember(currentSong?.id) { mutableStateOf<String?>(null) }
    var rawPhoneticLyricsText by remember(currentSong?.id) { mutableStateOf<String?>(null) }
    var remotePhoneticLyrics by remember(currentSong?.id) { mutableStateOf<List<LyricEntry>>(emptyList()) }
    var plainLyrics by remember(currentSong?.id) { mutableStateOf<List<LyricEntry>>(emptyList()) }
    var plainTranslatedLyrics by remember(currentSong?.id) {
        mutableStateOf<List<LyricEntry>>(emptyList())
    }
    var embeddedPhoneticLyrics by remember(currentSong?.id) {
        mutableStateOf<List<LyricEntry>>(emptyList())
    }
    val nowPlayingViewModel: NowPlayingViewModel = viewModel()
    var artistPickerCandidates by remember { mutableStateOf<List<NeteaseArtistSummary>>(emptyList()) }
    var resolvingArtistNavigation by remember { mutableStateOf(false) }

    fun openResolvedArtist(artist: NeteaseArtistSummary) {
        onEnterArtist(artist)
        onNavigateUp()
    }

    fun openArtistCandidates(artists: List<NeteaseArtistSummary>) {
        val distinctArtists = artists.distinctBy { it.id }
        when (distinctArtists.size) {
            0 -> screenScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.artist_not_available))
            }
            1 -> openResolvedArtist(distinctArtists.first())
            else -> artistPickerCandidates = distinctArtists
        }
    }

    val openCurrentNeteaseArtist: () -> Unit = {
        val song = currentSong
        if (song == null || !isNeteaseArtistNavigationSource(song)) {
            screenScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.artist_not_available))
            }
        } else if (!resolvingArtistNavigation) {
            resolvingArtistNavigation = true
            nowPlayingViewModel.resolveNeteaseArtists(
                song = song,
                onResult = { artists ->
                    resolvingArtistNavigation = false
                    openArtistCandidates(artists)
                },
                onError = {
                    resolvingArtistNavigation = false
                    screenScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.artist_not_available))
                    }
                }
            )
        }
    }

    LaunchedEffect(
        currentSong?.id,
        currentSong?.matchedLyric,
        currentSong?.matchedTranslatedLyric,
        currentSong?.originalLyric,
        currentSong?.originalTranslatedLyric,
        currentSong?.matchedSongId,
        currentSong?.matchedLyricSource,
        currentSong?.album,
        currentMediaUrl
    ) {
        val song = currentSong
        val loadedLyricsState = withContext(Dispatchers.IO) {
            val storedRawLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedLyric,
                legacyLyric = song?.originalLyric
            )
            val storedRawTranslatedLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedTranslatedLyric,
                legacyLyric = song?.originalTranslatedLyric
            )
            val preferredSongId = resolvePreferredNeteaseLyricSongId(song)
            val preferredNeteaseLyric = runCatching {
                if (storedRawLyrics == null && preferredSongId != null) {
                    PlayerManager.getPreferredNeteaseLyricContent(preferredSongId)
                } else {
                    ""
                }
            }.getOrNull().orEmpty()
            val rawNeteasePhoneticLyric = runCatching {
                if (preferredSongId != null) {
                    PlayerManager.getPreferredNeteaseRomanizedLyricContent(preferredSongId)
                } else {
                    ""
                }
            }.getOrNull().orEmpty()
            val effectiveRawLyrics = resolvePreferredLyricContent(
                matchedLyric = song?.matchedLyric,
                preferredNeteaseLyric = preferredNeteaseLyric,
                legacyLyric = song?.originalLyric
            )
            val shouldDelayOnlineLyrics =
                song != null &&
                    extractYouTubeMusicVideoId(song.mediaUri) != null &&
                    currentMediaUrl.isNullOrBlank()
            val resolvedLyrics = when {
                !effectiveRawLyrics.isNullOrBlank() -> {
                    val parsedRawLyrics = parseNeteaseLyricsAuto(effectiveRawLyrics)
                    if (parsedRawLyrics.hasWordTimedEntries() || song == null) {
                        parsedRawLyrics
                    } else {
                        PlayerManager.getLyrics(song)
                            .takeIf { it.hasWordTimedEntries() }
                            ?: parsedRawLyrics
                    }
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
                    storedRawTranslatedLyrics != null -> {
                        if (storedRawTranslatedLyrics.isBlank()) {
                            emptyList()
                        } else {
                            parseNeteaseLyricsAuto(storedRawTranslatedLyrics)
                        }
                    }
                    song != null -> {
                        PlayerManager.getTranslatedLyrics(song)
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
            val resolvedPhoneticLyrics = try {
                when {
                    rawNeteasePhoneticLyric.isNotBlank() -> {
                        parseNeteaseLyricsAuto(rawNeteasePhoneticLyric)
                    }
                    song != null -> {
                        PlayerManager.getRomanizedLyrics(song)
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
            LoadedLyricsState(
                rawLyrics = effectiveRawLyrics,
                rawTranslatedLyrics = storedRawTranslatedLyrics,
                rawPhoneticLyrics = rawNeteasePhoneticLyric.takeIf { it.isNotBlank() },
                lyrics = resolvedLyrics,
                translatedLyrics = resolvedTranslatedLyrics,
                phoneticLyrics = resolvedPhoneticLyrics,
                plainLyrics = resolvedLyrics.flattenWordTimedEntries(),
                plainTranslatedLyrics = resolvedTranslatedLyrics.flattenWordTimedEntries(),
                embeddedPhoneticLyrics = buildPhoneticLyricEntries(
                    rawLyrics = effectiveRawLyrics,
                    lyrics = resolvedLyrics
                )
            )
        }
        rawLyricsText = loadedLyricsState.rawLyrics
        rawTranslatedLyricsText = loadedLyricsState.rawTranslatedLyrics
        rawPhoneticLyricsText = loadedLyricsState.rawPhoneticLyrics
        lyrics = loadedLyricsState.lyrics
        translatedLyrics = loadedLyricsState.translatedLyrics
        remotePhoneticLyrics = loadedLyricsState.phoneticLyrics
        plainLyrics = loadedLyricsState.plainLyrics
        plainTranslatedLyrics = loadedLyricsState.plainTranslatedLyrics
        embeddedPhoneticLyrics = loadedLyricsState.embeddedPhoneticLyrics
    }
    val phoneticLyrics = remember(rawPhoneticLyricsText, remotePhoneticLyrics, embeddedPhoneticLyrics) {
        remotePhoneticLyrics.takeIf { it.isNotEmpty() } ?: embeddedPhoneticLyrics
    }
    val usePhoneticTranslation = showLyricTranslation &&
        lyricTranslationUsePhonetic &&
        phoneticLyrics.isNotEmpty()
    val secondaryPlainLyrics = if (usePhoneticTranslation) phoneticLyrics else plainTranslatedLyrics
    var previewPositionOverrideMs by remember(currentSong?.id) { mutableStateOf<Long?>(null) }
    var lyricShareInitialLine by remember(currentSong?.stableKey()) {
        mutableStateOf<LyricEntry?>(null)
    }

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
    val platformOffset = resolveLyricDefaultOffsetMs(
        lyricSource = currentSong?.matchedLyricSource,
        cloudMusicDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
        qqMusicDefaultOffsetMs = qqMusicLyricDefaultOffsetMs
    )
    val userOffset = currentSong?.userLyricOffsetMs ?: 0L
    val totalOffset = platformOffset + userOffset
    val progressInfoSegments = remember(
        currentPlaybackAudioInfo,
        showProgressQualitySwitch,
        showProgressAudioCodec,
        showProgressAudioSpec,
        playbackSoundState.speed
    ) {
        buildNowPlayingProgressInfoSegments(
            audioInfo = currentPlaybackAudioInfo,
            showQualitySwitch = showProgressQualitySwitch,
            showAudioCodec = showProgressAudioCodec,
            showAudioSpec = showProgressAudioSpec,
            playbackSpeed = playbackSoundState.speed
        )
    }

    lyricShareInitialLine?.let { initialLine ->
        val song = currentSong
        if (song != null) {
            LyricShareSheet(
                song = song,
                lyrics = plainLyrics,
                initialLine = initialLine,
                queue = displayedQueue,
                onDismiss = { lyricShareInitialLine = null }
            )
        }
    }

    val previewCoverUrl = currentCoverUrl
    if (showCoverPreview && !previewCoverUrl.isNullOrBlank()) {
        NowPlayingCoverPreviewDialog(
            coverUrl = previewCoverUrl,
            songName = currentSong?.displayName().orEmpty(),
            offlineMode = offlineMode,
            onDownload = requestCoverDownload,
            onDismiss = { showCoverPreview = false }
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
                            rawLyrics = rawLyricsText,
                            rawTranslatedLyrics = rawTranslatedLyricsText,
                            lyricBlurEnabled = lyricBlurEnabled,
                            lyricBlurAmount = lyricBlurAmount,
                            lyricFontScale = lyricFontScale,
                            onEnterAlbum = onEnterAlbum,
                            onOpenCurrentNeteaseArtist = openCurrentNeteaseArtist,
                            onLyricFontScaleChange = onLyricFontScaleChange,
                            onExitNowPlaying = onNavigateUp,
                            onNavigateBack = { onShowLyricsScreenChange(false) },
                            onSeekTo = { position -> PlayerManager.seekTo(position) },
                            progressSeekEnabled = playbackProgressSeekEnabled,
                            advancedLyricsEnabled = advancedLyricsEnabled,
                            translatedLyrics = translatedLyrics,
                            phoneticLyrics = phoneticLyrics,
                            lyricOffsetMs = totalOffset,
                            showLyricTranslation = showLyricTranslation,
                            lyricTranslationUsePhonetic = lyricTranslationUsePhonetic,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@AnimatedContent,
                            offlineMode = offlineMode
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
                            if (dragAmount < -20) onShowLyricsScreenChange(true)
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
                                    val song = currentSong ?: return@HapticIconButton
                                    val willFav = nextFavoriteStateAfterTap(isFavorite)
                                    launchWithLocalSyncWarning(
                                        song = song,
                                        actionLabel = context.getString(R.string.favorite_add),
                                        warnForLocalSync = willFav
                                    ) {
                                        favOverride = willFav
                                        PlayerManager.toggleCurrentFavorite()
                                    }
                                },
                                enabled = localPlaylistsReady,
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
                                    hasPhoneticLyrics = phoneticLyrics.isNotEmpty(),
                                    onDismiss = { showMoreOptions = false },
                                    onShowSongDetails = { detailSong = it },
                                    onEnterAlbum = onEnterAlbum,
                                    onNavigateUp = onNavigateUp,
                                    snackbarHostState = snackbarHostState,
                                    lyricFontScale = lyricFontScale,
                                    onLyricFontScaleChange = onLyricFontScaleChange,
                                    currentPlaybackAudioInfo = currentPlaybackAudioInfo,
                                    onShowQualitySwitch = { showQualitySwitchDialog = true },
                                    offlineMode = offlineMode
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
                                                            R.string.cover_preview_unavailable
                                                        )
                                                    )
                                                }
                                            } else {
                                                showCoverPreview = true
                                            }
                                        }
                                    )
                            ) {
                                currentCoverUrl?.let { cover ->
                                    AsyncImage(
                                        model = remember(context, cover, coverRequestSizePx, offlineMode) {
                                            offlineCachedImageRequest(
                                                context = context,
                                                data = cover,
                                                sizePx = coverRequestSizePx,
                                                allowHardware = false,
                                                offlineMode = offlineMode
                                            )
                                        },
                                        contentDescription = currentSong?.customName ?: currentSong?.name ?: "",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
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
                                            onClick = openCurrentNeteaseArtist,
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
                        lyrics = plainLyrics,
                        lyricOffsetMs = totalOffset,
                        isPlaying = isPlaying,
                        isPlaybackWaiting = isPlaybackWaiting,
                        progressInfoSegments = progressInfoSegments,
                        seekEnabled = playbackProgressSeekEnabled,
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
                            enabled = !usbPlaybackPreparing,
                            modifier = Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = "play_button"),
                                    animatedVisibilityScope = this@AnimatedContent
                                )
                                .size(primaryControlButtonSize)
                        ) {
                            PlaybackControlIndicator(
                                isPlaying = isPlaybackControlPlaying,
                                isPlaybackWaiting = isPlaybackWaiting,
                                playContentDescription = stringResource(R.string.player_play),
                                pauseContentDescription = stringResource(R.string.player_pause),
                                waitingContentDescription = stringResource(R.string.player_waiting)
                            )
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
                            isPlaying = isPlaying && previewPositionOverrideMs == null,
                            playbackSpeed = playbackSoundState.speed,
                            onLyricClick = { entry -> PlayerManager.seekTo(entry.startTimeMs) },
                            onLyricLongClick = { entry -> lyricShareInitialLine = entry },
                            translatedLyrics = if (showLyricTranslation) secondaryPlainLyrics else null
                        )
                    }

                    // 将下面的内容推到底部，平板横屏也保持贴近底部的手感
                    Spacer(modifier = Modifier.weight(1f))

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
                                    onClick = { onShowLyricsScreenChange(!showLyricsScreen) },
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
                            when (
                                resolveNowPlayingWideLyricsMode(
                                    hasLyrics = lyrics.isNotEmpty(),
                                    advancedLyricsEnabled = advancedLyricsEnabled
                                )
                            ) {
                                NowPlayingWideLyricsMode.ADVANCED -> {
                                    val currentPosition by PlayerManager.playbackPositionFlow.collectAsStateWithLifecycle()
                                    val effectiveLyricTimeMs = previewPositionOverrideMs ?: currentPosition
                                    AdvancedLyricsView(
                                        lyrics = lyrics,
                                        currentTimeMs = effectiveLyricTimeMs,
                                        modifier = Modifier.fillMaxSize(),
                                        textColor = MaterialTheme.colorScheme.onBackground,
                                        lyricFontScale = lyricFontScale,
                                        baseFontSizeSp = 20f,
                                        lyricOffsetMs = totalOffset,
                                        rawLyrics = rawLyricsText,
                                        rawTranslatedLyrics = rawTranslatedLyricsText.takeUnless {
                                            usePhoneticTranslation
                                        },
                                        translatedLyrics = if (showLyricTranslation) {
                                            if (usePhoneticTranslation) phoneticLyrics else translatedLyrics
                                        } else {
                                            null
                                        },
                                        showLyricTranslation = showLyricTranslation,
                                        showPhoneticAsTranslation = usePhoneticTranslation,
                                        lyricBlurEnabled = lyricBlurEnabled,
                                        lyricBlurAmount = lyricBlurAmount,
                                        isPlaying = isPlaying,
                                        animateViewportScroll = previewPositionOverrideMs != null,
                                        offset = 72.dp,
                                        keepAliveZone = 128.dp,
                                        playedLyricViewportFraction = 0.36f,
                                        topFadeLength = 132.dp,
                                        bottomFadeLength = 220.dp,
                                        bottomContentInset = 32.dp,
                                        onLyricLongClick = { line ->
                                            lyricShareInitialLine = line
                                        },
                                        onSeekTo = { position -> PlayerManager.seekTo(position) }
                                    )
                                }

                                NowPlayingWideLyricsMode.SYNCED -> {
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
                                        isPlaying = isPlaying && previewPositionOverrideMs == null,
                                        playbackSpeed = playbackSoundState.speed,
                                        onLyricClick = { entry ->
                                            PlayerManager.seekTo(entry.startTimeMs)
                                        },
                                        onLyricLongClick = { entry ->
                                            lyricShareInitialLine = entry
                                        },
                                        translatedLyrics = if (showLyricTranslation) {
                                            secondaryPlainLyrics
                                        } else {
                                            null
                                        }
                                    )
                                }

                                NowPlayingWideLyricsMode.NO_LYRICS -> {
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

            if (artistPickerCandidates.isNotEmpty()) {
                NeteaseArtistPickerSheet(
                    artists = artistPickerCandidates,
                    onDismiss = { artistPickerCandidates = emptyList() },
                    onSelect = { artist ->
                        artistPickerCandidates = emptyList()
                        openResolvedArtist(artist)
                    }
                )
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
                        modifier = Modifier
                            .fillMaxHeight(QueueSheetMaxHeightFraction)
                            .bottomSheetScrollGuard()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        itemsIndexed(
                            items = displayedQueue,
                            key = ::buildNowPlayingQueueItemKey,
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

private fun isNeteaseArtistNavigationSource(song: SongItem): Boolean {
    val hasCachedArtists = song.neteaseArtists.orEmpty().any { it.id > 0L && it.name.isNotBlank() }
    val hasNeteaseCover = listOfNotNull(
        song.coverUrl,
        song.originalCoverUrl,
        song.customCoverUrl
    ).any { it.contains("music.126.net", ignoreCase = true) }
    val isManagedNeteaseDownload = song.id > 0L && listOfNotNull(
        song.localFileName,
        song.localFilePath,
        song.mediaUri
    ).any { reference ->
        reference.contains("netease -", ignoreCase = true) ||
            reference.contains("netease%20-", ignoreCase = true)
    }
    return song.channelId == "netease" ||
        song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG) ||
        song.mediaUri?.contains("music.163.com", ignoreCase = true) == true ||
        song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC ||
        hasCachedArtists ||
        hasNeteaseCover ||
        isManagedNeteaseDownload
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeteaseArtistPickerSheet(
    artists: List<NeteaseArtistSummary>,
    onDismiss: () -> Unit,
    onSelect: (NeteaseArtistSummary) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .bottomSheetScrollGuard()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.artist_choose_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            artists.forEach { artist ->
                ListItem(
                    headlineContent = { Text(artist.name) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(artist) }
                )
            }
        }
    }
}

private enum class MoreOptionsPage {
    MAIN,
    SEARCH,
    LYRIC_BEHAVIOR,
    FONT_SIZE,
    EDIT_INFO,
    LISTEN_TOGETHER,
    PLAYBACK_SOUND
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsSheet(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    hasPhoneticLyrics: Boolean = false,
    onDismiss: () -> Unit,
    onShowSongDetails: (SongItem) -> Unit = {},
    onEnterAlbum: (AlbumSummary) -> Unit,
    onNavigateUp: () -> Unit,
    snackbarHostState: SnackbarHostState,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    currentPlaybackAudioInfo: PlaybackAudioInfo? = null,
    onShowQualitySwitch: () -> Unit = {},
    offlineMode: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(MoreOptionsPage.MAIN) }
    var isDismissing by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val actualSong = currentSong?.takeIf { it.sameIdentityAs(originalSong) } ?: originalSong
    val isLocalSong = actualSong.isLocalSong()
    val playbackSoundState by PlayerManager.playbackSoundStateFlow.collectAsStateWithLifecycle()

    fun dismissSheet(afterHidden: () -> Unit = {}) {
        if (isDismissing) return
        isDismissing = true
        coroutineScope.launch {
            try {
                sheetState.hide()
                afterHidden()
            } finally {
                try {
                    onDismiss()
                } finally {
                    isDismissing = false
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissSheet() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        BackHandler(
            enabled = page != MoreOptionsPage.MAIN
        ) {
            page = MoreOptionsPage.MAIN
        }

        BackHandler(
            enabled = page == MoreOptionsPage.MAIN
        ) {
            dismissSheet()
        }

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                        scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                    .togetherWith(fadeOut(animationSpec = tween(90)))
            },
            label = "more_options_sheet_content"
        ) { targetState ->
            when (targetState) {
                MoreOptionsPage.MAIN -> {
                    MoreOptionsMainContent(
                        viewModel = viewModel,
                        originalSong = originalSong,
                        queue = queue,
                        isLocalSong = isLocalSong,
                        lyricFontScale = lyricFontScale,
                        currentPlaybackAudioInfo = currentPlaybackAudioInfo,
                        isDismissing = isDismissing,
                        snackbarHostState = snackbarHostState,
                        onOpenSearch = { page = MoreOptionsPage.SEARCH },
                        onOpenEditInfo = { page = MoreOptionsPage.EDIT_INFO },
                        onOpenPlaybackSound = { page = MoreOptionsPage.PLAYBACK_SOUND },
                        onOpenLyricBehavior = { page = MoreOptionsPage.LYRIC_BEHAVIOR },
                        onOpenFontSize = { page = MoreOptionsPage.FONT_SIZE },
                        onOpenListenTogether = { page = MoreOptionsPage.LISTEN_TOGETHER },
                        onShowSongDetails = {
                            dismissSheet { onShowSongDetails(originalSong) }
                        },
                        onShowQualitySwitch = {
                            dismissSheet { onShowQualitySwitch() }
                        },
                        onEnterAlbum = { album ->
                            dismissSheet {
                                onEnterAlbum(album)
                                onNavigateUp()
                            }
                        },
                        onDismissSheet = { afterHidden ->
                            dismissSheet(afterHidden)
                        }
                    )
                }

                MoreOptionsPage.LISTEN_TOGETHER -> {
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

                MoreOptionsPage.SEARCH -> {
                    SongMetadataSearchContent(
                        viewModel = viewModel,
                        song = actualSong,
                        offlineMode = offlineMode,
                        enabled = !isDismissing,
                        onSongSelected = { songResult ->
                            dismissSheet {
                                viewModel.onSongSelected(actualSong, songResult)
                            }
                        },
                        onDone = { page = MoreOptionsPage.MAIN }
                    )
                }

                MoreOptionsPage.LYRIC_BEHAVIOR -> {
                    LyricBehaviorSheet(
                        song = originalSong,
                        hasPhoneticLyrics = hasPhoneticLyrics,
                        onDismiss = { page = MoreOptionsPage.MAIN }
                    )
                }

                MoreOptionsPage.FONT_SIZE -> {
                    LyricFontSizeSheet(
                        currentScale = lyricFontScale,
                        onScaleCommit = onLyricFontScaleChange,
                        onDismiss = { page = MoreOptionsPage.MAIN }
                    )
                }

                MoreOptionsPage.EDIT_INFO -> {
                    EditSongInfoSheet(
                        viewModel = viewModel,
                        originalSong = actualSong,
                        displayedLyrics = displayedLyrics,
                        displayedTranslatedLyrics = displayedTranslatedLyrics,
                        onDismiss = { page = MoreOptionsPage.MAIN },
                        snackbarHostState = snackbarHostState,
                        offlineMode = offlineMode
                    )
                }

                MoreOptionsPage.PLAYBACK_SOUND -> {
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
                        onDismiss = { page = MoreOptionsPage.MAIN }
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
    showAudioSpec: Boolean,
    playbackSpeed: Float
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
    if (shouldShowPlaybackSpeedBadge(playbackSpeed)) {
        segments += NowPlayingProgressInfoSegment(label = formatNowPlayingPlaybackSpeed(playbackSpeed))
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

private fun shouldShowPlaybackSpeedBadge(playbackSpeed: Float): Boolean {
    return (playbackSpeed * 100).roundToInt() != 100
}

private fun formatNowPlayingPlaybackSpeed(playbackSpeed: Float): String {
    return String.format(Locale.US, "%.2fx", playbackSpeed)
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
    LyricBehaviorSheet(
        song = song,
        hasPhoneticLyrics = false,
        onDismiss = onDismiss
    )
}

@Composable
fun LyricBehaviorSheet(
    song: SongItem,
    hasPhoneticLyrics: Boolean,
    onDismiss: () -> Unit
) {
    var currentOffset by remember { mutableLongStateOf(song.userLyricOffsetMs) }
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { AppContainer.settingsRepo }
    val showLyricTranslation by settingsRepo.showLyricTranslationFlow.collectAsStateWithLifecycle(initialValue = true)
    val lyricTranslationUsePhonetic by settingsRepo
        .lyricTranslationUsePhoneticFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val sliderMinOffset = minOf(MIN_LYRIC_DEFAULT_OFFSET_MS, currentOffset)
    val sliderMaxOffset = maxOf(MAX_LYRIC_DEFAULT_OFFSET_MS, currentOffset)
    val sliderSteps = (((sliderMaxOffset - sliderMinOffset) / LYRIC_DEFAULT_OFFSET_STEP_MS).toInt() - 1)
        .coerceAtLeast(0)
    val phoneticSwitchEnabled = showLyricTranslation && hasPhoneticLyrics
    val phoneticSwitchChecked = showLyricTranslation && lyricTranslationUsePhonetic && hasPhoneticLyrics

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetDragBlocker()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.lyrics_adjust_behavior), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_show_lyric_translation)) },
            supportingContent = { Text(stringResource(R.string.settings_show_lyric_translation_desc)) },
            trailingContent = {
                Switch(
                    checked = showLyricTranslation,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepo.setShowLyricTranslation(enabled) }
                    }
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    scope.launch { settingsRepo.setShowLyricTranslation(!showLyricTranslation) }
                }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.lyrics_translation_use_phonetic)) },
            supportingContent = {
                Text(
                    when {
                        !showLyricTranslation ->
                            stringResource(R.string.lyrics_translation_use_phonetic_requires_translation)
                        !hasPhoneticLyrics ->
                            stringResource(R.string.lyrics_translation_use_phonetic_unavailable)
                        else -> stringResource(R.string.lyrics_translation_use_phonetic_desc)
                    }
                )
            },
            trailingContent = {
                Switch(
                    checked = phoneticSwitchChecked,
                    onCheckedChange = { enabled ->
                        if (hasPhoneticLyrics) {
                            scope.launch { settingsRepo.setLyricTranslationUsePhonetic(enabled) }
                        }
                    },
                    enabled = phoneticSwitchEnabled
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = phoneticSwitchEnabled) {
                    scope.launch {
                        settingsRepo.setLyricTranslationUsePhonetic(!phoneticSwitchChecked)
                    }
                }
        )

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lyrics_adjust_offset), style = MaterialTheme.typography.titleSmall)
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
                currentOffset = ((it / LyricOffsetStepMsFloat).roundToInt() *
                    LYRIC_DEFAULT_OFFSET_STEP_MS)
            },
            onValueChangeFinished = {
                scope.launch {
                    PlayerManager.updateUserLyricOffset(song, currentOffset)
                }
            },
            valueRange = sliderMinOffset.toFloat()..sliderMaxOffset.toFloat(),
            steps = sliderSteps
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
    snackbarHostState: SnackbarHostState,
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 监听当前播放的歌曲，以便在"获取歌曲信息"后更新UI
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
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
    var lyricsEditorSeed by remember { mutableStateOf<LyricsEditorSeed?>(null) }
    var shouldClearLyrics by remember { mutableStateOf(false) }  // 标记是否应该清除歌词(B站)
    var shouldRestoreLyrics by remember { mutableStateOf(false) }  // 标记是否应该恢复歌词(网易云)
    var originalLyric by remember { mutableStateOf<String?>(null) }  // 保存要恢复的原始歌词
    var originalTranslatedLyric by remember { mutableStateOf<String?>(null) }  // 保存要恢复的原始翻译歌词
    var shouldRestoreCoverBase by remember { mutableStateOf(false) }
    var shouldRestoreTitleBase by remember { mutableStateOf(false) }
    var shouldRestoreArtistBase by remember { mutableStateOf(false) }
    var shouldClearMatchedMetadata by remember { mutableStateOf(false) }

    // 标记用户是否手动编辑过，避免自动重置
    var userHasEdited by remember { mutableStateOf(false) }

    val searchState by viewModel.manualSearchState.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    // 当歌曲信息更新时，同步更新UI（仅在用户未手动编辑时）
    LaunchedEffect(actualSong) {
        if (!userHasEdited) {
            coverUrl = actualSong.customCoverUrl ?: actualSong.coverUrl ?: ""
            songName = actualSong.customName ?: actualSong.name
            artistName = actualSong.customArtist ?: actualSong.artist
            shouldRestoreCoverBase = false
            shouldRestoreTitleBase = false
            shouldRestoreArtistBase = false
            shouldClearMatchedMetadata = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.prepareForSearch(actualSong.displayName())
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
                    shouldRestoreTitleBase = true
                }
                if (restoreArtist) {
                    artistName = info.artist
                    shouldRestoreArtistBase = true
                }
                if (restoreCover) {
                    coverUrl = info.coverUrl ?: ""
                    shouldRestoreCoverBase = true
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
                if (restoreCover && restoreTitle && restoreArtist && restoreLyrics) {
                    shouldClearMatchedMetadata = true
                }
                userHasEdited = true
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 使用 AnimatedVisibility 控制内容显示，避免重叠
    AnimatedVisibility(
        visible = lyricsEditorSeed == null,
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
                onValueChange = {
                    coverUrl = it
                    userHasEdited = true
                    shouldRestoreCoverBase = false
                },
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
                            allowHardware = false,
                            offlineMode = offlineMode
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
                onValueChange = {
                    songName = it
                    userHasEdited = true
                    shouldRestoreTitleBase = false
                },
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
                onValueChange = {
                    artistName = it
                    userHasEdited = true
                    shouldRestoreArtistBase = false
                },
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
                                        PlayerManager.getPreferredNeteaseLyricContent(preferredSongId)
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
                                    fallbackLyricsText = fallbackLyricsText,
                                    legacyLyric = actualSong.originalLyric
                                )

                                val translatedLyrics = try {
                                    resolveStoredLyricText(
                                        currentLyric = actualSong.matchedTranslatedLyric,
                                        legacyLyric = actualSong.originalTranslatedLyric
                                    ) ?: run {
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

                            lyricsEditorSeed = resolveLyricsEditorSeed(
                                song = actualSong,
                                preparedLyrics = loadedLyrics,
                                preparedTranslatedLyrics = loadedTranslatedLyrics
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                            lyricsEditorSeed = resolveLyricsEditorSeed(song = actualSong)
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
                    viewModel.prepareForSearch(songName)
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
                                newArtist = artistName,
                                restoreBaseCover = shouldRestoreCoverBase,
                                restoreBaseName = shouldRestoreTitleBase,
                                restoreBaseArtist = shouldRestoreArtistBase,
                                clearMatchedMetadata = shouldClearMatchedMetadata
                            )

                            // 重置编辑标志，允许自动更新
                            userHasEdited = false
                            shouldRestoreCoverBase = false
                            shouldRestoreTitleBase = false
                            shouldRestoreArtistBase = false
                            shouldClearMatchedMetadata = false
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
                    shouldRestoreCoverBase = false
                }
                if (fillTitle) {
                    songName = selectedSongForFill!!.songName
                    shouldRestoreTitleBase = false
                }
                if (fillArtist) {
                    artistName = selectedSongForFill!!.singer
                    shouldRestoreArtistBase = false
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
    if (lyricsEditorSeed != null) {
        LyricsEditorSheet(
            originalSong = actualSong,
            initialLyrics = lyricsEditorSeed!!.lyrics,
            initialTranslatedLyrics = lyricsEditorSeed!!.translatedLyrics,
            onDismiss = {
                lyricsEditorSeed = null
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

                OutlinedTextField(
                    value = searchState.keyword,
                    onValueChange = { viewModel.onKeywordChange(it) },
                    label = { Text(stringResource(R.string.music_auto_fill_custom_title)) },
                    placeholder = {
                        Text(stringResource(R.string.music_auto_fill_custom_title_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        HapticIconButton(
                            onClick = {
                                viewModel.performSearch()
                                focusManager.clearFocus()
                            },
                            enabled = searchState.selectedPlatform != MusicPlatform.CLOUD_MUSIC ||
                                searchState.isCloudMusicAvailable
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.cd_search)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (
                            searchState.selectedPlatform != MusicPlatform.CLOUD_MUSIC ||
                            searchState.isCloudMusicAvailable
                        ) {
                            viewModel.performSearch()
                        }
                        focusManager.clearFocus()
                    })
                )
                if (
                    searchState.selectedPlatform == MusicPlatform.CLOUD_MUSIC &&
                    !searchState.isCloudMusicAvailable
                ) {
                    Text(
                        text = stringResource(R.string.netease_login_required_metadata),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
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
                            text = { Text(musicPlatformLabel(platform)) }
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
                                                    context = context,
                                                    data = songResult.coverUrl?.replaceFirst("http://", "https://"),
                                                    offlineMode = offlineMode
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
    lyrics: List<LyricEntry>,
    lyricOffsetMs: Long,
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    progressInfoSegments: List<NowPlayingProgressInfoSegment>,
    seekEnabled: Boolean,
    useWideLandscapeLayout: Boolean,
    onPreviewPositionChange: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val delayedPlaybackWaiting = rememberDelayedPlaybackWaiting(isPlaybackWaiting)
    val context = LocalContext.current
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsStateWithLifecycle()
    val latestOnPreviewPositionChange by rememberUpdatedState(onPreviewPositionChange)
    val lyricSeekHaptic = rememberLyricSeekHapticFeedback(
        lyrics = lyrics,
        lyricOffsetMs = lyricOffsetMs
    )
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
                    val previewPosition = newPercentage * durationMs
                    isUserDraggingSlider = true
                    sliderPosition = previewPosition
                    lyricSeekHaptic.onSeekMove(previewPosition.toLong())
                },
                onValueChangeStarted = { startPercentage ->
                    val previewPosition = startPercentage * durationMs
                    isUserDraggingSlider = true
                    sliderPosition = previewPosition
                    lyricSeekHaptic.onSeekStart(previewPosition.toLong())
                    context.performHapticFeedback(HapticFeedbackEffect.Click)
                },
                onValueChangeFinished = {
                    val previewTarget = sliderPosition.toLong()
                    pendingSeekPreviewPositionMs = previewTarget
                    PlayerManager.seekTo(previewTarget)
                    isUserDraggingSlider = false
                    lyricSeekHaptic.onSeekEnd()
                    context.performHapticFeedback(HapticFeedbackEffect.Confirm)
                },
                onValueChangeCanceled = {
                    sliderPosition = currentPosition.toFloat()
                    pendingSeekPreviewPositionMs = null
                    isUserDraggingSlider = false
                    lyricSeekHaptic.onSeekEnd()
                },
                isPlaying = isPlaying,
                enabled = seekEnabled,
                isPlaybackWaiting = delayedPlaybackWaiting
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
    isPlaying: Boolean,
    playbackSpeed: Float,
    onLyricClick: (LyricEntry) -> Unit,
    onLyricLongClick: (LyricEntry) -> Unit,
    translatedLyrics: List<LyricEntry>? = null
) {
    val currentPosition by PlayerManager.playbackPositionFlow.collectAsStateWithLifecycle()
    val effectivePositionMs = previewPositionOverrideMs ?: currentPosition
    SyncedLyricsView(
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
        onLyricLongClick = onLyricLongClick,
        translatedLyrics = translatedLyrics,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
        interpolatePlaybackPosition = true,
        visualEffectsEnabled = false,
        smoothActiveLineProgress = false
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
                            PlayerManager.updateSongLyricsAndTranslation(
                                originalSong,
                                lyricsText,
                                translatedLyricsText
                            )
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

@Composable
private fun musicPlatformLabel(platform: MusicPlatform): String {
    return when (platform) {
        MusicPlatform.CLOUD_MUSIC -> stringResource(R.string.platform_netease_short)
        MusicPlatform.QQ_MUSIC -> stringResource(R.string.settings_qq_music)
    }
}
