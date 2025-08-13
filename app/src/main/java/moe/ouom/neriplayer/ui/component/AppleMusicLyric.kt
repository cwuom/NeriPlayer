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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import androidx.compose.ui.graphics.TileMode

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

/** 单词/字的时间戳（增加 charCount 以映射到文本字符数） */
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

/** 根据当前时间计算该行的高亮进度（0f..1f） */
fun calculateLineProgress(line: LyricEntry, currentTimeMs: Long): Float {
    val start = line.startTimeMs
    val end = line.endTimeMs
    if (currentTimeMs <= start) return 0f
    if (currentTimeMs >= end) return 1f

    val words = line.words
    if (words.isNullOrEmpty()) {
        return (currentTimeMs - start).toFloat() / (end - start).toFloat()
    }

    var completed = 0
    var partial = 0f
    for ((i, w) in words.withIndex()) {
        val ws = w.startTimeMs
        val we = w.endTimeMs
        if (currentTimeMs < ws) break
        if (currentTimeMs in ws..we) {
            val dur = (we - ws).coerceAtLeast(1)
            partial = (currentTimeMs - ws).toFloat() / dur.toFloat()
            completed = i
            break
        } else {
            completed = i + 1
        }
    }
    val total = words.size.coerceAtLeast(1)
    val p = (completed + partial) / total.toFloat()
    return p.coerceIn(0f, 1f)
}

/** 把当前时间映射为“应该高亮的字符个数”（多行换行友好） */
fun calculateHighlightOffset(line: LyricEntry, currentTimeMs: Long): Int {
    if (currentTimeMs <= line.startTimeMs) return 0
    if (currentTimeMs >= line.endTimeMs) return line.text.length

    val words = line.words
    if (words.isNullOrEmpty()) {
        val p = (currentTimeMs - line.startTimeMs).toFloat() /
                (line.endTimeMs - line.startTimeMs).coerceAtLeast(1).toFloat()
        return (line.text.length * p).toInt().coerceIn(0, line.text.length)
    }

    var offset = 0
    for (w in words) {
        val ws = w.startTimeMs
        val we = w.endTimeMs
        val chars = w.charCount.coerceAtLeast(1)
        when {
            currentTimeMs < ws -> return offset
            currentTimeMs in ws..we -> {
                val dur = (we - ws).coerceAtLeast(1)
                val p = (currentTimeMs - ws).toFloat() / dur.toFloat()
                offset += floor(chars * p).toInt()
                return min(offset, line.text.length)
            }
            else -> offset += chars
        }
    }
    return min(offset, line.text.length)
}

/** 找到当前时间所在的行索引 */
fun findCurrentLineIndex(lines: List<LyricEntry>, currentTimeMs: Long): Int {
    if (lines.isEmpty()) return -1
    for (i in lines.indices) {
        if (currentTimeMs < lines[i].startTimeMs) return (i - 1).coerceAtLeast(0)
    }
    return lines.lastIndex
}

