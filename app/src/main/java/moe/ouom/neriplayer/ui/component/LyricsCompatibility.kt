package moe.ouom.neriplayer.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.ui.component.lyrics.flattenWordTimedEntries as newFlattenWordTimedEntries
import moe.ouom.neriplayer.ui.component.lyrics.hasWordTimedEntries as newHasWordTimedEntries
import moe.ouom.neriplayer.ui.component.lyrics.toEditableLyricsText as newToEditableLyricsText
import moe.ouom.neriplayer.ui.component.lyrics.verticalEdgeFade as newVerticalEdgeFade
import moe.ouom.neriplayer.data.model.SongItem

typealias LyricVisualSpec = moe.ouom.neriplayer.ui.component.lyrics.LyricVisualSpec
typealias WordTiming = moe.ouom.neriplayer.ui.component.lyrics.WordTiming
typealias LyricEntry = moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
typealias LyricSeekHapticFeedback = moe.ouom.neriplayer.ui.component.lyrics.LyricSeekHapticFeedback
internal typealias LyricsEditorSeed = moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSeed
internal typealias HeadGlowTarget = moe.ouom.neriplayer.ui.component.lyrics.HeadGlowTarget

@Deprecated(
    message = "Use SyncedLyricsView from ui.component.lyrics",
    replaceWith = ReplaceWith(
        "SyncedLyricsView(lyrics, currentTimeMs, modifier, textColor, inactiveAlphaNear, inactiveAlphaFar, blurInactiveAlphaNear, blurInactiveAlphaFar, fontSize, centerPadding, visualSpec, lyricOffsetMs, lyricBlurEnabled, lyricBlurAmount, onLyricClick, onLyricLongClick, translatedLyrics, translationFontSize, isPlaying, playbackSpeed, interpolatePlaybackPosition, visualEffectsEnabled, smoothActiveLineProgress)",
        "moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsView"
    )
)
@Composable
fun AppleMusicLyric(
    lyrics: List<LyricEntry>,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    textColor: Color = if (isSystemInDarkTheme()) Color.White else Color.Black,
    inactiveAlphaNear: Float = 0.4f,
    inactiveAlphaFar: Float = 0.35f,
    blurInactiveAlphaNear: Float = 0.72f,
    blurInactiveAlphaFar: Float = 0.40f,
    fontSize: TextUnit = 18.sp,
    centerPadding: Dp = 16.dp,
    visualSpec: LyricVisualSpec = LyricVisualSpec(),
    lyricOffsetMs: Long = 0L,
    lyricBlurEnabled: Boolean = true,
    lyricBlurAmount: Float = 10f,
    onLyricClick: ((LyricEntry) -> Unit)? = null,
    onLyricLongClick: ((LyricEntry) -> Unit)? = null,
    translatedLyrics: List<LyricEntry>? = null,
    translationFontSize: TextUnit = 14.sp,
    isPlaying: Boolean = false,
    playbackSpeed: Float = 1f,
    interpolatePlaybackPosition: Boolean = false,
    visualEffectsEnabled: Boolean = true,
    smoothActiveLineProgress: Boolean = true
) {
    moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsView(
        lyrics = lyrics,
        currentTimeMs = currentTimeMs,
        modifier = modifier,
        textColor = textColor,
        inactiveAlphaNear = inactiveAlphaNear,
        inactiveAlphaFar = inactiveAlphaFar,
        blurInactiveAlphaNear = blurInactiveAlphaNear,
        blurInactiveAlphaFar = blurInactiveAlphaFar,
        fontSize = fontSize,
        centerPadding = centerPadding,
        visualSpec = visualSpec,
        lyricOffsetMs = lyricOffsetMs,
        lyricBlurEnabled = lyricBlurEnabled,
        lyricBlurAmount = lyricBlurAmount,
        onLyricClick = onLyricClick,
        onLyricLongClick = onLyricLongClick,
        translatedLyrics = translatedLyrics,
        translationFontSize = translationFontSize,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
        interpolatePlaybackPosition = interpolatePlaybackPosition,
        visualEffectsEnabled = visualEffectsEnabled,
        smoothActiveLineProgress = smoothActiveLineProgress
    )
}

