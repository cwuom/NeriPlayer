package moe.ouom.neriplayer.ui.component.lyrics

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
 * File: moe.ouom.neriplayer.ui.component/SyncedLyricsView
 * Created: 2025/8/13
 */

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import moe.ouom.neriplayer.core.player.metadata.normalizeLegacyLrcTimestamps

private const val LYRIC_TIME_SMOOTHING_DURATION_MS = 96
private const val LYRIC_TIME_SMOOTHING_MAX_DELTA_MS = 180L
private const val LYRIC_TRANSLATION_GAP_REFERENCE_SP = 16f
private const val LYRIC_TRANSLATION_GAP_REFERENCE_DP = 4f
private const val LYRIC_TRANSLATION_GAP_MIN_DP = 2f
private const val LYRIC_TRANSLATION_GAP_MAX_DP = 8f
private const val EMBEDDED_ACTIVE_LINE_SCALE = 1.025f
private const val EMBEDDED_INACTIVE_LINE_SCALE = 0.985f
private const val EMBEDDED_LINE_SCALE_DURATION_MS = 220
private const val EMBEDDED_TRANSLATION_ENTER_DURATION_MS = 220
private const val EMBEDDED_TRANSLATION_EXIT_DURATION_MS = 220
private const val EMBEDDED_TRANSLATION_ENTER_SCALE = 0.98f
private const val EMBEDDED_TRANSLATION_EXIT_SCALE = 0.99f
private const val MANUAL_TRANSLATION_ENTER_DURATION_MS = 260
private const val MANUAL_TRANSLATION_EXIT_DURATION_MS = 220
private const val MANUAL_TRANSLATION_ENTER_SCALE = 0.97f
private const val MANUAL_TRANSLATION_EXIT_SCALE = 0.99f
private const val MANUAL_LYRIC_PRESENTATION_DURATION_MS = 280
private const val JAPANESE_LYRIC_TRANSLATION_EXTRA_GAP_DP = 3f
private const val LYRIC_LINE_HEIGHT_MULTIPLIER = 1.18f
private const val LYRIC_TRANSLATION_LINE_HEIGHT_MULTIPLIER = 1.12f

private data class LyricInkMetrics(
    val coverage: Float
)

internal fun shouldAnimateLyricItemPlacement(): Boolean {
    return false
}

internal enum class LyricTranslationTransitionMode {
    MANUAL_EXPANSION,
    PLAYBACK_CHANGE
}

internal fun resolveLyricTranslationTransitionMode(
    isManualReveal: Boolean
): LyricTranslationTransitionMode {
    return if (isManualReveal) {
        LyricTranslationTransitionMode.MANUAL_EXPANSION
    } else {
        LyricTranslationTransitionMode.PLAYBACK_CHANGE
    }
}

internal fun shouldHoldLyricViewportForManualScroll(
    manualScrollAnchorIndex: Int?,
    currentIndex: Int
): Boolean {
    return manualScrollAnchorIndex != null && manualScrollAnchorIndex == currentIndex
}

internal fun resolveInitialLyricScrollIndex(
    currentIndex: Int,
    lyricsSize: Int,
    stabilizeViewport: Boolean
): Int {
    if (!stabilizeViewport || lyricsSize <= 0) return 0
    return currentIndex.coerceIn(0, lyricsSize - 1)
}

internal fun shouldAutoScrollLyricViewport(
    currentIndex: Int,
    lyricsSize: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    isUserInteracting: Boolean,
    manualScrollAnchorIndex: Int? = null
): Boolean {
    return !isUserInteracting &&
        !shouldHoldLyricViewportForManualScroll(manualScrollAnchorIndex, currentIndex) &&
        currentIndex in 0 until lyricsSize &&
        (firstVisibleItemIndex != currentIndex || firstVisibleItemScrollOffset != 0)
}

internal data class LyricAutoScrollTarget(
    val lineIndex: Int,
    val lyricsSize: Int
)

internal fun shouldFinishLyricAutoScroll(
    requestTarget: LyricAutoScrollTarget,
    latestTarget: LyricAutoScrollTarget
): Boolean = requestTarget == latestTarget

internal fun resolveLyricScrollSessionKey(
    playbackSessionKey: String?,
    lyrics: List<LyricEntry>
): Any {
    return playbackSessionKey?.takeIf { it.isNotBlank() } ?: lyrics
}

internal fun resolveEmbeddedLyricScale(isActive: Boolean): Float {
    return if (isActive) EMBEDDED_ACTIVE_LINE_SCALE else EMBEDDED_INACTIVE_LINE_SCALE
}

internal fun resolveEmbeddedTranslationTransformOrigin(): TransformOrigin {
    return TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0.35f)
}

internal fun resolveLyricClearPresentationTarget(
    isManualPresentation: Boolean
): Float {
    return if (isManualPresentation) 1f else 0f
}

@Composable
private fun rememberLyricClearPresentationProgress(
    isManualPresentation: Boolean
): Float {
    // new lazy items begin in playback form so they do not pop clear mid-gesture
    val presentationProgress = remember { Animatable(0f) }

    LaunchedEffect(isManualPresentation) {
        presentationProgress.animateTo(
            targetValue = resolveLyricClearPresentationTarget(isManualPresentation),
            animationSpec = tween(
                durationMillis = MANUAL_LYRIC_PRESENTATION_DURATION_MS,
                easing = FastOutSlowInEasing
            )
        )
    }

    return presentationProgress.value
}

private fun lyricTranslationEnterTransition(
    transitionMode: LyricTranslationTransitionMode
): EnterTransition {
    val isManualExpansion = transitionMode == LyricTranslationTransitionMode.MANUAL_EXPANSION
    val enterDuration = if (isManualExpansion) {
        MANUAL_TRANSLATION_ENTER_DURATION_MS
    } else {
        EMBEDDED_TRANSLATION_ENTER_DURATION_MS
    }
    val enterScale = if (isManualExpansion) {
        MANUAL_TRANSLATION_ENTER_SCALE
    } else {
        EMBEDDED_TRANSLATION_ENTER_SCALE
    }
    return fadeIn(
        animationSpec = tween(
            durationMillis = enterDuration,
            easing = FastOutSlowInEasing
        )
    ) + scaleIn(
        initialScale = enterScale,
        transformOrigin = resolveEmbeddedTranslationTransformOrigin(),
        animationSpec = tween(
            durationMillis = enterDuration,
            easing = FastOutSlowInEasing
        )
    ) + expandVertically(
        expandFrom = Alignment.Top,
        animationSpec = tween(
            durationMillis = enterDuration,
            easing = FastOutSlowInEasing
        )
    )
}

