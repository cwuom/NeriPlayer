package moe.ouom.neriplayer.lyrics.embedded

import kotlin.math.abs
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.parser.flattenWordTimedEntries
import moe.ouom.neriplayer.lyrics.parser.isNeteaseYrc
import moe.ouom.neriplayer.lyrics.parser.isTtmlLyrics
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.lyrics.parser.toEditableLyricsText

// 显式 Unicode 数字让 JVM 与 android 使用相同匹配语义
private val lrcTimestampRegex = Regex(
    """\[(\p{Nd}{1,3}):(\p{Nd}{2})(?:[.:](\p{Nd}{1,3}))?]"""
)

const val NERI_ORIGINAL_LYRICS_METADATA_KEY = "NERI_LYRICS_ORIGINAL"
const val NERI_ROMANIZED_LYRICS_METADATA_KEY = "NERI_LYRICS_ROMANIZED"
const val STANDARD_TRANSLATED_LYRICS_METADATA_KEY = "LYRICS:TRANSLATION"
private const val LYRIC_TRANSLATION_TIMESTAMP_TOLERANCE_MS = 1_500L
private val lrcMetadataLineRegex = Regex("""^\s*\[[^]]+:[^]]*]""")
private val enhancedLrcWordTimestampRegex = Regex("""<\p{Nd}{1,3}:\p{Nd}{2}(?:\.\p{Nd}{1,3})?>""")

val translatedLyricsMetadataKeys = listOf(
    STANDARD_TRANSLATED_LYRICS_METADATA_KEY,
    "LYRICS_TRANSLATED",
    "NERI_LYRICS_TRANSLATED"
)

fun standardLyricsMetadataKeys(audioExtension: String?): List<String> {
    return buildList {
        add("LYRICS")
        when (audioExtension?.lowercase()) {
            "mp3" -> add("UNSYNCEDLYRICS")
            "m4a", "mp4", "aac" -> add("DESCRIPTION")
        }
    }
}

/**
 * keeps translations visible to players that only read one standard lyric tag
 * timed translations are placed immediately after their source line with the
 * same timestamp, which is the conventional dual-language LRC representation
 */
fun mergeLyricsForExternalPlayers(
    lyrics: String?,
    translatedLyrics: String?
): String? {
    val downgraded = lyrics?.let(::downgradeForExternalPlayers)
    val original = downgraded?.lyrics.orEmpty().trim()
    val translation = translatedLyrics?.let(::toLineTimedLyricsForExternalPlayers).orEmpty().trim()
        .ifEmpty { downgraded?.inlineTranslation.orEmpty().trim() }
    if (original.isEmpty()) return translation.takeIf(String::isNotEmpty)
    if (translation.isEmpty()) return original

    val originalLines = original.lineSequence().map(::parseLyricLine).toList()
    val translationLines = normalizeTranslationTimestamps(
        originalLines = originalLines,
        translationLines = translation.lineSequence().map(::parseLyricLine).toList()
    )
    if ((originalLines.asSequence() + translationLines).none { it.timestamps.isNotEmpty() }) {
        return mergePlainLyricLines(originalLines, translationLines)
    }

    val remainingTranslations = translationLines.toMutableList()
    val merged = mutableListOf<String>()
    originalLines.forEach { line ->
        merged += line.raw
        if (line.timestamps.isEmpty()) return@forEach
        remainingTranslations.asSequence()
            .map { candidate -> candidate to line.distanceTo(candidate) }
            .filter { (_, delta) -> delta <= LYRIC_TRANSLATION_TIMESTAMP_TOLERANCE_MS }
            .minWithOrNull(compareBy { it.second })
            ?.let { (candidate, _) ->
                remainingTranslations.remove(candidate)
                merged += retimeTranslationLine(line, candidate).raw
            }
    }
    return (merged.asSequence() + remainingTranslations.asSequence().map { it.raw }).joinToString("\n")
}

/**
 * 车机、蓝牙与第三方播放器只认逐行 LRC，逐字 YRC、TTML 与增强 LRC 在标准字段里会显示成乱码；
 * 这里降级为逐行时间轴，完整格式仍由 [NERI_ORIGINAL_LYRICS_METADATA_KEY] 保留
 */
