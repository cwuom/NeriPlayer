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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
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
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import moe.ouom.neriplayer.core.api.bili.resolveBiliVideoSkipTargetOptions
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchRequest
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchConfidence
import moe.ouom.neriplayer.core.api.lyrics.EditableLyricMatchSource
import moe.ouom.neriplayer.core.api.lyrics.RankedEditableLyricMatch
import moe.ouom.neriplayer.core.api.lyrics.defaultEditableLyricMatchSources
import moe.ouom.neriplayer.core.api.lyrics.editableLyricMatchResultComparator
import moe.ouom.neriplayer.core.api.lyrics.hasCollapsedTimedLyricTimeline
import moe.ouom.neriplayer.core.api.lyrics.normalizeLyricMatchText
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.download.shouldHideRemoteDownloadAction
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.metadata.resolveLyricTextForPlayback
import moe.ouom.neriplayer.core.player.metadata.shouldReadManagedDownloadLyrics
import moe.ouom.neriplayer.core.player.playback.BiliVideoSkipPlaybackController
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.forSource
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.core.player.model.PlayerQueueDisplayItem
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.isSyncableRemoteSong
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.settings.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.LYRIC_DEFAULT_OFFSET_STEP_MS
import moe.ouom.neriplayer.data.settings.LyricFontScalePage
import moe.ouom.neriplayer.data.settings.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_DEFAULT_OFFSET_MS
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.normalizeLyricFontScale
import moe.ouom.neriplayer.data.settings.resolveEffectiveLyricOffsetMs
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
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSource
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.LyricShareSheet
import moe.ouom.neriplayer.ui.component.lyrics.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.playback.PlaybackSoundSheet
import moe.ouom.neriplayer.ui.component.playback.SongMetadataSearchContent
import moe.ouom.neriplayer.ui.component.playback.NowPlayingCoverPreviewDialog
import moe.ouom.neriplayer.ui.component.playback.PlaybackControlIndicator
import moe.ouom.neriplayer.ui.component.playback.NowPlayingSongTitle
import moe.ouom.neriplayer.ui.component.playback.scaleButtonSize
import moe.ouom.neriplayer.ui.component.playback.scaleIconSize
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceBadge
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import moe.ouom.neriplayer.ui.component.playback.rememberDelayedPlaybackWaiting
import moe.ouom.neriplayer.ui.component.playback.SleepTimerDialog
import moe.ouom.neriplayer.ui.component.playback.WaveformSlider
import moe.ouom.neriplayer.ui.component.playback.resolvePlaybackWaiting
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.theme.LocalNeriTargetColorScheme
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.component.lyrics.rememberLyricSeekHapticFeedback
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricEdgeFadeHeight
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricSeekPosition
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricsEditorInitialText
import moe.ouom.neriplayer.ui.component.lyrics.resolveLocalLyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.resolvePreferredLyricContent
import moe.ouom.neriplayer.ui.component.lyrics.resolveStoredLyricText
import moe.ouom.neriplayer.ui.component.lyrics.toEditableLyricsText
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherRoomPanel
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialogContent
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.util.media.saveCoverToPictures
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.SpringDragCancelledAnimation
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.util.Locale
import kotlin.math.roundToInt

private const val LyricsPageTransitionDurationMs = 300
private const val CoverSourceBadgeRevealBufferMs = 120
private const val CoverSourceBadgeRevealDelayMs =
    LyricsPageTransitionDurationMs + CoverSourceBadgeRevealBufferMs
private const val NowPlayingCoverImageCrossfadeMs = 220
private const val QueueSheetMaxHeightFraction = 0.9f
internal val NowPlayingQueueReorderAutoScrollMaxPerFrame = 2.dp
private val QueueReorderDragCancelStiffness = Spring.StiffnessMediumLow
private const val QueueReorderDraggedItemScale = 1.01f
private const val HighUiDensityScaleThreshold = 1.1f
private const val CompactNowPlayingPortraitMaxHeightDp = 600f
private const val PlaybackActionToolbarItemCount = 5
private val PlaybackActionToolbarMinimumTouchTarget = 48.dp
private val PlaybackActionToolbarSmallSlotThreshold = 40.dp
private val NowPlayingMainControlsMinimumSpacing = 4.dp
private val LyricOffsetStepMsFloat = LYRIC_DEFAULT_OFFSET_STEP_MS.toFloat()

internal fun resolveDisplayedNowPlayingCoverUrl(
    requestedCoverUrl: String?,
    displayedCoverUrl: String?,
    requestSucceeded: Boolean
): String? {
    val requested = requestedCoverUrl?.trim()?.takeIf { it.isNotEmpty() }
    val displayed = displayedCoverUrl?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        requested == null -> null
        requested == displayed || requestSucceeded -> requested
        else -> displayed
    }
}

@Composable
private fun StableNowPlayingCoverImage(
    coverUrl: String?,
    songKey: String?,
    context: Context,
    coverRequestSizePx: Int,
    offlineMode: Boolean,
    coverCacheKey: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val requestedCoverUrl = coverUrl?.trim()?.takeIf { it.isNotEmpty() }
    var displayedCoverUrl by remember(songKey) { mutableStateOf(requestedCoverUrl) }
    val latestRequestedCoverUrl by rememberUpdatedState(requestedCoverUrl)

    LaunchedEffect(requestedCoverUrl) {
        if (requestedCoverUrl == null) {
            displayedCoverUrl = null
        } else if (displayedCoverUrl == requestedCoverUrl) {
            displayedCoverUrl = requestedCoverUrl
        }
    }

    Box(modifier = modifier) {
        val targetDisplayedCoverUrl = when {
            requestedCoverUrl == null -> null
            displayedCoverUrl == null -> requestedCoverUrl
            else -> displayedCoverUrl
        }
        Crossfade(
            targetState = targetDisplayedCoverUrl,
            animationSpec = if (targetDisplayedCoverUrl == null) {
                snap()
            } else {
                tween(durationMillis = NowPlayingCoverImageCrossfadeMs)
            },
            label = "NowPlayingCoverImage"
        ) { displayedCover ->
            if (displayedCover.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = remember(
                        context,
                        displayedCover,
                        coverRequestSizePx,
                        offlineMode,
                        coverCacheKey?.let { "$it|data=$displayedCover" }
                    ) {
                        offlineCachedImageRequest(
                            context = context,
                            data = displayedCover,
                            sizePx = coverRequestSizePx,
                            allowHardware = false,
                            crossfade = false,
                            offlineMode = offlineMode,
                            cacheKey = coverCacheKey?.let { "$it|data=$displayedCover" }
                        )
                    },
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (requestedCoverUrl != null && requestedCoverUrl != displayedCoverUrl) {
            AsyncImage(
                model = remember(
                    context,
                    requestedCoverUrl,
                    coverRequestSizePx,
                    offlineMode,
                    coverCacheKey?.let { "$it|data=$requestedCoverUrl" }
                ) {
                    offlineCachedImageRequest(
                        context = context,
                        data = requestedCoverUrl,
                        sizePx = coverRequestSizePx,
                        allowHardware = false,
                        crossfade = false,
                        offlineMode = offlineMode,
                        cacheKey = coverCacheKey?.let { "$it|data=$requestedCoverUrl" }
                    )
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0f },
                onSuccess = {
                    if (latestRequestedCoverUrl == requestedCoverUrl) {
                        displayedCoverUrl = resolveDisplayedNowPlayingCoverUrl(
                            requestedCoverUrl = requestedCoverUrl,
                            displayedCoverUrl = displayedCoverUrl,
                            requestSucceeded = true
                        )
                    }
                }
            )
        }
    }
}

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

internal fun shouldOpenNowPlayingCoverPreviewOnTap(song: SongItem?): Boolean =
    song != null && !song.isLocalSong()

internal fun shouldOpenNowPlayingCoverPreviewOnLongPress(song: SongItem?): Boolean =
    song != null

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
    currentTask: moe.ouom.neriplayer.core.download.DownloadTask?
): Boolean = shouldHideRemoteDownloadAction(hasLocalDownload, currentTask)

internal data class NowPlayingQueueEntry(
    val key: String,
    val queueIndex: Int,
    val song: SongItem
)

internal fun buildNowPlayingQueueEntries(queue: List<SongItem>): List<NowPlayingQueueEntry> {
    return buildNowPlayingQueueEntriesFromDisplayItems(
        queue.mapIndexed { index, song ->
            PlayerQueueDisplayItem(
                queueIndex = index,
                song = song
            )
        }
    )
}

internal fun buildNowPlayingQueueEntriesFromDisplayItems(
    displayItems: List<PlayerQueueDisplayItem>
): List<NowPlayingQueueEntry> {
    val occurrenceByStableKey = mutableMapOf<String, Int>()
    return displayItems.map { item ->
        val stableKey = item.song.stableKey()
        val occurrence = occurrenceByStableKey.getOrDefault(stableKey, 0)
        occurrenceByStableKey[stableKey] = occurrence + 1
        NowPlayingQueueEntry(
            key = "$occurrence:$stableKey",
            queueIndex = item.queueIndex,
            song = item.song
        )
    }
}

internal fun moveNowPlayingQueueEntry(
    entries: MutableList<NowPlayingQueueEntry>,
    fromKey: String,
    toKey: String
): Boolean {
    val fromIndex = entries.indexOfFirst { it.key == fromKey }
    val toIndex = entries.indexOfFirst { it.key == toKey }
    if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return false
    entries.add(toIndex, entries.removeAt(fromIndex))
    return true
}

internal fun syncNowPlayingQueueEntries(
    entries: MutableList<NowPlayingQueueEntry>,
    sourceEntries: List<NowPlayingQueueEntry>
): Boolean {
    if (entries == sourceEntries) return false
    entries.clear()
    entries.addAll(sourceEntries)
    return true
}

internal fun shouldShowNowPlayingQueueQuickActions(
    queueSize: Int,
    currentIndex: Int,
    hasSourceRoute: Boolean
): Boolean = queueSize > 0

internal fun resolveNowPlayingQueueCurrentIndexAfterReorder(
    queueSize: Int,
    currentIndex: Int,
    currentIndexByKey: Int
): Int {
    if (queueSize <= 0) return -1
    if (currentIndexByKey in 0 until queueSize) return currentIndexByKey
    return currentIndex.coerceIn(0, queueSize - 1)
}

internal fun resolveNowPlayingQueueScrollTarget(
    queueSize: Int,
    currentIndex: Int
): Int? = currentIndex.takeIf { it in 0 until queueSize }

internal fun shouldUpdateNowPlayingQueueScroll(
    targetIndex: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int
): Boolean = firstVisibleItemIndex != targetIndex || firstVisibleItemScrollOffset != 0

internal fun shouldAutoLocateNowPlayingQueue(
    selectionMode: Boolean,
    queueOrderDirty: Boolean
): Boolean = !selectionMode && !queueOrderDirty

internal fun isNowPlayingQueueReorderEnabled(
    selectionMode: Boolean,
    allowQueueReorder: Boolean
): Boolean = selectionMode && allowQueueReorder

internal fun shouldShowNowPlayingQueueDragHandle(
    selectionMode: Boolean,
    allowQueueReorder: Boolean
): Boolean = isNowPlayingQueueReorderEnabled(
    selectionMode = selectionMode,
    allowQueueReorder = allowQueueReorder
)

internal fun resolveNowPlayingQueueIndexInput(
    input: String,
    queueSize: Int
): Int? {
    val targetNumber = input.trim().toIntOrNull() ?: return null
    return (targetNumber - 1).takeIf { it in 0 until queueSize }
}

internal fun resolveNowPlayingQueueSelectedSongs(
    queue: List<SongItem>,
    selectedKeys: Set<String>
): List<SongItem> {
    if (selectedKeys.isEmpty()) return emptyList()
    return buildNowPlayingQueueEntries(queue).mapNotNull { entry ->
        entry.song.takeIf { entry.key in selectedKeys }
    }
}

internal fun invertNowPlayingQueueSelection(
    queue: List<SongItem>,
    selectedKeys: Set<String>
): Set<String> {
    return buildNowPlayingQueueEntries(queue).mapNotNullTo(LinkedHashSet()) { entry ->
        entry.key.takeUnless(selectedKeys::contains)
    }
}

@Composable
private fun NowPlayingQueueRow(
    modifier: Modifier = Modifier,
    index: Int,
    song: SongItem,
    isCurrent: Boolean,
    isFavoriteSong: Boolean,
    offlineMode: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToEnd: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    dragHandle: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coverUrl = rememberSongDisplayCoverUrl(song)
    var showMoreMenu by remember { mutableStateOf(false) }
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.64f)
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelect()
                    } else {
                        onPlay()
                    }
                },
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(20.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckBox
                    } else {
                        Icons.Filled.CheckBoxOutlineBlank
                    },
                    contentDescription = if (selected) {
                        stringResource(R.string.common_selected)
                    } else {
                        stringResource(R.string.action_select)
                    },
                    tint = if (selected) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
            Box(
                modifier = Modifier.width(34.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = coverUrl,
                        sizePx = 128,
                        allowHardware = false,
                        crossfade = true,
                        offlineMode = offlineMode
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            } else {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.displayArtist(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent && !selectionMode) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.player_now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (!selectionMode) {
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
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
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.SkipNext,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                onPlayNext()
                                showMoreMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.playlist_add_to_end)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                onAddToEnd()
                                showMoreMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isFavoriteSong) {
                                            R.string.favorite_remove
                                        } else {
                                            R.string.favorite_add
                                        }
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isFavoriteSong) {
                                        Icons.Filled.Favorite
                                    } else {
                                        Icons.Outlined.FavoriteBorder
                                    },
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                onFavoriteToggle()
                                showMoreMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nowplaying_queue_remove)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                onRemoveFromQueue()
                                showMoreMenu = false
                            }
                        )
                    }
                }
            } else if (dragHandle != null) {
                dragHandle()
            }
        }
    }
}

@Composable
private fun NowPlayingQueueQuickActionButton(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 4.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                maxLines = 1
            )
        }
        SmallFloatingActionButton(
            onClick = {
                context.performHapticFeedback(HapticFeedbackEffect.Click)
                onClick()
            },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            elevation = FloatingActionButtonDefaults.elevation()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription
            )
        }
    }
}

@Composable
private fun NowPlayingQueueQuickActionsFab(
    queueSize: Int,
    currentIndex: Int,
    hasSourceRoute: Boolean,
    onLocateCurrent: () -> Unit,
    onOpenSource: () -> Unit,
    onEnterSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!shouldShowNowPlayingQueueQuickActions(queueSize, currentIndex, hasSourceRoute)) return

    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasSourceRoute) {
                    NowPlayingQueueQuickActionButton(
                        label = stringResource(R.string.cd_open_current_playback_source),
                        icon = Icons.Outlined.LibraryMusic,
                        contentDescription = stringResource(R.string.cd_open_current_playback_source),
                        onClick = {
                            expanded = false
                            onOpenSource()
                        }
                    )
                }

                if (currentIndex >= 0) {
                    NowPlayingQueueQuickActionButton(
                        label = stringResource(R.string.cd_locate_playing),
                        icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                        contentDescription = stringResource(R.string.cd_locate_playing),
                        onClick = {
                            expanded = false
                            onLocateCurrent()
                        }
                    )
                }

                NowPlayingQueueQuickActionButton(
                    label = stringResource(R.string.action_enter_multi_select),
                    icon = Icons.Filled.CheckBox,
                    contentDescription = stringResource(R.string.action_enter_multi_select),
                    onClick = {
                        expanded = false
                        onEnterSelection()
                    }
                )
            }
        }

        HapticFloatingActionButton(
            onClick = { expanded = !expanded },
            hapticEffect = HapticFeedbackEffect.Click
        ) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.Close else Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_queue_quick_actions)
            )
        }
    }
}