private fun lyricTranslationExitTransition(
    transitionMode: LyricTranslationTransitionMode
): ExitTransition {
    val isManualExpansion = transitionMode == LyricTranslationTransitionMode.MANUAL_EXPANSION
    val exitDuration = if (isManualExpansion) {
        MANUAL_TRANSLATION_EXIT_DURATION_MS
    } else {
        EMBEDDED_TRANSLATION_EXIT_DURATION_MS
    }
    val exitScale = if (isManualExpansion) {
        MANUAL_TRANSLATION_EXIT_SCALE
    } else {
        EMBEDDED_TRANSLATION_EXIT_SCALE
    }
    return fadeOut(
        animationSpec = tween(
            durationMillis = exitDuration,
            easing = FastOutSlowInEasing
        )
    ) + scaleOut(
        targetScale = exitScale,
        transformOrigin = resolveEmbeddedTranslationTransformOrigin(),
        animationSpec = tween(
            durationMillis = exitDuration,
            easing = FastOutSlowInEasing
        )
    ) + shrinkVertically(
        shrinkTowards = Alignment.Top,
        animationSpec = tween(
            durationMillis = exitDuration,
            easing = FastOutSlowInEasing
        )
    )
}

private fun interpolateLyricVisualValue(
    playbackValue: Float,
    clearValue: Float,
    clearPresentationProgress: Float
): Float {
    return playbackValue + (clearValue - playbackValue) * clearPresentationProgress
}

internal fun containsJapaneseKana(text: String): Boolean {
    return text.any { char ->
        char in '\u3040'..'\u30FF' ||
            char in '\u31F0'..'\u31FF' ||
            char in '\uFF66'..'\uFF9F'
    }
}

internal fun resolveLyricTranslationExtraGap(
    lyricText: String,
    isLyricsPage: Boolean
): Dp {
    return if (isLyricsPage && containsJapaneseKana(lyricText)) {
        JAPANESE_LYRIC_TRANSLATION_EXTRA_GAP_DP.dp
    } else {
        0.dp
    }
}

internal fun resolveLyricTranslationGap(
    lyricFontSize: TextUnit,
    translationFontSize: TextUnit,
    lyricGlyphCoverage: Float = 1f,
    translationGlyphCoverage: Float = 1f,
    fontScale: Float = 1f
): Dp {
    val lyricSize = lyricFontSize.value.takeIf { it.isFinite() && it > 0f }
    val translationSize = translationFontSize.value.takeIf { it.isFinite() && it > 0f }
    val referenceFontSize = when {
        lyricSize != null && translationSize != null -> (lyricSize + translationSize) / 2f
        lyricSize != null -> lyricSize
        translationSize != null -> translationSize
        else -> LYRIC_TRANSLATION_GAP_REFERENCE_SP
    }
    val glyphCoverage = (
        lyricGlyphCoverage.coerceAtLeast(0f) + translationGlyphCoverage.coerceAtLeast(0f)
        ) / 2f
    val scaledGap = referenceFontSize /
        LYRIC_TRANSLATION_GAP_REFERENCE_SP * LYRIC_TRANSLATION_GAP_REFERENCE_DP *
        glyphCoverage.coerceIn(0.75f, 1f) * fontScale.coerceAtLeast(0.1f)
    return scaledGap.coerceIn(
        LYRIC_TRANSLATION_GAP_MIN_DP,
        LYRIC_TRANSLATION_GAP_MAX_DP
    ).dp
}

private fun measureLyricInkMetrics(
    text: String,
    fontSize: TextUnit,
    typeface: Typeface
): LyricInkMetrics {
    val sizeSp = fontSize.value.takeIf { it.isFinite() && it > 0f }
        ?: LYRIC_TRANSLATION_GAP_REFERENCE_SP
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeSp
        this.typeface = typeface
    }
    val bounds = Rect()
    val glyphHeight = text.lineSequence()
        .filter { it.isNotEmpty() }
        .maxOfOrNull { line ->
            paint.getTextBounds(line, 0, line.length, bounds)
            bounds.height().toFloat()
        }
        ?: 0f
    return LyricInkMetrics(
        coverage = (glyphHeight / sizeSp).coerceIn(0f, 1.5f)
    )
}

private fun resolveLyricFontTypeface(fontWeight: FontWeight): Typeface {
    return if (fontWeight >= FontWeight.Medium) {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    } else {
        Typeface.create("sans-serif", Typeface.NORMAL)
    }
}

private fun resolveLyricLineHeight(fontSize: TextUnit, multiplier: Float): TextUnit {
    val size = fontSize.value.takeIf { it.isFinite() && it > 0f }
        ?: LYRIC_TRANSLATION_GAP_REFERENCE_SP
    return if (fontSize.value.isFinite() && fontSize.value > 0f) {
        fontSize * multiplier
    } else {
        (size * multiplier).sp
    }
}

@Stable
data class LyricVisualSpec(
    val pageTiltDeg: Float = 9f,
    val activeScale: Float = 1.1f,
    val nearScale: Float = 0.9f,
    val farScale: Float = 0.88f,

    val farScaleMin: Float = 0.8f,
    val farScaleFalloffPerStep: Float = 0.02f,

    val inactiveBlurNear: Dp = 2.dp,
    val inactiveBlurFar: Dp = 3.dp,
    val flipDurationMs: Int = 260
)

/** 单词/字的时间戳 */
data class WordTiming(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val charCount: Int = 0
)

/** 一行歌词 */
data class LyricEntry(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val words: List<WordTiming>? = null,
    val translation: String? = null
)

private val NeteaseYrcLineRegex = Regex("""\[\d{1,19},\s*\d{1,19}]\(\d{1,19},""")
private val TtmlTagRegex = Regex("""<\s*tt(?:\s|>)""", RegexOption.IGNORE_CASE)
private val TtmlLayoutWhitespaceRegex = Regex("""[\r\n]\s*""")
private val EnhancedLrcLineTimestampRegex = Regex(
    """\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]"""
)
private val EnhancedLrcWordTimestampRegex = Regex(
    """<(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?>"""
)
private val LrcCreditLineRegex = Regex(
    """^(?:作词|作曲|编曲|填词|演唱|歌手|混音|母带|制作|监制|录音|和声|配唱|吉他(?:solo)?|贝斯|鼓|键盘|弦乐|vo(?:/mix)?|mix|tune|inst|guitar|bass|drums?|vocal|lyrics|music|arrangement|produced)\s*[:：]""",
    RegexOption.IGNORE_CASE
)

private data class LrcTimelineEntry(
    val startTimeMs: Long,
    val text: String
)

private data class EnhancedLrcWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long?
)

private data class EnhancedLrcTimelineEntry(
    val startTimeMs: Long,
    val text: String,
    val words: List<EnhancedLrcWord>
)

fun isNeteaseYrc(content: String): Boolean = content.contains(NeteaseYrcLineRegex)

internal fun isTtmlLyrics(content: String): Boolean = TtmlTagRegex.containsMatchIn(content)

