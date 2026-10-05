package moe.ouom.neriplayer.ui.screen.nowplaying

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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying/NowPlayingScreen
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.platform.comments.resolveCommentSource
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadTask
import moe.ouom.neriplayer.core.download.presentation.shouldHideRemoteDownloadAction
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.forSource
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.identity.isSyncableRemoteSong
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.identity.playbackVisualKeyAliases
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.platform.youtube.media.isYouTubeMusicSong
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScaleTarget
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.settings.appearance.ThemeDefaults
import moe.ouom.neriplayer.lyrics.offset.resolveEffectiveLyricOffsetMs
import moe.ouom.neriplayer.ui.component.local.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.LyricShareSheet
import moe.ouom.neriplayer.ui.component.comment.CommentSheet
import moe.ouom.neriplayer.ui.component.playback.PlaybackControlIndicator
import moe.ouom.neriplayer.ui.component.playback.scaleButtonSize
import moe.ouom.neriplayer.ui.component.playback.scaleIconSize
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import moe.ouom.neriplayer.ui.component.playback.SleepTimerDialog
import moe.ouom.neriplayer.ui.component.playback.resolvePlaybackWaiting
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.theme.LocalNeriTargetColorScheme
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricSeekPosition
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.lyrics.LyricsScreen
import moe.ouom.neriplayer.ui.screen.lyrics.isSystemPowerSaveMode
import moe.ouom.neriplayer.ui.screen.lyrics.LyricsSecondaryLineMode
import moe.ouom.neriplayer.ui.screen.lyrics.hasDisplayableLyricTranslation
import moe.ouom.neriplayer.ui.screen.lyrics.resolveEffectivePhoneticLyrics
import moe.ouom.neriplayer.ui.screen.lyrics.resolveLyricsSecondaryLineMode
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverActionToolbar
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverPanel
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverPreviewHost
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingCoverToolbarLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarStatus
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverTopBar
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverBackButton
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverTopActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingPhoneTopActionButtonSize
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingLyricsToolbarAction
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingEmbeddedLyricStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingEmbeddedLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingEmbeddedLyricsContent
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLeadingControls
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLeadingProgress
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricPlayback
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingTrailingControls
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingCoverSource
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.buildNowPlayingSyncedLyricContent
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingCoverPanelModifier
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.rememberNowPlayingCoverOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingCoverOwnerKey
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingCoverRequestUrl
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.shouldShowNowPlayingEmbeddedLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.rememberNowPlayingLyricsLoadOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsRefreshVersions
import moe.ouom.neriplayer.ui.screen.playback.nextFavoriteStateAfterTap
import moe.ouom.neriplayer.ui.screen.playback.resolveListenTogetherProgressSeekEnabled
import moe.ouom.neriplayer.util.media.saveCoverToPictures
import kotlin.time.Duration.Companion.milliseconds

private const val LyricsPageTransitionDurationMs = 300
private const val CoverSourceBadgeRevealBufferMs = 120
private const val CoverSourceBadgeRevealDelayMs =
    LyricsPageTransitionDurationMs + CoverSourceBadgeRevealBufferMs
private const val HighUiDensityScaleThreshold = 1.1f
private const val CompactNowPlayingPortraitMaxHeightDp = 600f
private const val PlaybackActionToolbarItemCount = 5
private val PlaybackActionToolbarMinimumTouchTarget = 48.dp
private val PlaybackActionToolbarSmallSlotThreshold = 40.dp
private val NowPlayingMainControlsMinimumSpacing = 4.dp

internal enum class NowPlayingWideLyricsMode {
    NO_LYRICS,
    ADVANCED,
    SYNCED
}

internal enum class NowPlayingLyricsSharedTransitionElement(
    val key: String
) {
    BACK("btn_back"),
    COVER("cover_image"),
    ARTIST("song_artist"),
    PROGRESS("progress_bar"),
    PREVIOUS("player_previous"),
    PLAY("play_button"),
    NEXT("player_next")
}

internal fun resolveNowPlayingWideLyricsMode(
    hasLyrics: Boolean,
    advancedLyricsEnabled: Boolean
): NowPlayingWideLyricsMode = when {
    !hasLyrics -> NowPlayingWideLyricsMode.NO_LYRICS
    advancedLyricsEnabled -> NowPlayingWideLyricsMode.ADVANCED
    else -> NowPlayingWideLyricsMode.SYNCED
}

internal fun shouldUseCompactNowPlayingPortraitLayout(
    isLandscape: Boolean,
    availableHeightDp: Float,
    uiDensityScale: Float
): Boolean {
    if (isLandscape) {
        return false
    }
    return uiDensityScale >= HighUiDensityScaleThreshold ||
            (availableHeightDp > 0f && availableHeightDp <= CompactNowPlayingPortraitMaxHeightDp)
}

internal fun shouldShowNowPlayingCoverLyrics(
    coverLyricsEnabled: Boolean,
    useCompactPortraitLayout: Boolean
): Boolean = coverLyricsEnabled && !useCompactPortraitLayout

internal fun shouldUseNowPlayingToolbarDock(
    toolbarDockEnabled: Boolean,
    useCompactPortraitLayout: Boolean,
    controlsAtBottom: Boolean = false
): Boolean = toolbarDockEnabled && !useCompactPortraitLayout && !controlsAtBottom

internal data class PlaybackActionToolbarLayout(
    val horizontalPadding: Dp,
    val minimumInteractiveComponentSize: Dp,
    val iconSize: Dp,
    val useEqualWidthSlots: Boolean
)

internal fun resolvePlaybackActionToolbarLayout(
    availableWidth: Dp,
    preferredHorizontalPadding: Dp,
    defaultIconSize: Dp,
    preferredMinimumTouchTarget: Dp = PlaybackActionToolbarMinimumTouchTarget
): PlaybackActionToolbarLayout {
    val minimumTouchTarget = preferredMinimumTouchTarget.coerceAtLeast(0.dp)
    val preferredSlotWidth = (
            (availableWidth - preferredHorizontalPadding * 2) / PlaybackActionToolbarItemCount
            ).coerceAtLeast(0.dp)
    if (preferredSlotWidth >= minimumTouchTarget) {
        return PlaybackActionToolbarLayout(
            horizontalPadding = preferredHorizontalPadding,
            minimumInteractiveComponentSize = minimumTouchTarget,
            iconSize = defaultIconSize,
            useEqualWidthSlots = false
        )
    }

    val compactSlotWidth = (availableWidth / PlaybackActionToolbarItemCount).coerceAtLeast(0.dp)
    return PlaybackActionToolbarLayout(
        horizontalPadding = 0.dp,
        minimumInteractiveComponentSize = minOf(
            minimumTouchTarget,
            compactSlotWidth
        ),
        iconSize = if (compactSlotWidth < PlaybackActionToolbarSmallSlotThreshold) {
            18.dp
        } else {
            defaultIconSize
        },
        useEqualWidthSlots = true
    )
}