@Composable
private fun NowPlayingQueueSelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    canExport: Boolean,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onExport: () -> Unit,
    onExitSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HapticIconButton(onClick = onExitSelection) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_cancel)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.common_selected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = pluralStringResource(
                    R.plurals.common_selected_count,
                    selectedCount,
                    selectedCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HapticIconButton(onClick = onSelectAll) {
            Icon(
                imageVector = if (allSelected) {
                    Icons.Filled.CheckBox
                } else {
                    Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = if (allSelected) {
                    stringResource(R.string.action_deselect_all)
                } else {
                    stringResource(R.string.action_select_all)
                }
            )
        }
        HapticTextButton(onClick = onInvertSelection) {
            Text(stringResource(R.string.action_inverse_select))
        }
        HapticIconButton(
            enabled = canExport,
            onClick = onExport
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = stringResource(R.string.cd_export_playlist)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingQueueSheet(
    displayedQueueItems: List<PlayerQueueDisplayItem>,
    currentIndexInDisplay: Int,
    offlineMode: Boolean,
    allowQueueReorder: Boolean,
    onDismissRequest: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val localPlaylistRepo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val playerPlaylists by PlayerManager.playlistsFlow.collectAsStateWithLifecycle()
    val allLocalPlaylists by localPlaylistRepo.playlists.collectAsStateWithLifecycle(
        initialValue = playerPlaylists
    )
    val displayedQueue = remember(displayedQueueItems) {
        displayedQueueItems.map { it.song }
    }
    val favoriteSongs = remember(allLocalPlaylists, context) {
        FavoritesPlaylist.firstOrNull(allLocalPlaylists, context)?.songs.orEmpty()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showQueueIndexJumpDialog by remember { mutableStateOf(false) }
    var queueIndexInput by remember { mutableStateOf("") }
    val sourceEntries = remember(displayedQueueItems) {
        buildNowPlayingQueueEntriesFromDisplayItems(displayedQueueItems)
    }
    val initialQueueScrollTarget = remember(sourceEntries, currentIndexInDisplay) {
        resolveNowPlayingQueueScrollTarget(
            queueSize = sourceEntries.size,
            currentIndex = currentIndexInDisplay
        )
    }
    val queueListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialQueueScrollTarget ?: 0
    )
    var initialQueuePositioned by remember { mutableStateOf(initialQueueScrollTarget != null) }
    var queueOrderDirty by remember { mutableStateOf(false) }
    val queueEntries = remember {
        mutableStateListOf<NowPlayingQueueEntry>().apply {
            addAll(sourceEntries)
        }
    }
    val currentEntryKey = sourceEntries.getOrNull(currentIndexInDisplay)?.key
    val currentIndexInQueueEntries = currentEntryKey
        ?.let { key -> queueEntries.indexOfFirst { it.key == key } }
        ?.takeIf { it >= 0 }
        ?: currentIndexInDisplay
    val latestCurrentEntryKey by rememberUpdatedState(currentEntryKey)
    val latestCurrentIndexInQueueEntries by rememberUpdatedState(currentIndexInQueueEntries)
    val latestQueueReorderEnabled by rememberUpdatedState(
        isNowPlayingQueueReorderEnabled(
            selectionMode = selectionMode,
            allowQueueReorder = allowQueueReorder
        )
    )
    val latestSourceEntries by rememberUpdatedState(sourceEntries)
    val queueItemKeys by remember {
        derivedStateOf {
            queueEntries.mapTo(LinkedHashSet()) { it.key }
        }
    }
    val selectedSongs by remember {
        derivedStateOf {
            queueEntries.filter { it.key in selectedKeys }.map { it.song }
        }
    }
    val allItemsSelected = queueEntries.isNotEmpty() &&
        selectedKeys.size == queueItemKeys.size &&
        selectedKeys.containsAll(queueItemKeys)
    val reorderState = rememberReorderableLazyListState(
        listState = queueListState,
        onMove = { from: ItemPosition, to: ItemPosition ->
            if (!latestQueueReorderEnabled) {
                return@rememberReorderableLazyListState
            }
            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
            val toKey = to.key as? String ?: return@rememberReorderableLazyListState
            if (moveNowPlayingQueueEntry(queueEntries, fromKey, toKey)) {
                queueOrderDirty = true
            }
        },
        onDragEnd = { _, _ ->
            if (!latestQueueReorderEnabled) {
                if (queueOrderDirty) {
                    queueOrderDirty = false
                    syncNowPlayingQueueEntries(queueEntries, latestSourceEntries)
                }
                return@rememberReorderableLazyListState
            }
            if (!queueOrderDirty) {
                return@rememberReorderableLazyListState
            }
            val currentKey = latestCurrentEntryKey
            val currentIndexByKey = currentKey
                ?.let { key -> queueEntries.indexOfFirst { it.key == key } }
                ?: -1
            val currentIndexAfterReorder = resolveNowPlayingQueueCurrentIndexAfterReorder(
                queueSize = queueEntries.size,
                currentIndex = latestCurrentIndexInQueueEntries,
                currentIndexByKey = currentIndexByKey
            )
            PlayerManager.reorderQueue(
                queue = queueEntries.map { it.song },
                currentIndexInQueue = currentIndexAfterReorder
            )
            queueOrderDirty = false
        },
        maxScrollPerFrame = NowPlayingQueueReorderAutoScrollMaxPerFrame,
        dragCancelledAnimation = SpringDragCancelledAnimation(
            stiffness = QueueReorderDragCancelStiffness
        )
    )

    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }

    var dismissingQueue by remember { mutableStateOf(false) }

    fun dismissQueue() {
        if (dismissingQueue) return
        dismissingQueue = true
        showExportSheet = false
        showQueueIndexJumpDialog = false
        exitSelection()
        screenScope.launch {
            runCatching { sheetState.hide() }
            onDismissRequest()
        }
    }

    fun scrollToQueueIndex(index: Int) {
        val targetIndex = index.takeIf { it in queueEntries.indices } ?: return
        screenScope.launch {
            reorderState.listState.animateScrollToItem(targetIndex)
        }
    }

    fun locateCurrentQueueItem() {
        scrollToQueueIndex(currentIndexInQueueEntries)
    }

    fun openQueueIndexJumpDialog() {
        context.performHapticFeedback(HapticFeedbackEffect.Click)
        queueIndexInput = (currentIndexInQueueEntries + 1)
            .coerceIn(1, queueEntries.size.coerceAtLeast(1))
            .toString()
        showQueueIndexJumpDialog = true
    }

    fun toggleItem(key: String) {
        selectedKeys = if (key in selectedKeys) {
            selectedKeys - key
        } else {
            selectedKeys + key
        }
    }

    fun toggleQueueSongFavorite(song: SongItem, isFavoriteSong: Boolean) {
        screenScope.launchLocalPlaylistMutation("toggleNowPlayingQueueSongFavorite") {
            if (isFavoriteSong) {
                localPlaylistRepo.removeFromFavorites(song)
            } else {
                localPlaylistRepo.addToFavorites(song)
            }
        }
    }

    LaunchedEffect(sourceEntries) {
        if (!queueOrderDirty) {
            syncNowPlayingQueueEntries(queueEntries, sourceEntries)
        }
    }

    LaunchedEffect(allowQueueReorder) {
        if (!allowQueueReorder && queueOrderDirty) {
            queueOrderDirty = false
            syncNowPlayingQueueEntries(queueEntries, sourceEntries)
        }
    }

    LaunchedEffect(queueEntries.size, currentIndexInQueueEntries, selectionMode, queueOrderDirty) {
        if (!shouldAutoLocateNowPlayingQueue(selectionMode, queueOrderDirty)) {
            return@LaunchedEffect
        }
        val targetIndex = resolveNowPlayingQueueScrollTarget(
            queueSize = queueEntries.size,
            currentIndex = currentIndexInQueueEntries
        ) ?: return@LaunchedEffect
        if (targetIndex !in queueEntries.indices) return@LaunchedEffect
        if (!shouldUpdateNowPlayingQueueScroll(
                targetIndex = targetIndex,
                firstVisibleItemIndex = queueListState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = queueListState.firstVisibleItemScrollOffset
            )
        ) {
            if (!initialQueuePositioned) initialQueuePositioned = true
            return@LaunchedEffect
        }
        if (!initialQueuePositioned) {
            queueListState.scrollToItem(targetIndex)
            initialQueuePositioned = true
        } else {
            queueListState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(queueItemKeys, selectedKeys) {
        val cleanedKeys = selectedKeys.intersect(queueItemKeys)
        if (cleanedKeys != selectedKeys) selectedKeys = cleanedKeys
    }

    ModalBottomSheet(
        onDismissRequest = ::dismissQueue,
        sheetState = sheetState,
        sheetGesturesEnabled = false
    ) {
        BackHandler(enabled = selectionMode && !showExportSheet) {
            exitSelection()
        }

        BackHandler(enabled = showExportSheet) {
            showExportSheet = false
        }

        if (showQueueIndexJumpDialog) {
            NowPlayingQueueIndexJumpDialog(
                queueSize = queueEntries.size,
                input = queueIndexInput,
                onInputChange = { queueIndexInput = it },
                onDismiss = { showQueueIndexJumpDialog = false },
                onJump = { targetIndex ->
                    scrollToQueueIndex(targetIndex)
                    showQueueIndexJumpDialog = false
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight(QueueSheetMaxHeightFraction)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(Modifier.fillMaxSize()) {
                if (selectionMode) {
                    NowPlayingQueueSelectionToolbar(
                        selectedCount = selectedKeys.size,
                        allSelected = allItemsSelected,
                        canExport = selectedSongs.isNotEmpty(),
                        onSelectAll = {
                            selectedKeys = if (allItemsSelected) {
                                emptySet()
                            } else {
                                queueItemKeys
                            }
                            if (selectedKeys.isEmpty()) selectionMode = false
                        },
                        onInvertSelection = {
                            selectedKeys = invertNowPlayingQueueSelection(
                                queueEntries.map { it.song },
                                selectedKeys
                            )
                            if (selectedKeys.isEmpty()) selectionMode = false
                        },
                        onExport = {
                            if (selectedSongs.isNotEmpty()) {
                                showExportSheet = true
                            }
                        },
                        onExitSelection = ::exitSelection
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 18.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.playlist_queue),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = pluralStringResource(
                                    R.plurals.nowplaying_queue_count_format,
                                    displayedQueue.size,
                                    displayedQueue.size
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (currentIndexInQueueEntries >= 0) {
                            val queueIndexButtonShape = RoundedCornerShape(999.dp)
                            Surface(
                                modifier = Modifier
                                    .clip(queueIndexButtonShape)
                                    .clickable(onClick = ::openQueueIndexJumpDialog),
                                shape = queueIndexButtonShape,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.nowplaying_queue_current_position,
                                            currentIndexInQueueEntries + 1
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    state = reorderState.listState,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (allowQueueReorder) {
                                Modifier.reorderable(reorderState)
                            } else {
                                Modifier
                            }
                        )
                        .bottomSheetScrollGuard(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 98.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = queueEntries,
                        key = { _, entry -> entry.key },
                        contentType = { _, _ -> "queue_song" }
                    ) { index, entry ->
                        ReorderableItem(state = reorderState, key = entry.key) { isDragging ->
                            val isFavoriteSong = remember(favoriteSongs, entry.song) {
                                favoriteSongs.any { it.sameIdentityAs(entry.song) }
                            }
                            val rowScale by animateFloatAsState(
                                targetValue = if (isDragging) {
                                    QueueReorderDraggedItemScale
                                } else {
                                    1f
                                },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "queue_row_scale"
                            )
                            NowPlayingQueueRow(
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = rowScale
                                        scaleY = rowScale
                                    },
                                index = index,
                                song = entry.song,
                                isCurrent = entry.key == currentEntryKey,
                                isFavoriteSong = isFavoriteSong,
                                offlineMode = offlineMode,
                                selectionMode = selectionMode,
                                selected = entry.key in selectedKeys,
                                onPlay = {
                                    PlayerManager.playFromQueue(index)
                                    dismissQueue()
                                },
                                onLongPress = {
                                    selectionMode = true
                                    selectedKeys = selectedKeys + entry.key
                                },
                                onToggleSelect = { toggleItem(entry.key) },
                                onPlayNext = { PlayerManager.addToQueueNext(entry.song) },
                                onAddToEnd = { PlayerManager.addToQueueEnd(entry.song) },
                                onFavoriteToggle = {
                                    toggleQueueSongFavorite(entry.song, isFavoriteSong)
                                },
                                onRemoveFromQueue = {
                                    PlayerManager.removeQueueItem(index)
                                },
                                dragHandle = if (
                                    shouldShowNowPlayingQueueDragHandle(
                                        selectionMode = selectionMode,
                                        allowQueueReorder = allowQueueReorder
                                    )
                                ) {
                                    {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
                                                )
                                                .detectReorder(reorderState)
                                                .padding(10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DragHandle,
                                                contentDescription = stringResource(R.string.common_drag_handle),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }

            if (!selectionMode) {
                NowPlayingQueueQuickActionsFab(
                    queueSize = displayedQueue.size,
                    currentIndex = currentIndexInQueueEntries,
                    hasSourceRoute = onOpenCurrentPlaybackSource != null,
                    onLocateCurrent = {
                        locateCurrentQueueItem()
                    },
                    onOpenSource = {
                        dismissQueue()
                        onOpenCurrentPlaybackSource?.invoke()
                    },
                    onEnterSelection = {
                        selectionMode = true
                        selectedKeys = emptySet()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp)
                )
            }

            NeriOverlaySnackbarHost(
                hostState = snackbarHostState,
                applyNavigationBarsPadding = false
            )
        }
    }

    if (showExportSheet) {
        PlaylistExportSheet(
            title = stringResource(R.string.playlist_export_to_local),
            playlists = allLocalPlaylists.filterNot {
                LocalFilesPlaylist.isSystemPlaylist(it, context)
            },
            selectedCount = selectedSongs.size,
            onDismissRequest = { showExportSheet = false },
            onCreateAndExport = { name ->
                val songs = selectedSongs
                screenScope.launchLocalPlaylistMutation(
                    operation = "createPlaylistFromNowPlayingQueue",
                    onResult = { result ->
                        screenScope.showPlaylistBatchExportCreatedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = localPlaylistRepo,
                            result = result
                        )
                    }
                ) {
                    localPlaylistRepo.createPlaylistWithSongs(name, songs)
                }
                showExportSheet = false
                dismissQueue()
            },
            onExportToPlaylist = { playlist ->
                val songs = selectedSongs
                screenScope.launchLocalPlaylistMutation(
                    operation = "exportSongsFromNowPlayingQueue",
                    onResult = { result ->
                        screenScope.showPlaylistBatchExportAddedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = localPlaylistRepo,
                            targetPlaylistId = playlist.id,
                            targetPlaylistName = playlist.name,
                            result = result
                        )
                    }
                ) {
                    localPlaylistRepo.addSongsToPlaylistWithResult(playlist.id, songs)
                }
                showExportSheet = false
                dismissQueue()
            }
        )
    }
}

@Composable
private fun NowPlayingQueueIndexJumpDialog(
    queueSize: Int,
    input: String,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    val targetIndex = remember(input, queueSize) {
        resolveNowPlayingQueueIndexInput(input, queueSize)
    }
    val isInputError = input.isNotBlank() && targetIndex == null

    fun submit() {
        targetIndex?.let(onJump)
    }

    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nowplaying_queue_jump_title)) },
        text = {
            MiuixSettingsDialogContent(verticalSpacing = 8.dp) {
                MiuixSettingsTextField(
                    value = input,
                    onValueChange = { value ->
                        onInputChange(value.filter { it.isDigit() }.take(6))
                    },
                    placeholder = {
                        Text(stringResource(R.string.nowplaying_queue_jump_input_label))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )
                Text(
                    text = stringResource(
                        R.string.nowplaying_queue_jump_input_supporting,
                        queueSize
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isInputError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        confirmButton = {
            MiuixSettingsButton(
                onClick = ::submit,
                enabled = targetIndex != null
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

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

internal fun hasCachedLocalDownload(song: SongItem): Boolean {
    return GlobalDownloadManager.hasDownloadedSongCached(song) ||
        ManagedDownloadStorage.peekDownloadedAudio(song) != null
}

internal fun hasCachedLocalDownload(context: Context, song: SongItem): Boolean {
    return hasCachedLocalDownload(song) ||
        ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song)
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

private fun seekToLyricSafely(
    positionMs: Long,
    playbackDurationMs: Long,
    songDurationMs: Long
) {
    val knownDurationMs = maxOf(playbackDurationMs, songDurationMs)
    resolveLyricSeekPosition(positionMs, knownDurationMs)?.let(PlayerManager::seekTo)
}

internal data class LoadedLyricsState(
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

internal enum class ManagedLyricVariant {
    ORIGINAL,
    TRANSLATED,
    ROMANIZED
}

internal fun resolveManagedDownloadFastLyricText(
    localLyrics: LocalLyricsScanMetadata?,
    downloadedLyrics: ManagedDownloadStorage.DownloadedLyricsBundle?,
    storedLyric: String?,
    variant: ManagedLyricVariant
): String? {
    val downloadedHasSidecar = when (variant) {
        ManagedLyricVariant.ORIGINAL -> downloadedLyrics?.hasOriginalSidecar == true
        ManagedLyricVariant.TRANSLATED -> downloadedLyrics?.hasTranslatedSidecar == true
        ManagedLyricVariant.ROMANIZED -> downloadedLyrics?.hasRomanizedSidecar == true
    }
    if (downloadedHasSidecar) {
        return when (variant) {
            ManagedLyricVariant.ORIGINAL -> downloadedLyrics?.lyric
            ManagedLyricVariant.TRANSLATED -> downloadedLyrics?.translatedLyric
            ManagedLyricVariant.ROMANIZED -> downloadedLyrics?.romanizedLyric
        }
    }

    val localHasSidecar = when (variant) {
        ManagedLyricVariant.ORIGINAL -> localLyrics?.hasOriginalSidecar == true
        ManagedLyricVariant.TRANSLATED -> localLyrics?.hasTranslatedSidecar == true
        ManagedLyricVariant.ROMANIZED -> localLyrics?.hasRomanizedSidecar == true
    }
    if (localHasSidecar) {
        return when (variant) {
            ManagedLyricVariant.ORIGINAL -> localLyrics?.lyric
            ManagedLyricVariant.TRANSLATED -> localLyrics?.translatedLyric
            ManagedLyricVariant.ROMANIZED -> localLyrics?.romanizedLyric
        }
    }

    val localValue = when (variant) {
        ManagedLyricVariant.ORIGINAL -> localLyrics?.lyric
        ManagedLyricVariant.TRANSLATED -> localLyrics?.translatedLyric
        ManagedLyricVariant.ROMANIZED -> localLyrics?.romanizedLyric
    }
    val downloadedValue = when (variant) {
        ManagedLyricVariant.ORIGINAL -> downloadedLyrics?.lyric
        ManagedLyricVariant.TRANSLATED -> downloadedLyrics?.translatedLyric
        ManagedLyricVariant.ROMANIZED -> downloadedLyrics?.romanizedLyric
    }
    return downloadedValue ?: localValue ?: storedLyric
}

internal fun resolveNowPlayingLyricText(
    isManagedLocalDownload: Boolean,
    localLyrics: LocalLyricsScanMetadata?,
    downloadedLyrics: ManagedDownloadStorage.DownloadedLyricsBundle?,
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?,
    variant: ManagedLyricVariant
): String? {
    return if (isManagedLocalDownload) {
        resolveManagedDownloadFastLyricText(
            localLyrics = localLyrics,
            downloadedLyrics = downloadedLyrics,
            storedLyric = storedLyric,
            variant = variant
        )
    } else {
        resolveLyricTextForPlayback(
            isManagedLocalDownload = false,
            localLyric = localLyric,
            storedLyric = storedLyric,
            downloadedLyric = downloadedLyric
        )
    }
}

internal fun buildNowPlayingFastLyricsState(
    rawLyrics: String?,
    rawTranslatedLyrics: String?,
    rawPhoneticLyrics: String?
): LoadedLyricsState {
    val bypassRawLyrics = shouldBypassCollapsedStoredLyric(rawLyrics)
    val bypassTranslatedLyrics = shouldBypassCollapsedStoredLyric(rawTranslatedLyrics)
    val lyrics = rawLyrics
        ?.takeIf { it.isNotBlank() && !bypassRawLyrics }
        ?.let(::parseNeteaseLyricsAuto)
        .orEmpty()
    val translatedLyrics = rawTranslatedLyrics
        ?.takeIf { it.isNotBlank() && !bypassTranslatedLyrics }
        ?.let(::parseNeteaseLyricsAuto)
        .orEmpty()
    val phoneticLyrics = rawPhoneticLyrics
        ?.takeIf(String::isNotBlank)
        ?.let(::parseNeteaseLyricsAuto)
        .orEmpty()
    return LoadedLyricsState(
        rawLyrics = rawLyrics.takeUnless { bypassRawLyrics },
        rawTranslatedLyrics = rawTranslatedLyrics.takeUnless { bypassTranslatedLyrics },
        rawPhoneticLyrics = rawPhoneticLyrics,
        lyrics = lyrics,
        translatedLyrics = translatedLyrics,
        phoneticLyrics = phoneticLyrics,
        plainLyrics = lyrics.flattenWordTimedEntries(),
        plainTranslatedLyrics = translatedLyrics.flattenWordTimedEntries(),
        embeddedPhoneticLyrics = buildPhoneticLyricEntries(
            rawLyrics = rawLyrics,
            lyrics = lyrics
        )
    )
}

/**
 * 为当前曲目建立无需磁盘访问的首帧歌词快照
 */
internal fun buildNowPlayingImmediateLyricsState(song: SongItem?): LoadedLyricsState {
    return buildNowPlayingFastLyricsState(
        rawLyrics = resolveStoredLyricText(
            currentLyric = song?.matchedLyric,
            legacyLyric = song?.originalLyric
        ),
        rawTranslatedLyrics = resolveStoredLyricText(
            currentLyric = song?.matchedTranslatedLyric,
            legacyLyric = song?.originalTranslatedLyric
        ),
        rawPhoneticLyrics = resolveStoredLyricText(
            currentLyric = song?.matchedRomanizedLyric,
            legacyLyric = song?.originalRomanizedLyric
        )
    )
}

internal fun shouldReplaceLyricsAfterRefresh(
    sameSong: Boolean,
    loadedHasLyrics: Boolean
): Boolean = !sameSong || loadedHasLyrics

internal fun shouldBackfillDownloadedLyricsAfterFastMiss(
    isManagedLocalDownload: Boolean,
    canReadManagedDownloadLyrics: Boolean,
    fastLyrics: ManagedDownloadStorage.DownloadedLyricsBundle?
): Boolean {
    if (!isManagedLocalDownload || !canReadManagedDownloadLyrics) {
        return false
    }
    // 音频快照命中不代表 Lyrics 索引已经完成, 首播也必须补齐过期或部分索引
    return fastLyrics == null ||
        !fastLyrics.hasOriginalSidecar ||
        !fastLyrics.hasTranslatedSidecar ||
        !fastLyrics.hasRomanizedSidecar
}

internal fun shouldReadEmbeddedLyricsForNowPlayingFastStage(): Boolean = false

internal fun resolveNowPlayingLyricsMediaReloadKey(
    song: SongItem?,
    currentMediaUrl: String?
): String? {
    // 本地音频的播放地址可能在首播期间从索引 URI 切换为可播放 URI, 不应取消歌词补读
    return currentMediaUrl.takeUnless { song?.isLocalSong() == true }
}

internal fun shouldBypassCollapsedStoredLyric(rawLyric: String?): Boolean {
    return rawLyric?.let(::hasCollapsedTimedLyricTimeline) == true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
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
    showNowPlayingTitle: Boolean = true,
    offlineMode: Boolean = false,
    resolvedCoverUrl: String? = null,
    visualCoverUrl: String? = null,
    playbackSongKey: String? = null,
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
        .collectAsStateWithLifecycle(initialValue = PlaybackControlLayoutPreferences())
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
    val downloadedLyricsRefreshVersion by
        ManagedDownloadStorage.lyricsRefreshVersion.collectAsStateWithLifecycle()
    val actualCoverUrl = resolvedCoverUrl ?: visualCoverUrl
    val currentCoverUrl = visualCoverUrl ?: actualCoverUrl
    val coverSongKey = playbackSongKey ?: currentSong?.stableKey()
    val coverPreviewOnTapEnabled = shouldOpenNowPlayingCoverPreviewOnTap(currentSong)
    val coverPreviewOnLongPressEnabled =
        shouldOpenNowPlayingCoverPreviewOnLongPress(currentSong)

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
    var showCoverPreview by remember(playbackSourceSongKey) { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }
    var showSongNameMenu by remember { mutableStateOf(false) }
    var showArtistMenu by remember { mutableStateOf(false) }
    var showQualitySwitchDialog by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Snackbar状态
    val snackbarHostState = remember { SnackbarHostState() }
    var detailSong by remember { mutableStateOf<SongItem?>(null) }
    var pendingSyncConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingSyncConfirmLabel by remember { mutableStateOf("") }

    val clipboard = LocalClipboard.current
    val screenScope = rememberCoroutineScope()

    val downloadCurrentCover: () -> Unit = {
        val song = currentSong
        if (song == null || actualCoverUrl.isNullOrBlank()) {
            screenScope.launch {
                snackbarHostState.showNeriSnackbar(
                    composeResources.getString(R.string.cover_download_unavailable)
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
                        composeResources.getString(R.string.cover_download_success, fileName)
                    )
                }.onFailure { error ->
                    val errorMessage = error.message ?: composeResources.getString(R.string.download_failed)
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(R.string.cover_download_failed, errorMessage)
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
                    composeResources.getString(R.string.cover_download_permission_required)
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

    val currentLyricSourceKey = currentSong?.stableKey()
    val immediateLyricsState = remember(
        currentLyricSourceKey,
        currentSong?.matchedLyric,
        currentSong?.matchedTranslatedLyric,
        currentSong?.matchedRomanizedLyric,
        currentSong?.originalLyric,
        currentSong?.originalTranslatedLyric,
        currentSong?.originalRomanizedLyric
    ) {
        buildNowPlayingImmediateLyricsState(currentSong)
    }
    // 切歌时直接以歌曲对象已有文本建立首帧，侧载快读完成后再覆盖，避免歌曲和歌词错帧
    var lyrics by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.lyrics)
    }
    var translatedLyrics by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.translatedLyrics)
    }
    var rawLyricsText by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.rawLyrics)
    }
    var rawTranslatedLyricsText by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.rawTranslatedLyrics)
    }
    var rawPhoneticLyricsText by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.rawPhoneticLyrics)
    }
    var remotePhoneticLyrics by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.phoneticLyrics)
    }
    var plainLyrics by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.plainLyrics)
    }
    var plainTranslatedLyrics by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.plainTranslatedLyrics)
    }
    var embeddedPhoneticLyrics by remember(currentLyricSourceKey) {
        mutableStateOf(immediateLyricsState.embeddedPhoneticLyrics)
    }
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
                snackbarHostState.showNeriSnackbar(composeResources.getString(R.string.artist_not_available))
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
                    composeResources.getString(R.string.youtube_creator_not_available)
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
                        snackbarHostState.showNeriSnackbar(composeResources.getString(R.string.artist_not_available))
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
                            composeResources.getString(R.string.bili_uploader_owner_unavailable)
                        )
                    }
                },
                onError = { error ->
                    resolvingBiliUploader = false
                    screenScope.launch {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(
                                R.string.bili_uploader_open_failed,
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
                                R.string.youtube_creator_open_failed,
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

    LaunchedEffect(
        currentSong?.id,
        currentSong?.matchedLyric,
        currentSong?.matchedTranslatedLyric,
        currentSong?.matchedRomanizedLyric,
        currentSong?.originalLyric,
        currentSong?.originalTranslatedLyric,
        currentSong?.originalRomanizedLyric,
        currentSong?.matchedSongId,
        currentSong?.matchedLyricSource,
        currentSong?.album,
        currentSong?.mediaUri,
        currentSong?.localFilePath,
        downloadPresenceVersion,
        downloadedLyricsRefreshVersion,
        resolveNowPlayingLyricsMediaReloadKey(
            song = currentSong,
            currentMediaUrl = currentMediaUrl
        )
    ) {
        val song = currentSong
        val lyricIdentity = song?.stableKey().orEmpty()
        NPLogger.d(
            "NowPlayingLyrics",
            "歌词加载开始: key=$lyricIdentity, local=${song?.isLocalSong() == true}, " +
                "mediaReloadKey=${resolveNowPlayingLyricsMediaReloadKey(song, currentMediaUrl)}"
        )
        fun publishLoadedLyricsState(
            loadedLyricsState: LoadedLyricsState,
            stage: String
        ) {
            if (song != null && currentSong?.sameIdentityAs(song) != true) {
                NPLogger.d(
                    "NowPlayingLyrics",
                    "丢弃过期歌词结果: stage=$stage, key=$lyricIdentity"
                )
                return
            }
            val loadedHasLyrics = loadedLyricsState.rawLyrics?.isNotBlank() == true ||
                loadedLyricsState.rawTranslatedLyrics?.isNotBlank() == true ||
                loadedLyricsState.rawPhoneticLyrics?.isNotBlank() == true ||
                loadedLyricsState.lyrics.isNotEmpty() ||
                loadedLyricsState.translatedLyrics.isNotEmpty() ||
                loadedLyricsState.phoneticLyrics.isNotEmpty()
            val sameSong = song != null
            if (song == null || shouldReplaceLyricsAfterRefresh(sameSong, loadedHasLyrics)) {
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
            NPLogger.d(
                "NowPlayingLyrics",
                "歌词结果发布: stage=$stage, key=$lyricIdentity, " +
                    "hasLyrics=$loadedHasLyrics, raw=${loadedLyricsState.rawLyrics != null}, " +
                    "translated=${loadedLyricsState.rawTranslatedLyrics != null}, " +
                    "romanized=${loadedLyricsState.rawPhoneticLyrics != null}"
            )
        }
        var localLyricsScan: LocalLyricsScanMetadata? = null
        var downloadedLyricsBundle: ManagedDownloadStorage.DownloadedLyricsBundle? = null
        var managedLocalDownloadDetected = false
        var managedDownloadLyricsReadable = false
        val fastLoadedLyricsState = withContext(Dispatchers.IO) {
            val isManagedLocalDownload = song?.let { candidate ->
                candidate.isLocalSong() && hasCachedLocalDownload(context, candidate)
            } == true
            managedLocalDownloadDetected = isManagedLocalDownload
            if (isManagedLocalDownload) {
                ManagedDownloadStorage.scheduleLyricsRefresh(context)
            }
            val canReadManagedDownloadLyrics = song?.let { candidate ->
                shouldReadManagedDownloadLyrics(
                    song = candidate,
                    isManagedLocalDownload = isManagedLocalDownload
                )
            } == true
            managedDownloadLyricsReadable = canReadManagedDownloadLyrics
            val downloadedLyrics = song?.takeIf { canReadManagedDownloadLyrics }?.let { downloadedSong ->
                runCatching {
                    AudioDownloadManager.getLyricsBundleFast(
                        context = context,
                        song = downloadedSong,
                        allowColdSafProbe = false
                    )
                }.onFailure { error ->
                    NPLogger.w(
                        "NowPlayingLyrics",
                        "下载歌词内存索引读取失败: ${error.message}"
                    )
                }.getOrNull().also { downloadedLyricsBundle = it }
            }
            // 托管下载由 ManagedDownloadStorage 统一读取, 避免同一首歌再走一次普通 SAF 探测
            val shouldProbeLocalSidecar = song?.isLocalSong() == true && !isManagedLocalDownload
            val localLyrics = song?.takeIf { shouldProbeLocalSidecar }?.let { localSong ->
                runCatching {
                    LocalMediaSupport.inspectLyricsFast(
                        context = context,
                        song = localSong,
                        includeStoredFallback = !isManagedLocalDownload,
                        // 嵌入歌词需要打开音频容器, 留到后台补全避免首帧被 TagLib 阻塞
                        includeEmbeddedFallback = shouldReadEmbeddedLyricsForNowPlayingFastStage()
                    )
                }
                    .onFailure { error ->
                        NPLogger.w(
                            "NowPlayingLyrics",
                            "本地歌词首屏读取失败: ${error.message}"
                        )
                    }
                    .getOrNull()
            }.also { localLyricsScan = it }
            val storedRawLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedLyric,
                legacyLyric = song?.originalLyric
            )
            val storedRawTranslatedLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedTranslatedLyric,
                legacyLyric = song?.originalTranslatedLyric
            )
            val storedRawPhoneticLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedRomanizedLyric,
                legacyLyric = song?.originalRomanizedLyric
            )
            buildNowPlayingFastLyricsState(
                rawLyrics = resolveNowPlayingLyricText(
                    isManagedLocalDownload = isManagedLocalDownload,
                    localLyrics = localLyrics,
                    downloadedLyrics = downloadedLyrics,
                    localLyric = localLyrics?.lyric,
                    storedLyric = storedRawLyrics,
                    downloadedLyric = null,
                    variant = ManagedLyricVariant.ORIGINAL
                ),
                rawTranslatedLyrics = resolveNowPlayingLyricText(
                    isManagedLocalDownload = isManagedLocalDownload,
                    localLyrics = localLyrics,
                    downloadedLyrics = downloadedLyrics,
                    localLyric = localLyrics?.translatedLyric,
                    storedLyric = storedRawTranslatedLyrics,
                    downloadedLyric = null,
                    variant = ManagedLyricVariant.TRANSLATED
                ),
                rawPhoneticLyrics = resolveNowPlayingLyricText(
                    isManagedLocalDownload = isManagedLocalDownload,
                    localLyrics = localLyrics,
                    downloadedLyrics = downloadedLyrics,
                    localLyric = localLyrics?.romanizedLyric,
                    storedLyric = storedRawPhoneticLyrics,
                    downloadedLyric = null,
                    variant = ManagedLyricVariant.ROMANIZED
                )
            )
        }
        publishLoadedLyricsState(fastLoadedLyricsState, stage = "fast")
        // 快读结果已经发布, 完整侧载校验必须在独立后台阶段执行, 不能阻塞切歌首帧
        launch {
            val loadedLyricsState = withContext(Dispatchers.IO) {
            val isLocalSong = song?.isLocalSong() == true
            val isManagedLocalDownload = managedLocalDownloadDetected
            val canReadManagedDownloadLyrics = managedDownloadLyricsReadable
            val localLyrics = if (isLocalSong && !isManagedLocalDownload) {
                val scan = localLyricsScan
                if (
                    scan == null ||
                        !scan.sourceResolved ||
                        (
                            !scan.hasOriginalSidecar &&
                                !scan.hasTranslatedSidecar &&
                                !scan.hasRomanizedSidecar
                            )
                ) {
                    runCatching {
                        LocalMediaSupport.inspectLyricsFast(
                            context = context,
                            song = song,
                            includeStoredFallback = true,
                            includeEmbeddedFallback = true
                        )
                    }.getOrElse { scan }
                } else {
                    scan
                }
            } else {
                null
            }
            val localRawLyrics = localLyrics?.lyric
            val localRawTranslatedLyrics = localLyrics?.translatedLyric
            val localRawPhoneticLyrics = localLyrics?.romanizedLyric
            val storedRawLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedLyric,
                legacyLyric = song?.originalLyric
            )
            val storedRawTranslatedLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedTranslatedLyric,
                legacyLyric = song?.originalTranslatedLyric
            )
            val storedRawPhoneticLyrics = resolveStoredLyricText(
                currentLyric = song?.matchedRomanizedLyric,
                legacyLyric = song?.originalRomanizedLyric
            )
            val cachedDownloadedLyrics = downloadedLyricsBundle
            val shouldReadDownloadedLyrics = shouldBackfillDownloadedLyricsAfterFastMiss(
                isManagedLocalDownload = isManagedLocalDownload,
                canReadManagedDownloadLyrics = canReadManagedDownloadLyrics,
                fastLyrics = cachedDownloadedLyrics
            )
            if (shouldReadDownloadedLyrics && isManagedLocalDownload) {
                NPLogger.d(
                    "NowPlayingLyrics",
                    "下载侧载快读不完整，执行一次后台补读: song=${song?.name.orEmpty()}"
                )
            }
            val downloadedLyrics = song
                ?.takeIf { shouldReadDownloadedLyrics }
                ?.let { downloadedSong ->
                    runCatching {
                        AudioDownloadManager.getLyricsBundle(context, downloadedSong)
                    }.onFailure { error ->
                        NPLogger.w(
                            "NowPlayingLyrics",
                            "下载歌词读取失败: ${error.message}"
                        )
                    }.getOrNull().also { refreshedLyrics ->
                        if (refreshedLyrics != null) {
                            downloadedLyricsBundle = refreshedLyrics
                        }
                    }
                }
                ?: cachedDownloadedLyrics
            val downloadedRawLyrics = downloadedLyrics?.lyric
            val downloadedRawTranslatedLyrics = downloadedLyrics?.translatedLyric
            val downloadedRawPhoneticLyrics = downloadedLyrics?.romanizedLyric
            val preferredSongId = resolvePreferredNeteaseLyricSongId(song)
            val preferredNeteaseLyric = runCatching {
                if (
                    !isLocalSong &&
                    localRawLyrics == null &&
                    storedRawLyrics == null &&
                    downloadedRawLyrics == null &&
                    preferredSongId != null
                ) {
                    PlayerManager.getPreferredNeteaseLyricContent(preferredSongId)
                } else {
                    ""
                }
            }.getOrNull().orEmpty()
            val rawNeteasePhoneticLyric = runCatching {
                if (
                    !isLocalSong &&
                    localRawPhoneticLyrics == null &&
                    downloadedRawPhoneticLyrics == null &&
                    preferredSongId != null
                ) {
                    PlayerManager.getPreferredNeteaseRomanizedLyricContent(preferredSongId)
                } else {
                    ""
                }
            }.getOrNull().orEmpty()
            val effectiveRawLyrics = resolvePreferredLyricContent(
                matchedLyric = if (isManagedLocalDownload) {
                    resolveManagedDownloadFastLyricText(
                        localLyrics = localLyrics,
                        downloadedLyrics = downloadedLyrics,
                        storedLyric = storedRawLyrics,
                        variant = ManagedLyricVariant.ORIGINAL
                    )
                } else {
                    resolveLyricTextForPlayback(
                        isManagedLocalDownload = false,
                        localLyric = localRawLyrics,
                        storedLyric = storedRawLyrics,
                        downloadedLyric = downloadedRawLyrics
                    )
                },
                preferredNeteaseLyric = preferredNeteaseLyric,
                legacyLyric = null
            )
            val effectiveRawTranslatedLyrics = if (isManagedLocalDownload) {
                resolveManagedDownloadFastLyricText(
                    localLyrics = localLyrics,
                    downloadedLyrics = downloadedLyrics,
                    storedLyric = storedRawTranslatedLyrics,
                    variant = ManagedLyricVariant.TRANSLATED
                )
            } else {
                resolveLyricTextForPlayback(
                    isManagedLocalDownload = false,
                    localLyric = localRawTranslatedLyrics,
                    storedLyric = storedRawTranslatedLyrics,
                    downloadedLyric = downloadedRawTranslatedLyrics
                )
            }
            val effectiveRawPhoneticLyrics = if (isManagedLocalDownload) {
                resolveManagedDownloadFastLyricText(
                    localLyrics = localLyrics,
                    downloadedLyrics = downloadedLyrics,
                    storedLyric = storedRawPhoneticLyrics,
                    variant = ManagedLyricVariant.ROMANIZED
                )
            } else {
                resolveLyricTextForPlayback(
                    isManagedLocalDownload = false,
                    localLyric = localRawPhoneticLyrics,
                    storedLyric = storedRawPhoneticLyrics,
                    downloadedLyric = downloadedRawPhoneticLyrics
                )
            } ?: rawNeteasePhoneticLyric.takeIf { it.isNotBlank() }
            val bypassStoredRawLyrics = shouldBypassCollapsedStoredLyric(effectiveRawLyrics)
            val bypassStoredTranslatedLyrics = shouldBypassCollapsedStoredLyric(
                effectiveRawTranslatedLyrics
            )
            val shouldDelayOnlineLyrics =
                song != null &&
                    extractYouTubeMusicVideoId(song.mediaUri) != null &&
                    currentMediaUrl.isNullOrBlank()
            val resolvedLyrics = when {
                isLocalSong && !isManagedLocalDownload && localRawLyrics != null -> {
                    parseNeteaseLyricsAuto(localRawLyrics)
                }
                isLocalSong && !effectiveRawLyrics.isNullOrBlank() -> {
                    parseNeteaseLyricsAuto(effectiveRawLyrics)
                }
                isLocalSong -> {
                    emptyList()
                }
                bypassStoredRawLyrics && song != null -> {
                    PlayerManager.getLyrics(song)
                }
                bypassStoredRawLyrics -> {
                    emptyList()
                }
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
                    // 当前曲目还在抢首播地址, 先别让歌词请求去争 EJS 和鉴权链路
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
                    isLocalSong && !isManagedLocalDownload && localRawTranslatedLyrics != null -> {
                        parseNeteaseLyricsAuto(localRawTranslatedLyrics)
                    }
                    effectiveRawTranslatedLyrics != null -> {
                        if (effectiveRawTranslatedLyrics.isBlank()) {
                            emptyList()
                        } else if (bypassStoredTranslatedLyrics && song != null) {
                            PlayerManager.getTranslatedLyrics(song)
                        } else if (bypassStoredTranslatedLyrics) {
                            emptyList()
                        } else {
                            parseNeteaseLyricsAuto(effectiveRawTranslatedLyrics)
                        }
                    }
                    isLocalSong -> {
                        emptyList()
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
                    isLocalSong && !isManagedLocalDownload && localRawPhoneticLyrics != null -> {
                        parseNeteaseLyricsAuto(localRawPhoneticLyrics)
                    }
                    downloadedRawPhoneticLyrics != null -> {
                        parseNeteaseLyricsAuto(downloadedRawPhoneticLyrics)
                    }
                    rawNeteasePhoneticLyric.isNotBlank() -> {
                        parseNeteaseLyricsAuto(rawNeteasePhoneticLyric)
                    }
                    isLocalSong -> {
                        emptyList()
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
                rawLyrics = effectiveRawLyrics.takeUnless { bypassStoredRawLyrics },
                rawTranslatedLyrics = effectiveRawTranslatedLyrics.takeUnless {
                    bypassStoredTranslatedLyrics
                },
                rawPhoneticLyrics = effectiveRawPhoneticLyrics,
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
            publishLoadedLyricsState(loadedLyricsState, stage = "background")
        }
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
    val windowWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val isWideLayout = windowWidthDp >= 480.dp
    val useWideLandscapeLayout = isWideLayout && isLandscape
    val useCompactPortraitLayout = shouldUseCompactNowPlayingPortraitLayout(
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
    val nowPlayingControlSize = playbackControlLayoutPreferences.nowPlayingSize
    val useNowPlayingToolbarDock = shouldUseNowPlayingToolbarDock(
        toolbarDockEnabled = nowPlayingToolbarDockEnabled,
        useCompactPortraitLayout = useCompactPortraitLayout,
        controlsAtBottom = nowPlayingControlsAtBottom
    )
    val isCompactTabletLandscape = useWideLandscapeLayout && windowWidthDp < 720.dp
    val baseSecondaryControlButtonSize = when {
        useWideLandscapeLayout && isCompactTabletLandscape -> 42.dp
        useWideLandscapeLayout -> 46.dp
        else -> 42.dp
    }
    val basePrimaryControlButtonSize = when {
        useWideLandscapeLayout && isCompactTabletLandscape -> 46.dp
        useWideLandscapeLayout -> 50.dp
        else -> 42.dp
    }
    val baseControlButtonSpacing = when {
        useWideLandscapeLayout && isCompactTabletLandscape -> 18.dp
        useWideLandscapeLayout -> 22.dp
        useCompactPortraitLayout -> 12.dp
        else -> 20.dp
    }
    val nowPlayingTopActionButtonSize = nowPlayingControlSize.scaleButtonSize(48.dp)
    val nowPlayingTopActionIconSize = nowPlayingControlSize.scaleIconSize(24.dp)
    val nowPlayingTopBarHeight = maxOf(56.dp, nowPlayingTopActionButtonSize)
    val secondaryControlButtonSize = nowPlayingControlSize.scaleButtonSize(
        baseSecondaryControlButtonSize
    )
    val primaryControlButtonSize = nowPlayingControlSize.scaleButtonSize(
        basePrimaryControlButtonSize
    )
    val controlButtonSpacing = baseControlButtonSpacing * nowPlayingControlSize.scale
    val nowPlayingToolbarIconSize = nowPlayingControlSize.scaleIconSize(
        if (useWideLandscapeLayout) 22.dp else 20.dp
    )
    val nowPlayingMainControlIconSize = nowPlayingControlSize.scaleIconSize(24.dp)
    val nowPlayingToolbarMinimumTouchTarget = nowPlayingControlSize.scaleButtonSize(
        PlaybackActionToolbarMinimumTouchTarget
    )

    // 歌词偏移 (平台 + 用户自定义)
    val totalOffset = resolveEffectiveLyricOffsetMs(
        lyricSource = currentSong?.matchedLyricSource,
        cloudMusicDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
        qqMusicDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
        userLyricOffsetMs = currentSong?.userLyricOffsetMs ?: 0L,
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

    val previewCoverUrl = actualCoverUrl
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

                // 手机或竖屏下, 左滑进入歌词页
                if (!useWideLandscapeLayout && lyrics.isNotEmpty()) {
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
                                    contentDescription = stringResource(R.string.player_shuffle),
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
                                    contentDescription = stringResource(R.string.player_previous),
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
                                    playContentDescription = stringResource(R.string.player_play),
                                    pauseContentDescription = stringResource(R.string.player_pause),
                                    restoreVolumeContentDescription = stringResource(R.string.player_restore_volume),
                                    waitingContentDescription = stringResource(R.string.player_waiting),
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
                                    contentDescription = stringResource(R.string.player_next),
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
                                    contentDescription = stringResource(R.string.player_repeat),
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
                        progressInfoSegments = progressInfoSegments,
                        seekEnabled = playbackProgressSeekEnabled,
                        activeContentColor = targetNowPlayingActiveIconColor,
                        useWideLandscapeLayout = useWideLandscapeLayout,
                        onPreviewPositionChange = { previewPositionOverrideMs = it },
                        progressRowModifier = Modifier
                            .sharedBounds(
                                rememberSharedContentState(
                                    key = NowPlayingLyricsSharedTransitionElement.PROGRESS.key
                                ),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                            .zIndex(1f),
                        modifier = Modifier
                            .fillMaxWidth(if (useWideLandscapeLayout) 0.88f else 1f)
                    )
                }

                // 主列内容
                val mainColumnContent: @Composable ColumnScope.() -> Unit = {
                    // 顶部栏
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(nowPlayingTopBarHeight)
                    ) {
                        // 返回按钮 - 左侧
                        HapticIconButton(
                            onClick = onNavigateUp,
                            modifier = Modifier.align(Alignment.CenterStart)
                                .size(nowPlayingTopActionButtonSize)
                                .sharedBounds(
                                    rememberSharedContentState(
                                        key = NowPlayingLyricsSharedTransitionElement.BACK.key
                                    ),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = EnterTransition.None,
                                    exit = ExitTransition.None,
                                ).zIndex(1f)
                        ) {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.action_back),
                                modifier = Modifier.size(nowPlayingTopActionIconSize)
                            )
                        }

                        // 标题 - 居中
                        if (showNowPlayingTitle) {
                            Text(
                                text = stringResource(R.string.player_now_playing),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                                        actionLabel = composeResources.getString(R.string.favorite_add),
                                        warnForLocalSync = willFav
                                    ) {
                                        favOverride = willFav
                                        PlayerManager.toggleCurrentFavorite()
                                    }
                                },
                                enabled = localPlaylistsReady,
                                modifier = Modifier.size(nowPlayingTopActionButtonSize)
                                    .sharedElement(
                                        rememberSharedContentState(key = "btn_favorite"),
                                        animatedVisibilityScope = this@AnimatedContent
                                    ).zIndex(1f)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) stringResource(R.string.nowplaying_favorited) else stringResource(R.string.nowplaying_favorite),
                                    modifier = Modifier.size(nowPlayingTopActionIconSize),
                                    tint = if (isFavorite) {
                                        Color.Red.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            HapticIconButton(
                                onClick = { showMoreOptions = true },
                                modifier = Modifier.size(nowPlayingTopActionButtonSize)
                                    .sharedBounds(
                                        rememberSharedContentState(key = "btn_more"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        enter = EnterTransition.None,
                                        exit = ExitTransition.None,
                                    ).zIndex(1f)
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.nowplaying_more_options),
                                    modifier = Modifier.size(nowPlayingTopActionIconSize)
                                )
                            }
                            if (showMoreOptions && currentSong != null) {
                                MoreOptionsSheet(
                                    viewModel = nowPlayingViewModel,
                                    originalSong = currentSong!!,
                                    queue = displayedQueue,
                                    displayedLyrics = lyrics,
                                    displayedTranslatedLyrics = translatedLyrics,
                                    displayedRomanizedLyrics = phoneticLyrics,
                                    hasPhoneticLyrics = phoneticLyrics.isNotEmpty(),
                                    onDismiss = { showMoreOptions = false },
                                    onShowSongDetails = { detailSong = it },
                                    onEnterAlbum = onEnterAlbum,
                                    onNavigateUp = onNavigateUp,
                                    snackbarHostState = snackbarHostState,
                                    lyricFontScalePage = LyricFontScalePage.COVER,
                                    lyricFontScales = lyricFontScales,
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
                                        rememberSharedContentState(
                                            key = NowPlayingLyricsSharedTransitionElement.COVER.key
                                        ),
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
                                    .then(
                                        if (
                                            coverPreviewOnTapEnabled ||
                                                coverPreviewOnLongPressEnabled
                                        ) {
                                            Modifier.combinedClickable(
                                                onClick = {
                                                    if (coverPreviewOnTapEnabled) {
                                                        if (actualCoverUrl.isNullOrBlank()) {
                                                            screenScope.launch {
                                                                snackbarHostState.showNeriSnackbar(
                                                                    composeResources.getString(
                                                                        R.string.cover_preview_unavailable
                                                                    )
                                                                )
                                                            }
                                                        } else {
                                                            showCoverPreview = true
                                                        }
                                                    }
                                                },
                                                onLongClick = {
                                                    if (coverPreviewOnLongPressEnabled) {
                                                        if (actualCoverUrl.isNullOrBlank()) {
                                                            screenScope.launch {
                                                                snackbarHostState.showNeriSnackbar(
                                                                    composeResources.getString(
                                                                        R.string.cover_preview_unavailable
                                                                    )
                                                                )
                                                            }
                                                        } else {
                                                            showCoverPreview = true
                                                        }
                                                    }
                                                }
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                StableNowPlayingCoverImage(
                                    coverUrl = currentCoverUrl,
                                    songKey = coverSongKey,
                                    context = context,
                                    coverRequestSizePx = coverRequestSizePx,
                                    offlineMode = offlineMode,
                                    coverCacheKey = listOf(
                                        "nowplaying-cover",
                                        currentCoverUrl.orEmpty(),
                                        downloadPresenceVersion
                                    ).joinToString("|"),
                                    contentDescription = currentSong?.customName
                                        ?: currentSong?.name
                                        ?: "",
                                    modifier = Modifier.fillMaxSize()
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
                            BoxWithConstraints {
                                NowPlayingSongTitle(
                                    text = currentSong?.customName ?: currentSong?.name ?: "",
                                    marqueeEnabled = nowPlayingSongTitleMarqueeEnabled,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = targetNowPlayingColorScheme.onSurface,
                                    modifier = Modifier
                                        .widthIn(max = maxWidth)
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
                                            rememberSharedContentState(
                                                key = NowPlayingLyricsSharedTransitionElement.ARTIST.key
                                            ),
                                            animatedVisibilityScope = this@AnimatedContent
                                        )
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = openCurrentArtist,
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

                    if (!nowPlayingProgressAtBottom) {
                        Spacer(Modifier.height(12.dp))
                        nowPlayingProgressSection()
                        Spacer(Modifier.height(if (useWideLandscapeLayout) 14.dp else 10.dp))
                    }

                    if (!nowPlayingControlsAtBottom) {
                        mainPlaybackControls()
                    }

                    // 手机/竖屏, 内嵌迷你歌词
                    if (!useWideLandscapeLayout && showCoverPageLyrics && lyrics.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))

                        NowPlayingLyricsPane(
                            lyrics = plainLyrics,
                            playbackSessionKey = currentSong?.stableKey(),
                            previewPositionOverrideMs = previewPositionOverrideMs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(8f),
                            textColor = MaterialTheme.colorScheme.onBackground,
                            fontSize = scaledLyricFontSize(18f, coverLyricFontScale).sp,
                            translationFontSize = scaledLyricFontSize(14f, coverTranslationFontScale).sp,
                            visualSpec = LyricVisualSpec(),
                            lyricOffsetMs = totalOffset,
                            lyricBlurEnabled = lyricBlurEnabled,
                            lyricBlurAmount = lyricBlurAmount,
                            isPlaying = isPlaying && previewPositionOverrideMs == null,
                            playbackSpeed = playbackSoundState.speed,
                            onLyricClick = { entry ->
                                seekToLyricSafely(
                                    positionMs = entry.startTimeMs,
                                    playbackDurationMs = durationMs,
                                    songDurationMs = currentSong?.durationMs ?: 0L
                                )
                            },
                            onLyricLongClick = { entry -> lyricShareInitialLine = entry },
                            showEmbeddedTranslations = showLyricTranslation &&
                                !usePhoneticTranslation,
                            translatedLyrics = if (showLyricTranslation) secondaryPlainLyrics else null
                        )
                    }

                    // 将下面的内容推到底部, 平板横屏也保持贴近底部的手感
                    Spacer(modifier = Modifier.weight(1f))

                    if (nowPlayingControlsAtBottom) {
                        if (nowPlayingProgressAtBottom) {
                            nowPlayingProgressSection()
                            Spacer(Modifier.height(if (useWideLandscapeLayout) 14.dp else 10.dp))
                        }
                        mainPlaybackControls()
                        Spacer(Modifier.height(4.dp))
                    }

                    // 底部操作栏 (固定在底部)
                    Column(
                        modifier = if (useWideLandscapeLayout) {
                            Modifier
                                .fillMaxWidth(0.9f)
                                .windowInsetsPadding(WindowInsets.navigationBars)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(
                                    horizontal = if (useCompactPortraitLayout) 4.dp else 16.dp,
                                    vertical = 8.dp
                                )
                                .padding(bottom = if (useNowPlayingToolbarDock) 2.dp else 0.dp)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val toolbarContainerModifier = Modifier.fillMaxWidth()
                        val toolbarContent: @Composable () -> Unit = {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val preferredToolbarPadding = when {
                                    useCompactPortraitLayout -> 0.dp
                                    useNowPlayingToolbarDock || useWideLandscapeLayout -> 18.dp
                                    else -> 6.dp
                                }
                                val toolbarLayout = resolvePlaybackActionToolbarLayout(
                                    availableWidth = maxWidth,
                                    preferredHorizontalPadding = preferredToolbarPadding,
                                    defaultIconSize = nowPlayingToolbarIconSize,
                                    preferredMinimumTouchTarget =
                                        nowPlayingToolbarMinimumTouchTarget
                                )
                                CompositionLocalProvider(
                                    LocalMinimumInteractiveComponentSize provides
                                        toolbarLayout.minimumInteractiveComponentSize
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = toolbarLayout.horizontalPadding,
                                                vertical = if (useNowPlayingToolbarDock || useWideLandscapeLayout) {
                                                    12.dp
                                                } else {
                                                    8.dp
                                                }
                                            ),
                                        horizontalArrangement = when {
                                            toolbarLayout.useEqualWidthSlots -> Arrangement.Start
                                            useWideLandscapeLayout || useNowPlayingToolbarDock -> {
                                                Arrangement.SpaceEvenly
                                            }
                                            else -> Arrangement.SpaceBetween
                                        },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val toolbarActionModifier = if (toolbarLayout.useEqualWidthSlots) {
                                            Modifier.weight(1f)
                                        } else {
                                            Modifier
                                        }
                                // 播放队列
                                HapticIconButton(onClick = { showQueueSheet = true },
                                    modifier = toolbarActionModifier
                                        .sharedBounds(
                                        rememberSharedContentState(key = "btn_queue"),
                                            animatedVisibilityScope = this@AnimatedContent,
                                            enter = EnterTransition.None,
                                            exit = ExitTransition.None,
                                        ).zIndex(1f)) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.QueueMusic,
                                        contentDescription = stringResource(R.string.playlist_queue),
                                        modifier = Modifier.size(toolbarLayout.iconSize)
                                    )
                                }

                                // 定时器按钮
                                HapticIconButton(onClick = { showSleepTimerDialog = true },
                                    modifier = toolbarActionModifier
                                    .sharedBounds(
                                        rememberSharedContentState(key = "btn_timer"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        enter = EnterTransition.None,
                                        exit = ExitTransition.None,
                                    ).zIndex(1f)) {
                                    Icon(
                                        Icons.Outlined.Timer,
                                        contentDescription = stringResource(R.string.sleep_timer_short),
                                        tint = if (sleepTimerState.isActive) {
                                            nowPlayingActiveIconColor
                                        } else {
                                            LocalContentColor.current
                                        },
                                        modifier = Modifier.size(toolbarLayout.iconSize)
                                    )
                                }

                                // 音量按钮 (根据设备显示不同图标, 居中)
                                val audioDeviceInfo = rememberAudioDeviceInfo()
                                HapticIconButton(onClick = { showVolumeSheet = true },
                                    modifier = toolbarActionModifier
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
                                        modifier = Modifier.size(toolbarLayout.iconSize)
                                    )
                                }

                                // 歌词按钮
                                HapticIconButton(
                                    onClick = { onShowLyricsScreenChange(!showLyricsScreen) },
                                    enabled = lyrics.isNotEmpty(),
                                    modifier = toolbarActionModifier
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
                                                nowPlayingActiveIconColor
                                            } else {
                                                LocalContentColor.current
                                            },
                                            modifier = Modifier.size(toolbarLayout.iconSize)
                                        )
                                    }
                                }

                                // 添加到歌单
                                HapticIconButton(onClick = { showAddSheet = true },
                                    modifier = toolbarActionModifier
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
                                        modifier = Modifier.size(toolbarLayout.iconSize)
                                    )
                                }
                                    }
                                }
                            }
                        }

                        if (useNowPlayingToolbarDock) {
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
                                        lyricFontScale = coverLyricFontScale,
                                        translationFontScale = coverTranslationFontScale,
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
                                        onSeekTo = { position ->
                                            seekToLyricSafely(
                                                positionMs = position,
                                                playbackDurationMs = durationMs,
                                                songDurationMs = currentSong?.durationMs ?: 0L
                                            )
                                        }
                                    )
                                }

                                NowPlayingWideLyricsMode.SYNCED -> {
                                    NowPlayingLyricsPane(
                                        lyrics = plainLyrics,
                                        playbackSessionKey = currentSong?.stableKey(),
                                        previewPositionOverrideMs = previewPositionOverrideMs,
                                        modifier = Modifier.fillMaxSize(),
                                        textColor = MaterialTheme.colorScheme.onBackground,
                                        fontSize = scaledLyricFontSize(18f, coverLyricFontScale).sp,
                                        translationFontSize = scaledLyricFontSize(14f, coverTranslationFontScale).sp,
                                        visualSpec = LyricVisualSpec(),
                                        lyricOffsetMs = totalOffset,
                                        lyricBlurEnabled = lyricBlurEnabled,
                                        lyricBlurAmount = lyricBlurAmount,
                                        isPlaying = isPlaying && previewPositionOverrideMs == null,
                                        playbackSpeed = playbackSoundState.speed,
                                        onLyricClick = { entry ->
                                            seekToLyricSafely(
                                                positionMs = entry.startTimeMs,
                                                playbackDurationMs = durationMs,
                                                songDurationMs = currentSong?.durationMs ?: 0L
                                            )
                                        },
                                        onLyricLongClick = { entry ->
                                            lyricShareInitialLine = entry
                                        },
                                        showEmbeddedTranslations = showLyricTranslation &&
                                            !usePhoneticTranslation,
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

            if (showQualitySwitchDialog && currentPlaybackAudioInfo != null) {
                NowPlayingQualityOptionsDialog(
                    title = stringResource(R.string.nowplaying_quality_switch_title),
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
                                            actionLabel = composeResources.getString(R.string.playlist_add_to)
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

internal fun isNeteaseArtistNavigationSource(song: SongItem): Boolean {
    val channelId = song.channelId?.trim()
    val isNeteaseChannel = channelId.equals("netease", ignoreCase = true)
    if (!channelId.isNullOrBlank() && !isNeteaseChannel) return false
    if (song.album.startsWith(PlayerManager.BILI_SOURCE_TAG, ignoreCase = true)) return false
    if (channelId.equals("youtubeMusic", ignoreCase = true) || isYouTubeMusicSong(song)) {
        return false
    }

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
    if (isNeteaseChannel ||
        song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG, ignoreCase = true) ||
        song.mediaUri?.contains("music.163.com", ignoreCase = true) == true ||
        isManagedNeteaseDownload
    ) {
        return true
    }

    if (song.isLocalSong()) return false
    return hasCachedArtists || hasNeteaseCover
}

internal fun isBiliUploaderNavigationSource(song: SongItem): Boolean {
    return song.id > 0L && song.album.startsWith(
        PlayerManager.BILI_SOURCE_TAG,
        ignoreCase = true
    )
}

internal fun isYouTubeMusicArtistNavigationSource(song: SongItem): Boolean {
    return song.artist.isNotBlank() && (
        song.channelId.equals("youtubeMusic", ignoreCase = true) || isYouTubeMusicSong(song)
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YouTubeMusicCreatorPickerSheet(
    creators: List<YouTubeMusicCreatorSummary>,
    onDismiss: () -> Unit,
    onSelect: (YouTubeMusicCreatorSummary) -> Unit
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
                text = stringResource(R.string.youtube_creator_choose_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            creators.forEach { creator ->
                ListItem(
                    headlineContent = { Text(creator.title) },
                    supportingContent = creator.subtitle
                        .takeIf(String::isNotBlank)
                        ?.let { subtitle -> { Text(subtitle) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(creator) }
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
    BILI_VIDEO_SKIP,
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
    displayedRomanizedLyrics: List<LyricEntry> = emptyList(),
    hasPhoneticLyrics: Boolean = false,
    onDismiss: () -> Unit,
    onShowSongDetails: (SongItem) -> Unit = {},
    onEnterAlbum: (AlbumSummary) -> Unit,
    onNavigateUp: () -> Unit,
    snackbarHostState: SnackbarHostState,
    lyricFontScalePage: LyricFontScalePage,
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
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
    val lyricFontScaleTarget = lyricFontScales.lyricTargetFor(lyricFontScalePage)
    val translationFontScaleTarget = lyricFontScales.translationTargetFor(lyricFontScalePage)
    val currentLyricFontScale = lyricFontScales.scaleFor(lyricFontScaleTarget)
    val currentTranslationFontScale = lyricFontScales.scaleFor(translationFontScaleTarget)

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
        sheetGesturesEnabled = page != MoreOptionsPage.LISTEN_TOGETHER,
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

        Box(modifier = Modifier.fillMaxWidth()) {
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
                        lyricFontScale = currentLyricFontScale,
                        translationFontScale = currentTranslationFontScale,
                        currentPlaybackAudioInfo = currentPlaybackAudioInfo,
                        isDismissing = isDismissing,
                        snackbarHostState = snackbarHostState,
                        onOpenSearch = { page = MoreOptionsPage.SEARCH },
                        onOpenEditInfo = { page = MoreOptionsPage.EDIT_INFO },
                        onOpenPlaybackSound = { page = MoreOptionsPage.PLAYBACK_SOUND },
                        onOpenLyricBehavior = { page = MoreOptionsPage.LYRIC_BEHAVIOR },
                        onOpenFontSize = { page = MoreOptionsPage.FONT_SIZE },
                        onOpenBiliVideoSkip = { page = MoreOptionsPage.BILI_VIDEO_SKIP },
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
                            .bottomSheetScrollGuard()
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

                MoreOptionsPage.BILI_VIDEO_SKIP -> {
                    val currentPosition by PlayerManager.playbackPositionFlow
                        .collectAsStateWithLifecycle()
                    val isPlaying by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
                    val activeBiliTargetGeneration by BiliVideoSkipPlaybackController
                        .activeTrackGeneration
                        .collectAsStateWithLifecycle()
                    val currentBiliTarget = remember(actualSong, activeBiliTargetGeneration) {
                        BiliVideoSkipPlaybackController.activeTargetFor(actualSong)
                    }
                    BiliVideoSkipIntervalsContent(
                        title = stringResource(R.string.bili_video_skip_title),
                        targetResolverKey = actualSong.stableKey(),
                        loadTargetOptions = {
                            resolveBiliVideoSkipTargetOptions(
                                song = actualSong,
                                client = AppContainer.biliClient
                            )
                        },
                        initialTarget = currentBiliTarget,
                        currentPlaybackPositionMs = currentPosition,
                        currentPlaybackTarget = currentBiliTarget,
                        currentPlaybackIsPlaying = isPlaying,
                        onTogglePlayback = { PlayerManager.togglePlayPauseWithoutFade() },
                        onSeekToPlaybackPosition = { positionMs ->
                            PlayerManager.seekTo(positionMs)
                        },
                        onDismiss = { page = MoreOptionsPage.MAIN }
                    )
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
                        currentLyricScale = currentLyricFontScale,
                        currentTranslationScale = currentTranslationFontScale,
                        onLyricScaleCommit = { scale ->
                            onLyricFontScaleChange(lyricFontScaleTarget, scale)
                        },
                        onTranslationScaleCommit = { scale ->
                            onLyricFontScaleChange(translationFontScaleTarget, scale)
                        },
                        onDismiss = { page = MoreOptionsPage.MAIN }
                    )
                }

                MoreOptionsPage.EDIT_INFO -> {
                    EditSongInfoSheet(
                        viewModel = viewModel,
                        originalSong = actualSong,
                        displayedLyrics = displayedLyrics,
                        displayedTranslatedLyrics = displayedTranslatedLyrics,
                        displayedRomanizedLyrics = displayedRomanizedLyrics,
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
        }

        NeriOverlaySnackbarHost(
            hostState = snackbarHostState,
            bottomPadding = LocalMiniPlayerHeight.current
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
    highlightedContentColor: Color,
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
                        highlightedContentColor.copy(alpha = 0.92f)
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
    currentLyricScale: Float,
    currentTranslationScale: Float,
    onLyricScaleCommit: (Float) -> Unit,
    onTranslationScaleCommit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var lyricSliderValue by remember {
        mutableFloatStateOf(normalizeLyricFontScale(currentLyricScale))
    }
    var translationSliderValue by remember {
        mutableFloatStateOf(normalizeLyricFontScale(currentTranslationScale))
    }

    LaunchedEffect(currentLyricScale) {
        lyricSliderValue = normalizeLyricFontScale(currentLyricScale)
    }

    LaunchedEffect(currentTranslationScale) {
        translationSliderValue = normalizeLyricFontScale(currentTranslationScale)
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
            text = stringResource(R.string.settings_lyrics_font_scale_hint),
            style = MaterialTheme.typography.bodySmall
        )

        SheetLyricFontScaleSlider(
            title = stringResource(R.string.settings_lyrics_lyric_font_size),
            currentScale = lyricSliderValue,
            onScaleChange = { lyricSliderValue = it },
            onScaleCommit = { onLyricScaleCommit(normalizeLyricFontScale(lyricSliderValue)) },
            sampleText = stringResource(R.string.nowplaying_lyrics_sample),
            sampleBaseSizeSp = 18f
        )
        SheetLyricFontScaleSlider(
            title = stringResource(R.string.settings_lyrics_translation_font_size),
            currentScale = translationSliderValue,
            onScaleChange = { translationSliderValue = it },
            onScaleCommit = {
                onTranslationScaleCommit(normalizeLyricFontScale(translationSliderValue))
            },
            sampleText = stringResource(R.string.settings_lyrics_translation_sample),
            sampleBaseSizeSp = 14f
        )

        Spacer(Modifier.height(16.dp))
        HapticTextButton(onClick = {
            onLyricScaleCommit(normalizeLyricFontScale(lyricSliderValue))
            onTranslationScaleCommit(normalizeLyricFontScale(translationSliderValue))
            onDismiss()
        }) {
            Text(stringResource(R.string.action_done))
        }
    }
}

@Composable
private fun SheetLyricFontScaleSlider(
    title: String,
    currentScale: Float,
    onScaleChange: (Float) -> Unit,
    onScaleCommit: () -> Unit,
    sampleText: String,
    sampleBaseSizeSp: Float
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${(currentScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Slider(
            value = currentScale,
            onValueChange = onScaleChange,
            onValueChangeFinished = onScaleCommit,
            valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
            steps = 10
        )
        Text(
            text = sampleText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
            fontSize = scaledLyricFontSize(sampleBaseSizeSp, currentScale).sp
        )
    }
}

internal fun resolveEditSongInitialCoverUrl(
    song: SongItem,
    resolvedDisplayCoverUrl: String?
): String {
    val directCover = song.displayCoverUrl()
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
    val localDownloadedCover = AudioDownloadManager.peekLocalCoverUri(song)
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { CustomSongCoverStorage.isRemoteReference(it) }
    val originalCover = song.originalCoverUrl
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
    val resolvedCover = resolvedDisplayCoverUrl?.takeIf { it.isNotBlank() }
    return when {
        directCover != null && !CustomSongCoverStorage.isRemoteReference(directCover) -> directCover
        localDownloadedCover != null -> localDownloadedCover
        directCover != null -> directCover
        originalCover != null -> originalCover
        resolvedCover != null -> resolvedCover
        else -> ""
    }
}

internal fun shouldApplyResolvedEditSongCover(
    userHasEdited: Boolean,
    currentCoverUrl: String,
    resolvedDisplayCoverUrl: String?
): Boolean {
    return !userHasEdited &&
        currentCoverUrl.isBlank() &&
        !resolvedDisplayCoverUrl.isNullOrBlank()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun EditSongInfoSheet(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    displayedRomanizedLyrics: List<LyricEntry> = emptyList(),
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState,
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun clearEditSongInfoFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    // 监听当前播放的歌曲, 以便在"获取歌曲信息"后更新UI
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val actualSong = if (currentSong?.sameIdentityAs(originalSong) == true) {
        currentSong!!
    } else {
        originalSong
    }
    val canReplaceCoverFromLocalFile = shouldAllowLocalCoverReplacement(actualSong, context)
    val resolvedDisplayCoverUrl = rememberSongDisplayCoverUrl(actualSong)

    var coverUrl by remember {
        mutableStateOf(
            resolveEditSongInitialCoverUrl(actualSong, resolvedDisplayCoverUrl)
        )
    }
    var songName by remember { mutableStateOf(actualSong.customName ?: actualSong.name) }
    var artistName by remember { mutableStateOf(actualSong.customArtist ?: actualSong.artist) }
    var showSearchResults by remember { mutableStateOf(false) }
    var selectedSongForFill by remember { mutableStateOf<SongSearchInfo?>(null) }
    var lyricsEditorSeed by remember { mutableStateOf<LyricsEditorSeed?>(null) }
    var pendingLyricsSourceSeed by remember { mutableStateOf<LyricsEditorSeed?>(null) }
    var isLyricsEditorOpening by remember { mutableStateOf(false) }
    var isPendingEmbeddedLyricsLoading by remember { mutableStateOf(false) }
    var shouldClearLyrics by remember { mutableStateOf(false) }  // 标记是否应该清除歌词(B站)
    var shouldRestoreLyrics by remember { mutableStateOf(false) }  // 标记是否应该恢复歌词(网易云)
    var originalLyric by remember { mutableStateOf<String?>(null) }  // 保存要恢复的原始歌词
    var originalTranslatedLyric by remember { mutableStateOf<String?>(null) }  // 保存要恢复的原始翻译歌词
    var originalRomanizedLyric by remember { mutableStateOf<String?>(null) }
    var shouldRestoreCoverBase by remember { mutableStateOf(false) }
    var shouldRestoreTitleBase by remember { mutableStateOf(false) }
    var shouldRestoreArtistBase by remember { mutableStateOf(false) }
    var shouldClearMatchedMetadata by remember { mutableStateOf(false) }
    var showLocalMetadataWriteBackConfirm by remember { mutableStateOf(false) }
    var showFillLyricsMetadataWriteBackConfirm by remember { mutableStateOf(false) }
    var lyricsEditorRequestId by remember { mutableIntStateOf(0) }
    var originalInfoRequestId by remember { mutableIntStateOf(0) }
    var showLocalCoverSyncConfirm by remember { mutableStateOf(false) }
    var pendingCoverReplacementSong by remember { mutableStateOf<SongItem?>(null) }

    // 标记用户是否手动编辑过, 避免自动重置
    var userHasEdited by remember { mutableStateOf(false) }
    var isCoverImporting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { sourceUri ->
        val targetSong = pendingCoverReplacementSong
        pendingCoverReplacementSong = null
        sourceUri ?: return@rememberLauncherForActivityResult
        val verifiedTargetSong = resolvePendingLocalCoverReplacementTarget(
            pendingSong = targetSong,
            currentSong = currentSong,
            context = context
        ) ?: return@rememberLauncherForActivityResult

        originalInfoRequestId += 1
        isCoverImporting = true
        coroutineScope.launch {
            try {
                val importedCover = CustomSongCoverStorage.importFromUri(
                    context = context,
                    song = verifiedTargetSong,
                    sourceUri = sourceUri
                )
                if (importedCover == null) {
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(R.string.music_cover_import_failed)
                    )
                } else {
                    coverUrl = importedCover.toString()
                    userHasEdited = true
                    shouldRestoreCoverBase = false
                }
            } catch (error: Exception) {
                NPLogger.e("NowPlayingScreen", "导入本地封面失败", error)
                snackbarHostState.showNeriSnackbar(
                    composeResources.getString(R.string.music_cover_import_failed)
                )
            } finally {
                isCoverImporting = false
            }
        }
    }

    val searchState by viewModel.manualSearchState.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    // 当歌曲信息更新时, 同步更新UI (仅在用户未手动编辑时)
    LaunchedEffect(actualSong.stableKey()) {
        if (!userHasEdited) {
            coverUrl = resolveEditSongInitialCoverUrl(actualSong, resolvedDisplayCoverUrl)
            songName = actualSong.customName ?: actualSong.name
            artistName = actualSong.customArtist ?: actualSong.artist
            shouldRestoreCoverBase = false
            shouldRestoreTitleBase = false
            shouldRestoreArtistBase = false
            shouldClearMatchedMetadata = false
        }
    }

    LaunchedEffect(actualSong.stableKey(), resolvedDisplayCoverUrl) {
        if (shouldApplyResolvedEditSongCover(
                userHasEdited = userHasEdited,
                currentCoverUrl = coverUrl,
                resolvedDisplayCoverUrl = resolvedDisplayCoverUrl
            )
        ) {
            coverUrl = resolvedDisplayCoverUrl.orEmpty()
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
        val requestId = originalInfoRequestId + 1
        originalInfoRequestId = requestId
        val requestSongKey = actualSong.stableKey()
        viewModel.fetchOriginalInfo(context, actualSong) { success, info, _ ->
            if (
                originalInfoRequestId != requestId ||
                    actualSong.stableKey() != requestSongKey
            ) {
                return@fetchOriginalInfo
            }
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
                    info.coverUrl?.let { restoredCover ->
                        coverUrl = restoredCover
                        shouldRestoreCoverBase = true
                    }
                }
                if (restoreLyrics) {
                    if (info.shouldClearLyrics) {
                        shouldClearLyrics = true
                        shouldRestoreLyrics = false
                        originalLyric = null
                        originalTranslatedLyric = null
                        originalRomanizedLyric = null
                    } else {
                        shouldClearLyrics = false
                        shouldRestoreLyrics = info.lyric != null ||
                            info.translatedLyric != null ||
                            info.romanizedLyric != null
                        originalLyric = info.lyric
                        originalTranslatedLyric = info.translatedLyric
                        originalRomanizedLyric = info.romanizedLyric
                    }
                }
                if (restoreCover && restoreTitle && restoreArtist && restoreLyrics) {
                    shouldClearMatchedMetadata = true
                }
                userHasEdited = true
            }
        }
    }

    fun saveEditedSongInfo(writeLocalMetadata: Boolean) {
        if (isSaving) return
        isSaving = true
        originalInfoRequestId += 1
        coroutineScope.launch {
            try {
                val latestSong = PlayerManager.currentSongFlow.value
                    ?.takeIf { it.sameIdentityAs(actualSong) }
                    ?: actualSong
                val writeLyricsToLocalMetadata = writeLocalMetadata &&
                    (shouldClearLyrics || shouldRestoreLyrics)
                var lyricsWriteSucceeded = true
                // 处理歌词: 清除(B站)或恢复(网易云)
                if (shouldClearLyrics) {
                    // B站音源: 清除歌词
                    NPLogger.d("NowPlayingScreen", "=== 开始清除歌词流程 ===")
                    NPLogger.d("NowPlayingScreen", "actualSong详情: id=${actualSong.id}, album='${actualSong.album}', name='${actualSong.name}', artist='${actualSong.artist}'")
                    NPLogger.d("NowPlayingScreen", "当前歌词状态: matchedLyric=${actualSong.matchedLyric?.take(50)}, matchedTranslatedLyric=${actualSong.matchedTranslatedLyric?.take(50)}")

                    NPLogger.d("NowPlayingScreen", "准备调用PlayerManager.updateSongLyricsAndTranslation清除歌词")
                    lyricsWriteSucceeded = PlayerManager.updateSongLyricsAndTranslation(
                        latestSong,
                        "",  // 清空歌词
                        "",  // 清空翻译歌词
                        newRomanizedLyrics = "",
                        writeLocalMetadata = false,
                        persistLocalSidecars = false,
                        syncDownloadedMetadata = false
                    )
                    NPLogger.d("NowPlayingScreen", "PlayerManager.updateSongLyricsAndTranslation调用完成")
                    NPLogger.d("NowPlayingScreen", "=== 清除歌词流程完成 ===")
                } else if (shouldRestoreLyrics) {
                    // 网易云音源: 恢复歌词
                    NPLogger.d("NowPlayingScreen", "=== 开始恢复歌词流程 ===")
                    NPLogger.d("NowPlayingScreen", "actualSong详情: id=${actualSong.id}, album='${actualSong.album}'")
                    NPLogger.d("NowPlayingScreen", "原始歌词: lyric=${originalLyric?.take(50)}, translatedLyric=${originalTranslatedLyric?.take(50)}")

                    NPLogger.d("NowPlayingScreen", "准备调用PlayerManager.updateSongLyricsAndTranslation恢复歌词")
                    lyricsWriteSucceeded = PlayerManager.updateSongLyricsAndTranslation(
                        latestSong,
                        originalLyric,  // 恢复原始歌词
                        originalTranslatedLyric,  // 恢复原始翻译歌词
                        newRomanizedLyrics = originalRomanizedLyric,
                        writeLocalMetadata = false,
                        persistLocalSidecars = false,
                        syncDownloadedMetadata = false
                    )
                    NPLogger.d("NowPlayingScreen", "PlayerManager.updateSongLyricsAndTranslation调用完成")
                    NPLogger.d("NowPlayingScreen", "=== 恢复歌词流程完成 ===")
                }

                if (!lyricsWriteSucceeded) {
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(R.string.local_song_lyrics_write_failed)
                    )
                    return@launch
                }

                val songAfterLyricsUpdate = PlayerManager.currentSongFlow.value
                    ?.takeIf { it.sameIdentityAs(actualSong) }
                    ?: latestSong

                // 然后更新歌曲信息
                val metadataWriteSucceeded = viewModel.updateSongInfo(
                    originalSong = songAfterLyricsUpdate,
                    newCoverUrl = coverUrl.ifBlank { null },
                    newName = songName,
                    newArtist = artistName,
                    restoreBaseCover = shouldRestoreCoverBase,
                    restoreBaseName = shouldRestoreTitleBase,
                    restoreBaseArtist = shouldRestoreArtistBase,
                    clearMatchedMetadata = shouldClearMatchedMetadata,
                    writeLocalMetadata = writeLocalMetadata,
                    writeLyrics = writeLyricsToLocalMetadata
                )

                if (!metadataWriteSucceeded) {
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(R.string.local_song_metadata_write_failed)
                    )
                    return@launch
                }

                // 重置编辑标志, 允许自动更新
                userHasEdited = false
                shouldRestoreCoverBase = false
                shouldRestoreTitleBase = false
                shouldRestoreArtistBase = false
                shouldClearMatchedMetadata = false
                shouldClearLyrics = false
                shouldRestoreLyrics = false
                originalLyric = null
                originalTranslatedLyric = null
                originalRomanizedLyric = null
                clearEditSongInfoFocus()
                onDismiss()
            } catch (e: Exception) {
                NPLogger.e("NowPlayingScreen", "保存歌曲信息失败", e)
                snackbarHostState.showNeriSnackbar(
                    composeResources.getString(R.string.toast_save_failed, e.message.orEmpty()),
                )
            } finally {
                isSaving = false
            }
        }
    }

    fun writeFetchedLyricsToLocalMetadata() {
        if (isSaving) return
        isSaving = true
        coroutineScope.launch {
            try {
                val latestSong = PlayerManager.currentSongFlow.value
                    ?.takeIf { it.sameIdentityAs(actualSong) }
                    ?: actualSong
                val writeSucceeded = viewModel.updateSongInfo(
                    originalSong = latestSong,
                    newCoverUrl = latestSong.customCoverUrl ?: latestSong.coverUrl,
                    newName = latestSong.customName ?: latestSong.name,
                    newArtist = latestSong.customArtist ?: latestSong.artist,
                    writeLocalMetadata = true,
                    writeLyrics = true
                )
                if (!writeSucceeded) {
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(R.string.local_song_metadata_write_failed)
                    )
                }
            } catch (error: Exception) {
                NPLogger.e("NowPlayingScreen", "回写填充歌词失败", error)
                snackbarHostState.showNeriSnackbar(
                    composeResources.getString(R.string.toast_save_failed, error.message.orEmpty())
                )
            } finally {
                isSaving = false
            }
        }
    }

    // 使用 AnimatedVisibility 控制内容显示, 避免重叠
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

            HapticTextButton(
                onClick = {
                    clearEditSongInfoFocus()
                    onDismiss()
                }
            ) {
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
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = canReplaceCoverFromLocalFile) {
                            clearEditSongInfoFocus()
                            showLocalCoverSyncConfirm = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = offlineCachedImageRequest(
                                context = context,
                                data = coverUrl,
                                sizePx = 384,
                                allowHardware = false,
                                offlineMode = offlineMode
                            ),
                            contentDescription = if (canReplaceCoverFromLocalFile) {
                                stringResource(R.string.music_edit_cover)
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = if (canReplaceCoverFromLocalFile) {
                                stringResource(R.string.music_edit_cover)
                            } else {
                                null
                            }
                        )
                    }
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
                    clearEditSongInfoFocus()
                    val displayedLyricsSnapshot = displayedLyrics.toList()
                    val displayedTranslatedLyricsSnapshot = displayedTranslatedLyrics.toList()
                    val displayedLyricsText = displayedLyricsSnapshot.toEditableLyricsText()
                    val displayedTranslatedLyricsText =
                        displayedTranslatedLyricsSnapshot.toEditableLyricsText()
                    val displayedRomanizedLyricsText = displayedRomanizedLyrics
                        .toEditableLyricsText()
                    val requestId = lyricsEditorRequestId + 1
                    lyricsEditorRequestId = requestId

                    // 本地歌曲必须先确认侧载是否存在, 否则编辑器和来源弹窗会竞态
                    lyricsEditorSeed = null
                    pendingLyricsSourceSeed = null
                    isLyricsEditorOpening = true
                    isPendingEmbeddedLyricsLoading = false

                    coroutineScope.launch {
                        try {
                            if (actualSong.isLocalSong()) {
                                val lyricsSources = withContext(Dispatchers.IO) {
                                    val isManagedLocalDownload = hasCachedLocalDownload(
                                        context,
                                        actualSong
                                    )
                                    val downloadedLyrics = if (isManagedLocalDownload) {
                                        try {
                                            AudioDownloadManager.getLyricsBundleFast(context, actualSong)
                                        } catch (error: SecurityException) {
                                            throw error
                                        } catch (error: Exception) {
                                            NPLogger.w(
                                                "NowPlayingLyrics",
                                                "编辑器读取下载歌词索引失败: ${error.message}"
                                            )
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                    val shouldProbeLocalSidecars = !isManagedLocalDownload ||
                                        downloadedLyrics == null ||
                                        !downloadedLyrics.hasOriginalSidecar ||
                                        !downloadedLyrics.hasTranslatedSidecar ||
                                        !downloadedLyrics.hasRomanizedSidecar
                                    val localLyrics = if (shouldProbeLocalSidecars) {
                                        // 保留绝对路径, 否则 managed fast miss 时无法找到同目录 Lyrics/
                                        val sidecarProbeSong = actualSong
                                        try {
                                            LocalMediaSupport.inspectLyricsFast(
                                                context = context,
                                                song = sidecarProbeSong,
                                                includeStoredFallback = !isManagedLocalDownload,
                                                includeEmbeddedFallback = false,
                                                forceRefresh = true
                                            )
                                        } catch (error: SecurityException) {
                                            throw error
                                        } catch (error: Exception) {
                                            NPLogger.w(
                                                "NowPlayingLyrics",
                                                "编辑器读取本地歌词快速失败: ${error.message}"
                                            )
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                    val hasSidecar = downloadedLyrics?.hasOriginalSidecar == true ||
                                        downloadedLyrics?.hasTranslatedSidecar == true ||
                                        downloadedLyrics?.hasRomanizedSidecar == true ||
                                        localLyrics?.hasOriginalSidecar == true ||
                                        localLyrics?.hasTranslatedSidecar == true ||
                                        localLyrics?.hasRomanizedSidecar == true
                                    val embeddedLyrics = if (!hasSidecar) {
                                        try {
                                            LocalMediaSupport.inspectLyricsFast(
                                                context = context,
                                                song = actualSong,
                                                includeStoredFallback = false,
                                                includeEmbeddedFallback = true,
                                                forceRefresh = true
                                            )
                                        } catch (error: SecurityException) {
                                            throw error
                                        } catch (error: Exception) {
                                            NPLogger.w(
                                                "NowPlayingLyrics",
                                                "编辑器读取嵌入歌词失败: ${error.message}"
                                            )
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                    Triple(
                                        isManagedLocalDownload,
                                        downloadedLyrics,
                                        localLyrics to embeddedLyrics
                                    )
                                }
                                val downloadedLyrics = lyricsSources.second
                                val localLyrics = lyricsSources.third.first
                                val embeddedLyrics = lyricsSources.third.second
                                val sidecarLyrics = when {
                                    downloadedLyrics?.hasOriginalSidecar == true -> {
                                        downloadedLyrics.lyric
                                    }
                                    localLyrics?.hasOriginalSidecar == true -> localLyrics.lyric
                                    else -> null
                                }
                                val sidecarTranslatedLyrics = when {
                                    downloadedLyrics?.hasTranslatedSidecar == true -> {
                                        downloadedLyrics.translatedLyric
                                    }
                                    localLyrics?.hasTranslatedSidecar == true -> {
                                        localLyrics.translatedLyric
                                    }
                                    else -> null
                                }
                                val sidecarRomanizedLyrics = when {
                                    downloadedLyrics?.hasRomanizedSidecar == true -> {
                                        downloadedLyrics.romanizedLyric
                                    }
                                    localLyrics?.hasRomanizedSidecar == true -> {
                                        localLyrics.romanizedLyric
                                    }
                                    else -> null
                                }
                                val storedLyrics = resolveStoredLyricText(
                                    actualSong.matchedLyric,
                                    actualSong.originalLyric
                                )
                                val storedTranslatedLyrics = resolveStoredLyricText(
                                    actualSong.matchedTranslatedLyric,
                                    actualSong.originalTranslatedLyric
                                )
                                val storedRomanizedLyrics = resolveStoredLyricText(
                                    actualSong.matchedRomanizedLyric,
                                    actualSong.originalRomanizedLyric
                                )
                                val loadedEditorSeed = resolveLocalLyricsEditorSeed(
                                    song = actualSong,
                                    sidecarLyrics = sidecarLyrics,
                                    sidecarTranslatedLyrics = sidecarTranslatedLyrics,
                                    sidecarRomanizedLyrics = sidecarRomanizedLyrics,
                                    embeddedLyrics = downloadedLyrics
                                        ?.takeUnless { it.hasOriginalSidecar }
                                        ?.lyric
                                        ?: embeddedLyrics?.lyric
                                        ?: storedLyrics
                                        ?: displayedLyricsText,
                                    embeddedTranslatedLyrics = downloadedLyrics
                                        ?.takeUnless { it.hasTranslatedSidecar }
                                        ?.translatedLyric
                                        ?: embeddedLyrics?.translatedLyric
                                        ?: storedTranslatedLyrics
                                        ?: displayedTranslatedLyricsText,
                                    embeddedRomanizedLyrics = downloadedLyrics
                                        ?.takeUnless { it.hasRomanizedSidecar }
                                        ?.romanizedLyric
                                        ?: embeddedLyrics?.romanizedLyric
                                        ?: storedRomanizedLyrics
                                        ?: displayedRomanizedLyricsText,
                                    hasOriginalSidecar =
                                        downloadedLyrics?.hasOriginalSidecar == true ||
                                            localLyrics?.hasOriginalSidecar == true,
                                    hasTranslatedSidecar =
                                        downloadedLyrics?.hasTranslatedSidecar == true ||
                                            localLyrics?.hasTranslatedSidecar == true,
                                    hasRomanizedSidecar =
                                        downloadedLyrics?.hasRomanizedSidecar == true ||
                                            localLyrics?.hasRomanizedSidecar == true
                                )
                                if (lyricsEditorRequestId != requestId) {
                                    return@launch
                                }
                                if (loadedEditorSeed.hasSidecar) {
                                    pendingLyricsSourceSeed = loadedEditorSeed
                                    isLyricsEditorOpening = false
                                    isPendingEmbeddedLyricsLoading = true
                                    // TagLib 只在来源弹窗出现后补充嵌入歌词, 不阻塞入口
                                    launch {
                                        val embedded = try {
                                            withContext(Dispatchers.IO) {
                                                LocalMediaSupport.inspectEmbeddedLyrics(
                                                    context = context,
                                                    song = actualSong
                                                )
                                            }
                                        } catch (error: SecurityException) {
                                            NPLogger.e(
                                                "NowPlayingLyrics",
                                                "补充读取嵌入歌词时目录授权失效",
                                                error
                                            )
                                            if (lyricsEditorRequestId == requestId) {
                                                pendingLyricsSourceSeed = null
                                                isPendingEmbeddedLyricsLoading = false
                                                snackbarHostState.showNeriSnackbar(
                                                    composeResources.getString(
                                                        R.string.settings_download_directory_permission_lost
                                                    )
                                                )
                                            }
                                            return@launch
                                        } catch (error: Exception) {
                                            NPLogger.w(
                                                "NowPlayingLyrics",
                                                "补充读取嵌入歌词失败: ${error.message}"
                                            )
                                            null
                                        }
                                        if (
                                            lyricsEditorRequestId == requestId &&
                                            pendingLyricsSourceSeed?.hasSidecar == true
                                        ) {
                                            pendingLyricsSourceSeed = loadedEditorSeed.copy(
                                                embeddedLyrics = embedded?.embeddedLyric.orEmpty(),
                                                embeddedTranslatedLyrics =
                                                    embedded?.embeddedTranslatedLyric.orEmpty(),
                                                embeddedRomanizedLyrics =
                                                    embedded?.embeddedRomanizedLyric.orEmpty()
                                            )
                                            isPendingEmbeddedLyricsLoading = false
                                        }
                                    }
                                } else {
                                    lyricsEditorSeed = loadedEditorSeed
                                    isLyricsEditorOpening = false
                                }
                            } else {
                                lyricsEditorSeed = resolveLyricsEditorSeed(
                                    song = actualSong,
                                    preparedLyrics = displayedLyricsText,
                                    preparedTranslatedLyrics = displayedTranslatedLyricsText
                                )
                                isLyricsEditorOpening = false
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ManagedDownloadRootUnavailableException) {
                            NPLogger.e("NowPlayingScreen", "歌词编辑器配置的下载目录授权已失效", e)
                            if (actualSong.isLocalSong() && lyricsEditorRequestId == requestId) {
                                lyricsEditorSeed = null
                                pendingLyricsSourceSeed = null
                                isPendingEmbeddedLyricsLoading = false
                                isLyricsEditorOpening = false
                                snackbarHostState.showNeriSnackbar(
                                    composeResources.getString(
                                        R.string.settings_download_directory_permission_lost
                                    )
                                )
                            }
                        } catch (e: SecurityException) {
                            NPLogger.e("NowPlayingScreen", "歌词编辑器读取本地文件权限失效", e)
                            if (actualSong.isLocalSong() && lyricsEditorRequestId == requestId) {
                                lyricsEditorSeed = null
                                pendingLyricsSourceSeed = null
                                isPendingEmbeddedLyricsLoading = false
                                isLyricsEditorOpening = false
                                snackbarHostState.showNeriSnackbar(
                                    composeResources.getString(
                                        R.string.settings_download_directory_permission_lost
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            NPLogger.e("NowPlayingScreen", "歌词编辑器初始化失败", e)
                            if (actualSong.isLocalSong() && lyricsEditorRequestId == requestId) {
                                lyricsEditorSeed = resolveLocalLyricsEditorSeed(
                                    song = actualSong,
                                    sidecarLyrics = null,
                                    sidecarTranslatedLyrics = null,
                                    sidecarRomanizedLyrics = null,
                                    embeddedLyrics = displayedLyricsText,
                                    embeddedTranslatedLyrics = displayedTranslatedLyricsText,
                                    embeddedRomanizedLyrics = null,
                                    hasOriginalSidecar = false,
                                    hasTranslatedSidecar = false,
                                    hasRomanizedSidecar = false
                                )
                                isLyricsEditorOpening = false
                            } else {
                                lyricsEditorSeed = resolveLyricsEditorSeed(song = actualSong)
                                isLyricsEditorOpening = false
                            }
                        }
                    }
                },
                enabled = !isLyricsEditorOpening,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLyricsEditorOpening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
                    if (
                        shouldConfirmLocalMetadataWriteBack(
                            song = actualSong,
                            title = songName,
                            artist = artistName,
                            coverUrl = coverUrl
                        )
                    ) {
                        clearEditSongInfoFocus()
                        showLocalMetadataWriteBackConfirm = true
                    } else {
                        saveEditedSongInfo(writeLocalMetadata = false)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isCoverImporting && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Outlined.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
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

    if (showLocalCoverSyncConfirm) {
        LocalSongSyncConfirmDialog(
            actionLabel = composeResources.getString(R.string.music_edit_cover),
            onConfirm = {
                showLocalCoverSyncConfirm = false
                if (shouldAllowLocalCoverReplacement(actualSong, context)) {
                    pendingCoverReplacementSong = actualSong
                    clearEditSongInfoFocus()
                    coverPickerLauncher.launch("image/*")
                }
            },
            onDismiss = { showLocalCoverSyncConfirm = false }
        )
    }

    if (showLocalMetadataWriteBackConfirm) {
        AlertDialog(
            onDismissRequest = { showLocalMetadataWriteBackConfirm = false },
            title = { Text(stringResource(R.string.local_song_metadata_write_confirm_title)) },
            text = { Text(stringResource(R.string.local_song_metadata_write_confirm_message)) },
            confirmButton = {
                HapticTextButton(
                    enabled = !isSaving,
                    onClick = {
                        showLocalMetadataWriteBackConfirm = false
                        saveEditedSongInfo(writeLocalMetadata = true)
                    }
                ) {
                    Text(stringResource(R.string.local_song_metadata_write_confirm_write))
                }
            },
            dismissButton = {
                HapticTextButton(
                    enabled = !isSaving,
                    onClick = {
                        showLocalMetadataWriteBackConfirm = false
                        saveEditedSongInfo(writeLocalMetadata = false)
                    }
                ) {
                    Text(stringResource(R.string.local_song_metadata_write_confirm_app_only))
                }
            }
        )
    }

    if (showFillLyricsMetadataWriteBackConfirm) {
        AlertDialog(
            onDismissRequest = { showFillLyricsMetadataWriteBackConfirm = false },
            title = { Text(stringResource(R.string.local_song_metadata_write_confirm_title)) },
            text = { Text(stringResource(R.string.local_lyrics_fill_write_confirm_message)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        showFillLyricsMetadataWriteBackConfirm = false
                        writeFetchedLyricsToLocalMetadata()
                    }
                ) {
                    Text(stringResource(R.string.local_song_metadata_write_confirm_write))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = { showFillLyricsMetadataWriteBackConfirm = false }
                ) {
                    Text(stringResource(R.string.local_song_metadata_write_confirm_app_only))
                }
            }
        )
    }

    // 填充选项对话框
    if (selectedSongForFill != null) {
        FillOptionsDialog(
            songResult = selectedSongForFill!!,
            onDismiss = { selectedSongForFill = null },
            onConfirm = { fillCover, fillTitle, fillArtist, fillLyrics ->
                // 标记用户已编辑, 防止自动重置
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
                        viewModel.fillLyrics(context, actualSong, selectedSong) { success, message ->
                            if (success && actualSong.isLocalSong()) {
                                showFillLyricsMetadataWriteBackConfirm = true
                            }
                            coroutineScope.launch {
                                snackbarHostState.showNeriSnackbar(message)
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
            initialRomanizedLyrics = lyricsEditorSeed!!.romanizedLyrics,
            editingSource = lyricsEditorSeed!!.source,
            hasExistingSidecar = lyricsEditorSeed!!.hasSidecar,
            onDismiss = {
                clearEditSongInfoFocus()
                lyricsEditorSeed = null
            }
        )
    }

    pendingLyricsSourceSeed?.let { pendingSeed ->
        AlertDialog(
            onDismissRequest = {
                pendingLyricsSourceSeed = null
                isPendingEmbeddedLyricsLoading = false
            },
            title = { Text(stringResource(R.string.local_lyrics_source_choice_title)) },
            text = { Text(stringResource(R.string.local_lyrics_source_choice_message)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        lyricsEditorSeed = pendingSeed.copy(
                            lyrics = pendingSeed.sidecarLyrics,
                            translatedLyrics = pendingSeed.sidecarTranslatedLyrics,
                            romanizedLyrics = pendingSeed.sidecarRomanizedLyrics,
                            source = LyricsEditorSource.SIDECAR
                        )
                        pendingLyricsSourceSeed = null
                        isPendingEmbeddedLyricsLoading = false
                    }
                ) {
                    Text(stringResource(R.string.local_lyrics_source_sidecar))
                }
            },
            dismissButton = {
                HapticTextButton(
                    enabled = !isPendingEmbeddedLyricsLoading,
                    onClick = {
                        lyricsEditorSeed = pendingSeed.copy(
                            lyrics = pendingSeed.embeddedLyrics,
                            translatedLyrics = pendingSeed.embeddedTranslatedLyrics,
                            romanizedLyrics = pendingSeed.embeddedRomanizedLyrics,
                            source = LyricsEditorSource.EMBEDDED
                        )
                        pendingLyricsSourceSeed = null
                        isPendingEmbeddedLyricsLoading = false
                    }
                ) {
                    if (isPendingEmbeddedLyricsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.local_lyrics_source_embedded))
                }
            }
        )
    }

    // 搜索结果Sheet
    if (showSearchResults) {
        ModalBottomSheet(
            onDismissRequest = {
                clearEditSongInfoFocus()
                showSearchResults = false
            },
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

                    HapticTextButton(
                        onClick = {
                            clearEditSongInfoFocus()
                            showSearchResults = false
                        }
                    ) {
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
                                            clearEditSongInfoFocus()
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

internal fun shouldConfirmLocalMetadataWriteBack(
    song: SongItem,
    title: String,
    artist: String,
    coverUrl: String
): Boolean {
    if (!song.isLocalSong()) {
        return false
    }
    val resolvedTitle = title.trim().ifBlank { song.name }
    val resolvedArtist = artist.trim().ifBlank { song.artist }
    val resolvedCoverUrl = coverUrl.trim().ifBlank { null }
    val currentCoverUrl = song.customCoverUrl ?: song.coverUrl
    return !song.displayName().trim().equals(resolvedTitle, ignoreCase = false) ||
        !song.displayArtist().trim().equals(resolvedArtist, ignoreCase = false) ||
        currentCoverUrl?.trim() != resolvedCoverUrl
}

internal fun shouldAllowLocalCoverReplacement(
    song: SongItem,
    context: Context? = null
): Boolean {
    return !song.isSyncableRemoteSong(context)
}

internal fun resolvePendingLocalCoverReplacementTarget(
    pendingSong: SongItem?,
    currentSong: SongItem?,
    context: Context? = null
): SongItem? {
    if (pendingSong == null || currentSong == null) return null
    if (!pendingSong.sameIdentityAs(currentSong)) return null
    if (!shouldAllowLocalCoverReplacement(pendingSong, context)) return null
    if (!shouldAllowLocalCoverReplacement(currentSong, context)) return null
    return pendingSong
}

@Composable
private fun NowPlayingProgressSection(
    songKey: String?,
    durationMs: Long,
    lyrics: List<LyricEntry>,
    lyricOffsetMs: Long,
    isPlaying: Boolean,
    isPlaybackWaiting: Boolean,
    playbackSpeed: Float,
    progressInfoSegments: List<NowPlayingProgressInfoSegment>,
    seekEnabled: Boolean,
    activeContentColor: Color,
    useWideLandscapeLayout: Boolean,
    onPreviewPositionChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    progressRowModifier: Modifier = Modifier
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
            modifier = Modifier
                .fillMaxWidth()
                .then(progressRowModifier),
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
                isPlaybackWaiting = delayedPlaybackWaiting,
                isProgressStalled = isPlaybackWaiting,
                isProgressPreviewing = isUserDraggingSlider ||
                    pendingSeekPreviewPositionMs != null,
                activeTint = activeContentColor,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                playbackSessionKey = songKey
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
                highlightedContentColor = activeContentColor,
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
    playbackSessionKey: String?,
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
    translatedLyrics: List<LyricEntry>? = null,
    showEmbeddedTranslations: Boolean = translatedLyrics != null
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
        smoothActiveLineProgress = false,
        edgeFadeHeight = resolveLyricEdgeFadeHeight(isEmbedded = true),
        showEmbeddedTranslations = showEmbeddedTranslations,
        playbackSessionKey = playbackSessionKey,
        stableEmbeddedViewport = true
    )
}

@Composable
fun LyricsEditorSheet(
    originalSong: SongItem,
    initialLyrics: String,
    initialTranslatedLyrics: String,
    initialRomanizedLyrics: String = "",
    editingSource: LyricsEditorSource = LyricsEditorSource.SIDECAR,
    hasExistingSidecar: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun dismissLyricsEditor() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onDismiss()
    }

    var lyricsText by remember(initialLyrics, editingSource) { mutableStateOf(initialLyrics) }
    var translatedLyricsText by remember(
        initialTranslatedLyrics,
        editingSource
    ) { mutableStateOf(initialTranslatedLyrics) }
    var romanizedLyricsText by remember(
        initialRomanizedLyrics,
        editingSource
    ) { mutableStateOf(initialRomanizedLyrics) }
    var isSaving by remember { mutableStateOf(false) }
    var showLocalMetadataWriteBackConfirm by remember { mutableStateOf(false) }
    var showEmbeddedLyricsOverwriteConfirm by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showLyricMatchSheet by remember { mutableStateOf(false) }
    var lyricMatchQuery by remember(originalSong.stableKey()) {
        mutableStateOf(defaultEditableLyricsMatchKeyword(originalSong))
    }
    var lyricMatchResultsBySource by remember(originalSong.stableKey()) {
        mutableStateOf<Map<EditableLyricMatchSource, List<RankedEditableLyricMatch>>>(emptyMap())
    }
    var cachedLyricMatchQuery by remember(originalSong.stableKey()) { mutableStateOf("") }
    var searchedLyricMatchSources by remember(originalSong.stableKey()) {
        mutableStateOf<Set<EditableLyricMatchSource>>(emptySet())
    }
    var isLyricMatching by remember { mutableStateOf(false) }
    var lyricMatchError by remember { mutableStateOf<String?>(null) }
    var selectedLyricMatchSources by remember(originalSong.stableKey()) {
        mutableStateOf(
            defaultEditableLyricMatchSources(
                isYouTubeMusicTrack = isYouTubeMusicSong(originalSong)
            )
        )
    }
    val visibleLyricMatchResults = remember(lyricMatchResultsBySource, selectedLyricMatchSources) {
        filterCachedLyricMatchResults(
            resultsBySource = lyricMatchResultsBySource,
            selectedSources = selectedLyricMatchSources
        )
    }
    val hasSearchedSelectedLyricSources = selectedLyricMatchSources.any { source ->
        source in searchedLyricMatchSources
    }

    BackHandler {
        when {
            showEmbeddedLyricsOverwriteConfirm -> showEmbeddedLyricsOverwriteConfirm = false
            showLocalMetadataWriteBackConfirm -> showLocalMetadataWriteBackConfirm = false
            showLyricMatchSheet -> showLyricMatchSheet = false
            else -> dismissLyricsEditor()
        }
    }

    fun saveLyrics(writeLocalMetadata: Boolean) {
        isSaving = true
        coroutineScope.launch {
            try {
                val writeSucceeded = PlayerManager.updateSongLyricsAndTranslation(
                    songToUpdate = originalSong,
                    newLyrics = lyricsText,
                    newTranslatedLyrics = translatedLyricsText,
                    newRomanizedLyrics = romanizedLyricsText,
                    writeLocalMetadata = writeLocalMetadata
                )
                if (writeSucceeded) {
                    dismissLyricsEditor()
                } else {
                    AppFeedback.show(
                        context = context,
                        message = resources.getString(R.string.local_song_lyrics_write_failed)
                    )
                }
            } catch (error: Exception) {
                NPLogger.e("NowPlayingScreen", "保存歌词失败", error)
                AppFeedback.show(
                    context = context,
                    message = resources.getString(R.string.local_song_lyrics_write_failed)
                )
            } finally {
                isSaving = false
            }
        }
    }

    fun runLyricMatch(
        query: String,
        sources: Set<EditableLyricMatchSource> = selectedLyricMatchSources
    ) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank() || isLyricMatching) return
        if (sources.isEmpty()) {
            lyricMatchError = resources.getString(R.string.lyrics_match_no_source_selected)
            return
        }
        val queryKey = normalizeLyricMatchText(trimmedQuery)
        val isSameQuery = cachedLyricMatchQuery == queryKey
        if (!isSameQuery) {
            cachedLyricMatchQuery = queryKey
            lyricMatchResultsBySource = emptyMap()
            searchedLyricMatchSources = emptySet()
        }
        val sourcesToSearch = sources
        lyricMatchQuery = trimmedQuery
        isLyricMatching = true
        lyricMatchError = null
        coroutineScope.launch {
            try {
                val matches = withContext(Dispatchers.IO) {
                    AppContainer.editableLyricsMatcher.matchLyrics(
                        EditableLyricMatchRequest(
                            keyword = trimmedQuery,
                            trackName = originalSong.customName ?: originalSong.name,
                            artistName = originalSong.customArtist ?: originalSong.artist,
                            albumName = originalSong.album,
                            durationMs = originalSong.durationMs,
                            sources = sourcesToSearch
                        )
                    )
                }
                val updatedResults = lyricMatchResultsBySource.toMutableMap()
                sourcesToSearch.forEach { source ->
                    val sourceMatches = matches.filter { it.candidate.source == source }
                    if (sourceMatches.isNotEmpty() || !isSameQuery || updatedResults[source].isNullOrEmpty()) {
                        updatedResults[source] = sourceMatches
                    }
                }
                lyricMatchResultsBySource = updatedResults
                searchedLyricMatchSources = searchedLyricMatchSources + sourcesToSearch
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lyricMatchError = resources.getString(
                    R.string.lyrics_match_error,
                    error.message.orEmpty().ifBlank { error.javaClass.simpleName }
                )
            } finally {
                isLyricMatching = false
            }
        }
    }


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

            HapticTextButton(onClick = ::dismissLyricsEditor) {
                Text(stringResource(R.string.action_cancel))
            }
        }

        // 歌曲信息
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            HapticTextButton(
                onClick = {
                    showLyricMatchSheet = true
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.lyrics_match_action), maxLines = 1)
            }
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
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.lyrics_romanized)) }
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
            2 -> {
                OutlinedTextField(
                    value = romanizedLyricsText,
                    onValueChange = { romanizedLyricsText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.lyrics_editor_hint_romanized),
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
                        2 -> romanizedLyricsText = ""
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
                                2 -> romanizedLyricsText = clipText
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
                    if (originalSong.isLocalSong()) {
                        if (
                            editingSource == LyricsEditorSource.EMBEDDED &&
                                hasExistingSidecar
                        ) {
                            showEmbeddedLyricsOverwriteConfirm = true
                        } else {
                            showLocalMetadataWriteBackConfirm = true
                        }
                    } else {
                        saveLyrics(writeLocalMetadata = false)
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

        if (showLyricMatchSheet) {
            LyricMatchResultsSheet(
                query = lyricMatchQuery,
                onQueryChange = { query ->
                    if (query != lyricMatchQuery) {
                        lyricMatchQuery = query
                        lyricMatchResultsBySource = emptyMap()
                        cachedLyricMatchQuery = ""
                        searchedLyricMatchSources = emptySet()
                        lyricMatchError = null
                    }
                },
                results = visibleLyricMatchResults,
                isLoading = isLyricMatching,
                errorMessage = lyricMatchError,
                hasSearched = hasSearchedSelectedLyricSources,
                selectedSources = selectedLyricMatchSources,
                onSourceToggle = { source ->
                    selectedLyricMatchSources = if (source in selectedLyricMatchSources) {
                        selectedLyricMatchSources - source
                    } else {
                        selectedLyricMatchSources + source
                    }
                    lyricMatchError = null
                },
                onSearch = { query -> runLyricMatch(query, selectedLyricMatchSources) },
                onApply = { result ->
                    lyricsText = result.candidate.lyrics
                    result.candidate.translatedLyrics?.takeIf { it.isNotBlank() }?.let { translated ->
                        translatedLyricsText = translated
                    }
                    selectedTab = 0
                    showLyricMatchSheet = false
                },
                onDismiss = { showLyricMatchSheet = false }
            )
        }
    }

    if (showLocalMetadataWriteBackConfirm) {
        AlertDialog(
            onDismissRequest = { showLocalMetadataWriteBackConfirm = false },
            title = { Text(stringResource(R.string.local_song_metadata_write_confirm_title)) },
            text = { Text(stringResource(R.string.local_song_metadata_write_confirm_message)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        showLocalMetadataWriteBackConfirm = false
                        saveLyrics(writeLocalMetadata = true)
                    }
                ) {
                    Text(stringResource(R.string.local_song_metadata_write_confirm_write))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = {
                        showLocalMetadataWriteBackConfirm = false
                        saveLyrics(writeLocalMetadata = false)
                    }
                ) {
                    Text(stringResource(R.string.local_song_metadata_write_confirm_app_only))
                }
            }
        )
    }

    if (showEmbeddedLyricsOverwriteConfirm) {
        AlertDialog(
            onDismissRequest = { showEmbeddedLyricsOverwriteConfirm = false },
            title = { Text(stringResource(R.string.local_lyrics_embedded_overwrite_title)) },
            text = { Text(stringResource(R.string.local_lyrics_embedded_overwrite_message)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        showEmbeddedLyricsOverwriteConfirm = false
                        saveLyrics(writeLocalMetadata = true)
                    }
                ) {
                    Text(stringResource(R.string.local_lyrics_embedded_overwrite_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = { showEmbeddedLyricsOverwriteConfirm = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LyricMatchResultsSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<RankedEditableLyricMatch>,
    isLoading: Boolean,
    errorMessage: String?,
    hasSearched: Boolean,
    selectedSources: Set<EditableLyricMatchSource>,
    onSourceToggle: (EditableLyricMatchSource) -> Unit,
    onSearch: (String) -> Unit,
    onApply: (RankedEditableLyricMatch) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.lyrics_match_title),
                    style = MaterialTheme.typography.titleMedium
                )
                HapticTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.lyrics_match_sources),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    lyricMatchSelectableSources.forEach { source ->
                        FilterChip(
                            selected = source in selectedSources,
                            onClick = { onSourceToggle(source) },
                            enabled = !isLoading,
                            label = {
                                Text(
                                    text = stringResource(source.stringResId()),
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.lyrics_match_keyword)) },
                    placeholder = { Text(stringResource(R.string.lyrics_match_keyword_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSearch(query) }
                    )
                )
                HapticTextButton(
                    onClick = { onSearch(query) },
                    enabled = !isLoading && query.isNotBlank() && selectedSources.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.lyrics_match_search), maxLines = 1)
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.lyrics_match_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (!isLoading && hasSearched && errorMessage == null && results.isEmpty()) {
                Text(
                    text = stringResource(R.string.lyrics_match_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = results,
                    key = { result ->
                        "${result.candidate.source}:${result.candidate.id}:${result.candidate.lyrics.hashCode()}"
                    }
                ) { result ->
                    LyricMatchResultCard(
                        result = result,
                        onClick = { onApply(result) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricMatchResultCard(
    result: RankedEditableLyricMatch,
    onClick: () -> Unit
) {
    val candidate = result.candidate
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = candidate.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildLyricMatchMetaText(result),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun buildLyricMatchMetaText(result: RankedEditableLyricMatch): String {
    val candidate = result.candidate
    return buildString {
        append(stringResource(candidate.source.stringResId()))
        if (candidate.durationMs > 0L) {
            append(" · ")
            append(formatDuration(candidate.durationMs))
        }
        result.durationDeltaMs?.let { deltaMs ->
            append(" · ")
            append(stringResource(R.string.lyrics_match_duration_delta, formatDuration(deltaMs)))
        }
        append(" · ")
        append(stringResource(R.string.lyrics_match_score, result.score))
        append(" · ")
        append(
            stringResource(
                when (result.confidence) {
                    EditableLyricMatchConfidence.HIGH -> R.string.lyrics_match_confidence_high
                    EditableLyricMatchConfidence.MEDIUM -> R.string.lyrics_match_confidence_medium
                    EditableLyricMatchConfidence.LOW -> R.string.lyrics_match_confidence_low
                }
            )
        )
    }
}

private fun defaultEditableLyricsMatchKeyword(song: SongItem): String {
    return listOf(
        song.customName ?: song.name,
        song.customArtist ?: song.artist
    ).filter { it.isNotBlank() }
        .joinToString(" ")
}

private fun filterCachedLyricMatchResults(
    resultsBySource: Map<EditableLyricMatchSource, List<RankedEditableLyricMatch>>,
    selectedSources: Set<EditableLyricMatchSource>
): List<RankedEditableLyricMatch> {
    if (selectedSources.isEmpty()) {
        return emptyList()
    }
    return lyricMatchSelectableSources.asSequence()
        .filter { it in selectedSources }
        .flatMap { source -> resultsBySource[source].orEmpty().asSequence() }
        .sortedWith(
            editableLyricMatchResultComparator(
                sourceRank = { source ->
                    val index = lyricMatchSelectableSources.indexOf(source)
                    if (index >= 0) lyricMatchSelectableSources.size - index else 0
                },
                sourceFallbackRank = lyricMatchSelectableSources::indexOf
            )
        )
        .toList()
}

private val lyricMatchSelectableSources = listOf(
    EditableLyricMatchSource.KUGOU,
    EditableLyricMatchSource.CLOUD_MUSIC,
    EditableLyricMatchSource.QQ_MUSIC,
    EditableLyricMatchSource.LRCLIB,
    EditableLyricMatchSource.AMLL_TTML,
    EditableLyricMatchSource.YOUTUBE_MUSIC
)

private fun EditableLyricMatchSource.stringResId(): Int {
    return when (this) {
        EditableLyricMatchSource.KUGOU -> R.string.lyrics_match_source_kugou
        EditableLyricMatchSource.CLOUD_MUSIC -> R.string.lyrics_match_source_cloud_music
        EditableLyricMatchSource.QQ_MUSIC -> R.string.lyrics_match_source_qq_music
        EditableLyricMatchSource.AMLL_TTML -> R.string.lyrics_match_source_amll_ttml
        EditableLyricMatchSource.LRCLIB -> R.string.lyrics_match_source_lrclib
        EditableLyricMatchSource.YOUTUBE_MUSIC -> R.string.lyrics_match_source_youtube_music
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
