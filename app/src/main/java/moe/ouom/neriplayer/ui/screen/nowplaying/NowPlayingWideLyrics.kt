package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.settings.lyrics.scaledLyricFontSize
import moe.ouom.neriplayer.ui.component.lyrics.AdvancedLyricsView
import moe.ouom.neriplayer.ui.component.lyrics.LyricVisualSpec
import moe.ouom.neriplayer.ui.screen.lyrics.LyricsContentPane
import moe.ouom.neriplayer.ui.screen.lyrics.LyricsContentViewport
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingLyricsPane
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricActions
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricContent
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricPlayback
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingSyncedLyricStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.resolveNowPlayingLyricsPosition
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.shouldAdvanceNowPlayingLyrics

internal data class NowPlayingWideLyricsContent(
    val lyrics: List<LyricEntry>,
    val translatedLyrics: List<LyricEntry>,
    val phoneticLyrics: List<LyricEntry>,
    val plainTranslatedLyrics: List<LyricEntry>,
    val rawLyrics: String?,
    val rawTranslatedLyrics: String?,
    val synced: NowPlayingSyncedLyricContent
)

internal data class NowPlayingWideLyricsPreferences(
    val advancedEnabled: Boolean,
    val showSecondary: Boolean,
    val usePhonetic: Boolean,
    val fontScales: LyricFontScales,
    val blurEnabled: Boolean,
    val blurAmount: Float
)

internal data class NowPlayingWideLyricsTypography(
    val lyricScale: Float,
    val translationScale: Float,
    val baseFontSizeSp: Float,
    val syncedFontSizeSp: Float,
    val syncedTranslationFontSizeSp: Float
)

internal fun resolveNowPlayingWideLyricsTypography(
    fullPage: Boolean,
    compactHeight: Boolean,
    scales: LyricFontScales
): NowPlayingWideLyricsTypography {
    val page = resolveNowPlayingLyricFontPage(fullPage)
    val lyricScale = scales.scaleFor(scales.lyricTargetFor(page))
    val translationScale = scales.scaleFor(scales.translationTargetFor(page))
    return NowPlayingWideLyricsTypography(
        lyricScale = lyricScale,
        translationScale = translationScale,
        baseFontSizeSp = if (compactHeight) 18f else 24f,
        syncedFontSizeSp = scaledLyricFontSize(if (compactHeight) 18f else 22f, lyricScale),
        syncedTranslationFontSizeSp = scaledLyricFontSize(14f, translationScale)
    )
}

internal data class NowPlayingWideLyricsPresentation(
    val fullPage: Boolean,
    val coverMode: NowPlayingWideLyricsMode,
    val typography: NowPlayingWideLyricsTypography,
    val bottomContentInset: Dp,
    val useTabletLayout: Boolean
)

internal fun resolveNowPlayingWideLyricsPresentation(
    fullPage: Boolean,
    compactHeight: Boolean,
    phoneLandscape: Boolean,
    availableWidth: Dp,
    hasLyrics: Boolean,
    preferences: NowPlayingWideLyricsPreferences
): NowPlayingWideLyricsPresentation = NowPlayingWideLyricsPresentation(
    fullPage = fullPage,
    coverMode = resolveNowPlayingWideLyricsMode(hasLyrics, preferences.advancedEnabled),
    typography = resolveNowPlayingWideLyricsTypography(fullPage, compactHeight, preferences.fontScales),
    bottomContentInset = if (compactHeight) 8.dp else 24.dp,
    useTabletLayout = !phoneLandscape && availableWidth >= 720.dp
)

internal data class NowPlayingWideLyricsSecondaryContent(
    val rawTranslation: String?,
    val lines: List<LyricEntry>?
)

