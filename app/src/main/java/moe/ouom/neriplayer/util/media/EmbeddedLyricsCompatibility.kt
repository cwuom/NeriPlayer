package moe.ouom.neriplayer.util.media

import kotlin.math.abs

private val lrcTimestampRegex = Regex(
    """\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]"""
)

internal const val NERI_ORIGINAL_LYRICS_METADATA_KEY = "NERI_LYRICS_ORIGINAL"
internal const val NERI_ROMANIZED_LYRICS_METADATA_KEY = "NERI_LYRICS_ROMANIZED"
internal const val STANDARD_TRANSLATED_LYRICS_METADATA_KEY = "LYRICS:TRANSLATION"
private const val LYRIC_TRANSLATION_TIMESTAMP_TOLERANCE_MS = 1_500L
private val lrcMetadataLineRegex = Regex("""^\s*\[[^]]+:[^]]*]""")

internal val translatedLyricsMetadataKeys = listOf(
    STANDARD_TRANSLATED_LYRICS_METADATA_KEY,
    "LYRICS_TRANSLATED",
    "NERI_LYRICS_TRANSLATED"
)

internal fun standardLyricsMetadataKeys(audioExtension: String?): List<String> {
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
internal fun mergeLyricsForExternalPlayers(
    lyrics: String?,
    translatedLyrics: String?
): String? {
    val original = lyrics?.trim()?.takeIf(String::isNotBlank)
    val translation = translatedLyrics?.trim()?.takeIf(String::isNotBlank)
    if (original == null) return translation
    if (translation == null) return original

    val originalLines = original.lines()
    val translationLines = normalizeTranslationTimestamps(
        originalLines = originalLines,
        translationLines = translation.lines()
    )
    val translatedByIndex = translationLines.mapIndexed { index, line ->
        index to lyricTimestampKeys(line)
    }
    val emittedTranslationIndexes = BooleanArray(translationLines.size)
    val merged = mutableListOf<String>()

    originalLines.forEach { line ->
        merged += line
        val timestamps = lyricTimestampKeys(line)
        if (timestamps.isEmpty()) return@forEach

        var bestTranslationIndex = -1
        var bestDelta = Long.MAX_VALUE
        translatedByIndex.forEach { (index, translationTimestamps) ->
            if (emittedTranslationIndexes[index] || translationTimestamps.isEmpty()) {
                return@forEach
            }
            val delta = timestamps.minOf { originalTimestamp ->
                translationTimestamps.minOf { translationTimestamp ->
                    abs(originalTimestamp - translationTimestamp)
                }
            }
            if (
                delta <= LYRIC_TRANSLATION_TIMESTAMP_TOLERANCE_MS &&
                delta < bestDelta
            ) {
                bestTranslationIndex = index
                bestDelta = delta
            }
        }
        if (bestTranslationIndex >= 0) {
            emittedTranslationIndexes[bestTranslationIndex] = true
            merged += retimeTranslationLine(
                sourceLine = line,
                translationLine = translationLines[bestTranslationIndex]
            )
        }
    }

    if (emittedTranslationIndexes.none { it } &&
        originalLines.none { lyricTimestampKeys(it).isNotEmpty() } &&
        translationLines.none { lyricTimestampKeys(it).isNotEmpty() }
    ) {
        return mergePlainLyricLines(originalLines, translationLines)
    }

    translationLines.forEachIndexed { index, line ->
        if (!emittedTranslationIndexes[index]) {
            merged += line
        }
    }
    return merged.joinToString("\n")
}

private fun normalizeTranslationTimestamps(
    originalLines: List<String>,
    translationLines: List<String>
): List<String> {
    if (
        originalLines.none { lyricTimestampKeys(it).isNotEmpty() } ||
        translationLines.any { lyricTimestampKeys(it).isNotEmpty() }
    ) {
        return translationLines
    }
    val sourceLines = originalLines.filter { lyricTimestampKeys(it).isNotEmpty() }
    var sourceIndex = 0
    return translationLines.map { line ->
        if (line.isBlank() || lrcMetadataLineRegex.containsMatchIn(line)) {
            line
        } else {
            sourceLines.getOrNull(sourceIndex++)?.let { sourceLine ->
                retimeTranslationLine(sourceLine, line)
            } ?: line
        }
    }
}

private fun retimeTranslationLine(
    sourceLine: String,
    translationLine: String
): String {
    val sourceTimestampPrefix = lrcTimestampRegex.findAll(sourceLine)
        .joinToString(separator = "") { it.value }
    if (sourceTimestampPrefix.isBlank()) {
        return translationLine
    }
    val translationText = translationLine.replace(lrcTimestampRegex, "").trimStart()
    return sourceTimestampPrefix + translationText
}

private fun mergePlainLyricLines(
    originalLines: List<String>,
    translationLines: List<String>
): String {
    return buildList {
        originalLines.forEachIndexed { index, line ->
            add(line)
            translationLines.getOrNull(index)?.let(::add)
        }
        if (translationLines.size > originalLines.size) {
            addAll(translationLines.drop(originalLines.size))
        }
    }.joinToString("\n")
}

private fun lyricTimestampKeys(line: String): Set<Long> {
    return lrcTimestampRegex.findAll(line)
        .mapNotNull { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val fraction = match.groupValues[3]
            val milliseconds = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLongOrNull()?.times(100L)
                2 -> fraction.toLongOrNull()?.times(10L)
                else -> fraction.take(3).toLongOrNull()
            } ?: return@mapNotNull null
            (minutes * 60_000L) + (seconds * 1_000L) + milliseconds
        }
        .toSet()
}