@Composable
fun SyncedLyricsView(
    lyrics: List<LyricEntry>,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    textColor: Color = if (isSystemInDarkTheme()) Color.White else Color.Black,
    inactiveAlphaNear: Float = 0.4f,
    inactiveAlphaFar: Float = 0.35f,
    blurInactiveAlphaNear: Float = 0.72f,
    blurInactiveAlphaFar: Float = 0.40f,
    fontSize: TextUnit = 18.sp,
    centerPadding: Dp = 16.dp,
    visualSpec: LyricVisualSpec = LyricVisualSpec(),
    lyricOffsetMs: Long = 0L,
    lyricBlurEnabled: Boolean = true,
    lyricBlurAmount: Float = 10f,
    onLyricClick: ((LyricEntry) -> Unit)? = null,
    onLyricLongClick: ((LyricEntry) -> Unit)? = null,
    translatedLyrics: List<LyricEntry>? = null,
    translationFontSize: TextUnit = 14.sp,
    isPlaying: Boolean = false,
    playbackSpeed: Float = 1f,
    interpolatePlaybackPosition: Boolean = false,
    visualEffectsEnabled: Boolean = true,
    smoothActiveLineProgress: Boolean = true
) {
    moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsView(
        lyrics = lyrics,
        currentTimeMs = currentTimeMs,
        modifier = modifier,
        textColor = textColor,
        inactiveAlphaNear = inactiveAlphaNear,
        inactiveAlphaFar = inactiveAlphaFar,
        blurInactiveAlphaNear = blurInactiveAlphaNear,
        blurInactiveAlphaFar = blurInactiveAlphaFar,
        fontSize = fontSize,
        centerPadding = centerPadding,
        visualSpec = visualSpec,
        lyricOffsetMs = lyricOffsetMs,
        lyricBlurEnabled = lyricBlurEnabled,
        lyricBlurAmount = lyricBlurAmount,
        onLyricClick = onLyricClick,
        onLyricLongClick = onLyricLongClick,
        translatedLyrics = translatedLyrics,
        translationFontSize = translationFontSize,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
        interpolatePlaybackPosition = interpolatePlaybackPosition,
        visualEffectsEnabled = visualEffectsEnabled,
        smoothActiveLineProgress = smoothActiveLineProgress
    )
}

@Composable
fun AdvancedLyricsView(
    lyrics: List<LyricEntry>,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    textColor: Color,
    lyricFontScale: Float,
    baseFontSizeSp: Float = 18f,
    lyricOffsetMs: Long = 0L,
    rawLyrics: String? = null,
    rawTranslatedLyrics: String? = null,
    translatedLyrics: List<LyricEntry>? = null,
    showLyricTranslation: Boolean = true,
    showPhoneticAsTranslation: Boolean = false,
    lyricBlurEnabled: Boolean = true,
    lyricBlurAmount: Float = 2.5f,
    isPlaying: Boolean = false,
    animateViewportScroll: Boolean = false,
    playbackSpeed: Float = 1f,
    lowPowerRendering: Boolean = false,
    offset: Dp = 48.dp,
    keepAliveZone: Dp = 108.dp,
    playedLyricViewportFraction: Float = 0.30f,
    topFadeLength: Dp = 112.dp,
    bottomFadeLength: Dp = 196.dp,
    bottomContentInset: Dp = 0.dp,
    onLyricLongClick: ((LyricEntry) -> Unit)? = null,
    onSeekTo: (Long) -> Unit = {}
) {
    moe.ouom.neriplayer.ui.component.lyrics.AdvancedLyricsView(
        lyrics = lyrics,
        currentTimeMs = currentTimeMs,
        modifier = modifier,
        textColor = textColor,
        lyricFontScale = lyricFontScale,
        baseFontSizeSp = baseFontSizeSp,
        lyricOffsetMs = lyricOffsetMs,
        rawLyrics = rawLyrics,
        rawTranslatedLyrics = rawTranslatedLyrics,
        translatedLyrics = translatedLyrics,
        showLyricTranslation = showLyricTranslation,
        showPhoneticAsTranslation = showPhoneticAsTranslation,
        lyricBlurEnabled = lyricBlurEnabled,
        lyricBlurAmount = lyricBlurAmount,
        isPlaying = isPlaying,
        animateViewportScroll = animateViewportScroll,
        playbackSpeed = playbackSpeed,
        lowPowerRendering = lowPowerRendering,
        offset = offset,
        keepAliveZone = keepAliveZone,
        playedLyricViewportFraction = playedLyricViewportFraction,
        topFadeLength = topFadeLength,
        bottomFadeLength = bottomFadeLength,
        bottomContentInset = bottomContentInset,
        onLyricLongClick = onLyricLongClick,
        onSeekTo = onSeekTo
    )
}