fun parseNeteaseLyricsAuto(content: String): List<LyricEntry> {
    return when {
        isTtmlLyrics(content) -> parseTtmlLyrics(content)
        isNeteaseYrc(content) -> runCatching { parseNeteaseYrc(content) }.getOrDefault(emptyList())
        isEnhancedLrc(content) -> parseEnhancedLrc(content)
        else -> parseNeteaseLrc(content)
    }
}

fun parseTtmlLyrics(content: String): List<LyricEntry> {
    return runCatching {
        AutoParser().parse(content).lines
            .mapNotNull(::toLyricEntry)
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startTimeMs }
    }.getOrDefault(emptyList())
}

private fun toLyricEntry(line: ISyncedLine): LyricEntry? {
    val startMs = line.start.toLong()
    val endMs = line.end.toLong().coerceAtLeast(startMs)
    return when (line) {
        is KaraokeLine -> {
            val syllables = line.syllables
                .map { syllable -> syllable to syllable.content.withoutTtmlLayoutWhitespace() }
                .filter { (_, content) -> content.isNotBlank() }
            val text = syllables.joinToString(separator = "") { (_, content) -> content }
            LyricEntry(
                text = text,
                startTimeMs = startMs,
                endTimeMs = endMs,
                words = syllables.map { (syllable, content) ->
                    WordTiming(
                        startTimeMs = syllable.start.toLong(),
                        endTimeMs = syllable.end.toLong().coerceAtLeast(syllable.start.toLong()),
                        charCount = content.length
                    )
                }.takeIf { it.isNotEmpty() },
                translation = line.translation
                    ?.withoutTtmlLayoutWhitespace()
                    ?.takeIf { it.isNotBlank() }
            )
        }
        is SyncedLine -> LyricEntry(
            text = line.content.withoutTtmlLayoutWhitespace(),
            startTimeMs = startMs,
            endTimeMs = endMs,
            translation = line.translation
                ?.withoutTtmlLayoutWhitespace()
                ?.takeIf { it.isNotBlank() }
        )
        else -> null
    }
}

private fun String.withoutTtmlLayoutWhitespace(): String {
    return replace(TtmlLayoutWhitespaceRegex, "")
}

/**
 * 根据当前时间计算该行的高亮进度 (0f..1f) , 基于字符数进行精确计算
 */
fun calculateLineProgress(line: LyricEntry, currentTimeMs: Long): Float {
    val start = line.startTimeMs
    val end = line.endTimeMs

    if (currentTimeMs <= start) return 0f
    if (currentTimeMs >= end) return 1f

    val words = line.words
    val totalChars = line.text.length
    if (words.isNullOrEmpty() || totalChars == 0) {
        val lineDur = (end - start).coerceAtLeast(1)
        return ((currentTimeMs - start).toFloat() / lineDur).coerceIn(0f, 1f)
    }

    var completedChars = 0
    for (word in words) {
        val ws = word.startTimeMs
        val we = word.endTimeMs

        if (currentTimeMs < ws) {
            return completedChars.toFloat() / totalChars
        }

        if (currentTimeMs < we) {
            val wordDur = (we - ws).coerceAtLeast(1)
            val timeInWord = currentTimeMs - ws
            val partialProgress = timeInWord.toFloat() / wordDur
            val partialChars = partialProgress * word.charCount
            return ((completedChars + partialChars) / totalChars).coerceIn(0f, 1f)
        }

        completedChars += word.charCount
    }

    return 1f
}
/** 找到当前时间所在的行索引 */
fun findCurrentLineIndex(lines: List<LyricEntry>, currentTimeMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.lastIndex
    var result = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startTimeMs <= currentTimeMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

internal fun shouldSnapLyricTimeSmoothing(
    displayedTimeMs: Long,
    targetTimeMs: Long,
    maxAnimatedDeltaMs: Long = LYRIC_TIME_SMOOTHING_MAX_DELTA_MS
): Boolean {
    val delta = targetTimeMs - displayedTimeMs
    return delta < 0L || delta > maxAnimatedDeltaMs
}

internal fun resolveLyricSeekPosition(positionMs: Long, durationMs: Long): Long? {
    val safePositionMs = positionMs.coerceAtLeast(0L)
    val safeDurationMs = durationMs.takeIf { it > 0L } ?: return safePositionMs
    return safePositionMs.takeIf { it < safeDurationMs }
}

internal fun resolveLyricTranslationText(
    line: LyricEntry,
    matchedTranslation: LyricEntry?,
    showEmbeddedTranslations: Boolean
): String? {
    return matchedTranslation?.text?.takeIf { it.isNotBlank() }
        ?: line.translation?.takeIf { showEmbeddedTranslations }
}

@Composable
private fun animateFloatWhenEnabled(
    targetValue: Float,
    enabled: Boolean,
    animationDurationMs: Int? = null,
    label: String
): State<Float> {
    val animationSpec = if (animationDurationMs != null) {
        tween<Float>(durationMillis = animationDurationMs)
    } else {
        spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = 0.85f
        )
    }
    val animated = animateFloatAsState(
        targetValue = targetValue,
        animationSpec = animationSpec,
        label = label
    )
    val immediate = rememberUpdatedState(targetValue)
    return if (enabled) animated else immediate
}

@Composable
private fun rememberSmoothedLyricTimeMs(
    targetTimeMs: Long
): Long {
    val smoothedTime = remember { Animatable(targetTimeMs.toFloat()) }

    LaunchedEffect(targetTimeMs) {
        val displayedTimeMs = smoothedTime.value.roundToLong()
        if (shouldSnapLyricTimeSmoothing(displayedTimeMs, targetTimeMs)) {
            smoothedTime.snapTo(targetTimeMs.toFloat())
        } else {
            smoothedTime.animateTo(
                targetValue = targetTimeMs.toFloat(),
                animationSpec = tween(
                    durationMillis = LYRIC_TIME_SMOOTHING_DURATION_MS,
                    easing = LinearEasing
                )
            )
        }
    }

    return smoothedTime.value.roundToLong()
}

/** 上下渐隐 */
fun Modifier.verticalEdgeFade(fadeHeight: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (fadeHeight.toPx() / size.height).coerceIn(0f, 0.44f)
        if (edge <= 0f) return@drawWithContent
        val transparentMask = Color.Black.copy(alpha = 0f)
        val lightMask = Color.Black.copy(alpha = 0.12f)
        val mediumMask = Color.Black.copy(alpha = 0.58f)
        val nearOpaqueMask = Color.Black.copy(alpha = 0.9f)
        val brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to transparentMask,
                edge * 0.3f to lightMask,
                edge * 0.65f to mediumMask,
                edge to nearOpaqueMask,
                edge * 1.12f to Color.Black,
                1f - edge * 1.12f to Color.Black,
                1f - edge to nearOpaqueMask,
                1f - edge * 0.65f to mediumMask,
                1f - edge * 0.3f to lightMask,
                1.0f to transparentMask
            )
        )
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
    }

