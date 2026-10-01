package moe.ouom.neriplayer.data.model.playback.storage

sealed interface PlayerLocalPlaybackResolution {
    data class Playable(val reference: String) : PlayerLocalPlaybackResolution
    data object NotIndexed : PlayerLocalPlaybackResolution
    data object Missing : PlayerLocalPlaybackResolution
    data class TemporarilyUnavailable(val evidence: String) : PlayerLocalPlaybackResolution
}

data class PlayerDownloadedLyrics(
    val lyric: String?,
    val translatedLyric: String?,
    val romanizedLyric: String?,
    val hasOriginalSidecar: Boolean,
    val hasTranslatedSidecar: Boolean,
    val hasRomanizedSidecar: Boolean,
)

data class PlayerDownloadedArtwork(val coverPath: String?, val coverUrl: String?)

data class PlayerMetadataClearRequest(
    val title: Boolean = false,
    val artist: Boolean = false,
    val cover: Boolean = false,
    val lyrics: Boolean = false,
    val userLyricOffset: Boolean = false,
)

enum class PlayerDownloadedMetadataSyncOutcome { SUCCESS, NOT_DOWNLOADED, FAILED }
