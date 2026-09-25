package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLrc

internal fun hasDisplayableLyricTranslation(
    rawTranslatedLyrics: String?,
    translatedLyrics: List<LyricEntry>,
    lyrics: List<LyricEntry>
): Boolean {
    if (translatedLyrics.any { it.text.isNotBlank() } ||
        lyrics.any { !it.translation.isNullOrBlank() }
    ) return true
    val raw = rawTranslatedLyrics?.takeIf(String::isNotBlank) ?: return false
    return runCatching { parseNeteaseLrc(raw).any { it.text.isNotBlank() } }
        .getOrDefault(false)
}

internal enum class LyricsSecondaryLineMode {
    TRANSLATION,
    PHONETIC,
    NONE
}

internal fun resolveLyricsSecondaryLineMode(
    showSecondaryLine: Boolean,
    preferPhonetic: Boolean,
    hasTranslation: Boolean,
    hasPhonetic: Boolean
): LyricsSecondaryLineMode = when {
    !showSecondaryLine -> LyricsSecondaryLineMode.NONE
    preferPhonetic && hasPhonetic -> LyricsSecondaryLineMode.PHONETIC
    hasTranslation -> LyricsSecondaryLineMode.TRANSLATION
    hasPhonetic -> LyricsSecondaryLineMode.PHONETIC
    else -> LyricsSecondaryLineMode.NONE
}

internal fun nextLyricsSecondaryLineMode(
    current: LyricsSecondaryLineMode,
    hasTranslation: Boolean,
    hasPhonetic: Boolean
): LyricsSecondaryLineMode {
    val availableModes = buildList {
        if (hasTranslation) add(LyricsSecondaryLineMode.TRANSLATION)
        if (hasPhonetic) add(LyricsSecondaryLineMode.PHONETIC)
    }
    if (availableModes.isEmpty()) return LyricsSecondaryLineMode.NONE
    val cycle = availableModes + LyricsSecondaryLineMode.NONE
    return cycle[(cycle.indexOf(current) + 1).mod(cycle.size)]
}