internal data class NowPlayingMainControlsLayout(
    val secondaryButtonSize: Dp,
    val primaryButtonSize: Dp,
    val spacing: Dp
)

internal fun resolveNowPlayingMainControlsLayout(
    availableWidth: Dp,
    secondaryButtonSize: Dp,
    primaryButtonSize: Dp,
    preferredSpacing: Dp
): NowPlayingMainControlsLayout {
    val gapCount = PlaybackActionToolbarItemCount - 1
    val requestedButtonWidth = secondaryButtonSize * 4 + primaryButtonSize
    val minimumSpacing = minOf(
        NowPlayingMainControlsMinimumSpacing,
        availableWidth / gapCount
    )
    val availableButtonWidth = (availableWidth - minimumSpacing * gapCount)
        .coerceAtLeast(0.dp)
    val buttonScale = if (
        requestedButtonWidth.value > 0f && requestedButtonWidth > availableButtonWidth
    ) {
        (availableButtonWidth.value / requestedButtonWidth.value).coerceIn(0f, 1f)
    } else {
        1f
    }
    val resolvedSecondaryButtonSize = secondaryButtonSize * buttonScale
    val resolvedPrimaryButtonSize = primaryButtonSize * buttonScale
    val maximumSpacing = (
            (availableWidth - resolvedSecondaryButtonSize * 4 - resolvedPrimaryButtonSize) /
                    gapCount
            ).coerceAtLeast(0.dp)
    return NowPlayingMainControlsLayout(
        secondaryButtonSize = resolvedSecondaryButtonSize,
        primaryButtonSize = resolvedPrimaryButtonSize,
        spacing = minOf(preferredSpacing, maximumSpacing)
    )
}

internal fun shouldHideDownloadActionForSong(
    hasLocalDownload: Boolean,
    currentTask: DownloadTask?
): Boolean = shouldHideRemoteDownloadAction(hasLocalDownload, currentTask)

