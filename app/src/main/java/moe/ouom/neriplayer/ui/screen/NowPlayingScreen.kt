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
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
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
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.LibraryMusic
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.lyrics.hasCollapsedTimedLyricTimeline
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.comment.resolveCommentSource
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.shouldHideRemoteDownloadAction
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.metadata.resolveLyricTextForPlayback
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.core.player.metadata.shouldTryPreferredLyricSource
import moe.ouom.neriplayer.core.player.metadata.shouldReadManagedDownloadLyrics
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.forSource
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.storage.LocalAssetInvalidationBus
import moe.ouom.neriplayer.data.model.isSyncableRemoteSong
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.playbackVisualKey
import moe.ouom.neriplayer.data.model.playbackVisualKeyAliases
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.data.settings.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.LyricSourcePreference
import moe.ouom.neriplayer.data.settings.LyricFontScalePage
import moe.ouom.neriplayer.data.settings.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.resolveEffectiveLyricOffsetMs
import moe.ouom.neriplayer.data.settings.scaledLyricFontSize
import moe.ouom.neriplayer.ui.component.lyrics.AdvancedLyricsView
import moe.ouom.neriplayer.ui.component.lyrics.buildPhoneticLyricEntries
import moe.ouom.neriplayer.ui.component.lyrics.flattenWordTimedEntries
import moe.ouom.neriplayer.ui.component.lyrics.hasWordTimedEntries
import moe.ouom.neriplayer.ui.component.local.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.LyricShareSheet
import moe.ouom.neriplayer.ui.component.lyrics.LyricVisualSpec
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
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricSeekPosition
import moe.ouom.neriplayer.ui.component.lyrics.resolvePreferredLyricContent
import moe.ouom.neriplayer.ui.component.lyrics.resolveStoredLyricText
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.media.saveCoverToPictures

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
    currentTask: moe.ouom.neriplayer.core.download.model.DownloadTask?
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
    val embeddedPhoneticLyrics: List<LyricEntry>,
    val preferredSource: LyricSourcePreference? = null
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

internal fun buildPreferredLyricSourceState(
    result: PreferredLyricSourceResult
): LoadedLyricsState = LoadedLyricsState(
    rawLyrics = null,
    rawTranslatedLyrics = null,
    rawPhoneticLyrics = null,
    lyrics = result.lyrics,
    translatedLyrics = result.translatedLyrics,
    phoneticLyrics = result.romanizedLyrics,
    plainLyrics = result.lyrics.flattenWordTimedEntries(),
    plainTranslatedLyrics = result.translatedLyrics.flattenWordTimedEntries(),
    embeddedPhoneticLyrics = emptyList(),
    preferredSource = result.source
)

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