fun Modifier.verticalEdgeFade(fadeHeight: Dp): Modifier =
    newVerticalEdgeFade(fadeHeight)

@Deprecated(
    message = "Use SyncedLyricsActiveLine from ui.component.lyrics",
    replaceWith = ReplaceWith(
        "SyncedLyricsActiveLine(line, currentTimeMs, activeColor, inactiveColor, fontSize, fadeWidth, lyricOffsetMs, isPlaying, playbackSpeed, interpolatePlaybackPosition, animateProgress)",
        "moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsActiveLine"
    )
)
@Composable
fun AppleMusicActiveLine(
    line: LyricEntry,
    currentTimeMs: Long,
    activeColor: Color,
    inactiveColor: Color,
    fontSize: TextUnit,
    fadeWidth: Dp = 12.dp,
    lyricOffsetMs: Long = 0L,
    isPlaying: Boolean = false,
    playbackSpeed: Float = 1f,
    interpolatePlaybackPosition: Boolean = false,
    animateProgress: Boolean = true
) {
    moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsActiveLine(
        line = line,
        currentTimeMs = currentTimeMs,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        fontSize = fontSize,
        fadeWidth = fadeWidth,
        lyricOffsetMs = lyricOffsetMs,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
        interpolatePlaybackPosition = interpolatePlaybackPosition,
        animateProgress = animateProgress
    )
}

@Composable
fun SyncedLyricsActiveLine(
    line: LyricEntry,
    currentTimeMs: Long,
    activeColor: Color,
    inactiveColor: Color,
    fontSize: TextUnit,
    fadeWidth: Dp = 12.dp,
    lyricOffsetMs: Long = 0L,
    isPlaying: Boolean = false,
    playbackSpeed: Float = 1f,
    interpolatePlaybackPosition: Boolean = false,
    animateProgress: Boolean = true
) {
    moe.ouom.neriplayer.ui.component.lyrics.SyncedLyricsActiveLine(
        line = line,
        currentTimeMs = currentTimeMs,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        fontSize = fontSize,
        fadeWidth = fadeWidth,
        lyricOffsetMs = lyricOffsetMs,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
        interpolatePlaybackPosition = interpolatePlaybackPosition,
        animateProgress = animateProgress
    )
}

@Composable
fun DebugActiveLine(
    line: LyricEntry,
    currentTimeMs: Long,
    activeColor: Color,
    inactiveColor: Color,
    fontSize: TextUnit
) {
    moe.ouom.neriplayer.ui.component.lyrics.DebugActiveLine(
        line = line,
        currentTimeMs = currentTimeMs,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        fontSize = fontSize
    )
}

fun isNeteaseYrc(content: String): Boolean =
    moe.ouom.neriplayer.ui.component.lyrics.isNeteaseYrc(content)

fun parseNeteaseLyricsAuto(content: String): List<LyricEntry> =
    moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto(content)

fun calculateLineProgress(line: LyricEntry, currentTimeMs: Long): Float =
    moe.ouom.neriplayer.ui.component.lyrics.calculateLineProgress(line, currentTimeMs)

fun findCurrentLineIndex(lines: List<LyricEntry>, currentTimeMs: Long): Int =
    moe.ouom.neriplayer.ui.component.lyrics.findCurrentLineIndex(lines, currentTimeMs)

fun parseNeteaseYrc(yrc: String): List<LyricEntry> =
    moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseYrc(yrc)

fun parseNeteaseLrc(lrc: String): List<LyricEntry> =
    moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLrc(lrc)

fun List<LyricEntry>.flattenWordTimedEntries(): List<LyricEntry> =
    newFlattenWordTimedEntries()

fun List<LyricEntry>.hasWordTimedEntries(): Boolean =
    newHasWordTimedEntries()

fun List<LyricEntry>.toEditableLyricsText(): String =
    newToEditableLyricsText()

fun resolvePreferredLyricContent(
    matchedLyric: String?,
    preferredNeteaseLyric: String,
    legacyLyric: String? = null
): String? =
    moe.ouom.neriplayer.ui.component.lyrics.resolvePreferredLyricContent(
        matchedLyric = matchedLyric,
        preferredNeteaseLyric = preferredNeteaseLyric,
        legacyLyric = legacyLyric
    )

internal fun resolveStoredLyricText(
    currentLyric: String?,
    legacyLyric: String?
): String? =
    moe.ouom.neriplayer.ui.component.lyrics.resolveStoredLyricText(
        currentLyric = currentLyric,
        legacyLyric = legacyLyric
    )