@Composable
fun HorizontalReveal(
    progress: Float,
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 0.5f,
    rtl: Boolean = false,
    content: @Composable () -> Unit
) {
    val animProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(90, easing = LinearEasing),
        label = "lyric_reveal_progress"
    )

    Box(modifier = modifier) {
        Box(Modifier.alpha(backgroundAlpha)) { content() }

        Box(
            Modifier
                .graphicsLayer {
                    clip = true
                    shape = RectangleShape
                }
                .drawWithContent {
                    val clipWidth = size.width * animProgress
                    val left = if (!rtl) 0f else size.width - clipWidth
                    val right = if (!rtl) clipWidth else size.width
                    clipRect(left = left, right = right, top = 0f, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
        ) { content() }
    }
}

/** 上下渐隐（用 DstIn，不涉及 blur） */
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
    inactiveAlphaNear: Float = 0.72f,
    inactiveAlphaFar: Float = 0.40f,
    fontSize: TextUnit = 18.sp,
    centerPadding: Dp = 16.dp,
    visualSpec: LyricVisualSpec = LyricVisualSpec()
) {
    val spec = visualSpec
    val listState = rememberLazyListState()

    val currentIndex = remember(lyrics, currentTimeMs) {
        findCurrentLineIndex(lyrics, currentTimeMs)
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val centerPad = maxHeight / 2.5f
        val maxTextWidth = maxWidth - 48.dp
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

                // ==================== ⬇️ 主要修改区域开始 ⬇️ ====================

                // 1. 计算模糊半径 (逻辑与你原来的 softRadiusDp 相同)
                val blurRadiusDp = if (isActive) 0.dp else {
                    val t = ((distance - 1).coerceAtLeast(0) / 3f).coerceIn(0f, 1f)
                    lerp(spec.inactiveBlurNear, spec.inactiveBlurFar, t)
                }
                val blurRadiusPx = with(density) { blurRadiusDp.toPx() }

                // 2. 根据 API 版本决定使用哪种效果
                var blurEffect: androidx.compose.ui.graphics.RenderEffect? = null
                var shadowEffect: Shadow? = null

                if (blurRadiusPx > 0.1f) { // 加一个阈值避免不必要的对象创建
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // 在 Android 12 (API 31) 及以上版本使用真正的 BlurEffect
                        blurEffect = BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Decal)
                    } else {
                        // 在旧版本上回退到使用 Shadow 模拟模糊效果
                        shadowEffect = Shadow(
                            color = textColor.copy(alpha = 0.28f),
                            offset = Offset.Zero,
                            blurRadius = blurRadiusPx
                        )
                    }
                }

                // ==================== ⬆️ 主要修改区域结束 ⬆️ ====================

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
                            // 3. 应用 RenderEffect (只在 API 31+ 生效)
                            renderEffect = blurEffect
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isActive) {
                        AppleMusicActiveLine(
                            line = line,
                            currentTimeMs = currentTimeMs,
                            activeColor = textColor,
                            inactiveColor = textColor.copy(alpha = 0.5f),
                            fontSize = fontSize,
                            fadeWidth = 12.dp,
                            spec = spec
                        )
                    } else {
                        Text(
                            text = line.text,
                            style = TextStyle(
                                color = textColor.copy(alpha = alphaForDistance(distance, inactiveAlphaNear, inactiveAlphaFar)),
                                fontSize = fontSize,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                // 4. 应用 Shadow (只在 API 31 以下的设备上生效)
                                shadow = shadowEffect
                            ),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * 解析网易云 yrc（逐字/逐词）。
 * 示例：[12580,3470](12580,250,0)难(12830,300,0)以...
 * 会把每段文字的长度写入 WordTiming.charCount，用于多行逐字揭示。
 */
fun parseNeteaseYrc(yrc: String): List<LyricEntry> {
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

/** 小数字符偏移的多行 reveal（更顺滑） */
@Composable
fun Modifier.multilineGradientReveal(
    layout: TextLayoutResult?,
    revealOffsetChars: Float, // 小数级字符偏移
    textLength: Int,
    fadeWidth: Dp
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        if (layout == null || textLength == 0 || revealOffsetChars <= 0f) {
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
            val t = layout.getLineTop(i).toFloat()
            val b = layout.getLineBottom(i).toFloat()
            clipRect(left = l, top = t, right = r, bottom = b) {
                this@drawWithContent.drawContent()
            }
        }

        val l = layout.getLineLeft(line)
        val r = layout.getLineRight(line)
        val t = layout.getLineTop(line).toFloat()
        val b = layout.getLineBottom(line).toFloat()
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
                topLeft = androidx.compose.ui.geometry.Offset(l, t),
                size = androidx.compose.ui.geometry.Size(r - l, b - t),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * 顶层当前行（修改后：使用移动的径向辉光，替换旧的整体模糊辉光）
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
    val revealOffsetChars by animateFloatAsState(
        targetValue = line.text.length * progressTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.92f),
        label = "reveal_chars_anim"
    )

    // 1. (修改) 为“头部光晕”设置独立的半径和透明度动画
    //    - 当有单词激活时，使用 spec 中定义的 expanded 半径和 alpha。
    //    - 当没有单词激活时（如单词间隙或句子结束），半径和 alpha 都变为 0，使其消失。
    val isWordCurrentlyActive = isWordActive(line, currentTimeMs)
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

    // 2. (修改) 在 Box 上使用 drawBehind 添加移动的辉光
    Box(
        modifier = Modifier.drawBehind {
            // 确保 layout 已计算完成且辉光半径大于0
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
        // 背景层：整句半透明文字 (保持不变)
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

        // 3. (移除) 旧的、使用 BlurEffect 的辉光层已被完全删除
        // if (glowBlurRadiusPx > 0.1f) { ... }

        // 前景层：清晰的高亮文字 (保持不变)
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


// 辅助函数，判断当前是否有词在激活状态，用于触发辉光半径动画
private fun isWordActive(line: LyricEntry, currentTimeMs: Long): Boolean {
    return currentWordAndSustainStable(line, currentTimeMs) != null
}
fun currentWordAndSustainStable(
    line: LyricEntry,
    t: Long,
    marginMs: Long = 80L,
    mergeGapMs: Long = 90L
): ActiveWord? {
    val words = line.words ?: return null
    val merged: MutableList<Triple<IntRange, Long, Long>> = mutableListOf()
    var offset = 0
    var accStart = words.first().startTimeMs
    var accEnd = words.first().endTimeMs
    var accRangeStart = 0
    var accRangeEnd = words.first().charCount.coerceAtLeast(1) - 1

    fun flush() { merged += Triple(accRangeStart..accRangeEnd, accStart, accEnd) }

    for (i in 1 until words.size) {
        val wPrevEnd = accEnd
        val w = words[i]
        val ws = w.startTimeMs
        val we = w.endTimeMs
        val chars = w.charCount.coerceAtLeast(1)
        val rStart = offset + (accRangeEnd - accRangeStart + 1)
        val rEnd = rStart + chars - 1

        if (ws - wPrevEnd <= mergeGapMs) {
            accEnd = maxOf(accEnd, we)
            accRangeEnd = rEnd
        } else {
            flush()
            accStart = ws
            accEnd = we
            accRangeStart = rStart
            accRangeEnd = rEnd
        }
    }
    flush()

    for ((range, start, end) in merged) {
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

/** （保留备用）按段裁剪的工具；当前发光已不依赖它 */
fun Modifier.multilineSegmentClip(
    layout: TextLayoutResult?,
    range: IntRange,
    feather: Dp,
    overscan: Dp
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        if (layout == null) return@drawWithContent

        val start = range.first.coerceAtLeast(0)
        val endIncl = range.last.coerceAtLeast(start)
        val f = feather.toPx()
        val o = maxOf(overscan.toPx(), f * 2f)

        fun maskOneLine(line: Int, s: Int, e: Int) {
            if (e < s) return
            val l = layout.getLineLeft(line)
            val r = layout.getLineRight(line)
            val t = layout.getLineTop(line).toFloat()
            val b = layout.getLineBottom(line).toFloat()

            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line, true)

            val x0 = layout.getHorizontalPosition(s, true).coerceIn(l, r)
            val isEnd = e >= (lineEnd - 1).coerceAtLeast(lineStart)
            val x1Raw = if (isEnd) r + f else layout.getHorizontalPosition(e, true)
            val x1 = maxOf(x1Raw, x0 + 0.5f).coerceAtLeast(x0)

            val fullLayer = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
            drawContext.canvas.saveLayer(fullLayer, Paint())

            this@drawWithContent.drawContent()

            val startX = 0f - o
            val endX = size.width + o
            fun nx(x: Float) = ((x - startX) / (endX - startX)).coerceIn(0f, 1f)

            val hStops = arrayOf(
                0f to Color.Transparent,
                nx(x0 - f) to Color.Transparent,
                nx(x0 + f) to Color.White,
                nx(x1 - f) to Color.White,
                nx(x1 + f) to Color.Transparent,
                1f to Color.Transparent
            )

            drawRect(
                brush = Brush.horizontalGradient(colorStops = hStops, startX = startX, endX = endX),
                topLeft = androidx.compose.ui.geometry.Offset(l - o, t - o),
                size = androidx.compose.ui.geometry.Size((r - l) + 2 * o, (b - t) + 2 * o),
                blendMode = BlendMode.DstIn
            )

            val startY = t - o
            val endY = b + o
            fun ny(y: Float) = ((y - startY) / (endY - startY)).coerceIn(0f, 1f)
            val vStops = arrayOf(
                0f to Color.Transparent,
                ny(t - o) to Color.Transparent,
                ny(t + o) to Color.White,
                ny(b - o) to Color.White,
                ny(b + o) to Color.Transparent,
                1f to Color.Transparent
            )

            drawRect(
                brush = Brush.verticalGradient(colorStops = vStops, startY = startY, endY = endY),
                topLeft = androidx.compose.ui.geometry.Offset(l - o, t - o),
                size = androidx.compose.ui.geometry.Size((r - l) + 2 * o, (b - t) + 2 * o),
                blendMode = BlendMode.DstIn
            )

            drawContext.canvas.restore()
        }

        val startLine = layout.getLineForOffset(start)
        val endLine = layout.getLineForOffset(endIncl)
        if (startLine == endLine) {
            maskOneLine(startLine, start, endIncl)
        } else {
            val startEnd = (layout.getLineEnd(startLine, true) - 1).coerceAtLeast(start)
            maskOneLine(startLine, start, startEnd)
            for (ln in (startLine + 1) until endLine) {
                val ls = layout.getLineStart(ln)
                val le = layout.getLineEnd(ln, true) - 1
                maskOneLine(ln, ls, le)
            }
            val endStart = layout.getLineStart(endLine)
            maskOneLine(endLine, endStart, endIncl)
        }
    }

data class ActiveWord(val range: IntRange, val sustainWeight: Float, val tInWord: Float)

private fun sustainEnvelope(t: Float): Float {
    val s = kotlin.math.sin(t * Math.PI).toFloat()
    return s * s
}

/**
 * 解析 LRC（逐句）。支持 [mm:ss.SSS] 或 [mm:ss]。
 * 没有逐字信息时，逐字揭示会按整句线性推进。
 */
fun parseNeteaseLrc(lrc: String): List<LyricEntry> {
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
/** 径向头部光晕 (已修复跨行抖动问题) */
private fun DrawScope.drawRadialHeadGlow(
    layout: TextLayoutResult,
    charOffset: Float,
    radiusPx: Float,
    color: Color,
    alpha: Float
) {
    // 如果半径或透明度过小，则不绘制
    if (radiusPx <= 0f || alpha <= 0.01f) return

    val textLength = layout.layoutInput.text.length
    // 确保偏移量在有效范围内
    val safeOffset = charOffset.coerceIn(0f, textLength.toFloat())

    // 1. 计算当前点和目标点的位置信息
    val currentIndex = floor(safeOffset).toInt().coerceIn(0, (textLength - 1).coerceAtLeast(0))
    val nextIndex = (currentIndex + 1).coerceAtMost(textLength - 1)
    val fraction = (safeOffset - currentIndex).coerceIn(0f, 1f)

    // 获取当前字符所在行和Y坐标中心
    val currentLine = layout.getLineForOffset(currentIndex)
    val currentLineTop = layout.getLineTop(currentLine)
    val currentLineBottom = layout.getLineBottom(currentLine)
    val y0 = (currentLineTop + currentLineBottom) * 0.5f
    // 获取当前字符的X坐标
    val x0 = layout.getHorizontalPosition(currentIndex, true)

    // 获取下一个字符所在行和Y坐标中心
    val nextLine = layout.getLineForOffset(nextIndex)
    val nextLineTop = layout.getLineTop(nextLine)
    val nextLineBottom = layout.getLineBottom(nextLine)
    val y1 = (nextLineTop + nextLineBottom) * 0.5f
    // 获取下一个字符的X坐标
    // 特殊处理：如果下一个字符还在当前行，并且是最后一个字符，我们把目标X设为行尾，而不是下一个字符的开头，让过渡更自然
    val x1 = if (nextLine == currentLine && nextIndex >= layout.getLineEnd(currentLine, true) - 1) {
        layout.getLineRight(currentLine)
    } else {
        layout.getHorizontalPosition(nextIndex, true)
    }

    // 2. 对 X 和 Y 坐标进行线性插值，实现平滑过渡
    val cx = x0 + (x1 - x0) * fraction
    val cy = y0 + (y1 - y0) * fraction

    // 3. 绘制辉光
    // 使用插值后的 cx, cy 作为中心点
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