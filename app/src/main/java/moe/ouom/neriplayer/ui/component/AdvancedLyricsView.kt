package moe.ouom.neriplayer.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.ModernKaraokeLyricsView
import moe.ouom.neriplayer.data.settings.scaledLyricFontSize
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max

private const val TranslationAlignmentToleranceMs = 1_500L
private const val InterpolatedPlaybackResyncThresholdMs = 220L
private const val InterpolatedPlaybackBackwardToleranceMs = 24L
private const val FocusedLyricVisualCompensationRatio = 0.42f
private val FocusedLyricMaskSafePadding = 24.dp

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
    lyricBlurEnabled: Boolean = true,
    lyricBlurAmount: Float = 2.5f,
    isPlaying: Boolean = false,
    animateViewportScroll: Boolean = false,
    playbackSpeed: Float = 1f,
    offset: Dp = 48.dp,
    keepAliveZone: Dp = 108.dp,
    playedLyricViewportFraction: Float = 0.30f,
    topFadeLength: Dp = 112.dp,
    bottomFadeLength: Dp = 196.dp,
    bottomContentInset: Dp = 0.dp,
    onSeekTo: (Long) -> Unit = {}
) {
    val effectiveTranslatedLyrics = translatedLyrics.orEmpty()
    val syncedLyrics = remember(rawLyrics, rawTranslatedLyrics, lyrics, effectiveTranslatedLyrics) {
        buildAdvancedSyncedLyrics(
            rawLyrics = rawLyrics,
            rawTranslatedLyrics = rawTranslatedLyrics,
            lyrics = lyrics,
            translatedLyrics = effectiveTranslatedLyrics
        )
    }
    if (syncedLyrics.lines.isEmpty()) {
        return
    }

    val normalFontSize = scaledLyricFontSize(baseFontSizeSp, lyricFontScale).sp
    val accompanimentFontSize = scaledLyricFontSize(baseFontSizeSp * 0.62f, lyricFontScale).sp
    val normalTextStyle = remember(normalFontSize) {
        TextStyle(
            fontSize = normalFontSize,
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
            lineHeight = (normalFontSize.value * 1.18f).sp
        )
    }
    val accompanimentTextStyle = remember(accompanimentFontSize) {
        TextStyle(
            fontSize = accompanimentFontSize,
            fontWeight = FontWeight.SemiBold,
            textMotion = TextMotion.Animated,
            lineHeight = (accompanimentFontSize.value * 1.12f).sp
        )
    }
    val listState = rememberLazyListState()
    val blurDelta = (lyricBlurAmount * 0.45f).coerceIn(0f, 4f)
    val safeCurrentPosition = (currentTimeMs + lyricOffsetMs)
        .coerceAtLeast(0L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
    val renderPositionProvider = rememberInterpolatedPlaybackPositionProvider(
        currentTimeMs = safeCurrentPosition,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed
    )

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val focusedLineVisualCompensation = with(density) {
            normalTextStyle.lineHeight.toDp() * FocusedLyricVisualCompensationRatio
        }
        val effectiveOffset = resolvePlayedLyricViewportOffset(
            viewportHeight = maxHeight,
            keepAliveZone = keepAliveZone,
            minimumOffset = offset,
            playedLyricViewportFraction = playedLyricViewportFraction,
            focusedLineVisualCompensation = focusedLineVisualCompensation,
            topFadeLength = topFadeLength
        )

        ModernKaraokeLyricsView(
            listState = listState,
            lyrics = syncedLyrics,
            currentPosition = { safeCurrentPosition.toInt() },
            renderCurrentPosition = renderPositionProvider,
            onLineClicked = { line -> onSeekTo(line.start.toLong()) },
            onLinePressed = { line -> onSeekTo(line.start.toLong()) },
            modifier = Modifier.fillMaxSize(),
            normalLineTextStyle = normalTextStyle,
            accompanimentLineTextStyle = accompanimentTextStyle,
            textColor = textColor,
            showTranslation = showLyricTranslation,
            showPhonetic = false,
            useBlurEffect = lyricBlurEnabled,
            animateViewportScroll = animateViewportScroll,
            offset = effectiveOffset,
            keepAliveZone = keepAliveZone,
            bottomContentInset = bottomContentInset,
            blurDelta = blurDelta,
            topFadeLength = topFadeLength,
            bottomFadeLength = bottomFadeLength
        )
    }
}

