package moe.ouom.neriplayer.core.player.service.lyrics.coloros

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.parser.isLyricCreditOrNoticeLine
import moe.ouom.neriplayer.lyrics.parser.isUntranslatedPlaceholderText
import moe.ouom.neriplayer.lyrics.parser.matchTranslationsToLineIndices
import org.json.JSONObject
import java.util.Locale

private const val MAX_PAYLOAD_CHARACTERS = 128 * 1024

internal fun colorOsLyricPayload(
    timeline: ColorOsLyricTimeline?,
    songKey: String?,
    title: String,
    artist: String,
    applicationId: String,
): String? {
    if (timeline == null || songKey == null || timeline.request.songKey != songKey) return null
    val lines = timeline.lyrics.filter { it.text.isNotBlank() && !isLyricCreditOrNoticeLine(it.text) }
        .sortedBy { it.startTimeMs }
    if (lines.isEmpty() || lines.sumOf { it.text.length.toLong() } > MAX_PAYLOAD_CHARACTERS) return null
    val lyric = lines.joinToString("") { "[${timestamp(it.startTimeMs, timeline.offsetMs)}]${it.text.singleLine()}\n" }
    val rawLyric = lines.mapNotNull { enhancedLine(it, timeline.offsetMs) }.joinToString("")
    val translations = matchTranslationsToLineIndices(
        lines, timeline.translations.filter { it.text.isNotBlank() }.sortedBy { it.startTimeMs },
    )
    val translationLyric = lines.mapIndexedNotNull { index, line ->
        val translation = (translations[index]?.text ?: line.translation)?.takeIf {
            it.isNotBlank() && it.trim() != line.text.trim() &&
                !isLyricCreditOrNoticeLine(it) && !isUntranslatedPlaceholderText(it)
        } ?: return@mapIndexedNotNull null
        "[${timestamp(line.startTimeMs, timeline.offsetMs)}]${translation.singleLine()}\n"
    }.joinToString("")
    val json = JSONObject()
        .put("songName", title)
        .put("artist", artist)
        .put("songId", songKey)
        .put("trackKey", songKey)
        .put("sessionGeneration", timeline.request.generation)
        .put("lyricType", 0)
        .put("noLyric", false)
        .put("provider", applicationId)
        .put("source", applicationId)
        .put("lyric", lyric)
    if (rawLyric.isNotEmpty()) json.put("rawLyric", rawLyric)
    if (translationLyric.isNotEmpty()) json.put("translationLyric", translationLyric)
    return json.toString().takeIf { it.length <= MAX_PAYLOAD_CHARACTERS }
}

private fun enhancedLine(line: LyricEntry, offsetMs: Long): String? {
    val words = line.words?.takeIf { it.isNotEmpty() } ?: return null
    val text = line.text.singleLine()
    val result = StringBuilder("[${timestamp(line.startTimeMs, offsetMs)}]")
    var cursor = 0
    var previousStartMs = line.startTimeMs
    for (word in words) {
        if (word.startTimeMs < previousStartMs || word.endTimeMs <= word.startTimeMs ||
            word.endTimeMs > line.endTimeMs || word.charCount <= 0 || word.charCount > text.length - cursor
        ) return null
        val end = cursor + word.charCount
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) return null
        result.append('<').append(timestamp(word.startTimeMs, offsetMs)).append('>')
            .append(text, cursor, end)
        cursor = end
        previousStartMs = word.startTimeMs
    }
    if (cursor != text.length) return null
    result.append('<').append(timestamp(words.last().endTimeMs, offsetMs)).append(">\n")
    return result.toString()
}

private fun timestamp(timeMs: Long, offsetMs: Long): String {
    val adjusted = try {
        Math.subtractExact(timeMs, offsetMs).coerceAtLeast(0L)
    } catch (_: ArithmeticException) {
        if (offsetMs < 0L) Long.MAX_VALUE else 0L
    }
    return String.format(Locale.ROOT, "%02d:%02d.%03d", adjusted / 60_000L, adjusted / 1000L % 60L, adjusted % 1000L)
}

private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ')
