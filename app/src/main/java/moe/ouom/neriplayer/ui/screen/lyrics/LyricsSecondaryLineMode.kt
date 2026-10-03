package moe.ouom.neriplayer.ui.screen.lyrics

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLrc

internal fun resolveEffectivePhoneticLyrics(
    rawPhoneticLyrics: String?,
    phoneticLyrics: List<LyricEntry>,
    embeddedPhoneticLyrics: List<LyricEntry>
): List<LyricEntry> {
    if (rawPhoneticLyrics != null) return phoneticLyrics
    return phoneticLyrics.takeIf { it.isNotEmpty() } ?: embeddedPhoneticLyrics
}

internal fun hasDisplayableLyricTranslation(
    rawTranslatedLyrics: String?,
    translatedLyrics: List<LyricEntry>,
    lyrics: List<LyricEntry>
): Boolean {
    if (rawTranslatedLyrics != null) return hasSuppliedTranslation(rawTranslatedLyrics, translatedLyrics)
    if (translatedLyrics.any { it.text.isNotBlank() } ||
        lyrics.any { !it.translation.isNullOrBlank() }
    ) return true
    return false
}

private fun hasSuppliedTranslation(raw: String, translated: List<LyricEntry>): Boolean {
    if (raw.isBlank()) return false
    if (translated.any { it.text.isNotBlank() }) return true
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