fun toLineTimedLyricsForExternalPlayers(lyrics: String): String = downgradeForExternalPlayers(lyrics).lyrics

/** TTML 常把翻译内嵌在每一行里，降级时单独取出为同时间戳的逐行翻译，否则标准字段会丢掉全部翻译 */
private fun downgradeForExternalPlayers(lyrics: String): DowngradedLyrics {
    if (!needsExternalPlayerDowngrade(lyrics)) return DowngradedLyrics(lyrics, inlineTranslation = null)
    val entries = parseNeteaseLyricsAuto(lyrics).flattenWordTimedEntries()
    return DowngradedLyrics(
        lyrics = entries.toEditableLyricsText().ifBlank { lyrics },
        inlineTranslation = entries.mapNotNull(LyricEntry::asTranslationLine).toEditableLyricsText()
            .takeIf(String::isNotBlank)
    )
}

private fun needsExternalPlayerDowngrade(lyrics: String): Boolean =
    isTtmlLyrics(lyrics) || isNeteaseYrc(lyrics) || enhancedLrcWordTimestampRegex.containsMatchIn(lyrics)

private fun LyricEntry.asTranslationLine(): LyricEntry? =
    translation?.takeIf(String::isNotBlank)?.let { copy(text = it, translation = null) }

private data class DowngradedLyrics(val lyrics: String, val inlineTranslation: String?)

private fun normalizeTranslationTimestamps(
    originalLines: List<ParsedLyricLine>,
    translationLines: List<ParsedLyricLine>
): List<ParsedLyricLine> {
    if (translationLines.any { it.timestamps.isNotEmpty() }) return translationLines
    val sources = originalLines.asSequence().filter { it.timestamps.isNotEmpty() }.iterator()
    return translationLines.asSequence().map { line ->
        if (line.raw.isBlank() || lrcMetadataLineRegex.containsMatchIn(line.raw) || !sources.hasNext()) {
            line
        } else {
            retimeTranslationLine(sources.next(), line)
        }
    }.toList()
}

private fun retimeTranslationLine(
    sourceLine: ParsedLyricLine,
    translationLine: ParsedLyricLine
): ParsedLyricLine = translationLine.copy(
    raw = sourceLine.timestampPrefix + translationLine.text,
    timestampPrefix = sourceLine.timestampPrefix,
    timestamps = sourceLine.timestamps
)

private fun mergePlainLyricLines(
    originalLines: List<ParsedLyricLine>,
    translationLines: List<ParsedLyricLine>
): String {
    return buildList {
        originalLines.forEachIndexed { index, line ->
            add(line.raw)
            translationLines.getOrNull(index)?.let { add(it.raw) }
        }
        if (translationLines.size > originalLines.size) {
            addAll(translationLines.asSequence().drop(originalLines.size).map { it.raw })
        }
    }.joinToString("\n")
}

private fun parseLyricLine(line: String): ParsedLyricLine {
    val tokens = lrcTimestampRegex.findAll(line).toList()
    // android 的数字匹配也包含 Unicode，数值读取失败时跳过时间键
    val timestamps = tokens.asSequence().mapNotNull { match ->
        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val milliseconds = match.groupValues[3].take(3).padEnd(3, '0').toLongOrNull()
            ?: return@mapNotNull null
        (minutes * 60_000L) + (seconds * 1_000L) + milliseconds
    }.toSet()
    val text = buildString {
        var start = 0
        tokens.forEach { token ->
            append(line, start, token.range.first)
            start = token.range.last + 1
        }
        append(line, start, line.length)
    }.trimStart()
    return ParsedLyricLine(line, tokens.joinToString("") { it.value }, timestamps, text)
}

private data class ParsedLyricLine(
    val raw: String,
    val timestampPrefix: String,
    val timestamps: Set<Long>,
    val text: String
) {
    fun distanceTo(other: ParsedLyricLine): Long = timestamps.asSequence()
        .flatMap { sourceTimestamp ->
            other.timestamps.asSequence().map { timestamp -> abs(sourceTimestamp - timestamp) }
        }
        .minOrNull() ?: Long.MAX_VALUE
}