internal fun resolveLyricEdgeFadeHeight(isEmbedded: Boolean): Dp {
    return if (isEmbedded) 56.dp else 72.dp
}

@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("UnusedBoxWithConstraintsScope")
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
    smoothActiveLineProgress: Boolean = true,
    edgeFadeHeight: Dp = resolveLyricEdgeFadeHeight(isEmbedded = false),
    showEmbeddedTranslations: Boolean = true,
    playbackSessionKey: String? = null,
    stableEmbeddedViewport: Boolean = false
) {
    val lyricScrollSessionKey = remember(playbackSessionKey, lyrics) {
        resolveLyricScrollSessionKey(playbackSessionKey, lyrics)
    }

    val lineSelectionTimeMs = (currentTimeMs + lyricOffsetMs).coerceAtLeast(0L)
    val currentIndex = remember(lyrics, lineSelectionTimeMs) {
        findCurrentLineIndex(lyrics, lineSelectionTimeMs)
    }
    val listState = remember(lyricScrollSessionKey, stableEmbeddedViewport) {
        LazyListState(
            firstVisibleItemIndex = resolveInitialLyricScrollIndex(
                currentIndex = currentIndex,
                lyricsSize = lyrics.size,
                stabilizeViewport = stableEmbeddedViewport
            )
        )
    }
    var manualClearHoldIndex by remember(lyricScrollSessionKey) { mutableStateOf<Int?>(null) }
    var isAutoScrolling by remember(lyricScrollSessionKey) { mutableStateOf(false) }
    var lastUserInteracting by remember(lyricScrollSessionKey) { mutableStateOf(false) }

    val translationMatchesByIndex = remember(lyrics, translatedLyrics) {
        translatedLyrics
            ?.takeIf { it.isNotEmpty() }
            ?.let { translations ->
                matchTranslationsToLineIndices(
                    lines = lyrics,
                    translations = translations.filter { it.text.isNotBlank() }
                )
            }
            .orEmpty()
    }
    val animateItemPlacement = shouldAnimateLyricItemPlacement()

    val autoScrollTarget = LyricAutoScrollTarget(
        lineIndex = currentIndex,
        lyricsSize = lyrics.size
    )
    val latestAutoScrollTarget by rememberUpdatedState(autoScrollTarget)
    val isUserInteracting by remember(listState) {
        derivedStateOf { listState.isScrollInProgress && !isAutoScrolling }
    }

    LaunchedEffect(
        lyricScrollSessionKey,
        currentIndex,
        lyrics.size,
        isUserInteracting,
        manualClearHoldIndex
    ) {
        if (!shouldAutoScrollLyricViewport(
                currentIndex = currentIndex,
                lyricsSize = lyrics.size,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                isUserInteracting = isUserInteracting,
                manualScrollAnchorIndex = manualClearHoldIndex
            )
        ) {
            return@LaunchedEffect
        }

        val requestTarget = autoScrollTarget
        isAutoScrolling = true
        try {
            listState.animateScrollToItem(currentIndex)
        } finally {
            if (shouldFinishLyricAutoScroll(requestTarget, latestAutoScrollTarget)) {
                isAutoScrolling = false
            }
        }
    }

    LaunchedEffect(isUserInteracting, currentIndex) {
        if (isUserInteracting && !lastUserInteracting && currentIndex >= 0) {
            manualClearHoldIndex = currentIndex
        }
        lastUserInteracting = isUserInteracting
    }

    LaunchedEffect(currentIndex, isUserInteracting) {
        if (!isUserInteracting && manualClearHoldIndex != null && currentIndex != manualClearHoldIndex) {
            manualClearHoldIndex = null
        }
    }

    val shouldUseClearText = isUserInteracting ||
        shouldHoldLyricViewportForManualScroll(manualClearHoldIndex, currentIndex)
    val handleLyricClick: ((LyricEntry) -> Unit)? = onLyricClick?.let { callback ->
        { line ->
            manualClearHoldIndex = null
            callback(line)
        }
    }
    val handleLyricLongClick: ((LyricEntry) -> Unit)? = onLyricLongClick?.let { callback ->
        { line ->
            manualClearHoldIndex = null
            callback(line)
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val centerPad = maxHeight / 2.5f
        val maxTextWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp)
        val density = LocalDensity.current

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = centerPad, bottom = centerPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalEdgeFade(fadeHeight = edgeFadeHeight)
        ) {
            itemsIndexed(
                items = lyrics,
                key = { index, line ->
                    lyricScrollSessionKey to lyricListItemKey(index, line)
                }
            ) { index, line ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = centerPadding / 2, horizontal = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            enabled = handleLyricClick != null || handleLyricLongClick != null,
                            onClick = { handleLyricClick?.invoke(line) },
                            onLongClick = { handleLyricLongClick?.invoke(line) }
                        )
                        .then(
                            if (animateItemPlacement) {
                                Modifier.animateItem()
                            } else {
                                Modifier.animateItem(placementSpec = null)
                            }
                        )
                        .widthIn(max = maxTextWidth),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val distance = abs(index - currentIndex)
                    val isActive = index == currentIndex
                    val clearPresentationProgress =
                        rememberLyricClearPresentationProgress(shouldUseClearText)

                    val playbackScaleTarget =
                        if (!visualEffectsEnabled) resolveEmbeddedLyricScale(isActive)
                        else if (isActive) visualSpec.activeScale
                        else scaleForDistance(distance, visualSpec)
                    val animatedPlaybackScale by if (visualEffectsEnabled) {
                        animateFloatWhenEnabled(
                            targetValue = playbackScaleTarget,
                            enabled = true,
                            label = "lyric_scale"
                        )
                    } else {
                        animateFloatAsState(
                            targetValue = playbackScaleTarget,
                            animationSpec = tween(
                                durationMillis = EMBEDDED_LINE_SCALE_DURATION_MS,
                                easing = FastOutSlowInEasing
                            ),
                            label = "embedded_lyric_scale"
                        )
                    }
                    val playbackScale = if (isActive && visualEffectsEnabled) {
                        1f
                    } else {
                        animatedPlaybackScale
                    }
                    val scale = interpolateLyricVisualValue(
                        playbackValue = playbackScale,
                        clearValue = 1f,
                        clearPresentationProgress = clearPresentationProgress
                    )

                    val playbackTilt =
                        if (!visualEffectsEnabled || isActive) {
                            0f
                        } else if (index < currentIndex) {
                            visualSpec.pageTiltDeg
                        } else {
                            -visualSpec.pageTiltDeg
                        }
                    val animatedPlaybackRotationX by animateFloatWhenEnabled(
                        targetValue = playbackTilt,
                        enabled = visualEffectsEnabled,
                        animationDurationMs = visualSpec.flipDurationMs,
                        label = "lyric_flip"
                    )
                    val rotationX = interpolateLyricVisualValue(
                        playbackValue = animatedPlaybackRotationX,
                        clearValue = 0f,
                        clearPresentationProgress = clearPresentationProgress
                    )

                    val playbackBlurRadiusPx =
                        if (isActive || !lyricBlurEnabled || !visualEffectsEnabled) {
                            0f
                        } else {
                            blurForDistance(distance, lyricBlurAmount)
                        }
                    val blurRadiusPx = interpolateLyricVisualValue(
                        playbackValue = playbackBlurRadiusPx,
                        clearValue = 0f,
                        clearPresentationProgress = clearPresentationProgress
                    )
                    val blurEffect = remember(blurRadiusPx) {
                        if (blurRadiusPx > 0.1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp)
                        } else {
                            null
                        }
                    }
                    val shadowEffect = remember(blurRadiusPx, textColor) {
                        if (blurRadiusPx > 0.1f && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            Shadow(
                                color = textColor.copy(alpha = 0.28f),
                                offset = Offset.Zero,
                                blurRadius = blurRadiusPx
                            )
                        } else {
                            null
                        }
                    }
                    val playbackTextAlpha = when {
                        isActive -> 1f
                        lyricBlurEnabled -> alphaForDistance(
                            distance,
                            blurInactiveAlphaNear,
                            blurInactiveAlphaFar
                        )
                        else -> alphaForDistance(
                            distance,
                            inactiveAlphaNear,
                            inactiveAlphaFar
                        )
                    }
                    val textAlpha = interpolateLyricVisualValue(
                        playbackValue = playbackTextAlpha,
                        clearValue = 1f,
                        clearPresentationProgress = clearPresentationProgress
                    )
                    val lyricTransformModifier = Modifier.graphicsLayer {
                        transformOrigin = if (visualEffectsEnabled) {
                            TransformOrigin(0.5f, if (index < currentIndex) 1f else 0f)
                        } else {
                            TransformOrigin(0.5f, 0.5f)
                        }
                        cameraDistance = 16f * density.density
                        this.rotationX = rotationX
                        scaleX = scale
                        scaleY = scale
                        renderEffect = blurEffect
                    }
                    val clearTextStyle = TextStyle(
                        color = textColor.copy(alpha = textAlpha),
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        shadow = shadowEffect,
                        lineHeight = resolveLyricLineHeight(
                            fontSize,
                            LYRIC_LINE_HEIGHT_MULTIPLIER
                        )
                    )

                    if (isActive) {
                        Box {
                            Box(
                                modifier = lyricTransformModifier.graphicsLayer {
                                    alpha = 1f - clearPresentationProgress
                                }
                            ) {
                                SyncedLyricsActiveLine(
                                    line = line,
                                    currentTimeMs = currentTimeMs,
                                    activeColor = textColor,
                                    inactiveColor = textColor.copy(alpha = 0.5f),
                                    fontSize = fontSize,
                                    fadeWidth = 12.dp,
                                    lyricOffsetMs = lyricOffsetMs,
                                    isPlaying = isPlaying,
                                    playbackSpeed = playbackSpeed,
                                    interpolatePlaybackPosition = interpolatePlaybackPosition,
                                    animateProgress = smoothActiveLineProgress && !interpolatePlaybackPosition
                                )
                            }
                            Text(
                                text = line.text,
                                modifier = lyricTransformModifier.graphicsLayer {
                                    alpha = clearPresentationProgress
                                },
                                style = clearTextStyle,
                                maxLines = Int.MAX_VALUE,
                                softWrap = true
                            )
                        }
                    } else {
                        Text(
                            text = line.text,
                            modifier = lyricTransformModifier,
                            style = clearTextStyle,
                            maxLines = Int.MAX_VALUE,
                            softWrap = true
                        )
                    }

                    val transText = resolveLyricTranslationText(
                        line = line,
                        matchedTranslation = translationMatchesByIndex[index],
                        showEmbeddedTranslations = showEmbeddedTranslations
                    )
                    transText?.takeIf { it.isNotBlank() }?.let { translation ->
                        AnimatedLyricTranslation(
                            text = translation,
                            visible = shouldUseClearText || isActive,
                            transitionMode = resolveLyricTranslationTransitionMode(
                                isManualReveal = shouldUseClearText
                            ),
                            textColor = textColor,
                            lyricText = line.text,
                            lyricFontSize = fontSize,
                            fontSize = translationFontSize,
                            isLyricsPage = visualEffectsEnabled
                        )
                    }
                }
            }
        }
    }
}

