package moe.ouom.neriplayer.data.model.lyrics

enum class LyricsEditorSource {
    SIDECAR,
    EMBEDDED
}

data class LyricsEditorSeed(
    val lyrics: String,
    val translatedLyrics: String,
    val romanizedLyrics: String = "",
    val sidecarLyrics: String = lyrics,
    val sidecarTranslatedLyrics: String = translatedLyrics,
    val sidecarRomanizedLyrics: String = romanizedLyrics,
    val embeddedLyrics: String = lyrics,
    val embeddedTranslatedLyrics: String = translatedLyrics,
    val embeddedRomanizedLyrics: String = romanizedLyrics,
    val hasSidecar: Boolean = false,
    val hasEmbeddedLyrics: Boolean = false,
    val source: LyricsEditorSource = LyricsEditorSource.SIDECAR
)
