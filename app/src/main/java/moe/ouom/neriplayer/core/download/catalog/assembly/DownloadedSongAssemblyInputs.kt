package moe.ouom.neriplayer.core.download.catalog.assembly

internal data class DownloadedSongFileInfo(
    val reference: String,
    val playbackUri: String,
    val logicalName: String,
    val sizeBytes: Long,
    val downloadTime: Long,
    val parsedTitle: String,
    val parsedArtist: String
)

internal data class DownloadedSongCoverInfo(
    val reference: String?,
    val coverUrl: String?,
    val customCoverUrl: String?,
    val originalCoverUrl: String?
)

internal data class DownloadedSongLocalMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUri: String? = null,
    val lyricContent: String? = null,
    val originalTitle: String? = null,
    val originalArtist: String? = null,
    val sourceStableKey: String? = null
)
