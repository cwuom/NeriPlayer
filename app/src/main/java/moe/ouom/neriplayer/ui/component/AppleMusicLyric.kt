package moe.ouom.neriplayer.ui.component

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
 * File: moe.ouom.neriplayer.ui.component/AppleMusicLyric
 * Created: 2025/8/13
 */

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.util.NPLogger
import kotlin.math.abs
import kotlin.math.floor

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

    val glowColor: Color = Color.White,

    val glowRadiusExpanded: Dp = 48.dp,

    val glowAlpha: Float = 0.85f,

    // 动效参数
    val glowMoveSmoothingMs: Int = 110,   // 跟随位移的平滑
    val glowPulseStiffness: Float = Spring.StiffnessMedium,
    val glowPulseDamping: Float = 0.72f,

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
    val words: List<WordTiming>? = null
)

/**
 * 根据当前时间计算该行的高亮进度（0f..1f），基于字符数进行精确计算
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
    for (i in lines.indices) {
        if (currentTimeMs < lines[i].startTimeMs) return (i - 1).coerceAtLeast(0)
    }
    return lines.lastIndex
}

/** 上下渐隐 */
fun Modifier.verticalEdgeFade(fadeHeight: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (fadeHeight.toPx() / size.height).coerceIn(0f, 0.5f)
        val brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f       to Color.Transparent,
                edge       to Color.Black,
                (1f - edge) to Color.Black,
                1.0f       to Color.Transparent
            )
        )
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
    }

