package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import moe.ouom.neriplayer.data.identity.stableKey

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.lyrics.scaledLyricFontSize
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.LyricVisualSpec
import moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsView
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricEdgeFadeHeight

internal data class NowPlayingSyncedLyricContent(
    val lines: List<LyricEntry>,
    val translatedLines: List<LyricEntry>?,
    val showTranslatedLines: Boolean,
    val playbackSessionKey: String?,
    val offsetMs: Long
)

internal fun buildNowPlayingSyncedLyricContent(
    lines: List<LyricEntry>,
    secondaryLines: List<LyricEntry>,
    showSecondary: Boolean,
    usePhonetic: Boolean,
    song: SongItem?,
    offsetMs: Long
): NowPlayingSyncedLyricContent = NowPlayingSyncedLyricContent(
    lines = lines,
    translatedLines = nowPlayingSecondaryLyrics(showSecondary, secondaryLines),
    showTranslatedLines = showSecondary && !usePhonetic,
    playbackSessionKey = song?.stableKey(),
    offsetMs = offsetMs
)

internal data class NowPlayingEmbeddedLyricStyle(
    val fontScale: Float,
    val translationFontScale: Float,
    val blurEnabled: Boolean,
    val blurAmount: Float
)

internal data class NowPlayingSyncedLyricPlayback(
    val positionFlow: StateFlow<Long>,
    val previewPositionMs: Long?,
    val isPlaying: Boolean,
    val speed: Float
)

internal data class NowPlayingSyncedLyricStyle(
    val textColor: Color,
    val fontSize: TextUnit,
    val translationFontSize: TextUnit,
    val visualSpec: LyricVisualSpec,
    val blurEnabled: Boolean,
    val blurAmount: Float
)

internal data class NowPlayingSyncedLyricActions(
    val onClick: (LyricEntry) -> Unit,
    val onLongClick: (LyricEntry) -> Unit
)

internal fun shouldShowNowPlayingEmbeddedLyrics(
    wideLandscape: Boolean,
    enabled: Boolean,
    hasLyrics: Boolean
): Boolean = !wideLandscape && enabled && hasLyrics

internal fun nowPlayingSecondaryLyrics(
    showSecondary: Boolean,
    lines: List<LyricEntry>
): List<LyricEntry>? = if (showSecondary) lines else null

internal fun shouldAdvanceNowPlayingLyrics(
    isPlaying: Boolean,
    previewPositionMs: Long?
): Boolean = isPlaying && previewPositionMs == null

internal fun resolveNowPlayingLyricsPosition(
    previewPositionMs: Long?,
    playbackPositionMs: Long
): Long = previewPositionMs ?: playbackPositionMs

@Composable
internal fun ColumnScope.NowPlayingEmbeddedLyrics(
    visible: Boolean,
    content: NowPlayingSyncedLyricContent,
    style: NowPlayingEmbeddedLyricStyle,
    playback: NowPlayingSyncedLyricPlayback,
    actions: NowPlayingSyncedLyricActions
) {
    if (!visible) return
    Spacer(Modifier.weight(1f))
    NowPlayingEmbeddedLyricsContent(
        content = content,
        style = style,
        playback = playback,
        actions = actions,
        modifier = Modifier.fillMaxWidth().weight(8f)
    )
}

@Composable
internal fun NowPlayingEmbeddedLyricsContent(
    content: NowPlayingSyncedLyricContent,
    style: NowPlayingEmbeddedLyricStyle,
    playback: NowPlayingSyncedLyricPlayback,
    actions: NowPlayingSyncedLyricActions,
    modifier: Modifier
) {
    NowPlayingLyricsPane(
        content = content,
        playback = playback.copy(
            isPlaying = shouldAdvanceNowPlayingLyrics(
                playback.isPlaying, playback.previewPositionMs
            )
        ),
        modifier = modifier,
        style = NowPlayingSyncedLyricStyle(
            textColor = MaterialTheme.colorScheme.onBackground,
            fontSize = scaledLyricFontSize(18f, style.fontScale).sp,
            translationFontSize = scaledLyricFontSize(14f, style.translationFontScale).sp,
            visualSpec = LyricVisualSpec(),
            blurEnabled = style.blurEnabled,
            blurAmount = style.blurAmount
        ),
        actions = actions
    )
}

@Composable
internal fun NowPlayingLyricsPane(
    content: NowPlayingSyncedLyricContent,
    style: NowPlayingSyncedLyricStyle,
    playback: NowPlayingSyncedLyricPlayback,
    actions: NowPlayingSyncedLyricActions,
    modifier: Modifier
) {
    val currentPosition by playback.positionFlow.collectAsStateWithLifecycle()
    SyncedLyricsView(
        lyrics = content.lines,
        currentTimeMs = resolveNowPlayingLyricsPosition(playback.previewPositionMs, currentPosition),
        modifier = modifier,
        textColor = style.textColor,
        fontSize = style.fontSize,
        translationFontSize = style.translationFontSize,
        visualSpec = style.visualSpec,
        lyricOffsetMs = content.offsetMs,
        lyricBlurEnabled = style.blurEnabled,
        lyricBlurAmount = style.blurAmount,
        onLyricClick = actions.onClick,
        onLyricLongClick = actions.onLongClick,
        translatedLyrics = content.translatedLines,
        isPlaying = playback.isPlaying,
        playbackSpeed = playback.speed,
        interpolatePlaybackPosition = true,
        visualEffectsEnabled = false,
        smoothActiveLineProgress = false,
        edgeFadeHeight = resolveLyricEdgeFadeHeight(isEmbedded = true),
        showEmbeddedTranslations = content.showTranslatedLines,
        playbackSessionKey = content.playbackSessionKey,
        stableEmbeddedViewport = true
    )
}