internal fun resolveNowPlayingPlaybackSourceType(
    isLocalSong: Boolean,
    isYouTubeMusicSong: Boolean,
    isFromNeteaseTag: Boolean,
    isFromBiliTag: Boolean,
    currentMediaUrl: String?,
    playbackAudioSource: PlaybackAudioSource?,
    isNeteaseLocalFallback: Boolean = false
): PlaybackSourceType? {
    if (isLocalSong || isNeteaseLocalFallback) return PlaybackSourceType.LOCAL

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

internal fun hasPublishedManagedDownload(
    song: SongItem,
    catalogLookup: (SongItem) -> Boolean = { candidate ->
        GlobalDownloadManager.hasDownloadedSongCached(candidate)
    }
): Boolean = catalogLookup(song)

internal fun hasCachedLocalDownload(song: SongItem): Boolean {
    return hasPublishedManagedDownload(song)
}

private fun seekToLyricSafely(
    positionMs: Long,
    playbackDurationMs: Long,
    songDurationMs: Long
) {
    val knownDurationMs = maxOf(playbackDurationMs, songDurationMs)
    resolveLyricSeekPosition(positionMs, knownDurationMs)?.let(PlayerManager::seekTo)
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun NowPlayingScreen(
    onNavigateUp: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)? = null,
    showLyricsScreen: Boolean,
    onShowLyricsScreenChange: (Boolean) -> Unit,
    onEnterAlbum: (AlbumSummary) -> Unit,
    onEnterArtist: (NeteaseArtistSummary) -> Unit = {},
    onEnterBiliUploader: (BiliUploaderSummary) -> Unit = {},
    onEnterYouTubeCreator: (YouTubeMusicCreatorSummary) -> Unit = {},
    lyricBlurEnabled: Boolean,
    lyricBlurAmount: Float,
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    advancedLyricsEnabled: Boolean = true,
    showCoverSourceBadge: Boolean = true,
    showLyricTranslation: Boolean = true,
    offlineMode: Boolean = false,
    resolvedCoverUrl: String? = null,
    visualCoverUrl: String? = null,
    playbackSongKey: String? = null,
    playbackSongKeyAliases: List<String> = emptyList(),
    visualCoverSongKey: String? = null,
    onPhoneLandscapeBack: (() -> Unit)? = null,
) {
    val coverLyricFontScale = lyricFontScales.coverLyric
    val coverTranslationFontScale = lyricFontScales.coverTranslation
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val isPlaybackControlPlaying by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
    val isAudioRouteMuted by PlayerManager.audioRouteMuteSuppressedFlow.collectAsStateWithLifecycle()
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
    val preferredQualityKeys by PlayerManager.preferredQualityKeys.collectAsStateWithLifecycle()
    val playbackSoundState by PlayerManager.playbackSoundStateFlow.collectAsStateWithLifecycle()
    val settingsRepo = remember { AppContainer.settingsRepo }
    val themeSeedColorHex by settingsRepo.themeSeedColorFlow.collectAsStateWithLifecycle(
        initialValue = ThemeDefaults.DEFAULT_SEED_COLOR_HEX
    )
    val preferWordTimedLyrics by settingsRepo.preferWordTimedLyricsFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val defaultLyricSource by settingsRepo.defaultLyricSourceFlow
        .collectAsStateWithLifecycle(initialValue = PlayerManager.defaultLyricSource)
    val lyricsPreferenceRevision by PlayerManager.lyricsPreferenceRevisionFlow
        .collectAsStateWithLifecycle()
    val targetNowPlayingColorScheme = LocalNeriTargetColorScheme.current
    val targetNowPlayingActiveIconColor = resolveNowPlayingActiveIconColor(
        accentColor = targetNowPlayingColorScheme.primary,
        seedColor = resolveNowPlayingThemeSeedColor(themeSeedColorHex),
        inactiveContentColor = targetNowPlayingColorScheme.onSurface,
        backgroundColor = targetNowPlayingColorScheme.background
    )
    val nowPlayingActiveIconColor = rememberStableNowPlayingActiveContentColor(
        targetColor = targetNowPlayingActiveIconColor
    )
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
    val playbackControlLayoutPreferences by settingsRepo
        .playbackControlLayoutPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = settingsRepo.defaultPlaybackControlLayoutPreferences)
    val nowPlayingCoverLyricsEnabled by settingsRepo
        .nowPlayingCoverLyricsEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val nowPlayingSongTitleMarqueeEnabled by settingsRepo
        .nowPlayingSongTitleMarqueeEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val uiDensityScale by settingsRepo
        .uiDensityScaleFlow
        .collectAsStateWithLifecycle(initialValue = 1.0f)
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
    val kugouLyricDefaultOffsetMs by settingsRepo
        .kugouLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = DEFAULT_KUGOU_LYRIC_OFFSET_MS)
    val lrclibLyricDefaultOffsetMs by settingsRepo
        .lrclibLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = DEFAULT_LRCLIB_LYRIC_OFFSET_MS)
    val amllTtmlLyricDefaultOffsetMs by settingsRepo
        .amllTtmlLyricDefaultOffsetMsFlow
        .collectAsStateWithLifecycle(initialValue = DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS)
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
        playbackAudioSource = currentPlaybackAudioInfo?.source,
        isNeteaseLocalFallback = currentPlaybackAudioInfo?.isNeteaseLocalFallback == true
    )
    val playbackSourceSongKey = currentSong?.let {
        listOf(it.id.toString(), it.album, it.mediaUri.orEmpty(), it.localFilePath.orEmpty())
            .joinToString(separator = "|")
    }
    var playbackSourceType by remember { mutableStateOf<PlaybackSourceType?>(null) }

    val playlists by PlayerManager.playlistsFlow.collectAsStateWithLifecycle()
    val localPlaylistsReady by PlayerManager.localPlaylistsReadyFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val coverAssetRootGeneration by LocalAssetInvalidationBus.rootGenerationFlow
        .collectAsStateWithLifecycle()
    val coverAssetSongRevisionKey = remember(currentSong) {
        currentSong?.stableKey().orEmpty()
    }
    val coverAssetSongRevisionFlow = remember(coverAssetSongRevisionKey) {
        LocalAssetInvalidationBus.revisionFlow(coverAssetSongRevisionKey)
    }
    val coverAssetSongRevision by coverAssetSongRevisionFlow.collectAsStateWithLifecycle(
        initialValue = LocalAssetInvalidationBus.currentSongRevision(
            coverAssetSongRevisionKey
        )
    )
    val downloadedLyricsRefreshVersion by
    ManagedDownloadStorage.lyricsRefreshVersion.collectAsStateWithLifecycle()
    val currentSongVisualKey = currentSong?.playbackVisualKey()
    val coverSongKey = resolveNowPlayingCoverOwnerKey(
        currentSongKey = currentSongVisualKey,
        parentSongKey = playbackSongKey
    )
    val coverSongKeyAliases = if (currentSongVisualKey != null) {
        currentSong?.playbackVisualKeyAliases().orEmpty()
    } else {
        playbackSongKeyAliases
    }
    val currentCoverUrl = resolveNowPlayingCoverRequestUrl(
        resolvedCoverUrl = resolvedCoverUrl,
        resolvedCoverSongKey = playbackSongKey,
        resolvedCoverOwnerRequired = playbackSongKey == null && currentSongVisualKey != null,
        visualCoverUrl = visualCoverUrl,
        visualCoverSongKey = visualCoverSongKey,
        currentSongKey = coverSongKey
    )
    val actualCoverUrl = currentCoverUrl
    val coverOwner = rememberNowPlayingCoverOwner()

    // 点击即切换, 回流后撤销覆盖
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
    val queueDisplayRevision by PlayerManager.currentQueueDisplayRevisionFlow.collectAsStateWithLifecycle()
    val queueDisplayState = remember(queue, currentSong, shuffleEnabled, queueDisplayRevision) {
        PlayerManager.currentQueueDisplaySnapshot()
    }
    val displayedQueueItems = queueDisplayState.items
    val displayedQueue = remember(displayedQueueItems) { displayedQueueItems.map { it.song } }
    val currentIndexInDisplay = queueDisplayState.currentDisplayIndex

    var showAddSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showCoverPageSourceBadge by remember { mutableStateOf(false) }
    var animateCoverPageSourceBadge by remember { mutableStateOf(false) }
    var previousLyricsScreenState by remember { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }
    var startMoreOptionsWithLyricBehavior by remember { mutableStateOf(false) }
    var showCommentSheet by remember { mutableStateOf(false) }
    var showQualitySwitchDialog by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 评论来源只由「逻辑音源」决定 (平台 + 原始资源 id), 与最终播放地址无关 (§3/§49.2)
    val commentSource = remember(currentSong) { resolveCommentSource(currentSong) }

    // 歌曲切到不支持评论的音源时一并收起面板标记, 否则后续歌曲又能取到评论时,
    // 面板会在没有任何点击的情况下自己重新弹出来 (§8/§48)
    LaunchedEffect(commentSource) {
        if (commentSource == null) {
            showCommentSheet = false
        }
    }

    // Snackbar状态
    val snackbarHostState = remember { SnackbarHostState() }
    var detailSong by remember { mutableStateOf<SongItem?>(null) }
    var pendingSyncConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingSyncConfirmLabel by remember { mutableStateOf("") }

    val screenScope = rememberCoroutineScope()

    val downloadCurrentCover: () -> Unit = {
        val song = currentSong
        if (song == null || actualCoverUrl.isNullOrBlank()) {
            screenScope.launch {
                snackbarHostState.showNeriSnackbar(
                    composeResources.getString(CoreCommonR.string.cover_download_unavailable)
                )
            }
        } else {
            screenScope.launch {
                saveCoverToPictures(
                    context = context,
                    imageUrl = actualCoverUrl,
                    suggestedName = "${song.displayArtist()} - ${song.displayName()} 封面"
                ).onSuccess { fileName ->
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(CoreCommonR.string.cover_download_success, fileName)
                    )
                }.onFailure { error ->
                    val errorMessage =
                        error.message ?: composeResources.getString(CoreCommonR.string.download_failed)
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(CoreCommonR.string.cover_download_failed, errorMessage)
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
                snackbarHostState.showNeriSnackbar(
                    composeResources.getString(CoreCommonR.string.cover_download_permission_required)
                )
            }
        }
    }

    val requestCoverDownload: () -> Unit = {
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

    val currentLyricSourceKey = currentSong?.stableKey()
    val lyricsLoadOwner = rememberNowPlayingLyricsLoadOwner(
        request = NowPlayingLyricsLoadRequest(context, currentSong, currentMediaUrl,
            preferWordTimedLyrics, defaultLyricSource, cachedPreferredLyrics = null),
        versions = NowPlayingLyricsRefreshVersions(lyricsPreferenceRevision,
            downloadPresenceVersion, downloadedLyricsRefreshVersion)
    )
    val loadedLyricsState = lyricsLoadOwner.state
    val secondaryLyricsResolved = lyricsLoadOwner.secondaryResolved
    val lyrics = loadedLyricsState.lyrics
    val translatedLyrics = loadedLyricsState.translatedLyrics
    val rawLyricsText = loadedLyricsState.rawLyrics
    val rawTranslatedLyricsText = loadedLyricsState.rawTranslatedLyrics
    val rawPhoneticLyricsText = loadedLyricsState.rawPhoneticLyrics
    val remotePhoneticLyrics = loadedLyricsState.phoneticLyrics
    val plainLyrics = loadedLyricsState.plainLyrics
    val plainTranslatedLyrics = loadedLyricsState.plainTranslatedLyrics
    val embeddedPhoneticLyrics = loadedLyricsState.embeddedPhoneticLyrics
    val loadedPreferredLyricSource = loadedLyricsState.preferredSource
    val nowPlayingViewModel: NowPlayingViewModel = viewModel()
    var artistPickerCandidates by remember { mutableStateOf<List<NeteaseArtistSummary>>(emptyList()) }
    var youtubeCreatorPickerCandidates by remember {
        mutableStateOf<List<YouTubeMusicCreatorSummary>>(emptyList())
    }
    var resolvingArtistNavigation by remember { mutableStateOf(false) }
    var resolvingBiliUploader by remember { mutableStateOf(false) }
    var resolvingYouTubeCreator by remember { mutableStateOf(false) }

    fun openResolvedArtist(artist: NeteaseArtistSummary) {
        onEnterArtist(artist)
        onNavigateUp()
    }

    fun openResolvedBiliUploader(uploader: BiliUploaderSummary) {
        onEnterBiliUploader(uploader)
        onNavigateUp()
    }

    fun openResolvedYouTubeCreator(creator: YouTubeMusicCreatorSummary) {
        onEnterYouTubeCreator(creator)
        onNavigateUp()
    }

    fun openArtistCandidates(artists: List<NeteaseArtistSummary>) {
        val distinctArtists = artists.distinctBy { it.id }
        when (distinctArtists.size) {
            0 -> screenScope.launch {
                snackbarHostState.showNeriSnackbar(composeResources.getString(CoreCommonR.string.artist_not_available))
            }

            1 -> openResolvedArtist(distinctArtists.first())
            else -> artistPickerCandidates = distinctArtists
        }
    }

    fun openYouTubeCreatorCandidates(creators: List<YouTubeMusicCreatorSummary>) {
        val distinctCreators = creators
            .filter { it.browseId.isNotBlank() && it.title.isNotBlank() }
            .distinctBy(YouTubeMusicCreatorSummary::browseId)
        when (distinctCreators.size) {
            0 -> screenScope.launch {
                snackbarHostState.showNeriSnackbar(
                    composeResources.getString(CoreCommonR.string.youtube_creator_not_available)
                )
            }

            1 -> openResolvedYouTubeCreator(distinctCreators.first())
            else -> youtubeCreatorPickerCandidates = distinctCreators
        }
    }

    val openCurrentNeteaseArtist: () -> Unit = {
        val song = currentSong
        if (song != null && isNeteaseArtistNavigationSource(song) && !resolvingArtistNavigation) {
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
                        snackbarHostState.showNeriSnackbar(composeResources.getString(CoreCommonR.string.artist_not_available))
                    }
                }
            )
        }
    }

    val openCurrentBiliUploader: () -> Unit = {
        val song = currentSong
        if (song != null && isBiliUploaderNavigationSource(song) && !resolvingBiliUploader) {
            resolvingBiliUploader = true
            nowPlayingViewModel.resolveBiliUploader(
                song = song,
                onResult = { uploader ->
                    resolvingBiliUploader = false
                    if (currentSong?.sameIdentityAs(song) == true) {
                        openResolvedBiliUploader(uploader)
                    }
                },
                onUnavailable = {
                    resolvingBiliUploader = false
                    screenScope.launch {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(CoreCommonR.string.bili_uploader_owner_unavailable)
                        )
                    }
                },
                onError = { error ->
                    resolvingBiliUploader = false
                    screenScope.launch {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(
                                CoreCommonR.string.bili_uploader_open_failed,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
                }
            )
        }
    }

    val openCurrentYouTubeCreator: () -> Unit = {
        val song = currentSong
        if (song != null && isYouTubeMusicArtistNavigationSource(song) && !resolvingYouTubeCreator) {
            resolvingYouTubeCreator = true
            nowPlayingViewModel.resolveYouTubeMusicCreators(
                song = song,
                onResult = { creators ->
                    resolvingYouTubeCreator = false
                    if (currentSong?.sameIdentityAs(song) == true) {
                        openYouTubeCreatorCandidates(creators)
                    }
                },
                onError = { error ->
                    resolvingYouTubeCreator = false
                    screenScope.launch {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(
                                CoreCommonR.string.youtube_creator_open_failed,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
                }
            )
        }
    }

    val openCurrentArtist: () -> Unit = {
        when {
            currentSong?.let(::isBiliUploaderNavigationSource) == true -> {
                openCurrentBiliUploader()
            }

            currentSong?.let(::isYouTubeMusicArtistNavigationSource) == true -> {
                openCurrentYouTubeCreator()
            }

            else -> {
                openCurrentNeteaseArtist()
            }
        }
    }

    val phoneticLyrics =
        remember(rawPhoneticLyricsText, remotePhoneticLyrics, embeddedPhoneticLyrics) {
            resolveEffectivePhoneticLyrics(rawPhoneticLyricsText, remotePhoneticLyrics, embeddedPhoneticLyrics)
        }
    val hasTranslation = remember(rawTranslatedLyricsText, translatedLyrics, lyrics) {
        hasDisplayableLyricTranslation(rawTranslatedLyricsText, translatedLyrics, lyrics)
    }
    val secondaryMode = resolveLyricsSecondaryLineMode(
        showSecondaryLine = showLyricTranslation,
        preferPhonetic = lyricTranslationUsePhonetic,
        hasTranslation = hasTranslation,
        hasPhonetic = phoneticLyrics.any { it.text.isNotBlank() }
    )
    val showSecondaryLyrics = secondaryMode != LyricsSecondaryLineMode.NONE
    val usePhoneticTranslation = secondaryMode == LyricsSecondaryLineMode.PHONETIC
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
                delay((CoverSourceBadgeRevealDelayMs.toLong()).milliseconds)
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
                delay(250.milliseconds)
                playbackSourceType = null
            }
        }
    }

    // 当仓库回流或歌曲切换时, 撤销本地乐观覆盖, 用真实状态对齐
    LaunchedEffect(playlists, currentSong?.id) { favOverride = null }

    fun launchWithLocalSyncWarning(
        song: SongItem?,
        actionLabel: String,
        warnForLocalSync: Boolean = true,
        action: () -> Unit
    ) {
        if (warnForLocalSync && song?.isSyncableRemoteSong(context) == false) {
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
    val phoneLandscape = isNowPlayingPhoneLandscape(isLandscape, configuration.smallestScreenWidthDp)
    val tabletPortrait = isNowPlayingTabletPortrait(isLandscape, configuration.smallestScreenWidthDp)
    val windowWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val isWideLayout = windowWidthDp >= 480.dp
    val useWideLandscapeLayout = isLandscape && (isWideLayout || phoneLandscape)
    val useCompactPortraitLayout = !tabletPortrait && shouldUseCompactNowPlayingPortraitLayout(
        isLandscape = isLandscape,
        availableHeightDp = windowHeightDp.value,
        uiDensityScale = uiDensityScale
    )
    val showCoverPageLyrics = shouldShowNowPlayingCoverLyrics(
        coverLyricsEnabled = nowPlayingCoverLyricsEnabled,
        useCompactPortraitLayout = useCompactPortraitLayout
    )
    val nowPlayingControlsAtBottom =
        playbackControlLayoutPreferences.nowPlayingPlacement.placesControlsAtBottom
    val nowPlayingProgressAtBottom =
        playbackControlLayoutPreferences.nowPlayingPlacement.placesProgressAtBottom
    val useNowPlayingToolbarDock = shouldUseNowPlayingToolbarDock(
        toolbarDockEnabled = nowPlayingToolbarDockEnabled,
        useCompactPortraitLayout = useCompactPortraitLayout,
        controlsAtBottom = nowPlayingControlsAtBottom
    )
    val controlBaseSizes = resolveNowPlayingControlBaseSizes(
        phoneLandscape = phoneLandscape,
        wideLandscape = useWideLandscapeLayout,
        compactWideLayout = windowWidthDp < 720.dp,
        compactPortrait = useCompactPortraitLayout
    )
    val lowPowerLyricsRendering = remember(context, showLyricsScreen) { context.isSystemPowerSaveMode() }

    // 歌词偏移 (平台 + 用户自定义)
    val totalOffset = resolveEffectiveLyricOffsetMs(
        lyricSource = currentSong?.matchedLyricSource,
        cloudMusicDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
        qqMusicDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
        userLyricOffsetMs = currentSong?.userLyricOffsetMs ?: 0L,
        kugouDefaultOffsetMs = kugouLyricDefaultOffsetMs,
        lrclibDefaultOffsetMs = lrclibLyricDefaultOffsetMs,
        amllTtmlDefaultOffsetMs = amllTtmlLyricDefaultOffsetMs,
        preferredLyricSource = loadedPreferredLyricSource,
    )
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
                onDismiss = { lyricShareInitialLine = null },
                onShowMessage = { message ->
                    screenScope.launch {
                        snackbarHostState.showNeriSnackbar(message)
                    }
                }
            )
        }
    }

    NowPlayingCoverPreviewHost(
        owner = coverOwner,
        previewSessionKey = playbackSourceSongKey,
        coverUrl = actualCoverUrl,
        songName = currentSong?.displayName().orEmpty(),
        offlineMode = offlineMode,
        onDownload = requestCoverDownload
    )

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        BackHandler(enabled = useWideLandscapeLayout && showLyricsScreen) {
            onShowLyricsScreenChange(false)
        }
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
                    if (shouldUseStandaloneNowPlayingLyricsPage(isLyricsMode, useWideLandscapeLayout)) {
                        // 歌词全屏页面
                        LyricsScreen(
                            lyrics = lyrics,
                            rawLyrics = rawLyricsText,
                            rawTranslatedLyrics = rawTranslatedLyricsText,
                            rawPhoneticLyrics = rawPhoneticLyricsText,
                            lyricBlurEnabled = lyricBlurEnabled,
                            lyricBlurAmount = lyricBlurAmount,
                            lyricFontScales = lyricFontScales,
                            onEnterAlbum = onEnterAlbum,
                            onOpenCurrentArtist = openCurrentArtist,
                            onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource,
                            onLyricFontScaleChange = onLyricFontScaleChange,
                            onExitNowPlaying = onNavigateUp,
                            onNavigateBack = { onShowLyricsScreenChange(false) },
                            onSeekTo = { position ->
                                seekToLyricSafely(
                                    positionMs = position,
                                    playbackDurationMs = durationMs,
                                    songDurationMs = currentSong?.durationMs ?: 0L
                                )
                            },
                            progressSeekEnabled = playbackProgressSeekEnabled,
                            advancedLyricsEnabled = advancedLyricsEnabled,
                            translatedLyrics = translatedLyrics,
                            phoneticLyrics = phoneticLyrics,
                            lyricSourceKey = currentLyricSourceKey,
                            secondaryLyricsResolved = secondaryLyricsResolved,
                            lyricOffsetMs = totalOffset,
                            showLyricTranslation = showLyricTranslation,
                            lyricTranslationUsePhonetic = lyricTranslationUsePhonetic,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@AnimatedContent,
                            offlineMode = offlineMode
                        )
                    } else {
                        // 播放页面
                        val lyricFontPage = resolveNowPlayingLyricFontPage(isLyricsMode)
                        val nowPlayingControlSize = resolveNowPlayingPageControlSize(
                            playbackControlLayoutPreferences, isLyricsMode
                        )
                        val nowPlayingTopActionButtonSize = nowPlayingControlSize.scaleButtonSize(48.dp)
                        val nowPlayingTopActionIconSize = nowPlayingControlSize.scaleIconSize(24.dp)
                        val nowPlayingTopBarHeight = maxOf(56.dp, nowPlayingTopActionButtonSize)
                        val secondaryControlButtonSize = nowPlayingControlSize.scaleButtonSize(
                            controlBaseSizes.secondaryButtonSize
                        )
                        val primaryControlButtonSize = nowPlayingControlSize.scaleButtonSize(
                            controlBaseSizes.primaryButtonSize
                        )
                        val controlButtonSpacing = controlBaseSizes.spacing * nowPlayingControlSize.scale
                        val nowPlayingToolbarIconSize = nowPlayingControlSize.scaleIconSize(
                            if (useWideLandscapeLayout) 22.dp else 20.dp
                        )
                        val nowPlayingMainControlIconSize = nowPlayingControlSize.scaleIconSize(controlBaseSizes.iconSize)
                        val nowPlayingToolbarMinimumTouchTarget = nowPlayingControlSize.scaleButtonSize(
                            PlaybackActionToolbarMinimumTouchTarget
                        )
                        val onTopBarNavigateUp: () -> Unit = when {
                            phoneLandscape -> onPhoneLandscapeBack ?: onNavigateUp
                            isLyricsMode -> { { onShowLyricsScreenChange(false) } }
                            else -> onNavigateUp
                        }
                        val horizontalPadding = if (isLandscape) 16.dp else 20.dp
                        val verticalPadding = if (isLandscape) 8.dp else 12.dp
                        var contentModifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 60) onNavigateUp() }
                            }
                        if (useWideLandscapeLayout) {
                            contentModifier = contentModifier.testTag(
                                if (isLyricsMode) "nowPlayingLandscapeLyricsPage" else "nowPlayingLandscapeCoverPage"
                            )
                        }

                        // 横屏仍可左右切页，进度条会先消费自己的拖动手势
                        if (useWideLandscapeLayout && (isLyricsMode || lyrics.isNotEmpty())) {
                            contentModifier = contentModifier.pointerInput(isLyricsMode, lyrics) {
                                var horizontalDragDistance = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { horizontalDragDistance = 0f }
                                ) { _, dragAmount ->
                                    horizontalDragDistance += dragAmount
                                    if (isLyricsMode && horizontalDragDistance > 60f) {
                                        onShowLyricsScreenChange(false)
                                    } else if (!isLyricsMode && horizontalDragDistance < -60f) {
                                        onShowLyricsScreenChange(true)
                                    }
                                }
                            }
                        } else if (!useWideLandscapeLayout && lyrics.isNotEmpty()) {
                            contentModifier = contentModifier.pointerInput(lyrics) {
                                detectHorizontalDragGestures { _, dragAmount ->
                                    if (dragAmount < -20) onShowLyricsScreenChange(true)
                                }
                            }
                        }

                        val mainPlaybackControls: @Composable () -> Unit = {
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                val controlsLayout = resolveNowPlayingMainControlsLayout(
                                    availableWidth = maxWidth,
                                    secondaryButtonSize = secondaryControlButtonSize,
                                    primaryButtonSize = primaryControlButtonSize,
                                    preferredSpacing = controlButtonSpacing
                                )
                                val secondaryIconSize = (
                                        nowPlayingMainControlIconSize *
                                                (controlsLayout.secondaryButtonSize.value /
                                                        secondaryControlButtonSize.value)
                                        ).coerceAtLeast(18.dp)
                                val primaryIconSize = (
                                        nowPlayingMainControlIconSize *
                                                (controlsLayout.primaryButtonSize.value /
                                                        primaryControlButtonSize.value)
                                        ).coerceAtLeast(18.dp)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(controlsLayout.spacing),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HapticIconButton(
                                        onClick = { PlayerManager.setShuffle(!shuffleEnabled) },
                                        modifier = Modifier.size(controlsLayout.secondaryButtonSize)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Shuffle,
                                            contentDescription = stringResource(CoreCommonR.string.player_shuffle),
                                            modifier = Modifier.size(secondaryIconSize),
                                            tint = if (shuffleEnabled) {
                                                nowPlayingActiveIconColor
                                            } else {
                                                LocalContentColor.current
                                            }
                                        )
                                    }

                                    HapticIconButton(
                                        onClick = { PlayerManager.previous() },
                                        modifier = Modifier
                                            .sharedElement(
                                                rememberSharedContentState(
                                                    key = NowPlayingLyricsSharedTransitionElement.PREVIOUS.key
                                                ),
                                                animatedVisibilityScope = this@AnimatedContent
                                            )
                                            .size(controlsLayout.secondaryButtonSize)
                                    ) {
                                        Icon(
                                            Icons.Outlined.SkipPrevious,
                                            contentDescription = stringResource(CoreCommonR.string.player_previous),
                                            modifier = Modifier.size(secondaryIconSize)
                                        )
                                    }

                                    HapticFilledIconButton(
                                        onClick = { PlayerManager.togglePlayPause() },
                                        enabled = !usbPlaybackPreparing,
                                        modifier = Modifier
                                            .sharedElement(
                                                rememberSharedContentState(
                                                    key = NowPlayingLyricsSharedTransitionElement.PLAY.key
                                                ),
                                                animatedVisibilityScope = this@AnimatedContent
                                            )
                                            .size(controlsLayout.primaryButtonSize)
                                    ) {
                                        PlaybackControlIndicator(
                                            isPlaying = isPlaybackControlPlaying,
                                            isPlaybackWaiting = isPlaybackWaiting,
                                            isAudioRouteMuted = isAudioRouteMuted,
                                            playContentDescription = stringResource(CoreCommonR.string.player_play),
                                            pauseContentDescription = stringResource(CoreCommonR.string.player_pause),
                                            restoreVolumeContentDescription = stringResource(CoreCommonR.string.player_restore_volume),
                                            waitingContentDescription = stringResource(CoreCommonR.string.player_waiting),
                                            modifier = Modifier.size(primaryIconSize),
                                            progressIndicatorSize = primaryIconSize
                                        )
                                    }

                                    HapticIconButton(
                                        onClick = { PlayerManager.next() },
                                        modifier = Modifier
                                            .sharedElement(
                                                rememberSharedContentState(
                                                    key = NowPlayingLyricsSharedTransitionElement.NEXT.key
                                                ),
                                                animatedVisibilityScope = this@AnimatedContent
                                            )
                                            .size(controlsLayout.secondaryButtonSize)
                                    ) {
                                        Icon(
                                            Icons.Outlined.SkipNext,
                                            contentDescription = stringResource(CoreCommonR.string.player_next),
                                            modifier = Modifier.size(secondaryIconSize)
                                        )
                                    }

                                    HapticIconButton(
                                        onClick = { PlayerManager.cycleRepeatMode() },
                                        modifier = Modifier.size(controlsLayout.secondaryButtonSize)
                                    ) {
                                        Icon(
                                            imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                                                Icons.Filled.RepeatOne
                                            } else {
                                                Icons.Outlined.Repeat
                                            },
                                            contentDescription = stringResource(CoreCommonR.string.player_repeat),
                                            modifier = Modifier.size(secondaryIconSize),
                                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) {
                                                nowPlayingActiveIconColor
                                            } else {
                                                LocalContentColor.current
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        val nowPlayingProgressSection: @Composable () -> Unit = {
                            NowPlayingProgressSection(
                                songKey = currentSong?.stableKey(),
                                durationMs = durationMs,
                                lyrics = plainLyrics,
                                lyricOffsetMs = totalOffset,
                                isPlaying = isPlaying,
                                isPlaybackWaiting = isPlaybackWaiting,
                                playbackSpeed = playbackSoundState.speed,
                                progressInfoSegments = nowPlayingVisibleProgressInfoSegments(progressInfoSegments, phoneLandscape),
                                seekEnabled = playbackProgressSeekEnabled,
                                activeContentColor = targetNowPlayingActiveIconColor,
                                useWideLandscapeLayout = useWideLandscapeLayout,
                                onPreviewPositionChange = { previewPositionOverrideMs = it },
                                tabletLandscape = useWideLandscapeLayout && !phoneLandscape,
                                progressRowModifier = Modifier
                                    .sharedBounds(
                                        rememberSharedContentState(
                                            key = NowPlayingLyricsSharedTransitionElement.PROGRESS.key
                                        ),
                                        animatedVisibilityScope = this@AnimatedContent
                                    )
                                    .zIndex(1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                            )
                        }

                        val coverCommentAvailable = commentSource != null
                        val coverLyricsAvailable = lyrics.isNotEmpty()
                        val onFavoriteCover: () -> Unit = {
                            val song = currentSong
                            if (song != null) {
                                val willFav = nextFavoriteStateAfterTap(isFavorite)
                                launchWithLocalSyncWarning(
                                    song = song,
                                    actionLabel = composeResources.getString(CoreCommonR.string.favorite_add),
                                    warnForLocalSync = willFav
                                ) {
                                    favOverride = willFav
                                    PlayerManager.toggleCurrentFavorite()
                                }
                            }
                        }
                        val onCoverPreviewUnavailable: () -> Unit = {
                            screenScope.launch {
                                snackbarHostState.showNeriSnackbar(
                                    composeResources.getString(CoreCommonR.string.cover_preview_unavailable)
                                )
                            }
                        }
                        val onEmbeddedLyricClick: (LyricEntry) -> Unit = { entry ->
                            seekToLyricSafely(
                                positionMs = entry.startTimeMs,
                                playbackDurationMs = durationMs,
                                songDurationMs = currentSong?.durationMs ?: 0L
                            )
                        }
                        val onEmbeddedLyricLongClick: (LyricEntry) -> Unit = { entry ->
                            lyricShareInitialLine = entry
                        }
                        val onCoverComment: () -> Unit = { showCommentSheet = true }
                        val onCoverMoreOptions: () -> Unit = {
                            startMoreOptionsWithLyricBehavior = false
                            showMoreOptions = true
                        }
                        val coverToolbarActions = NowPlayingCoverToolbarActions(
                            onQueue = { showQueueSheet = true },
                            onSleepTimer = { showSleepTimerDialog = true },
                            onVolume = { showVolumeSheet = true },
                            onLyrics = resolveNowPlayingLyricsToolbarAction(
                                wideLandscape = useWideLandscapeLayout,
                                onAdjust = {
                                    startMoreOptionsWithLyricBehavior = true
                                    showMoreOptions = true
                                },
                                onSwitchPage = { onShowLyricsScreenChange(!showLyricsScreen) }
                            ),
                            onAddToPlaylist = { showAddSheet = true }
                        )

                        val coverTopBar: @Composable () -> Unit = {
                            NowPlayingCoverTopBar(
                                isFavorite = isFavorite,
                                favoriteEnabled = localPlaylistsReady,
                                commentAvailable = coverCommentAvailable,
                                height = nowPlayingTopBarHeight,
                                buttonSize = nowPlayingTopActionButtonSize,
                                iconSize = nowPlayingTopActionIconSize,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedContent,
                                onNavigateUp = onTopBarNavigateUp,
                                onFavorite = onFavoriteCover,
                                onComment = onCoverComment,
                                onMoreOptions = onCoverMoreOptions,
                                phoneLandscape = phoneLandscape
                            )
                        }
                        val coverBackBar: @Composable () -> Unit = {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(nowPlayingTopBarHeight),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                NowPlayingCoverBackButton(
                                    buttonSize = nowPlayingTopActionButtonSize,
                                    iconSize = nowPlayingTopActionIconSize,
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this@AnimatedContent,
                                    onNavigateUp = onPhoneLandscapeBack ?: onNavigateUp,
                                    phoneLandscape = true
                                )
                            }
                        }
                        val coverTopActions: @Composable () -> Unit = {
                            BoxWithConstraints(Modifier.height(nowPlayingTopBarHeight)) {
                                val actionButtonSize = resolveNowPlayingPhoneTopActionButtonSize(
                                    maxWidth, nowPlayingTopActionButtonSize, coverCommentAvailable
                                )
                                val actionIconSize = (nowPlayingTopActionIconSize *
                                    (actionButtonSize.value / nowPlayingTopActionButtonSize.value)).coerceAtLeast(18.dp)
                                NowPlayingCoverTopActions(
                                    isFavorite = isFavorite,
                                    favoriteEnabled = localPlaylistsReady,
                                    commentAvailable = coverCommentAvailable,
                                    buttonSize = actionButtonSize,
                                    iconSize = actionIconSize,
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this@AnimatedContent,
                                    onFavorite = onFavoriteCover,
                                    onComment = onCoverComment,
                                    onMoreOptions = onCoverMoreOptions,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                        val coverPanel: @Composable (Modifier, Dp?) -> Unit = { modifier, preferredSize ->
                            NowPlayingCoverPanel(
                                owner = coverOwner,
                                source = buildNowPlayingCoverSource(
                                    currentCoverUrl, coverSongKey, coverSongKeyAliases,
                                    downloadPresenceVersion, coverAssetRootGeneration,
                                    coverAssetSongRevision, currentSong
                                ),
                                song = currentSong,
                                previewSessionKey = playbackSourceSongKey,
                                offlineMode = offlineMode,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedContent,
                                showSourceBadge = showCoverPageSourceBadge,
                                animateSourceBadge = animateCoverPageSourceBadge,
                                playbackSourceType = playbackSourceType,
                                onPreviewUnavailable = onCoverPreviewUnavailable,
                                modifier = modifier,
                                preferredSize = preferredSize
                            )
                        }
                        val trackIdentity: @Composable (Boolean) -> Unit = { compact ->
                            NowPlayingTrackIdentity(
                                display = resolveNowPlayingTrackDisplay(currentSong),
                                visible = contentVisible,
                                marqueeEnabled = nowPlayingSongTitleMarqueeEnabled,
                                titleColor = targetNowPlayingColorScheme.onSurface,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedContent,
                                onArtistClick = openCurrentArtist,
                                compact = compact
                            )
                        }
                        val coverToolbar: @Composable (Boolean) -> Unit = { compactHeight ->
                            NowPlayingCoverActionToolbar(
                                spec = resolveNowPlayingCoverToolbarLayoutSpec(
                                    wideLandscape = useWideLandscapeLayout,
                                    compactPortrait = useCompactPortraitLayout,
                                    dockEnabled = useNowPlayingToolbarDock,
                                    iconSize = nowPlayingToolbarIconSize,
                                    minimumTouchTarget = nowPlayingToolbarMinimumTouchTarget,
                                    compactHeight = compactHeight
                                ),
                                status = NowPlayingCoverToolbarStatus(
                                    sleepTimerActive = sleepTimerState.isActive,
                                    lyricsAvailable = coverLyricsAvailable,
                                    lyricsShowing = showLyricsScreen,
                                    activeColor = nowPlayingActiveIconColor
                                ),
                                actions = coverToolbarActions,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedContent
                            )
                        }
                        val embeddedLyricsVisible = shouldShowNowPlayingEmbeddedLyrics(
                            useWideLandscapeLayout, showCoverPageLyrics, coverLyricsAvailable
                        )
                        val embeddedLyricContent = buildNowPlayingSyncedLyricContent(
                            plainLyrics, secondaryPlainLyrics, showSecondaryLyrics,
                            usePhoneticTranslation, currentSong, totalOffset
                        )
                        val embeddedLyricStyle = NowPlayingEmbeddedLyricStyle(
                            fontScale = coverLyricFontScale,
                            translationFontScale = coverTranslationFontScale,
                            blurEnabled = lyricBlurEnabled,
                            blurAmount = lyricBlurAmount
                        )
                        val embeddedLyricPlayback = NowPlayingSyncedLyricPlayback(
                            positionFlow = PlayerManager.playbackPositionFlow,
                            previewPositionMs = previewPositionOverrideMs,
                            isPlaying = isPlaying,
                            speed = playbackSoundState.speed
                        )
                        val embeddedLyricActions = NowPlayingSyncedLyricActions(
                            onClick = onEmbeddedLyricClick,
                            onLongClick = onEmbeddedLyricLongClick
                        )

                        // 手机竖屏保留原来的控件顺序和封面歌词
                        val mainColumnContent: @Composable ColumnScope.() -> Unit = {
                            coverTopBar()
                            Spacer(Modifier.height(8.dp))
                            coverPanel(
                                Modifier.nowPlayingCoverPanelModifier(false, this),
                                null
                            )
                            Spacer(Modifier.height(16.dp))
                            trackIdentity(false)

                            NowPlayingLeadingProgress(
                                nowPlayingProgressAtBottom,
                                useWideLandscapeLayout,
                                nowPlayingProgressSection
                            )
                            NowPlayingLeadingControls(
                                nowPlayingControlsAtBottom,
                                mainPlaybackControls
                            )

                            NowPlayingEmbeddedLyrics(
                                visible = embeddedLyricsVisible,
                                content = embeddedLyricContent,
                                style = embeddedLyricStyle,
                                playback = embeddedLyricPlayback,
                                actions = embeddedLyricActions
                            )

                            // 置底偏好始终以可用内容区的底部为准
                            Spacer(modifier = Modifier.weight(1f))

                            NowPlayingTrailingControls(
                                nowPlayingControlsAtBottom,
                                nowPlayingProgressAtBottom,
                                useWideLandscapeLayout,
                                nowPlayingProgressSection,
                                mainPlaybackControls
                            )

                            coverToolbar(false)
                        }

                        if (useWideLandscapeLayout) {
                            val wideLyricsContent: @Composable (Boolean) -> Unit = { compactHeight ->
                                NowPlayingWideLyrics(
                                    fullPage = isLyricsMode,
                                    compactHeight = compactHeight,
                                    phoneLandscape = phoneLandscape,
                                    availableWidth = windowWidthDp,
                                    content = NowPlayingWideLyricsContent(
                                        lyrics = lyrics,
                                        translatedLyrics = translatedLyrics,
                                        phoneticLyrics = phoneticLyrics,
                                        plainTranslatedLyrics = plainTranslatedLyrics,
                                        rawLyrics = rawLyricsText,
                                        rawTranslatedLyrics = rawTranslatedLyricsText,
                                        synced = embeddedLyricContent
                                    ),
                                    preferences = NowPlayingWideLyricsPreferences(
                                        advancedEnabled = advancedLyricsEnabled,
                                        showSecondary = showSecondaryLyrics,
                                        usePhonetic = usePhoneticTranslation,
                                        fontScales = lyricFontScales,
                                        blurEnabled = lyricBlurEnabled,
                                        blurAmount = lyricBlurAmount
                                    ),
                                    playback = embeddedLyricPlayback,
                                    lowPowerRendering = lowPowerLyricsRendering,
                                    actions = embeddedLyricActions,
                                    onSeekTo = { position ->
                                        seekToLyricSafely(
                                            positionMs = position,
                                            playbackDurationMs = durationMs,
                                            songDurationMs = currentSong?.durationMs ?: 0L
                                        )
                                    }
                                )
                            }
                            Box(modifier = contentModifier) {
                                NowPlayingWideLayout(
                                    controlsAtBottom = nowPlayingControlsAtBottom,
                                    progressAtBottom = nowPlayingProgressAtBottom,
                                    topBar = if (phoneLandscape) coverBackBar else coverTopBar,
                                    cover = { modifier -> coverPanel(modifier, 420.dp) },
                                    identity = trackIdentity,
                                    progress = nowPlayingProgressSection,
                                    controls = mainPlaybackControls,
                                    toolbar = coverToolbar,
                                    lyrics = wideLyricsContent,
                                    modifier = Modifier.fillMaxSize(),
                                    phoneLandscape = phoneLandscape,
                                    phoneTopActions = coverTopActions
                                )
                            }
                        } else if (tabletPortrait) {
                            Box(modifier = contentModifier) {
                                NowPlayingTabletPortraitLayout(
                                    controlsAtBottom = nowPlayingControlsAtBottom,
                                    progressAtBottom = nowPlayingProgressAtBottom,
                                    lyricsVisible = embeddedLyricsVisible,
                                    topBar = coverTopBar,
                                    cover = coverPanel,
                                    identity = { trackIdentity(false) },
                                    progress = nowPlayingProgressSection,
                                    controls = mainPlaybackControls,
                                    toolbar = { coverToolbar(false) },
                                    lyrics = {
                                        NowPlayingEmbeddedLyricsContent(
                                            content = embeddedLyricContent,
                                            style = embeddedLyricStyle,
                                            playback = embeddedLyricPlayback,
                                            actions = embeddedLyricActions,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Column(
                                modifier = contentModifier,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                content = mainColumnContent
                            )
                        }

                        if (showMoreOptions && currentSong != null) {
                            MoreOptionsSheet(
                                viewModel = nowPlayingViewModel,
                                originalSong = currentSong!!,
                                queue = displayedQueue,
                                lyricContent = MoreOptionsLyricContent(
                                    lyrics = lyrics,
                                    translatedLyrics = translatedLyrics,
                                    romanizedLyrics = phoneticLyrics,
                                    hasTranslation = hasTranslation,
                                    hasPhonetic = phoneticLyrics.any { it.text.isNotBlank() }
                                ),
                                navigation = MoreOptionsSheetNavigation(
                                    onDismiss = { showMoreOptions = false },
                                    onShowSongDetails = { detailSong = it },
                                    onEnterAlbum = onEnterAlbum,
                                    onNavigateUp = onNavigateUp,
                                    onShowQualitySwitch = { showQualitySwitchDialog = true },
                                    startWithLyricBehavior = startMoreOptionsWithLyricBehavior
                                ),
                                snackbarHostState = snackbarHostState,
                                fontSettings = MoreOptionsFontSettings(
                                        page = lyricFontPage,
                                    scales = lyricFontScales,
                                    onChange = onLyricFontScaleChange
                                ),
                                biliClient = AppContainer.biliClient,
                                currentPlaybackAudioInfo = currentPlaybackAudioInfo,
                                offlineMode = offlineMode
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

                    if (youtubeCreatorPickerCandidates.isNotEmpty()) {
                        YouTubeMusicCreatorPickerSheet(
                            creators = youtubeCreatorPickerCandidates,
                            onDismiss = { youtubeCreatorPickerCandidates = emptyList() },
                            onSelect = { creator ->
                                youtubeCreatorPickerCandidates = emptyList()
                                openResolvedYouTubeCreator(creator)
                            }
                        )
                    }

                    // 播放队列弹窗
                    if (showQueueSheet) {
                        NowPlayingQueueSheet(
                            displayedQueueItems = displayedQueueItems,
                            currentIndexInDisplay = currentIndexInDisplay,
                            offlineMode = offlineMode,
                            allowQueueReorder = playbackProgressSeekEnabled,
                            onDismissRequest = { showQueueSheet = false },
                            onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource
                        )
                    }

                    // 评论弹窗 (只新增入口与弹窗, 不改动原有播放 UI)
                    if (showCommentSheet && commentSource != null) {
                        CommentSheet(
                            source = commentSource,
                            offlineMode = offlineMode,
                            onDismissRequest = { showCommentSheet = false }
                        )
                    }

                    if (showQualitySwitchDialog && currentPlaybackAudioInfo != null) {
                        NowPlayingQualityOptionsDialog(
                            title = stringResource(CoreCommonR.string.nowplaying_quality_switch_title),
                            selectedKey = currentPlaybackAudioInfo
                                ?.source
                                ?.let(preferredQualityKeys::forSource)
                                ?: currentPlaybackAudioInfo?.qualityKey,
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
                                                    actionLabel = composeResources.getString(CoreCommonR.string.playlist_add_to)
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
                                                CoreCommonR.plurals.nowplaying_song_count_format,
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
                            onDismiss = { detailSong = null },
                            onShowMessage = { message ->
                                screenScope.launch {
                                    snackbarHostState.showNeriSnackbar(message)
                                }
                            }
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
