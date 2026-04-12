package moe.ouom.neriplayer.ui.component

import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

fun List<LyricEntry>.flattenWordTimedEntries(): List<LyricEntry> {
    if (none { !it.words.isNullOrEmpty() }) {
        return this
    }
    return map { entry ->
        if (entry.words.isNullOrEmpty()) {
            entry
        } else {
            entry.copy(words = null)
        }
    }
}

fun List<LyricEntry>.hasWordTimedEntries(): Boolean = any { !it.words.isNullOrEmpty() }

internal data class LyricsEditorSeed(
    val lyrics: String,
    val translatedLyrics: String
)

internal fun resolveStoredLyricText(
    currentLyric: String?,
    legacyLyric: String?
): String? {
    return if (currentLyric != null) currentLyric else legacyLyric
}

fun List<LyricEntry>.toEditableLyricsText(): String {
    if (isEmpty()) {
        return ""
    }
    return joinToString("\n") { entry ->
        if (entry.words.isNullOrEmpty()) {
            entry.toLrcText()
        } else {
            entry.toYrcText()
        }
    }
}

fun resolvePreferredLyricContent(
    matchedLyric: String?,
    preferredNeteaseLyric: String,
    legacyLyric: String? = null
): String? {
    val storedLyric = resolveStoredLyricText(
        currentLyric = matchedLyric,
        legacyLyric = legacyLyric
    )
    if (storedLyric != null) {
        return storedLyric
    }
    return preferredNeteaseLyric.takeIf { it.isNotBlank() }
}

internal fun resolveLyricsEditorInitialText(
    matchedLyric: String?,
    preferredNeteaseLyric: String,
    displayedLyricsText: String,
    displayedHasWordTimedEntries: Boolean,
    fallbackLyricsText: String?,
    legacyLyric: String? = null
): String {
    resolveStoredLyricText(
        currentLyric = matchedLyric,
        legacyLyric = legacyLyric
    )?.let {
        return it
    }
    if (displayedHasWordTimedEntries) {
        return displayedLyricsText
    }
    return preferredNeteaseLyric.takeIf { it.isNotBlank() } ?: fallbackLyricsText ?: displayedLyricsText
}

internal fun resolveLyricsEditorSeed(
    song: SongItem,
    preparedLyrics: String? = null,
    preparedTranslatedLyrics: String? = null
): LyricsEditorSeed {
    return LyricsEditorSeed(
        lyrics = preparedLyrics ?: resolveStoredLyricText(
            currentLyric = song.matchedLyric,
            legacyLyric = song.originalLyric
        ).orEmpty(),
        translatedLyrics = preparedTranslatedLyrics ?: resolveStoredLyricText(
            currentLyric = song.matchedTranslatedLyric,
            legacyLyric = song.originalTranslatedLyric
        ).orEmpty()
    )
}

private fun LyricEntry.toLrcText(): String {
    val minutes = startTimeMs / 60_000
    val seconds = (startTimeMs % 60_000) / 1_000
    val millis = (startTimeMs % 1_000) / 10
    return "[%02d:%02d.%02d]%s".format(minutes, seconds, millis, text)
}

private fun LyricEntry.toYrcText(): String {
    val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(0L)
    return buildString {
        append("[")
        append(startTimeMs)
        append(",")
        append(durationMs)
        append("]")
        words.orEmpty().forEachIndexed { index, word ->
            append("(")
            append(word.startTimeMs)
            append(",")
            append((word.endTimeMs - word.startTimeMs).coerceAtLeast(0L))
            append(",0)")
            append(extractWordContent(index))
        }
    }
}

private fun LyricEntry.extractWordContent(index: Int): String {
    val safeWords = words.orEmpty()
    if (safeWords.isEmpty()) {
        return text
    }

    var cursor = 0
    safeWords.forEachIndexed { currentIndex, word ->
        val requestedLength = word.charCount.coerceAtLeast(0)
        val isLast = currentIndex == safeWords.lastIndex
        val endExclusive = when {
            isLast -> text.length
            requestedLength == 0 -> cursor
            else -> (cursor + requestedLength).coerceAtMost(text.length)
        }
        if (currentIndex == index) {
            return text.substring(cursor.coerceAtMost(text.length), endExclusive)
        }
        cursor = endExclusive
    }
    return ""
}
