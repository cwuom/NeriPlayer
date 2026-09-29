package moe.ouom.neriplayer.api.search.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class CloudMusicSearchResponse(val result: CloudMusicSearchResult?)
@Serializable internal data class CloudMusicSearchResult(val songs: List<CloudMusicSongSummary>? = null)
@Serializable internal data class CloudMusicSongSummary(
    val id: Long,
    val name: String,
    @SerialName("dt") val duration: Long,
    @SerialName("ar") val artists: List<CloudMusicArtist>,
    @SerialName("al") val album: CloudMusicAlbum
)

@Serializable internal data class CloudMusicSongDetail(
    val name: String,
    @SerialName("artists") val artists: List<CloudMusicArtist>,
    @SerialName("album") val album: CloudMusicAlbum,
)

@Serializable internal data class CloudMusicSongDetailResponse(val songs: List<CloudMusicSongDetail>)
@Serializable internal data class CloudMusicArtist(val name: String)
@Serializable internal data class CloudMusicAlbum(val name: String, val picUrl: String?)
@Serializable
internal data class CloudMusicLyricResponse(
    val lrc: CloudMusicLrc?,
    val tlyric: CloudMusicLrc? = null,
    val yrc: CloudMusicLrc? = null,
    val ytlrc: CloudMusicLrc? = null
)

@Serializable
internal data class CloudMusicLrc(val lyric: String?)
