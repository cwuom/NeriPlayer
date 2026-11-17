package moe.ouom.neriplayer.core.player.service.lyrics.coloros

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.parser.isLyricCreditOrNoticeLine
import java.util.Locale

private const val MAX_JOVI_LRC_CHARACTERS = 128 * 1024

internal fun joviCarLyricPayload(timeline: ColorOsLyricTimeline?, songKey: String): String? {
    if (timeline == null || timeline.request.songKey != songKey) return null
    val lyrics = timeline.lyrics.asSequence()
        .filter { it.text.isNotBlank() && !isLyricCreditOrNoticeLine(it.text) }
        .sortedBy(LyricEntry::startTimeMs)
        .toList()
    if (lyrics.isEmpty() || lyrics.sumOf { it.text.length.toLong() } > MAX_JOVI_LRC_CHARACTERS) return null

    return lyrics.joinToString(separator = "\n") { line ->
        "[${formatLrcTimestamp(adjustForOffset(line.startTimeMs, timeline.offsetMs))}]${line.text.replace('\r', ' ').replace('\n', ' ')}"
    }.takeIf(String::isNotBlank)
}

private fun adjustForOffset(timeMs: Long, offsetMs: Long): Long = try {
    Math.subtractExact(timeMs, offsetMs).coerceAtLeast(0L)
} catch (_: ArithmeticException) {
    if (offsetMs < 0L) Long.MAX_VALUE else 0L
}

private fun formatLrcTimestamp(timeMs: Long): String = String.format(
    Locale.ROOT,
    "%02d:%02d.%03d",
    timeMs.coerceAtLeast(0L) / 60_000L,
    timeMs.coerceAtLeast(0L) / 1_000L % 60L,
    timeMs.coerceAtLeast(0L) % 1_000L,
)