internal fun lyricListItemKey(index: Int, line: LyricEntry): String {
    return "$index:${line.startTimeMs}:${line.endTimeMs}:${line.text}"
}

@Composable
private fun AnimatedLyricTranslation(
    text: String,
    visible: Boolean,
    transitionMode: LyricTranslationTransitionMode,
    textColor: Color,
    lyricText: String,
    lyricFontSize: TextUnit,
    fontSize: TextUnit,
    isLyricsPage: Boolean
) {
    val initiallyVisible =
        visible && transitionMode == LyricTranslationTransitionMode.PLAYBACK_CHANGE
    val visibilityState = remember { MutableTransitionState(initiallyVisible) }
    visibilityState.targetState = visible

    AnimatedVisibility(
        visibleState = visibilityState,
        enter = lyricTranslationEnterTransition(transitionMode),
        exit = lyricTranslationExitTransition(transitionMode)
    ) {
        LyricTranslationText(
            text = text,
            textColor = textColor,
            lyricText = lyricText,
            lyricFontSize = lyricFontSize,
            fontSize = fontSize,
            isLyricsPage = isLyricsPage
        )
    }
}

@Composable
private fun LyricTranslationText(
    text: String,
    textColor: Color,
    lyricText: String,
    lyricFontSize: TextUnit,
    fontSize: TextUnit,
    isLyricsPage: Boolean
) {
    val density = LocalDensity.current
    val translationGap = remember(
        lyricText,
        text,
        lyricFontSize,
        fontSize,
        isLyricsPage,
        density.fontScale
    ) {
        val lyricMetrics = measureLyricInkMetrics(
            text = lyricText,
            fontSize = lyricFontSize,
            typeface = resolveLyricFontTypeface(FontWeight.Medium)
        )
        val translationMetrics = measureLyricInkMetrics(
            text = text,
            fontSize = fontSize,
            typeface = resolveLyricFontTypeface(FontWeight.Normal)
        )
        resolveLyricTranslationGap(
            lyricFontSize = lyricFontSize,
            translationFontSize = fontSize,
            lyricGlyphCoverage = lyricMetrics.coverage,
            translationGlyphCoverage = translationMetrics.coverage,
            fontScale = density.fontScale
        ) + resolveLyricTranslationExtraGap(
            lyricText = lyricText,
            isLyricsPage = isLyricsPage
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(translationGap))
        Text(
            text = text,
            style = TextStyle(
                color = textColor.copy(alpha = 0.85f),
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = resolveLyricLineHeight(
                    fontSize,
                    LYRIC_TRANSLATION_LINE_HEIGHT_MULTIPLIER
                )
            ),
            maxLines = Int.MAX_VALUE,
            softWrap = true
        )
    }
}


