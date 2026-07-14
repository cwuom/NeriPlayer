package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import kotlin.math.abs
import kotlin.math.max

private const val TranslationAlignmentToleranceMs = 450L
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
    val effectiveTranslatedLyrics = translatedLyrics.orEmpty()
    val syncedLyrics = remember(
        rawLyrics,
        rawTranslatedLyrics,
        lyrics,
        effectiveTranslatedLyrics,
        showPhoneticAsTranslation
    ) {
        buildAdvancedSyncedLyrics(
            rawLyrics = rawLyrics,
            rawTranslatedLyrics = rawTranslatedLyrics,
            lyrics = lyrics,
            translatedLyrics = effectiveTranslatedLyrics,
            showPhoneticAsTranslation = showPhoneticAsTranslation
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
        playbackSpeed = playbackSpeed,
        frameIntervalNanos = if (lowPowerRendering) {
            InterpolatedPlaybackLowPowerFrameIntervalNanos
        } else {
            InterpolatedPlaybackDefaultFrameIntervalNanos
        }
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
            onLinePressed = { line ->
                val entry = resolvePressedLyricEntry(line, lyrics)
                if (onLyricLongClick != null) {
                    onLyricLongClick(entry)
                } else {
                    onSeekTo(line.start.toLong())
                }
            },
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
            blurDelta = if (lowPowerRendering) blurDelta * 0.55f else blurDelta,
            topFadeLength = topFadeLength,
            bottomFadeLength = bottomFadeLength,
            useAdditiveBlend = !lowPowerRendering
        )
    }
}

private fun resolvePressedLyricEntry(
    line: ISyncedLine,
    lyrics: List<LyricEntry>
): LyricEntry {
    val startTimeMs = line.start.toLong()
    lyrics.firstOrNull { it.startTimeMs == startTimeMs }?.let { return it }
    lyrics.minByOrNull { abs(it.startTimeMs - startTimeMs) }
        ?.takeIf { abs(it.startTimeMs - startTimeMs) <= TranslationAlignmentToleranceMs }
        ?.let { return it }

    return LyricEntry(
        text = line.plainText(),
        startTimeMs = line.start.toLong(),
        endTimeMs = line.end.toLong()
    )
}

private fun ISyncedLine.plainText(): String {
    return when (this) {
        is KaraokeLine -> syllables.joinToString(separator = "") { it.content }
        is SyncedLine -> content
        else -> ""
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

internal fun buildAdvancedSyncedLyrics(
    rawLyrics: String?,
    rawTranslatedLyrics: String?,
    lyrics: List<LyricEntry>,
    translatedLyrics: List<LyricEntry>,
    showPhoneticAsTranslation: Boolean = false
): SyncedLyrics {
    val baseLyrics = resolveAdvancedBaseSyncedLyrics(
        rawLyrics = rawLyrics,
        lyrics = lyrics
    )
    if (showPhoneticAsTranslation) {
        return if (translatedLyrics.isNotEmpty()) {
            baseLyrics.attachTranslations(translatedLyrics, replaceExisting = true)
        } else {
            baseLyrics.withPhoneticsAsTranslations()
        }
    }
    val translationEntries = when {
        !rawTranslatedLyrics.isNullOrBlank() -> parseNeteaseLrc(rawTranslatedLyrics)
        translatedLyrics.isNotEmpty() -> translatedLyrics
        else -> emptyList()
    }
    return baseLyrics.attachTranslations(translationEntries)
}

fun buildPhoneticLyricEntries(
    rawLyrics: String?,
    lyrics: List<LyricEntry>
): List<LyricEntry> {
    return resolveAdvancedBaseSyncedLyrics(
        rawLyrics = rawLyrics,
        lyrics = lyrics
    ).lines.mapNotNull { line ->
        val phonetic = line.phoneticText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LyricEntry(
            text = phonetic,
            startTimeMs = line.start.toLong(),
            endTimeMs = line.end.toLong()
        )
    }
}

private fun resolveAdvancedBaseSyncedLyrics(
    rawLyrics: String?,
    lyrics: List<LyricEntry>
): SyncedLyrics {
    val shouldPreferParsedLyrics = lyrics.hasWordTimedEntries() &&
        (rawLyrics.isNullOrBlank() || !isNeteaseYrc(rawLyrics))
    return when {
        shouldPreferParsedLyrics -> lyrics.toSyncedLyrics()
        else -> parseRawLyrics(rawLyrics).takeIf { it.lines.isNotEmpty() }
            ?: lyrics.toSyncedLyrics()
    }
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

private fun SyncedLyrics.attachTranslations(
    translations: List<LyricEntry>,
    replaceExisting: Boolean = false
): SyncedLyrics {
    if (lines.isEmpty() || translations.isEmpty()) {
        return this
    }

    val baseLyricEntries = lines.map { line ->
        LyricEntry(
            text = "",
            startTimeMs = line.start.toLong(),
            endTimeMs = line.end.toLong()
        )
    }
    val translationMatchesByIndex = matchTranslationsToLineIndices(
        lines = baseLyricEntries,
        translations = translations,
        toleranceMs = TranslationAlignmentToleranceMs
    )

    val updatedLines = lines.mapIndexed { index, line ->
        val matchedTranslation = translationMatchesByIndex[index]?.text

        when {
            matchedTranslation.isNullOrBlank() -> line
            line is KaraokeLine.MainKaraokeLine && (replaceExisting || line.translation.isNullOrBlank()) ->
                line.copy(translation = matchedTranslation)
            line is SyncedLine && (replaceExisting || line.translation.isNullOrBlank()) ->
                line.copy(translation = matchedTranslation)
            else -> line
        }
    }

    return copy(lines = updatedLines)
}

private fun SyncedLyrics.withPhoneticsAsTranslations(): SyncedLyrics {
    if (lines.isEmpty()) {
        return this
    }

    return copy(
        lines = lines.map { line ->
            val phonetic = line.phoneticText()?.takeIf { it.isNotBlank() } ?: return@map line
            when (line) {
                is KaraokeLine.MainKaraokeLine -> line.copy(
                    translation = phonetic,
                    accompanimentLines = line.accompanimentLines?.map { accompaniment ->
                        val accompanimentPhonetic = accompaniment.phoneticText()
                        accompaniment.copy(
                            translation = accompanimentPhonetic.takeUnless { it.isNullOrBlank() }
                                ?: accompaniment.translation
                        )
                    }
                )
                is KaraokeLine.AccompanimentKaraokeLine -> line.copy(translation = phonetic)
                is SyncedLine -> line
                else -> line
            }
        }
    )
}

private fun ISyncedLine.phoneticText(): String? {
    return when (this) {
        is KaraokeLine -> linePhoneticText()
        else -> null
    }
}

private fun KaraokeLine.linePhoneticText(): String? {
    phonetic?.takeIf { it.isNotBlank() }?.let { return it }
    return syllables.joinToString(separator = " ") { it.phonetic.orEmpty() }
        .trim()
        .takeIf { it.isNotEmpty() }
}

private fun Long.toIntSafely(): Int {
    return coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
