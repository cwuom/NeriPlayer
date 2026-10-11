package moe.ouom.neriplayer.lyrics.parser

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
 */

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

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
    val text: String,
    val words: List<EnhancedLrcWord>? = null,
    val explicitEndTimeMs: Long? = null,
    val sourceLineIndex: Int = 0,
    val timestampIndex: Int = 0
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

fun isTtmlLyrics(content: String): Boolean = TtmlTagRegex.containsMatchIn(content)

fun parseNeteaseLyricsAuto(content: String): List<LyricEntry> {
    return when {
        isTtmlLyrics(content) -> parseTtmlLyrics(content)
        isNeteaseYrc(content) -> runCatching { parseNeteaseYrc(content) }.getOrDefault(emptyList())
        isEnhancedLrc(content) -> parseEnhancedLrc(content).withoutLyricCredits()
        else -> parseNeteaseLrc(content)
    }
}

fun parseTtmlLyrics(content: String): List<LyricEntry> {
    return runCatching {
        AutoParser().parse(content).lines
            .mapNotNull(::toLyricEntry)
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startTimeMs }
            .withoutLyricCredits()
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
    return out.sortedBy { it.startTimeMs }.withoutLyricCredits()
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

private fun parseSquareBracketLrcTimelineEntries(
    rawLine: String,
    sourceLineIndex: Int
): List<LrcTimelineEntry> {
    val line = rawLine.trim()
    val timestamps = EnhancedLrcLineTimestampRegex.findAll(line).toList()
    if (timestamps.isEmpty() || timestamps.first().range.first != 0) {
        return emptyList()
    }

    var leadingTimestampCount = 1
    var nextExpectedStart = timestamps.first().range.last + 1
    while (
        leadingTimestampCount < timestamps.size &&
        timestamps[leadingTimestampCount].range.first == nextExpectedStart
    ) {
        nextExpectedStart = timestamps[leadingTimestampCount].range.last + 1
        leadingTimestampCount++
    }

    val primaryTimestampIndex = leadingTimestampCount - 1
    val primaryTimestamp = timestamps[primaryTimestampIndex]
    val primaryStartTimeMs = parseLrcTimestampMs(primaryTimestamp) ?: return emptyList()
    val inlineTimestamps = timestamps.drop(leadingTimestampCount)
    val fragments = buildList {
        val firstTextEnd = inlineTimestamps.firstOrNull()?.range?.first ?: line.length
        add(
            EnhancedLrcWord(
                text = line.substring(primaryTimestamp.range.last + 1, firstTextEnd),
                startTimeMs = primaryStartTimeMs,
                endTimeMs = inlineTimestamps.firstOrNull()?.let(::parseLrcTimestampMs)
            )
        )
        inlineTimestamps.forEachIndexed { index, timestamp ->
            val textStart = timestamp.range.last + 1
            val textEnd = inlineTimestamps.getOrNull(index + 1)?.range?.first ?: line.length
            add(
                EnhancedLrcWord(
                    text = line.substring(textStart, textEnd),
                    startTimeMs = parseLrcTimestampMs(timestamp) ?: return@forEachIndexed,
                    endTimeMs = inlineTimestamps.getOrNull(index + 1)
                        ?.let(::parseLrcTimestampMs)
                )
            )
        }
    }
    val visibleFragments = fragments.filterIndexed { index, fragment ->
        fragment.text.isNotEmpty() &&
            (index != 0 || fragment.text.any { !it.isWhitespace() })
    }

    if (visibleFragments.size >= 2) {
        return listOf(
            LrcTimelineEntry(
                startTimeMs = primaryStartTimeMs,
                text = visibleFragments.joinToString(separator = "") { it.text },
                words = visibleFragments,
                sourceLineIndex = sourceLineIndex,
                timestampIndex = primaryTimestampIndex
            )
        )
    }

    val text = visibleFragments.singleOrNull()?.text?.trim().orEmpty()
    val explicitEndTimeMs = visibleFragments.singleOrNull()?.endTimeMs
    return timestamps.take(leadingTimestampCount).mapIndexedNotNull { timestampIndex, timestamp ->
        val startTimeMs = parseLrcTimestampMs(timestamp) ?: return@mapIndexedNotNull null
        LrcTimelineEntry(
            startTimeMs = startTimeMs,
            text = text,
            explicitEndTimeMs = explicitEndTimeMs.takeIf { leadingTimestampCount == 1 },
            sourceLineIndex = sourceLineIndex,
            timestampIndex = timestampIndex
        )
    }
}

private fun foldAdjacentSquareBracketTranslations(
    entries: List<LyricEntry>
): List<LyricEntry> {
    val foldedEntries = mutableListOf<LyricEntry>()
    var index = 0
    while (index < entries.size) {
        val entry = entries[index]
        val followingEntry = entries.getOrNull(index + 1)
        val followingText = followingEntry?.text.orEmpty()
        val isAdjacentTranslation =
            !entry.words.isNullOrEmpty() &&
                followingEntry?.words.isNullOrEmpty() &&
                followingEntry?.startTimeMs == entry.startTimeMs &&
                followingText.isNotBlank() &&
                !LrcCreditLineRegex.containsMatchIn(followingText) &&
                !isLyricCreditMetadataLine(followingText)
        if (isAdjacentTranslation) {
            foldedEntries += entry.copy(translation = followingText)
            index += 2
        } else {
            foldedEntries += entry
            index++
        }
    }
    return foldedEntries
}

fun parseNeteaseLrc(lrc: String): List<LyricEntry> {
//    NPLogger.d("parseLyc-N", lrc)
    val normalizedLrc = normalizeLegacyLrcTimestamps(lrc)
    if (isEnhancedLrc(normalizedLrc)) {
        return parseEnhancedLrc(normalizedLrc).withoutLyricCredits()
    }
    val timeline = mutableListOf<LrcTimelineEntry>()

    normalizedLrc.lineSequence().forEachIndexed { sourceLineIndex, raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEachIndexed
        if (line.startsWith("{") || line.startsWith("}")) return@forEachIndexed // 过滤 JSON 段

        timeline += parseSquareBracketLrcTimelineEntries(
            rawLine = line,
            sourceLineIndex = sourceLineIndex
        )
    }

    timeline.sortWith(
        compareBy<LrcTimelineEntry> { it.startTimeMs }
            .thenBy { it.sourceLineIndex }
            .thenBy { it.timestampIndex }
    )
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
            val nextDistinctTimestampMs = effectiveTimeline
                .asSequence()
                .drop(index + 1)
                .firstOrNull { it.startTimeMs > entry.startTimeMs }
                ?.startTimeMs
            val words = entry.words?.let { sourceWords ->
                sourceWords.mapIndexed { wordIndex, word ->
                    val fallbackEndTimeMs = sourceWords.getOrNull(wordIndex + 1)?.startTimeMs
                        ?: nextDistinctTimestampMs
                        ?: entry.startTimeMs.saturatingAdd(5_000L)
                    WordTiming(
                        startTimeMs = word.startTimeMs,
                        endTimeMs = (word.endTimeMs ?: fallbackEndTimeMs)
                            .coerceAtLeast(word.startTimeMs),
                        charCount = word.text.length
                    )
                }
            }
            val endTimeMs = words?.maxOfOrNull { it.endTimeMs }
                ?: entry.explicitEndTimeMs
                ?: nextTimestampMs
                ?: entry.startTimeMs.saturatingAdd(5_000L)
            out.add(
                LyricEntry(
                    text = entry.text,
                    startTimeMs = entry.startTimeMs,
                    endTimeMs = endTimeMs.coerceAtLeast(entry.startTimeMs),
                    words = words
                )
            )
        }
        nextTimestampMs = entry.startTimeMs
    }
    out.reverse()
    return foldAdjacentSquareBracketTranslations(out).withoutLyricCredits()
}
