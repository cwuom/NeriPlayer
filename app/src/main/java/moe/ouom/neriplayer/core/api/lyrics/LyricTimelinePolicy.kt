package moe.ouom.neriplayer.core.api.lyrics

private const val MIN_LINES_FOR_COLLAPSED_TIMELINE = 3

private val lrcTimestampRegex = Regex(
    """\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]"""
)

internal fun isUsableTimedLyricTimeline(rawLyric: String): Boolean {
    val timestampedLines = parseTimestampedLyricLines(rawLyric)
    return timestampedLines.isNotEmpty() && !timestampedLines.hasCollapsedTimeline()
}

internal fun hasCollapsedTimedLyricTimeline(rawLyric: String): Boolean {
    return parseTimestampedLyricLines(rawLyric).hasCollapsedTimeline()
}

internal fun extractPlainLyricsFromCollapsedTimedLyrics(rawLyric: String): String? {
    if (!hasCollapsedTimedLyricTimeline(rawLyric)) {
        return null
    }
    return rawLyric.lineSequence()
        .map { lrcTimestampRegex.replace(it, "").trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .takeIf { it.isNotBlank() }
}

internal fun hasLrcTimestamp(rawLyric: String): Boolean {
    return lrcTimestampRegex.containsMatchIn(rawLyric)
}

private fun parseTimestampedLyricLines(rawLyric: String): List<List<Long>> {
    return buildList {
        rawLyric.lineSequence().forEach { line ->
            val matches = lrcTimestampRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val lyricText = lrcTimestampRegex.replace(line, "").trim()
            if (lyricText.isBlank()) return@forEach

            val timestamps = matches.mapNotNull(::parseLrcTimestampMs)
            if (timestamps.isNotEmpty()) add(timestamps)
        }
    }
}

private fun List<List<Long>>.hasCollapsedTimeline(): Boolean {
    if (size < MIN_LINES_FOR_COLLAPSED_TIMELINE) {
        return false
    }
    return asSequence()
        .flatten()
        .distinct()
        .take(2)
        .count() < 2
}

private fun parseLrcTimestampMs(match: MatchResult): Long? {
    val minutes = match.groupValues[1].toLongOrNull() ?: return null
    val seconds = match.groupValues[2].toLongOrNull() ?: return null
    if (seconds !in 0L..59L) return null
    val fraction = match.groupValues[3]
    val milliseconds = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLongOrNull()?.times(100L)
        2 -> fraction.toLongOrNull()?.times(10L)
        else -> fraction.toLongOrNull()
    } ?: return null
    return (minutes * 60_000L) + (seconds * 1_000L) + milliseconds
}
