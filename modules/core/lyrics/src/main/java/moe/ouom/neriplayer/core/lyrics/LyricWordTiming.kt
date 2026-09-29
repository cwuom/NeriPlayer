package moe.ouom.neriplayer.core.lyrics

fun hasEditableLyricWordTiming(rawLyric: String): Boolean {
    if (rawLyric.isBlank()) {
        return false
    }
    return runCatching {
        parseNeteaseLyricsAuto(rawLyric).hasWordTimedEntries()
    }.getOrDefault(false)
}