internal fun resolveNowPlayingWideLyricsSecondaryContent(
    content: NowPlayingWideLyricsContent,
    showSecondary: Boolean,
    usePhonetic: Boolean
): NowPlayingWideLyricsSecondaryContent = NowPlayingWideLyricsSecondaryContent(
    rawTranslation = content.rawTranslatedLyrics.takeUnless { usePhonetic },
    lines = if (showSecondary) {
        if (usePhonetic) content.phoneticLyrics else content.translatedLyrics
    } else null
)

@Composable
internal fun NowPlayingWideLyrics(
    fullPage: Boolean,
    compactHeight: Boolean,
    phoneLandscape: Boolean,
    availableWidth: Dp,
    content: NowPlayingWideLyricsContent,
    preferences: NowPlayingWideLyricsPreferences,
    playback: NowPlayingSyncedLyricPlayback,
    lowPowerRendering: Boolean,
    actions: NowPlayingSyncedLyricActions,
    onSeekTo: (Long) -> Unit
) {
    val presentation = resolveNowPlayingWideLyricsPresentation(
        fullPage, compactHeight, phoneLandscape, availableWidth,
        content.lyrics.isNotEmpty(), preferences
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = resolveNowPlayingWideLyricViewport(maxHeight)
        if (presentation.fullPage) {
            NowPlayingFullWideLyrics(
                content, preferences, presentation, viewport, playback,
                lowPowerRendering, actions, onSeekTo
            )
        } else {
            NowPlayingCoverWideLyrics(
                content, preferences, presentation, viewport, playback, actions, onSeekTo
            )
        }
    }
}

@Composable
private fun NowPlayingFullWideLyrics(
    content: NowPlayingWideLyricsContent,
    preferences: NowPlayingWideLyricsPreferences,
    presentation: NowPlayingWideLyricsPresentation,
    viewport: NowPlayingWideLyricViewport,
    playback: NowPlayingSyncedLyricPlayback,
    lowPowerRendering: Boolean,
    actions: NowPlayingSyncedLyricActions,
    onSeekTo: (Long) -> Unit
) {
    LyricsContentPane(
        lyrics = content.lyrics,
        plainLyrics = content.synced.lines,
        plainTranslatedLyrics = content.plainTranslatedLyrics,
        translatedLyrics = content.translatedLyrics,
        phoneticLyrics = content.phoneticLyrics,
        playbackSessionKey = content.synced.playbackSessionKey,
        previewPositionOverrideMs = playback.previewPositionMs,
        advancedLyricsEnabled = preferences.advancedEnabled,
        showLyricTranslation = preferences.showSecondary,
        lyricTranslationUsePhonetic = preferences.usePhonetic,
        lyricFontScale = presentation.typography.lyricScale,
        translationFontScale = presentation.typography.translationScale,
        lyricOffsetMs = content.synced.offsetMs,
        lyricBlurEnabled = preferences.blurEnabled,
        lyricBlurAmount = preferences.blurAmount,
        textColor = MaterialTheme.colorScheme.onBackground,
        rawLyrics = content.rawLyrics,
        rawTranslatedLyrics = content.rawTranslatedLyrics,
        playbackSpeed = playback.speed,
        isPlaying = playback.isPlaying,
        lowPowerRendering = lowPowerRendering,
        useTabletLayout = presentation.useTabletLayout,
        onLyricLongClick = actions.onLongClick,
        onSeekTo = onSeekTo,
        viewport = LyricsContentViewport(
            presentation.typography.baseFontSizeSp, viewport.offset,
            viewport.topFadeLength, viewport.bottomFadeLength, presentation.bottomContentInset
        )
    )
}

@Composable
private fun NowPlayingCoverWideLyrics(
    content: NowPlayingWideLyricsContent,
    preferences: NowPlayingWideLyricsPreferences,
    presentation: NowPlayingWideLyricsPresentation,
    viewport: NowPlayingWideLyricViewport,
    playback: NowPlayingSyncedLyricPlayback,
    actions: NowPlayingSyncedLyricActions,
    onSeekTo: (Long) -> Unit
) {
    if (presentation.coverMode == NowPlayingWideLyricsMode.NO_LYRICS) {
        NowPlayingEmptyWideLyrics()
    } else {
        NowPlayingPopulatedCoverLyrics(
            content, preferences, presentation, viewport, playback, actions, onSeekTo
        )
    }
}