internal fun buildNowPlayingInitialLyricsState(
    song: SongItem?,
    cachedPreferredLyrics: PreferredLyricSourceResult?
): LoadedLyricsState = cachedPreferredLyrics?.let(::buildPreferredLyricSourceState)
    ?: buildNowPlayingImmediateLyricsState(song)

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
    offlineMode: Boolean = false,
    resolvedCoverUrl: String? = null,
    visualCoverUrl: String? = null,
    playbackSongKey: String? = null,
    playbackSongKeyAliases: List<String> = emptyList(),
    visualCoverSongKey: String? = null,
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
    val shouldLoadPreferredLyrics = currentSong?.let { song ->
        shouldTryPreferredLyricSource(song, defaultLyricSource)
    } == true
    val cachedPreferredLyrics = remember(
        currentSong,
        defaultLyricSource,
        preferWordTimedLyrics,
        lyricsPreferenceRevision
    ) {
        currentSong?.let { song ->
            PlayerManager.getCachedPreferredLyricSourceResult(
                song,
                defaultLyricSource,
                preferWordTimedLyrics
            )
        }
    }
    var secondaryLyricsResolved by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(false)
    }
    val immediateLyricsState = remember(currentSong, cachedPreferredLyrics) {
        buildNowPlayingInitialLyricsState(currentSong, cachedPreferredLyrics)
    }
    // 切歌时直接以歌曲对象已有文本建立首帧，侧载快读完成后再覆盖，避免歌曲和歌词错帧
    var lyrics by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.lyrics)
    }
    var translatedLyrics by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.translatedLyrics)
    }
    var rawLyricsText by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.rawLyrics)
    }
    var rawTranslatedLyricsText by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.rawTranslatedLyrics)
    }
    var rawPhoneticLyricsText by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.rawPhoneticLyrics)
    }
    var remotePhoneticLyrics by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.phoneticLyrics)
    }
    var plainLyrics by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.plainLyrics)
    }
    var plainTranslatedLyrics by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.plainTranslatedLyrics)
    }
    var embeddedPhoneticLyrics by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.embeddedPhoneticLyrics)
    }
    var loadedPreferredLyricSource by remember(currentLyricSourceKey, defaultLyricSource) {
        mutableStateOf(immediateLyricsState.preferredSource)
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
        ),
        preferWordTimedLyrics,
        defaultLyricSource,
        lyricsPreferenceRevision
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
            if (shouldReplaceLyricsAfterRefresh(
                    sameSong = sameSong,
                    loadedHasLyrics = loadedHasLyrics
                )
            ) {
                rawLyricsText = loadedLyricsState.rawLyrics
                rawTranslatedLyricsText = loadedLyricsState.rawTranslatedLyrics
                rawPhoneticLyricsText = loadedLyricsState.rawPhoneticLyrics
                lyrics = loadedLyricsState.lyrics
                translatedLyrics = loadedLyricsState.translatedLyrics
                remotePhoneticLyrics = loadedLyricsState.phoneticLyrics
                plainLyrics = loadedLyricsState.plainLyrics
                plainTranslatedLyrics = loadedLyricsState.plainTranslatedLyrics
                embeddedPhoneticLyrics = loadedLyricsState.embeddedPhoneticLyrics
                loadedPreferredLyricSource = loadedLyricsState.preferredSource
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
                candidate.isLocalSong() && hasCachedLocalDownload(candidate)
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
            cachedPreferredLyrics?.let(::buildPreferredLyricSourceState)
                ?: buildNowPlayingFastLyricsState(
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
            if (shouldLoadPreferredLyrics && song != null) {
                PlayerManager.getPreferredLyricSourceResult(song, defaultLyricSource)?.let { preferred ->
                    NPLogger.d(
                        "NowPlayingLyrics",
                        "使用偏好歌词源: source=${defaultLyricSource.storageValue}, song=${song.name}"
                    )
                    return@withContext buildPreferredLyricSourceState(preferred)
                }
                NPLogger.d(
                    "NowPlayingLyrics",
                    "偏好歌词源未命中，回退已存或平台歌词: source=${defaultLyricSource.storageValue}, " +
                        "song=${song.name}"
                )
            }
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
                    // 关闭"优先使用逐词歌词"后, 不再为了逐词去在线覆盖已有歌词
                    if (!preferWordTimedLyrics ||
                        parsedRawLyrics.hasWordTimedEntries() ||
                        song == null
                    ) {
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
            secondaryLyricsResolved = true
        }
    }
    val phoneticLyrics = remember(rawPhoneticLyricsText, remotePhoneticLyrics, embeddedPhoneticLyrics) {
        remotePhoneticLyrics.takeIf { it.isNotEmpty() } ?: embeddedPhoneticLyrics
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

                val coverCommentAvailable = commentSource != null
                val coverLyricsAvailable = lyrics.isNotEmpty()
                val onFavoriteCover: () -> Unit = {
                    val song = currentSong
                    if (song != null) {
                        val willFav = nextFavoriteStateAfterTap(isFavorite)
                        launchWithLocalSyncWarning(
                            song = song,
                            actionLabel = composeResources.getString(R.string.favorite_add),
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
                            composeResources.getString(R.string.cover_preview_unavailable)
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
                val onCoverMoreOptions: () -> Unit = { showMoreOptions = true }
                val coverToolbarActions = NowPlayingCoverToolbarActions(
                    onQueue = { showQueueSheet = true },
                    onSleepTimer = { showSleepTimerDialog = true },
                    onVolume = { showVolumeSheet = true },
                    onLyrics = { onShowLyricsScreenChange(!showLyricsScreen) },
                    onAddToPlaylist = { showAddSheet = true }
                )

                // 主列内容
                val mainColumnContent: @Composable ColumnScope.() -> Unit = {
                    NowPlayingCoverTopBar(
                        isFavorite = isFavorite,
                        favoriteEnabled = localPlaylistsReady,
                        commentAvailable = coverCommentAvailable,
                        height = nowPlayingTopBarHeight,
                        buttonSize = nowPlayingTopActionButtonSize,
                        iconSize = nowPlayingTopActionIconSize,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        onNavigateUp = onNavigateUp,
                        onFavorite = onFavoriteCover,
                        onComment = onCoverComment,
                        onMoreOptions = onCoverMoreOptions
                    )

                    Spacer(Modifier.height(8.dp))

                    // 封面
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
                        modifier = Modifier.nowPlayingCoverPanelModifier(useWideLandscapeLayout, this)
                    )

                    Spacer(Modifier.height(16.dp))

                    NowPlayingTrackIdentity(
                        display = resolveNowPlayingTrackDisplay(currentSong),
                        visible = contentVisible,
                        marqueeEnabled = nowPlayingSongTitleMarqueeEnabled,
                        titleColor = targetNowPlayingColorScheme.onSurface,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        onArtistClick = openCurrentArtist
                    )

                    NowPlayingLeadingProgress(
                        nowPlayingProgressAtBottom, useWideLandscapeLayout, nowPlayingProgressSection
                    )
                    NowPlayingLeadingControls(nowPlayingControlsAtBottom, mainPlaybackControls)

                    NowPlayingEmbeddedLyrics(
                        visible = shouldShowNowPlayingEmbeddedLyrics(
                            useWideLandscapeLayout, showCoverPageLyrics, coverLyricsAvailable
                        ),
                        content = buildNowPlayingSyncedLyricContent(
                            plainLyrics, secondaryPlainLyrics, showSecondaryLyrics,
                            usePhoneticTranslation, currentSong, totalOffset
                        ),
                        style = NowPlayingEmbeddedLyricStyle(
                            fontScale = coverLyricFontScale,
                            translationFontScale = coverTranslationFontScale,
                            blurEnabled = lyricBlurEnabled,
                            blurAmount = lyricBlurAmount
                        ),
                        playback = NowPlayingSyncedLyricPlayback(
                            positionFlow = PlayerManager.playbackPositionFlow,
                            previewPositionMs = previewPositionOverrideMs,
                            isPlaying = isPlaying,
                            speed = playbackSoundState.speed
                        ),
                        actions = NowPlayingSyncedLyricActions(
                            onClick = onEmbeddedLyricClick,
                            onLongClick = onEmbeddedLyricLongClick
                        )
                    )

                    // 将下面的内容推到底部, 平板横屏也保持贴近底部的手感
                    Spacer(modifier = Modifier.weight(1f))

                    NowPlayingTrailingControls(
                        nowPlayingControlsAtBottom, nowPlayingProgressAtBottom,
                        useWideLandscapeLayout, nowPlayingProgressSection, mainPlaybackControls
                    )

                    NowPlayingCoverActionToolbar(
                        spec = NowPlayingCoverToolbarLayoutSpec(
                            wideLandscape = useWideLandscapeLayout,
                            compactPortrait = useCompactPortraitLayout,
                            docked = useNowPlayingToolbarDock,
                            iconSize = nowPlayingToolbarIconSize,
                            minimumTouchTarget = nowPlayingToolbarMinimumTouchTarget
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
                                        translatedLyrics = if (showSecondaryLyrics) {
                                            if (usePhoneticTranslation) phoneticLyrics else translatedLyrics
                                        } else {
                                            null
                                        },
                                        showLyricTranslation = showSecondaryLyrics,
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
                                        content = buildNowPlayingSyncedLyricContent(
                                            plainLyrics, secondaryPlainLyrics, showSecondaryLyrics,
                                            usePhoneticTranslation, currentSong, totalOffset
                                        ),
                                        playback = NowPlayingSyncedLyricPlayback(
                                            positionFlow = PlayerManager.playbackPositionFlow,
                                            previewPositionMs = previewPositionOverrideMs,
                                            isPlaying = shouldAdvanceNowPlayingLyrics(
                                                isPlaying, previewPositionOverrideMs
                                            ),
                                            speed = playbackSoundState.speed
                                        ),
                                        style = NowPlayingSyncedLyricStyle(
                                            textColor = MaterialTheme.colorScheme.onBackground,
                                            fontSize = scaledLyricFontSize(18f, coverLyricFontScale).sp,
                                            translationFontSize = scaledLyricFontSize(14f, coverTranslationFontScale).sp,
                                            visualSpec = LyricVisualSpec(),
                                            blurEnabled = lyricBlurEnabled,
                                            blurAmount = lyricBlurAmount
                                        ),
                                        actions = NowPlayingSyncedLyricActions(
                                            onClick = { entry ->
                                                seekToLyricSafely(
                                                    positionMs = entry.startTimeMs,
                                                    playbackDurationMs = durationMs,
                                                    songDurationMs = currentSong?.durationMs ?: 0L
                                                )
                                            },
                                            onLongClick = { entry -> lyricShareInitialLine = entry }
                                        ),
                                        modifier = Modifier.fillMaxSize(),
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
                            onShowQualitySwitch = { showQualitySwitchDialog = true }
                        ),
                        snackbarHostState = snackbarHostState,
                        fontSettings = MoreOptionsFontSettings(
                            page = LyricFontScalePage.COVER,
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
