package moe.ouom.neriplayer.platform.lyrics.matching

fun chooseQQMusicLyrics(
    qqLyric: String?,
    qqTranslatedLyric: String?,
    amllLyric: String?
): Pair<String?, String?> {
    return if (amllLyric.isNullOrBlank()) {
        qqLyric to qqTranslatedLyric
    } else {
        amllLyric to null
    }
}