internal fun resolvePlayedLyricViewportOffset(
    viewportHeight: Dp,
    keepAliveZone: Dp,
    minimumOffset: Dp,
    playedLyricViewportFraction: Float,
    focusedLineVisualCompensation: Dp,
    topFadeLength: Dp
): Dp {
    val effectivePlayedLyricViewportFraction = playedLyricViewportFraction.coerceIn(0.18f, 0.46f)
    val desiredPlayedLyricSpace = viewportHeight * effectivePlayedLyricViewportFraction
    val minimumVisiblePlayedLyricSpace = topFadeLength + FocusedLyricMaskSafePadding
    val resolvedPlayedLyricSpace = if (desiredPlayedLyricSpace > minimumVisiblePlayedLyricSpace) {
        desiredPlayedLyricSpace
    } else {
        minimumVisiblePlayedLyricSpace
    }
    return (
        resolvedPlayedLyricSpace + focusedLineVisualCompensation - keepAliveZone
        ).coerceAtLeast(minimumOffset)
}

@Composable
private fun rememberInterpolatedPlaybackPositionProvider(
    currentTimeMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float
): () -> Int {
    var renderedPositionMs by remember { mutableLongStateOf(currentTimeMs) }
    var anchorPositionMs by remember { mutableLongStateOf(currentTimeMs) }
    var anchorRealtimeNanos by remember { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(currentTimeMs, isPlaying) {
        anchorPositionMs = currentTimeMs
        anchorRealtimeNanos = System.nanoTime()
        if (shouldSnapInterpolatedPlaybackPosition(currentTimeMs, renderedPositionMs, isPlaying)) {
            renderedPositionMs = currentTimeMs
        } else if (currentTimeMs > renderedPositionMs) {
            renderedPositionMs = currentTimeMs
        }
    }

    LaunchedEffect(isPlaying, playbackSpeed) {
        if (!isPlaying) {
            renderedPositionMs = currentTimeMs
            return@LaunchedEffect
        }

        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val predictedPositionMs = resolveInterpolatedPlaybackPosition(
                anchorPositionMs = anchorPositionMs,
                anchorRealtimeNanos = anchorRealtimeNanos,
                frameRealtimeNanos = frameNanos,
                playbackSpeed = playbackSpeed,
                previousRenderedPositionMs = renderedPositionMs
            )
            if (predictedPositionMs != renderedPositionMs) {
                renderedPositionMs = predictedPositionMs
            }
        }
    }

    return remember {
        {
            renderedPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}

internal fun shouldSnapInterpolatedPlaybackPosition(
    externalPositionMs: Long,
    renderedPositionMs: Long,
    isPlaying: Boolean,
    snapThresholdMs: Long = InterpolatedPlaybackResyncThresholdMs
): Boolean {
    if (!isPlaying) {
        return true
    }
    return abs(externalPositionMs - renderedPositionMs) >= snapThresholdMs
}

internal fun resolveInterpolatedPlaybackPosition(
    anchorPositionMs: Long,
    anchorRealtimeNanos: Long,
    frameRealtimeNanos: Long,
    playbackSpeed: Float,
    previousRenderedPositionMs: Long,
    backwardToleranceMs: Long = InterpolatedPlaybackBackwardToleranceMs
): Long {
    val elapsedNanos = (frameRealtimeNanos - anchorRealtimeNanos).coerceAtLeast(0L)
    val predictedDeltaMs = (
        (elapsedNanos / 1_000_000.0) * playbackSpeed.coerceAtLeast(0f)
        ).toLong()
    val predictedPositionMs = anchorPositionMs +
        predictedDeltaMs
    val backwardDeltaMs = previousRenderedPositionMs - predictedPositionMs
    return if (backwardDeltaMs in 1..backwardToleranceMs) {
        previousRenderedPositionMs
    } else {
        predictedPositionMs
    }
}

internal fun buildAdvancedSyncedLyrics(
    rawLyrics: String?,
    rawTranslatedLyrics: String?,
    lyrics: List<LyricEntry>,
    translatedLyrics: List<LyricEntry>
): SyncedLyrics {
    val shouldPreferParsedLyrics = lyrics.hasWordTimedEntries() &&
        (rawLyrics.isNullOrBlank() || !isNeteaseYrc(rawLyrics))
    val baseLyrics = when {
        shouldPreferParsedLyrics -> lyrics.toSyncedLyrics()
        else -> parseRawLyrics(rawLyrics).takeIf { it.lines.isNotEmpty() }
            ?: lyrics.toSyncedLyrics()
    }
    val translationEntries = when {
        !rawTranslatedLyrics.isNullOrBlank() -> parseNeteaseLrc(rawTranslatedLyrics)
        translatedLyrics.isNotEmpty() -> translatedLyrics
        else -> emptyList()
    }
    return baseLyrics.attachTranslations(translationEntries)
}

private fun parseRawLyrics(rawLyrics: String?): SyncedLyrics {
    if (rawLyrics.isNullOrBlank()) {
        return SyncedLyrics(emptyList())
    }
    return runCatching { AutoParser().parse(rawLyrics) }
        .getOrDefault(SyncedLyrics(emptyList()))
}

private fun List<LyricEntry>.toSyncedLyrics(): SyncedLyrics {
    if (isEmpty()) {
        return SyncedLyrics(emptyList())
    }
    return SyncedLyrics(lines = map { it.toSyncedLine() })
}

private fun LyricEntry.toSyncedLine(): ISyncedLine {
    val syllables = words.orEmpty().mapIndexedNotNull { index, word ->
        val content = extractWordContent(index)
        if (content.isEmpty()) {
            null
        } else {
            KaraokeSyllable(
                content = content,
                start = word.startTimeMs.toIntSafely(),
                end = max(word.endTimeMs.toIntSafely(), word.startTimeMs.toIntSafely())
            )
        }
    }

    if (syllables.isEmpty()) {
        return SyncedLine(
            content = text,
            translation = null,
            start = startTimeMs.toIntSafely(),
            end = endTimeMs.toIntSafely()
        )
    }

    return KaraokeLine.MainKaraokeLine(
        syllables = syllables,
        translation = null,
        alignment = KaraokeAlignment.Unspecified,
        start = startTimeMs.toIntSafely(),
        end = max(endTimeMs.toIntSafely(), syllables.last().end)
    )
}

private fun LyricEntry.extractWordContent(index: Int): String {
    val safeWords = words.orEmpty()
    if (safeWords.isEmpty()) {
        return text
    }

    var cursor = 0
    safeWords.forEachIndexed { currentIndex, word ->
        val requestedLength = word.charCount.coerceAtLeast(0)
        val isLast = currentIndex == safeWords.lastIndex
        val endExclusive = when {
            isLast -> text.length
            requestedLength == 0 -> cursor
            else -> (cursor + requestedLength).coerceAtMost(text.length)
        }
        if (currentIndex == index) {
            return text.substring(cursor.coerceAtMost(text.length), endExclusive)
        }
        cursor = endExclusive
    }
    return ""
}

private fun SyncedLyrics.attachTranslations(translations: List<LyricEntry>): SyncedLyrics {
    if (lines.isEmpty() || translations.isEmpty()) {
        return this
    }

    val updatedLines = lines.map { line ->
        val matchedTranslation = findBestMatchingTranslation(
            translations = translations,
            lineStartMs = line.start.toLong(),
            lineEndMs = line.end.toLong(),
            toleranceMs = TranslationAlignmentToleranceMs
        )?.text

        when {
            matchedTranslation.isNullOrBlank() -> line
            line is KaraokeLine.MainKaraokeLine && line.translation.isNullOrBlank() ->
                line.copy(translation = matchedTranslation)
            line is SyncedLine && line.translation.isNullOrBlank() ->
                line.copy(translation = matchedTranslation)
            else -> line
        }
    }

    return copy(lines = updatedLines)
}

private fun Long.toIntSafely(): Int {
    return coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