@SuppressLint("UnusedBoxWithConstraintsScope")
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
    lyricBlurEnabled: Boolean = true
) {
    val spec = visualSpec
    val listState = rememberLazyListState()
    var isUserScrolling by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    val currentIndex = remember(lyrics, currentTimeMs + lyricOffsetMs) {
        findCurrentLineIndex(lyrics, currentTimeMs + lyricOffsetMs)
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && !listState.isScrollInProgress) {
            isAutoScrolling = true
            listState.animateScrollToItem(currentIndex)
            isAutoScrolling = false
            isUserScrolling = false // Explicitly reset here
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !isAutoScrolling) {
            isUserScrolling = true
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
                .verticalEdgeFade(fadeHeight = 72.dp)
        ) {
            itemsIndexed(lyrics) { index, line ->
                Crossfade(
                    targetState = isUserScrolling,
                    animationSpec = tween(1000),
                    label = "lyric_mode_fade"
                ) { isScrolling ->
                    if (isScrolling) {
                        Text(
                            text = line.text,
                            style = TextStyle(
                                color = textColor,
                                fontSize = fontSize,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            ),
                            maxLines = Int.MAX_VALUE,
                            softWrap = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = centerPadding / 2, horizontal = 24.dp)
                                .widthIn(max = maxTextWidth)
                        )
                    } else {
                        val distance = abs(index - currentIndex)
                        val isActive = index == currentIndex

                        val targetScale = if (isActive) spec.activeScale else scaleForDistance(distance, spec)
                        val scale by animateFloatAsState(
                            targetValue = targetScale,
                            animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.85f),
                            label = "lyric_scale"
                        )

                        val tilt = if (isActive) 0f else if (index < currentIndex) spec.pageTiltDeg else -spec.pageTiltDeg
                        val rotationX by animateFloatAsState(
                            targetValue = tilt,
                            animationSpec = tween(durationMillis = spec.flipDurationMs),
                            label = "lyric_flip"
                        )

                        val blurRadiusDp = if (isActive || !lyricBlurEnabled) 0.dp else {
                            val t = ((distance - 1).coerceAtLeast(0) / 3f).coerceIn(0f, 1f)
                            lerp(spec.inactiveBlurNear, spec.inactiveBlurFar, t)
                        }
                        val blurRadiusPx = with(density) { blurRadiusDp.toPx() }

                        var blurEffect: androidx.compose.ui.graphics.RenderEffect? = null
                        var shadowEffect: Shadow? = null

                        if (blurRadiusPx > 0.1f) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                blurEffect = BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Decal)
                            } else {
                                shadowEffect = Shadow(
                                    color = textColor.copy(alpha = 0.28f),
                                    offset = Offset.Zero,
                                    blurRadius = blurRadiusPx
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = centerPadding / 2, horizontal = 24.dp)
                                .widthIn(max = maxTextWidth)
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0.5f, if (index < currentIndex) 1f else 0f)
                                    cameraDistance = 16f * density.density
                                    this.rotationX = rotationX
                                    scaleX = scale
                                    scaleY = scale
                                    renderEffect = blurEffect
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isActive) {
                                AppleMusicActiveLine(
                                    line = line,
                                    currentTimeMs = currentTimeMs + lyricOffsetMs,
                                    activeColor = textColor,
                                    inactiveColor = textColor.copy(alpha = 0.5f),
                                    fontSize = fontSize,
                                    fadeWidth = 12.dp,
                                    spec = spec
                                )
                            } else {
                                var colorStyle = textColor.copy(alpha = alphaForDistance(distance, inactiveAlphaNear, inactiveAlphaFar))
                                if (lyricBlurEnabled) {
                                    colorStyle = textColor.copy(alpha = alphaForDistance(distance, blurInactiveAlphaNear, blurInactiveAlphaFar))
                                }
                                Text(
                                    text = line.text,
                                    style = TextStyle(
                                        color = colorStyle,
                                        fontSize = fontSize,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        shadow = shadowEffect
                                    ),
                                    maxLines = Int.MAX_VALUE,
                                    softWrap = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * 解析网易云 yrc（逐字/逐词）
 * 示例：[12580,3470](12580,250,0)难(12830,300,0)以...
 * 会把每段文字的长度写入 WordTiming.charCount，用于多行逐字揭示
 */
fun parseNeteaseYrc(yrc: String): List<LyricEntry> {
//    NPLogger.d("parseYrc-N", yrc)
    val out = mutableListOf<LyricEntry>()
    val headerRegex = Regex("""\[(\d+),\s*(\d+)]""")
    val segRegex = Regex("""\((\d+),\s*(\d+),\s*[-\d]+\)([^()\n\r]+)""")

    yrc.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        if (!line.startsWith("[")) return@forEach

        val header = headerRegex.find(line) ?: return@forEach
        val start = header.groupValues[1].toLong()
        val dur = header.groupValues[2].toLong()
        val end = start + dur

        val segs = segRegex.findAll(line).toList()
        if (segs.isEmpty()) {
            val text = line.substringAfter("]").trim()
            out.add(LyricEntry(text = text, startTimeMs = start, endTimeMs = end, words = null))
        } else {
            val words = mutableListOf<WordTiming>()
            val sb = StringBuilder()
            for (m in segs) {
                val ws = m.groupValues[1].toLong()
                val wd = m.groupValues[2].toLong()
                val we = ws + wd
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

/** 小数字符偏移的多行 reveal */
@Composable
fun Modifier.multilineGradientReveal(
    layout: TextLayoutResult?,
    revealOffsetChars: Float,
    textLength: Int,
    fadeWidth: Dp
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        if (layout == null || textLength == 0 || revealOffsetChars <= 0f) {
            // 如果不需要揭示，直接返回
            return@drawWithContent
        }

        if (revealOffsetChars >= textLength) {
            drawContent()
            return@drawWithContent
        }

        val safeChars = revealOffsetChars.coerceIn(0f, textLength.toFloat())
        var idx = floor(safeChars).toInt().coerceIn(0, (textLength - 1).coerceAtLeast(0))
        var frac = (safeChars - idx).coerceIn(0f, 1f)

        var line = layout.getLineForOffset(idx)
        val lineStartAtIdx = layout.getLineStart(line)
        if (idx == lineStartAtIdx && frac == 0f && idx > 0) {
            line = (line - 1).coerceAtLeast(0)
            val prevStart = layout.getLineStart(line)
            val prevEnd = layout.getLineEnd(line, true)
            if (prevEnd > prevStart) {
                idx = (prevEnd - 1).coerceAtLeast(prevStart)
                frac = 1f
            }
        }

        for (i in 0 until line) {
            val l = layout.getLineLeft(i)
            val r = layout.getLineRight(i)
            val t = layout.getLineTop(i)
            val b = layout.getLineBottom(i)
            clipRect(left = l, top = t, right = r, bottom = b) {
                this@drawWithContent.drawContent()
            }
        }

        val l = layout.getLineLeft(line)
        val r = layout.getLineRight(line)
        val t = layout.getLineTop(line)
        val b = layout.getLineBottom(line)
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line, true)
        if (lineEnd <= lineStart) return@drawWithContent

        val clampedIdx = idx.coerceIn(lineStart, lineEnd - 1)
        val nextIdx = (clampedIdx + 1).coerceAtMost(lineEnd - 1)

        val atLineLast = clampedIdx >= lineEnd - 1
        val x0 = layout.getHorizontalPosition(clampedIdx, usePrimaryDirection = true)
        val x1 = if (atLineLast) r else layout.getHorizontalPosition(nextIdx, usePrimaryDirection = true)
        val x = (x0 + (x1 - x0) * frac).coerceIn(l, r)

        val fadePx = fadeWidth.toPx()
        val start = (x - fadePx).coerceAtLeast(l)

        clipRect(left = l, top = t, right = r, bottom = b) {
            this@drawWithContent.drawContent()

            val brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    ((start - l) / (r - l)) to Color.White,
                    ((x - l) / (r - l)) to Color.Transparent,
                    1f to Color.Transparent
                ),
                startX = l,
                endX = r
            )
            drawRect(
                brush = brush,
                topLeft = Offset(l, t),
                size = androidx.compose.ui.geometry.Size(r - l, b - t),
                blendMode = BlendMode.DstIn
            )
        }
    }
/**
 * 顶层当前行
 */
@Composable
fun AppleMusicActiveLine(
    line: LyricEntry,
    currentTimeMs: Long,
    activeColor: Color,
    inactiveColor: Color,
    fontSize: TextUnit,
    fadeWidth: Dp = 12.dp,
    spec: LyricVisualSpec = LyricVisualSpec()
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val progressTarget = remember(line, currentTimeMs) {
        calculateLineProgress(line, currentTimeMs).coerceIn(0f, 1f)
    }

    val revealOffsetCharsAnimatable = remember(line.text) { Animatable(0f) }

    LaunchedEffect(progressTarget) {
        val targetChars = line.text.length * progressTarget

        if (revealOffsetCharsAnimatable.value == 0f && progressTarget > 0) {
            revealOffsetCharsAnimatable.animateTo(
                targetValue = targetChars,
                animationSpec = tween(durationMillis = 1000, easing = LinearEasing)
            )
        } else {
            revealOffsetCharsAnimatable.snapTo(targetChars)
        }
    }

    val revealOffsetChars = revealOffsetCharsAnimatable.value

    val mergedWords = remember(line.words) {
        mergeWordTimings(line.words)
    }



    val isWordCurrentlyActive = remember(mergedWords, currentTimeMs) {
        findActiveWord(mergedWords, currentTimeMs) != null
    }
    val headGlowRadius by animateDpAsState(
        targetValue = if (isWordCurrentlyActive) spec.glowRadiusExpanded else 0.dp,
        animationSpec = spring(stiffness = spec.glowPulseStiffness, dampingRatio = spec.glowPulseDamping),
        label = "head_glow_radius"
    )
    val headGlowAlpha by animateFloatAsState(
        targetValue = if (isWordCurrentlyActive) spec.glowAlpha else 0f,
        animationSpec = tween(durationMillis = spec.glowMoveSmoothingMs),
        label = "head_glow_alpha"
    )
    val headGlowRadiusPx = with(LocalDensity.current) { headGlowRadius.toPx() }

    val textStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )

    Box(
        modifier = Modifier.drawBehind {
            if (layout != null && headGlowRadiusPx > 0f) {
                drawRadialHeadGlow(
                    layout = layout!!,
                    charOffset = revealOffsetChars, // 使用逐字动画的精确偏移
                    radiusPx = headGlowRadiusPx,
                    color = spec.glowColor,
                    alpha = headGlowAlpha
                )
            }
        }
    ) {
        Text(
            text = line.text,
            style = textStyle.copy(
                color = inactiveColor,
                fontWeight = FontWeight.Medium
            ),
            maxLines = Int.MAX_VALUE,
            softWrap = true,
            onTextLayout = { layout = it }
        )

        Text(
            text = line.text,
            style = textStyle.copy(color = activeColor),
            maxLines = Int.MAX_VALUE,
            softWrap = true,
            modifier = Modifier.multilineGradientReveal(
                layout = layout,
                revealOffsetChars = revealOffsetChars,
                textLength = line.text.length,
                fadeWidth = fadeWidth
            )
        )
    }
}


data class ActiveWord(val range: IntRange, val sustainWeight: Float, val tInWord: Float)

/**
 * 解析 LRC（逐句）。支持 [mm:ss.SSS] 或 [mm:ss]。
 * 没有逐字信息时，逐字揭示会按整句线性推进
 */
fun parseNeteaseLrc(lrc: String): List<LyricEntry> {
//    NPLogger.d("parseLyc-N", lrc)
    val tag = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?]""")
    val timeline = mutableListOf<Pair<Long, String>>()

    lrc.lineSequence().forEach { raw ->
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
        timeline.add(time to text)
    }

    timeline.sortBy { it.first }
    val out = mutableListOf<LyricEntry>()
    for (i in timeline.indices) {
        val (start, text) = timeline[i]
        val end = if (i < timeline.lastIndex) timeline[i + 1].first else start + 5_000L
        out.add(LyricEntry(text = text, startTimeMs = start, endTimeMs = end, words = null))
    }
    return out
}

/** 径向头部光晕 */
private fun DrawScope.drawRadialHeadGlow(
    layout: TextLayoutResult,
    charOffset: Float,
    radiusPx: Float,
    color: Color,
    alpha: Float
) {
    if (radiusPx <= 0f || alpha <= 0.01f) return

    val textLength = layout.layoutInput.text.length

    val safeOffset = charOffset.coerceIn(0f, textLength.toFloat())

    val currentIndex = floor(safeOffset).toInt().coerceIn(0, (textLength - 1).coerceAtLeast(0))
    val nextIndex = (currentIndex + 1).coerceAtMost(textLength - 1)
    val fraction = (safeOffset - currentIndex).coerceIn(0f, 1f)

    val currentLine = layout.getLineForOffset(currentIndex)
    val currentLineTop = layout.getLineTop(currentLine)
    val currentLineBottom = layout.getLineBottom(currentLine)
    val y0 = (currentLineTop + currentLineBottom) * 0.5f
    val x0 = layout.getHorizontalPosition(currentIndex, true)

    val nextLine = layout.getLineForOffset(nextIndex)
    val nextLineTop = layout.getLineTop(nextLine)
    val nextLineBottom = layout.getLineBottom(nextLine)
    val y1 = (nextLineTop + nextLineBottom) * 0.5f
    val x1 = if (nextLine == currentLine && nextIndex >= layout.getLineEnd(currentLine, true) - 1) {
        layout.getLineRight(currentLine)
    } else {
        layout.getHorizontalPosition(nextIndex, true)
    }

    val cx = x0 + (x1 - x0) * fraction
    val cy = y0 + (y1 - y0) * fraction

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = Offset(cx, cy),
            radius = radiusPx
        ),
        radius = radiusPx,
        center = Offset(cx, cy)
    )
}

/**
 * 将单词时间戳合并的逻辑提取出来
 */
private fun mergeWordTimings(words: List<WordTiming>?, mergeGapMs: Long = 90L): List<Triple<IntRange, Long, Long>> {
    if (words.isNullOrEmpty()) return emptyList()

    val merged = mutableListOf<Triple<IntRange, Long, Long>>()
    var accStart = words.first().startTimeMs
    var accEnd = words.first().endTimeMs
    var accRangeStart = 0
    var accRangeEnd = words.first().charCount.coerceAtLeast(1) - 1

    fun flush() { merged += Triple(accRangeStart..accRangeEnd, accStart, accEnd) }

    val offset = 0
    for (i in 1 until words.size) {
        val wPrevEnd = accEnd
        val w = words[i]
        val chars = w.charCount.coerceAtLeast(1)
        val rStart = offset + (accRangeEnd - accRangeStart + 1)
        val rEnd = rStart + chars - 1

        if (w.startTimeMs - wPrevEnd <= mergeGapMs) {
            accEnd = maxOf(accEnd, w.endTimeMs)
            accRangeEnd = rEnd
        } else {
            flush()
            accStart = w.startTimeMs
            accEnd = w.endTimeMs
            accRangeStart = rStart
            accRangeEnd = rEnd
        }
    }
    flush()
    return merged
}

/**
 * 从已合并的列表中查找当前活动的单词
 */
private fun findActiveWord(
    mergedWords: List<Triple<IntRange, Long, Long>>,
    t: Long,
    marginMs: Long = 80L
): ActiveWord? {
    for ((range, start, end) in mergedWords) {
        val s = start - marginMs
        val e = end + marginMs
        if (t in s..e) {
            val dur = (end - start).coerceAtLeast(1)
            val tIn = ((t - start).toFloat() / dur).coerceIn(0f, 1f)
            val sustain = ((dur - 140f) / (900f - 140f)).coerceIn(0f, 1f)
            return ActiveWord(range, sustain, tIn)
        }
    }
    return null
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