/**
 * 解析网易云 yrc (逐字/逐词)
 * 示例: [12580,3470](12580,250,0)难(12830,300,0)以
 * 会把每段文字的长度写入 WordTiming.charCount, 用于多行逐字揭示
 */
fun parseNeteaseYrc(yrc: String): List<LyricEntry> {
//    NPLogger.d("parseYrc-N", yrc)
    val out = mutableListOf<LyricEntry>()
    val headerRegex = Regex("""\[(\d{1,19}),\s*(\d{1,19})]""")
    val segRegex = Regex("""\((\d{1,19}),\s*(\d{1,19}),\s*[-\d]{1,20}\)([^()\n\r]*)""")

    yrc.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        if (!line.startsWith("[")) return@forEach

        val header = headerRegex.find(line) ?: return@forEach
        val start = header.groupValues[1].toLongOrNull() ?: return@forEach
        val dur = header.groupValues[2].toLongOrNull() ?: return@forEach
        val end = start.saturatingAdd(dur)

        val segs = segRegex.findAll(line).toList()
        if (segs.isEmpty()) {
            val text = line.substringAfter("]").trim()
            out.add(LyricEntry(text = text, startTimeMs = start, endTimeMs = end, words = null))
        } else {
            val words = mutableListOf<WordTiming>()
            val sb = StringBuilder()
            for (m in segs) {
                val ws = m.groupValues[1].toLongOrNull() ?: continue
                val wd = m.groupValues[2].toLongOrNull() ?: continue
                val we = ws.saturatingAdd(wd)
                val t = m.groupValues[3]
                sb.append(t)
                words.add(WordTiming(ws, we, charCount = t.length))
            }
            out.add(
                LyricEntry(
                    text = sb.toString(),
                    startTimeMs = start,
                    endTimeMs = end,
                    words = words
                )
            )
        }
    }
    return out.sortedBy { it.startTimeMs }
}

private fun Long.saturatingAdd(other: Long): Long {
    return if (other > 0L && this > Long.MAX_VALUE - other) {
        Long.MAX_VALUE
    } else {
        this + other
    }
}

private fun isEnhancedLrc(content: String): Boolean {
    return content.lineSequence().any { rawLine ->
        val line = rawLine.trimStart()
        val lineTimestamp = EnhancedLrcLineTimestampRegex.find(line)
            ?.takeIf { it.range.first == 0 }
            ?: return@any false
        EnhancedLrcWordTimestampRegex.find(line.substring(lineTimestamp.range.last + 1)) != null
    }
}

private fun parseEnhancedLrc(lrc: String): List<LyricEntry> {
    val timeline = lrc.lineSequence()
        .mapNotNull(::parseEnhancedLrcTimelineEntry)
        .sortedBy(EnhancedLrcTimelineEntry::startTimeMs)
        .toList()

    return timeline.mapIndexed { index, line ->
        val nextLineStartMs = timeline.getOrNull(index + 1)?.startTimeMs
        val words = line.words.mapIndexed { wordIndex, word ->
            val fallbackEndMs = line.words.getOrNull(wordIndex + 1)?.startTimeMs
                ?: nextLineStartMs
                ?: line.startTimeMs.saturatingAdd(5_000L)
            WordTiming(
                startTimeMs = word.startTimeMs,
                endTimeMs = (word.endTimeMs ?: fallbackEndMs).coerceAtLeast(word.startTimeMs),
                charCount = word.text.length
            )
        }
        val endTimeMs = words.maxOfOrNull { it.endTimeMs }
            ?: nextLineStartMs
            ?: line.startTimeMs.saturatingAdd(5_000L)
        LyricEntry(
            text = line.text,
            startTimeMs = line.startTimeMs,
            endTimeMs = endTimeMs.coerceAtLeast(line.startTimeMs),
            words = words
        )
    }
}

private fun parseEnhancedLrcTimelineEntry(rawLine: String): EnhancedLrcTimelineEntry? {
    val line = rawLine.trim()
    val lineTimestamp = EnhancedLrcLineTimestampRegex.find(line)
        ?.takeIf { it.range.first == 0 }
        ?: return null
    val startTimeMs = parseLrcTimestampMs(lineTimestamp) ?: return null
    val content = line.substring(lineTimestamp.range.last + 1)
    val wordTimestamps = EnhancedLrcWordTimestampRegex.findAll(content).toList()
    if (wordTimestamps.isEmpty()) {
        return null
    }

    val words = buildList {
        val prefix = content.substring(0, wordTimestamps.first().range.first)
        if (prefix.any { !it.isWhitespace() }) {
            add(
                EnhancedLrcWord(
                    text = prefix,
                    startTimeMs = startTimeMs,
                    endTimeMs = parseLrcTimestampMs(wordTimestamps.first())
                )
            )
        }
        wordTimestamps.forEachIndexed { index, timestamp ->
            val textStart = timestamp.range.last + 1
            val textEnd = wordTimestamps.getOrNull(index + 1)?.range?.first ?: content.length
            val text = content.substring(textStart, textEnd)
            if (text.isNotEmpty()) {
                add(
                    EnhancedLrcWord(
                        text = text,
                        startTimeMs = parseLrcTimestampMs(timestamp) ?: return@forEachIndexed,
                        endTimeMs = wordTimestamps.getOrNull(index + 1)
                            ?.let(::parseLrcTimestampMs)
                    )
                )
            }
        }
    }
    if (words.isEmpty()) {
        return null
    }
    return EnhancedLrcTimelineEntry(
        startTimeMs = startTimeMs,
        text = words.joinToString(separator = "") { it.text },
        words = words
    )
}

private fun parseLrcTimestampMs(timestamp: MatchResult): Long? {
    val minutes = timestamp.groupValues[1].toLongOrNull() ?: return null
    val seconds = timestamp.groupValues[2].toLongOrNull() ?: return null
    val fraction = timestamp.groupValues[3]
    val milliseconds = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLongOrNull()?.times(100L)
        2 -> fraction.toLongOrNull()?.times(10L)
        else -> fraction.toLongOrNull()
    } ?: return null
    return minutes * 60_000L + seconds * 1_000L + milliseconds
}

