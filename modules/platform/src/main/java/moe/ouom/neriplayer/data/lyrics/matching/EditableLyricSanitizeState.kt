package moe.ouom.neriplayer.data.lyrics.matching

internal data class EditableLyricSanitizeContext(
    val title: String,
    val artistTerms: List<String>,
    val album: String
)

internal data class EditableLyricSanitizeLine(
    val index: Int,
    val rawLine: String,
    val text: String,
    val startMs: Long?,
    val durationMs: Long?
)