internal fun resolveLyricsEditorInitialText(
    matchedLyric: String?,
    preferredNeteaseLyric: String,
    displayedLyricsText: String,
    displayedHasWordTimedEntries: Boolean,
    fallbackLyricsText: String?,
    legacyLyric: String? = null
): String =
    moe.ouom.neriplayer.ui.component.lyrics.resolveLyricsEditorInitialText(
        matchedLyric = matchedLyric,
        preferredNeteaseLyric = preferredNeteaseLyric,
        displayedLyricsText = displayedLyricsText,
        displayedHasWordTimedEntries = displayedHasWordTimedEntries,
        fallbackLyricsText = fallbackLyricsText,
        legacyLyric = legacyLyric
    )

internal fun resolveLyricsEditorSeed(
    song: SongItem,
    preparedLyrics: String? = null,
    preparedTranslatedLyrics: String? = null
): LyricsEditorSeed =
    moe.ouom.neriplayer.ui.component.lyrics.resolveLyricsEditorSeed(
        song = song,
        preparedLyrics = preparedLyrics,
        preparedTranslatedLyrics = preparedTranslatedLyrics
    )

internal fun matchTranslationsToLineIndices(
    lines: List<LyricEntry>,
    translations: List<LyricEntry>,
    toleranceMs: Long = 450L
): Map<Int, LyricEntry> =
    moe.ouom.neriplayer.ui.component.lyrics.matchTranslationsToLineIndices(
        lines = lines,
        translations = translations,
        toleranceMs = toleranceMs
    )

internal fun findBestMatchingTranslation(
    translations: List<LyricEntry>,
    lineStartMs: Long,
    lineEndMs: Long,
    toleranceMs: Long = 1_500L
): LyricEntry? =
    moe.ouom.neriplayer.ui.component.lyrics.findBestMatchingTranslation(
        translations = translations,
        lineStartMs = lineStartMs,
        lineEndMs = lineEndMs,
        toleranceMs = toleranceMs
    )

internal fun shouldSnapLyricTimeSmoothing(
    displayedTimeMs: Long,
    targetTimeMs: Long,
    maxAnimatedDeltaMs: Long = 180L
): Boolean =
    moe.ouom.neriplayer.ui.component.lyrics.shouldSnapLyricTimeSmoothing(
        displayedTimeMs = displayedTimeMs,
        targetTimeMs = targetTimeMs,
        maxAnimatedDeltaMs = maxAnimatedDeltaMs
    )

internal fun lyricListItemKey(index: Int, line: LyricEntry): String =
    moe.ouom.neriplayer.ui.component.lyrics.lyricListItemKey(index, line)

internal fun resolveHeadGlowTarget(
    currentLine: Int,
    nextLine: Int,
    currentLineRight: Float,
    currentLineCenterY: Float,
    nextCharLeft: Float,
    nextLineCenterY: Float
): HeadGlowTarget =
    moe.ouom.neriplayer.ui.component.lyrics.resolveHeadGlowTarget(
        currentLine = currentLine,
        nextLine = nextLine,
        currentLineRight = currentLineRight,
        currentLineCenterY = currentLineCenterY,
        nextCharLeft = nextCharLeft,
        nextLineCenterY = nextLineCenterY
    )

fun buildPhoneticLyricEntries(
    rawLyrics: String?,
    lyrics: List<LyricEntry>
): List<LyricEntry> =
    moe.ouom.neriplayer.ui.component.lyrics.buildPhoneticLyricEntries(
        rawLyrics = rawLyrics,
        lyrics = lyrics
    )

@Composable
fun rememberLyricSeekHapticFeedback(
    lyrics: List<LyricEntry>,
    lyricOffsetMs: Long = 0L
): moe.ouom.neriplayer.ui.component.lyrics.LyricSeekHapticFeedback =
    moe.ouom.neriplayer.ui.component.lyrics.rememberLyricSeekHapticFeedback(
        lyrics = lyrics,
        lyricOffsetMs = lyricOffsetMs
    )

@Composable
fun LyricShareSheet(
    song: SongItem,
    lyrics: List<LyricEntry>,
    initialLine: LyricEntry,
    queue: List<SongItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    moe.ouom.neriplayer.ui.component.lyrics.LyricShareSheet(
        song = song,
        lyrics = lyrics,
        initialLine = initialLine,
        queue = queue,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