/** 小数字符偏移的多行 reveal */
@Composable
internal fun Modifier.multilineGradientReveal(
    layout: TextLayoutResult?,
    revealOffsetChars: Float?,
    textLength: Int,
    fadeWidth: Dp,
    line: LyricEntry? = null,
    interpolatedPositionState: InterpolatedPlaybackPositionState? = null,
    lyricOffsetMs: Long = 0L
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        if (layout == null || textLength == 0) {
            drawContent()
            return@drawWithContent
        }
        val effectiveRevealOffsetChars = revealOffsetChars ?: run {
            val currentLine = line
            val positionState = interpolatedPositionState
            if (currentLine == null || positionState == null) {
                drawContent()
                return@drawWithContent
            }
            val drawTimeMs = (positionState.renderedPositionMs + lyricOffsetMs).coerceAtLeast(0L)
            currentLine.text.length * calculateLineProgress(currentLine, drawTimeMs).coerceIn(0f, 1f)
        }

        // 进度达100%, 直接显示全部高亮, 跳过裁剪
        if (effectiveRevealOffsetChars >= textLength) {
            drawContent()
            return@drawWithContent
        }

        val safeChars = effectiveRevealOffsetChars.coerceIn(0f, textLength.toFloat())
        val totalLines = layout.lineCount

        // 遍历所有行, 分三种情况处理, 已完成行, 当前行, 未开始行
        for (lineIndex in 0 until totalLines) {
            val lineStartIdx = layout.getLineStart(lineIndex) // 该行第一个字符的索引
            val lineEndIdx = layout.getLineEnd(lineIndex, true) // 该行最后一个字符的索引 (含换行符)

            // 进度超过该行最后一个字符, 直接绘制全高亮
            if (safeChars >= lineEndIdx) {
                clipRect(
                    left = layout.getLineLeft(lineIndex),
                    top = layout.getLineTop(lineIndex),
                    right = layout.getLineRight(lineIndex),
                    bottom = layout.getLineBottom(lineIndex)
                ) {
                    this@drawWithContent.drawContent()
                }
            }
            // 进度落在该行内, 执行渐变裁剪
            else if (safeChars >= lineStartIdx) {
                val currentIdxInLine = (safeChars - lineStartIdx).coerceAtLeast(0f)
                val currentCharIdx = lineStartIdx + floor(currentIdxInLine).toInt()
                val frac = (currentIdxInLine - floor(currentIdxInLine)).coerceIn(0f, 1f)

                // 计算当前字符和下一个字符的X坐标
                // 使用 getBoundingBox 获取更准确的字符边界, 避免字体渲染偏移
                val x0 = try {
                    layout.getBoundingBox(currentCharIdx).left
                } catch (e: Exception) {
                    layout.getHorizontalPosition(currentCharIdx, usePrimaryDirection = true)
                }
                val nextCharIdx = if (currentCharIdx >= lineEndIdx - 1) {
                    lineEndIdx // 该行最后一个字符，下一个字符指向行尾
                } else {
                    currentCharIdx + 1
                }
                val x1 = if (currentCharIdx >= lineEndIdx - 1) {
                    layout.getLineRight(lineIndex) // 该行最后一个字符，X1取行右边界
                } else {
                    try {
                        layout.getBoundingBox(nextCharIdx).left
                    } catch (e: Exception) {
                        layout.getHorizontalPosition(nextCharIdx, usePrimaryDirection = true)
                    }
                }

                // 确保X坐标在当前行范围内
                val lineLeft = layout.getLineLeft(lineIndex)
                val lineRight = layout.getLineRight(lineIndex)
                val x = (x0 + (x1 - x0) * frac).coerceIn(lineLeft, lineRight)

                // 计算渐变范围
                val fadePx = fadeWidth.toPx()
                if (fadePx <= 0.5f) {
                    clipRect(
                        left = lineLeft,
                        top = layout.getLineTop(lineIndex),
                        right = x,
                        bottom = layout.getLineBottom(lineIndex)
                    ) {
                        this@drawWithContent.drawContent()
                    }
                    continue
                }
                val start = (x - fadePx).coerceAtLeast(lineLeft)

                // 裁剪并绘制当前行的渐变高亮
                clipRect(
                    left = lineLeft,
                    top = layout.getLineTop(lineIndex),
                    right = lineRight,
                    bottom = layout.getLineBottom(lineIndex)
                ) {
                    this@drawWithContent.drawContent()

                    // 绘制渐变遮罩
                    val lineWidth = (lineRight - lineLeft).coerceAtLeast(1f)
                    val s1 = ((start - lineLeft) / lineWidth).coerceIn(0f, 1f)
                    val s2 = ((x - lineLeft) / lineWidth).coerceIn(0f, 1f)
                    val leftStop = minOf(s1, s2)
                    val rightStop = maxOf(s1, s2)
                    val brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.White,
                            leftStop to Color.White,
                            rightStop to Color.Transparent,
                            1f to Color.Transparent
                        ),
                        startX = lineLeft,
                        endX = lineRight
                    )
                    drawRect(
                        brush = brush,
                        topLeft = Offset(lineLeft, layout.getLineTop(lineIndex)),
                        size = androidx.compose.ui.geometry.Size(
                            lineRight - lineLeft,
                            layout.getLineBottom(lineIndex) - layout.getLineTop(lineIndex)
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
            // 进度未到该行, 不绘制高亮
            else {
                continue
            }
        }
    }


/**
 * 顶层当前行
 */
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
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val isLayoutReady by remember { derivedStateOf { layout != null } }
    val interpolatedPositionState = rememberInterpolatedPlaybackPositionState(
        currentTimeMs = currentTimeMs,
        isPlaying = isPlaying && interpolatePlaybackPosition,
        playbackSpeed = playbackSpeed
    )
    val targetLyricTimeMs = (currentTimeMs + lyricOffsetMs).coerceAtLeast(0L)
    val smoothedTargetLyricTimeMs = rememberSmoothedLyricTimeMs(targetLyricTimeMs)
    val smoothedLyricTimeMs = if (animateProgress) {
        smoothedTargetLyricTimeMs
    } else {
        targetLyricTimeMs
    }

    // 计算当前行进度
    val progressTarget = remember(line, smoothedLyricTimeMs) {
        calculateLineProgress(line, smoothedLyricTimeMs).coerceIn(0f, 1f)
    }

    val revealOffsetCharsAnimatable = remember(line.text) { Animatable(0f) }
    LaunchedEffect(isLayoutReady, progressTarget, animateProgress) {
        if (!isLayoutReady) return@LaunchedEffect
        if (!animateProgress) return@LaunchedEffect
        val targetChars = line.text.length * progressTarget
        revealOffsetCharsAnimatable.snapTo(targetChars)
    }
    val revealOffsetChars = if (animateProgress) {
        revealOffsetCharsAnimatable.value
    } else if (interpolatePlaybackPosition) {
        null
    } else {
        line.text.length * progressTarget
    }

    val textStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        letterSpacing = 0.sp,  // 禁用字符间距调整，确保测量和渲染一致
        lineHeight = resolveLyricLineHeight(fontSize, LYRIC_LINE_HEIGHT_MULTIPLIER)
    )

    val effectiveFadeWidth = if (line.words.isNullOrEmpty()) fadeWidth else 0.dp

    Box {
        // 底版文本
        Text(
            text = line.text,
            style = textStyle.copy(color = inactiveColor),
            maxLines = Int.MAX_VALUE,
            softWrap = true,
            onTextLayout = { newLayout ->
                // 仅在布局实际变化时更新, 减少重绘
                if (layout?.layoutInput != newLayout.layoutInput) {
                    layout = newLayout
                }
            }
        )

        // 高亮文本 - 仅在布局准备好后渲染, 避免旧数据导致的异常
        if (isLayoutReady) {
            Text(
                text = line.text,
                style = textStyle.copy(color = activeColor),
                maxLines = Int.MAX_VALUE,
                softWrap = true,
                modifier = Modifier.multilineGradientReveal(
                    layout = layout,
                    revealOffsetChars = revealOffsetChars,
                    textLength = line.text.length,
                    fadeWidth = effectiveFadeWidth,
                    line = line,
                    interpolatedPositionState = if (interpolatePlaybackPosition) {
                        interpolatedPositionState
                    } else {
                        null
                    },
                    lyricOffsetMs = lyricOffsetMs
                )
            )
        }
    }
}

