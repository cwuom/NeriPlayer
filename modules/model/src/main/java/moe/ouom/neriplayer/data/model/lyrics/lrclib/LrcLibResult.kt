package moe.ouom.neriplayer.data.model.lyrics.lrclib

data class LrcLibResult(
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val trackName: String = "",
    val artistName: String = "",
    val durationSeconds: Long? = null,
    val plainLyricsRecoveredFromCollapsedTimeline: Boolean = false
)
