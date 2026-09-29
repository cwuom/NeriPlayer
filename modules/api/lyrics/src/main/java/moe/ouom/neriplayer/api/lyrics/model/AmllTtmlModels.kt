package moe.ouom.neriplayer.api.lyrics.model


data class AmllTtmlLyrics(
    val lyrics: String,
    val file: String,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String = ""
)

data class AmllTtmlSearchResult(
    val file: String,
    val title: String,
    val titles: List<String>,
    val artist: String,
    val artists: List<String>,
    val albums: List<String>,
    val ncmIds: List<String>,
    val qqIds: List<String>,
    val score: Int
)