@Composable
private fun NowPlayingPopulatedCoverLyrics(
    content: NowPlayingWideLyricsContent,
    preferences: NowPlayingWideLyricsPreferences,
    presentation: NowPlayingWideLyricsPresentation,
    viewport: NowPlayingWideLyricViewport,
    playback: NowPlayingSyncedLyricPlayback,
    actions: NowPlayingSyncedLyricActions,
    onSeekTo: (Long) -> Unit
) {
    if (presentation.coverMode == NowPlayingWideLyricsMode.ADVANCED) {
        NowPlayingAdvancedWideLyrics(content, preferences, presentation, viewport, playback, actions, onSeekTo)
    } else {
        NowPlayingLyricsPane(
            content = content.synced,
            playback = playback.copy(
                isPlaying = shouldAdvanceNowPlayingLyrics(playback.isPlaying, playback.previewPositionMs)
            ),
            style = NowPlayingSyncedLyricStyle(
                textColor = MaterialTheme.colorScheme.onBackground,
                fontSize = presentation.typography.syncedFontSizeSp.sp,
                translationFontSize = presentation.typography.syncedTranslationFontSizeSp.sp,
                visualSpec = LyricVisualSpec(),
                blurEnabled = preferences.blurEnabled,
                blurAmount = preferences.blurAmount
            ),
            actions = actions,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun NowPlayingAdvancedWideLyrics(
    content: NowPlayingWideLyricsContent,
    preferences: NowPlayingWideLyricsPreferences,
    presentation: NowPlayingWideLyricsPresentation,
    viewport: NowPlayingWideLyricViewport,
    playback: NowPlayingSyncedLyricPlayback,
    actions: NowPlayingSyncedLyricActions,
    onSeekTo: (Long) -> Unit
) {
    val currentPosition by playback.positionFlow.collectAsStateWithLifecycle()
    val secondary = resolveNowPlayingWideLyricsSecondaryContent(
        content, preferences.showSecondary, preferences.usePhonetic
    )
    AdvancedLyricsView(
        lyrics = content.lyrics,
        currentTimeMs = resolveNowPlayingLyricsPosition(playback.previewPositionMs, currentPosition),
        modifier = Modifier.fillMaxSize(),
        textColor = MaterialTheme.colorScheme.onBackground,
        lyricFontScale = presentation.typography.lyricScale,
        translationFontScale = presentation.typography.translationScale,
        baseFontSizeSp = presentation.typography.baseFontSizeSp,
        lyricOffsetMs = content.synced.offsetMs,
        rawLyrics = content.rawLyrics,
        rawTranslatedLyrics = secondary.rawTranslation,
        translatedLyrics = secondary.lines,
        showLyricTranslation = preferences.showSecondary,
        showPhoneticAsTranslation = preferences.usePhonetic,
        lyricBlurEnabled = preferences.blurEnabled,
        lyricBlurAmount = preferences.blurAmount,
        isPlaying = shouldAdvanceNowPlayingLyrics(playback.isPlaying, playback.previewPositionMs),
        animateViewportScroll = playback.previewPositionMs != null,
        offset = viewport.offset,
        keepAliveZone = 128.dp,
        playedLyricViewportFraction = 0.36f,
        topFadeLength = viewport.topFadeLength,
        bottomFadeLength = viewport.bottomFadeLength,
        bottomContentInset = presentation.bottomContentInset,
        onLyricLongClick = actions.onLongClick,
        onSeekTo = onSeekTo
    )
}

@Composable
private fun NowPlayingEmptyWideLyrics() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
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
            text = stringResource(CoreCommonR.string.lyrics_no_lyrics),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
