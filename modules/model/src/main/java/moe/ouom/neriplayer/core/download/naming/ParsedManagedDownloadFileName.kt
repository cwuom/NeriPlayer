package moe.ouom.neriplayer.core.download.naming

data class ParsedManagedDownloadFileName(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: String? = null,
    val songId: String? = null,
    val audioId: String? = null,
    val subAudioId: String? = null
)
