package moe.ouom.neriplayer.core.player.service

import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry

internal const val LIVE_UPDATE_COMPACT_MAX_CODE_POINTS = 7

private val liveLyricWhitespace = Regex("\\s+")

/** Text payloads used by the Android 16 Live Update notification and its compact chip. */
internal data class LiveLyricNotificationText(
    val lyric: String,
    val fullLyric: String,
    val compactLyric: String,
    val allowLongCompactLyric: Boolean = false
)

/**
 * Normalizes a parsed lyric line without changing the source entry. Neri's lyric model keeps
 * character timing but not individual word text, so the safe fallback is the complete current
 * line rather than attempting to manufacture a word window.
 */
internal fun buildLiveLyricNotificationText(
    line: LyricEntry,
    selectedText: String? = line.text
): LiveLyricNotificationText? {
    val normalized = normalizeLiveLyricText(selectedText.orEmpty())
    if (normalized.isBlank()) return null
    return LiveLyricNotificationText(
        lyric = normalized,
        fullLyric = normalized,
        compactLyric = compactLiveLyricText(normalized),
    )
}

internal fun buildLiveLyricSecondaryText(text: String?): String? =
    text?.let(::normalizeLiveLyricText)?.takeIf { it.isNotBlank() }

/** Keeps the compact chip short without splitting a surrogate pair. */
internal fun compactLiveLyricText(
    text: String,
    preserveLongToken: Boolean = false
): String {
    val normalized = normalizeLiveLyricText(text)
    if (normalized.isBlank()) return ""
    if (liveLyricCodePointCount(normalized) <= LIVE_UPDATE_COMPACT_MAX_CODE_POINTS) {
        return normalized
    }
    if (preserveLongToken && normalized.none(Char::isWhitespace)) return normalized
    val visibleCount = (LIVE_UPDATE_COMPACT_MAX_CODE_POINTS - 1).coerceAtLeast(1)
    return normalized.takeCodePoints(visibleCount) + "…"
}

private fun normalizeLiveLyricText(text: String): String =
    text.replace(liveLyricWhitespace, " ").trim()

private fun String.takeCodePoints(count: Int): String {
    if (count <= 0 || isEmpty()) return ""
    val end = offsetByCodePoints(0, count.coerceAtMost(codePointCount(0, length)))
    return substring(0, end)
}

private fun liveLyricCodePointCount(text: String): Int =
    text.codePointCount(0, text.length)
