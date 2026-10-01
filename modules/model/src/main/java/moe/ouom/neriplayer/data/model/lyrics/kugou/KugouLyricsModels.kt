package moe.ouom.neriplayer.data.model.lyrics.kugou

data class KugouSongSearchResult(
    val id: String,
    val hash: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long
)

data class KugouLyricCandidate(
    val id: String,
    val accessKey: String,
    val durationMs: Long,
    val score: Int
)

data class KugouLyricsPayload(
    val lyrics: String,
    val translatedLyrics: String? = null
)