internal data class HeadGlowTarget(
    val x: Float,
    val y: Float
)

internal fun resolveHeadGlowTarget(
    currentLine: Int,
    nextLine: Int,
    currentLineRight: Float,
    currentLineCenterY: Float,
    nextCharLeft: Float,
    nextLineCenterY: Float
): HeadGlowTarget {
    return if (nextLine != currentLine) {
        // 换行时先留在当前行末, 避免最后一个字提前跳到下一行
        HeadGlowTarget(
            x = currentLineRight,
            y = currentLineCenterY
        )
    } else {
        HeadGlowTarget(
            x = nextCharLeft,
            y = nextLineCenterY
        )
    }
}

/**
 * 解析 LRC (逐句)
 * 支持 [mm:ss.SSS] 或 [mm:ss]
 * 没有逐字信息时, 逐字揭示会按整句线性推进
 */
fun parseNeteaseLrc(lrc: String): List<LyricEntry> {
//    NPLogger.d("parseLyc-N", lrc)
    val normalizedLrc = normalizeLegacyLrcTimestamps(lrc)
    if (isEnhancedLrc(normalizedLrc)) {
        return parseEnhancedLrc(normalizedLrc)
    }
    val tag = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?]""")
    val timeline = mutableListOf<LrcTimelineEntry>()

    normalizedLrc.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("{") || line.startsWith("}")) return@forEach // 过滤 JSON 段

        val m = tag.find(line) ?: return@forEach
        val mm = m.groupValues[1].toInt()
        val ss = m.groupValues[2].toInt()
        val msStr = m.groupValues.getOrNull(3).orEmpty()
        val ms = when (msStr.length) {
            0 -> 0
            2 -> msStr.toInt() * 10
            else -> msStr.toInt()
        }
        val time = mm * 60_000L + ss * 1_000L + ms
        val text = line.substring(m.range.last + 1).trim()
        timeline.add(LrcTimelineEntry(startTimeMs = time, text = text))
    }

    timeline.sortBy { it.startTimeMs }
    val suffixContainsOnlyCredits = BooleanArray(timeline.size + 1)
    suffixContainsOnlyCredits[timeline.size] = true
    for (index in timeline.lastIndex downTo 0) {
        val text = timeline[index].text
        suffixContainsOnlyCredits[index] = text.isNotBlank() &&
            LrcCreditLineRegex.containsMatchIn(text) &&
            suffixContainsOnlyCredits[index + 1]
    }
    var seenNonBlankLine = false
    var terminalMarkerIndex: Int? = null
    for (index in timeline.indices) {
        val entry = timeline[index]
        if (entry.text.isBlank() && seenNonBlankLine && suffixContainsOnlyCredits[index + 1]) {
            terminalMarkerIndex = index
            break
        }
        if (entry.text.isNotBlank()) {
            seenNonBlankLine = true
        }
    }
    val effectiveTimeline = terminalMarkerIndex?.let { markerIndex ->
        timeline.take(markerIndex + 1)
    } ?: timeline
    val out = mutableListOf<LyricEntry>()
    var nextTimestampMs: Long? = null
    for (index in effectiveTimeline.lastIndex downTo 0) {
        val entry = effectiveTimeline[index]
        if (entry.text.isNotBlank()) {
            out.add(
                LyricEntry(
                    text = entry.text,
                    startTimeMs = entry.startTimeMs,
                    endTimeMs = nextTimestampMs ?: (entry.startTimeMs + 5_000L),
                    words = null
                )
            )
        }
        nextTimestampMs = entry.startTimeMs
    }
    out.reverse()
    return out
}

@Composable
fun DebugActiveLine(
    line: LyricEntry,
    currentTimeMs: Long,
    activeColor: Color,
    inactiveColor: Color,
    fontSize: TextUnit
) {
    val progressTarget = remember(line, currentTimeMs) {
        calculateLineProgress(line, currentTimeMs)
    }

    val revealCharIndex = (line.text.length * progressTarget).toInt()

    val highlightedText = line.text.substring(0, revealCharIndex.coerceIn(0, line.text.length))
    val remainingText = line.text.substring(revealCharIndex.coerceIn(0, line.text.length))

    val textStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Text(
                text = highlightedText,
                style = textStyle,
                color = activeColor,
            )
            Text(
                text = remainingText,
                style = textStyle,
                color = inactiveColor,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Time: $currentTimeMs ms | Progress: ${(progressTarget * 100).toInt()}% | Chars: $revealCharIndex/${line.text.length}",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun scaleForDistance(d: Int, spec: LyricVisualSpec): Float =
    when {
        d <= 0 -> spec.activeScale
        d == 1 -> spec.nearScale
        else -> (spec.farScale - (d - 2) * spec.farScaleFalloffPerStep)
            .coerceIn(spec.farScaleMin, spec.farScale)
    }

private fun alphaForDistance(d: Int, near: Float, far: Float): Float =
    when (d) {
        1 -> near
        2 -> far
        else -> (far - 0.08f * (d - 2)).coerceIn(0.16f, far)
    }

private fun blurForDistance(d: Int, maxBlur: Float): Float =
    when (d) {
        1 -> maxBlur * 1.0f
        2 -> maxBlur * 1.5f
        3 -> maxBlur * 2.0f
        4 -> maxBlur * 2.5f
        else -> maxBlur * 4.0f
    }
